package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.api.AbsClient
import com.trentcowden.audiobookshelf.api.Chapter
import com.trentcowden.audiobookshelf.screens.chapterIndexAt
import com.trentcowden.audiobookshelf.screens.chapterStartMs
import com.trentcowden.audiobookshelf.screens.formatSpeed
import com.trentcowden.audiobookshelf.library.BookRow
import com.trentcowden.audiobookshelf.library.primarySeries
import com.trentcowden.audiobookshelf.library.searchBooks
import com.trentcowden.audiobookshelf.screens.formatTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LogicTest {
    @Test
    fun normalizesServerUrls() {
        assertEquals("https://abs.example.com", AbsClient.normalizeServerUrl(" abs.example.com/ "))
        assertEquals("http://192.168.1.5:13378", AbsClient.normalizeServerUrl("http://192.168.1.5:13378//"))
        assertEquals("HTTPS://X.com", AbsClient.normalizeServerUrl("HTTPS://X.com"))
        assertEquals("", AbsClient.normalizeServerUrl("   "))
    }

    @Test
    fun findsChapterAtPosition() {
        val chapters = listOf(Chapter(0, 0.0, 60.0, "One"), Chapter(1, 60.0, 120.0, "Two"))
        assertEquals(0, chapterIndexAt(chapters, 0))
        assertEquals(0, chapterIndexAt(chapters, 59_998))
        assertEquals(1, chapterIndexAt(chapters, 60_000))
        assertEquals(1, chapterIndexAt(chapters, 999_999)) // past the end clamps
        assertEquals(0, chapterIndexAt(emptyList(), 5_000))
    }

    @Test
    fun jumpingToAChapterLandsInsideIt() {
        // Real ABS chapter boundaries are fractional seconds; truncating the
        // start to ms used to land a hair inside the previous chapter.
        val chapters = listOf(Chapter(0, 0.0, 754.2366, "One"), Chapter(1, 754.2366, 1500.0, "Two"))
        assertEquals(1, chapterIndexAt(chapters, chapterStartMs(chapters[1])))
        assertEquals(0, chapterIndexAt(chapters, chapterStartMs(chapters[0])))
    }

    @Test
    fun formats() {
        assertEquals("0:05", formatTime(5_000))
        assertEquals("1:02:03", formatTime(3_723_000))
        assertEquals("0:00", formatTime(-10))
        assertEquals("1x", formatSpeed(1f))
        assertEquals("1.25x", formatSpeed(1.25f))
    }

    @Test
    fun searchMatchesAllWordsAcrossFieldsTitleFirst() {
        val books = listOf(
            BookRow("1", "The Way of Kings", "Brandon Sanderson", null, false, series = "Stormlight Archive #1"),
            BookRow("2", "Mistborn", "Brandon Sanderson", null, false),
            BookRow("3", "Kings of the Wyld", "Nicholas Eames", null, false),
            BookRow("4", "Les Misérables", "Victor Hugo", null, false),
        )
        assertEquals(listOf("1", "2"), searchBooks(books, "sanderson").map { it.id })
        assertEquals(listOf("1"), searchBooks(books, "stormlight kings").map { it.id })
        assertEquals(listOf("1", "3"), searchBooks(books, "kings").map { it.id })
        // A title match outranks an author-only match.
        val ranked = listOf(BookRow("a", "Dune", "Hugo Kingsley", null, false), BookRow("b", "Kings Rising", "C. S. Pacat", null, false))
        assertEquals(listOf("b", "a"), searchBooks(ranked, "kings").map { it.id })
        assertEquals(listOf("4"), searchBooks(books, "miserables").map { it.id })
        assertEquals(emptyList(), searchBooks(books, "   ").map { it.id })
    }

    @Test
    fun showsTheFirstSeries() {
        assertEquals("Dune #1", primarySeries("Dune #1, The Dune Sequence #12"))
        assertEquals("The Ender Saga #3", primarySeries("The Ender Saga #3, The Enderverse #13"))
        assertEquals("Dungeon Crawler Carl #6", primarySeries("Dungeon Crawler Carl #6"))
        assertEquals("Earth, Wind & Fire #2", primarySeries("Earth, Wind & Fire #2"))
        assertEquals("Discworld", primarySeries("Discworld"))
        assertEquals(null, primarySeries(""))
        assertEquals(null, primarySeries(null))
    }
}
