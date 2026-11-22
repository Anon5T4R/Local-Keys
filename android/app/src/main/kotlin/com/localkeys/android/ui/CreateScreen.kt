package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.generator.PasswordStrength

/** Mínimo da senha-mestra. */
private const val MIN_MASTER = 8

@Composable
fun CreateScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != password
    val tooShort = password.isNotEmpty() && password.length < MIN_MASTER
    val canCreate = !busy && password.length >= MIN_MASTER && confirm == password

    StartLayout(modifier) {
        Text(
            text = stringResource(R.string.create_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.create_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.create_password),
            enabled = !busy,
            isError = tooShort,
            supportingText = if (tooShort) {
                { Text(stringResource(R.string.create_password_short)) }
            } else {
                null
            },
            imeAction = ImeAction.Next,
        )
        StrengthMeter(password, Modifier.padding(top = 4.dp, bottom = 12.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.create_confirm),
            enabled = !busy,
            isError = mismatch,
            supportingText = if (mismatch) {
                { Text(stringResource(R.string.create_mismatch)) }
            } else {
                null
            },
            onImeAction = { if (canCreate) onCreate(password) },
        )
        Spacer(Modifier.height(16.dp))

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { onCreate(password) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = canCreate,
        ) {
            Text(stringResource(R.string.create_button))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, enabled = !busy) {
            Text(stringResource(R.string.create_back))
        }
    }
}

/** Barra de força (estimativa local) com o rótulo "Fraca/Razoável/Boa/Forte/Muito forte". */
@Composable
fun StrengthMeter(password: String, modifier: Modifier = Modifier) {
    val result = remember(password) { PasswordStrength.estimate(password) }
    val labels = listOf(
        R.string.strength_0,
        R.string.strength_1,
        R.string.strength_2,
        R.string.strength_3,
        R.string.strength_4,
    )
    val color = when (result.score) {
        0 -> MaterialTheme.colorScheme.error
        1 -> Color(0xFFE0730B)
        2 -> Color(0xFFC9A100)
        3 -> Color(0xFF3B9B4A)
        else -> Color(0xFF1E8E3E)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { if (password.isEmpty()) 0f else (result.score + 1) / 5f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
        if (password.isNotEmpty()) {
            Row {
                Text(
                    text = stringResource(R.string.strength_label, stringResource(labels[result.score])),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
