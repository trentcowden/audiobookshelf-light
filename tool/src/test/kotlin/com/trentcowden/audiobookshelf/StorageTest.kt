package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.storage.AuthState
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.LibrarySort
import com.trentcowden.audiobookshelf.storage.Settings
import com.trentcowden.audiobookshelf.storage.SettingsStore
import com.trentcowden.audiobookshelf.storage.VersionedJsonFile
import com.trentcowden.audiobookshelf.storage.storageJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    @Serializable
    data class Thing(val name: String, val count: Int = 0)

    @Test
    fun roundTripsWithAVersionEnvelope() {
        val file = File(tempDir(), "thing.json")
        val store = VersionedJsonFile(file, Thing.serializer(), 3)
        assertNull(store.read())
        assertTrue(store.write(Thing("a", 2)))
        assertEquals(Thing("a", 2), store.read())
        val onDisk = storageJson.parseToJsonElement(file.readText()).jsonObject
        assertEquals("3", onDisk["version"]!!.jsonPrimitive.content)
        assertFalse(File(file.parentFile, "thing.json.tmp").exists())
    }

    @Test
    fun migratesFilesFromBeforeVersioningAndRewritesThem() {
        val file = File(tempDir(), "thing.json").apply { writeText("""{"label":"old","n":5}""") }
        val store = VersionedJsonFile(file, Thing.serializer(), 1) { from, data ->
            assertEquals(0, from)
            val o = data.jsonObject
            buildJsonObject {
                put("name", o["label"]!!.jsonPrimitive.content)
                put("count", o["n"]!!.jsonPrimitive.content.toInt())
            }
        }
        assertEquals(Thing("old", 5), store.read())
        assertTrue(file.readText().contains("\"version\":1"))
        assertEquals(Thing("old", 5), VersionedJsonFile(file, Thing.serializer(), 1).read())
    }

    @Test
    fun ignoresNewerVersionsAndDamagedFiles() {
        val file = File(tempDir(), "thing.json")
        VersionedJsonFile(file, Thing.serializer(), 5).write(Thing("future"))
        assertNull(VersionedJsonFile(file, Thing.serializer(), 4).read())
        file.writeText("{not json")
        assertNull(VersionedJsonFile(file, Thing.serializer(), 4).read())
    }

    @Test
    fun sealsTokensAndKeepsTheServerOnLogout() {
        val auth = AuthState("https://abs.example.com", "trent", "access-123", "refresh-456")
        val dir = freshAuthStore(auth)
        val onDisk = File(dir, "auth.json").readText()
        assertFalse(onDisk.contains("access-123"), "tokens must not be stored in plain text")
        assertFalse(onDisk.contains("refresh-456"))
        AuthStore.resetForTest()
        AuthStore.init(dir, FakeCipher)
        assertEquals(auth, AuthStore.current)
        AuthStore.clear()
        assertNull(AuthStore.current)
        assertEquals("https://abs.example.com", AuthStore.lastSignIn?.serverUrl)
        assertEquals("trent", AuthStore.lastSignIn?.username)
    }

    @Test
    fun sealsAPlainTextSignInFromAnOlderVersion() {
        val dir = tempDir()
        File(dir, "auth.json").writeText(
            """{"serverUrl":"https://abs.example.com","username":"trent","accessToken":"a1","refreshToken":"r1"}""",
        )
        AuthStore.resetForTest()
        AuthStore.init(dir, FakeCipher)
        assertEquals(AuthState("https://abs.example.com", "trent", "a1", "r1"), AuthStore.current)
        assertFalse(File(dir, "auth.json").readText().contains("\"a1\""))
    }

    @Test
    fun aSignInThatCannotBeOpenedMeansSignedOut() {
        val dir = freshAuthStore(AuthState("https://x", "u", "a"))
        AuthStore.resetForTest()
        AuthStore.init(dir, object : com.trentcowden.audiobookshelf.storage.TokenCipher {
            override fun seal(plain: String) = plain
            override fun open(sealed: String): String? = null // key lost
        })
        assertNull(AuthStore.current)
    }

    @Test
    fun replaceIfOnlyReplacesTheExpectedSignIn() {
        val a = AuthState("https://x", "u", "a")
        freshAuthStore(a)
        val newer = a.copy(accessToken = "b")
        assertTrue(AuthStore.replaceIf(a, newer))
        assertFalse(AuthStore.replaceIf(a, a.copy(accessToken = "c")))
        assertEquals(newer, AuthStore.current)
    }

    @Test
    fun migratesTheOldLibraryCache() {
        // v0 cached whole server items; v1 keeps only what the list shows.
        val v0 = """
            {"books":[
              {"id":"b1","libraryId":"l1","media":{"metadata":{"title":"The Hobbit","titleIgnorePrefix":"Hobbit","authorName":"J. R. R. Tolkien"},"duration":3600.0,"numTracks":1}},
              {"id":"e1","media":{"metadata":{"title":"An Ebook"},"numTracks":0}}
             ],
             "inProgress":[{"id":"b1"}],
             "progress":{"b1":{"libraryItemId":"b1","currentTime":60.0,"lastUpdate":5}},
             "savedAt":42}
        """.trimIndent()
        val migrated = storageJson.decodeFromJsonElement(
            com.trentcowden.audiobookshelf.storage.CachedLibrary.serializer(),
            LibraryCache.migrate(0, storageJson.parseToJsonElement(v0))!!,
        )
        assertEquals(listOf("b1"), migrated.books.map { it.id })
        assertEquals("Hobbit", migrated.books[0].sortTitle)
        assertEquals(3600.0, migrated.books[0].durationSec)
        assertEquals(listOf("b1"), migrated.inProgressIds)
        assertEquals(60.0, migrated.progress["b1"]?.currentTime)
        assertEquals(42, migrated.savedAt)
    }

    @Test
    fun picksUpSettingsFromBeforeSettingsJson() {
        val dir = tempDir()
        File(dir, "speed.txt").writeText("1.5")
        File(dir, "bar_chapter_scope").writeText("1")
        val read = SettingsStoreProbe.load(dir)
        assertEquals(Settings(speed = 1.5f, chapterScopedBar = true, sort = LibrarySort.TITLE), read)
        assertFalse(File(dir, "speed.txt").exists())
        assertTrue(File(dir, "settings.json").exists())
    }
}

private object SettingsStoreProbe {
    fun load(dir: File): Settings {
        SettingsStore.resetForTest()
        SettingsStore.init(dir)
        return SettingsStore.settings.value
    }
}
