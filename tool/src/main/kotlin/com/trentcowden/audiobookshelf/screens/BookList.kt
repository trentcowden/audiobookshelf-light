package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.thelightphone.sdk.ui.LightThemeTokens
import com.trentcowden.audiobookshelf.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.downloads.DownloadState
import com.trentcowden.audiobookshelf.library.BookRow
import com.trentcowden.audiobookshelf.library.primarySeries
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.thelightphone.sdk.ui.LightLazyScrollView

/**
 * A lazily composed list of books, so a library of thousands scrolls as
 * smoothly as one of ten. The SDK's lazy list sizes its scroll bar from one
 * item height; rows here vary (wrapped titles, series), so it gets the
 * average height of the rows seen so far.
 */
@Composable
fun BookList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val listState = rememberLazyListState()
    val unitPx = with(LocalDensity.current) { 1f.gridUnitsAsDp().toPx() }
    var averageHeight by remember { mutableFloatStateOf(INITIAL_ROW_HEIGHT) }
    LaunchedEffect(listState, unitPx) {
        val heights = mutableMapOf<Any, Int>()
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key to it.size } }.collect { visible ->
            visible.forEach { (key, size) -> heights[key] = size }
            if (heights.isNotEmpty()) {
                val average = heights.values.average().toFloat() / unitPx
                // Ignore tiny changes so the scroll bar doesn't twitch.
                if (kotlin.math.abs(average - averageHeight) > averageHeight * 0.02f) averageHeight = average
            }
        }
    }
    LightLazyScrollView(
        modifier = modifier,
        listState = listState,
        uniformItemHeightGridUnits = averageHeight,
        content = content,
    )
}

/** Starting guess for a row's height (grid units) until real rows are measured. */
private const val INITIAL_ROW_HEIGHT = 4f

/** A book in a list: title, author, and how far through it you are. */
@Composable
fun BookListRow(row: BookRow, download: DownloadState?, onClick: () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        // Left-margin marker for downloaded books, like the LightOS podcast tool.
        Box(
            modifier = Modifier.width(LIST_GUTTER.gridUnitsAsDp()).padding(top = 0.45f.gridUnitsAsDp()),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (download is DownloadState.Done) {
                LightIcon(icon = LightIcons.DOWNLOADED_ARROW, size = 1.1f, contentDescription = "Downloaded")
            }
        }
        BookRowBody(row, downloadLabel(download), themeColors)
    }
}

/** Width of the left margin that holds the downloaded marker; section labels indent by it too. */
const val LIST_GUTTER = 1.9f

@Composable
private fun RowScope.BookRowBody(row: BookRow, downloadLabel: String?, themeColors: com.thelightphone.sdk.ui.LightColors) {
    Column(modifier = Modifier.weight(1f)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).alignByBaseline()) {
                // Copy's 1.5× line height is made for single lines; a wrapped
                // title should read as one block, with the author just below.
                LightText(
                    text = row.title,
                    variant = LightTextVariant.Copy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 1.15.em,
                )
                val series = primarySeries(row.series)
                if (series != null) {
                    LightText(
                        text = series,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Tight under the title (it describes the title); the
                        // author gets a little more room below.
                        lineHeight = 1.15.em,
                    )
                }
                val detail = listOfNotNull(row.author, downloadLabel).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    LightText(
                        text = detail,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = if (series == null) 0.2f.gridUnitsAsDp() else 0.35f.gridUnitsAsDp()),
                    )
                }
            }
            if (row.finished) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_white),
                    contentDescription = "Finished",
                    tint = LightThemeTokens.colors.content,
                    modifier = Modifier
                        .padding(start = 0.5f.gridUnitsAsDp(), top = 0.3f.gridUnitsAsDp())
                        .size(1.15f.gridUnitsAsDp()),
                )
            }
            val label = row.progress?.takeIf { !row.finished }?.let { "${(it * 100).toInt().coerceIn(1, 99)}%" }
            if (label != null) {
                LightText(
                    text = label,
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.alignByBaseline().padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
        }
        if (row.progress != null && !row.finished) {
            Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
            LightProgressBar(colors = themeColors, progress = row.progress)
        }
    }
}

/** Short download status for a list row (collect [Downloads.states] once per screen). */
fun downloadLabel(state: DownloadState?): String? = when (state) {
    is DownloadState.Done -> null // shown as an icon in the left margin
    is DownloadState.Running ->
        "downloading ${(state.doneBytes * 100 / state.totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 99)}%"
    is DownloadState.Queued -> "waiting to download"
    is DownloadState.Failed -> "download paused"
    null -> null
}
