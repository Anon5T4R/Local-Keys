package com.localkeys.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.ui.theme.FavoriteAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O que o detalhe pede ao cofre (todas as ações gravam no arquivo pelo ViewModel). */
data class DetailActions(
    val onEdit: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onTrash: () -> Unit,
    val onTrashAttachment: (String) -> Unit,
    val onRestoreAttachment: (String) -> Unit,
    val onDeleteAttachmentForever: (String) -> Unit,
)

/**
 * Detalhe de um item em tela cheia (no celular) ou no painel da direita (no tablet, sem a seta de voltar).
 * [item] null = o item ainda não chegou (acabou de ser criado) ou não existe mais.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    item: Item?,
    folders: List<Folder>,
    busy: Boolean,
    totpClock: State<Long>,
    snackbarHostState: SnackbarHostState?,
    onBack: (() -> Unit)?,
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = actions.onToggleFavorite, enabled = !busy) {
                            Icon(
                                imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(if (item.favorite) R.string.item_unfavorite else R.string.item_favorite),
                                tint = if (item.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = actions.onEdit, enabled = !busy) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.item_edit))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.item_move_to_trash)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        confirmTrash = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
    ) { padding ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = stringResource(R.string.detail_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        // Uma chave por item: no tablet, trocar de item zera o "mostrar senha" e a rolagem.
        key(item.id) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                DetailHeader(item, folders)
                DetailBody(item, totpClock, actions, busy)
            }
        }
    }

    if (confirmTrash && item != null) {
        ConfirmDialog(
            title = stringResource(R.string.trash_confirm_title),
            message = stringResource(R.string.trash_confirm_message, item.name),
            confirmLabel = stringResource(R.string.item_move_to_trash),
            destructive = true,
            onConfirm = {
                confirmTrash = false
                actions.onTrash()
            },
            onDismiss = { confirmTrash = false },
        )
    }
}

@Composable
private fun DetailHeader(item: Item, folders: List<Folder>) {
    val folderName = folders.firstOrNull { it.id == item.folderId }?.name ?: stringResource(R.string.folder_sem)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindAvatar(item.kind, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { stringResource(R.string.item_untitled) },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${kindLabel(item.kind)} · $folderName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailBody(item: Item, totpClock: State<Long>, actions: DetailActions, busy: Boolean) {
    val context = LocalContext.current
    when (item.kind) {
        ItemKind.LOGIN -> item.login?.let { login ->
            val rows = buildList<@Composable () -> Unit> {
                if (login.username.isNotBlank()) add { FieldRow(stringResource(R.string.field_username), login.username) }
                if (login.password.isNotEmpty()) add { FieldRow(stringResource(R.string.field_password), login.password, secret = true, monospace = true) }
                if (login.totp.isNotBlank()) add { TotpField(login.totp, totpClock) }
                login.uris.filter { it.isNotBlank() }.forEach { uri ->
                    add {
                        val url = VaultFilters.openableUrl(uri)
                        FieldRow(
                            label = stringResource(R.string.field_uri),
                            value = uri,
                            extra = if (url != null) {
                                {
                                    IconButton(onClick = { openUrl(context, url) }) {
                                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.detail_open_site))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            if (rows.isNotEmpty()) FieldCard(rows)
        }
        ItemKind.CARD -> item.card?.let { card ->
            val rows = buildList<@Composable () -> Unit> {
                if (card.cardholder.isNotBlank()) add { FieldRow(stringResource(R.string.field_cardholder), card.cardholder) }
                if (card.brand.isNotBlank()) add { FieldRow(stringResource(R.string.field_brand), card.brand) }
                if (card.number.isNotBlank()) add { FieldRow(stringResource(R.string.field_number), card.number, secret = true, monospace = true) }
                if (card.exp.isNotBlank()) add { FieldRow(stringResource(R.string.field_exp), card.exp) }
                if (card.code.isNotBlank()) add { FieldRow(stringResource(R.string.field_code), card.code, secret = true, monospace = true) }
            }
            if (rows.isNotEmpty()) FieldCard(rows)
        }
        ItemKind.IDENTITY -> item.identity?.let { id ->
            val rows = buildList<@Composable () -> Unit> {
                if (id.firstName.isNotBlank()) add { FieldRow(stringResource(R.string.field_first_name), id.firstName) }
                if (id.lastName.isNotBlank()) add { FieldRow(stringResource(R.string.field_last_name), id.lastName) }
                if (id.email.isNotBlank()) add { FieldRow(stringResource(R.string.field_email), id.email) }
                if (id.phone.isNotBlank()) add { FieldRow(stringResource(R.string.field_phone), id.phone) }
                if (id.address.isNotBlank()) add { FieldRow(stringResource(R.string.field_address), id.address) }
            }
            if (rows.isNotEmpty()) FieldCard(rows)
        }
        ItemKind.NOTE -> Unit
    }

    if (item.notes.isNotBlank()) {
        SectionTitle(stringResource(if (item.kind == ItemKind.NOTE) R.string.field_note else R.string.field_notes))
        FieldCardOf(listOf(item.notes)) { NoteBlock(it) }
    }

    item.customFields?.takeIf { it.isNotEmpty() }?.let { fields ->
        SectionTitle(stringResource(R.string.custom_fields))
        FieldCardOf(fields) { f -> FieldRow(f.name.ifBlank { "—" }, f.value, secret = f.hidden) }
    }

    if (item.livePasskeys.isNotEmpty()) {
        SectionTitle(stringResource(R.string.passkeys_title))
        FieldCardOf(item.livePasskeys) { pk ->
                    InfoRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = pk.rpId,
                        subtitle = listOf(
                            pk.userName.ifBlank { pk.userDisplayName },
                            if (pk.createdAt > 0) stringResource(R.string.passkey_created, formatDate(pk.createdAt)) else "",
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                    )
        }
    }

    AttachmentsSection(item, actions, busy)

    item.passwordHistory?.takeIf { it.isNotEmpty() }?.let { history ->
        var open by rememberSaveable(item.id) { mutableStateOf(false) }
        ExpandableTitle(stringResource(R.string.history_title, history.size), open) { open = !open }
        if (open) {
            FieldCardOf(history) { e -> FieldRow(formatDateTime(e.at), e.password, secret = true, monospace = true) }
        }
    }

    Text(
        text = buildString {
            if (item.createdAt > 0) append(stringResource(R.string.detail_created, formatDateTime(item.createdAt)))
            if (item.updatedAt > 0) {
                if (isNotEmpty()) append("\n")
                append(stringResource(R.string.detail_updated, formatDateTime(item.updatedAt)))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Gutter, vertical = 20.dp),
    )
}

@Composable
private fun AttachmentsSection(item: Item, actions: DetailActions, busy: Boolean) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    var confirmForever by remember { mutableStateOf<Attachment?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val att = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(Base64.decode(att.dataB64, Base64.NO_WRAP))
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) R.string.attach_saved else R.string.attach_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val live = item.liveAttachments
    val trashed = item.trashedAttachments
    if (live.isNotEmpty()) {
        SectionTitle(stringResource(R.string.attach_title))
        FieldCardOf(live) { att ->
                    InfoRow(
                        icon = Icons.Outlined.InsertDriveFile,
                        title = att.name,
                        subtitle = formatSize(att.size),
                    ) {
                        IconButton(onClick = { pendingSave = att; saver.launch(att.name) }) {
                            Icon(Icons.Outlined.Download, stringResource(R.string.attach_save_named, att.name))
                        }
                        IconButton(onClick = { actions.onTrashAttachment(att.id) }, enabled = !busy) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.attach_trash_named, att.name))
                        }
                    }
        }
    }
    if (trashed.isNotEmpty()) {
        var open by rememberSaveable(item.id) { mutableStateOf(false) }
        ExpandableTitle(stringResource(R.string.attach_trash_title, trashed.size), open) { open = !open }
        if (open) {
            FieldCardOf(trashed) { att ->
                        InfoRow(
                            icon = Icons.Outlined.InsertDriveFile,
                            title = att.name,
                            subtitle = listOfNotNull(
                                formatSize(att.size),
                                att.deletedAt?.let { stringResource(R.string.trash_deleted_at, formatDate(it)) },
                            ).joinToString(" · "),
                        ) {
                            IconButton(onClick = { actions.onRestoreAttachment(att.id) }, enabled = !busy) {
                                Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.trash_restore_named, att.name))
                            }
                            IconButton(onClick = { confirmForever = att }, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    stringResource(R.string.trash_delete_forever_named, att.name),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
            }
        }
    }

    confirmForever?.let { att ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever_title),
            message = stringResource(R.string.attach_delete_forever_message, att.name),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            destructive = true,
            onConfirm = {
                confirmForever = null
                actions.onDeleteAttachmentForever(att.id)
            },
            onDismiss = { confirmForever = null },
        )
    }
}

/** Cartão com uma linha por elemento de [items]. */
@Composable
fun <T> FieldCardOf(items: List<T>, row: @Composable (T) -> Unit) {
    FieldCard(items.map { value -> @Composable { row(value) } })
}

