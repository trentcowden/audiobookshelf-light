package com.trentcowden.audiobookshelf.playback

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.downloads.DownloadedBook
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.LocalProgress
import com.trentcowden.audiobookshelf.storage.SettingsStore
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the player UI renders. Times are milliseconds on the book's timeline. */
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
)

/** Pauses playback after a while. */
sealed interface SleepTimer {
    /** [remainingMs] of playing time left (it doesn't count down while paused). */
    data class Countdown(val remainingMs: Long) : SleepTimer

    /** Pause when playback reaches [endMs] (the end of the chapter it was set in). */
    data class EndOfChapter(val endMs: Long) : SleepTimer
}

/**
 * The tool's player, shared by every screen and alive for the whole process
 * — never owned by a screen (see [PlayerSlot] for why that matters).
 *
 * It ties together the audio handle ([PlayerSlot]), progress syncing
 * ([ProgressSync]) and the downloaded book: commands from the UI, mapping
 * multi-file books onto one timeline, play verification, and the sleep timer.
 * Books play only from downloads.
 *
 * Everything that touches the handle, the book or the session runs on the
 * main thread under [lock].
 */
object Playback {
    private const val SEEK_DEBOUNCE_MS = 300L
    /** How long the player may take to report a new position after a seek. */
    private const val SEEK_SETTLE_MS = 2_000L
    /** Close enough: the player has caught up with a seek. */
    private const val SEEK_TOLERANCE_MS = 1_500L
    /** Resuming after a pause this long first checks whether another device moved on. */
    private const val CHECK_SERVER_AFTER_PAUSE_MS = 60_000L
    private const val RESUME_CHECK_TIMEOUT_MS = 4_000L
    /** A server position this far from ours is a real jump (another device), not drift. */
    private const val SERVER_JUMP_MS = 3_000L
    private const val LOOP_INTERVAL_MS = 1_000L

    /**
     * Anything unexpected (e.g. a command racing a released handle, which the
     * SDK reports by throwing) becomes a visible error and a dropped handle —
     * never a crash, and never a silently dead player.
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        runCatching { android.util.Log.e("AbsPlayback", "playback error", e) }
        _error.value = "Player error: ${e.message ?: e.toString()}. Tap play to retry."
        _state.update { it?.copy(loading = false) }
        slot.drop()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    private val lock = Mutex()

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last user-facing playback problem; cleared by the next successful action. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    private val slot: PlayerSlot = PlayerSlot(scope, SlotEvents, speed = { _speed.value })
    private val sync: ProgressSync = ProgressSync(AbsApi, LocalProgress, scope, Dispatchers.IO)

    /** The downloaded book loaded on the player. */
    private var book: DownloadedBook? = null
    /** A seek waiting for the track's duration (the SDK clamps seeks to the known duration). */
    private var pendingSeekWithinTrackMs: Long? = null
    private var pausedAt = 0L
    private var seekJob: Job? = null
    /** A seek is settling until then: a brief stop while it buffers isn't a pause. */
    private var seekingUntil = 0L
    private var pendingPause: Job? = null
    private var loopJob: Job? = null

    // ---- Activity lifecycle ------------------------------------------------------

    /**
     * Called by every screen when it shows. Remembers how to build a player
     * from the current activity; reconnects to live playback if a book is loaded.
     */
    fun attach(lightAudio: LightAudio, generation: Int) {
        slot.attach(lightAudio, generation)
        _speed.value = SettingsStore.settings.value.speed
        if (slot.holdsStaleHandle || (book != null && slot.current == null)) {
            scope.launch {
                lock.withLock {
                    // Never reuse a handle bound through a previous activity.
                    if (slot.holdsStaleHandle) slot.drop()
                    if (book != null) slot.ensure()
                }
            }
        }
    }

    /** The activity is being destroyed: let go of the handle now (see [PlayerSlot.onActivityGone]). */
    fun onActivityGone(generation: Int) {
        // Capture where we are before the handle goes.
        val released = slot.onActivityGone(generation) { h -> refreshFromHandle(h) }
        if (!released) return
        saveLocal()
        syncNow()
        // Nothing observes the detached player now: stop counting listening time
        // and syncing a frozen position. The next attach reads the real state.
        _state.update { it?.copy(playing = false) }
        sync.stopTicking()
    }

