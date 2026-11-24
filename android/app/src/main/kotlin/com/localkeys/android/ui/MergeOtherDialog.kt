package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeys.android.R

/**
 * "Juntar outro cofre": a senha do outro (quando a chave deste não o abriu) ou o resumo antes de gravar. O cofre
 * aberto só é gravado ao confirmar; o outro arquivo nunca é escrito.
 */
@Composable
fun MergeOtherDialog(
    stage: MergeOtherStage,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    when (stage) {
        is MergeOtherStage.NeedsPassword -> PasswordStep(stage, busy, onPassword, onCancel)
        is MergeOtherStage.Ready -> SummaryStep(stage, busy, onConfirm, onCancel)
    }
}

@Composable
private fun PasswordStep(
    stage: MergeOtherStage.NeedsPassword,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // `remember`, não `rememberSaveable`: a senha do outro cofre não vai para o estado salvo (rotação/morte do
    // processo) e some do campo assim que a pessoa confirma — o ViewModel não guarda cópia.
    var password by remember(stage.name) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.merge_other_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.merge_other_password_message, stage.name))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.unlock_password),
                    enabled = !busy,
                    isError = stage.error != null,
                    supportingText = stage.error?.let { msg -> { Text(msg) } },
                    onImeAction = { if (password.isNotBlank() && !busy) onPassword(password) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val typed = password
                    password = ""
                    onPassword(typed)
                },
                enabled = !busy && password.isNotBlank(),
            ) { Text(stringResource(R.string.sync_open)) }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
private fun SummaryStep(
    stage: MergeOtherStage.Ready,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = {
            Text(
                stringResource(
                    if (stage.nothingChanges) R.string.merge_other_summary_nothing_title else R.string.merge_other_summary_title,
                    stage.name,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stage.nothingChanges) {
                    Text(stringResource(R.string.merge_other_summary_nothing))
                } else {
                    for (line in MergeOtherSummary.lines(stage.counts)) {
                        Text(pluralStringResource(line.label, line.count, line.count))
                    }
                    Text(
                        stringResource(R.string.merge_other_summary_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (stage.nothingChanges) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.merge_other_close)) }
            } else {
                TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.merge_other_confirm)) }
            }
        },
        dismissButton = if (stage.nothingChanges) {
            null
        } else {
            { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.editor_cancel)) } }
        },
    )
}
