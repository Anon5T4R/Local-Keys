package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.forms.CardFormat

/**
 * Mostra o número do cartão (só dígitos no estado) em grupos de 4 — Amex 4-6-5 — sem tocar no que é guardado.
 * O cursor anda nos dígitos; os espaços são só desenho.
 */
class CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val formatted = CardFormat.group(digits)
        // Fronteiras dos grupos (em dígitos): onde entra um espaço antes do próximo dígito.
        val bounds = CardFormat.groups(digits).runningReduce { a, b -> a + b }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + bounds.count { it < offset }
            override fun transformedToOriginal(offset: Int): Int {
                var digitsSeen = 0
                for (i in 0 until minOf(offset, formatted.length)) if (formatted[i] != ' ') digitsSeen++
                return digitsSeen.coerceAtMost(digits.length)
            }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/**
 * Campo de texto que se formata enquanto a pessoa digita (validade MM/AA, CPF). [format] recebe o texto novo e
 * devolve o formatado; se mudou, o cursor vai para o fim (quem digita está no fim).
 */
@Composable
fun FormattingField(
    value: String,
    onValueChange: (String) -> Unit,
    format: (String) -> String,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    OutlinedTextField(
        value = TextFieldValue(value, TextRange(value.length)),
        onValueChange = { tfv -> onValueChange(format(tfv.text)) },
        label = { Text(label) },
        singleLine = true,
        isError = warning != null,
        supportingText = warning?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Atalhos de documento da identidade: cada um cria um campo personalizado com o nome já posto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentShortcuts(onAdd: (String) -> Unit) {
    val shortcuts = listOf(
        stringResource(R.string.doc_cpf),
        stringResource(R.string.doc_rg),
        stringResource(R.string.doc_cnh),
        stringResource(R.string.doc_birth),
        stringResource(R.string.doc_passport),
    )
    Column {
        Text(
            stringResource(R.string.doc_shortcuts_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.forEach { name -> ShortcutChip(name) { onAdd(name) } }
            // "Documento (outro)": o nome fica vazio para a pessoa escrever.
            ShortcutChip(stringResource(R.string.doc_other)) { onAdd("") }
        }
    }
}

@Composable
private fun ShortcutChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, softWrap = false) },
        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}
