package com.localkeys.android.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Preferências locais (DataStore) — só configuração, nunca segredo em claro:
 *
 *  - `vault_uri`: URI de documento (SAF) do cofre atual. O arquivo em si fica no armazenamento do usuário; aqui
 *    só a referência.
 *  - `recent_vaults`: os cofres abertos recentemente (URI + nome), para trocar entre eles ([RecentVaults]).
 *  - `biometric_keys`: por cofre, a chave derivada dele (32 bytes) cifrada com a chave AES/GCM do Android
 *    Keystore que só a biometria libera (`iv || ct`, em hex; [BiometricKeys]). Sem a biometria do dono não é
 *    decifrável, e a de um cofre nunca é usada em outro. (As chaves `wrapped_key_hex`/`biometric_enabled` das
 *    versões antigas são migradas por [migrateLegacy].)
 *  - `copy_totp_after_autofill`: copiar o TOTP depois de preencher um login (padrão: ligado).
 *  - `theme_mode`: tema do app ("system", "light" ou "dark"). O idioma não mora aqui: é o idioma por app do
 *    Android (AppCompatDelegate), que o próprio sistema guarda.
 *  - `update_*`: o atualizador (desligado por padrão), ver [UpdatePrefs]. Nada dele toca o cofre.
 */
private val Context.dataStore by preferencesDataStore(name = "localkeys")

class SettingsStore(private val context: Context) {

