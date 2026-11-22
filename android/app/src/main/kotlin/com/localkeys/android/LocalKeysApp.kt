package com.localkeys.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.localkeys.android.ui.CreateScreen
import com.localkeys.android.ui.HostCallbacks
import com.localkeys.android.ui.UnlockScreen
import com.localkeys.android.ui.VaultHost
import com.localkeys.android.ui.VaultViewModel

/**
 * Raiz da navegação:
 *  - Cofre trancado (ou nenhum escolhido): [UnlockScreen] (abrir/criar/desbloquear) e [CreateScreen].
 *  - Cofre aberto: [VaultHost] (lista, detalhe, editor, lixeira, pastas e configurações).
 *
 * Os gatilhos de SAF, BiometricPrompt e Credential Exchange vivem na [MainActivity] e chegam por [callbacks].
 */
@Composable
fun LocalKeysApp(
    viewModel: VaultViewModel,
    onPickDocument: () -> Unit,
    onCreateDocument: (String) -> Unit,
    callbacks: HostCallbacks,
) {
    val state by viewModel.state.collectAsState()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val vault = state.vault

    when {
        state.screen == VaultViewModel.Screen.Vault && vault != null -> {
            LaunchedEffect(Unit) { showCreate = false }
            VaultHost(viewModel = viewModel, state = state, vault = vault, callbacks = callbacks)
        }
        showCreate -> {
            BackHandler { showCreate = false; viewModel.clearError() }
            CreateScreen(
                busy = state.busy,
                error = state.error,
                onBack = {
                    showCreate = false
                    viewModel.clearError()
                },
                onCreate = { password ->
                    viewModel.clearError()
                    onCreateDocument(password)
                },
            )
        }
        else -> UnlockScreen(
            state = state,
            onPickVault = onPickDocument,
            onCreateVault = { showCreate = true },
            onUnlock = viewModel::unlock,
            onBiometricUnlock = { callbacks.onRequestBiometric(com.localkeys.android.ui.BiometricRequest.Unlock) },
            onErrorShown = viewModel::clearError,
        )
    }
}
