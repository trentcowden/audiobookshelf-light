package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.api.AuthStore
import com.trentcowden.audiobookshelf.playback.SettingsStore
import java.io.File

/**
 * One-time wiring of the process-wide stores. There's no Application class in a
 * Light tool, so the first screen to build its view model calls this with
 * `lightContext.filesDir`.
 */
object AppInit {
    fun init(filesDir: File) {
        AuthStore.init(filesDir)
        SettingsStore.init(filesDir)
        com.trentcowden.audiobookshelf.downloads.Downloads.init(filesDir)
        com.trentcowden.audiobookshelf.downloads.LocalProgress.init(filesDir)
        com.trentcowden.audiobookshelf.downloads.LibraryCache.init(filesDir)
    }
}
