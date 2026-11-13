package com.trentcowden.audiobookshelf.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The tool's JSON settings: lenient on read so older/newer fields never break parsing. */
internal val storageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * One JSON file in the tool's private storage, with a schema version.
 *
 * On disk: `{"version": n, "data": …}`. Files written before versioning
 * existed (no envelope) count as version 0. When [version] goes up, [migrate]
 * turns older data into the current shape (and the file is rewritten in it),
 * so an update never strands what people already have on their phone. Data from a *newer* version (after a
 * downgrade) is ignored rather than misread.
 *
 * Writes go to a temp file that is atomically moved over the target, so a
 * crash or power cut mid-write never leaves a corrupt file.
 */
class VersionedJsonFile<T>(
    val file: File,
    private val serializer: KSerializer<T>,
    private val version: Int,
    /** (version found on disk, its data) → data in the current shape, or null to discard. */
    private val migrate: (fromVersion: Int, data: JsonElement) -> JsonElement? = { _, _ -> null },
) {
    fun read(): T? {
        if (!file.isFile) return null
        return try {
            val (found, data) = unwrap(storageJson.parseToJsonElement(file.readText()))
            val current = when {
                found == version -> data
                found < version -> migrate(found, data) ?: return null
                else -> return null
            }
            storageJson.decodeFromJsonElement(serializer, current).also { value ->
                // Store migrated data in the current shape straight away.
                if (found < version) write(value)
            }
        } catch (e: Exception) {
            StorageLog.warn("Couldn't read ${file.name}", e)
            null
        }
    }

    fun write(value: T): Boolean {
        val envelope = buildJsonObject {
            put(VERSION_KEY, JsonPrimitive(version))
            put(DATA_KEY, storageJson.encodeToJsonElement(serializer, value))
        }
        return writeAtomically(file, envelope.toString())
    }

    fun delete() {
        file.delete()
    }

    private fun unwrap(root: JsonElement): Pair<Int, JsonElement> {
        if (root is JsonObject && root.keys == setOf(VERSION_KEY, DATA_KEY)) {
            val v = (root[VERSION_KEY] as? JsonPrimitive)?.intOrNull
            val data = root[DATA_KEY]
            if (v != null && data != null) return v to data
        }
        return 0 to root
    }

    private companion object {
        const val VERSION_KEY = "version"
        const val DATA_KEY = "data"
    }
}

/** Writes [text] to [target] via a temp file and an atomic rename. Returns false (and logs) on failure. */
fun writeAtomically(target: File, text: String): Boolean = try {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    true
} catch (e: Exception) {
    StorageLog.warn("Couldn't write ${target.name}", e)
    false
}

/** Logging that also works in plain JVM unit tests (where android.util.Log is a stub). */
internal object StorageLog {
    fun warn(message: String, e: Throwable? = null) {
        runCatching { android.util.Log.w("AbsStorage", message, e) }
    }
}
