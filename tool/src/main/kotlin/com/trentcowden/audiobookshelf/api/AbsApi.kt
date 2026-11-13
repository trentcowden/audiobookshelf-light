package com.trentcowden.audiobookshelf.api

import com.trentcowden.audiobookshelf.BuildConfig
import com.trentcowden.audiobookshelf.storage.AuthState
import com.trentcowden.audiobookshelf.storage.AuthStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException

/** A failure worth showing to the user, already phrased for them. */
open class AbsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The stored sign-in no longer works (refresh rejected) — the user has to log in again. */
class SignedOutException : AbsException("Signed out. Please log in again.")

/** The server no longer knows this playback session (restarted, or closed it as stale). */
class SessionGoneException : AbsException("Playback session expired.")

/** The server has password sign-in turned off (single sign-on only): use an API key. */
class SingleSignOnOnlyException : AbsException(
    "This server only allows single sign-on. Sign in with an API key instead " +
        "(in Audiobookshelf: Settings → API Keys).",
)

/** A download problem that retrying won't fix (no permission, no space, no audio). */
class PermanentDownloadException(message: String) : AbsException(message)

/** Final position for closing a playback session. */
data class FinalSync(val currentTimeSec: Double, val timeListenedSec: Double, val durationSec: Double)

/** The server calls the progress logic makes ([AbsClient] in the app, a fake in tests). */
interface ProgressServer {
    /** The server's progress for one book, or null if never started. */
    suspend fun progressFor(itemId: String): MediaProgress?

    /** Writes progress directly — used when there's no live session. */
    suspend fun patchProgress(itemId: String, currentTimeSec: Double, durationSec: Double)

    /** Opens a playback session, used to sync progress and listening time. */
    suspend fun startPlayback(itemId: String): PlaybackSession

    /** Throws [SessionGoneException] when the server no longer knows the session. */
    suspend fun syncSession(sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double)

    /** Closes a session, optionally with a final position. */
    suspend fun closeSession(sessionId: String, final: FinalSync?)
}

/** The app's client, on OkHttp. */
object AbsApi : AbsClient(OkHttp.create())

/**
 * Minimal Audiobookshelf client. Every call carries `Authorization: Bearer`;
 * an expired access token is refreshed once (2.26+ servers) and the call
 * retried. Books are downloaded through the same authenticated file endpoint.
 * Supported: Audiobookshelf 2.x, signed in with username + password or an API
 * key, over https or plain http, at a domain root or under a sub-path.
 */
open class AbsClient(engine: HttpClientEngine) : ProgressServer {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    private val refreshLock = Mutex()

    // ---- Sign-in -------------------------------------------------------------

    /** Server status plus the base URL it actually answered on (after redirects). */
    data class Reached(val baseUrl: String, val status: ServerStatus)

    private suspend fun reach(serverUrl: String): Reached {
        val response = network { client.get("$serverUrl/status") }
        if (!response.status.isSuccess()) {
            throw AbsException("That doesn't look like an Audiobookshelf server (HTTP ${response.status.value}).")
        }
        val status = runCatching { response.body<ServerStatus>() }.getOrNull()
        if (status == null || status.app?.lowercase() != "audiobookshelf") {
            throw AbsException("That doesn't look like an Audiobookshelf server.")
        }
        status.serverVersion?.let { version ->
            if (compareVersions(version, MIN_SERVER_VERSION) < 0) {
                throw AbsException("This server runs Audiobookshelf $version. Update it to $MIN_SERVER_VERSION or newer.")
            }
        }
        // Store the URL the server really lives at (e.g. after an http→https
        // redirect, or under a sub-path): the audio player can't follow
        // cross-protocol redirects, and every later call builds on it.
        val finalUrl = response.call.request.url.toString().substringBefore("?")
        val base = finalUrl.removeSuffix("/status").trimEnd('/').ifEmpty { serverUrl }
        return Reached(base, status)
    }

