package com.trentcowden.audiobookshelf.playback

import java.io.File

/** The one playback preference: speed, shared by every book (like fenleon's Audiobooks). */
object SettingsStore {
    private var file: File? = null

    @Volatile
    private var cachedSpeed: Float? = null

    fun init(filesDir: File) = synchronized(this) {
        if (file == null) file = File(filesDir, "speed.txt")
    }

    var speed: Float
        get() = cachedSpeed ?: synchronized(this) {
            (file?.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toFloat() }.getOrNull() } ?: 1f)
                .also { cachedSpeed = it }
        }
        set(value) = synchronized(this) {
            cachedSpeed = value
            runCatching { file?.writeText(value.toString()) }
        }

    /** Seek bar scope on the player: whole book (default) or current chapter. */
    var chapterScopedBar: Boolean
        get() = chapterFile()?.isFile == true
        set(value) {
            val f = chapterFile() ?: return
            runCatching { if (value) f.writeText("1") else f.delete() }
        }

    private fun chapterFile(): File? = file?.let { File(it.parentFile, "bar_chapter_scope") }

    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
}
