package com.trentcowden.audiobookshelf.playback

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightPlayerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tool's one detached audio player handle, and the rules that keep it
 * from wedging.
 *
 * The SDK allows one detached handle per process, and a handle that is never
 * released holds that slot (and keeps the playback service alive) forever.
 * LightActivity has no onDestroy, so a handle owned by a screen leaks when
 * Android tears the activity down; every later `newPlayer()` then throws and
 * every tap silently does nothing until the phone reboots. So here:
 *
 * - exactly one handle exists, owned by this slot (itself owned by the
 *   process-wide [Playback]), created on demand by [ensure];
 * - it is released when its activity goes away ([onActivityGone]), so it
 *   never outlives the activity it was bound through (light-sdk #299);
 * - it is re-checked before every use and rebuilt when it's dead;
 * - [probe] proves a new player can be built (Settings → Reset Player).
 *
 * Not thread-safe: call on the main thread under [Playback]'s lock.
 */
internal class PlayerSlot(
    private val scope: CoroutineScope,
    private val events: Events,
    private val speed: () -> Float,
) {
    interface Events {
        /** [observedPause]: a playing → paused change seen live (not the state found at connect). */
        fun onPlayingChanged(playing: Boolean, observedPause: Boolean)
        fun onPositionChanged(handle: LightAudioPlayer)
        fun onDurationKnown(handle: LightAudioPlayer)
        /** A new playback failure (not one already present when we connected). */
        fun onPlaybackError(diagnostic: String)
        /** A fresh handle is connected and observed: read the real state from it. */
        fun onConnected(handle: LightAudioPlayer)
        /** Building or connecting a handle failed; [message] is for the user. */
        fun onSlotError(message: String)
    }

    private var audio: LightAudio? = null
    /** Which activity [audio] belongs to (see ActivityWatch). */
    private var audioGeneration = -1
    private var handle: LightAudioPlayer? = null
    /** Which activity [handle] was created through. */
    private var handleGeneration = -1
    private var observation: Job? = null

    val current: LightAudioPlayer? get() = handle

    /** The handle if it's connected right now, without building or waiting. */
    val ready: LightAudioPlayer?
        get() = handle?.takeIf { it.availability.value == LightAudioPlayerAvailability.Ready }

    /** A handle bound through an earlier activity, which must not be reused. */
    val holdsStaleHandle: Boolean get() = handle != null && handleGeneration != audioGeneration

    /** Remembers how to build a player from the activity now showing. */
    fun attach(lightAudio: LightAudio, generation: Int) {
        audio = lightAudio
        audioGeneration = generation
    }

    /**
     * The activity [generation] is being destroyed. If it owns the handle,
     * release it *now*, while its binding is still valid — detached playback
     * carries on in the SDK's service, and the next activity reconnects with
     * a fresh handle. [beforeRelease] gets a last look at the handle's state.
     * Returns true if a handle was released.
     */
    fun onActivityGone(generation: Int, beforeRelease: (LightAudioPlayer) -> Unit): Boolean {
        if (generation == audioGeneration) audio = null
        // A newer activity already owns the player — nothing of ours to release.
        if (generation != handleGeneration) return false
        val h = handle ?: return false
        beforeRelease(h)
        drop()
        return true
    }

    /** Returns a live, connected handle — building a new one if needed. */
    suspend fun ensure(): LightAudioPlayer? {
        handle?.let { h ->
            when (h.availability.value) {
                LightAudioPlayerAvailability.Ready -> return h
                LightAudioPlayerAvailability.Initializing ->
                    if (withTimeoutOrNull(READY_TIMEOUT_MS) { h.awaitReady() } == true) return h
                else -> Unit
            }
            drop()
        }
        val lightAudio = audio ?: run {
            events.onSlotError("Player unavailable. Reopen the tool.")
            return null
        }
        val h = try {
            lightAudio.newPlayer(PLAYER_SPEC)
        } catch (first: Throwable) {
            try {
                lightAudio.newPlayer(FALLBACK_SPEC)
            } catch (e: Throwable) {
                events.onSlotError("Couldn't start the audio player (${first.message}). Try Settings → Reset Player.")
                return null
            }
        }
        handle = h
        handleGeneration = audioGeneration
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { h.awaitReady() } != true) {
            drop()
            events.onSlotError("The audio player didn't respond. Try again, or Settings → Reset Player.")
            return null
        }
        h.speed = speed()
        observe(h)
        events.onConnected(h)
        return h
    }

    /** Releases the handle (if any) and stops observing it. */
    fun drop() {
        observation?.cancel()
        observation = null
        val h = handle ?: return
        handle = null
        runCatching { h.release() }
    }

    /**
     * Proves a player can be created again. False means the SDK still refuses
     * (a detached slot held by something we can't reach).
     */
    suspend fun probe(): Boolean {
        val lightAudio = audio ?: return true
        val probe = runCatching { lightAudio.newPlayer(PLAYER_SPEC) }.getOrNull()
            ?: runCatching { lightAudio.newPlayer(FALLBACK_SPEC) }.getOrNull()
            ?: return false
        withTimeoutOrNull(READY_TIMEOUT_MS) { probe.awaitReady() }
        runCatching { probe.release() }
        return true
    }

    private fun observe(h: LightAudioPlayer) {
        observation?.cancel()
        val errorAtConnect = h.error.value
        observation = scope.launch {
            launch {
                var previous: Boolean? = null
                h.isPlaying.collect { playing ->
                    events.onPlayingChanged(playing, observedPause = !playing && previous == true)
                    previous = playing
                }
            }
            launch { h.positionMs.collect { events.onPositionChanged(h) } }
            launch { h.currentMediaItemIndex.collect { events.onPositionChanged(h) } }
            launch { h.durationMs.collect { if (it > 0) events.onDurationKnown(h) } }
            launch {
                h.error.collect { e ->
                    if (e != null && e !== errorAtConnect) events.onPlaybackError(e.diagnostic)
                }
            }
        }
    }

    companion object {
        private const val READY_TIMEOUT_MS = 4_000L
        private const val PLAY_START_TIMEOUT_MS = 6_000L

        /**
         * Speech, so the lock screen and headset buttons get seek back/forward
         * (Music would get previous/next track instead — useless for a book).
         */
        private val PLAYER_SPEC = LightPlayerSpec(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached)

        /**
         * Fallback when the SDK refuses [PLAYER_SPEC]: if a headset button revived
         * the playback service by itself, it comes back as Music, and a detached
         * session can't change usage until it ends. Playing as Music beats not
         * playing at all.
         */
        private val FALLBACK_SPEC = LightPlayerSpec(usage = LightAudioUsage.Music, playback = LightAudioPlayback.Detached)

        /** Waits for [h] to report playing. */
        suspend fun awaitPlaying(h: LightAudioPlayer): Boolean =
            withTimeoutOrNull(PLAY_START_TIMEOUT_MS) {
                h.isPlaying.first { it }
                true
            } ?: false
    }
}
