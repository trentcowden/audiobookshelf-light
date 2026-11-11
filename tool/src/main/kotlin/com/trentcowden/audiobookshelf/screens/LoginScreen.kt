package com.trentcowden.audiobookshelf.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.trentcowden.audiobookshelf.AppInit
import com.trentcowden.audiobookshelf.api.AbsApi
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(previousServer: String?, previousUser: String?) : LightViewModel<Boolean>() {
    private val _ui = MutableStateFlow(
        LoginUiState(server = previousServer.orEmpty(), username = previousUser.orEmpty()),
    )
    val ui = _ui.asStateFlow()

    fun setServer(value: String) = _ui.update { it.copy(server = value.trim(), error = null) }
    fun setUsername(value: String) = _ui.update { it.copy(username = value.trim(), error = null) }
    fun setPassword(value: String) = _ui.update { it.copy(password = value, error = null) }

    /** Logs in; calls [onSuccess] on the main thread once the sign-in is saved. */
    fun logIn(onSuccess: () -> Unit) {
        val state = _ui.value
        if (state.busy) return
        if (state.server.isBlank() || state.username.isBlank()) {
            _ui.update { it.copy(error = "Enter your server address and username.") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AbsApi.login(state.server, state.username, state.password) }
            }
            result.fold(
                onSuccess = { auth ->
                    withContext(Dispatchers.IO) { AuthStore.save(auth) }
                    // Don't keep the password around once it has done its job.
                    _ui.update { it.copy(busy = false, password = "") }
                    onSuccess()
                },
                onFailure = { e ->
                    val message = (e as? AbsException)?.message ?: "Login failed: ${e.message}"
                    _ui.update { it.copy(busy = false, error = message) }
                },
            )
        }
    }
}

/** Server / username / password, then LOG IN. Returns true once signed in. */
class LoginScreen(sealedActivity: SealedLightActivity) :
    AbsScreen<Boolean, LoginViewModel>(sealedActivity) {

    override val viewModelClass = LoginViewModel::class.java

    override fun createViewModel(): LoginViewModel {
        AppInit.init(lightContext.filesDir)
        val previous = AuthStore.lastServer
        return LoginViewModel(previous?.first, previous?.second)
    }

    @Composable
    override fun ScreenContent() {
        val ui by viewModel.ui.collectAsState()
        AbsScreenScaffold {
            BackTopBar(title = "Audiobookshelf", onBack = { goBack(false) })
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                // Fields are locked while connecting, so a login that finishes
                // never lands while an editor is on top.
                Field("Server:", ui.server, "audiobooks.example.com") {
                    if (!ui.busy) edit("Server", ui.server, viewModel::setServer)
                }
                Field("Username:", ui.username, "username") {
                    if (!ui.busy) edit("Username", ui.username, viewModel::setUsername)
                }
                Field("Password:", "•".repeat(ui.password.length), "password") {
                    // The Light keyboard editor can't mask input, so start empty
                    // rather than reveal a previously typed password.
                    if (!ui.busy) edit("Password", "", viewModel::setPassword)
                }
                val status = when {
                    ui.busy -> "Connecting…"
                    else -> ui.error
                }
                if (status != null) {
                    LightText(
                        text = status,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(text = "LOG IN", onClick = {
                        viewModel.logIn(onSuccess = { finishIfOnTop() })
                    }),
                    null,
                ),
            )
        }
    }

    private var onTop = false
    private var pendingSuccess = false

    override fun willShow() {
        super.willShow()
        onTop = true
        if (pendingSuccess) {
            pendingSuccess = false
            goBack(true)
        }
    }

    override fun willHide() {
        super.willHide()
        onTop = false
    }

    /** Close with success — now if we're the top screen, otherwise when we're shown again. */
    private fun finishIfOnTop() {
        if (onTop) goBack(true) else pendingSuccess = true
    }

    @Composable
    private fun Field(label: String, value: String, placeholder: String, onClick: () -> Unit) {
        Box(modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp())) {
            LightTextField(
                label = label,
                value = value,
                placeholder = placeholder,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        navigateTo(
            screenFactory = { TextEditorScreen(it, EditorRequest(title = title, initialValue = initial)) },
            resultCallback = onResult,
        )
    }
}
