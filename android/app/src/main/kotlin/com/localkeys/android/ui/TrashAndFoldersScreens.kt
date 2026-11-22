package com.localkeys.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item

/** Barra de cima das telas secundárias: seta de voltar e título. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
            }
        },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * Lixeira de itens (a do desktop): o que foi excluído fica aqui com a data, para restaurar ou excluir de vez.
 * O oficial não tem "esvaziar lixeira" e o desktop também não; cada item sai um por um.
 */
@Composable
fun TrashScreen(
    items: List<Item>,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
) {
    var confirm by remember { mutableStateOf<Item?>(null) }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.trash_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.trash_empty_title),
                body = stringResource(R.string.trash_empty),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.trash_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                )
            }
            items(items, key = { it.id }) { item ->
                ListItem(
                    leadingContent = { KindAvatar(item.kind) },
                    headlineContent = {
                        Text(item.name.ifBlank { stringResource(R.string.item_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.trash_deleted_at, formatDate(item.deletedAt ?: 0)), maxLines = 1)
                    },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { onRestore(item.id) }, enabled = !busy) {
                                Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.trash_restore_named, item.name))
                            }
                            IconButton(onClick = { confirm = item }, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    stringResource(R.string.trash_delete_forever_named, item.name),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
    confirm?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever_title),
            message = stringResource(R.string.trash_delete_forever_message, item.name),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            destructive = true,
            onConfirm = {
                confirm = null
                onDeleteForever(item.id)
            },
            onDismiss = { confirm = null },
        )
    }
}

/** Pastas: criar, renomear e excluir (os itens da pasta excluída ficam "Sem pasta"). */
@Composable
fun FoldersScreen(
    folders: List<Folder>,
    items: List<Item>,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }
    val counts = remember(items) { items.filter { !it.inTrash }.groupingBy { it.folderId }.eachCount() }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.folders_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                text = { Text(stringResource(R.string.folder_new)) },
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.folder_none_title),
                body = stringResource(R.string.folder_none),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(folders.sortedBy { it.name.lowercase() }, key = { it.id }) { folder ->
                val n = counts[folder.id] ?: 0
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(if (n == 0) stringResource(R.string.folder_empty) else pluralStringResource(R.plurals.folder_count, n, n))
                    },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { renaming = folder }, enabled = !busy) {
                                Icon(Icons.Outlined.Edit, stringResource(R.string.folder_rename_named, folder.name))
                            }
                            IconButton(onClick = { deleting = folder }, enabled = !busy) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.folder_delete_named, folder.name))
                            }
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        FolderNameDialog(
            title = stringResource(R.string.folder_new),
            initial = "",
            onConfirm = {
                creating = false
                onAdd(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.folder_rename),
            initial = folder.name,
            onConfirm = {
                renaming = null
                onRename(folder.id, it)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.folder_delete),
            message = stringResource(R.string.folder_delete_message, folder.name),
            confirmLabel = stringResource(R.string.folder_delete),
            destructive = true,
            onConfirm = {
                deleting = null
                onDelete(folder.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun FolderNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // O nome atual já vem selecionado: digitar substitui, e o teclado abre sozinho.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim()) }, enabled = name.text.isNotBlank()) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}
