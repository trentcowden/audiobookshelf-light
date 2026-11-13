package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.R
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.downloads.DownloadState
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.playback.NowPlaying
import com.trentcowden.audiobookshelf.playback.Playback
import com.trentcowden.audiobookshelf.playback.SleepTimer
import com.trentcowden.audiobookshelf.playback.chapterEndAfter
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.SettingsStore

/**
 * Playback state lives in [Playback]. For a downloaded book that isn't loaded
 * yet, the screen keeps a *preview* position: the full player shows straight
 * away, seeking/skipping/chapters just move where Play will start, and the
 * server's position is checked in the background while you look.
 */
class PlayerViewModel(private val itemId: String) : LightViewModel<Unit>() {
    /** Result of the last menu action ("Marked as finished.", or what went wrong). */
    val notice = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /** Where Play starts for a book that isn't loaded (ms on the book timeline). */
    val previewPositionMs = MutableStateFlow((Playback.bestKnownPositionSec(itemId) * 1000).toLong())

    /** The server check finished (or you picked a spot), so Play can start at once. */
    private var previewSettled = false
    private var userMoved = false

    fun toggleScope() = SettingsStore.update { it.copy(chapterScopedBar = !it.chapterScopedBar) }

    private val durationSec: Double
        get() = Downloads.book(itemId)?.durationSec
            ?: LibraryCache.load()?.books?.firstOrNull { it.id == itemId }?.durationSec
            ?: 0.0

    fun markFinished() = changeProgress("Marked as finished.") { Playback.markFinished(itemId, durationSec) }

    fun markNotStarted() = changeProgress("Marked as not started.") { Playback.markNotStarted(itemId, durationSec) }

    private fun changeProgress(done: String, change: suspend () -> String?) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            val error = change()
            busy.value = false
            notice.value = error ?: done
            if (error == null) {
                userMoved = false
                previewPositionMs.value = (Playback.bestKnownPositionSec(itemId) * 1000).toLong()
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (Playback.state.value?.itemId == itemId || Downloads.book(itemId) == null) return
        viewModelScope.launch {
            Playback.refreshFromServer(itemId)
            if (!userMoved) previewPositionMs.value = (Playback.bestKnownPositionSec(itemId) * 1000).toLong()
            // Offline counts as settled too: the phone's copy is the best there is.
            previewSettled = true
        }
    }

    fun movePreview(targetMs: Long, durationMs: Long) {
        userMoved = true
        previewSettled = true
        previewPositionMs.value = targetMs.coerceIn(0, durationMs.coerceAtLeast(0))
    }

