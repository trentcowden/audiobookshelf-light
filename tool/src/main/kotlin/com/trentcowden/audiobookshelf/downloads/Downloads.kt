package com.trentcowden.audiobookshelf.downloads

import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import com.trentcowden.audiobookshelf.api.SignedOutException
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** One audio file of a downloaded book, positioned on the book's timeline. */
@Serializable
data class DownloadedTrack(
    val index: Int,
    val ino: String,
    /** Seconds from the start of the book. */
    val startOffset: Double,
    val duration: Double,
    val fileName: String,
    val bytes: Long = 0,
)

/** A book's manifest, written before its audio so a half-done download can resume. */
@Serializable
data class DownloadedBook(
    val itemId: String,
    val title: String,
    val author: String? = null,
    val durationSec: Double = 0.0,
    val chapters: List<Chapter> = emptyList(),
    val tracks: List<DownloadedTrack> = emptyList(),
    val complete: Boolean = false,
)

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState
    /** [retryable] failures (network, interrupted, tool closed) resume automatically. */
    data class Failed(val message: String, val retryable: Boolean = true) : DownloadState
    data class Done(val book: DownloadedBook) : DownloadState
}

/**
 * Downloads books into the tool's private storage, one at a time. Lives for the
 * whole process, so leaving a screen doesn't stop a download.
 *
 * The SDK has no background-transfer support yet (light-sdk #187): a download
 * only makes progress while the tool is open or a book is playing. Every file
 * is written as `.part` and resumed with an HTTP Range request, so nothing is
 * lost when the phone freezes or kills the tool — [resumeUnfinished] picks it
 * back up next time.
 */
/** A download problem that retrying won't fix (no space, no audio). */
class PermanentDownloadException(message: String) : AbsException(message)

object Downloads {
    const val PAUSED = "Paused"
    private const val MAX_ATTEMPTS = 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var root: File? = null

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    private var currentId: String? = null

    fun init(filesDir: File) = synchronized(this) {
        if (root != null) return
        val dir = File(filesDir, "books").also { it.mkdirs() }
        root = dir
        val loaded = dir.listFiles().orEmpty().mapNotNull { bookDir ->
            readManifest(bookDir)?.let { book ->
                book.itemId to (if (book.complete) DownloadState.Done(book) else DownloadState.Failed(PAUSED))
            }
        }.toMap()
        _states.value = loaded
    }

    /** A finished download, or null. */
    fun book(itemId: String): DownloadedBook? = (_states.value[itemId] as? DownloadState.Done)?.book

    fun audioFile(book: DownloadedBook, track: DownloadedTrack): File = File(bookDir(book.itemId), track.fileName)

    /** Bytes used by downloads on disk. */
    fun usedBytes(): Long = root?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    /** Starts (or resumes) downloading a book. */
    fun download(itemId: String, title: String, author: String?) {
        _states.update { it + (itemId to DownloadState.Queued) }
        synchronized(this) {
            if (itemId != currentId && itemId !in queue) queue.addLast(itemId)
            // Remember title/author in case the manifest isn't written yet.
            pendingTitles[itemId] = title to author
        }
        ensureWorker()
    }

    /** Picks up downloads left unfinished (tool closed, or the connection dropped). */
    fun resumeUnfinished() {
        val unfinished = _states.value.filterValues { it is DownloadState.Failed && it.retryable }.keys
        unfinished.forEach { id ->
            val book = readManifest(bookDir(id))
            download(id, book?.title ?: "", book?.author)
        }
    }

    /** Stops a download if running and deletes everything for the book. */
    fun remove(itemId: String) {
        val running = synchronized(this) {
            queue.remove(itemId)
            pendingTitles.remove(itemId)
            if (currentId == itemId) worker else null
        }
        scope.launch {
            // Wait for the worker to actually stop before deleting under it.
            running?.cancelAndJoin()
            synchronized(this@Downloads) {
                if (running != null && worker === running) worker = null
                if (currentId == itemId) currentId = null
            }
            bookDir(itemId).deleteRecursively()
            _states.update { it - itemId }
            ensureWorker()
        }
    }

    private val pendingTitles = mutableMapOf<String, Pair<String, String?>>()

    private fun ensureWorker() = synchronized(this) {
        if (worker != null || queue.isEmpty()) return@synchronized
        worker = scope.launch {
            while (true) {
                val next = synchronized(this@Downloads) {
                    queue.removeFirstOrNull().also {
                        currentId = it
                        // Cleared in the same critical section that finds the queue
                        // empty, so a download() arriving now starts a new worker.
                        if (it == null) worker = null
                    }
                } ?: break
                val (title, author) = synchronized(this@Downloads) { pendingTitles[next] } ?: ("" to null)
                runOne(next, title, author)
                synchronized(this@Downloads) { if (currentId == next) currentId = null }
            }
        }
    }

