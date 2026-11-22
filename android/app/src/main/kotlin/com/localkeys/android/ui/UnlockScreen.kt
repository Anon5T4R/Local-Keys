package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R

/**
 * Primeira tela. Sem cofre: logo, nome e os dois caminhos (abrir ou criar). Com cofre escolhido: o nome do
 * arquivo, a senha-mestra e, se ativada, a biometria. Também é usada pela tela do provedor de credenciais.
 */
@Composable
fun UnlockScreen(
    state: VaultViewModel.UiState,
    onPickVault: () -> Unit,
    onCreateVault: () -> Unit,
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    // Com a biometria ligada, o pedido de digital/rosto já sobe ao abrir a tela (uma vez por abertura; cancelar
    // deixa a senha-mestra à mão e o botão de biometria continua lá).
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.vaultUri, state.biometricAvailable) {
        if (!autoPrompted && state.vaultUri != null && state.biometricAvailable && !state.busy) {
            autoPrompted = true
            onBiometricUnlock()
        }
    }

    StartLayout(modifier) {
        BrandHeader()
        Spacer(Modifier.height(40.dp))

        if (state.vaultUri == null) {
            Button(
                onClick = onPickVault,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.busy,
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.unlock_open))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCreateVault,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.busy,
            ) {
                Icon(Icons.Outlined.NoteAdd, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.unlock_create))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.unlock_no_vault),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            VaultFileChip(state.vaultName ?: stringResource(R.string.unlock_vault_unknown))
            Spacer(Modifier.height(20.dp))
            PasswordField(
                value = password,
                onValueChange = {
                    password = it
                    if (state.error != null) onErrorShown()
                },
                label = stringResource(R.string.unlock_password),
                enabled = !state.busy,
                isError = state.error != null,
                supportingText = state.error?.let { msg -> { Text(msg) } },
                onImeAction = { if (password.isNotBlank() && !state.busy) onUnlock(password) },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onUnlock(password) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.busy && password.isNotBlank(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.unlock_button))
                }
            }
            if (state.biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onBiometricUnlock,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.unlock_biometric))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onPickVault, enabled = !state.busy) {
                Text(stringResource(R.string.unlock_change_vault))
            }
        }

        if (state.vaultUri == null && state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Moldura das telas de início: rolável, centralizada, largura máxima de 480 dp (fica bonita no tablet) e com
 * as margens do sistema e do teclado — o teclado nunca cobre um botão.
 */
@Composable
fun StartLayout(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // Surface (não só um fundo pintado): define também a cor do texto, senão o título sai preto no tema escuro.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
    }
}

/** Logo (cadeado no quadrado índigo), "Local Keys" e o subtítulo de uma linha. */
@Composable
fun BrandHeader() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(44.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.unlock_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun VaultFileChip(name: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.InsertDriveFile,
                contentDescription = stringResource(R.string.unlock_vault_file),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
