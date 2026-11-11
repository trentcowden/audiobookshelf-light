package com.trentcowden.audiobookshelf.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Who we are signed in as. The password is never stored — only the tokens the server hands back. */
@Serializable
data class AuthState(
    val serverUrl: String,
    val username: String,
    val accessToken: String,
    /** Null on servers older than 2.26, whose single token never expires. */
    val refreshToken: String? = null,
)

/**
 * Persists [AuthState] as JSON in the tool's private files dir (not backed up,
 * not visible to other tools). Writes go to a temp file that is renamed over
 * the target so a crash mid-write never corrupts the stored sign-in.
 */
object AuthStore {
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    @Volatile
    private var cached: AuthState? = null
    private var loaded = false

    fun init(filesDir: File) = synchronized(this) {
        if (file == null) file = File(filesDir, "auth.json")
    }

    val current: AuthState?
        get() = synchronized(this) {
            if (!loaded) {
                cached = file?.takeIf { it.isFile }?.let { f ->
                    runCatching { json.decodeFromString<AuthState>(f.readText()) }.getOrNull()
                }
                loaded = true
            }
            cached
        }

    fun save(state: AuthState) = synchronized(this) {
        cached = state
        loaded = true
        val target = file ?: return@synchronized
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(AuthState.serializer(), state))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** Replaces [expected] with [new] only if [expected] is still the stored sign-in. */
    fun replaceIf(expected: AuthState, new: AuthState): Boolean = synchronized(this) {
        if (current != expected) return@synchronized false
        save(new)
        true
    }

    /** Clears the sign-in only if it is still [expected] (not a newer login). */
    fun clearIf(expected: AuthState) = synchronized(this) {
        if (current == expected) clear()
    }

    /** Signs out but keeps the server and username, so logging back in is quick. */
    fun clear() = synchronized(this) {
        current?.let { remember(it) }
        cached = null
        loaded = true
        file?.delete()
    }

    /** Server URL and username of the last sign-in (survives sign-out), or null. */
    val lastServer: Pair<String, String>?
        get() = current?.let { it.serverUrl to it.username }
            ?: lastFile()?.takeIf { it.isFile }?.let { f ->
                runCatching { f.readLines().let { it[0] to it[1] } }.getOrNull()
            }

    private fun remember(state: AuthState) {
        runCatching { lastFile()?.writeText("${state.serverUrl}\n${state.username}") }
    }

    private fun lastFile(): File? = file?.let { File(it.parentFile, "last_server.txt") }
}
