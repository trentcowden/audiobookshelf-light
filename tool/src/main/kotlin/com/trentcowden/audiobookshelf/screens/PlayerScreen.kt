package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.playback.NowPlaying
import com.trentcowden.audiobookshelf.playback.Playback
import com.trentcowden.audiobookshelf.playback.SettingsStore
import com.trentcowden.audiobookshelf.downloads.DownloadState
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.thelightphone.sdk.ui.LightTopBar

/** Playback state lives in [Playback]; the screen only keeps its bar-scope preference. */
class PlayerViewModel : LightViewModel<Unit>() {
    val chapterScoped = kotlinx.coroutines.flow.MutableStateFlow(SettingsStore.chapterScopedBar)

    fun toggleScope() {
        val next = !chapterScoped.value
        chapterScoped.value = next
        SettingsStore.chapterScopedBar = next
    }
}

class PlayerScreen(
    sealedActivity: SealedLightActivity,
    private val itemId: String,
    private val title: String,
    private val author: String?,
) : AbsScreen<Unit, PlayerViewModel>(sealedActivity) {

    override val viewModelClass = PlayerViewModel::class.java

    override fun createViewModel(): PlayerViewModel {
        AppInit.init(lightContext.filesDir)
        return PlayerViewModel()
    }

    @Composable
    override fun ScreenContent() {
        val nowPlaying by Playback.state.collectAsState()
        val error by Playback.error.collectAsState()
        val speed by Playback.speed.collectAsState()
        // Live = this book is the one loaded on the player. Otherwise this is a
        // preview and Play starts it (replacing whatever was playing).
        val live = nowPlaying?.takeIf { it.itemId == itemId }
        val downloads by Downloads.states.collectAsState()
        val download = downloads[itemId]

        AbsScreenScaffold {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                    contentDescription = "Back",
                ),
                // Downloaded (or partly downloaded) books can be removed to free space.
                rightButton = if (download != null && download !is DownloadState.Queued) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.TRASH,
                        onClick = { confirmRemove(download) },
                        contentDescription = "Remove download",
                    )
                } else {
                    null
                },
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .padding(bottom = 1f.gridUnitsAsDp()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LightText(
                        text = live?.title ?: title,
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        maxLines = 3,
                        lineHeight = 1.15.em, // wrapped titles read as one block
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (live?.author ?: author)?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                // Everything below the title shares the rest of the screen,
                // spread out so the controls have room to breathe.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                when {
                    live != null -> LiveControls(live, speed, onOpenChapters = { openChapters(live) })
                    download is DownloadState.Done -> BigButton(LightIcons.PLAY, "Play") {
                        Playback.playBook(itemId)
                    }
                    download is DownloadState.Queued -> Status("Waiting to download…")
                    download is DownloadState.Running -> Column(modifier = Modifier.fillMaxWidth()) {
                        val fraction = download.doneBytes.toFloat() / download.totalBytes.coerceAtLeast(1)
                        Status("Downloading… ${(fraction * 100).toInt().coerceIn(0, 99)}%")
                        LightText(
                            text = "${formatMegabytes(download.doneBytes)} of ${formatMegabytes(download.totalBytes)}" +
                                " · keep the tool open",
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    download is DownloadState.Failed -> Column(modifier = Modifier.fillMaxWidth()) {
                        Status(if (download.message == Downloads.PAUSED) "Download paused." else download.message)
                        BigButton(LightIcons.DOWNLOAD_ARROW, "Resume download") {
                            Downloads.download(itemId, title, author)
                        }
                    }
                    else -> Column(modifier = Modifier.fillMaxWidth()) {
                        Status("Download to listen")
                        BigButton(LightIcons.DOWNLOAD_ARROW, "Download") {
                            Downloads.download(itemId, title, author)
                        }
                    }
                }
                error?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                }
            }
        }
    }

    @Composable
    private fun LiveControls(live: NowPlaying, speed: Float, onOpenChapters: () -> Unit) {
        val themeColors by LightThemeController.colors.collectAsState()
        val chapterScoped by viewModel.chapterScoped.collectAsState()
        val chapterIndex = chapterIndexAt(live.chapters, live.positionMs)
        val chapter = live.chapters.getOrNull(chapterIndex)
        // The bar covers the whole book by default (so you always see how far
        // through you are). Tapping the times row switches it to the current
        // chapter, for finer seeking — a 20-hour book on one bar is coarse.
        val useChapter = chapterScoped && chapter != null
        val startMs = if (useChapter) chapterStartMs(chapter!!) else 0L
        val lengthMs = if (useChapter) ((chapter!!.end - chapter.start) * 1000).toLong() else live.durationMs
        val withinMs = (live.positionMs - startMs).coerceIn(0, lengthMs.coerceAtLeast(0))

        // One group: chapter title, bar, and the times/percent row.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (chapter != null && live.chapters.size > 1) {
                LightText(
                    text = chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" },
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = onOpenChapters)
                        .padding(bottom = 0.75f.gridUnitsAsDp()),
                )
            }
            LightTouchableProgressBar(
                colors = themeColors,
                progress = if (lengthMs > 0) withinMs.toFloat() / lengthMs else 0f,
                onValueChange = { fraction ->
                    if (lengthMs > 0) Playback.seekTo(startMs + (lengthMs * fraction.coerceIn(0f, 1f)).toLong())
                },
            )
            // Elapsed · percent · remaining, all for the bar's scope. Tapping
            // anywhere on the row switches book ↔ chapter (like BookPlayer).
            val percent = if (lengthMs > 0) (withinMs * 100 / lengthMs).toInt().coerceIn(0, 100) else 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { if (live.chapters.size > 1) viewModel.toggleScope() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = formatTime(withinMs),
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.weight(1f),
                )
                LightText(text = "$percent%", variant = LightTextVariant.Fine, align = TextAlign.Center)
                LightText(
                    // Listening time left at the current speed, as BookPlayer shows it.
                    text = "-" + formatTime(((lengthMs - withinMs) / speed.coerceAtLeast(0.1f)).toLong()),
                    variant = LightTextVariant.Fine,
                    align = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Speed on the left, then back / play / forward, spaced evenly across
        // the row. A matching empty slot on the right keeps play centered.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.width(SPEED_SLOT.gridUnitsAsDp()),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(
                    text = formatSpeed(speed),
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.lightClickable { openSpeed() },
                )
            }
            TransportButton(LightIcons.SKIP_BACKWARD_FIFTEEN, "Back 15 seconds") { Playback.seekBy(-15_000) }
            if (live.loading) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.width(3f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) { LightText(text = "…", variant = LightTextVariant.Heading) }
            } else {
                TransportButton(
                    if (live.playing) LightIcons.PAUSE else LightIcons.PLAY,
                    if (live.playing) "Pause" else "Play",
                ) { Playback.togglePlay() }
            }
            TransportButton(LightIcons.SKIP_FORWARD_FIFTEEN, "Forward 15 seconds") { Playback.seekBy(15_000) }
            androidx.compose.foundation.layout.Spacer(Modifier.width(SPEED_SLOT.gridUnitsAsDp()))
        }
    }


    @Composable
    private fun Status(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun BigButton(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 1.5f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.Center,
        ) {
            TransportButton(icon, description, onClick)
        }
    }

    private fun confirmRemove(download: DownloadState) {
        val question = if (download is DownloadState.Done) {
            "Remove the download of “$title”? Your progress is kept."
        } else {
            "Cancel this download?"
        }
        navigateTo(screenFactory = { ConfirmScreen(it, question) }) { confirmed ->
            if (confirmed) {
                Playback.unload(itemId)
                Downloads.remove(itemId)
            }
        }
    }

    @Composable
    private fun TransportButton(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
        LightIcon(
            icon = icon,
            size = 3f,
            modifier = Modifier.lightClickable(onClick = onClick),
            contentDescription = description,
        )
    }

    private fun openSpeed() {
        navigateTo(screenFactory = { SpeedScreen(it) }) { Playback.setSpeed(it) }
    }

    private fun openChapters(live: NowPlaying) {
        val current = chapterIndexAt(live.chapters, live.positionMs)
        navigateTo(screenFactory = { ChaptersScreen(it, live.chapters, current) }) { index ->
            live.chapters.getOrNull(index)?.let { Playback.seekTo(chapterStartMs(it)) }
        }
    }
}

/** Index of the chapter containing [positionMs] (book timeline), clamped to the list. */
fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int {
    if (chapters.isEmpty()) return 0
    val sec = positionMs / 1000.0
    // Small tolerance: chapter starts are fractional seconds, positions whole ms.
    val index = chapters.indexOfFirst { sec + 0.001 < it.end }
    return if (index >= 0) index else chapters.lastIndex
}

/** Width of the speed label's slot (and the matching empty slot that keeps play centered). */
private const val SPEED_SLOT = 2.5f

/** Chapter start in ms, rounded up so seeking there lands inside the chapter. */
fun chapterStartMs(chapter: Chapter): Long = kotlin.math.ceil(chapter.start * 1000).toLong()

/** m:ss or h:mm:ss. */
fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "14h 2m", "38m", "<1m" — for time left in a book. */
fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0) / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        else -> "<1m"
    }
}

/** "640 MB", "1.2 GB". */
fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "${mb.toLong()} MB"
}

/** 1x, 1.25x, ... */
fun formatSpeed(speed: Float): String = "${speed.toString().trimEnd('0').trimEnd('.')}x"