    /** Typed without a scheme: try https first, then plain http (home-network servers). */
    suspend fun reachTypedServer(raw: String): Reached {
        val normalized = normalizeServerUrl(raw)
        if (normalized.isEmpty()) throw AbsException("Enter your server address.")
        return try {
            reach(normalized)
        } catch (e: AbsException) {
            if (hasScheme(raw)) throw e
            runCatching { reach("http://" + normalized.removePrefix("https://")) }.getOrNull() ?: throw e
        }
    }

    /** Checks the server, logs in with username + password and returns the sign-in to persist. */
    suspend fun login(rawServerUrl: String, username: String, password: String): AuthState {
        val (serverUrl, status) = reachTypedServer(rawServerUrl)
        if (status.authMethods.isNotEmpty() && "local" !in status.authMethods) throw SingleSignOnOnlyException()
        val response = network {
            client.post("$serverUrl/login") {
                header("x-return-tokens", "true")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("username", username)
                    put("password", password)
                })
            }
        }
        when {
            response.status == HttpStatusCode.Unauthorized ->
                throw AbsException("Wrong username or password.")
            response.status == HttpStatusCode.TooManyRequests ->
                throw AbsException("Too many login attempts. Try again later.")
            !response.status.isSuccess() ->
                throw AbsException("Login failed (HTTP ${response.status.value}).")
        }
        val user = runCatching { response.body<LoginResponse>().user }.getOrNull()
            ?: throw AbsException("Login failed: unexpected server response.")
        val access = user.accessToken ?: user.token
            ?: throw AbsException("Login failed: the server returned no token.")
        return AuthState(
            serverUrl = serverUrl,
            username = user.username.ifBlank { username },
            accessToken = access,
            refreshToken = user.refreshToken,
        )
    }

    /**
     * Checks the server and signs in with an API key (Audiobookshelf 2.26+:
     * Settings → API Keys; an older user's legacy token works too). For
     * single sign-on servers, where there's no password to type.
     */
    suspend fun loginWithApiKey(rawServerUrl: String, apiKey: String): AuthState {
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        if (key.isEmpty()) throw AbsException("Enter your API key.")
        val (serverUrl, _) = reachTypedServer(rawServerUrl)
        val response = network {
            client.get("$serverUrl/api/me") { header(HttpHeaders.Authorization, "Bearer $key") }
        }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw AbsException("That API key wasn't accepted. Check it hasn't expired or been turned off.")
            !response.status.isSuccess() ->
                throw AbsException("Sign-in failed (HTTP ${response.status.value}).")
        }
        val me = runCatching { response.body<MeResponse>() }.getOrNull()
            ?: throw AbsException("Sign-in failed: unexpected server response.")
        return AuthState(serverUrl = serverUrl, username = me.username, accessToken = key, usesApiKey = true)
    }

    /** Best-effort server-side logout (invalidates the refresh token). Never throws. */
    suspend fun logout(auth: AuthState) {
        val refresh = auth.refreshToken ?: return
        runCatching {
            client.post("${auth.serverUrl}/logout") { header("x-refresh-token", refresh) }
        }
    }

    // ---- Library ---------------------------------------------------------------

    suspend fun bookLibraries(): List<Library> {
        // 2.x answers {"libraries": [...]}; some older 2.x builds a bare array.
        val body = authed(HttpMethod.Get, "/api/libraries").body<JsonElement>()
        val list = when (body) {
            is JsonArray -> body
            is JsonObject -> body["libraries"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { runCatching { json.decodeFromJsonElement(Library.serializer(), it) }.getOrNull() }
            .filter { it.mediaType == "book" }
    }

    /** Every item in a library, sorted by title (paged under the hood). */
    suspend fun allItems(libraryId: String): List<LibraryItem> {
        val all = mutableListOf<LibraryItem>()
        var page = 0
        while (true) {
            val response = authed(
                HttpMethod.Get,
                "/api/libraries/$libraryId/items?minified=1&sort=media.metadata.title&desc=0" +
                    "&limit=$PAGE_SIZE&page=$page",
            ).body<LibraryItemsResponse>()
            all += response.results
            page++
            if (response.results.size < PAGE_SIZE || all.size >= response.total) break
        }
        return all
    }

    /** Started-but-unfinished books, most recently listened first. */
    suspend fun itemsInProgress(): List<LibraryItem> =
        authed(HttpMethod.Get, "/api/me/items-in-progress").body<ItemsInProgressResponse>()
            .libraryItems
            .filter { it.mediaType == "book" }
            .sortedByDescending { it.progressLastUpdate }

    /** The signed-in user's progress for every book they've touched, keyed by item id. */
    suspend fun mediaProgress(): Map<String, MediaProgress> =
        authed(HttpMethod.Get, "/api/me").body<MeResponse>()
            .mediaProgress
            .filter { it.episodeId == null }
            .associateBy { it.libraryItemId }

    /** Tracks, chapters and metadata for one book. */
    suspend fun itemDetails(itemId: String): ItemDetails =
        authed(HttpMethod.Get, "/api/items/$itemId?expanded=1").body<ItemDetails>()

    override suspend fun progressFor(itemId: String): MediaProgress? {
        val response = authed(HttpMethod.Get, "/api/me/progress/$itemId", allow404 = true)
        if (response.status == HttpStatusCode.NotFound) return null
        return runCatching { response.body<MediaProgress>() }.getOrNull()
    }

    /**
     * Downloads one audio file into [part], resuming from its current length
     * (the server honours Range). Returns the file's total size. [onProgress]
     * gets (bytes so far, total). Refreshes the sign-in once on 401.
     */
    suspend fun downloadFile(itemId: String, ino: String, part: File, onProgress: (Long, Long) -> Unit): Long {
        var refreshed = false
        repeat(3) {
            val auth = AuthStore.current ?: throw SignedOutException()
            val offset = if (part.isFile) part.length() else 0L
            val result = network {
                client.prepareGet("${auth.serverUrl}/api/items/$itemId/file/$ino") {
                    header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                    if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
                    // A ~1 GB book takes a while: no overall deadline, only a stall timeout.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = 60_000
                    }
                }.execute { response -> receiveFile(response, part, offset, onProgress) }
            }
            when {
                result >= 0 -> return result
                result == UNAUTHORIZED && !refreshed -> {
                    refreshTokens(auth)
                    refreshed = true
                }
                result == UNAUTHORIZED -> throw SignedOutException()
                else -> Unit // RESTART: retry from zero
            }
        }
        throw AbsException("Download failed.")
    }

    /** Writes a file response to [part]. Returns its total size, [UNAUTHORIZED] or [RESTART]. */
    private suspend fun receiveFile(response: HttpResponse, part: File, offset: Long, onProgress: (Long, Long) -> Unit): Long {
        when (response.status) {
            HttpStatusCode.Unauthorized -> return UNAUTHORIZED
            HttpStatusCode.Forbidden -> throw PermanentDownloadException(
                "Your account isn't allowed to download books. Ask the server's admin to turn on " +
                    "downloads for your user.",
            )
            HttpStatusCode.RequestedRangeNotSatisfiable -> {
                // "bytes */N": if we already hold all N bytes (killed just before
                // the rename), we're done. Otherwise the file changed on the
                // server: start over cleanly.
                val size = response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                if (size != null && size == offset) return size
                part.delete()
                return RESTART
            }
            HttpStatusCode.NotFound -> throw PermanentDownloadException("This book's audio file is gone from the server.")
            else -> if (!response.status.isSuccess()) {
                throw AbsException("Download failed (HTTP ${response.status.value}).")
            }
        }
        val resumed = response.status == HttpStatusCode.PartialContent
        val total = if (resumed) {
            response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                ?: (offset + (response.contentLength() ?: 0))
        } else {
            response.contentLength() ?: 0
        }
        var done = if (resumed) offset else 0L
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        java.io.FileOutputStream(part, resumed).use { out ->
            var lastReport = 0L
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) {
                    out.write(buffer, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        lastReport = now
                        onProgress(done, total)
                    }
                }
            }
        }
        onProgress(done, total)
        if (total > 0 && done < total) throw AbsException("Download interrupted. It will resume.")
        return total
    }

    // ---- Progress ----------------------------------------------------------------

    override suspend fun startPlayback(itemId: String): PlaybackSession {
        val response = authed(HttpMethod.Post, "/api/items/$itemId/play") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("mediaPlayer", "light-phone")
                put("forceDirectPlay", true)
                put("forceTranscode", false)
                putJsonObject("deviceInfo") {
                    put("clientName", "Light Phone")
                    put("clientVersion", BuildConfig.VERSION_NAME)
                    put("manufacturer", "Light")
                    put("model", "Light Phone III")
                }
            })
        }
        // Only used for syncing progress — audio plays from the download, so
        // how the server would stream it doesn't matter.
        return response.body<PlaybackSession>()
    }

    override suspend fun syncSession(sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) {
        val response = authed(HttpMethod.Post, "/api/session/$sessionId/sync", allow404 = true) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("currentTime", currentTimeSec)
                put("timeListened", timeListenedSec)
                put("duration", durationSec)
            })
        }
        if (response.status == HttpStatusCode.NotFound) throw SessionGoneException()
    }

    /** Never throws for 404 (already closed). */
    override suspend fun closeSession(sessionId: String, final: FinalSync?) {
        authed(HttpMethod.Post, "/api/session/$sessionId/close", allow404 = true) {
            if (final != null) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("currentTime", final.currentTimeSec)
                    put("timeListened", final.timeListenedSec)
                    put("duration", final.durationSec)
                })
            }
        }
    }

    override suspend fun patchProgress(itemId: String, currentTimeSec: Double, durationSec: Double) {
        authed(HttpMethod.Patch, "/api/me/progress/$itemId", allow404 = true) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("currentTime", currentTimeSec)
                put("duration", durationSec)
                put("progress", if (durationSec > 0) (currentTimeSec / durationSec).coerceIn(0.0, 1.0) else 0.0)
                put("isFinished", isAtEnd(currentTimeSec, durationSec))
            })
        }
    }

    /**
     * Marks a book finished on the server (at its end, so playing it again
     * starts over) and returns the server's progress after.
     */
    suspend fun setFinished(itemId: String, durationSec: Double): MediaProgress? {
        authed(HttpMethod.Patch, "/api/me/progress/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("isFinished", true)
                if (durationSec > 0) {
                    put("currentTime", durationSec)
                    put("duration", durationSec)
                    put("progress", 1.0)
                }
            })
        }
        return progressFor(itemId)
    }

    /** Forgets a book's progress on the server ("not started"). */
    suspend fun deleteProgress(itemId: String) {
        val progress = progressFor(itemId) ?: return
        // The server deletes progress by its own id, not the book's.
        val id = progress.id ?: return patchProgress(itemId, 0.0, progress.duration)
        authed(HttpMethod.Delete, "/api/me/progress/$id", allow404 = true)
    }

    // ---- Plumbing --------------------------------------------------------------

    /**
     * Sends an authenticated request. On 401 refreshes the access token once and
     * retries; if the refresh itself is rejected the stored sign-in is cleared
     * and [SignedOutException] is thrown. Other non-2xx statuses (except 404,
     * which callers may interpret) become an [AbsException].
     */
    private suspend fun authed(
        method: HttpMethod,
        path: String,
        allow404: Boolean = false,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val auth = AuthStore.current ?: throw SignedOutException()
        var response = send(auth, method, path, block)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = refreshTokens(auth)
            response = send(refreshed, method, path, block)
            if (response.status == HttpStatusCode.Unauthorized) {
                AuthStore.clearIf(refreshed)
                throw SignedOutException()
            }
        }
        if (response.status == HttpStatusCode.NotFound && !allow404) {
            throw AbsException("Not found on the server (it may have been removed).")
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
            val detail = runCatching { response.bodyAsText().take(120) }.getOrDefault("")
            throw AbsException("Server error (HTTP ${response.status.value}). $detail".trim())
        }
        return response
    }

    private suspend fun send(
        auth: AuthState,
        method: HttpMethod,
        path: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = network {
        client.request("${auth.serverUrl}$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
            block()
        }
    }

    /** Exchanges the refresh token for new tokens. Concurrent callers share one refresh. */
    internal suspend fun refreshTokens(stale: AuthState): AuthState = refreshLock.withLock {
        val current = AuthStore.current ?: throw SignedOutException()
        // Someone else already refreshed while we waited for the lock.
        if (current.accessToken != stale.accessToken) return@withLock current
        // API keys and pre-2.26 tokens can't be refreshed: rejected means signed out.
        val refreshToken = current.refreshToken ?: run {
            AuthStore.clearIf(current)
            throw SignedOutException()
        }
        val response = network {
            client.post("${current.serverUrl}/auth/refresh") {
                header("x-refresh-token", refreshToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            AuthStore.clearIf(current)
            throw SignedOutException()
        }
        if (!response.status.isSuccess()) {
            // 5xx etc: transient. Keep the sign-in; the caller's request fails for now.
            throw AbsException("Couldn't refresh sign-in (HTTP ${response.status.value}).")
        }
        val user = runCatching { response.body<LoginResponse>().user }.getOrNull()
            ?: throw AbsException("Couldn't refresh sign-in: unexpected server response.")
        val updated = current.copy(
            accessToken = user.accessToken ?: user.token ?: current.accessToken,
            refreshToken = user.refreshToken ?: current.refreshToken,
        )
        // Signed out (or into another account) while refreshing? Don't resurrect it.
        if (!AuthStore.replaceIf(current, updated)) throw SignedOutException()
        updated
    }

    /** Maps I/O failures to a message that makes sense on a phone. */
    private suspend fun <T> network(call: suspend () -> T): T = try {
        call()
    } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
        throw AbsException("The server took too long to respond.", e)
    } catch (e: io.ktor.client.network.sockets.SocketTimeoutException) {
        throw AbsException("The server took too long to respond.", e)
    } catch (e: javax.net.ssl.SSLException) {
        throw AbsException(sslMessage(e), e)
    } catch (e: IOException) {
        throw AbsException("Can't reach the server. Check the address and your connection.", e)
    } catch (e: java.nio.channels.UnresolvedAddressException) {
        throw AbsException("Can't find that server. Check the address.", e)
    }

    companion object {
        /** Oldest server this tool is known to work with. */
        const val MIN_SERVER_VERSION = "2.0.0"
        private const val PAGE_SIZE = 200
        private const val UNAUTHORIZED = -1L
        private const val RESTART = -2L

        /** "abs.example.com" → "https://abs.example.com"; trims spaces and trailing slashes. */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if (hasScheme(trimmed)) trimmed else "https://$trimmed"
        }

        private fun hasScheme(input: String): Boolean =
            input.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

        /** Whether a position counts as the end of the book (what the server calls finished). */
        fun isAtEnd(positionSec: Double, durationSec: Double): Boolean =
            durationSec > 0 && durationSec - positionSec <= FINISHED_WITHIN_SEC

        private const val FINISHED_WITHIN_SEC = 30.0

        /** Compares dotted versions numerically ("2.10.0" > "2.9.1"); ignores suffixes like "-beta". */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        /** Certificate problems get their own explanation: they're common with self-hosting. */
        internal fun sslMessage(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is java.security.cert.CertificateException || cause is java.security.cert.CertPathValidatorException) {
                    return "The server's certificate isn't trusted. The phone only accepts certificates from a " +
                        "public authority (such as Let's Encrypt), not self-signed ones. On your home network, " +
                        "you can type http:// before the address instead."
                }
                cause = cause.cause
            }
            return "Secure connection failed. If your server uses plain http, type http:// first."
        }
    }
}
