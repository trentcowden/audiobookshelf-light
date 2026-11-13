package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.trentcowden.audiobookshelf.BuildConfig
import com.trentcowden.audiobookshelf.downloads.DownloadState
import com.trentcowden.audiobookshelf.downloads.Downloads
import com.trentcowden.audiobookshelf.playback.Playback
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.LibraryCache
import com.trentcowden.audiobookshelf.storage.LibrarySort
import com.trentcowden.audiobookshelf.storage.SettingsStore

/** ChoiceScreen value for "every library" (results can't be null). */
private const val ALL_LIBRARIES = ""

class SettingsViewModel : LightViewModel<Unit>() {
    val signingOut = kotlinx.coroutines.flow.MutableStateFlow(false)
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    AbsScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass = SettingsViewModel::class.java
    override fun createViewModel() = SettingsViewModel()

    @Composable
    override fun ScreenContent() {
        val auth = AuthStore.current
        val signingOut by viewModel.signingOut.collectAsState()
        AbsScreenScaffold {
            BackTopBar(title = "Settings", onBack = { goBack() })
            if (signingOut) {
                CenteredMessage("Logging out…")
                return@AbsScreenScaffold
            }
            LightScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
            ) {
                if (auth != null) {
                    Info("Server", auth.serverUrl.removePrefix("https://"))
                    Info("Signed in as", auth.username + if (auth.usesApiKey) " (API key)" else "")
                }
                val settings by SettingsStore.settings.collectAsState()
                // Only worth a choice when the server has more than one book library.
                val libraries = LibraryCache.load()?.libraries.orEmpty()
                if (libraries.size > 1) {
                    val chosen = libraries.firstOrNull { it.id == settings.libraryId }
                    Action("Library", chosen?.name ?: "All libraries") {
                        val choices = listOf(Choice("All libraries", ALL_LIBRARIES)) +
                            libraries.map { Choice(it.name.ifBlank { "Untitled" }, it.id) }
                        navigateTo(screenFactory = {
                            ChoiceScreen(it, "Library", choices, selected = chosen?.id ?: ALL_LIBRARIES)
                        }) { id ->
                            SettingsStore.update { s -> s.copy(libraryId = id.takeIf { it != ALL_LIBRARIES }) }
                        }
                    }
                }
                Action("Sort books by", settings.sort.label) {
                    val choices = LibrarySort.entries.map { Choice(it.label, it) }
                    navigateTo(screenFactory = { ChoiceScreen(it, "Sort by", choices, selected = settings.sort) }) { sort ->
                        SettingsStore.update { s -> s.copy(sort = sort) }
                    }
                }
                val downloaded = Downloads.states.collectAsState().value.values.count { it is DownloadState.Done }
                Info(
                    "Downloads",
                    if (downloaded == 0) "None yet" else
                        "$downloaded book${if (downloaded == 1) "" else "s"} · ${formatMegabytes(Downloads.usedBytes())}",
                )
                Action("Reset Player", "If playback gets stuck, this stops it and starts the player fresh.") {
                    Playback.reset()
                    goBack()
                }
                if (auth != null) {
                    Action("Log Out", null) {
                        navigateTo(screenFactory = {
                            ConfirmScreen(it, "Log out of ${auth.serverUrl.removePrefix("https://")}?")
                        }) { confirmed ->
                            if (confirmed && !viewModel.signingOut.value) {
                                // Saves the final position before the sign-in is forgotten.
                                viewModel.signingOut.value = true
                                Playback.signOut(onDone = { goBack() })
                            }
                        }
                    }
                }
                LightText(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                )
            }
        }
    }

    @Composable
    private fun Info(label: String, value: String) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 0.75f.gridUnitsAsDp())) {
            LightText(text = label, variant = LightTextVariant.Detail)
            LightText(text = value, variant = LightTextVariant.Copy)
        }
    }

    @Composable
    private fun Action(label: String, detail: String?, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            LightText(text = label, variant = LightTextVariant.Heading)
            detail?.let { LightText(text = it, variant = LightTextVariant.Detail) }
        }
    }
}

class ConfirmViewModel : LightViewModel<Boolean>()

/** Yes/no question in the stock Light style: back = no, CONFIRM = yes. */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val question: String,
) : AbsScreen<Boolean, ConfirmViewModel>(sealedActivity) {

    override val viewModelClass = ConfirmViewModel::class.java
    override fun createViewModel() = ConfirmViewModel()

    @Composable
    override fun ScreenContent() {
        AbsScreenScaffold {
            BackTopBar(title = "", onBack = { goBack(false) })
            CenteredMessage(question)
            LightBottomBar(
                items = listOf(null, LightBarButton.Text(text = "CONFIRM", onClick = { goBack(true) }), null),
            )
        }
    }
}
