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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.downloads.DownloadState

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
                val detail = listOfNotNull(row.author, downloadLabel).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    LightText(
                        text = detail,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.2f.gridUnitsAsDp()),
                    )
                }
                primarySeries(row.series)?.let { series ->
                    LightText(
                        text = series,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val label = when {
                row.finished -> "done"
                row.progress != null -> "${(row.progress * 100).toInt().coerceIn(1, 99)}%"
                else -> null
            }
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

/**
 * The first series of an ABS series list ("Dune #1, The Dune Sequence #12" →
 * "Dune #1"). The listing joins several series with ", ", so split only where
 * a ", " follows a "#number" — series names themselves may contain commas.
 */
fun primarySeries(seriesName: String?): String? {
    val name = seriesName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val boundary = Regex("""#[\d.]+, """).find(name) ?: return name
    return name.substring(0, boundary.range.last - 1)
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

/**
 * Every word of the query must appear somewhere in the title, author, series
 * or narrator (case- and accent-insensitive). Title matches sort first.
 */
fun searchBooks(books: List<BookRow>, query: String): List<BookRow> {
    val words = normalize(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return books
        .map { book ->
            val title = normalize(book.title)
            val haystack = listOfNotNull(title, book.author, book.series, book.narrator)
                .joinToString(" ") { normalize(it) }
            book to (words.all { haystack.contains(it) } to words.all { title.contains(it) })
        }
        .filter { (_, match) -> match.first }
        .sortedByDescending { (_, match) -> match.second }
        .map { it.first }
}

private fun normalize(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9 ]+"), " ")
