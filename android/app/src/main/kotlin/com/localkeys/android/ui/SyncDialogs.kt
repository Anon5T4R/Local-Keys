package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.sync.VaultMerge

/** "Sincronizar com outra cópia": a senha da outra cópia (se a atual não abriu) ou o resumo antes de gravar. */
@Composable
fun SyncDialog(
    pending: VaultViewModel.SyncPending,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val summary = pending.summary
    if (pending.needsPassword || summary == null) {
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) onCancel() },
            title = { Text(stringResource(R.string.sync_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sync_password_message, pending.otherName))
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.unlock_password),
                        enabled = !busy,
                        isError = pending.passwordError != null,
                        supportingText = pending.passwordError?.let { msg -> { Text(msg) } },
                        onImeAction = { if (password.isNotBlank()) onPassword(password) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onPassword(password) }, enabled = !busy && password.isNotBlank()) {
                    Text(stringResource(R.string.sync_open))
                }
            },
            dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.editor_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (summary.nothingChanges) R.string.sync_summary_same_title else R.string.sync_summary_title,
                    pending.otherName,
                ),
            )
        },
        text = { SummaryBody(summary) },
        confirmButton = {
            if (summary.nothingChanges) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.sync_ok)) }
            } else {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.sync_confirm)) }
            }
        },
        dismissButton = if (summary.nothingChanges) {
            null
        } else {
            { TextButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) } }
        },
    )
}

@Composable
private fun SummaryBody(s: VaultMerge.Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.nothingChanges) {
            Text(stringResource(R.string.sync_summary_nothing))
            return@Column
        }
        Text(stringResource(R.string.sync_summary_here), style = MaterialTheme.typography.titleSmall)
        Count(R.string.sync_count_added, s.added)
        Count(R.string.sync_count_updated, s.updated)
        Count(R.string.sync_count_trashed, s.trashed)
        Count(R.string.sync_count_restored, s.restored)
        Count(R.string.sync_count_folders, s.foldersAdded)
        Count(R.string.sync_count_conflicts, s.conflicts)
        Text(
            stringResource(R.string.sync_summary_there),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Count(R.string.sync_count_to_other, s.toOther)
        Count(R.string.sync_count_folders, s.foldersToOther)
        Text(
            stringResource(R.string.sync_summary_no_backup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Count(label: Int, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (value > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}
