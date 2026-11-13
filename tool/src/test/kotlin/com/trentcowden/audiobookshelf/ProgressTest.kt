package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.api.FinalSync
import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.api.PlaybackSession
import com.trentcowden.audiobookshelf.api.ProgressServer
import com.trentcowden.audiobookshelf.api.SessionGoneException
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.playback.ProgressPolicy
import com.trentcowden.audiobookshelf.playback.ProgressPolicy.Decision
import com.trentcowden.audiobookshelf.playback.ProgressSync
import com.trentcowden.audiobookshelf.playback.TrackPosition
import com.trentcowden.audiobookshelf.playback.bookPositionMs
import com.trentcowden.audiobookshelf.playback.chapterEndAfter
import com.trentcowden.audiobookshelf.playback.trackPositionAt
import com.trentcowden.audiobookshelf.storage.LocalPosition
import com.trentcowden.audiobookshelf.storage.LocalPositions
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressPolicyTest {
    private fun local(pos: Double, at: Long, unsynced: Boolean) = LocalPosition(pos, 1000.0, at, unsynced)
    private fun server(pos: Double, at: Long) = MediaProgress(libraryItemId = "b", currentTime = pos, lastUpdate = at)

    @Test
    fun newestWins() {
        // Another device listened after this phone: take the server's spot.
        assertEquals(Decision.AdoptServer(500.0, 200), ProgressPolicy.reconcile(local(100.0, 100, true), server(500.0, 200)))
        // Never listened here: the server's spot.
        assertEquals(Decision.AdoptServer(500.0, 200), ProgressPolicy.reconcile(null, server(500.0, 200)))
        // Offline listening here is newer and unsent: send it.
        val newer = local(700.0, 300, true)
        assertEquals(Decision.SendLocal(newer), ProgressPolicy.reconcile(newer, server(500.0, 200)))
        assertEquals(Decision.SendLocal(newer), ProgressPolicy.reconcile(newer, null))
        // Newer but already sent, or nothing anywhere: nothing to do.
        assertEquals(Decision.InSync, ProgressPolicy.reconcile(local(700.0, 300, false), server(500.0, 200)))
        assertEquals(Decision.InSync, ProgressPolicy.reconcile(null, null))
        // A tie goes to the phone (the server isn't *newer*).
        assertEquals(Decision.InSync, ProgressPolicy.reconcile(local(100.0, 200, false), server(500.0, 200)))
    }

    @Test
    fun offlineUsesNewerOfPhoneAndLastListing() {
        assertEquals(500.0, ProgressPolicy.bestKnownPositionSec(local(100.0, 100, false), server(500.0, 200)))
        assertEquals(100.0, ProgressPolicy.bestKnownPositionSec(local(100.0, 300, false), server(500.0, 200)))
        assertEquals(500.0, ProgressPolicy.bestKnownPositionSec(null, server(500.0, 200)))
        assertEquals(0.0, ProgressPolicy.bestKnownPositionSec(null, null))
    }

    @Test
    fun rewindsOnlyAfterALongPause() {
        val now = 10_000_000L
        assertEquals(60_000, ProgressPolicy.rewindAfterPause(75_000, now - ProgressPolicy.REWIND_AFTER_PAUSE_MS, now))
        assertEquals(75_000, ProgressPolicy.rewindAfterPause(75_000, now - 60_000, now))
        assertEquals(75_000, ProgressPolicy.rewindAfterPause(75_000, 0, now)) // never paused
        assertEquals(0, ProgressPolicy.rewindAfterPause(5_000, 1, now)) // not before the start
    }

    @Test
    fun finishedBooksRestart() {
        assertEquals(0, ProgressPolicy.restartIfFinished(99_000, 100_000))
        assertEquals(97_000, ProgressPolicy.restartIfFinished(97_000, 100_000))
        assertEquals(5_000, ProgressPolicy.restartIfFinished(5_000, 0)) // unknown duration
    }

    @Test
    fun onlyRealMovementIsSaved() {
        assertTrue(ProgressPolicy.worthSaving(5_000, -1))
        assertFalse(ProgressPolicy.worthSaving(5_400, 5_000))
        assertTrue(ProgressPolicy.worthSaving(6_000, 5_000))
        assertTrue(ProgressPolicy.worthSaving(4_000, 5_000))
    }

    @Test
    fun mapsMultiFileBooksOntoOneTimeline() {
        // Three files: 0–90 s, 90–180 s, 180–270.5 s.
        val starts = listOf(0.0, 90.0, 180.0)
        assertEquals(TrackPosition(0, 0), trackPositionAt(starts, 0))
        assertEquals(TrackPosition(0, 89_999), trackPositionAt(starts, 89_999))
        assertEquals(TrackPosition(1, 0), trackPositionAt(starts, 90_000))
        assertEquals(TrackPosition(1, 45_000), trackPositionAt(starts, 135_000))
        assertEquals(TrackPosition(2, 90_500), trackPositionAt(starts, 270_500))
        assertEquals(TrackPosition(0, 0), trackPositionAt(starts, -10)) // clamps
        for (ms in listOf(0L, 89_999L, 90_000L, 135_000L, 200_123L)) {
            val at = trackPositionAt(starts, ms)
            assertEquals(ms, bookPositionMs(starts, at.index, at.withinMs))
        }
        // Single-file books: the timeline is the file.
        assertEquals(TrackPosition(0, 42_000), trackPositionAt(listOf(0.0), 42_000))
    }

    @Test
    fun endOfChapterTimerTargetsTheCurrentChapter() {
        val chapters = listOf(Chapter(0, 0.0, 60.5, "One"), Chapter(1, 60.5, 120.0, "Two"))
        assertEquals(60_500, chapterEndAfter(chapters, 10_000))
        assertEquals(120_000, chapterEndAfter(chapters, 60_500))
        assertEquals(120_000, chapterEndAfter(chapters, 500_000)) // past the end: the last chapter
        assertNull(chapterEndAfter(emptyList(), 0))
    }
}

