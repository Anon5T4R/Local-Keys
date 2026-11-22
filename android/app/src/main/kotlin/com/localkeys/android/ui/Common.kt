package com.localkeys.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.totp.Totp
import com.localkeys.android.data.totp.TotpCode
import com.localkeys.android.data.vault.ItemKind
import kotlinx.coroutines.delay

/** Margem lateral padrão das telas. */
val Gutter: Dp = 16.dp

/** Ícone de cada tipo de item (lista, detalhe e editor usam o mesmo). */
fun kindIcon(kind: ItemKind): ImageVector = when (kind) {
    ItemKind.LOGIN -> Icons.Outlined.Key
    ItemKind.NOTE -> Icons.Outlined.Description
    ItemKind.CARD -> Icons.Outlined.CreditCard
    ItemKind.IDENTITY -> Icons.Outlined.Badge
}

@Composable
fun kindLabel(kind: ItemKind): String = stringResource(
    when (kind) {
        ItemKind.LOGIN -> R.string.kind_login
        ItemKind.NOTE -> R.string.kind_note
        ItemKind.CARD -> R.string.kind_card
        ItemKind.IDENTITY -> R.string.kind_identity
    },
)

/** O ícone do tipo num círculo tingido (avatar das linhas e do cabeçalho do detalhe). */
@Composable
fun KindAvatar(kind: ItemKind, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kindIcon(kind),
            contentDescription = kindLabel(kind),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Copia para a área de transferência. Segredos (senha, código, número do cartão) vão marcados como sensíveis:
 * no Android 13+ o sistema não mostra o conteúdo na prévia da cópia. O próprio Android 13+ já confirma a cópia;
 * antes dele, um toast curto avisa.
 */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
    }
    manager.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.copy_copied_what, label), Toast.LENGTH_SHORT).show()
    }
}

/** Campo de senha com o ícone de olho para mostrar/ocultar (e espaço para mais ações à direita). */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    extraActions: @Composable () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraActions()
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.unlock_hide else R.string.unlock_show),
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Diálogo de confirmação simples; [destructive] pinta o botão de confirmar de vermelho. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/**
 * Relógio compartilhado de todos os TOTP da tela: UMA corrotina atualizando o segundo atual a cada 1 s. O que
 * desce para as linhas é o [State] (identidade estável); a leitura acontece dentro de cada linha, então só quem
 * mostra TOTP recompõe a cada segundo.
 */
@Composable
fun rememberTotpClock(): State<Long> = produceState(initialValue = System.currentTimeMillis() / 1000) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis() / 1000
    }
}

/** Código TOTP vivo (null se a chave não serve). */
@Composable
fun rememberTotpCode(secret: String, clock: State<Long>): TotpCode? {
    val now by clock
    return remember(now, secret) { Totp.atOrNull(secret, now) }
}

/** Anel de contagem regressiva do TOTP com os segundos no meio. */
@Composable
fun TotpRing(code: TotpCode, size: Dp = 32.dp) {
    val remaining = code.secondsRemaining
    val color = if (remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.totp_seconds_left, remaining.toInt())
    Box(
        modifier = Modifier.size(size).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { (remaining / code.period.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.size(size),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 3.dp,
        )
        Text(
            text = remaining.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Data legível no locale do aparelho. */
fun formatDateTime(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMillis))

fun formatDate(epochMillis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))

/** "12 KB", "3,4 MB". */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Título de seção (rótulo pequeno em cor primária). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 8.dp),
    )
}

/** Mensagem centralizada de tela/lista vazia. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
