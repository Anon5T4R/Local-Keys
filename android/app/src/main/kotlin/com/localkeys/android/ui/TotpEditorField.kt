package com.localkeys.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.localkeys.android.R
import com.localkeys.android.data.totp.OtpAuth

/**
 * Campo da chave TOTP do login: digitar, "Ler QR" (câmera, sem Play Services) ou "Colar". QR e área de
 * transferência aceitam `otpauth://totp/…` ou a chave crua; o cofre guarda só a chave base32 (o desktop gera
 * o código dela). Com chave válida, mostra o código atual; inválida, diz o porquê.
 */
@Composable
fun TotpEditorField(state: ItemEditorState) {
    val context = LocalContext.current

    fun apply(raw: String?) {
        if (raw.isNullOrBlank()) return
        when (val r = OtpAuth.parse(raw)) {
            is OtpAuth.Result.Ok -> {
                state.applyTotp(r.value)
                r.value.unsupported?.let { u ->
                    val what = listOfNotNull(
                        u.digits?.let { context.getString(R.string.totp_digits, it) },
                        u.period?.let { context.getString(R.string.totp_period, it) },
                        u.algorithm,
                    ).joinToString(", ")
                    Toast.makeText(context, context.getString(R.string.totp_unsupported, what), Toast.LENGTH_LONG).show()
                }
            }
            is OtpAuth.Result.Invalid -> Toast.makeText(context, reasonText(context, r.reason), Toast.LENGTH_LONG).show()
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> apply(result.contents) }
    fun launchScan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(context.getString(R.string.totp_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScan()
        } else {
            Toast.makeText(context, R.string.totp_camera_denied, Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val parsed = remember(state.totp) { state.totp.takeIf { it.isNotBlank() }?.let(OtpAuth::parse) }
        OutlinedTextField(
            value = state.totp,
            onValueChange = { state.totp = it },
            label = { Text(stringResource(R.string.field_totp)) },
            singleLine = true,
            isError = parsed is OtpAuth.Result.Invalid,
            supportingText = {
                when (parsed) {
                    null -> Text(stringResource(R.string.editor_totp_hint))
                    is OtpAuth.Result.Invalid -> Text(reasonText(context, parsed.reason))
                    is OtpAuth.Result.Ok -> TotpPreview(parsed.value.secret)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) launchScan() else permission.launch(Manifest.permission.CAMERA)
            }) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.totp_scan))
            }
            OutlinedButton(onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) {
                    Toast.makeText(context, R.string.totp_clipboard_empty, Toast.LENGTH_SHORT).show()
                } else {
                    apply(text)
                }
            }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.totp_paste))
            }
        }
    }
}

/** O código atual da chave, com o anel do tempo (o mesmo relógio da lista). */
@Composable
private fun TotpPreview(secret: String) {
    val clock = rememberTotpClock()
    val code = rememberTotpCode(secret, clock)
    if (code == null) {
        Text(stringResource(R.string.totp_invalid_key))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.totp_preview, code.code.chunked(3).joinToString(" ")),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        TotpRing(code, size = 24.dp)
    }
}

private fun reasonText(context: Context, reason: OtpAuth.Reason): String = context.getString(
    when (reason) {
        OtpAuth.Reason.EMPTY -> R.string.totp_clipboard_empty
        OtpAuth.Reason.NOT_TOTP -> R.string.totp_not_totp
        OtpAuth.Reason.NO_SECRET -> R.string.totp_no_secret
        OtpAuth.Reason.BAD_KEY -> R.string.totp_invalid_key
    },
)
