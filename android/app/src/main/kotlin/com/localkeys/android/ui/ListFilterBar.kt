package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.vault.Folder

/**
 * Topo da lista: o seletor de pasta (o mais usado, sempre à vista) e o botão "Filtrar (n)", que abre o painel com
 * todos os filtros. Com algum filtro ligado, uma fileira de chips removíveis mostra quais são. Nada rola na
 * horizontal: no celular estreito a fileira antiga escondia a maior parte dos filtros.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ListFilterBar(filters: ListFilterState, folders: List<Folder>) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val filter = filters.filter
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
        // A pasta fica com todo o espaço que o botão de filtro deixa (nome longo vira reticências só no limite).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { FolderSelector(filters, folders) }
            val count = filter.activeCount
            FilterChip(
                selected = count > 0,
                onClick = { sheetOpen = true },
                label = {
                    Text(
                        if (count > 0) stringResource(R.string.filter_button_count, count) else stringResource(R.string.filter_button),
                        maxLines = 1,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        if (filter.activeCount > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filter.kind != KindFilter.ALL) {
                    ActiveChip(kindFilterLabel(filter.kind)) { filters.filter = filter.copy(kind = KindFilter.ALL) }
                }
                ShowOnly.entries.filter { it in filter.only }.forEach { only ->
                    ActiveChip(showOnlyLabel(only)) { filters.filter = filter.copy(only = filter.only - only) }
                }
            }
        }
    }
    if (sheetOpen) {
        FilterSheet(
            filter = filter,
            onChange = { filters.filter = it },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Chip de um filtro ligado; tocar desliga. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveChip(label: String, onRemove: () -> Unit) {
    val remove = stringResource(R.string.filter_remove, label)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = remove, modifier = Modifier.size(InputChipDefaults.IconSize)) },
    )
}

/** O painel de filtros: tipo (um só) e "mostrar só" (vários, somados com E). As mudanças valem na hora. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(filter: ListFilter, onChange: (ListFilter) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.filter_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.filter_section_type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindFilter.entries.forEach { kind ->
                    val selected = filter.kind == kind
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(filter.copy(kind = kind)) },
                        label = { Text(kindFilterLabel(kind), maxLines = 1) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.filter_section_only),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShowOnly.entries.forEach { only ->
                    val selected = only in filter.only
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(filter.copy(only = if (selected) filter.only - only else filter.only + only)) },
                        label = { Text(showOnlyLabel(only), maxLines = 1) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(ListFilter.NONE) }, enabled = filter.activeCount > 0) {
                    Text(stringResource(R.string.filter_clear))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text(stringResource(R.string.filter_done)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSelector(filters: ListFilterState, folders: List<Folder>) {
    var open by remember { mutableStateOf(false) }
    val current = when (val f = filters.folder) {
        FolderFilter.All -> stringResource(R.string.folder_filter_all_folders)
        FolderFilter.NoFolder -> stringResource(R.string.folder_sem)
        is FolderFilter.In -> folders.firstOrNull { it.id == f.id }?.name ?: stringResource(R.string.folder_filter_all_folders)
    }
    Box {
        FilterChip(
            selected = filters.folder != FolderFilter.All,
            onClick = { open = true },
            label = { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = FolderLabelMax)) },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_filter_all_folders)) },
                onClick = { filters.folderKey = FolderFilter.All.key; open = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_sem)) },
                onClick = { filters.folderKey = FolderFilter.NoFolder.key; open = false },
            )
            if (folders.isNotEmpty()) HorizontalDivider()
            folders.sortedBy { it.name.lowercase() }.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { filters.folderKey = folder.id; open = false },
                )
            }
        }
    }
}

/** Nome de pasta longo não estica o chip além disto (fica com reticências). */
private val FolderLabelMax = 180.dp

@Composable
fun kindFilterLabel(kind: KindFilter): String = stringResource(
    when (kind) {
        KindFilter.ALL -> R.string.filter_all
        KindFilter.LOGINS -> R.string.filter_logins
        KindFilter.NOTES -> R.string.filter_notes
        KindFilter.CARDS -> R.string.filter_cards
        KindFilter.IDENTITIES -> R.string.filter_identities
    },
)

@Composable
fun showOnlyLabel(only: ShowOnly): String = stringResource(
    when (only) {
        ShowOnly.FAVORITES -> R.string.filter_favorites
        ShowOnly.WITH_TOTP -> R.string.filter_totp
        ShowOnly.WITH_ATTACHMENTS -> R.string.filter_attachments
        ShowOnly.WITH_PASSKEY -> R.string.filter_passkey
    },
)
