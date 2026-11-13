package com.trentcowden.audiobookshelf.storage

import com.trentcowden.audiobookshelf.api.Library
import com.trentcowden.audiobookshelf.api.LibraryItem
import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.library.CatalogBook
import com.trentcowden.audiobookshelf.library.isListenable
import com.trentcowden.audiobookshelf.library.toCatalogBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

@Serializable
data class CachedLibrary(
    /** The server's book libraries. */
    val libraries: List<Library> = emptyList(),
    /** Every book in every book library. */
    val books: List<CatalogBook> = emptyList(),
    /** Started-but-unfinished books, most recently listened first. */
    val inProgressIds: List<String> = emptyList(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val savedAt: Long = 0,
)

/** v0 cached the server's full item objects. */
@Serializable
private data class CachedLibraryV0(
    val books: List<LibraryItem> = emptyList(),
    val inProgress: List<LibraryItem> = emptyList(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val savedAt: Long = 0,
)

/** Last library listing, so the tool opens (and downloaded books play) with no signal. */
object LibraryCache {
    private var file: VersionedJsonFile<CachedLibrary>? = null

    @Volatile
    private var cached: CachedLibrary? = null

    fun init(filesDir: File) = synchronized(this) {
        if (file != null) return@synchronized
        file = VersionedJsonFile(File(filesDir, "library_cache.json"), CachedLibrary.serializer(), 1, ::migrate)
    }

    fun load(): CachedLibrary? = cached ?: synchronized(this) { cached ?: file?.read().also { cached = it } }

    fun save(library: CachedLibrary) = synchronized(this) {
        cached = library
        file?.write(library)
    }

    /** Records a progress change made from this phone (mark finished / not started). */
    fun updateProgress(itemId: String, progress: MediaProgress?) = synchronized(this) {
        val current = load() ?: return@synchronized
        save(current.copy(progress = if (progress == null) current.progress - itemId else current.progress + (itemId to progress)))
    }

    fun clear() = synchronized(this) {
        cached = null
        file?.delete()
    }

    internal fun migrate(from: Int, data: JsonElement): JsonElement? {
        if (from != 0) return null
        val old = storageJson.decodeFromJsonElement(CachedLibraryV0.serializer(), data)
        val migrated = CachedLibrary(
            books = old.books.filter { it.isListenable() }.map { it.toCatalogBook() },
            inProgressIds = old.inProgress.map { it.id },
            progress = old.progress,
            savedAt = old.savedAt,
        )
        return storageJson.encodeToJsonElement(CachedLibrary.serializer(), migrated)
    }
}
