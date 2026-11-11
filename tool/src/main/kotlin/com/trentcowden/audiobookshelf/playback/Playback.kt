package com.trentcowden.audiobookshelf.playback

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AuthStore
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.api.PlaybackSession
import com.trentcowden.audiobookshelf.api.SessionGoneException
import com.trentcowden.audiobookshelf.downloads.DownloadedBook
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.downloads.LibraryCache
import com.trentcowden.audiobookshelf.downloads.LocalProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the player UI renders. Times are milliseconds on the book's global timeline. */
data class NowPlaying(
    val itemId: String,
    val title: String,
    val author: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val chapters: List<Chapter>,
    /** True while the book is being opened or the player is (re)connecting. */
    val loading: Boolean = false,
    /** True when the server can't be reached; progress is kept on the phone until it can. */
    val offline: Boolean = false,
)

/**
 * The tool's single audio player, shared by every screen and alive for the
 * whole process — **not** owned by a screen's view model.
 *
 * Why: the SDK allows one detached player handle per process, and a handle
 * that is never released holds that slot (and keeps the playback service and
 * process alive) forever. fenleon's Audiobooks kept the handle in the Player
 * screen's view model; LightActivity has no onDestroy, so when Android tears
 * the activity down the view model is never cleared, the handle leaks, every
 * later `newPlayer()` throws, and every tap silently does nothing until the
 * phone reboots. Here:
 *
 * - exactly one handle exists, owned by this object, created on demand;
 * - it is released when the activity goes away ([onActivityGone]), so it
 *   never outlives the activity it was bound through (see light-sdk #299);
 * - it is re-checked before every use and rebuilt when it's dead;
 * - a Play that doesn't start within a few seconds rebuilds the player and
 *   retries once, then shows an error instead of doing nothing;
 * - [reset] is a user-visible escape hatch that tears everything down.
 *
 * Books play only from downloads. Progress is saved on the phone every few
 * seconds and synced to Audiobookshelf through a playback session whenever
 * the server is reachable (every [SYNC_INTERVAL_MS] while playing, on pause,
 * and when switching books). Offline, it waits on the phone and is pushed
 * when the connection comes back — unless the server has something newer.
 */
object Playback {
    private const val SYNC_INTERVAL_MS = 15_000L
    private const val LOCAL_SAVE_INTERVAL_MS = 5_000L
    private const val RECONNECT_INTERVAL_MS = 60_000L
    private const val READY_TIMEOUT_MS = 4_000L
    private const val PLAY_START_TIMEOUT_MS = 6_000L
    private const val REWIND_AFTER_PAUSE_MS = 5 * 60_000L
    private const val REWIND_MS = 15_000L
    private const val END_EPSILON_MS = 2_000L
    private const val SEEK_DEBOUNCE_MS = 300L
    private const val NETWORK_TIMEOUT_MS = 8_000L
    /** Resuming after a pause this long first checks whether another device moved on. */
    private const val CHECK_SERVER_AFTER_PAUSE_MS = 60_000L
    private const val RESUME_CHECK_TIMEOUT_MS = 4_000L

    /**
     * Speech, so the lock screen and headset buttons get seek back/forward
     * (Music would get previous/next track instead — useless for a one-file
     * audiobook).
     */
    private val PLAYER_SPEC = LightPlayerSpec(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached)

    /**
     * Fallback when the SDK refuses [PLAYER_SPEC]: if a headset button revived
     * the playback service by itself, it comes back as Music, and a detached
     * session can't change usage until it ends. Playing as Music beats not
     * playing at all.
     */
    private val FALLBACK_SPEC = LightPlayerSpec(usage = LightAudioUsage.Music, playback = LightAudioPlayback.Detached)

    /**
     * Anything unexpected (e.g. a command racing a released handle, which the
     * SDK reports by throwing) becomes a visible error and a dropped handle —
     * never a crash, and never a silently dead player.
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("AbsPlayback", "playback error", e)
        _error.value = "Player error: ${e.message ?: e.toString()}. Tap play to retry."
        _state.update { it?.copy(loading = false) }
        observation?.cancel()
        observation = null
        handle?.let { h -> handle = null; runCatching { h.release() } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    /** Serializes everything that touches the handle, the book or the session. */
    private val lock = Mutex()

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last user-facing playback problem; cleared by the next successful action. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // ---- Handle --------------------------------------------------------------

