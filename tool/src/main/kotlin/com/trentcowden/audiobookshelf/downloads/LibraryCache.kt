package com.trentcowden.audiobookshelf.downloads

import com.trentcowden.audiobookshelf.api.LibraryItem
import com.trentcowden.audiobookshelf.api.MediaProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedLibrary(
    val books: List<LibraryItem> = emptyList(),
    val inProgress: List<LibraryItem> = emptyList(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val savedAt: Long = 0,
)

/** Last library listing, so the tool opens (and downloaded books play) with no signal. */
object LibraryCache {
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    fun init(filesDir: File) = synchronized(this) {
        if (file == null) file = File(filesDir, "library_cache.json")
    }

    fun load(): CachedLibrary? = synchronized(this) {
        file?.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString<CachedLibrary>(f.readText()) }.getOrNull() }
    }

    fun save(library: CachedLibrary) = synchronized(this) {
        val target = file ?: return@synchronized
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(CachedLibrary.serializer(), library))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun clear() = synchronized(this) { file?.delete() }
}
