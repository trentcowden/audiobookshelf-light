package com.trentcowden.audiobookshelf.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class LibrarySort(val label: String) {
    TITLE("Title"),
    AUTHOR("Author"),
    RECENTLY_ADDED("Recently added"),
}

@Serializable
data class Settings(
    /** Playback speed, shared by every book. */
    val speed: Float = 1f,
    /** Player seek bar covers the current chapter instead of the whole book. */
    val chapterScopedBar: Boolean = false,
    /** The library shown; null = every book library on the server. */
    val libraryId: String? = null,
    val sort: LibrarySort = LibrarySort.TITLE,
)

object SettingsStore {
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private var file: VersionedJsonFile<Settings>? = null
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(filesDir: File) = synchronized(this) {
        if (file != null) return@synchronized
        val f = VersionedJsonFile(File(filesDir, "settings.json"), Settings.serializer(), 1)
        file = f
        _settings.value = f.read() ?: legacy(filesDir)?.also { f.write(it) } ?: Settings()
    }

    fun update(transform: (Settings) -> Settings) = synchronized(this) {
        _settings.update(transform)
        file?.write(_settings.value)
    }

    /** Test hook: forget the loaded settings so [init] reads a directory again. */
    internal fun resetForTest() = synchronized(this) {
        file = null
        _settings.value = Settings()
    }

    /** Before settings.json: speed in speed.txt, chapter scope as a marker file. */
    private fun legacy(filesDir: File): Settings? {
        val speedFile = File(filesDir, "speed.txt")
        val scopeFile = File(filesDir, "bar_chapter_scope")
        if (!speedFile.isFile && !scopeFile.isFile) return null
        val speed = runCatching { speedFile.readText().trim().toFloat() }.getOrNull() ?: 1f
        return Settings(speed = speed, chapterScopedBar = scopeFile.isFile).also {
            speedFile.delete()
            scopeFile.delete()
        }
    }
}