    private object SlotEvents : PlayerSlot.Events {
        override fun onPlayingChanged(playing: Boolean, observedPause: Boolean) {
            log("playing=$playing observedPause=$observedPause at ${_state.value?.positionMs}")
            pendingPause?.cancel()
            pendingPause = null
            if (!playing && System.currentTimeMillis() < seekingUntil) {
                // A seek while playing stops the player for a moment while it
                // buffers the new spot. Don't flash the play button (or count it
                // as a pause) unless it's still stopped once the seek settles.
                pendingPause = scope.launch {
                    delay((seekingUntil - System.currentTimeMillis()).coerceAtLeast(0))
                    if (slot.current?.isPlaying?.value != true) applyPlaying(false, observedPause)
                }
                return
            }
            applyPlaying(playing, observedPause)
        }

        private fun applyPlaying(playing: Boolean, observedPause: Boolean) {
            sync.tick(wasPlaying = _state.value?.playing == true, isPlaying = playing)
            _state.update { it?.copy(playing = playing) }
            if (playing) {
                _error.value = null
            } else if (observedPause) {
                // Only a pause we saw happen counts for rewind-after-pause.
                pausedAt = System.currentTimeMillis()
                saveLocal()
                syncNow()
            }
        }

        override fun onPositionChanged(handle: LightAudioPlayer) {
            refreshFromHandle(handle)
            checkEndOfChapterTimer()
        }

        override fun onDurationKnown(handle: LightAudioPlayer) = applyPendingSeek(handle)

        override fun onPlaybackError(diagnostic: String) {
            if (book == null) return
            _state.update { it?.copy(loading = false, playing = false) }
            _error.value = "Couldn't play the downloaded file ($diagnostic). " +
                "Try removing the download and downloading it again."
        }

        override fun onConnected(handle: LightAudioPlayer) {
            if (book != null && handle.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
                // The playback service stopped since we last looked (idle timeout
                // or a crash), taking its queue with it. Keep the book and position
                // so the next Play re-queues from there.
                _state.update { it?.copy(playing = false) }
            } else {
                refreshFromHandle(handle)
                _state.update { it?.copy(playing = handle.isPlaying.value) }
            }
        }

        override fun onSlotError(message: String) {
            log("slot error: $message")
            _error.value = message
        }
    }

    // ---- Commands --------------------------------------------------------------

    /**
     * Plays a downloaded book from wherever you left off (on this phone or
     * another device, whichever is newer). If it's already loaded this resumes.
     * With [startAtMs] (the player already checked the server, or you picked
     * a spot) it starts there straight away.
     */
    fun playBook(itemId: String, startAtMs: Long? = null) {
        scope.launch {
            lock.withLock {
                if (book?.itemId == itemId) resumeLocked() else openLocked(itemId, startAtMs)
            }
        }
    }

    /** Best position this phone knows for a book without asking the server. */
    fun bestKnownPositionSec(itemId: String): Double =
        ProgressPolicy.bestKnownPositionSec(LocalProgress.get(itemId), LibraryCache.load()?.progress?.get(itemId))

    /**
     * Newest-wins check against the server for a book about to be shown, so
     * Play can start straight away from the right spot. Returns false when
     * the server can't be reached. Never throws.
     */
    suspend fun refreshFromServer(itemId: String): Boolean {
        val b = Downloads.book(itemId) ?: return false
        return sync.tryReconcile(itemId, b.durationSec).isSuccess
    }

    fun togglePlay() {
        scope.launch {
            lock.withLock {
                if (_state.value?.playing == true) {
                    log("pause tapped")
                    seekingUntil = 0 // a real pause shows at once
                    slot.ensure()?.pause()
                } else {
                    resumeLocked()
                }
            }
        }
    }

    /** Seeks on the book's timeline (crossing file boundaries for multi-file books). */
    /**
     * Seeks on the book's timeline (crossing file boundaries for multi-file
     * books). [dragging]: the seek bar is moving, so wait until it's released
     * ([finishDrag]) or settles instead of seeking on every step; taps (skip,
     * chapters) seek at once.
     */
    fun seekTo(targetMs: Long, dragging: Boolean = false) {
        val b0 = book ?: return
        val clamped = targetMs.coerceIn(0, b0.durationMs)
        dragTargetMs = if (dragging) clamped else null
        // Show the target immediately, and keep showing it until the player
        // catches up (it reports the old position for a moment).
        expectPosition(clamped, SEEK_SETTLE_MS + if (dragging) SEEK_DEBOUNCE_MS else 0)
        _state.update { it?.copy(positionMs = clamped) }
        retargetEndOfChapterTimer(clamped)
        pausedAt = 0
        seekJob?.cancel()
        seekJob = scope.launch {
            if (dragging) delay(SEEK_DEBOUNCE_MS)
            // Sent now: letting go of the bar here mustn't seek a second time
            // (two seeks in a row are audible as a glitch).
            if (dragTargetMs == clamped) dragTargetMs = null
            lock.withLock {
                val b = book ?: return@withLock
                val h = slot.ensure() ?: return@withLock
                if (h.currentMediaItemIndex.value == NO_MEDIA_ITEM) return@withLock // applied on Play
                seekHandle(h, b, clamped)
                saveLocal()
                syncNow()
            }
        }
    }

