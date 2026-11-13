package com.trentcowden.audiobookshelf.library

import com.trentcowden.audiobookshelf.api.LibraryItem
import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.storage.LibrarySort
import com.trentcowden.audiobookshelf.storage.LocalPosition
import kotlinx.serialization.Serializable

/**
 * A book as the library list needs it: just the text it shows and sorts by.
 * Kept small on purpose — the whole catalog is cached on the phone and held
 * in memory, and big libraries have thousands of books.
 */
@Serializable
data class CatalogBook(
    val id: String,
    val libraryId: String = "",
    val title: String,
    /** Title without a leading article, for sorting. */
    val sortTitle: String = title,
    val author: String? = null,
    /** "Last, First", for sorting. */
    val sortAuthor: String? = null,
    val series: String? = null,
    val narrator: String? = null,
    val addedAt: Long = 0,
    val durationSec: Double = 0.0,
)

/** One row in a book list. */
data class BookRow(
    val id: String,
    val title: String,
    val author: String?,
    /** 0..1, or null when never started. */
    val progress: Float?,
    val finished: Boolean,
    val series: String? = null,
    val narrator: String? = null,
)

/** Items worth listing: books with audio (ebook-only items can't be played). */
fun LibraryItem.isListenable(): Boolean = mediaType == "book" && media.numTracks != 0

fun LibraryItem.toCatalogBook(): CatalogBook {
    val m = media.metadata
    val title = m.title?.takeIf { it.isNotBlank() } ?: "Untitled"
    return CatalogBook(
        id = id,
        libraryId = libraryId,
        title = title,
        sortTitle = m.titleIgnorePrefix?.takeIf { it.isNotBlank() } ?: title,
        author = m.authorName?.takeIf { it.isNotBlank() },
        sortAuthor = m.authorNameLF?.takeIf { it.isNotBlank() } ?: m.authorName?.takeIf { it.isNotBlank() },
        series = m.seriesName?.takeIf { it.isNotBlank() },
        narrator = m.narratorName?.takeIf { it.isNotBlank() },
        addedAt = addedAt,
        durationSec = media.duration,
    )
}

fun sortBooks(books: List<CatalogBook>, sort: LibrarySort): List<CatalogBook> {
    val byTitle = compareBy(String.CASE_INSENSITIVE_ORDER, CatalogBook::sortTitle)
    return when (sort) {
        LibrarySort.TITLE -> books.sortedWith(byTitle)
        // No author sorts last.
        // By author; within an author, each series in reading order (placed
        // by series name among the standalone titles). No author sorts last.
        LibrarySort.AUTHOR -> {
            val series = books.associate { it.id to seriesPosition(it.series) }
            books.sortedWith(
                compareBy<CatalogBook> { it.sortAuthor == null }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.sortAuthor.orEmpty() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { series[it.id]?.name ?: it.sortTitle }
                    .thenBy(nullsLast()) { series[it.id]?.sequence }
                    .then(byTitle),
            )
        }
        LibrarySort.RECENTLY_ADDED -> books.sortedWith(compareByDescending<CatalogBook> { it.addedAt }.then(byTitle))
    }
}

/**
 * The row for [book]: progress from the server, unless this phone has newer
 * listening (offline) — then the phone's position.
 */
fun bookRow(book: CatalogBook, server: MediaProgress?, local: LocalPosition?): BookRow {
    val phoneIsNewer = local != null && (server == null || local.updatedAt > server.lastUpdate)
    val fraction = if (phoneIsNewer) {
        local!!.takeIf { it.durationSec > 0 }?.let { (it.positionSec / it.durationSec).toFloat() }
    } else {
        server?.progress?.toFloat()
    }
    return BookRow(
        id = book.id,
        title = book.title,
        author = book.author,
        progress = fraction?.takeIf { it > 0f }?.coerceAtMost(1f),
        finished = server?.isFinished == true && !phoneIsNewer,
        series = book.series,
        narrator = book.narrator,
    )
}

/** A book's place in its (first) series: "Dune #2" → ("Dune", 2.0). */
data class SeriesPosition(val name: String, val sequence: Double?)

fun seriesPosition(seriesName: String?): SeriesPosition? {
    val first = primarySeries(seriesName) ?: return null
    val match = Regex("""^(.*?)\s*#([\d.]+)$""").find(first)
        ?: return SeriesPosition(first, null)
    return SeriesPosition(match.groupValues[1], match.groupValues[2].toDoubleOrNull())
}

/** Whether a book is finished, and whether it's been started at all (finished counts). */
data class ProgressState(val finished: Boolean, val started: Boolean)

/**
 * From the server's progress and the phone's: finished as the server says,
 * unless the phone has listened since; started when there's any position.
 */
fun progressState(server: MediaProgress?, local: LocalPosition?): ProgressState {
    val phoneIsNewer = local != null && (server == null || local.updatedAt > server.lastUpdate)
    val finished = server?.isFinished == true && !phoneIsNewer
    val positionSec = if (phoneIsNewer) local!!.positionSec else server?.currentTime ?: local?.positionSec ?: 0.0
    return ProgressState(finished = finished, started = finished || positionSec >= 1.0)
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