    private var audio: LightAudio? = null
    /** Which activity [audio] belongs to (see ActivityWatch). */
    private var audioGeneration = -1
    private var handle: LightAudioPlayer? = null
    /** Which activity [handle] was created through. */
    private var handleGeneration = -1
    private var observation: Job? = null
    private var seekJob: Job? = null

    // ---- Book & server session ------------------------------------------------

    /** The downloaded book loaded on the player. */
    private var book: DownloadedBook? = null
    /** Server playback session used for syncing; null while offline. */
    private var session: PlaybackSession? = null
    /** The server dropped the session; sync by writing progress directly. */
    private var sessionStale = false
    private var pendingSeekWithinTrackMs: Long? = null
    private var listenedMsSinceSync = 0L
    private var lastTickAt = 0L
    private var lastSyncAt = 0L
    private var lastLocalSaveAt = 0L
    /** Position written by the last [saveLocal]; unchanged positions aren't re-saved. */
    private var lastSavedPositionMs = -1L
    private var lastReconnectAt = 0L
    private var pausedAt = 0L
    private var syncJob: Job? = null

    /**
     * Called by every screen when it shows. Remembers how to build a player
     * from the current activity; reconnects to live playback if a book is loaded.
     */
    fun attach(lightAudio: LightAudio, generation: Int) {
        audio = lightAudio
        audioGeneration = generation
        _speed.value = SettingsStore.speed
        val staleHandle = handle != null && handleGeneration != generation
        if (staleHandle || (book != null && handle == null)) {
            scope.launch {
                lock.withLock {
                    // Never reuse a handle bound through a previous activity.
                    if (handle != null && handleGeneration != audioGeneration) dropHandle()
                    if (book != null) ensureHandle()
                }
            }
        }
    }

    /**
     * The activity is being destroyed. Release the handle *now*, while its
     * binding is still valid — detached playback carries on in the SDK's
     * service, and the next activity reconnects with a fresh handle.
     */
    fun onActivityGone(generation: Int) {
        if (generation == audioGeneration) audio = null
        // A newer activity already owns the player — nothing of ours to release.
        if (generation != handleGeneration) return
        val h = handle ?: return
        // Capture where we are before the handle goes.
        refreshFromHandle(h)
        observation?.cancel()
        observation = null
        handle = null
        runCatching { h.release() }
        saveLocal()
        syncNow()
        // Nothing observes the detached player now: stop ticking listening time
        // and syncing a frozen position. The next attach reads the real state.
        _state.update { it?.copy(playing = false) }
        lastTickAt = 0
    }