    /** The seek bar was released: seek to where it was dragged now, without waiting. */
    fun finishDrag() {
        val target = dragTargetMs ?: return
        seekTo(target)
    }

    /** Where the seek bar is being dragged to (seek not sent yet). */
    private var dragTargetMs: Long? = null

    fun setSpeed(value: Float) {
        _speed.value = value
        SettingsStore.update { it.copy(speed = value) }
        slot.ready?.speed = value
    }

    /** Sets (or with null, cancels) the sleep timer. */
    fun setSleepTimer(timer: SleepTimer?) {
        _sleepTimer.value = timer
    }

    /** A sleep timer that pauses at the end of the chapter playing now. */
    fun endOfChapterTimer(): SleepTimer? {
        val now = _state.value ?: return null
        return chapterEndAfter(now.chapters, now.positionMs)?.let { SleepTimer.EndOfChapter(it) }
    }

    /** Stops and unloads [itemId] if it's the loaded book (its download is being removed). */
    fun unload(itemId: String) {
        scope.launch { lock.withLock { if (book?.itemId == itemId) unloadLocked() } }
    }

    /**
     * Marks a book finished on the server. If it's loaded, playback stops
     * first. Returns a message for the user on failure, null on success.
     */
    suspend fun markFinished(itemId: String, durationSec: Double): String? =
        changeProgress(itemId) {
            val progress = AbsApi.setFinished(itemId, durationSec)
            val end = durationSec.takeIf { it > 0 } ?: progress?.duration ?: 0.0
            LocalProgress.adoptServer(itemId, end, end, progress?.lastUpdate ?: System.currentTimeMillis())
        }

    /** Forgets a book's progress everywhere ("not started"). Same contract as [markFinished]. */
    suspend fun markNotStarted(itemId: String, durationSec: Double): String? =
        changeProgress(itemId) {
            AbsApi.deleteProgress(itemId)
            LocalProgress.adoptServer(itemId, 0.0, durationSec, System.currentTimeMillis())
        }

    private suspend fun changeProgress(itemId: String, change: suspend () -> Unit): String? {
        lock.withLock { if (book?.itemId == itemId) unloadLocked() }
        return try {
            withContext(Dispatchers.IO) { change() }
            null
        } catch (e: AbsException) {
            e.message
        } catch (e: Exception) {
            "Couldn't reach the server."
        }
    }

