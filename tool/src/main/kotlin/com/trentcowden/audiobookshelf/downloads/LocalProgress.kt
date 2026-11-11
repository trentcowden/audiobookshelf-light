package com.trentcowden.audiobookshelf.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where you are in a book, kept on the phone so offline listening is never lost. */
@Serializable
data class LocalPosition(
    val positionSec: Double,
    val durationSec: Double,
    /** Wall-clock ms of the last change. */
    val updatedAt: Long,
    /** True until the server has this position. */
    val unsynced: Boolean,
)

object LocalProgress {
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private var cache: MutableMap<String, LocalPosition>? = null

    fun init(filesDir: File) = synchronized(this) {
        if (file == null) file = File(filesDir, "local_progress.json")
    }

    private fun load(): MutableMap<String, LocalPosition> = cache ?: run {
        val loaded = file?.takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString<Map<String, LocalPosition>>(f.readText()) }.getOrNull()
        }.orEmpty().toMutableMap()
        cache = loaded
        loaded
    }

    fun get(itemId: String): LocalPosition? = synchronized(this) { load()[itemId] }

    fun unsynced(): Map<String, LocalPosition> = synchronized(this) { load().filterValues { it.unsynced } }

    /** Records listening on this phone; returns the timestamp stored (for [markSynced]). */
    fun save(itemId: String, positionSec: Double, durationSec: Double): Long = synchronized(this) {
        val map = load()
        val now = System.currentTimeMillis()
        map[itemId] = LocalPosition(positionSec, durationSec, now, unsynced = true)
        persist(map)
        now
    }

    /** Adopts the server's (newer) position, stamped with the server's own time. */
    fun adoptServer(itemId: String, positionSec: Double, durationSec: Double, serverUpdatedAt: Long) =
        synchronized(this) {
            val map = load()
            map[itemId] = LocalPosition(positionSec, durationSec, serverUpdatedAt, unsynced = false)
            persist(map)
        }

    /** Forget everything (sign-out: never push one account's listening into another). */
    fun clear() = synchronized(this) {
        cache = mutableMapOf()
        file?.delete()
    }

    /** The server now has [at]'s position — unless the book moved on since. */
    fun markSynced(itemId: String, at: Long) = synchronized(this) {
        val map = load()
        val p = map[itemId] ?: return@synchronized
        if (p.updatedAt <= at) {
            map[itemId] = p.copy(unsynced = false)
            persist(map)
        }
    }

    private fun persist(map: Map<String, LocalPosition>) {
        val target = file ?: return
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(map))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}
