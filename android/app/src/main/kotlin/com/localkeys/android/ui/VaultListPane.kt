package com.localkeys.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.store.ListFolder
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.ui.theme.FavoriteAmber

/** Ações do menu ⋮ da lista. [onImportOther] null = aparelho sem Credential Exchange (Android < 14). */
data class VaultMenuActions(
    val onImportFile: () -> Unit,
    val onImportOther: (() -> Unit)?,
    val onExport: () -> Unit,
    /** Juntar outro cofre DIFERENTE no aberto (o outro só é lido; o resumo vem antes de gravar). */
    val onMergeOther: () -> Unit,
    val onFolders: () -> Unit,
    val onTrash: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * Estado salvável da lista: busca, filtro e pasta. Sobrevive a rotação e a ida e volta das outras telas. A pasta
 * escolhida é lembrada nas preferências ([onFolderChanged]); a busca e o filtro de tipo não são.
 */
class ListFilterState(
    query: String,
    searching: Boolean,
    filter: ListFilter,
    initialFolderKey: String?,
    /** A pasta veio do estado salvo (rotação/morte do processo): a preferência que chega depois não mexe nela. */
    restored: Boolean = false,
) {
    var query by mutableStateOf(query)
    var searching by mutableStateOf(searching)
    var filter by mutableStateOf(filter)

    private var folderKeyState by mutableStateOf(initialFolderKey)

    /** A pasta já foi decidida nesta tela (escolha da pessoa ou restauração): a lembrança não sobrescreve. */
    private var folderChosen = restored || initialFolderKey != null

    /** Grava a pasta escolhida nas preferências (null = todas). A tela aponta para elas. */
    var onFolderChanged: (String?) -> Unit = {}

    /** Pasta escolhida; cada mudança (inclusive voltar para "todas") é lembrada. */
    var folderKey: String?
        get() = folderKeyState
        set(value) {
            folderKeyState = value
            folderChosen = true
            onFolderChanged(value)
        }

    val folder: FolderFilter get() = FolderFilter.fromKey(folderKeyState)

    /**
     * Abre a lista já na pasta lembrada, sem regravar (a preferência veio de lá). Só vale antes de qualquer
     * escolha: a preferência pode chegar do disco alguns quadros depois do primeiro desenho.
     */
    fun openIn(savedKey: String?) {
        if (folderChosen) return
        folderKeyState = savedKey
        folderChosen = savedKey != null
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ListFilterState, Any?>(
            save = { listOf(it.query, it.searching, it.filter.encode(), it.folderKey) },
            restore = {
                ListFilterState(it[0] as String, it[1] as Boolean, ListFilter.decode(it[2] as String?), it[3] as String?, restored = true)
            },
        )
    }
}

/**
 * O estado da lista, criado já na pasta lembrada ([rememberedFolderKey], validada contra [folderIds]) e gravando
 * cada escolha de pasta em [onFolderChanged].
 */
@Composable
fun rememberListFilterState(
    rememberedFolderKey: String?,
    folderIds: Collection<String>,
    onFolderChanged: (String?) -> Unit,
): ListFilterState {
    val state = rememberSaveable(saver = ListFilterState.Saver) {
        ListFilterState("", false, ListFilter.NONE, ListFolder.openKey(rememberedFolderKey, folderIds))
    }
    // A instância restaurada é a que a tela usa; o gravador é sempre o de agora.
    SideEffect { state.onFolderChanged = onFolderChanged }
    // A preferência pode chegar do DataStore depois do primeiro desenho: entra uma vez, e nunca por cima de uma
    // pasta que a pessoa já escolheu.
    val openKey = ListFolder.openKey(rememberedFolderKey, folderIds)
    LaunchedEffect(openKey) { state.openIn(openKey) }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListPane(
    vault: Vault,
    filters: ListFilterState,
    selectedId: String?,
    /** A seleção múltipla (modo, ids marcados); as regras do toque moram nela. */
    selection: ListSelectionMode,
    busy: Boolean,
    totpClock: State<Long>,
    snackbarHostState: SnackbarHostState,
    menu: VaultMenuActions,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onLock: () -> Unit,
    /** Mover a seleção para uma pasta (`null` = sem pasta); grava uma vez no `VaultViewModel`. */
    onMoveSelected: (String?) -> Unit,
    /** Apagar a seleção (depois da confirmação): manda para a lixeira. */
    onTrashSelected: () -> Unit,
    /** Exportar a seleção para um cofre NOVO: abre o "criar" do sistema e depois o diálogo das senhas. */
    onExportSelected: () -> Unit,
    /** Cartão do atualizador no topo da lista (null = nada a mostrar). */
    banner: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val visible = remember(vault.items, filters.query, filters.filter, filters.folderKey) {
        VaultFilters.visible(vault.items, filters.filter, filters.folder, filters.query)
    }
    val anyLive = remember(vault.items) { vault.items.any { !it.inTrash } }
    var confirmingTrash by remember { mutableStateOf(false) }

    // Trocar a busca, o filtro de tipo ou a pasta zera a seleção: os ids marcados podem nem estar na lista nova.
    // A assinatura é comparada dentro do modo, então uma recomposição (ou a volta da rotação) não perde nada.
    val filterSignature = "${filters.query}\u0000${filters.filter.encode()}\u0000${filters.folderKey ?: "todas"}"
    SideEffect { selection.syncFilter(filterSignature) }

    // Pasta apagada (aqui ou por outro aparelho): volta para "todas".
    LaunchedEffect(vault.folders, filters.folderKey) {
        val f = filters.folder
        if (f is FolderFilter.In && vault.folders.none { it.id == f.id }) filters.folderKey = null
    }
    // A seleção esvaziou (apagou, moveu, fechou): a confirmação pendente some junto.
    LaunchedEffect(selection.active) {
        if (!selection.active) confirmingTrash = false
    }

    BackHandler(enabled = filters.searching) {
        filters.searching = false
        filters.query = ""
    }
    BackHandler(enabled = selection.active) { selection.clear() }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                if (selection.active) {
                    SelectionTopBar(
                        selection = selection,
                        folders = vault.folders,
                        visibleIds = visible.map { it.id },
                        busy = busy,
                        onMove = onMoveSelected,
                        onExport = onExportSelected,
                        onTrash = { confirmingTrash = true },
                    )
                } else {
                    ListTopBar(filters = filters, busy = busy, menu = menu, onLock = onLock)
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // No modo de seleção o FAB de novo item sai: o foco é o lote, e um toque nele abriria o editor por cima
            // da seleção.
            if (!selection.active) {
                FloatingActionButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_add))
                }
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
                            inSelection = selection.active,
                            batchSelected = selection.contains(item.id),
                            detailSelected = item.id == selectedId,
                            showTotp = rowTotp,
                            totpClock = totpClock,
                            // Fora do modo de seleção o toque abre o item; no modo ele alterna (regra do modo). Com
                            // um lote em andamento (`busy`) a linha não responde: a seleção não pode mudar durante
                            // a gravação que a está usando.
                            onClick = { if (!busy && selection.onTap(item.id)) onOpen(item.id) },
                            onLongClick = { if (!busy) selection.onLongPress(item.id) },
                            onToggleFavorite = { onToggleFavorite(item.id) },
                        )
                    }
                }
                }
            }
        }
    }

    if (confirmingTrash && selection.active) {
        val count = selection.len
        ConfirmDialog(
            title = stringResource(R.string.selection_delete),
            message = pluralStringResource(R.plurals.selection_delete_message, count, count),
            confirmLabel = stringResource(R.string.selection_delete_confirm),
            destructive = true,
            onConfirm = {
                confirmingTrash = false
                onTrashSelected()
            },
            onDismiss = { confirmingTrash = false },
        )
    }
}

