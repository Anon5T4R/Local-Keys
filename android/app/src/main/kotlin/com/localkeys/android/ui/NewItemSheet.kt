package com.localkeys.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeys.android.R

/** O "+" da lista: escolher o tipo antes de abrir o formulário (ou o item livre, sem formato pronto). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemSheet(onPick: (NewItemPreset) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.new_item_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            NewItemPreset.entries.forEach { preset ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (preset == NewItemPreset.BLANK) Icons.Outlined.PostAdd else kindIcon(preset.kind),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text(presetLabel(preset)) },
                    supportingContent = { Text(stringResource(presetHint(preset))) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(preset) },
                )
            }
        }
    }
}

@Composable
fun presetLabel(preset: NewItemPreset): String =
    if (preset == NewItemPreset.BLANK) stringResource(R.string.new_item_blank) else kindLabel(preset.kind)

private fun presetHint(preset: NewItemPreset): Int = when (preset) {
    NewItemPreset.LOGIN -> R.string.new_item_login_hint
    NewItemPreset.CARD -> R.string.new_item_card_hint
    NewItemPreset.IDENTITY -> R.string.new_item_identity_hint
    NewItemPreset.NOTE -> R.string.new_item_note_hint
    NewItemPreset.BLANK -> R.string.new_item_blank_hint
}
