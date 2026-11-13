package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.library.BookRow
import com.trentcowden.audiobookshelf.library.searchBooks
import androidx.compose.foundation.lazy.items

class SearchViewModel(initialQuery: String) : LightViewModel<Unit>() {
    val query = MutableStateFlow(initialQuery)
}

/**
 * Results for a search of the library already loaded on the Library screen —
 * instant, and it works offline. The magnifier in the top bar searches again.
 */
class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val books: List<BookRow>,
    private val initialQuery: String,
) : AbsScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel(initialQuery)

    @Composable
    override fun ScreenContent() {
        val query by viewModel.query.collectAsState()
        val results = remember(query) { searchBooks(books, query) }
        val downloads by Downloads.states.collectAsState()
        AbsScreenScaffold {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("“$query”"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.SEARCH,
                    onClick = { editQuery() },
                    contentDescription = "Search again",
                ),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            when {
                results.isEmpty() -> CenteredMessage("No books match “$query”.")
                else -> BookList(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.id }) { row ->
                        BookListRow(row, downloads[row.id]) {
                            navigateTo(screenFactory = { PlayerScreen(it, row.id, row.title, row.author) })
                        }
                    }
                }
            }
        }
    }

    private fun editQuery() {
        navigateTo(
            screenFactory = {
                TextEditorScreen(it, EditorRequest(title = "Search", initialValue = viewModel.query.value, submitLabel = "SEARCH"))
            },
            resultCallback = { q -> if (q.isNotBlank()) viewModel.query.value = q.trim() },
        )
    }
}