/**
 * A barra de topo no modo de seleção: contagem, "selecionar tudo" (os visíveis), mover para pasta (com "sem
 * pasta"), exportar para um cofre novo e apagar. Fechar é a seta/X da esquerda; o back do aparelho faz o mesmo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selection: ListSelectionMode,
    folders: List<Folder>,
    visibleIds: List<String>,
    busy: Boolean,
    onMove: (String?) -> Unit,
    onExport: () -> Unit,
    onTrash: () -> Unit,
) {
    var moveOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { selection.clear() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_close))
            }
        },
        title = {
            Text(
                text = pluralStringResource(R.plurals.selection_count, selection.len, selection.len),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = { selection.selectAll(visibleIds) }, enabled = !busy) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.selection_all))
            }
            Box {
                IconButton(onClick = { moveOpen = true }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.selection_move))
                }
                DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_sem)) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            moveOpen = false
                            onMove(null)
                        },
                    )
                    folders.sortedBy { it.name.lowercase() }.forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = {
                                moveOpen = false
                                onMove(folder.id)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onExport, enabled = !busy) {
                Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.selection_export_vault))
            }
            IconButton(onClick = onTrash, enabled = !busy) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.selection_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_merge_other)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, contentDescription = null) },
                        enabled = !busy,
                        onClick = { pick(menu.onMergeOther) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: Item,
    /** O modo de seleção está ligado: a linha mostra a marca de seleção no lugar do avatar. */
    inSelection: Boolean,
    /** Esta linha está marcada. */
    batchSelected: Boolean,
    /** Esta linha é a que está aberta no painel de detalhe (tablet). */
    detailSelected: Boolean,
    showTotp: Boolean,
    totpClock: State<Long>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val subtitle = remember(item) { VaultFilters.subtitle(item) }
    val hasTotp = VaultFilters.hasTotp(item)
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // O leitor de tela anuncia "selecionado"; a marca visual é o ícone da esquerda.
            .semantics { selected = batchSelected },
        colors = if (batchSelected || detailSelected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            if (inSelection) {
                Icon(
                    imageVector = if (batchSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (batchSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KindAvatar(item.kind)
            }
        },
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
                if (hasTotp && showTotp) RowTotp(item.login?.totp.orEmpty(), totpClock, copyable = !inSelection)
                // No modo de seleção o favorito sai da linha: um toque ali não pode mudar o cofre sem querer.
                if (!inSelection) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(if (item.favorite) R.string.item_unfavorite else R.string.item_favorite),
                            tint = if (item.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

/**
 * Código TOTP vivo na linha; tocar copia — menos no modo de seleção ([copyable] falso), onde o toque pertence à
 * linha (marcar/desmarcar) e não pode copiar o código por engano.
 */
@Composable
private fun RowTotp(secret: String, clock: State<Long>, copyable: Boolean) {
    val code = rememberTotpCode(secret, clock) ?: return
    val context = LocalContext.current
    val label = stringResource(R.string.field_totp_code)
    val tap = if (copyable) {
        Modifier.clickable { copyToClipboard(context, label, code.code, sensitive = true) }
    } else {
        Modifier
    }
    Row(
        modifier = tap.padding(horizontal = 4.dp, vertical = 4.dp),
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