/** Cartão arredondado com as linhas separadas por divisórias recuadas. */
@Composable
fun FieldCard(rows: List<@Composable () -> Unit>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
    ) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = Gutter), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                row()
            }
        }
    }
}

/**
 * Um campo: rótulo pequeno, valor e as ações (mostrar, copiar, extras). [secret] mascara o valor até tocar no
 * olho e marca a cópia como sensível.
 */
@Composable
fun FieldRow(
    label: String,
    value: String,
    secret: Boolean = false,
    monospace: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var revealed by rememberSaveable { mutableStateOf(false) }
    val shown = if (secret && !revealed) "•".repeat(value.length.coerceIn(8, 16)) else value
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionContainer {
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = if (monospace && (!secret || revealed)) FontFamily.Monospace else null,
                )
            }
        }
        if (secret) {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (revealed) R.string.detail_hide_named else R.string.detail_show_named, label),
                )
            }
        }
        extra?.invoke()
        IconButton(onClick = { copyToClipboard(context, label, value, sensitive = secret) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.detail_copy_named, label))
        }
    }
}

@Composable
private fun TotpField(secret: String, clock: State<Long>) {
    val context = LocalContext.current
    val label = stringResource(R.string.field_totp_code)
    val code = rememberTotpCode(secret, clock)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (code == null) {
                Text(stringResource(R.string.totp_invalid), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    text = code.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (code != null) {
            TotpRing(code, size = 36.dp)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { copyToClipboard(context, label, code.code, sensitive = true) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.detail_copy_named, label))
            }
        }
    }
}

@Composable
private fun NoteBlock(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(Gutter),
        )
    }
}

/** Linha com ícone, título e subtítulo, e ações à direita (anexos, passkeys, lixeira). */
@Composable
fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
private fun ExpandableTitle(text: String, open: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(4.dp))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    Spacer(Modifier.height(4.dp))
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.detail_no_browser, Toast.LENGTH_SHORT).show()
    }
}
