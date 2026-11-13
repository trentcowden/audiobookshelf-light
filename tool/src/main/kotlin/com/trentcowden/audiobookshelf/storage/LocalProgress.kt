package com.trentcowden.audiobookshelf.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

/** The phone's copy of listening progress, as the progress logic sees it (faked in tests). */
interface LocalPositions {
    fun get(itemId: String): LocalPosition?

    /** Records listening on this phone; returns the timestamp stored (for [markSynced]). */
    fun save(itemId: String, positionSec: Double, durationSec: Double): Long

    /** Adopts a position the server has (newer, or just written), stamped [updatedAt]. */
    fun adoptServer(itemId: String, positionSec: Double, durationSec: Double, updatedAt: Long)

    /** The server now has the position saved at [at] — unless the book moved on since. */
    fun markSynced(itemId: String, at: Long)
}

object LocalProgress : LocalPositions {
    private var file: VersionedJsonFile<Map<String, LocalPosition>>? = null
    private var cache: MutableMap<String, LocalPosition>? = null

    fun init(filesDir: File) = synchronized(this) {
        if (file != null) return@synchronized
        file = VersionedJsonFile(
            File(filesDir, "local_progress.json"),
            MapSerializer(String.serializer(), LocalPosition.serializer()),
            version = 1,
        ) { from, data -> if (from == 0) data else null } // v0: same map, no envelope
    }

    private fun load(): MutableMap<String, LocalPosition> =
        cache ?: (file?.read().orEmpty().toMutableMap()).also { cache = it }

    override fun get(itemId: String): LocalPosition? = synchronized(this) { load()[itemId] }

    fun unsynced(): Map<String, LocalPosition> = synchronized(this) { load().filterValues { it.unsynced } }

    override fun save(itemId: String, positionSec: Double, durationSec: Double): Long = synchronized(this) {
        val now = System.currentTimeMillis()
        put(itemId, LocalPosition(positionSec, durationSec, now, unsynced = true))
        now
    }

    override fun adoptServer(itemId: String, positionSec: Double, durationSec: Double, updatedAt: Long) =
        synchronized(this) { put(itemId, LocalPosition(positionSec, durationSec, updatedAt, unsynced = false)) }

    override fun markSynced(itemId: String, at: Long) = synchronized(this) {
        val p = load()[itemId] ?: return@synchronized
        if (p.updatedAt <= at && p.unsynced) put(itemId, p.copy(unsynced = false))
    }

    /** Forget everything (sign-out: never push one account's listening into another). */
    fun clear() = synchronized(this) {
        cache = mutableMapOf()
        file?.delete()
    }

    private fun put(itemId: String, position: LocalPosition) {
        val map = load()
        map[itemId] = position
        file?.write(map)
    }
}
