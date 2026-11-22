package com.localkeys.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.update.UpdateFailure
import java.text.DateFormat
import java.util.Date

/** Ganchos do atualizador para as telas (o [UpdateViewModel] mora na Activity). */
data class UpdateActions(
    val onAutoCheck: (Boolean) -> Unit,
    val onCheckNow: () -> Unit,
    val onUpdate: () -> Unit,
    val onDismissBanner: () -> Unit,
)

/** Cartão no topo da lista: versão nova disponível (com "Atualizar" e dispensar) ou o download em curso. */
@Composable
fun UpdateBanner(state: UpdateViewModel.UiState, actions: UpdateActions) {
    val progress = state.progress
    val release = state.available
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = progressText(progress) ?: stringResource(R.string.update_banner, release?.version.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (progress == null) {
                    TextButton(onClick = actions.onUpdate) { Text(stringResource(R.string.update_action)) }
                    IconButton(onClick = actions.onDismissBanner) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.update_dismiss))
                    }
                }
            }
            if (progress != null) ProgressBar(progress, Modifier.padding(top = 8.dp, end = 12.dp, bottom = 4.dp))
        }
    }
}

/** Seção "Atualizações" das configurações. */
@Composable
fun UpdateSettingsSection(state: UpdateViewModel.UiState, actions: UpdateActions) {
    SectionTitle(stringResource(R.string.settings_updates))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Update, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.update_auto_title)) },
        supportingContent = { Text(stringResource(R.string.update_auto_body)) },
        trailingContent = { Switch(checked = state.autoCheck, onCheckedChange = actions.onAutoCheck) },
        modifier = Modifier.clickable { actions.onAutoCheck(!state.autoCheck) },
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
        Text(
            statusText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.status is UpdateViewModel.CheckStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            stringResource(R.string.update_current_version, state.currentVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        state.progress?.let { progress ->
            Text(progressText(progress).orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            ProgressBar(progress, Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = actions.onCheckNow, enabled = !state.checking && state.progress == null) {
                if (state.checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_checking))
                } else {
                    Text(stringResource(R.string.update_check_now))
                }
            }
            if (state.available != null && state.progress == null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = actions.onUpdate) {
                    Text(stringResource(R.string.update_action_version, state.available.version))
                }
            }
        }
    }
}

/** Explica a permissão "instalar apps desconhecidos" antes de mandar o usuário à tela do sistema. */
@Composable
fun InstallPermissionDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_permission_title)) },
        text = { Text(stringResource(R.string.update_permission_body)) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.update_permission_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
private fun ProgressBar(progress: UpdateViewModel.Progress, modifier: Modifier) {
    when (progress) {
        is UpdateViewModel.Progress.Downloading -> if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.read.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier.fillMaxWidth())
        }
        is UpdateViewModel.Progress.Installing -> LinearProgressIndicator(modifier.fillMaxWidth())
    }
}

@Composable
private fun progressText(progress: UpdateViewModel.Progress?): String? = when (progress) {
    null -> null
    is UpdateViewModel.Progress.Downloading -> {
        val pct = if (progress.total > 0) ((progress.read * 100) / progress.total).toInt().coerceIn(0, 100) else 0
        stringResource(R.string.update_downloading, progress.version, pct)
    }
    is UpdateViewModel.Progress.Installing -> stringResource(R.string.update_installing, progress.version)
}

@Composable
private fun statusText(state: UpdateViewModel.UiState): String {
    val context = LocalContext.current
    val at = if (state.lastCheckAt > 0) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastCheckAt))
    } else {
        ""
    }
    return when (val s = state.status) {
        UpdateViewModel.CheckStatus.Never -> stringResource(R.string.update_status_never)
        UpdateViewModel.CheckStatus.UpToDate -> stringResource(R.string.update_status_up_to_date, at)
        is UpdateViewModel.CheckStatus.Available -> stringResource(R.string.update_status_available, s.version)
        is UpdateViewModel.CheckStatus.Failed -> stringResource(R.string.update_status_error, failureText(context, s.failure, s.code))
    }
}

/** Texto da falha, para a linha de status e os avisos. */
fun failureText(context: android.content.Context, failure: UpdateFailure, code: Int): String = when (failure) {
    UpdateFailure.NETWORK -> context.getString(R.string.update_error_network)
    UpdateFailure.HTTP -> context.getString(R.string.update_error_http, code)
    UpdateFailure.PARSE -> context.getString(R.string.update_error_parse)
    UpdateFailure.TOO_LARGE -> context.getString(R.string.update_error_too_large)
    UpdateFailure.DIGEST -> context.getString(R.string.update_error_digest)
    UpdateFailure.STORAGE -> context.getString(R.string.update_error_storage)
    UpdateFailure.INSTALL -> context.getString(R.string.update_error_install)
}

/** Texto do aviso único de instalação (snackbar). */
fun noticeText(context: android.content.Context, notice: UpdateViewModel.Notice): String = when (notice) {
    is UpdateViewModel.Notice.Updated -> context.getString(R.string.update_done_notice, notice.version)
    UpdateViewModel.Notice.Aborted -> context.getString(R.string.update_cancelled_notice)
    is UpdateViewModel.Notice.Failed -> context.getString(R.string.update_failed_notice, notice.message)
    is UpdateViewModel.Notice.Error ->
        context.getString(R.string.update_failed_notice, failureText(context, notice.failure, notice.code))
}
