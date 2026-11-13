package com.trentcowden.audiobookshelf.playback

import com.trentcowden.audiobookshelf.api.FinalSync
import com.trentcowden.audiobookshelf.api.PlaybackSession
import com.trentcowden.audiobookshelf.api.ProgressServer
import com.trentcowden.audiobookshelf.api.SessionGoneException
import com.trentcowden.audiobookshelf.storage.LocalPositions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the loaded book's progress in step between this phone and the server.
 *
 * The position is saved on the phone every few seconds ([saveLocal]) and sent
 * to the server through a playback session ([syncNow]), which also records
 * listening time for the server's stats. Offline, positions wait on the phone
 * (marked unsynced) and go up when the server is reachable again — unless the
 * server has something newer ([ProgressPolicy.reconcile]).
 *
 * Not thread-safe: [Playback] calls it on the main thread while holding its
 * lock. Network calls hop to [io] and are bounded by [NETWORK_TIMEOUT_MS] so a
 * slow server never holds the player hostage.
 */
internal class ProgressSync(
    private val server: ProgressServer,
    private val local: LocalPositions,
    /** Runs fire-and-forget syncs; should be a main-thread scope. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Server playback session for the loaded book; null while offline. */
    var session: PlaybackSession? = null
        private set

    /** The server dropped the session; progress is written directly instead. */
    private var sessionStale = false
    private var listenedMs = 0L
    private var lastTickAt = 0L
    private var lastSyncAt = 0L
    private var lastLocalSaveAt = 0L
    private var lastSavedPositionMs = -1L
    private var lastReconnectAt = 0L

    fun localSaveDue(now: Long) = now - lastLocalSaveAt >= LOCAL_SAVE_INTERVAL_MS
    fun syncDue(now: Long) = session != null && now - lastSyncAt >= SYNC_INTERVAL_MS
    fun reconnectDue(now: Long) = session == null && now - lastReconnectAt >= RECONNECT_INTERVAL_MS

    /** Starts the clocks for a freshly loaded book. */
    fun resetClock() {
        val now = clock()
        lastSyncAt = now
        lastLocalSaveAt = now
        listenedMs = 0
    }

    /**
     * Sets the position counted as already saved: the next [saveLocal] only
     * writes once playback moves away from it. -1 makes any position new
     * (you picked a spot).
     */
    fun markSaved(positionMs: Long) {
        lastSavedPositionMs = positionMs
    }

    // ---- Listening time -----------------------------------------------------------

    /** Credits time since the last tick to listening (capped, so a frozen phone doesn't inflate it). */
    fun tick(wasPlaying: Boolean, isPlaying: Boolean) {
        val now = clock()
        if (wasPlaying && lastTickAt > 0) listenedMs += (now - lastTickAt).coerceIn(0, MAX_TICK_MS)
        lastTickAt = if (wasPlaying || isPlaying) now else 0
    }

    fun stopTicking() {
        lastTickAt = 0
    }

    private fun takeListened(): Long = listenedMs.also { listenedMs = 0 }

    // ---- Phone ----------------------------------------------------------------

    /** Records [positionMs] on the phone, if it moved (see [ProgressPolicy.worthSaving]). */
    fun saveLocal(itemId: String, positionMs: Long, durationSec: Double) {
        if (!ProgressPolicy.worthSaving(positionMs, lastSavedPositionMs)) return
        lastSavedPositionMs = positionMs
        lastLocalSaveAt = local.save(itemId, positionMs / 1000.0, durationSec)
    }

    private fun hasUnsynced(itemId: String) = local.get(itemId)?.unsynced == true

    // ---- Server ---------------------------------------------------------------

    /**
     * Newest wins for one book. If the server is newer, adopts its position on
     * the phone and returns it; if the phone has unsent listening, sends it and
     * returns null. Throws when the server can't be reached.
     */
    suspend fun reconcile(itemId: String, durationSec: Double): Double? = withContext(io) {
        when (val decision = ProgressPolicy.reconcile(local.get(itemId), server.progressFor(itemId))) {
            is ProgressPolicy.Decision.AdoptServer -> {
                local.adoptServer(itemId, decision.positionSec, durationSec, decision.updatedAt)
                decision.positionSec
            }
            is ProgressPolicy.Decision.SendLocal -> {
                server.patchProgress(itemId, decision.local.positionSec, decision.local.durationSec)
                local.markSynced(itemId, decision.local.updatedAt)
                null
            }
            ProgressPolicy.Decision.InSync -> null
        }
    }

    /** [reconcile], bounded: Result.failure when offline or slow. */
    suspend fun tryReconcile(itemId: String, durationSec: Double, timeoutMs: Long = NETWORK_TIMEOUT_MS): Result<Double?> =
        withTimeoutOrNull(timeoutMs) { runCatching { reconcile(itemId, durationSec) } }
            ?: Result.failure(IllegalStateException("timed out"))

    /** Asks the server for a playback session (bounded). Doesn't adopt it — see [adopt]. */
    suspend fun openSession(itemId: String): PlaybackSession? {
        lastReconnectAt = clock()
        return withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            withContext(io) { runCatching { server.startPlayback(itemId) }.getOrNull() }
        }
    }

    fun adopt(opened: PlaybackSession) {
        session = opened
        sessionStale = false
    }

    /** Closes a session we ended up not using (e.g. you switched books meanwhile). */
    fun discard(opened: PlaybackSession) {
        scope.launch(io) { runCatching { server.closeSession(opened.id, null) } }
    }

    fun noteReconnectAttempt() {
        lastReconnectAt = clock()
    }

    /** Sends [positionMs] to the server (fire and forget), if there's anything new to send. */
    fun syncNow(itemId: String, positionMs: Long, durationSec: Double) {
        val s = session ?: return
        // Nothing new since the server last heard from us: don't resend an old position.
        if (!hasUnsynced(itemId) && listenedMs == 0L) return
        lastSyncAt = clock()
        val savedAt = lastLocalSaveAt
        val listened = takeListened()
        val positionSec = positionMs / 1000.0
        val stale = sessionStale
        scope.launch {
            var sessionGone = false
            val ok = withContext(io) {
                runCatching {
                    if (stale) {
                        server.patchProgress(itemId, positionSec, durationSec)
                    } else {
                        try {
                            server.syncSession(s.id, positionSec, listened / 1000.0, durationSec)
                        } catch (e: SessionGoneException) {
                            // Server restarted or expired the session: write progress directly.
                            sessionGone = true
                            server.patchProgress(itemId, positionSec, durationSec)
                        }
                    }
                }.isSuccess
            }
            if (sessionGone && session?.id == s.id) sessionStale = true
            if (ok) {
                local.markSynced(itemId, savedAt)
            } else {
                // Offline: credit the listening time to the next sync. The
                // position is safe on the phone and goes up when we're back.
                listenedMs += listened
            }
        }
    }

    /**
     * Final save, sync and close for the loaded book (switching books, reset,
     * sign-out). [positionMs] null = nothing loaded to report.
     */
    suspend fun close(itemId: String, positionMs: Long?, durationSec: Double) {
        if (positionMs != null) saveLocal(itemId, positionMs, durationSec)
        val s = session
        val listened = takeListened()
        val stale = sessionStale
        session = null
        sessionStale = false
        lastSavedPositionMs = -1
        if (positionMs == null) return
        val savedAt = lastLocalSaveAt
        if (!hasUnsynced(itemId)) {
            // Nothing new to report: just close, without a position that could
            // rewind progress made on another device since.
            if (s != null && !stale) {
                withContext(io + NonCancellable) {
                    withTimeoutOrNull(NETWORK_TIMEOUT_MS) { runCatching { server.closeSession(s.id, null) } }
                }
            }
            return
        }
        val positionSec = positionMs / 1000.0
        val ok = withContext(io + NonCancellable) {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                runCatching {
                    if (s == null || stale) {
                        server.patchProgress(itemId, positionSec, durationSec)
                    } else {
                        server.closeSession(s.id, FinalSync(positionSec, listened / 1000.0, durationSec))
                    }
                }.isSuccess
            } ?: false
        }
        if (ok) local.markSynced(itemId, savedAt)
    }

    companion object {
        const val SYNC_INTERVAL_MS = 15_000L
        const val LOCAL_SAVE_INTERVAL_MS = 5_000L
        const val RECONNECT_INTERVAL_MS = 60_000L
        const val NETWORK_TIMEOUT_MS = 8_000L
        private const val MAX_TICK_MS = 5_000L
    }
}