    /** Start playing from the preview — immediately if the position is settled. */
    fun play() {
        Playback.playBook(itemId, startAtMs = if (previewSettled) previewPositionMs.value else null)
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
        return PlayerViewModel(itemId)
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
                rightButton = LightBarButton.Icon(
                    painter = painterResource(R.drawable.ic_menu_white),
                    onClick = { openMenu(download) },
                    contentDescription = "Menu",
                ),
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
                    live != null -> LiveControls(live, speed, preview = false)
                    download is DownloadState.Done -> {
                        // Not loaded yet: show the whole player from the download
                        // and the best-known position; Play starts from there.
                        val previewMs by viewModel.previewPositionMs.collectAsState()
                        LiveControls(
                            NowPlaying(
                                itemId = itemId,
                                title = download.book.title,
                                author = download.book.author,
                                durationMs = (download.book.durationSec * 1000).toLong(),
                                positionMs = previewMs,
                                playing = false,
                                chapters = download.book.chapters,
                            ),
                            speed,
                            preview = true,
                        )
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
                val notice by viewModel.notice.collectAsState()
                val busy by viewModel.busy.collectAsState()
                (error ?: if (busy) "Updating…" else notice)?.let {
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
    private fun LiveControls(live: NowPlaying, speed: Float, preview: Boolean) {
        // A preview only moves where Play will start; live controls drive the player.
        val seekTo: (Long) -> Unit = { ms ->
            if (preview) viewModel.movePreview(ms, live.durationMs) else Playback.seekTo(ms)
        }
        val onOpenChapters = { openChapters(live, seekTo) }
        val themeColors by LightThemeController.colors.collectAsState()
        val chapterScoped = SettingsStore.settings.collectAsState().value.chapterScopedBar
        val sleepTimer by Playback.sleepTimer.collectAsState()
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
                // Seek the moment the bar is let go (dragging only previews).
                modifier = if (preview) Modifier else Modifier.onRelease { Playback.finishDrag() },
                colors = themeColors,
                progress = if (lengthMs > 0) withinMs.toFloat() / lengthMs else 0f,
                onValueChange = { fraction ->
                    if (lengthMs > 0) {
                        val target = startMs + (lengthMs * fraction.coerceIn(0f, 1f)).toLong()
                        if (preview) seekTo(target) else Playback.seekTo(target, dragging = true)
                    }
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
        // Speed, back / play / forward, sleep timer. Speed and the timer have
        // equal slots so play stays centered; play gets extra room either side.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.width(SPEED_SLOT.gridUnitsAsDp()),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(
                    text = formatSpeed(speed),
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    // Never clipped: may grow past the slot into the space beside it.
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .lightClickable { openSpeed() },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TransportButton(LightIcons.SKIP_BACKWARD_FIFTEEN, "Back 15 seconds") { seekTo(live.positionMs - 15_000) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(PLAY_ROOM))
            if (live.loading) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.width(3f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) { LightText(text = "…", variant = LightTextVariant.Heading) }
            } else {
                TransportButton(
                    if (live.playing) LightIcons.PAUSE else LightIcons.PLAY,
                    if (live.playing) "Pause" else "Play",
                ) { if (preview) viewModel.play() else Playback.togglePlay() }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(PLAY_ROOM))
            TransportButton(LightIcons.SKIP_FORWARD_FIFTEEN, "Forward 15 seconds") { seekTo(live.positionMs + 15_000) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            // Sleep timer, mirroring speed: a moon when off, time left when on.
            Box(
                modifier = Modifier.width(SPEED_SLOT.gridUnitsAsDp()),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val left = sleepTimerLeftMs(sleepTimer, live.positionMs, speed)
                val open = { openSleepTimer(live) }
                if (left == null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sleep_white),
                        contentDescription = "Sleep timer",
                        tint = LightThemeTokens.colors.content,
                        modifier = Modifier.lightClickable(onClick = open).size(1.8f.gridUnitsAsDp()),
                    )
                } else {
                    LightText(
                        text = formatCountdown(left),
                        variant = LightTextVariant.Fine, // like the times under the bar
                        maxLines = 1,
                        modifier = Modifier
                            .wrapContentWidth(Alignment.End, unbounded = true)
                            .lightClickable(onClick = open),
                    )
                }
            }
        }
    }

    private fun openSleepTimer(live: NowPlaying) {
        val current = when (Playback.sleepTimer.value) {
            null -> SleepChoice.OFF
            is SleepTimer.EndOfChapter -> SleepChoice.END_OF_CHAPTER
            is SleepTimer.Countdown -> null
        }
        val choices = SleepChoice.entries
            .filter { it != SleepChoice.END_OF_CHAPTER || live.chapters.size > 1 }
            .map { Choice(it.label, it) }
        navigateTo(screenFactory = { ChoiceScreen(it, "Sleep Timer", choices, selected = current) }) { choice ->
            Playback.setSleepTimer(
                when (choice) {
                    SleepChoice.OFF -> null
                    SleepChoice.END_OF_CHAPTER ->
                        chapterEndAfter(live.chapters, live.positionMs)?.let { SleepTimer.EndOfChapter(it) }
                    else -> SleepTimer.Countdown(choice.minutes * 60_000L)
                },
            )
        }
    }

    private fun openMenu(download: DownloadState?) {
        val choices = listOfNotNull(
            when (download) {
                is DownloadState.Done -> Choice("Remove download", BookAction.REMOVE_DOWNLOAD, "Your progress is kept.")
                null -> null
                else -> Choice("Cancel download", BookAction.REMOVE_DOWNLOAD)
            },
            Choice("Mark as finished", BookAction.MARK_FINISHED),
            Choice("Mark as not started", BookAction.MARK_NOT_STARTED, "Resets your progress."),
        )
        navigateTo(screenFactory = { ChoiceScreen(it, "", choices) }) { action ->
            when (action) {
                BookAction.REMOVE_DOWNLOAD -> Downloads.states.value[itemId]?.let { confirmRemove(it) }
                BookAction.MARK_FINISHED -> viewModel.markFinished()
                BookAction.MARK_NOT_STARTED ->
                    navigateTo(screenFactory = {
                        ConfirmScreen(it, "Mark “$title” as not started? Your progress will be reset.")
                    }) { confirmed -> if (confirmed) viewModel.markNotStarted() }
            }
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
        val choices = SettingsStore.speeds.map { Choice(formatSpeed(it), it) }
        navigateTo(screenFactory = { ChoiceScreen(it, "Speed", choices, selected = Playback.speed.value) }) {
            Playback.setSpeed(it)
        }
    }

    private fun openChapters(live: NowPlaying, seekTo: (Long) -> Unit) {
        val current = chapterIndexAt(live.chapters, live.positionMs)
        navigateTo(screenFactory = { ChaptersScreen(it, live.chapters, current) }) { index ->
            live.chapters.getOrNull(index)?.let { seekTo(chapterStartMs(it)) }
        }
    }
}

private enum class BookAction { REMOVE_DOWNLOAD, MARK_FINISHED, MARK_NOT_STARTED }

private enum class SleepChoice(val label: String, val minutes: Int = 0) {
    OFF("Off"),
    END_OF_CHAPTER("End of chapter"),
    FIVE("5 minutes", 5),
    TEN("10 minutes", 10),
    FIFTEEN("15 minutes", 15),
    THIRTY("30 minutes", 30),
    FORTY_FIVE("45 minutes", 45),
    HOUR("1 hour", 60),
}

/** Listening time until the sleep timer pauses, or null when it's off. */
private fun sleepTimerLeftMs(timer: SleepTimer?, positionMs: Long, speed: Float): Long? = when (timer) {
    null -> null
    is SleepTimer.Countdown -> timer.remainingMs
    is SleepTimer.EndOfChapter -> ((timer.endMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()
}

/**
 * Calls [onRelease] when the last finger lifts after touching this element.
 * Watches the touch before the child handles it (Initial pass) and consumes
 * nothing, so the child's own drag handling is unaffected.
 */
private fun Modifier.onRelease(onRelease: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        onRelease()
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

/** Width of the speed and sleep timer slots either side of the transport buttons. */
private const val SPEED_SLOT = 3.6f

/** Space either side of play, relative to the space outside the skip buttons. */
private const val PLAY_ROOM = 1.6f

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

/** "4:05", "60:00" (minutes, however many), "125m": short enough for the sleep timer slot. */
fun formatCountdown(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) + 999) / 1000 // never shows 0:00 while running
    val minutes = totalSeconds / 60
    return if (minutes >= 100) "${minutes}m" else "%d:%02d".format(minutes, totalSeconds % 60)
}

/** "640 MB", "1.2 GB". */
fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "${mb.toLong()} MB"
}

/** 1x, 1.25x, ... */
fun formatSpeed(speed: Float): String = "${speed.toString().trimEnd('0').trimEnd('.')}x"