    /** Downloads one book, retrying interrupted transfers with backoff. */
    private suspend fun runOne(itemId: String, title: String, author: String?) {
        var attempt = 0
        while (true) {
            attempt++
            val bytesBefore = bytesOnDisk(itemId)
            try {
                val book = fetch(itemId, title, author)
                coroutineContext.ensureActive()
                _states.update { it + (itemId to DownloadState.Done(book)) }
                return
            } catch (e: CancellationException) {
                throw e // removed: remove() cleans up
            } catch (e: Throwable) {
                coroutineContext.ensureActive()
                val permanent = e is PermanentDownloadException || e is SignedOutException
                val message = (e as? AbsException)?.message ?: "Download failed: ${e.message}"
                // A drop after real progress (e.g. the Cloudflare tunnel resetting
                // a long transfer) isn't a failing connection: carry straight on.
                val madeProgress = bytesOnDisk(itemId) > bytesBefore
                if (madeProgress && !permanent) attempt = 0
                if (permanent || attempt >= MAX_ATTEMPTS) {
                    _states.update { it + (itemId to DownloadState.Failed(message, retryable = !permanent)) }
                    return
                }
                // Wait, then pick up where the .part left off.
                _states.update { it + (itemId to DownloadState.Failed("Connection lost — retrying…")) }
                delay(if (madeProgress) 2_000L else attempt * 10_000L)
                _states.update { it + (itemId to DownloadState.Queued) }
            }
        }
    }

    private suspend fun fetch(itemId: String, title: String, author: String?): DownloadedBook {
        val dir = bookDir(itemId).also { it.mkdirs() }
        // Reuse a manifest from an interrupted attempt so file names stay put.
        val book = readManifest(dir)?.takeIf { it.tracks.isNotEmpty() } ?: run {
            val details = AbsApi.itemDetails(itemId)
            val tracks = details.media.tracks.sortedBy { it.index }.map { t ->
                DownloadedTrack(
                    index = t.index,
                    ino = t.ino,
                    startOffset = t.startOffset,
                    duration = t.duration,
                    fileName = "${t.index}${t.metadata.ext?.takeIf { it.startsWith('.') } ?: ".audio"}",
                    bytes = t.metadata.size,
                )
            }
            if (tracks.isEmpty()) throw PermanentDownloadException("This book has no audio.")
            DownloadedBook(
                itemId = itemId,
                title = details.media.metadata.title ?: title,
                author = details.media.metadata.authorName ?: author,
                durationSec = details.media.duration,
                chapters = details.media.chapters,
                tracks = tracks,
            ).also { writeManifest(dir, it) }
        }
        val needed = book.tracks.sumOf { t -> (t.bytes - partOrFile(dir, t).length()).coerceAtLeast(0) }
        if (dir.usableSpace < needed + 200L * 1024 * 1024) {
            throw PermanentDownloadException("Not enough space on the phone for this book.")
        }
        val totalBytes = book.tracks.sumOf { it.bytes }.coerceAtLeast(1)
        var finishedBytes = 0L
        val job = coroutineContext[Job]
        for (track in book.tracks) {
            val file = File(dir, track.fileName)
            if (file.isFile) {
                finishedBytes += file.length()
                continue
            }
            val part = File(dir, track.fileName + ".part")
            AbsApi.downloadFile(itemId, track.ino, part) { done, _ ->
                // Never report progress for a download that was just removed.
                if (job?.isActive != false) {
                    _states.update { it + (itemId to DownloadState.Running(finishedBytes + done, totalBytes)) }
                }
            }
            coroutineContext.ensureActive()
            if (!part.renameTo(file)) throw AbsException("Couldn't save the download.")
            finishedBytes += file.length()
        }
        return book.copy(complete = true).also { writeManifest(dir, it) }
    }

    private fun bytesOnDisk(itemId: String): Long =
        bookDir(itemId).listFiles().orEmpty().filter { it.name != "book.json" }.sumOf { it.length() }

    private fun partOrFile(dir: File, t: DownloadedTrack): File =
        File(dir, t.fileName).takeIf { it.isFile } ?: File(dir, t.fileName + ".part")

    private fun bookDir(itemId: String): File =
        File(root ?: error("Downloads not initialized"), itemId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun readManifest(dir: File): DownloadedBook? =
        File(dir, "book.json").takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString<DownloadedBook>(f.readText()) }.getOrNull()
        }

    private fun writeManifest(dir: File, book: DownloadedBook) {
        val target = File(dir, "book.json")
        val tmp = File(dir, "book.json.tmp")
        tmp.writeText(json.encodeToString(DownloadedBook.serializer(), book))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
