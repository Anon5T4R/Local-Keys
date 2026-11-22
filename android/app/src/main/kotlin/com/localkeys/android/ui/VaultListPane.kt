package com.localkeys.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.ui.theme.FavoriteAmber

/** Ações do menu ⋮ da lista. [onImportOther] null = aparelho sem Credential Exchange (Android < 14). */
data class VaultMenuActions(
    val onImportFile: () -> Unit,
    val onImportOther: (() -> Unit)?,
    val onExport: () -> Unit,
    val onFolders: () -> Unit,
    val onTrash: () -> Unit,
    val onSettings: () -> Unit,
)

/** Estado salvável da lista: busca, filtro e pasta. Sobrevive a rotação e a ida e volta das outras telas. */
class ListFilterState(query: String, searching: Boolean, filter: ListFilter, folderKey: String?) {
    var query by mutableStateOf(query)
    var searching by mutableStateOf(searching)
    var filter by mutableStateOf(filter)
    var folderKey by mutableStateOf(folderKey)
    val folder: FolderFilter get() = FolderFilter.fromKey(folderKey)

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ListFilterState, Any?>(
            save = { listOf(it.query, it.searching, it.filter.encode(), it.folderKey) },
            restore = { ListFilterState(it[0] as String, it[1] as Boolean, ListFilter.decode(it[2] as String?), it[3] as String?) },
        )
    }
}

@Composable
fun rememberListFilterState(): ListFilterState =
    rememberSaveable(saver = ListFilterState.Saver) { ListFilterState("", false, ListFilter.NONE, null) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListPane(
    vault: Vault,
    filters: ListFilterState,
    selectedId: String?,
    busy: Boolean,
    totpClock: State<Long>,
    snackbarHostState: SnackbarHostState,
    menu: VaultMenuActions,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onLock: () -> Unit,
    /** Cartão do atualizador no topo da lista (null = nada a mostrar). */
    banner: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val visible = remember(vault.items, filters.query, filters.filter, filters.folderKey) {
        VaultFilters.visible(vault.items, filters.filter, filters.folder, filters.query)
    }
    val anyLive = remember(vault.items) { vault.items.any { !it.inTrash } }

    // Pasta apagada (aqui ou por outro aparelho): volta para "todas".
    LaunchedEffect(vault.folders, filters.folderKey) {
        val f = filters.folder
        if (f is FolderFilter.In && vault.folders.none { it.id == f.id }) filters.folderKey = null
    }

    BackHandler(enabled = filters.searching) {
        filters.searching = false
        filters.query = ""
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                ListTopBar(filters = filters, busy = busy, menu = menu, onLock = onLock)
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_add))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            banner?.invoke()
            ListFilterBar(filters = filters, folders = vault.folders)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (visible.isEmpty()) {
                if (!anyLive) {
                    EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.vault_empty_title),
                        body = stringResource(R.string.vault_empty),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.vault_no_match_title),
                        body = stringResource(R.string.vault_no_match),
                    )
                }
            } else {
                // Lista estreita (painel do tablet em pé): o código TOTP sai da linha para o nome caber; ele
                // continua no detalhe ao lado.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val rowTotp = maxWidth >= 400.dp
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                ) {
                    items(visible, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            selected = item.id == selectedId,
                            showTotp = rowTotp,
                            totpClock = totpClock,
                            onClick = { onOpen(item.id) },
                            onToggleFavorite = { onToggleFavorite(item.id) },
                        )
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTopBar(filters: ListFilterState, busy: Boolean, menu: VaultMenuActions, onLock: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    if (filters.searching) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = {
                    filters.searching = false
                    filters.query = ""
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close))
                }
            },
            title = {
                TextField(
                    value = filters.query,
                    onValueChange = { filters.query = it },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            },
            actions = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { filters.query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
        )
        return
    }
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.app_name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = { filters.searching = true }) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_open))
            }
            IconButton(onClick = onLock) {
                Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.vault_lock))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    fun pick(action: () -> Unit) {
                        menuOpen = false
                        action()
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_import_file)) },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                        enabled = !busy,
                        onClick = { pick(menu.onImportFile) },
                    )
                    menu.onImportOther?.let { importOther ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_import_other)) },
                            leadingIcon = { Icon(Icons.Outlined.SyncAlt, contentDescription = null) },
                            enabled = !busy,
                            onClick = { pick(importOther) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_export)) },
                        leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                        enabled = !busy,
                        onClick = { pick(menu.onExport) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folders_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = { pick(menu.onFolders) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { pick(menu.onTrash) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = { pick(menu.onSettings) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ItemRow(
    item: Item,
    selected: Boolean,
    showTotp: Boolean,
    totpClock: State<Long>,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val subtitle = remember(item) { VaultFilters.subtitle(item) }
    val hasTotp = VaultFilters.hasTotp(item)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { KindAvatar(item.kind) },
        headlineContent = {
            Text(
                text = item.name.ifBlank { stringResource(R.string.item_untitled) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Badges(item, showTotpBadge = hasTotp && !showTotp, modifier = Modifier.padding(start = if (subtitle.isNotEmpty()) 8.dp else 0.dp))
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasTotp && showTotp) RowTotp(item.login?.totp.orEmpty(), totpClock)
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(if (item.favorite) R.string.item_unfavorite else R.string.item_favorite),
                        tint = if (item.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Ícones pequenos de passkey e anexo (o TOTP já aparece como código vivo na linha). */
@Composable
private fun Badges(item: Item, showTotpBadge: Boolean, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showTotpBadge) {
            Icon(Icons.Outlined.Schedule, stringResource(R.string.badge_totp), Modifier.size(16.dp), tint)
        }
        if (item.livePasskeys.isNotEmpty()) {
            Icon(Icons.Outlined.Fingerprint, stringResource(R.string.badge_passkey), Modifier.size(16.dp), tint)
        }
        if (item.liveAttachments.isNotEmpty()) {
            Icon(Icons.Outlined.AttachFile, stringResource(R.string.badge_attachment), Modifier.size(16.dp), tint)
        }
    }
}

/** Código TOTP vivo na linha; tocar copia. */
@Composable
private fun RowTotp(secret: String, clock: State<Long>) {
    val code = rememberTotpCode(secret, clock) ?: return
    val context = LocalContext.current
    val label = stringResource(R.string.field_totp_code)
    Row(
        modifier = Modifier
            .clickable { copyToClipboard(context, label, code.code, sensitive = true) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = code.code.chunked(3).joinToString(" "),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        TotpRing(code, size = 22.dp)
    }
}
