package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.SignedOutException
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.library.BookRow
import com.trentcowden.audiobookshelf.library.bookRow
import com.trentcowden.audiobookshelf.library.isListenable
import com.trentcowden.audiobookshelf.library.sortBooks
import com.trentcowden.audiobookshelf.library.toCatalogBook
import com.trentcowden.audiobookshelf.playback.Playback
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.CachedLibrary
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.LocalProgress
import com.trentcowden.audiobookshelf.storage.Settings
import com.trentcowden.audiobookshelf.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val continueListening: List<BookRow> = emptyList(),
    val allBooks: List<BookRow> = emptyList(),
    /** Heading for the full list: "All Books", or the chosen library's name. */
    val allBooksLabel: String = "All Books",
    val loadedOnce: Boolean = false,
)

class LibraryViewModel : LightViewModel<Unit>() {
    /** The full catalog is reloaded at most this often; progress refreshes on every show. */
    private var lastFullLoadAt = 0L
    private var library = LibraryCache.load() ?: CachedLibrary()
    private var rowsJob: Job? = null

    private val _ui = MutableStateFlow(LibraryUiState(signedIn = AuthStore.current != null))
    val ui = _ui.asStateFlow()

    /** Ask for a login automatically only once; after that the user taps LOG IN. */
    var promptedLogin = false

