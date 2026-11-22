package com.localkeys.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.localkeys.android.R
import com.localkeys.android.data.vault.Vault

/** Largura a partir da qual a lista e o detalhe ficam lado a lado (tablet, celular deitado grande). */
private const val TWO_PANE_MIN_DP = 720

/** Fração da largura da lista no modo de dois painéis. */
private const val LIST_FRACTION = 0.4f

private val RouteStackSaver = Saver<List<Route>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map { it.encode() }) },
    restore = { saved -> saved.mapNotNull { Route.decode(it) } },
)

/** Ganchos da Activity: SAF (importar/exportar), biometria e o Credential Exchange. */
data class HostCallbacks(
    val onPickImport: () -> Unit,
    val onExport: (String) -> Unit,
    val onRequestBiometric: (BiometricRequest) -> Unit,
    /** null = aparelho sem Credential Exchange (Android < 14). */
    val onImportOther: (() -> Unit)?,
)

/**
 * O cofre aberto: a lista e as telas por cima dela. No celular é uma pilha (lista → detalhe → editor); com
 * [TWO_PANE_MIN_DP] ou mais, lista (40%) e detalhe/editor (60%) lado a lado, e as telas de configuração,
 * lixeira e pastas ocupam tudo. A pilha sobrevive a rotação.
 */
