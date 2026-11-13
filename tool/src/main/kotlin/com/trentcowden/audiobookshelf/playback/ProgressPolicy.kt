package com.trentcowden.audiobookshelf.playback

import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.storage.LocalPosition

/**
 * The rules for where a book resumes, as plain functions so they can be
 * tested without a player or a server. Times are milliseconds unless named
 * `…Sec`.
 */
object ProgressPolicy {
    /** After a pause this long, resume a little earlier so you can find the thread again. */
    const val REWIND_AFTER_PAUSE_MS = 5 * 60_000L
    const val REWIND_MS = 15_000L

    /** Starting this close to the end restarts the book instead. */
    const val END_EPSILON_MS = 2_000L

    /** Smaller moves than this aren't worth a new saved position. */
    const val MIN_SAVE_MOVEMENT_MS = 1_000L

    sealed interface Decision {
        /** The server is newer (another device listened since): take its position. */
        data class AdoptServer(val positionSec: Double, val updatedAt: Long) : Decision

        /** The phone has newer listening the server hasn't seen: send it. */
        data class SendLocal(val local: LocalPosition) : Decision

        data object InSync : Decision
    }

    /** Newest wins between this phone and the server. */
    fun reconcile(local: LocalPosition?, server: MediaProgress?): Decision = when {
        server != null && (local == null || server.lastUpdate > local.updatedAt) ->
            Decision.AdoptServer(server.currentTime, server.lastUpdate)
        local != null && local.unsynced -> Decision.SendLocal(local)
        else -> Decision.InSync
    }

    /** Without the server: the newer of the phone's position and the last library listing. */
    fun bestKnownPositionSec(local: LocalPosition?, cached: MediaProgress?): Double = when {
        cached != null && (local == null || cached.lastUpdate > local.updatedAt) -> cached.currentTime
        else -> local?.positionSec ?: 0.0
    }

    /** Back [REWIND_MS] if playback has been paused for [REWIND_AFTER_PAUSE_MS] or more. */
    fun rewindAfterPause(positionMs: Long, pausedAt: Long, now: Long): Long =
        if (pausedAt > 0 && now - pausedAt >= REWIND_AFTER_PAUSE_MS) (positionMs - REWIND_MS).coerceAtLeast(0) else positionMs

    /** A finished book plays again from the start. */
    fun restartIfFinished(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0 && positionMs >= durationMs - END_EPSILON_MS) 0 else positionMs

    /** Only real movement is saved, so an idle book never gets a timestamp that beats newer progress elsewhere. */
    fun worthSaving(positionMs: Long, lastSavedMs: Long): Boolean =
        lastSavedMs < 0 || kotlin.math.abs(positionMs - lastSavedMs) >= MIN_SAVE_MOVEMENT_MS
}

/** Where a book-timeline position falls in a book made of several files. */
data class TrackPosition(val index: Int, val withinMs: Long)

/**
 * Maps [positionMs] on the book's timeline to (track, offset in it).
 * [startOffsetsSec] are the tracks' starts on the timeline, in play order.
 */
fun trackPositionAt(startOffsetsSec: List<Double>, positionMs: Long): TrackPosition {
    if (startOffsetsSec.isEmpty()) return TrackPosition(0, positionMs.coerceAtLeast(0))
    val sec = positionMs / 1000.0
    val index = startOffsetsSec.indexOfLast { it <= sec }.coerceIn(0, startOffsetsSec.lastIndex)
    val within = positionMs - (startOffsetsSec[index] * 1000).toLong()
    return TrackPosition(index, within.coerceAtLeast(0))
}

/** The inverse of [trackPositionAt]. */
fun bookPositionMs(startOffsetsSec: List<Double>, index: Int, withinMs: Long): Long =
    ((startOffsetsSec.getOrNull(index) ?: 0.0) * 1000).toLong() + withinMs.coerceAtLeast(0)
