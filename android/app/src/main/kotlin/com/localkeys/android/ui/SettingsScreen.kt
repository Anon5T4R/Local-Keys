package com.localkeys.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.localkeys.android.BuildConfig
import com.localkeys.android.R
import com.localkeys.android.data.store.RecentVault
import com.localkeys.android.credentials.LocalKeysCredentialService

/**
 * Configurações: biometria, o Local Keys como padrão do sistema (preenchimento e, no Android 14+, senhas e
 * passkeys), importar/exportar e a versão. Os estados do sistema são relidos a cada volta para a tela.
 */
@Composable
fun SettingsScreen(
    biometricOn: Boolean,
    vaultName: String?,
    vaultUri: String?,
    recentVaults: List<RecentVault>,
    vaultActions: VaultSwitchActions,
    /** Levar o cofre para uma pasta sincronizada (SAF criar documento). */
    onMoveVault: () -> Unit,
    /** Sincronizar com outra cópia (SAF abrir). */
    onSyncOther: () -> Unit,
    themeMode: String,
    onThemeMode: (String) -> Unit,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onImportFile: () -> Unit,
    onImportOther: (() -> Unit)?,
    onExport: () -> Unit,
    copyTotpAfterAutofill: Boolean,
    onCopyTotpAfterAutofill: (Boolean) -> Unit,
    updateState: UpdateViewModel.UiState,
    updateActions: UpdateActions,
) {
    val context = LocalContext.current
    // Muda a cada ON_RESUME: o usuário volta das telas do sistema e o status é relido.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val autofillOn = remember(resumes) { autofillEnabled(context) }
    val autofillSupported = remember(resumes) { autofillSupported(context) }
    val credentialsOn = remember(resumes) { credentialProviderEnabled(context) }
    val credentialsPreferred = remember(resumes) { credentialProviderPreferred(context) }
    val biometricHardware = remember(resumes) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            VaultSection(vaultName, vaultUri, recentVaults, busy, vaultActions)

            SectionTitle(stringResource(R.string.sync_section))
            SettingsAction(Icons.Outlined.DriveFileMove, stringResource(R.string.sync_move), stringResource(R.string.sync_move_body), !busy, onMoveVault)
            SettingsAction(Icons.Outlined.Sync, stringResource(R.string.sync_other), stringResource(R.string.sync_other_body), !busy, onSyncOther)

            AppearanceSection(themeMode, onThemeMode)

            SectionTitle(stringResource(R.string.settings_security))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.biometric_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            when {
                                !biometricHardware && !biometricOn -> R.string.biometric_no_hardware
                                biometricOn -> R.string.biometric_active
                                else -> R.string.biometric_hint
                            },
                        ),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = biometricOn,
                        enabled = !busy && (biometricHardware || biometricOn),
                        onCheckedChange = { on -> if (on) onEnableBiometric() else onDisableBiometric() },
                    )
                },
                modifier = Modifier.clickable(enabled = !busy && (biometricHardware || biometricOn)) {
                    if (biometricOn) onDisableBiometric() else onEnableBiometric()
                },
            )

            SectionTitle(stringResource(R.string.settings_default))
            Text(
                stringResource(R.string.settings_default_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Gutter),
            )
            if (autofillSupported) {
                DefaultServiceCard(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.settings_autofill),
                    body = stringResource(R.string.settings_autofill_body),
                    active = autofillOn,
                    onOpen = { openAutofillSettings(context) },
                )
            }
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Pin, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_copy_totp)) },
                supportingContent = { Text(stringResource(R.string.settings_copy_totp_body)) },
                trailingContent = {
                    Switch(checked = copyTotpAfterAutofill, onCheckedChange = onCopyTotpAfterAutofill)
                },
                modifier = Modifier.clickable { onCopyTotpAfterAutofill(!copyTotpAfterAutofill) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                DefaultServiceCard(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.settings_credentials),
                    body = stringResource(R.string.settings_credentials_body),
                    active = credentialsOn,
                    detail = when (credentialsPreferred) {
                        true -> stringResource(R.string.settings_credentials_preferred_yes)
                        false -> stringResource(R.string.settings_credentials_preferred_no)
                        null -> null
                    },
                    onOpen = { openCredentialSettings(context) },
                )
            }

            SectionTitle(stringResource(R.string.settings_import_export))
            SettingsAction(Icons.Outlined.FileDownload, stringResource(R.string.menu_import_file), stringResource(R.string.settings_import_file_body), !busy, onImportFile)
            if (onImportOther != null) {
                SettingsAction(
                    Icons.Outlined.SyncAlt,
                    stringResource(R.string.menu_import_other),
                    stringResource(R.string.settings_import_other_body),
                    !busy,
                    onImportOther,
                    note = stringResource(R.string.settings_import_other_note),
                )
            }
            SettingsAction(Icons.Outlined.FileUpload, stringResource(R.string.menu_export), stringResource(R.string.settings_export_body), !busy, onExport)

            UpdateSettingsSection(updateState, updateActions)

            SectionTitle(stringResource(R.string.settings_about))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            )
        }
    }
}