@Composable
fun VaultHost(
    viewModel: VaultViewModel,
    state: VaultViewModel.UiState,
    vault: Vault,
    callbacks: HostCallbacks,
) {
    var stack by rememberSaveable(stateSaver = RouteStackSaver) { mutableStateOf(emptyList<Route>()) }
    val twoPane = LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_DP
    val filters = rememberListFilterState()
    val snackbar = remember { SnackbarHostState() }
    val totpClock = rememberTotpClock()
    val holder = rememberSaveableStateHolder()
    var exportOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        snackbar.showSnackbar(notice)
    }
    // Erro fora do diálogo de import: vira snackbar (no diálogo, aparece dentro dele).
    LaunchedEffect(state.error, state.pendingImport) {
        val error = state.error ?: return@LaunchedEffect
        if (state.pendingImport != null) return@LaunchedEffect
        viewModel.clearError()
        snackbar.showSnackbar(error)
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    fun push(route: Route) {
        stack = stack + route
    }
    fun pop() {
        stack = stack.dropLast(1)
    }

    val menu = VaultMenuActions(
        onImportFile = callbacks.onPickImport,
        onImportOther = callbacks.onImportOther,
        onExport = { exportOpen = true },
        onFolders = { push(Route.Folders) },
        onTrash = { push(Route.Trash) },
        onSettings = { push(Route.Settings) },
    )

    @Composable
    fun ListPane(selectedId: String?, modifier: Modifier = Modifier) {
        holder.SaveableStateProvider("list") {
            VaultListPane(
                vault = vault,
                filters = filters,
                selectedId = selectedId,
                busy = state.busy,
                totpClock = totpClock,
                snackbarHostState = snackbar,
                menu = menu,
                onOpen = { id -> stack = NavStack.openItem(stack, id, twoPane) },
                onNew = { push(Route.Edit(null)) },
                onToggleFavorite = viewModel::toggleFavorite,
                onLock = viewModel::lock,
                modifier = modifier,
            )
        }
    }

    @Composable
    fun PaneContent(route: Route, inPane: Boolean) {
        when (route) {
            is Route.Detail -> {
                val item = vault.items.firstOrNull { it.id == route.id && !it.inTrash }
                ItemDetailScreen(
                    item = item,
                    folders = vault.folders,
                    busy = state.busy,
                    totpClock = totpClock,
                    snackbarHostState = if (inPane) null else snackbar,
                    onBack = if (inPane) null else ::pop,
                    actions = DetailActions(
                        onEdit = { push(Route.Edit(route.id)) },
                        onToggleFavorite = { viewModel.toggleFavorite(route.id) },
                        onTrash = {
                            viewModel.deleteItem(route.id)
                            stack = NavStack.removeItem(stack, route.id)
                        },
                        onTrashAttachment = { viewModel.trashAttachment(route.id, it) },
                        onRestoreAttachment = { viewModel.restoreAttachment(route.id, it) },
                        onDeleteAttachmentForever = { viewModel.deleteAttachmentForever(route.id, it) },
                    ),
                )
            }
            is Route.Edit -> {
                val initial = route.id?.let { id -> vault.items.firstOrNull { it.id == id } }
                ItemEditorScreen(
                    initial = initial,
                    folders = vault.folders,
                    defaultFolderId = (filters.folder as? FolderFilter.In)?.id,
                    busy = state.busy,
                    onSave = { item ->
                        viewModel.saveItem(item)
                        stack = NavStack.afterSave(stack, item.id)
                    },
                    onCancel = ::pop,
                )
            }
            else -> Unit
        }
    }

    @Composable
    fun FullScreen(route: Route) {
        when (route) {
            Route.Trash -> TrashScreen(
                items = remember(vault.items) { vault.items.filter { it.inTrash } },
                busy = state.busy,
                snackbarHostState = snackbar,
                onBack = ::pop,
                onRestore = viewModel::restoreItem,
                onDeleteForever = viewModel::deleteItemForever,
            )
            Route.Folders -> FoldersScreen(
                folders = vault.folders,
                items = vault.items,
                busy = state.busy,
                snackbarHostState = snackbar,
                onBack = ::pop,
                onAdd = viewModel::addFolder,
                onRename = viewModel::renameFolder,
                onDelete = viewModel::deleteFolder,
            )
            Route.Settings -> SettingsScreen(
                biometricOn = state.biometricAvailable,
                vaultName = state.vaultName,
                themeMode = state.themeMode,
                onThemeMode = viewModel::setThemeMode,
                busy = state.busy,
                snackbarHostState = snackbar,
                onBack = ::pop,
                onEnableBiometric = { callbacks.onRequestBiometric(BiometricRequest.Wrap) },
                onDisableBiometric = viewModel::disableBiometric,
                onImportFile = callbacks.onPickImport,
                onImportOther = callbacks.onImportOther,
                onExport = { exportOpen = true },
            )
            else -> PaneContent(route, inPane = false)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val top = stack.lastOrNull()
        val full = NavStack.fullScreenRoute(stack)
        when {
            twoPane && full == null -> {
                val pane = NavStack.paneRoute(stack)
                Row(Modifier.fillMaxSize()) {
                    ListPane(
                        selectedId = (pane as? Route.Detail)?.id,
                        modifier = Modifier.fillMaxHeight().weight(LIST_FRACTION),
                    )
                    VerticalDivider()
                    Box(Modifier.fillMaxHeight().weight(1f - LIST_FRACTION)) {
                        if (pane != null) {
                            PaneContent(pane, inPane = true)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    icon = Icons.Outlined.Key,
                                    title = stringResource(R.string.pane_empty_title),
                                    body = stringResource(R.string.pane_empty),
                                )
                            }
                        }
                    }
                }
            }
            top == null -> ListPane(selectedId = null)
            else -> FullScreen(top)
        }
    }

    state.pendingImport?.let { pending ->
        ImportPasswordDialog(
            pending = pending,
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::onImportPassword,
            onDismiss = viewModel::dismissImport,
        )
    }
    if (exportOpen) {
        ExportDialog(
            onPick = { format ->
                exportOpen = false
                callbacks.onExport(format)
            },
            onDismiss = { exportOpen = false },
        )
    }
    if (state.externalChange) {
        ExternalChangeDialog(
            onReload = viewModel::reloadFromDisk,
            onOverwrite = viewModel::forceSave,
            onDismiss = viewModel::dismissExternalChange,
        )
    }
}