    val vaultUri: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.VAULT_URI] }

    /** Tema escolhido nas configurações: "system", "light" ou "dark". */
    val themeMode: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode }
    }

    suspend fun setVaultUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.VAULT_URI) else prefs[Keys.VAULT_URI] = uri
        }
    }

    /** Cofres abertos recentemente (o atual incluído), do mais novo para o mais velho. */
    val recentVaults: Flow<List<RecentVault>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { RecentVaults.decode(it[Keys.RECENT_VAULTS]) }

    /** Põe o cofre no topo da lista de recentes (com o nome que o provedor mostra). */
    suspend fun touchRecentVault(uri: String, name: String?) {
        context.dataStore.edit { p ->
            p[Keys.RECENT_VAULTS] = RecentVaults.encode(RecentVaults.touch(RecentVaults.decode(p[Keys.RECENT_VAULTS]), uri, name))
        }
    }

    /**
     * Esquece um cofre da lista (e a chave biométrica dele). O arquivo fica onde está. Devolve quantas chaves
     * biométricas sobraram (zero = a chave do Keystore pode ir embora).
     */
    suspend fun forgetRecentVault(uri: String): Int {
        var left = 0
        context.dataStore.edit { p ->
            p[Keys.RECENT_VAULTS] = RecentVaults.encode(RecentVaults.remove(RecentVaults.decode(p[Keys.RECENT_VAULTS]), uri))
            val keys = BiometricKeys.decode(p[Keys.BIOMETRIC_KEYS]) - uri
            p[Keys.BIOMETRIC_KEYS] = BiometricKeys.encode(keys)
            left = keys.size
        }
        return left
    }

    /** Chaves de cofre cifradas pela biometria, uma por cofre (URI → hex). Ver [BiometricKeys]. */
    val biometricKeys: Flow<Map<String, String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { BiometricKeys.decode(it[Keys.BIOMETRIC_KEYS]) }

    /** Guarda (hex) ou apaga (null) a chave biométrica de um cofre. Devolve quantas chaves sobraram. */
    suspend fun setBiometricKey(uri: String, hex: String?): Int {
        var left = 0
        context.dataStore.edit { p ->
            val map = BiometricKeys.decode(p[Keys.BIOMETRIC_KEYS])
            val next = if (hex == null) map - uri else map + (uri to hex)
            p[Keys.BIOMETRIC_KEYS] = BiometricKeys.encode(next)
            left = next.size
        }
        return left
    }

    /** Apaga todas as chaves biométricas (a chave do Keystore foi invalidada: nenhuma serve mais). */
    suspend fun clearBiometricKeys() {
        context.dataStore.edit { p -> p.remove(Keys.BIOMETRIC_KEYS) }
    }

    /**
     * Uma vez: a chave biométrica única das versões antigas vira a do cofre que estava aberto, e o cofre atual
     * entra na lista de recentes. As chaves velhas somem das preferências.
     */
    suspend fun migrateLegacy() {
        context.dataStore.edit { p ->
            val legacyHex = p[Keys.WRAPPED_KEY_HEX]
            val legacyOn = p[Keys.BIOMETRIC_ENABLED] ?: false
            val uri = p[Keys.VAULT_URI]
            if (legacyHex != null || p.contains(Keys.BIOMETRIC_ENABLED)) {
                val migrated = BiometricKeys.migrateLegacy(BiometricKeys.decode(p[Keys.BIOMETRIC_KEYS]), legacyHex, legacyOn, uri)
                p[Keys.BIOMETRIC_KEYS] = BiometricKeys.encode(migrated)
                p.remove(Keys.WRAPPED_KEY_HEX)
                p.remove(Keys.BIOMETRIC_ENABLED)
            }
        }
    }

    /** Copiar o TOTP para a área de transferência depois de preencher um login que tem TOTP (padrão: ligado). */
    val copyTotpAfterAutofill: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.COPY_TOTP] ?: true }

    suspend fun setCopyTotpAfterAutofill(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COPY_TOTP] = enabled }
    }

    val updatePrefs: Flow<UpdatePrefs> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            UpdatePrefs(
                autoCheck = p[Keys.UPDATE_AUTO] ?: false,
                lastCheckAt = p[Keys.UPDATE_LAST_CHECK] ?: 0L,
                lastStatus = p[Keys.UPDATE_LAST_STATUS],
                availableJson = p[Keys.UPDATE_AVAILABLE],
                dismissedVersion = p[Keys.UPDATE_DISMISSED],
                pendingVersion = p[Keys.UPDATE_PENDING],
                installNotice = p[Keys.UPDATE_NOTICE],
            )
        }

    suspend fun setUpdateAuto(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_AUTO] = enabled }
    }

    /** Resultado de uma verificação: status ("uptodate", "available" ou "error:<FALHA>:<código>") e a release. */
    suspend fun setUpdateCheckResult(at: Long, status: String, availableJson: String?) {
        context.dataStore.edit { p ->
            p[Keys.UPDATE_LAST_CHECK] = at
            p[Keys.UPDATE_LAST_STATUS] = status
            if (availableJson == null) p.remove(Keys.UPDATE_AVAILABLE) else p[Keys.UPDATE_AVAILABLE] = availableJson
        }
    }

    suspend fun setUpdateDismissed(version: String?) {
        context.dataStore.edit { p ->
            if (version == null) p.remove(Keys.UPDATE_DISMISSED) else p[Keys.UPDATE_DISMISSED] = version
        }
    }

    /** Versão entregue ao instalador do sistema (ao abrir já nela, o app avisa que atualizou). */
    suspend fun setUpdatePending(version: String?) {
        context.dataStore.edit { p ->
            if (version == null) p.remove(Keys.UPDATE_PENDING) else p[Keys.UPDATE_PENDING] = version
        }
    }

    /** Aviso para a próxima abertura ("ok:<versão>", "fail:<mensagem>" ou "aborted"); null apaga. */
    suspend fun setUpdateNotice(notice: String?) {
        context.dataStore.edit { p ->
            if (notice == null) p.remove(Keys.UPDATE_NOTICE) else p[Keys.UPDATE_NOTICE] = notice
        }
    }

    private object Keys {
        val VAULT_URI = stringPreferencesKey("vault_uri")
        val WRAPPED_KEY_HEX = stringPreferencesKey("wrapped_key_hex")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val RECENT_VAULTS = stringPreferencesKey("recent_vaults")
        val BIOMETRIC_KEYS = stringPreferencesKey("biometric_keys")
        val COPY_TOTP = booleanPreferencesKey("copy_totp_after_autofill")
        val UPDATE_AUTO = booleanPreferencesKey("update_auto")
        val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check")
        val UPDATE_LAST_STATUS = stringPreferencesKey("update_last_status")
        val UPDATE_AVAILABLE = stringPreferencesKey("update_available")
        val UPDATE_DISMISSED = stringPreferencesKey("update_dismissed")
        val UPDATE_PENDING = stringPreferencesKey("update_pending")
        val UPDATE_NOTICE = stringPreferencesKey("update_notice")
    }
}

/** Estado guardado do atualizador. [autoCheck] nasce desligado: sem ele (ou o "verificar agora") não há rede. */
data class UpdatePrefs(
    val autoCheck: Boolean = false,
    val lastCheckAt: Long = 0L,
    val lastStatus: String? = null,
    val availableJson: String? = null,
    val dismissedVersion: String? = null,
    val pendingVersion: String? = null,
    val installNotice: String? = null,
)