    /**
     * Escape hatch (Settings → Reset Player): stops playback, releases the
     * player, closes the server session. Everything starts fresh after this.
     */
    fun reset() {
        scope.launch {
            lock.withLock {
                // Get a connected handle (building one if needed) so stop()
                // actually reaches the playback service instead of being dropped.
                slot.ensure()?.let { h -> runCatching { h.stop() } }
                closeSessionLocked()
                clearLoadedBook()
                slot.drop()
                // If the SDK still refuses to build a player, the only remaining
                // fix is a fresh process — LightOS has no force-stop, so do it for
                // the user. The tool closes; reopening starts clean.
                if (!slot.probe()) android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * Sign-out: stop, save the final position and close the session while the
     * sign-in still works, then forget it. [onDone] runs on the main thread.
     */
    fun signOut(onDone: () -> Unit) {
        scope.launch {
            lock.withLock {
                slot.ready?.let { runCatching { it.stop() } }
                closeSessionLocked()
                clearLoadedBook()
                slot.drop()
            }
            val auth = AuthStore.current
            AuthStore.clear()
            LibraryCache.clear()
            LocalProgress.clear()
            if (auth != null) {
                withContext(Dispatchers.IO + NonCancellable) {
                    withTimeoutOrNull(ProgressSync.NETWORK_TIMEOUT_MS) { AbsApi.logout(auth) }
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
        scope.launch {
            val loaded = book?.itemId
            LocalProgress.unsynced().forEach { (itemId, local) ->
                if (itemId != loaded) runCatching { sync.reconcile(itemId, local.durationSec) }
            }
        }
    }

    // ---- Opening and resuming (call under lock) -----------------------------------

    private suspend fun resumeLocked() {
        val b = book ?: return
        val pausedLong = pausedAt == 0L || System.currentTimeMillis() - pausedAt >= CHECK_SERVER_AFTER_PAUSE_MS
        if (sync.session == null || pausedLong) {
            // Another device may have moved this book on while it sat paused here.
            saveLocal()
            val result = sync.tryReconcile(b.itemId, b.durationSec, RESUME_CHECK_TIMEOUT_MS)
            result.getOrNull()?.let { serverSec ->
                val serverMs = (serverSec * 1000).toLong()
                if (kotlin.math.abs(serverMs - (_state.value?.positionMs ?: 0)) > SERVER_JUMP_MS) {
                    _state.update { it?.copy(positionMs = serverMs) }
                    sync.markSaved(serverMs)
                    pausedAt = 0 // jumping to someone else's spot: no rewind
                }
            }
            // Offline: play straight away from the phone's position.
            if (sync.session == null && result.isSuccess) openSessionLocked(b)
        }
        val now = _state.value ?: return
        val h = slot.ensure() ?: return
        val target = ProgressPolicy.rewindAfterPause(
            ProgressPolicy.restartIfFinished(now.positionMs, b.durationMs),
            pausedAt,
            System.currentTimeMillis(),
        )
        if (h.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
            loadQueue(h, b, target) // the service lost its queue: re-queue where we were
        } else if (target != now.positionMs) {
            seekHandle(h, b, target)
        }
        startAndVerify(h)
    }

    /**
     * Loads and plays a downloaded book. With [startAtMs] playback starts at
     * once and the sync session opens in the background; without it, the
     * server is asked first (newest of phone and server wins).
     */
    private suspend fun openLocked(itemId: String, startAtMs: Long?) {
        _error.value = null
        val b = Downloads.book(itemId) ?: run {
            _error.value = "Download this book first."
            return
        }
        // Silence and close out whatever was loaded, saving its progress.
        slot.ready?.let { runCatching { it.pause() } }
        closeSessionLocked()
        book = b
        _state.value = NowPlaying(
            itemId = itemId,
            title = b.title,
            author = b.author,
            durationMs = b.durationMs,
            positionMs = ((LocalProgress.get(itemId)?.positionSec ?: 0.0) * 1000).toLong(),
            playing = false,
            chapters = b.chapters,
            loading = true,
        )
        val target: Long
        if (startAtMs != null) {
            sync.resetClock()
            sync.markSaved(-1) // a chosen spot counts as new progress
            target = startAtMs.coerceIn(0, b.durationMs)
        } else {
            // Online: newest of phone/server. Offline: newest of phone and the last listing.
            val reconciled = sync.tryReconcile(itemId, b.durationSec)
            val startSec = reconciled.getOrNull()
                ?: if (reconciled.isSuccess) LocalProgress.get(itemId)?.positionSec ?: 0.0 else bestKnownPositionSec(itemId)
            if (reconciled.isSuccess) openSessionLocked(b)
            sync.resetClock()
            sync.markSaved((startSec * 1000).toLong())
            target = ProgressPolicy.restartIfFinished((startSec * 1000).toLong(), b.durationMs)
        }
        _state.update { it?.copy(positionMs = target) }
        // A timer set on the player before Play: end of the chapter Play starts in.
        retargetEndOfChapterTimer(target)
        val h = slot.ensure()
        if (h == null) {
            _state.update { it?.copy(loading = false) }
            return
        }
        loadQueue(h, b, target)
        startAndVerify(h)
        startLoop()
        if (startAtMs != null) openSessionInBackground(b)
    }

    private suspend fun unloadLocked() {
        slot.ready?.let { runCatching { it.stop() } }
        closeSessionLocked()
        clearLoadedBook()
    }

    private fun clearLoadedBook() {
        book = null
        pendingSeekWithinTrackMs = null
        _state.value = null
        _error.value = null
        _sleepTimer.value = null
    }

    private suspend fun closeSessionLocked() {
        val b = book ?: return
        pendingSeekWithinTrackMs = null
        sync.close(b.itemId, _state.value?.takeIf { it.itemId == b.itemId }?.positionMs, b.durationSec)
    }

    /** Opens a sync session while holding the lock (bounded; stays offline on failure). */
    private suspend fun openSessionLocked(b: DownloadedBook) {
        val opened = sync.openSession(b.itemId) ?: return
        if (book?.itemId == b.itemId) {
            sync.adopt(opened)
            sync.resetClock()
        } else {
            sync.discard(opened)
        }
    }

    /** Opens the sync session without holding the lock, so taps stay responsive meanwhile. */
    private fun openSessionInBackground(b: DownloadedBook) {
        scope.launch {
            val opened = sync.openSession(b.itemId) ?: return@launch
            lock.withLock {
                if (book?.itemId != b.itemId || sync.session != null) {
                    // Switched books meanwhile: don't leave a stray session open.
                    if (sync.session?.id != opened.id) sync.discard(opened)
                    return@withLock
                }
                sync.adopt(opened)
                syncNow()
            }
        }
    }

    /** Offline → online while playing: push what we have (newest wins) and get a session. */
    private suspend fun tryReconnectLocked() {
        val b = book ?: return
        sync.noteReconnectAttempt()
        saveLocal()
        if (sync.tryReconcile(b.itemId, b.durationSec).isSuccess) openSessionLocked(b)
    }

    // ---- The handle and the book's timeline --------------------------------------

    private fun loadQueue(h: LightAudioPlayer, b: DownloadedBook, positionMs: Long) {
        val items = b.tracks.map { track ->
            LightAudioItem(
                id = "${b.itemId}/${track.index}",
                source = LightAudioSource.FileSource(Downloads.audioFile(b, track)),
                metadata = LightMediaMetadata(title = b.title, artist = b.author),
            )
        }
        val at = trackPositionAt(b.trackStarts, positionMs)
        pendingSeekWithinTrackMs = at.withinMs
        expectPosition(positionMs, SEEK_SETTLE_MS)
        h.setMediaQueue(items, at.index)
        h.speed = _speed.value
        _state.update { it?.copy(positionMs = positionMs) }
        applyPendingSeek(h)
    }

    private fun seekHandle(h: LightAudioPlayer, b: DownloadedBook, targetMs: Long) {
        val at = trackPositionAt(b.trackStarts, targetMs)
        pendingSeekWithinTrackMs = at.withinMs
        expectPosition(targetMs, SEEK_SETTLE_MS)
        if (at.index != h.currentMediaItemIndex.value) {
            // The new track's duration resolves asynchronously; the seek is applied then.
            h.seekToDefaultPosition(at.index)
        } else {
            applyPendingSeek(h)
        }
        _state.update { it?.copy(positionMs = targetMs) }
    }

    private fun applyPendingSeek(h: LightAudioPlayer) {
        val pending = pendingSeekWithinTrackMs ?: return
        if (h.durationMs.value <= 0) return
        pendingSeekWithinTrackMs = null
        h.seekTo(pending)
    }

    /** Maps the handle's (track, offset) onto the book's timeline. */
    private fun refreshFromHandle(h: LightAudioPlayer) {
        val b = book ?: return
        val index = h.currentMediaItemIndex.value
        if (index == NO_MEDIA_ITEM || index !in b.tracks.indices) return
        // While a seek is pending the handle still reports the old offset.
        val within = pendingSeekWithinTrackMs ?: h.positionMs.value.coerceAtLeast(0)
        var position = bookPositionMs(b.trackStarts, index, within)
        expectedPositionMs?.let { expected ->
            when {
                // Just after a seek the player still reports where it was: keep
                // showing the target so the bar doesn't flicker back.
                System.currentTimeMillis() < expectedUntil &&
                    kotlin.math.abs(position - expected) > SEEK_TOLERANCE_MS -> position = expected
                else -> expectedPositionMs = null
            }
        }
        _state.update { it?.copy(positionMs = position) }
    }

    /** Position the player should report soon (a seek in flight), until [settleMs] from now. */
    private var expectedPositionMs: Long? = null
    private var expectedUntil = 0L

    private fun expectPosition(positionMs: Long, settleMs: Long) {
        expectedPositionMs = positionMs
        expectedUntil = System.currentTimeMillis() + settleMs
        seekingUntil = expectedUntil
    }

    /**
     * Plays and confirms it actually started. If nothing happens (dead
     * controller, lost audio focus) rebuild the player once and retry, then
     * surface an error — never fail silently.
     */
    private suspend fun startAndVerify(h: LightAudioPlayer) {
        _state.update { it?.copy(loading = true) }
        h.play()
        if (PlayerSlot.awaitPlaying(h) || h.error.value != null) {
            // Started — or failed with an error the slot's observer explains.
            _state.update { it?.copy(loading = false) }
            return
        }
        // Didn't start: rebuild the handle and re-queue at our position.
        log("play didn't start; rebuilding the player")
        val b = book
        val position = _state.value?.positionMs ?: 0
        slot.drop()
        val fresh = slot.ensure()
        if (fresh == null || b == null) {
            _state.update { it?.copy(loading = false) }
            return
        }
        if (fresh.currentMediaItemIndex.value == NO_MEDIA_ITEM) loadQueue(fresh, b, position)
        fresh.play()
        val started = PlayerSlot.awaitPlaying(fresh)
        _state.update { it?.copy(loading = false) }
        if (!started && fresh.error.value == null) {
            _error.value = "Playback didn't start. Another app may be using audio — " +
                "try again, or Settings → Reset Player."
        }
    }

    // ---- Progress and the sleep timer ---------------------------------------------

    private fun saveLocal() {
        val b = book ?: return
        val now = _state.value?.takeIf { it.itemId == b.itemId } ?: return
        sync.saveLocal(b.itemId, now.positionMs, b.durationSec)
    }

    private fun syncNow() {
        val b = book ?: return
        val now = _state.value?.takeIf { it.itemId == b.itemId } ?: return
        sync.syncNow(b.itemId, now.positionMs, b.durationSec)
    }

    /** Once a second while a book is loaded: listening time, saving, syncing, the sleep timer. */
    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var lastAt = System.currentTimeMillis()
            while (isActive) {
                delay(LOOP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastAt).coerceIn(0, 5_000)
                lastAt = now
                if (_state.value?.playing != true || slot.current == null) {
                    sync.stopTicking()
                    continue
                }
                sync.tick(wasPlaying = true, isPlaying = true)
                if (sync.localSaveDue(now)) saveLocal()
                if (sync.reconnectDue(now)) {
                    lock.withLock { if (sync.session == null) tryReconnectLocked() }
                } else if (sync.syncDue(now)) {
                    syncNow()
                }
                countDownSleepTimer(elapsed)
                checkEndOfChapterTimer()
            }
        }
    }

    private fun countDownSleepTimer(elapsedMs: Long) {
        val timer = _sleepTimer.value as? SleepTimer.Countdown ?: return
        val left = timer.remainingMs - elapsedMs
        if (left > 0) _sleepTimer.value = SleepTimer.Countdown(left) else sleepNow()
    }

    private fun checkEndOfChapterTimer() {
        val timer = _sleepTimer.value as? SleepTimer.EndOfChapter ?: return
        val now = _state.value?.takeIf { it.playing } ?: return
        if (now.positionMs >= timer.endMs - END_OF_CHAPTER_EARLY_MS) sleepNow()
    }

    /** Seeking moves an end-of-chapter timer to the chapter you landed in. */
    private fun retargetEndOfChapterTimer(positionMs: Long) {
        if (_sleepTimer.value !is SleepTimer.EndOfChapter) return
        val chapters = _state.value?.chapters ?: return
        _sleepTimer.value = chapterEndAfter(chapters, positionMs)?.let { SleepTimer.EndOfChapter(it) }
    }

    private fun sleepNow() {
        log("sleep timer: pausing at ${_state.value?.positionMs}")
        _sleepTimer.value = null
        seekingUntil = 0
        scope.launch { lock.withLock { slot.ready?.pause() } }
    }

    /** Pausing a moment early keeps the next chapter's first words from slipping out. */
    private const val END_OF_CHAPTER_EARLY_MS = 250L
}

/** Player events for `adb logcat -s AbsPlayback` — what happened when playback misbehaves. */
private fun log(message: String) {
    runCatching { android.util.Log.d("AbsPlayback", message) }
}

/** End (ms) of the chapter containing [positionMs], or null without chapters. */
fun chapterEndAfter(chapters: List<Chapter>, positionMs: Long): Long? {
    if (chapters.isEmpty()) return null
    val sec = positionMs / 1000.0
    val chapter = chapters.firstOrNull { sec + 0.001 < it.end } ?: chapters.last()
    return (chapter.end * 1000).toLong()
}

private val DownloadedBook.durationMs: Long get() = (durationSec * 1000).toLong().coerceAtLeast(0)
private val DownloadedBook.trackStarts: List<Double> get() = tracks.map { it.startOffset }
