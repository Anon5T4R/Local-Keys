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
 *  - `vault_uri`: URI de documento (SAF) do último cofre aberto/criado. O
 *    arquivo em si fica no armazenamento do usuário; aqui só a referência.
 *  - `wrapped_key_hex`: a chave derivada do vault (32 bytes) cifrada com a
 *    chave AES/GCM autenticada por biometria do Android Keystore (formato
 *    `iv || ct`, em hex). Sem a biometria do dono não é decifrável.
 *  - `biometric_enabled`: opt-in de desbloqueio rápido por biometria.
 *  - `theme_mode`: tema do app ("system", "light" ou "dark"). O idioma não mora aqui: é o idioma por app do
 *    Android (AppCompatDelegate), que o próprio sistema guarda.
 *  - `update_*`: o atualizador (desligado por padrão), ver [UpdatePrefs]. Nada dele toca o cofre.
 */
private val Context.dataStore by preferencesDataStore(name = "localkeys")

class SettingsStore(private val context: Context) {

    val vaultUri: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.VAULT_URI] }

    /** Chave do vault (32 bytes) cifrada + IV, em hex — ou null se não ativada. */
    val wrappedKeyHex: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.WRAPPED_KEY_HEX] }

    val biometricEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.BIOMETRIC_ENABLED] ?: false }

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

    suspend fun setWrappedKeyHex(hex: String?) {
        context.dataStore.edit { prefs ->
            if (hex == null) prefs.remove(Keys.WRAPPED_KEY_HEX) else prefs[Keys.WRAPPED_KEY_HEX] = hex
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BIOMETRIC_ENABLED] = enabled }
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
