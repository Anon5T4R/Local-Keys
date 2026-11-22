package com.localkeys.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.ui.VaultViewModel.PendingImport

/**
 * Escolha do formato do export. O aviso é proposital: o arquivo sai EM CLARO (sem cifra) — serve para migrar de
 * app, não para backup.
 */
@Composable
fun ExportDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.export_warning), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.export_json)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick("json") },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.TableChart, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.export_csv)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick("csv") },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/** Export cifrado (Bitwarden) ou cofre KeePass: pede a senha DO ARQUIVO (não a do cofre) para decifrar. */
@Composable
fun ImportPasswordDialog(
    pending: PendingImport,
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val isKdbx = pending.source == VaultViewModel.ImportSource.KDBX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.import_file, pending.fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(if (isKdbx) R.string.import_kdbx_hint else R.string.import_encrypted_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(if (isKdbx) R.string.import_kdbx_password_label else R.string.import_password_label),
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    onImeAction = { if (password.isNotBlank() && !busy) onConfirm(password) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank() && !busy) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}

/** O arquivo mudou fora do app: recarregar (adota o do disco) ou sobrescrever (grava o daqui). */
@Composable
fun ExternalChangeDialog(onReload: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_change_title)) },
        text = { Text(stringResource(R.string.external_change_message)) },
        confirmButton = {
            TextButton(onClick = onReload) { Text(stringResource(R.string.external_change_reload)) }
        },
        dismissButton = {
            TextButton(onClick = onOverwrite) { Text(stringResource(R.string.external_change_overwrite)) }
        },
    )
}
