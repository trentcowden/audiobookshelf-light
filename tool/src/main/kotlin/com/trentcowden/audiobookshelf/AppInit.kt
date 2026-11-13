package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.LocalProgress
import com.trentcowden.audiobookshelf.storage.SettingsStore
import java.io.File

/**
 * One-time wiring of the process-wide stores. There's no Application class in a
 * Light tool, so every screen calls this (cheap after the first time) with
 * `lightContext.filesDir`.
 */
object AppInit {
    fun init(filesDir: File) {
        AuthStore.init(filesDir)
        SettingsStore.init(filesDir)
        LocalProgress.init(filesDir)
        LibraryCache.init(filesDir)
        Downloads.init(filesDir)
    }
}
