package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.playback.SettingsStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

class PickerViewModel<T> : LightViewModel<T>()

/**
 * Chapter list; returns the chosen index. The player applies the result, so a
 * selection is never lost to this screen's teardown.
 */
class ChaptersScreen(
    sealedActivity: SealedLightActivity,
    private val chapters: List<Chapter>,
    private val currentIndex: Int,
) : AbsScreen<Int, PickerViewModel<Int>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass = PickerViewModel::class.java as Class<PickerViewModel<Int>>
    override fun createViewModel() = PickerViewModel<Int>()

    @Composable
    override fun ScreenContent() {
        val scrollState = rememberScrollState()
        val rowTops = remember { mutableStateMapOf<Int, Int>() }
        // Open scrolled to the current chapter.
        LaunchedEffect(currentIndex) {
            val y = snapshotFlow { rowTops[currentIndex] }.filterNotNull().first()
            withFrameNanos { }
            scrollState.scrollTo(y.coerceAtMost(scrollState.maxValue))
        }
        AbsScreenScaffold {
            BackTopBar(title = "Chapters", onBack = { goBack() })
            LightScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                scrollState = scrollState,
            ) {
                chapters.forEachIndexed { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { rowTops[index] = it.positionInParent().y.roundToInt() }
                            .lightClickable { goBack(index) }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightText(
                            text = chapter.title.ifBlank { "Chapter ${index + 1}" },
                            variant = LightTextVariant.Copy,
                            underline = index == currentIndex,
                            modifier = Modifier.weight(1f),
                        )
                        val length = ((chapter.end - chapter.start) * 1000).toLong()
                        if (length > 0) {
                            LightText(
                                text = formatTime(length),
                                variant = LightTextVariant.Fine,
                                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Playback speed picker; returns the chosen speed. */
class SpeedScreen(sealedActivity: SealedLightActivity) :
    AbsScreen<Float, PickerViewModel<Float>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass = PickerViewModel::class.java as Class<PickerViewModel<Float>>
    override fun createViewModel() = PickerViewModel<Float>()

    @Composable
    override fun ScreenContent() {
        val current = SettingsStore.speed
        AbsScreenScaffold {
            BackTopBar(title = "Speed", onBack = { goBack() })
            LightScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
            ) {
                SettingsStore.speeds.forEach { speed ->
                    LightText(
                        text = formatSpeed(speed),
                        variant = LightTextVariant.Heading,
                        underline = speed == current,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { goBack(speed) }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
