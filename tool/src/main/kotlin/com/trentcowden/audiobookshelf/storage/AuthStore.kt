package com.trentcowden.audiobookshelf.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Who we are signed in as. A password is never stored — only what the server hands back. */
@Serializable
data class AuthState(
    val serverUrl: String,
    val username: String,
    val accessToken: String,
    /** Null for API keys and servers older than 2.26, whose token doesn't expire. */
    val refreshToken: String? = null,
    val usesApiKey: Boolean = false,
)

/** Server and username of the last sign-in, kept after logging out so logging back in is quick. */
@Serializable
data class LastSignIn(val serverUrl: String, val username: String, val usedApiKey: Boolean = false)

@Serializable
private data class StoredAuth(val serverUrl: String, val username: String, val usesApiKey: Boolean = false, val sealed: String)

@Serializable
private data class Tokens(val accessToken: String, val refreshToken: String? = null)

/**
 * The stored sign-in. Server and username are plain; the tokens are sealed
 * with [TokenCipher] (Android Keystore on the phone). All access is
 * synchronized: the API client refreshes tokens from background threads.
 */
object AuthStore {
    private const val VERSION = 1
    private var cipher: TokenCipher? = null
    private var authFile: VersionedJsonFile<StoredAuth>? = null
    private var lastFile: VersionedJsonFile<LastSignIn>? = null
    private var legacyLastFile: File? = null

    @Volatile
    private var cached: AuthState? = null
    private var loaded = false

    fun init(filesDir: File, tokenCipher: TokenCipher = KeystoreTokenCipher()) = synchronized(this) {
        if (authFile != null) return@synchronized
        cipher = tokenCipher
        authFile = VersionedJsonFile(File(filesDir, "auth.json"), StoredAuth.serializer(), VERSION) { from, data ->
            // v0 kept the tokens in plain text: seal them on the way in.
            if (from == 0) sealLegacy(data, tokenCipher) else null
        }
        lastFile = VersionedJsonFile(File(filesDir, "last_sign_in.json"), LastSignIn.serializer(), 1)
        legacyLastFile = File(filesDir, "last_server.txt")
    }

    val current: AuthState?
        get() = synchronized(this) {
            if (!loaded) {
                val stored = authFile?.read()
                cached = stored?.let { s ->
                    cipher?.open(s.sealed)?.let { opened ->
                        runCatching { storageJson.decodeFromString(Tokens.serializer(), opened) }.getOrNull()
                    }?.let { t -> AuthState(s.serverUrl, s.username, t.accessToken, t.refreshToken, s.usesApiKey) }
                }
                loaded = true
            }
            cached
        }

    fun save(state: AuthState) = synchronized(this) {
        cached = state
        loaded = true
        val c = cipher ?: return@synchronized
        val sealed = c.seal(storageJson.encodeToString(Tokens.serializer(), Tokens(state.accessToken, state.refreshToken)))
        authFile?.write(StoredAuth(state.serverUrl, state.username, state.usesApiKey, sealed))
        lastFile?.write(LastSignIn(state.serverUrl, state.username, state.usesApiKey))
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
        current?.let { lastFile?.write(LastSignIn(it.serverUrl, it.username, it.usesApiKey)) }
        cached = null
        loaded = true
        authFile?.delete()
    }

    /** The last sign-in (survives logging out), or null. */
    val lastSignIn: LastSignIn?
        get() = synchronized(this) {
            current?.let { LastSignIn(it.serverUrl, it.username, it.usesApiKey) }
                ?: lastFile?.read()
                ?: legacyLastFile?.takeIf { it.isFile }?.let { f ->
                    runCatching { f.readLines().let { LastSignIn(it[0], it[1]) } }.getOrNull()
                }
        }

    private fun sealLegacy(data: JsonElement, c: TokenCipher): JsonElement? {
        val legacy = runCatching { storageJson.decodeFromJsonElement(AuthState.serializer(), data) }.getOrNull()
            ?: return null
        val sealed = c.seal(storageJson.encodeToString(Tokens.serializer(), Tokens(legacy.accessToken, legacy.refreshToken)))
        return buildJsonObject {
            put("serverUrl", legacy.serverUrl)
            put("username", legacy.username)
            put("usesApiKey", false)
            put("sealed", sealed)
        }.jsonObject
    }

    /** Test hook: forget the in-memory state so the next read comes from disk. */
    internal fun resetForTest() = synchronized(this) {
        authFile = null
        lastFile = null
        cipher = null
        cached = null
        loaded = false
    }
}