/** Idioma (por app, pelo AppCompatDelegate) e tema (preferência do app). */
@Composable
private fun AppearanceSection(themeMode: String, onThemeMode: (String) -> Unit) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val languages = listOf(
        "" to stringResource(R.string.settings_language_system),
        "pt-BR" to stringResource(R.string.lang_pt_br),
        "en" to stringResource(R.string.lang_en),
    )
    val themes = listOf(
        "system" to stringResource(R.string.settings_theme_system),
        "light" to stringResource(R.string.settings_theme_light),
        "dark" to stringResource(R.string.settings_theme_dark),
    )
    val currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags().let { tags ->
        when {
            tags.isEmpty() -> ""
            tags.startsWith("pt") -> "pt-BR"
            else -> "en"
        }
    }

    SectionTitle(stringResource(R.string.settings_appearance))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_language)) },
        supportingContent = { Text(languages.first { it.first == currentLanguage }.second) },
        modifier = Modifier.clickable { dialog = "language" },
    )
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_theme)) },
        supportingContent = { Text(themes.firstOrNull { it.first == themeMode }?.second ?: themes.first().second) },
        modifier = Modifier.clickable { dialog = "theme" },
    )

    when (dialog) {
        "language" -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = languages,
            selected = currentLanguage,
            onPick = { tag ->
                dialog = null
                // Vazio = seguir o sistema. O AppCompat guarda e reaplica a escolha (no Android 13+, o sistema).
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            },
            onDismiss = { dialog = null },
        )
        "theme" -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = themes,
            selected = themeMode,
            onPick = { mode ->
                dialog = null
                onThemeMode(mode)
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, onClick = { onPick(value) }, role = Role.RadioButton)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
private fun SettingsAction(
    icon: ImageVector,
    title: String,
    body: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Uma linha a mais, menor, embaixo do texto (o que a opção precisa para funcionar). */
    note: String? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(body)
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** Cartão de um serviço do sistema: o que faz, se o Local Keys está ativo nele e o botão para abrir a tela do Android. */
@Composable
private fun DefaultServiceCard(
    icon: ImageVector,
    title: String,
    body: String,
    active: Boolean,
    onOpen: () -> Unit,
    /** Uma linha de estado a mais, só quando o sistema deixa saber com certeza (null = não mostra). */
    detail: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (active) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (active) R.string.settings_status_on else R.string.settings_status_off),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onOpen) {
                    Text(stringResource(if (active) R.string.settings_open_system else R.string.settings_turn_on))
                }
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun autofillSupported(context: Context): Boolean =
    context.getSystemService(AutofillManager::class.java)?.isAutofillSupported == true

/** O serviço de preenchimento escolhido no sistema é o nosso? */
private fun autofillEnabled(context: Context): Boolean =
    runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }
        .getOrDefault(false)

private fun openAutofillSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.settings_open_failed, Toast.LENGTH_LONG).show()
    }
}

/** Android 14+: o Local Keys está ligado como provedor em "Senhas, passkeys e contas"? */
private fun credentialProviderEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    return runCatching {
        val manager = context.getSystemService(android.credentials.CredentialManager::class.java)
        manager?.isEnabledCredentialProviderService(ComponentName(context, LocalKeysCredentialService::class.java)) == true
    }.getOrDefault(false)
}

/** A tela do sistema de provedores de senhas e passkeys (direto na entrada, quando o Android deixa). */
private fun openCredentialSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val viaPendingIntent = runCatching { CredentialManager.create(context).createSettingsPendingIntent().send() }.isSuccess
    if (viaPendingIntent) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_CREDENTIAL_PROVIDER).setData(Uri.parse("package:" + context.packageName)),
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_CREDENTIAL_PROVIDER))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.settings_open_failed, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Android 14+: o Local Keys é o *serviço preferencial* (o que o sistema oferece primeiro, inclusive para
 * passkeys em apps)? O Android não tem API pública para isso; tenta ler a configuração do sistema e, se ela não
 * puder ser lida (bloqueada ou ausente), devolve null: a tela então não mostra estado nenhum, em vez de chutar.
 */
private fun credentialProviderPreferred(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    val raw = runCatching { Settings.Secure.getString(context.contentResolver, "credential_service_primary") }
        .getOrNull() ?: return null
    return raw.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
}
