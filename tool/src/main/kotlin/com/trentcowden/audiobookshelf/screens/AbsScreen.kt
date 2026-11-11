package com.trentcowden.audiobookshelf.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.playback.Playback

/**
 * Detects the activity being torn down, which the SDK doesn't report
 * (LightActivity has no onDestroy, and a destroyed activity's screens are never
 * destroyed or their view models cleared).
 *
 * LightActivity composes only the current screen, and on navigation it calls
 * the next screen's willShow *before* the old screen's content leaves
 * composition. So when a screen's content is disposed while it is still the
 * most recently shown screen, nothing replaced it: the activity is going away.
 * That's the moment to release the audio player handle — synchronously, while
 * the binding it was created through is still valid.
 */
object ActivityWatch {
    @Volatile
    private var current: Any? = null

    /**
     * Activity "generation": bumped each time LightActivity builds its initial
     * screen (= a new activity). Every other screen is pushed from the
     * foreground activity, so it inherits the generation current at its
     * construction. Lets the player tell which activity a handle belongs to.
     */
    @Volatile
    var generation = 0
        private set

    fun newActivity(): Int = ++generation

    fun shown(screen: Any) {
        current = screen
    }

    @Composable
    fun Track(screen: Any, generation: Int) {
        DisposableEffect(screen) {
            onDispose {
                if (current === screen) {
                    current = null
                    Playback.onActivityGone(generation)
                }
            }
        }
    }
}

/** Common wiring for this tool's screens: stores, the shared player, teardown detection. */
abstract class AbsScreen<R, VM : LightViewModel<R>>(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<R, VM>(sealedActivity) {

    /** The initial screen marks a new activity; pushed screens join the current one. */
    private val generation =
        if (this is LibraryScreen) ActivityWatch.newActivity() else ActivityWatch.generation

    override fun willShow() {
        super.willShow()
        AppInit.init(lightContext.filesDir)
        ActivityWatch.shown(this)
        Playback.attach(DefaultLightAudio(sealedActivity), generation)
    }

    @Composable
    final override fun Content() {
        ActivityWatch.Track(this, generation)
        ScreenContent()
    }

    @Composable
    abstract fun ScreenContent()
}

/** The same wiring for screens without a view model (the text editor). */
abstract class AbsSimpleScreen<R>(
    private val sealedActivity: SealedLightActivity,
) : SimpleLightScreen<R>(sealedActivity) {

    private val generation = ActivityWatch.generation

    override fun willShow() {
        super.willShow()
        ActivityWatch.shown(this)
        Playback.attach(DefaultLightAudio(sealedActivity), generation)
    }

    @Composable
    final override fun Content() {
        ActivityWatch.Track(this, generation)
        ScreenContent()
    }

    @Composable
    abstract fun ScreenContent()
}
