package com.trentcowden.audiobookshelf.api

import kotlinx.serialization.Serializable

// Only the fields this tool reads. Everything is optional/defaulted: the client
// is lenient (ignoreUnknownKeys) so server upgrades don't break parsing.

@Serializable
data class ServerStatus(
    val app: String? = null,
    val serverVersion: String? = null,
    val isInit: Boolean = false,
    val authMethods: List<String> = emptyList(),
)

@Serializable
internal data class LoginResponse(val user: LoginUser)

@Serializable
internal data class LoginUser(
    val username: String = "",
    /** Pre-2.26 servers: a non-expiring token. Still sent by newer servers for compatibility. */
    val token: String? = null,
    /** 2.26+ servers (when the request sets `x-return-tokens: true`). */
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

@Serializable
internal data class LibrariesResponse(val libraries: List<Library> = emptyList())

@Serializable
data class Library(val id: String, val name: String = "", val mediaType: String = "book")

@Serializable
internal data class LibraryItemsResponse(
    val results: List<LibraryItem> = emptyList(),
    val total: Int = 0,
)

@Serializable
internal data class ItemsInProgressResponse(val libraryItems: List<LibraryItem> = emptyList())

@Serializable
data class LibraryItem(
    val id: String,
    val mediaType: String = "book",
    val media: BookMedia = BookMedia(),
    /** Present on /api/me/items-in-progress results. */
    val progressLastUpdate: Long = 0,
)

@Serializable
data class BookMedia(
    val metadata: BookMetadata = BookMetadata(),
    val duration: Double = 0.0,
    val numTracks: Int = 0,
    val numAudioFiles: Int = 0,
)

@Serializable
data class BookMetadata(
    val title: String? = null,
    val authorName: String? = null,
    val seriesName: String? = null,
    val narratorName: String? = null,
)

@Serializable
internal data class MeResponse(val mediaProgress: List<MediaProgress> = emptyList())

@Serializable
data class MediaProgress(
    val libraryItemId: String,
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
data class PlaybackSession(
    val id: String,
    val libraryItemId: String,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    /** Seconds. */
    val duration: Double = 0.0,
    /** Seconds — where the server says to resume. */
    val currentTime: Double = 0.0,
    /** 0 = direct play, 1 = direct stream, 2 = transcode (HLS), 3 = local. */
    val playMethod: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
)

@Serializable
data class Chapter(
    val id: Int = 0,
    /** Seconds from the start of the book. */
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

@Serializable
data class AudioTrack(
    /** 1-based. */
    val index: Int = 1,
    /** Seconds from the start of the book. */
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String? = null,
    val mimeType: String? = null,
)

/** GET /api/items/{id}?expanded=1 — what a download needs. */
@Serializable
data class ItemDetails(
    val id: String,
    val media: ItemDetailsMedia = ItemDetailsMedia(),
)

@Serializable
data class ItemDetailsMedia(
    val metadata: BookMetadata = BookMetadata(),
    val duration: Double = 0.0,
    val chapters: List<Chapter> = emptyList(),
    val tracks: List<ItemTrack> = emptyList(),
)

@Serializable
data class ItemTrack(
    val index: Int = 1,
    val ino: String = "",
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val mimeType: String? = null,
    val metadata: TrackFileMetadata = TrackFileMetadata(),
)

@Serializable
data class TrackFileMetadata(
    val ext: String? = null,
    val size: Long = 0,
)
