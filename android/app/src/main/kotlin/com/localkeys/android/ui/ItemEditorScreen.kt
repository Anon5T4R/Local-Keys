package com.localkeys.android.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.forms.CardFormat
import com.localkeys.android.data.forms.Cpf
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.MAX_ATTACHMENT_BYTES
import com.localkeys.android.ffi.FieldLabelsFfi
import com.localkeys.android.ui.theme.FavoriteAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Editor de item em tela cheia: tipo, campos do tipo, gerador de senha, pasta, campos personalizados e anexos.
 * O tipo é escolhido ao criar (chips) e, ao editar, vira um seletor que só converte ao salvar (a regra do núcleo
 * pela ponte, igual ao desktop). Salvar e fechar ficam na barra de cima; sair com alterações pede confirmação.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(
    initial: Item?,
    folders: List<Folder>,
    defaultFolderId: String?,
    busy: Boolean,
    onSave: (Item) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Item novo: o tipo escolhido no "+". */
    preset: NewItemPreset? = null,
) {
    val state = remember(initial?.id, preset) { ItemEditorState(initial, defaultFolderId, preset) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var generatorOpen by remember { mutableStateOf(false) }
    var kindError by remember { mutableStateOf(false) }
    // Os rótulos dos campos que a conversão gera: os MESMOS do formulário, para o campo extra nascer com o nome
    // que a pessoa conhece. O número do cartão tem chave própria ("Card number"), sem a ambiguidade do "Number".
    val kindLabels = FieldLabelsFfi(
        username = stringResource(R.string.field_username),
        password = stringResource(R.string.field_password),
        website = stringResource(R.string.field_uri),
        totp = stringResource(R.string.field_totp),
        cardholder = stringResource(R.string.field_cardholder),
        brand = stringResource(R.string.field_brand),
        cardNumber = stringResource(R.string.field_card_number),
        expiry = stringResource(R.string.field_exp_mask),
        securityCode = stringResource(R.string.field_code),
        firstName = stringResource(R.string.field_first_name),
        lastName = stringResource(R.string.field_last_name),
        email = stringResource(R.string.field_email),
        phone = stringResource(R.string.field_phone),
        address = stringResource(R.string.field_address),
    )

    fun requestClose() {
        if (state.isDirty) confirmDiscard = true else onCancel()
    }
    BackHandler { requestClose() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::requestClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_cancel))
                    }
                },
                title = {
                    Text(
                        stringResource(if (state.isNew) R.string.editor_new_title else R.string.editor_edit_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { state.favorite = !state.favorite }) {
                        Icon(
                            imageVector = if (state.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(if (state.favorite) R.string.item_unfavorite else R.string.item_favorite),
                            tint = if (state.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            when (val save = state.prepareSave(System.currentTimeMillis(), kindLabels)) {
                                is ItemEditorState.Save.Ready -> onSave(save.item)
                                // A conversão recusada não grava nada: o editor fica como está (tipo original e
                                // as edições da pessoa) e o motivo aparece aqui.
                                is ItemEditorState.Save.Refused -> kindError = true
                            }
                        },
                        enabled = state.canSave && !busy,
                    ) {
                        Text(stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.canChangeKind) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // O tipo do item aberto continua no comando dos campos; o seletor só marca o alvo da conversão.
                    KindSelector(selected = state.targetKind ?: state.kind) { state.targetKind = it }
                    if (state.kindChanged) {
                        // Nada some em silêncio: o que o tipo novo não tem vira campo personalizado, os ocultos
                        // continuam ocultos e os campos do tipo novo só existem ao reabrir o item.
                        KindWarning(stringResource(R.string.editor_kind_warning))
                        if (state.totpBecomesHiddenField) {
                            KindWarning(stringResource(R.string.editor_kind_totp_warning))
                        }
                    }
                }
            } else {
                KindSelector(selected = state.kind) { state.kind = it }
            }

            // O aviso de nome vazio só aparece depois que a pessoa mexeu no nome (trocar o tipo não conta).
            var nameTouched by rememberSaveable { mutableStateOf(false) }
            val nameMissing = nameTouched && state.name.isBlank()
            OutlinedTextField(
                value = state.name,
                onValueChange = {
                    state.name = it
                    nameTouched = true
                },
                label = { Text(stringResource(R.string.editor_name_label)) },
                singleLine = true,
                isError = nameMissing,
                supportingText = if (nameMissing) {
                    { Text(stringResource(R.string.editor_name_required)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            FolderDropdown(folders, state.folderId) { state.folderId = it }

            when (state.kind) {
                ItemKind.LOGIN -> LoginFields(state, onOpenGenerator = { generatorOpen = true })
                ItemKind.CARD -> CardFields(state)
                ItemKind.IDENTITY -> IdentityFields(state)
                ItemKind.NOTE -> Unit
            }

            OutlinedTextField(
                value = state.notes,
                onValueChange = { state.notes = it },
                label = { Text(stringResource(if (state.kind == ItemKind.NOTE) R.string.field_note else R.string.field_notes)) },
                minLines = if (state.kind == ItemKind.NOTE) 6 else 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            CustomFieldsEditor(state)
            AttachmentsEditor(state, busy)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (generatorOpen) {
        GeneratorSheet(
            onUse = {
                state.password = it
                generatorOpen = false
            },
            onDismiss = { generatorOpen = false },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onCancel()
                }) { Text(stringResource(R.string.editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            },
        )
    }

    if (kindError) {
        // O motivo sem o diagnóstico cru do núcleo (o `reason` é inglês de log): o que a pessoa precisa saber é
        // que nada foi gravado e que o item continua no tipo original.
        AlertDialog(
            onDismissRequest = { kindError = false },
            text = { Text(stringResource(R.string.tkeys_not_convertible)) },
            confirmButton = {
                TextButton(onClick = { kindError = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

/** Tipo do item: chips que quebram de linha (o texto nunca quebra letra por letra). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun KindSelector(selected: ItemKind, onSelect: (ItemKind) -> Unit) {
    Column {
        Text(
            stringResource(R.string.editor_kind_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemKind.entries.forEach { k ->
                FilterChip(
                    selected = selected == k,
                    onClick = { onSelect(k) },
                    label = { Text(kindLabel(k), maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(kindIcon(k), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
    }
}

/** Uma frase do aviso da troca de tipo (a cor de erro do tema, legível nos dois temas). */
@Composable
private fun KindWarning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDropdown(folders: List<Folder>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.folder_sem)
    val current = folders.firstOrNull { it.id == selected }?.name ?: none
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.field_folder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = { onSelect(null); expanded = false })
            folders.sortedBy { it.name.lowercase() }.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(folder.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun LoginFields(state: ItemEditorState, onOpenGenerator: () -> Unit) {
    EditorField(state.username, { state.username = it }, stringResource(R.string.field_username), KeyboardType.Email)
    Column {
        PasswordField(
            value = state.password,
            onValueChange = { state.password = it },
            label = stringResource(R.string.field_password),
            extraActions = {
                IconButton(onClick = onOpenGenerator) {
                    Icon(Icons.Outlined.Password, contentDescription = stringResource(R.string.editor_generate))
                }
            },
        )
        if (state.password.isNotEmpty()) StrengthMeter(state.password, Modifier.padding(top = 6.dp))
    }
    state.uris.forEachIndexed { index, uri ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uri,
                onValueChange = { state.uris[index] = it },
                label = { Text(stringResource(R.string.field_uri)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            if (state.uris.size > 1) {
                IconButton(onClick = { state.uris.removeAt(index) }) {
                    Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.editor_remove_site))
                }
            }
        }
    }
    TextButton(onClick = { state.uris.add("") }) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.editor_add_site))
    }
    TotpEditorField(state)
}

@Composable
private fun CardFields(state: ItemEditorState) {
    EditorField(state.cardholder, { state.cardholder = it }, stringResource(R.string.field_cardholder), capitalize = KeyboardCapitalization.Words)
    // Número: teclado numérico, grupos de 4 na tela (só dígitos no cofre), bandeira automática e o aviso do
    // Luhn, que não impede salvar (cartão de teste, número antigo...).
    val luhnWarning = CardFormat.luhnWarning(state.number)
    OutlinedTextField(
        value = state.number,
        onValueChange = state::updateNumber,
        label = { Text(stringResource(R.string.field_number)) },
        singleLine = true,
        isError = luhnWarning,
        supportingText = if (luhnWarning) {
            { Text(stringResource(R.string.card_luhn_warning)) }
        } else {
            null
        },
        visualTransformation = remember { CardNumberTransformation() },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    EditorField(
        state.brand,
        state::updateBrand,
        stringResource(R.string.field_brand),
        capitalize = KeyboardCapitalization.Words,
        supporting = stringResource(R.string.card_brand_hint),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            FormattingField(
                value = state.exp,
                onValueChange = state::updateExp,
                format = CardFormat::expiry,
                label = stringResource(R.string.field_exp_mask),
                keyboardType = KeyboardType.Number,
            )
        }
        Column(Modifier.weight(1f)) {
            PasswordField(
                value = state.code,
                onValueChange = state::updateCode,
                label = stringResource(R.string.field_code),
                keyboardType = KeyboardType.NumberPassword,
            )
        }
    }
}

@Composable
private fun IdentityFields(state: ItemEditorState) {
    EditorField(state.firstName, { state.firstName = it }, stringResource(R.string.field_first_name), capitalize = KeyboardCapitalization.Words)
    EditorField(state.lastName, { state.lastName = it }, stringResource(R.string.field_last_name), capitalize = KeyboardCapitalization.Words)
    EditorField(state.email, { state.email = it }, stringResource(R.string.field_email), KeyboardType.Email)
    EditorField(state.phone, { state.phone = it }, stringResource(R.string.field_phone), KeyboardType.Phone)
    OutlinedTextField(
        value = state.address,
        onValueChange = { state.address = it },
        label = { Text(stringResource(R.string.field_address)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    DocumentShortcuts(onAdd = state::addNamedField)
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalize: KeyboardCapitalization = KeyboardCapitalization.None,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalize),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        content()
    }
}

@Composable
private fun CustomFieldsEditor(state: ItemEditorState) {
    EditorSection(stringResource(R.string.custom_fields)) {
        state.customFields.forEachIndexed { index, field ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = field.name,
                        onValueChange = { v -> state.customFields[index] = field.copy(name = v) },
                        label = { Text(stringResource(R.string.custom_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (field.hidden) {
                        PasswordField(
                            value = field.value,
                            onValueChange = { v -> state.customFields[index] = field.copy(value = v) },
                            label = stringResource(R.string.custom_field_value),
                        )
                    } else if (Cpf.isCpfField(field.name)) {
                        // CPF: formata dígitos enquanto digita; outro formato colado fica como está. Aviso sem bloquear.
                        FormattingField(
                            value = field.value,
                            onValueChange = { v -> state.customFields[index] = field.copy(value = v) },
                            format = Cpf::format,
                            label = stringResource(R.string.custom_field_value),
                            keyboardType = KeyboardType.Number,
                            warning = if (Cpf.warning(field.value)) stringResource(R.string.cpf_warning) else null,
                        )
                    } else {
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { v -> state.customFields[index] = field.copy(value = v) },
                            label = { Text(stringResource(R.string.custom_field_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = VisualTransformation.None,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = field.hidden,
                            onCheckedChange = { checked -> state.customFields[index] = field.copy(hidden = checked) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.custom_field_hidden),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { state.customFields.removeAt(index) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.custom_field_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = state::addField) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.custom_field_add))
        }
    }
}

@Composable
private fun AttachmentsEditor(state: ItemEditorState, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tooLarge = stringResource(R.string.attach_too_large)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            when (read) {
                AttachmentRead.TooLarge -> Toast.makeText(context, tooLarge, Toast.LENGTH_LONG).show()
                AttachmentRead.Failed -> Toast.makeText(context, R.string.attach_read_failed, Toast.LENGTH_SHORT).show()
                is AttachmentRead.Ok -> state.attachments += read.attachment
            }
        }
    }
    EditorSection(stringResource(R.string.attach_title)) {
        state.attachments.filter { it.live }.forEach { att ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                InfoRow(icon = Icons.Outlined.InsertDriveFile, title = att.name, subtitle = formatSize(att.size)) {
                    IconButton(onClick = { state.trashAttachment(att.id) }, enabled = !busy) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.attach_trash_named, att.name),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.attach_add))
        }
        Text(
            stringResource(R.string.attach_limit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface AttachmentRead {
    data class Ok(val attachment: Attachment) : AttachmentRead
    data object TooLarge : AttachmentRead
    data object Failed : AttachmentRead
}

/** Lê o arquivo escolhido. Confere o tamanho antes (pelo provedor) e de novo depois de ler. */
private fun readAttachment(context: android.content.Context, uri: Uri): AttachmentRead = try {
    val resolver = context.contentResolver
    var name = context.getString(R.string.attach_default_name)
    var declared = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) declared = c.getLong(1)
        }
    }
    if (declared > MAX_ATTACHMENT_BYTES) {
        AttachmentRead.TooLarge
    } else {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ATTACHMENT_BYTES) return AttachmentRead.TooLarge
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        if (bytes == null || bytes.isEmpty()) {
            AttachmentRead.Failed
        } else {
            AttachmentRead.Ok(
                Attachment(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    size = bytes.size.toLong(),
                    mime = resolver.getType(uri) ?: "application/octet-stream",
                    dataB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ),
            )
        }
    }
} catch (_: Exception) {
    AttachmentRead.Failed
}
