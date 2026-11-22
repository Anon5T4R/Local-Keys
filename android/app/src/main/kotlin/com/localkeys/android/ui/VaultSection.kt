package com.localkeys.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.localkeys.android.R
import com.localkeys.android.data.store.RecentVault

/** O que a seção "Cofre" das configurações precisa para trocar de cofre. */
data class VaultSwitchActions(
    /** Tranca o cofre atual e carrega o escolhido (que pede a senha ou a biometria dele). */
    val onSwitch: (String) -> Unit,
    /** Escolher outro arquivo pelo seletor do sistema (também tranca o atual ao escolher). */
    val onOpenOther: () -> Unit,
    /** Só esquece o cofre da lista; o arquivo fica onde está. */
    val onForget: (String) -> Unit,
)

/**
 * Seção "Cofre": o cofre aberto, os outros recentes (tocar = trancar este e abrir aquele) com o X que só os
 * tira da lista, e "Abrir outro cofre…".
 */
@Composable
fun VaultSection(
    vaultName: String?,
    currentUri: String?,
    recents: List<RecentVault>,
    busy: Boolean,
    actions: VaultSwitchActions,
) {
    var forgetting by remember { mutableStateOf<RecentVault?>(null) }

    SectionTitle(stringResource(R.string.settings_vault))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            Text(vaultName ?: stringResource(R.string.unlock_vault_unknown), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(stringResource(R.string.settings_vault_current)) },
    )
    recents.filterNot { it.uri == currentUri }.forEach { vault ->
        ListItem(
            leadingContent = { Icon(Icons.Outlined.InsertDriveFile, contentDescription = null) },
            headlineContent = { Text(vault.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(stringResource(R.string.settings_vault_switch_hint)) },
            trailingContent = {
                IconButton(onClick = { forgetting = vault }, enabled = !busy) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.switch_forget_named, vault.name))
                }
            },
            modifier = Modifier.clickable(enabled = !busy) { actions.onSwitch(vault.uri) },
        )
    }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_vault_open_other)) },
        supportingContent = { Text(stringResource(R.string.settings_vault_open_other_body)) },
        modifier = Modifier.clickable(enabled = !busy, onClick = actions.onOpenOther),
    )

    forgetting?.let { vault ->
        ConfirmDialog(
            title = stringResource(R.string.switch_forget_title),
            message = stringResource(R.string.switch_forget_message, vault.name),
            confirmLabel = stringResource(R.string.switch_forget),
            onConfirm = {
                forgetting = null
                actions.onForget(vault.uri)
            },
            onDismiss = { forgetting = null },
        )
    }
}