/** Records what reaches the "server". [online] = false makes every call fail like no signal. */
private class FakeServer : ProgressServer {
    var online = true
    var progress: MediaProgress? = null
    var sessionGone = false
    val calls = mutableListOf<String>()

    private fun check() {
        if (!online) throw IOException("offline")
    }

    override suspend fun progressFor(itemId: String): MediaProgress? = check().let { progress }

    override suspend fun patchProgress(itemId: String, currentTimeSec: Double, durationSec: Double) {
        check()
        calls += "patch $currentTimeSec"
    }

    override suspend fun startPlayback(itemId: String): PlaybackSession {
        check()
        calls += "start"
        return PlaybackSession("s1", itemId)
    }

    override suspend fun syncSession(sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) {
        check()
        if (sessionGone) throw SessionGoneException()
        calls += "sync $currentTimeSec listened $timeListenedSec"
    }

    override suspend fun closeSession(sessionId: String, final: FinalSync?) {
        check()
        calls += if (final == null) "close" else "close ${final.currentTimeSec}"
    }
}

private class FakeLocal(var now: () -> Long) : LocalPositions {
    val positions = mutableMapOf<String, LocalPosition>()
    override fun get(itemId: String) = positions[itemId]
    override fun save(itemId: String, positionSec: Double, durationSec: Double): Long {
        val at = now()
        positions[itemId] = LocalPosition(positionSec, durationSec, at, unsynced = true)
        return at
    }
    override fun adoptServer(itemId: String, positionSec: Double, durationSec: Double, updatedAt: Long) {
        positions[itemId] = LocalPosition(positionSec, durationSec, updatedAt, unsynced = false)
    }
    override fun markSynced(itemId: String, at: Long) {
        val p = positions[itemId] ?: return
        if (p.updatedAt <= at) positions[itemId] = p.copy(unsynced = false)
    }
}

class ProgressSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var clock = 1_000_000L
    private val server = FakeServer()
    private val local = FakeLocal { clock }
    private val sync = ProgressSync(server, local, scope, dispatcher) { clock }

    @Test
    fun adoptsANewerServerPosition() = scope.runTest {
        local.positions["b"] = LocalPosition(100.0, 1000.0, 100, unsynced = false)
        server.progress = MediaProgress(libraryItemId = "b", currentTime = 600.0, lastUpdate = 200)
        assertEquals(600.0, sync.reconcile("b", 1000.0))
        assertEquals(LocalPosition(600.0, 1000.0, 200, unsynced = false), local.positions["b"])
    }

    @Test
    fun sendsNewerOfflineListening() = scope.runTest {
        local.positions["b"] = LocalPosition(700.0, 1000.0, 300, unsynced = true)
        server.progress = MediaProgress(libraryItemId = "b", currentTime = 600.0, lastUpdate = 200)
        assertNull(sync.reconcile("b", 1000.0))
        assertEquals(listOf("patch 700.0"), server.calls)
        assertFalse(local.positions["b"]!!.unsynced)
    }

    @Test
    fun offlineReconcileFailsWithoutTouchingThePhone() = scope.runTest {
        server.online = false
        val before = LocalPosition(700.0, 1000.0, 300, unsynced = true)
        local.positions["b"] = before
        assertTrue(sync.tryReconcile("b", 1000.0).isFailure)
        assertEquals(before, local.positions["b"])
    }

    @Test
    fun savesOnlyMovement() {
        sync.markSaved(10_000)
        sync.saveLocal("b", 10_400, 1000.0)
        assertNull(local.positions["b"])
        sync.saveLocal("b", 12_000, 1000.0)
        assertEquals(12.0, local.positions["b"]!!.positionSec)
    }

    @Test
    fun syncsPositionAndListeningTime() = scope.runTest {
        sync.adopt(PlaybackSession("s1", "b"))
        sync.markSaved(-1)
        sync.tick(wasPlaying = false, isPlaying = true)
        clock += 3_000
        sync.tick(wasPlaying = true, isPlaying = true)
        sync.saveLocal("b", 20_000, 1000.0)
        sync.syncNow("b", 20_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("sync 20.0 listened 3.0"), server.calls)
        assertFalse(local.positions["b"]!!.unsynced)
        // Nothing new since: nothing is sent.
        sync.syncNow("b", 20_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertEquals(1, server.calls.size)
    }

    @Test
    fun offlineSyncKeepsListeningTimeForLater() = scope.runTest {
        sync.adopt(PlaybackSession("s1", "b"))
        sync.markSaved(-1)
        sync.tick(wasPlaying = false, isPlaying = true)
        clock += 4_000
        sync.tick(wasPlaying = true, isPlaying = true)
        sync.saveLocal("b", 30_000, 1000.0)
        server.online = false
        sync.syncNow("b", 30_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertTrue(local.positions["b"]!!.unsynced)
        server.online = true
        sync.syncNow("b", 30_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("sync 30.0 listened 4.0"), server.calls)
    }

    @Test
    fun aDroppedSessionFallsBackToWritingProgress() = scope.runTest {
        sync.adopt(PlaybackSession("s1", "b"))
        sync.markSaved(-1)
        server.sessionGone = true
        sync.saveLocal("b", 40_000, 1000.0)
        sync.syncNow("b", 40_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("patch 40.0"), server.calls)
        // From now on progress is written directly, without trying the dead session.
        clock += 1_000
        sync.saveLocal("b", 50_000, 1000.0)
        sync.syncNow("b", 50_000, 1000.0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("patch 40.0", "patch 50.0"), server.calls)
    }

    @Test
    fun closingWithNothingNewDoesNotSendAnOldPosition() = scope.runTest {
        sync.adopt(PlaybackSession("s1", "b"))
        sync.markSaved(60_000)
        sync.close("b", 60_000, 1000.0)
        assertEquals(listOf("close"), server.calls)
        assertNull(sync.session)
    }

    @Test
    fun closingSendsTheFinalPosition() = scope.runTest {
        sync.adopt(PlaybackSession("s1", "b"))
        sync.markSaved(-1)
        sync.close("b", 70_000, 1000.0)
        assertEquals(listOf("close 70.0"), server.calls)
        assertFalse(local.positions["b"]!!.unsynced)
    }

    @Test
    fun closingOfflineWritesProgressLater() = scope.runTest {
        // No session (offline when the book opened): the final position is patched.
        sync.markSaved(-1)
        sync.close("b", 80_000, 1000.0)
        assertEquals(listOf("patch 80.0"), server.calls)
    }
}