    init {
        // Show the last listing straight away (and with no signal at all).
        if (library.books.isNotEmpty()) publishRows(loadedOnce = true)
        // A different library or sort order re-sorts what's already loaded.
        viewModelScope.launch { SettingsStore.settings.drop(1).collect { publishRows() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        Downloads.resumeUnfinished()
        refresh()
    }

    fun refresh(force: Boolean = false) {
        val signedIn = AuthStore.current != null
        if (!signedIn) {
            // Signed out: forget the previous account's books.
            library = CachedLibrary()
            lastFullLoadAt = 0
            _ui.value = LibraryUiState(signedIn = false)
            return
        }
        _ui.update { it.copy(signedIn = true) }
        if (_ui.value.loading) return
        val fullLoad = force || library.books.isEmpty() ||
            System.currentTimeMillis() - lastFullLoadAt > CATALOG_MAX_AGE_MS
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch(fullLoad) } }
            result.fold(
                onSuccess = { fetched ->
                    if (fullLoad) lastFullLoadAt = System.currentTimeMillis()
                    library = fetched
                    withContext(Dispatchers.IO) { LibraryCache.save(fetched) }
                    // Online again: send positions from offline listening.
                    Playback.flushOfflineProgress()
                    _ui.update { it.copy(loading = false, error = null) }
                    publishRows(loadedOnce = true)
                },
                onFailure = { e ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            signedIn = e !is SignedOutException,
                            error = if (it.loadedOnce) {
                                "Offline — showing your library from last time."
                            } else {
                                (e as? AbsException)?.message ?: "Couldn't load your library."
                            },
                        )
                    }
                },
            )
        }
    }

    /** Progress always; the whole catalog only when [fullLoad]. */
    private suspend fun fetch(fullLoad: Boolean): CachedLibrary = coroutineScope {
        val inProgress = async { AbsApi.itemsInProgress() }
        val progress = async { AbsApi.mediaProgress() }
        val (libraries, books) = if (fullLoad) {
            val libraries = AbsApi.bookLibraries()
            val books = libraries.map { lib -> async { AbsApi.allItems(lib.id) } }.awaitAll()
                .flatten()
                .filter { it.isListenable() }
                .map { it.toCatalogBook() }
            libraries to books
        } else {
            library.libraries to library.books
        }
        CachedLibrary(
            libraries = libraries,
            books = books,
            inProgressIds = inProgress.await().map { it.id },
            progress = progress.await(),
            savedAt = System.currentTimeMillis(),
        )
    }

    /** Builds the rows for the chosen library and sort order, off the main thread. */
    private fun publishRows(loadedOnce: Boolean = _ui.value.loadedOnce) {
        val snapshot = library
        val settings = SettingsStore.settings.value
        rowsJob?.cancel()
        rowsJob = viewModelScope.launch {
            val (continueRows, allRows, label) = withContext(Dispatchers.Default) { buildRows(snapshot, settings) }
            _ui.update {
                it.copy(continueListening = continueRows, allBooks = allRows, allBooksLabel = label, loadedOnce = loadedOnce)
            }
        }
    }

    private fun buildRows(lib: CachedLibrary, settings: Settings): Triple<List<BookRow>, List<BookRow>, String> {
        // A library that's gone from the server falls back to all of them.
        val chosen = lib.libraries.firstOrNull { it.id == settings.libraryId }
        val books = if (chosen == null) lib.books else lib.books.filter { it.libraryId == chosen.id }
        val byId = books.associateBy { it.id }
        fun row(book: com.trentcowden.audiobookshelf.library.CatalogBook) =
            bookRow(book, lib.progress[book.id], LocalProgress.get(book.id))
        val continueRows = lib.inProgressIds.mapNotNull { byId[it] }.map(::row)
        val allRows = sortBooks(books, settings.sort).map(::row)
        val label = if (chosen != null && lib.libraries.size > 1) chosen.name else "All Books"
        return Triple(continueRows, allRows, label)
    }

    private companion object {
        const val CATALOG_MAX_AGE_MS = 10 * 60_000L
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    AbsScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel {
        AppInit.init(lightContext.filesDir)
        return LibraryViewModel()
    }

    override fun willShow() {
        super.willShow()
        if (AuthStore.current == null && !viewModel.promptedLogin) {
            viewModel.promptedLogin = true
            openLogin()
        }
    }

    @Composable
    override fun ScreenContent() {
        val ui by viewModel.ui.collectAsState()
        val nowPlaying by Playback.state.collectAsState()
        val downloads by Downloads.states.collectAsState()

        AbsScreenScaffold {
            Spacer(Modifier.height(2f.gridUnitsAsDp()))
            when {
                !ui.signedIn -> CenteredMessage("Sign in to your Audiobookshelf server to see your books.")
                !ui.loadedOnce && ui.loading -> CenteredMessage("Loading your library…")
                !ui.loadedOnce && ui.error != null -> CenteredMessage(ui.error!!)
                ui.allBooks.isEmpty() && !ui.loadedOnce -> CenteredMessage("Loading your library…")
                ui.allBooks.isEmpty() -> CenteredMessage("No audiobooks in this library yet.")
                else -> BookList(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    ui.error?.let { error ->
                        item(key = "error") {
                            LightText(
                                text = error,
                                variant = LightTextVariant.Detail,
                                modifier = Modifier.padding(start = LIST_GUTTER.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                    if (ui.continueListening.isNotEmpty()) {
                        item(key = "continue") { SectionLabel("Continue") }
                        items(ui.continueListening, key = { "c-${it.id}" }) { Book(it, downloads[it.id]) }
                        item(key = "all") { SectionLabel(ui.allBooksLabel, top = 1f) }
                    }
                    items(ui.allBooks, key = { "a-${it.id}" }) { Book(it, downloads[it.id]) }
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { navigateTo(screenFactory = { SettingsScreen(it) }) },
                        contentDescription = "Settings",
                    ),
                    nowPlaying.let { np ->
                        when {
                            !ui.signedIn -> LightBarButton.Text(text = "LOG IN", onClick = { openLogin() })
                            np != null -> LightBarButton.Text(text = "NOW PLAYING", onClick = {
                                openPlayer(np.itemId, np.title, np.author)
                            })
                            else -> null
                        }
                    },
                    if (ui.signedIn && ui.allBooks.isNotEmpty()) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = { openSearch(ui.allBooks) },
                            contentDescription = "Search",
                        )
                    } else {
                        null // keeps the middle button centered
                    },
                ),
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String, top: Float = 0f) {
        LightText(
            text = text,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(
                start = LIST_GUTTER.gridUnitsAsDp(),
                top = top.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
        )
    }

    @Composable
    private fun Book(row: BookRow, download: com.trentcowden.audiobookshelf.downloads.DownloadState?) {
        BookListRow(row, download) { openPlayer(row.id, row.title, row.author) }
    }

    private fun openPlayer(id: String, title: String, author: String?) {
        navigateTo(screenFactory = { PlayerScreen(it, id, title, author) })
    }

    /** Keyboard first; the results screen is pushed once there's a query. */
    private fun openSearch(books: List<BookRow>) {
        navigateTo(
            screenFactory = { TextEditorScreen(it, EditorRequest(title = "Search", initialValue = "", submitLabel = "SEARCH")) },
            resultCallback = { query ->
                if (query.isNotBlank()) navigateTo(screenFactory = { SearchScreen(it, books, query.trim()) })
            },
        )
    }

    private fun openLogin() {
        navigateTo(screenFactory = { LoginScreen(it) }) { signedIn ->
            if (signedIn) viewModel.refresh(force = true)
        }
    }
}
