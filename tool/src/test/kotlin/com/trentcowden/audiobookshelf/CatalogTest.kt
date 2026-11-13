package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.api.BookMedia
import com.trentcowden.audiobookshelf.api.BookMetadata
import com.trentcowden.audiobookshelf.api.ItemDetails
import com.trentcowden.audiobookshelf.api.ItemDetailsMedia
import com.trentcowden.audiobookshelf.api.ItemTrack
import com.trentcowden.audiobookshelf.api.LibraryItem
import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.api.PermanentDownloadException
import com.trentcowden.audiobookshelf.api.TrackFileMetadata
import com.trentcowden.audiobookshelf.downloads.manifestFrom
import com.trentcowden.audiobookshelf.library.CatalogBook
import com.trentcowden.audiobookshelf.library.bookRow
import com.trentcowden.audiobookshelf.library.isListenable
import com.trentcowden.audiobookshelf.library.sortBooks
import com.trentcowden.audiobookshelf.library.toCatalogBook
import com.trentcowden.audiobookshelf.storage.LibrarySort
import com.trentcowden.audiobookshelf.storage.LocalPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private fun item(id: String, tracks: Int?, mediaType: String = "book") =
        LibraryItem(id = id, mediaType = mediaType, media = BookMedia(metadata = BookMetadata(title = id), numTracks = tracks))

    @Test
    fun hidesItemsWithoutAudio() {
        assertTrue(item("a", 3).isListenable())
        assertFalse(item("ebook", 0).isListenable())
        assertTrue(item("unknown", null).isListenable()) // older servers may not say: keep it
        assertFalse(item("pod", 2, mediaType = "podcast").isListenable())
    }

    @Test
    fun usesTheServersSortFields() {
        val book = LibraryItem(
            id = "1",
            libraryId = "l",
            addedAt = 99,
            media = BookMedia(
                metadata = BookMetadata(
                    title = "The Hobbit",
                    titleIgnorePrefix = "Hobbit",
                    authorName = "J. R. R. Tolkien",
                    authorNameLF = "Tolkien, J. R. R.",
                ),
                duration = 3600.0,
            ),
        ).toCatalogBook()
        assertEquals(CatalogBook("1", "l", "The Hobbit", "Hobbit", "J. R. R. Tolkien", "Tolkien, J. R. R.", addedAt = 99, durationSec = 3600.0), book)
        assertEquals("Untitled", item("x", 1).copy(media = BookMedia()).toCatalogBook().title)
    }

    @Test
    fun sortsByTitleAuthorOrDateAdded() {
        val books = listOf(
            CatalogBook("1", title = "The Way of Kings", sortTitle = "Way of Kings", sortAuthor = "Sanderson, Brandon", addedAt = 1),
            CatalogBook("2", title = "Dune", sortAuthor = "Herbert, Frank", addedAt = 3),
            CatalogBook("3", title = "anonymous tales", sortAuthor = null, addedAt = 2),
            CatalogBook("4", title = "Mistborn", sortAuthor = "Sanderson, Brandon", addedAt = 4),
        )
        assertEquals(listOf("3", "2", "4", "1"), sortBooks(books, LibrarySort.TITLE).map { it.id })
        // By author, then title; no author last.
        assertEquals(listOf("2", "4", "1", "3"), sortBooks(books, LibrarySort.AUTHOR).map { it.id })
        assertEquals(listOf("4", "2", "3", "1"), sortBooks(books, LibrarySort.RECENTLY_ADDED).map { it.id })
    }

    @Test
    fun rowsPreferNewerListeningOnThePhone() {
        val book = CatalogBook("1", title = "Dune")
        val server = MediaProgress(libraryItemId = "1", progress = 0.25, lastUpdate = 100)
        assertEquals(0.25f, bookRow(book, server, null).progress)
        val offline = LocalPosition(positionSec = 50.0, durationSec = 100.0, updatedAt = 200, unsynced = true)
        assertEquals(0.5f, bookRow(book, server, offline).progress)
        val older = offline.copy(updatedAt = 50)
        assertEquals(0.25f, bookRow(book, server, older).progress)
        assertNull(bookRow(book, null, null).progress)
        // Finished on the server shows as finished…
        assertTrue(bookRow(book, server.copy(isFinished = true), older).finished)
        // …unless you've listened on this phone since (started it again).
        assertFalse(bookRow(book, server.copy(isFinished = true), offline).finished)
    }

    @Test
    fun buildsAManifestForMultiFileBooks() {
        val details = ItemDetails(
            id = "b",
            media = ItemDetailsMedia(
                metadata = BookMetadata(title = "Multi", authorName = "A"),
                duration = 0.0, // unknown: summed from the tracks
                tracks = listOf(
                    ItemTrack(index = 2, ino = "i2", startOffset = 90.0, duration = 90.0, metadata = TrackFileMetadata(".mp3", 2000)),
                    ItemTrack(index = 1, ino = "i1", startOffset = 0.0, duration = 90.0, metadata = TrackFileMetadata(".mp3", 1000)),
                    ItemTrack(index = 3, ino = "i3", startOffset = 180.0, duration = 90.5, metadata = TrackFileMetadata("../x", 3000)),
                ),
            ),
        )
        val book = manifestFrom(details, "fallback", null)
        assertEquals(listOf("i1", "i2", "i3"), book.tracks.map { it.ino })
        assertEquals(listOf("1.mp3", "2.mp3", "3.audio"), book.tracks.map { it.fileName }) // odd extensions are never used as paths
        assertEquals(270.5, book.durationSec)
        assertEquals("Multi", book.title)
        assertFailsWith<PermanentDownloadException> { manifestFrom(ItemDetails("e"), "Ebook", null) }
    }
}
