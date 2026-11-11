package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.AuthStore
import com.trentcowden.audiobookshelf.api.LibraryItem
import com.trentcowden.audiobookshelf.api.MediaProgress
import com.trentcowden.audiobookshelf.api.SignedOutException
import com.trentcowden.audiobookshelf.playback.Playback
import com.trentcowden.audiobookshelf.downloads.CachedLibrary
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.downloads.DownloadState
import com.trentcowden.audiobookshelf.downloads.LibraryCache
import com.trentcowden.audiobookshelf.downloads.LocalProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the library list. */
data class BookRow(
    val id: String,
    val title: String,
    val author: String?,
    /** 0..1, or null when never started. */
    val progress: Float?,
    val finished: Boolean,
    val series: String? = null,
    val narrator: String? = null,
)

data class LibraryUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val continueListening: List<BookRow> = emptyList(),
    val allBooks: List<BookRow> = emptyList(),
    val loadedOnce: Boolean = false,
)

class LibraryViewModel : LightViewModel<Unit>() {
    /** The full catalog is reloaded at most this often; progress refreshes on every show. */
    private var lastFullLoadAt = 0L
    private var catalog: List<LibraryItem> = emptyList()

    private val _ui = MutableStateFlow(LibraryUiState(signedIn = AuthStore.current != null))
    val ui = _ui.asStateFlow()

    /** Ask for a login automatically only once; after that the user taps LOG IN. */
    var promptedLogin = false

    init {
        // Show the last listing straight away (and with no signal at all).
        LibraryCache.load()?.let { cached ->
            catalog = cached.books
            _ui.update {
                it.copy(
                    loadedOnce = cached.books.isNotEmpty(),
                    continueListening = cached.inProgress.map { item -> item.toRow(cached.progress) },
                    allBooks = cached.books.map { item -> item.toRow(cached.progress) },
                )
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        Downloads.resumeUnfinished()
        refresh()
    }

    fun refresh(force: Boolean = false) {
        val signedIn = AuthStore.current != null
        _ui.update { it.copy(signedIn = signedIn) }
        if (!signedIn) {
            // Signed out: forget the previous account's books.
            catalog = emptyList()
            lastFullLoadAt = 0
            _ui.update { LibraryUiState(signedIn = false) }
            return
        }
        if (_ui.value.loading) return
        val fullLoad = force || catalog.isEmpty() ||
            System.currentTimeMillis() - lastFullLoadAt > CATALOG_MAX_AGE_MS
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    coroutineScope {
                        val inProgress = async { AbsApi.itemsInProgress() }
                        val progress = async { AbsApi.mediaProgress() }
                        val books = if (fullLoad) {
                            AbsApi.bookLibraries().flatMap { AbsApi.allBooks(it.id) }
                        } else {
                            catalog
                        }
                        Triple(inProgress.await(), progress.await(), books)
                    }
                }
            }
            result.fold(
                onSuccess = { (inProgress, progress, books) ->
                    if (fullLoad) {
                        catalog = books
                        lastFullLoadAt = System.currentTimeMillis()
                    }
                    withContext(Dispatchers.IO) {
                        LibraryCache.save(CachedLibrary(books, inProgress, progress, System.currentTimeMillis()))
                    }
                    // Online again: send positions from offline listening.
                    Playback.flushOfflineProgress()
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = null,
                            loadedOnce = true,
                            continueListening = inProgress.map { item -> item.toRow(progress) },
                            allBooks = books.map { item -> item.toRow(progress) },
                        )
                    }
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

    private companion object {
        const val CATALOG_MAX_AGE_MS = 10 * 60_000L
    }

    private fun LibraryItem.toRow(progress: Map<String, MediaProgress>): BookRow {
        val p = progress[id]
        // Listening on this phone may be ahead of the server (offline).
        val local = LocalProgress.get(id)?.takeIf { l -> p == null || l.updatedAt > p.lastUpdate }
        val localFraction = local?.takeIf { it.durationSec > 0 }?.let { (it.positionSec / it.durationSec).toFloat() }
        return BookRow(
            id = id,
            title = media.metadata.title ?: "Untitled",
            author = media.metadata.authorName?.takeIf { it.isNotBlank() },
            progress = (localFraction ?: p?.progress?.toFloat())?.takeIf { it > 0f },
            finished = p?.isFinished == true,
            series = media.metadata.seriesName?.takeIf { it.isNotBlank() },
            narrator = media.metadata.narratorName?.takeIf { it.isNotBlank() },
        )
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
                ui.allBooks.isEmpty() -> CenteredMessage("No books in your library yet.")
                else -> LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (ui.error != null) {
                        LightText(
                            text = ui.error!!,
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(start = LIST_GUTTER.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                        )
                    }
                    if (ui.continueListening.isNotEmpty()) {
                        SectionLabel("Continue")
                        ui.continueListening.forEach { Book(it, downloads) }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SectionLabel("All Books")
                    }
                    ui.allBooks.forEach { Book(it, downloads) }
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
                        // Balancing spacer: keeps the middle button centered.
                        null
                    },
                ),
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(start = LIST_GUTTER.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun Book(row: BookRow, downloads: Map<String, DownloadState>) {
        BookListRow(row, downloads[row.id]) { openPlayer(row.id, row.title, row.author) }
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