    /** Returns a live, connected handle — building a new one if needed. Call under [lock]. */
    private suspend fun ensureHandle(): LightAudioPlayer? {
        handle?.let { h ->
            when (h.availability.value) {
                LightAudioPlayerAvailability.Ready -> return h
                LightAudioPlayerAvailability.Initializing ->
                    if (withTimeoutOrNull(READY_TIMEOUT_MS) { h.awaitReady() } == true) return h
                else -> Unit
            }
            dropHandle()
        }
        val lightAudio = audio ?: run {
            _error.value = "Player unavailable. Reopen the tool."
            return null
        }
        val h = try {
            lightAudio.newPlayer(PLAYER_SPEC)
        } catch (first: Throwable) {
            try {
                lightAudio.newPlayer(FALLBACK_SPEC)
            } catch (e: Throwable) {
                _error.value = "Couldn't start the audio player (${first.message}). Try Settings → Reset Player."
                return null
            }
        }
        handle = h
        handleGeneration = audioGeneration
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { h.awaitReady() } != true) {
            dropHandle()
            _error.value = "The audio player didn't respond. Try again, or Settings → Reset Player."
            return null
        }
        h.speed = _speed.value
        observe(h)
        if (book != null && h.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
            // The playback service stopped since we last looked (idle timeout
            // or a crash), taking its queue with it. Keep the book and position
            // so the next Play re-queues from there.
            _state.update { it?.copy(playing = false) }
        } else {
            refreshFromHandle(h)
        }
        return h
    }

    private fun dropHandle() {
        observation?.cancel()
        observation = null
        val h = handle ?: return
        handle = null
        runCatching { h.release() }
    }

    private fun observe(h: LightAudioPlayer) {
        observation?.cancel()
        val errorAtConnect = h.error.value
        observation = scope.launch {
            launch {
                var previous: Boolean? = null
                h.isPlaying.collect { playing ->
                    tickListened(wasPlaying = _state.value?.playing == true)
                    _state.update { it?.copy(playing = playing) }
                    if (!playing) {
                        // Only a pause we saw happen counts for rewind-after-pause.
                        if (previous == true) {
                            pausedAt = System.currentTimeMillis()
                            saveLocal()
                            syncNow()
                        }
                    } else {
                        _error.value = null
                    }
                    previous = playing
                }
            }
            launch { h.positionMs.collect { refreshFromHandle(h) } }
            launch { h.currentMediaItemIndex.collect { refreshFromHandle(h) } }
            launch {
                h.durationMs.collect { duration ->
                    if (duration > 0) applyPendingSeek(h)
                }
            }
            launch {
                h.error.collect { e ->
                    if (e != null && e !== errorAtConnect && book != null) {
                        _state.update { it?.copy(loading = false, playing = false) }
                        _error.value = "Couldn't play the downloaded file (${e.diagnostic}). " +
                            "Try removing the download and downloading it again."
                    }
                }
            }
        }
    }

    /** Maps the handle's (track index, offset) onto the book timeline. */
    private fun refreshFromHandle(h: LightAudioPlayer) {
        val b = book ?: return
        val index = h.currentMediaItemIndex.value
        if (index == NO_MEDIA_ITEM) return
        val track = b.tracks.getOrNull(index) ?: return
        // While a seek is pending the handle still reports the old offset.
        val within = pendingSeekWithinTrackMs ?: h.positionMs.value.coerceAtLeast(0)
        _state.update {
            it?.copy(positionMs = (track.startOffset * 1000).toLong() + within, playing = h.isPlaying.value)
        }
    }

    private fun applyPendingSeek(h: LightAudioPlayer) {
        val pending = pendingSeekWithinTrackMs ?: return
        if (h.durationMs.value <= 0) return
        pendingSeekWithinTrackMs = null
        h.seekTo(pending)
    }

    // ---- Commands --------------------------------------------------------------

    /**
     * Plays a downloaded book from wherever you left off (on this phone or
     * another device, whichever is newer). If it's already loaded this resumes.
     */
    fun playBook(itemId: String) {
        scope.launch {
            lock.withLock {
                if (book?.itemId == itemId) resumeLocked() else openLocked(itemId)
            }
        }
    }

    fun togglePlay() {
        scope.launch {
            lock.withLock {
                if (_state.value?.playing == true) {
                    ensureHandle()?.pause()
                } else {
                    resumeLocked()
                }
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val now = _state.value ?: return
        seekTo(now.positionMs + deltaMs)
    }

    /** Seeks on the book's timeline (crossing track boundaries for multi-file books). */
    fun seekTo(targetMs: Long) {
        val b0 = book ?: return
        val clamped = targetMs.coerceIn(0, (b0.durationSec * 1000).toLong().coerceAtLeast(0))
        // Show the target immediately; seek once the user stops dragging.
        _state.update { it?.copy(positionMs = clamped) }
        pausedAt = 0
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(SEEK_DEBOUNCE_MS)
            lock.withLock {
                val b = book ?: return@withLock
                val h = ensureHandle() ?: return@withLock
                if (h.currentMediaItemIndex.value == NO_MEDIA_ITEM) return@withLock // applied on Play
                seekHandle(h, b, clamped)
                saveLocal()
                syncNow()
            }
        }
    }

    fun setSpeed(value: Float) {
        _speed.value = value
        SettingsStore.speed = value
        handle?.takeIf { it.availability.value == LightAudioPlayerAvailability.Ready }?.speed = value
    }

    /** Stops and unloads [itemId] if it's the loaded book (its download is being removed). */
    fun unload(itemId: String) {
        scope.launch {
            lock.withLock {
                if (book?.itemId != itemId) return@withLock
                handle?.takeIf { it.availability.value == LightAudioPlayerAvailability.Ready }
                    ?.let { runCatching { it.stop() } }
                closeSessionLocked()
                book = null
                _state.value = null
                _error.value = null
            }
        }
    }

    /**
     * Escape hatch (Settings → Reset Player): stops playback, releases the
     * player, closes the server session. Everything starts fresh after this.
     */
    fun reset() {
        scope.launch { lock.withLock { resetLocked() } }
    }

    private suspend fun resetLocked() {
        // Get a connected handle (building one if needed) so stop() actually
        // reaches the playback service instead of being dropped.
        ensureHandle()?.let { h -> runCatching { h.stop() } }
        closeSessionLocked()
        book = null
        dropHandle()
        _state.value = null
        _error.value = null
        // Prove a player can be created again. If the SDK still refuses (a
        // detached slot held by something we can't reach), the only remaining
        // fix is a fresh process — LightOS has no force-stop, so do it for the
        // user. The tool closes; reopening starts clean.
        val lightAudio = audio ?: return
        val probe = runCatching { lightAudio.newPlayer(PLAYER_SPEC) }.getOrNull()
            ?: runCatching { lightAudio.newPlayer(FALLBACK_SPEC) }.getOrNull()
        if (probe == null) {
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        withTimeoutOrNull(READY_TIMEOUT_MS) { probe.awaitReady() }
        runCatching { probe.release() }
    }

    /**
     * Sign-out: stop, save the final position and close the session while the
     * sign-in still works, then forget it. [onDone] runs on the main thread.
     */
    fun signOut(onDone: () -> Unit) {
        scope.launch {
            lock.withLock {
                handle?.takeIf { it.availability.value == LightAudioPlayerAvailability.Ready }
                    ?.let { runCatching { it.stop() } }
                closeSessionLocked()
                book = null
                dropHandle()
                _state.value = null
                _error.value = null
            }
            val auth = AuthStore.current
            AuthStore.clear()
            LibraryCache.clear()
            LocalProgress.clear()
            if (auth != null) {
                withContext(Dispatchers.IO + NonCancellable) {
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { AbsApi.logout(auth) }
                }
            }
            onDone()
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Pushes progress from offline listening to the server (books other than
     * the loaded one). Called when the library refreshes successfully.
     */
    fun flushOfflineProgress() {
        scope.launch(Dispatchers.IO) {
            val loaded = book?.itemId
            LocalProgress.unsynced().forEach { (itemId, local) ->
                if (itemId != loaded) runCatching { reconcileItem(itemId, local.durationSec) }
            }
        }
    }

    // ---- Internals (call under lock) -------------------------------------------

    private suspend fun resumeLocked() {
        val b = book ?: return
        val pausedLong = pausedAt == 0L || System.currentTimeMillis() - pausedAt >= CHECK_SERVER_AFTER_PAUSE_MS
        if (session == null || pausedLong) {
            // Another device may have moved this book on while it sat paused here.
            saveLocal()
            val result = withTimeoutOrNull(RESUME_CHECK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runCatching { reconcile(b) } }
            }
            // Offline: play straight away from the phone's position.
            val reachable = result?.isSuccess == true
            val serverSec = result?.getOrNull()
            if (serverSec != null) {
                val serverMs = (serverSec * 1000).toLong()
                if (kotlin.math.abs(serverMs - (_state.value?.positionMs ?: 0)) > 3_000) {
                    _state.update { it?.copy(positionMs = serverMs) }
                    lastSavedPositionMs = serverMs
                    pausedAt = 0 // jumping to someone else's spot: no rewind
                }
            }
            if (session == null && reachable) tryOpenSession(b)
        }
        val now = _state.value ?: return
        val h = ensureHandle() ?: return
        if (h.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
            // Service lost its queue — re-queue where we were.
            loadQueue(h, b, rewindIfLongPause(now.positionMs))
            startAndVerify(h)
            return
        }
        var target = now.positionMs
        if (b.durationSec > 0 && target >= (b.durationSec * 1000).toLong() - END_EPSILON_MS) target = 0 // finished → restart
        target = rewindIfLongPause(target)
        if (target != now.positionMs) seekHandle(h, b, target)
        startAndVerify(h)
    }

    private fun rewindIfLongPause(positionMs: Long): Long =
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt >= REWIND_AFTER_PAUSE_MS) {
            (positionMs - REWIND_MS).coerceAtLeast(0)
        } else {
            positionMs
        }

    private suspend fun openLocked(itemId: String) {
        _error.value = null
        val b = Downloads.book(itemId) ?: run {
            _error.value = "Download this book first."
            return
        }
        // Silence and close out whatever was loaded, saving its progress.
        handle?.takeIf { it.availability.value == LightAudioPlayerAvailability.Ready }
            ?.let { runCatching { it.pause() } }
        closeSessionLocked()
        book = b
        val local = LocalProgress.get(itemId)
        _state.value = NowPlaying(
            itemId = itemId,
            title = b.title,
            author = b.author,
            durationMs = (b.durationSec * 1000).toLong(),
            positionMs = ((local?.positionSec ?: 0.0) * 1000).toLong(),
            playing = false,
            chapters = b.chapters,
            loading = true,
        )
        // Online: newest of phone/server wins, then open a sync session.
        // Offline: newest of the phone's copy and the last library listing.
        val reconciled = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { runCatching { reconcile(b) ?: (LocalProgress.get(itemId)?.positionSec ?: 0.0) }.getOrNull() }
        }
        val startSec = reconciled ?: run {
            val cached = LibraryCache.load()?.progress?.get(itemId)
            when {
                cached != null && (local == null || cached.lastUpdate > local.updatedAt) -> cached.currentTime
                else -> local?.positionSec ?: 0.0
            }
        }
        session = null
        if (reconciled != null) tryOpenSession(b)
        resetSyncClock()
        lastSavedPositionMs = (startSec * 1000).toLong()
        var target = (startSec * 1000).toLong()
        if (b.durationSec > 0 && target >= (b.durationSec * 1000).toLong() - END_EPSILON_MS) target = 0
        _state.update { it?.copy(positionMs = target, offline = session == null) }
        val h = ensureHandle()
        if (h == null) {
            _state.update { it?.copy(loading = false) }
            return
        }
        loadQueue(h, b, target)
        startAndVerify(h)
        startSyncLoop()
    }

    /**
     * Newest wins between this phone and the server for [b]. If the server is
     * newer, adopts its position locally and returns it; if the phone has
     * unsynced listening that's newer, sends it. Throws when offline.
     */
    private suspend fun reconcile(b: DownloadedBook): Double? = reconcileItem(b.itemId, b.durationSec)

    /** Opens a server session for syncing (bounded; stays offline on failure). */
    private suspend fun tryOpenSession(b: DownloadedBook) {
        lastReconnectAt = System.currentTimeMillis()
        val opened = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { runCatching { AbsApi.startPlayback(b.itemId) }.getOrNull() }
        }
        if (opened != null && book?.itemId == b.itemId) {
            session = opened
            sessionStale = false
            resetSyncClock()
            _state.update { it?.copy(offline = false) }
        } else {
            _state.update { it?.copy(offline = true) }
        }
    }

    /** Offline → online while playing: push what we have (newest wins) and get a session. */
    private suspend fun tryReconnect() {
        val b = book ?: return
        lastReconnectAt = System.currentTimeMillis()
        saveLocal()
        val reachable = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { runCatching { reconcile(b) }.isSuccess }
        } == true
        if (reachable) tryOpenSession(b)
    }

    private fun loadQueue(h: LightAudioPlayer, b: DownloadedBook, positionMs: Long) {
        val items = b.tracks.map { track ->
            LightAudioItem(
                id = "${b.itemId}/${track.index}",
                source = LightAudioSource.FileSource(Downloads.audioFile(b, track)),
                metadata = LightMediaMetadata(title = b.title, artist = b.author),
            )
        }
        val trackIndex = trackIndexAt(b, positionMs)
        pendingSeekWithinTrackMs = positionMs - (b.tracks[trackIndex].startOffset * 1000).toLong()
        h.setMediaQueue(items, trackIndex)
        h.speed = _speed.value
        _state.update { it?.copy(positionMs = positionMs) }
        applyPendingSeek(h)
    }

    private fun seekHandle(h: LightAudioPlayer, b: DownloadedBook, targetMs: Long) {
        val index = trackIndexAt(b, targetMs)
        val within = targetMs - (b.tracks[index].startOffset * 1000).toLong()
        pendingSeekWithinTrackMs = within
        if (index != h.currentMediaItemIndex.value) {
            // The new track's duration resolves asynchronously; applied then.
            h.seekToDefaultPosition(index)
        } else {
            applyPendingSeek(h)
        }
        _state.update { it?.copy(positionMs = targetMs) }
    }

    /**
     * Plays and confirms it actually started. If nothing happens (dead
     * controller, lost audio focus) rebuild the player once and retry, then
     * surface an error — never fail silently.
     */
    private suspend fun startAndVerify(h: LightAudioPlayer) {
        _state.update { it?.copy(loading = true) }
        h.play()
        if (awaitPlaying(h)) {
            _state.update { it?.copy(loading = false) }
            return
        }
        if (h.error.value != null) {
            _state.update { it?.copy(loading = false) } // the error collector explains
            return
        }
        // Didn't start: rebuild the handle and re-queue at our position.
        val b = book
        val position = _state.value?.positionMs ?: 0
        dropHandle()
        val fresh = ensureHandle()
        if (fresh == null || b == null) {
            _state.update { it?.copy(loading = false) }
            return
        }
        if (fresh.currentMediaItemIndex.value == NO_MEDIA_ITEM) loadQueue(fresh, b, position)
        fresh.play()
        val started = awaitPlaying(fresh)
        _state.update { it?.copy(loading = false) }
        if (!started && fresh.error.value == null) {
            _error.value = "Playback didn't start. Another app may be using audio — " +
                "try again, or Settings → Reset Player."
        }
    }

    private suspend fun awaitPlaying(h: LightAudioPlayer): Boolean =
        withTimeoutOrNull(PLAY_START_TIMEOUT_MS) {
            h.isPlaying.first { it }
            true
        } ?: false

    // ---- Progress: phone + server ------------------------------------------------

    private fun resetSyncClock() {
        val now = System.currentTimeMillis()
        lastSyncAt = now
        lastLocalSaveAt = now
        listenedMsSinceSync = 0
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                delay(1_000)
                if (_state.value?.playing == true && handle != null) {
                    tickListened(wasPlaying = true)
                    val now = System.currentTimeMillis()
                    if (now - lastLocalSaveAt >= LOCAL_SAVE_INTERVAL_MS) saveLocal()
                    if (session == null) {
                        if (now - lastReconnectAt >= RECONNECT_INTERVAL_MS) {
                            lock.withLock { if (session == null) tryReconnect() }
                        }
                    } else if (now - lastSyncAt >= SYNC_INTERVAL_MS) {
                        syncNow()
                    }
                } else {
                    lastTickAt = 0
                }
            }
        }
    }

    private fun tickListened(wasPlaying: Boolean) {
        val now = System.currentTimeMillis()
        if (wasPlaying && lastTickAt > 0) listenedMsSinceSync += (now - lastTickAt).coerceIn(0, 5_000)
        lastTickAt = if (wasPlaying || _state.value?.playing == true) now else 0
    }

    private fun takeListened(): Long = listenedMsSinceSync.also { listenedMsSinceSync = 0 }

    /**
     * Records the current position on the phone (unsynced until the server has
     * it). Only real movement is saved, so an idle loaded book never gets a
     * fresh timestamp that could beat newer progress from another device.
     */
    private fun saveLocal() {
        val b = book ?: return
        val now = _state.value?.takeIf { it.itemId == b.itemId } ?: return
        if (lastSavedPositionMs >= 0 && kotlin.math.abs(now.positionMs - lastSavedPositionMs) < 1_000) return
        lastSavedPositionMs = now.positionMs
        lastLocalSaveAt = LocalProgress.save(b.itemId, now.positionMs / 1000.0, b.durationSec)
    }

    private fun hasUnsynced(b: DownloadedBook): Boolean = LocalProgress.get(b.itemId)?.unsynced == true

    /** Pushes the current position to the server (fire and forget). */
    private fun syncNow() {
        val b = book ?: return
        val s = session ?: return
        val now = _state.value?.takeIf { it.itemId == b.itemId } ?: return
        // Nothing new since the server last heard from us: don't resend an old position.
        if (!hasUnsynced(b) && listenedMsSinceSync == 0L) return
        lastSyncAt = System.currentTimeMillis()
        val savedAt = lastLocalSaveAt
        val listened = takeListened()
        val positionSec = now.positionMs / 1000.0
        val stale = sessionStale
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (stale) {
                        AbsApi.patchProgress(b.itemId, positionSec, b.durationSec)
                    } else {
                        try {
                            AbsApi.syncSession(s.id, positionSec, listened / 1000.0, b.durationSec)
                        } catch (e: SessionGoneException) {
                            // Server restarted or expired the session: write progress directly.
                            withContext(Dispatchers.Main) { if (session?.id == s.id) sessionStale = true }
                            AbsApi.patchProgress(b.itemId, positionSec, b.durationSec)
                        }
                    }
                }
            }
            result.fold(
                onSuccess = {
                    LocalProgress.markSynced(b.itemId, savedAt)
                    _state.update { it?.copy(offline = false) }
                },
                onFailure = {
                    // Offline: credit the listening time to the next sync. The
                    // position is safe on the phone and goes up when we're back.
                    listenedMsSinceSync += listened
                    _state.update { it?.copy(offline = true) }
                },
            )
        }
    }

    /** Final save + sync + close for the loaded book (switching books, reset, sign-out). */
    private suspend fun closeSessionLocked() {
        val b = book ?: return
        saveLocal()
        val s = session
        val now = _state.value?.takeIf { it.itemId == b.itemId }
        val listened = takeListened()
        session = null
        val stale = sessionStale
        sessionStale = false
        pendingSeekWithinTrackMs = null
        if (now == null) return
        val savedAt = lastLocalSaveAt
        lastSavedPositionMs = -1
        if (!hasUnsynced(b)) {
            // Nothing new to report: just close, without a position that could
            // rewind progress made on another device since.
            if (s != null && !stale) {
                withContext(Dispatchers.IO + NonCancellable) {
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { runCatching { AbsApi.closeSession(s.id, null) } }
                }
            }
            return
        }
        // Bounded: a slow server mustn't hold the player lock (and every tap) hostage.
        val ok = withContext(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                runCatching {
                    val positionSec = now.positionMs / 1000.0
                    if (s == null || stale) {
                        AbsApi.patchProgress(b.itemId, positionSec, b.durationSec)
                    } else {
                        AbsApi.closeSession(s.id, AbsApi.FinalSync(positionSec, listened / 1000.0, b.durationSec))
                    }
                }.isSuccess
            } ?: false
        }
        if (ok) LocalProgress.markSynced(b.itemId, savedAt)
    }

    /** Newest-wins for one book (see [reconcile]). Throws when offline. */
    private suspend fun reconcileItem(itemId: String, durationSec: Double): Double? {
        val local = LocalProgress.get(itemId)
        val server = AbsApi.progressFor(itemId)
        if (server != null && (local == null || server.lastUpdate > local.updatedAt)) {
            LocalProgress.adoptServer(itemId, server.currentTime, durationSec, server.lastUpdate)
            return server.currentTime
        }
        if (local != null && local.unsynced) {
            AbsApi.patchProgress(itemId, local.positionSec, local.durationSec)
            LocalProgress.markSynced(itemId, local.updatedAt)
        }
        return null
    }

    private fun trackIndexAt(b: DownloadedBook, positionMs: Long): Int {
        val sec = positionMs / 1000.0
        val index = b.tracks.indexOfLast { it.startOffset <= sec }
        return index.coerceIn(0, b.tracks.lastIndex)
    }
}
