package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.generator.PasswordGenerator

/** Gerador (senha aleatória ou frase-senha) numa folha de baixo; "Usar" devolve o valor para o editor. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeneratorSheet(onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var passphrase by rememberSaveable { mutableStateOf(false) }
    var length by rememberSaveable { mutableIntStateOf(20) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var words by rememberSaveable { mutableIntStateOf(5) }
    var separator by rememberSaveable { mutableStateOf("-") }
    var capitalize by rememberSaveable { mutableStateOf(false) }
    var number by rememberSaveable { mutableStateOf(true) }
    var seed by rememberSaveable { mutableIntStateOf(0) }

    // Gera de novo a cada mudança de opção (ou no "gerar outra", que muda o seed).
    val value = androidx.compose.runtime.remember(passphrase, length, lower, upper, digits, symbols, words, separator, capitalize, number, seed) {
        if (passphrase) {
            PasswordGenerator.passphrase(PasswordGenerator.PassphraseOptions(words, separator, capitalize, number))
        } else {
            val anyClass = lower || upper || digits || symbols
            PasswordGenerator.generate(
                PasswordGenerator.Options(length, lower || !anyClass, upper, digits, symbols),
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.gen_title), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !passphrase,
                    onClick = { passphrase = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.gen_mode_password), maxLines = 1) }
                SegmentedButton(
                    selected = passphrase,
                    onClick = { passphrase = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.gen_mode_passphrase), maxLines = 1) }
            }

            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { seed++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.gen_again))
                    }
                    IconButton(onClick = { copyToClipboard(context, context.getString(R.string.field_password), value, sensitive = true) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.detail_copy_named, stringResource(R.string.field_password)))
                    }
                }
            }
            StrengthMeter(value)

            if (passphrase) {
                LabeledSlider(stringResource(R.string.gen_words, words), words, 3..12) { words = it }
                Text(stringResource(R.string.gen_separator), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("-" to "-", "." to ".", "_" to "_", " " to stringResource(R.string.gen_separator_space)).forEach { (sep, label) ->
                        FilterChip(selected = separator == sep, onClick = { separator = sep }, label = { Text(label) })
                    }
                }
                SwitchRow(stringResource(R.string.gen_capitalize), capitalize) { capitalize = it }
                SwitchRow(stringResource(R.string.gen_number), number) { number = it }
            } else {
                LabeledSlider(stringResource(R.string.gen_length, length), length, 8..64) { length = it }
                SwitchRow(stringResource(R.string.gen_lower), lower) { lower = it }
                SwitchRow(stringResource(R.string.gen_upper), upper) { upper = it }
                SwitchRow(stringResource(R.string.gen_digits), digits) { digits = it }
                SwitchRow(stringResource(R.string.gen_symbols), symbols) { symbols = it }
            }

            Button(onClick = { onUse(value) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.gen_use))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 12) range.last - range.first - 1 else 0,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
