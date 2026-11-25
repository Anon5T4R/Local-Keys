package com.localkeys.android.ui

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.transfer.CredentialTypes
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import androidx.credentials.providerevents.transfer.KnownExtensions
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localkeys.android.R
import com.localkeys.android.data.biometric.BiometricVaultKey
import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.crypto.Hex
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.crypto.mergeOtherVault
import com.localkeys.android.data.import.BitwardenImport
import com.localkeys.android.data.import.BwDecrypt
import com.localkeys.android.data.import.ImportError
import com.localkeys.android.data.import.ImportFormat
import com.localkeys.android.data.import.Importers
import com.localkeys.android.data.export.VaultExport
import com.localkeys.android.data.export.VaultExporter
import com.localkeys.android.data.export.exportPasswordProblem
import com.localkeys.android.data.export.ExportPasswordProblem
import com.localkeys.android.data.passkey.CxfImport
import com.localkeys.android.data.store.CappedStream
import com.localkeys.android.data.store.DocumentIo
import com.localkeys.android.data.store.DocumentWriteException
import com.localkeys.android.data.store.DocumentWriter
import com.localkeys.android.data.store.EmptyDocument
import com.localkeys.android.data.store.RecentVault
import com.localkeys.android.data.store.RecentVaults
import com.localkeys.android.data.store.declaredDocumentSize
import com.localkeys.android.data.sync.JsonEq
import com.localkeys.android.data.sync.MergeConfirmAction
import com.localkeys.android.data.sync.MergeOtherFreshness
import com.localkeys.android.data.sync.MergeOtherLimit
import com.localkeys.android.data.sync.MergeOtherPrecheck
import com.localkeys.android.data.sync.VaultMerge
import com.localkeys.android.data.sync.confirmAction
import com.localkeys.android.data.sync.isWrongPassword
import org.json.JSONObject
import com.localkeys.android.data.store.SettingsStore
import com.localkeys.android.data.store.describeCause
import com.localkeys.android.data.vault.AutofillPrefs
import com.localkeys.android.data.vault.AutofillSaveBridge
import com.localkeys.android.data.vault.CredentialBridge
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.data.vault.VaultAutofillCache
import com.localkeys.android.data.vault.VaultRepository
import com.localkeys.android.data.vault.VaultSaver
import com.localkeys.android.data.vault.VaultSession
import com.localkeys.android.data.vault.VaultSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/** O documento do SAF para o [DocumentWriter]: os modos de escrita e a releitura pelo ContentResolver. */
class ResolverDocument(private val resolver: ContentResolver, private val uri: Uri) : DocumentIo {
    override fun openOutput(mode: String): java.io.OutputStream? = resolver.openOutputStream(uri, mode)

    override fun openInput(): java.io.InputStream? = resolver.openInputStream(uri)
}

/**
 * Regrava o documento do zero ("wt", com o "w" de reserva; ver [DocumentWriter]) e devolve o que ficou
 * gravado, relido (null se o provedor não deixa reler). Lança [DocumentWriteException] com a causa.
 */
fun writeDocument(resolver: ContentResolver, uri: Uri, bytes: ByteArray): ByteArray? =
    DocumentWriter.write(ResolverDocument(resolver, uri), bytes)

/**
 * Estado da UI + ponte entre telas, `.tkeys` e cofre biométrico do SO.
 *
 * A master password só entra em [unlock]/[onCreatedDocument] e morre no escopo
 * da chamada; o resto do tempo o app guarda apenas a **chave derivada** (na
 * sessão, em memória, ou cifrada pela biometria no DataStore).
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Screen {
        data object Unlock : Screen
        data object Vault : Screen
    }

    /** Origem do import pendente: muda a mensagem do diálogo de senha. */
    enum class ImportSource { BITWARDEN, KDBX }

    /** Import em andamento: arquivo escolhido + se precisa da senha do export. */
    data class PendingImport(
        val fileName: String,
        val needsPassword: Boolean,
        val source: ImportSource,
    )

    /** O destino do cofre novo: a URI do "criar" do SAF e o nome que o sistema deu ao arquivo (é o nome do cofre). */
    data class PendingExportVault(val uri: String, val name: String?)

    data class UiState(
        val screen: Screen = Screen.Unlock,
        val vault: Vault? = null,
        val vaultUri: String? = null,
        /** Nome do arquivo do cofre (o que o SAF mostra), para a tela de desbloqueio e as configurações. */
        val vaultName: String? = null,
        val biometricAvailable: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val pendingImport: PendingImport? = null,
        /** Exportar a seleção para um cofre NOVO: destino escolhido no "criar" do sistema, esperando a senha. */
        val exportVault: PendingExportVault? = null,
        /** Erro de validação das senhas do cofre novo (aparece DENTRO do diálogo). */
        val exportVaultError: String? = null,
        /** Arquivo mudou no disco fora do app → UI mostra o diálogo de conflito. */
        val externalChange: Boolean = false,
        /** Tema escolhido nas configurações ("system", "light" ou "dark"). */
        val themeMode: String = "system",
        /** Cofres abertos recentemente, o atual incluído (a tela de desbloqueio troca entre eles). */
        val recentVaults: List<RecentVault> = emptyList(),
        /** Copiar o TOTP depois de preencher um login pelo autofill. */
        val copyTotpAfterAutofill: Boolean = true,
        /** Pasta lembrada no seletor da lista (null = todas, "" = sem pasta, o id nos outros casos). */
        val rememberedFolderKey: String? = null,
        /** Sincronizar com outra cópia: esperando a senha dela ou a confirmação do resumo. */
        val syncPending: SyncPending? = null,
    )

    private val crypto = TkeysCrypto()
    private val repository = VaultRepository(crypto)
    private val saver = VaultSaver(repository)
    private val session = VaultSession(repository, saver)
    private val exporter = VaultExport()
    private val settings = SettingsStore(application)
    private val biometricKey = BiometricVaultKey()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * A seleção múltipla da lista principal (unidade 5): a tela da lista lê e muda este estado (toque longo
     * entra no modo, toque alterna, "selecionar tudo", fechar, trocar a busca/filtro/pasta) e as duas operações
     * que gravam passam por aqui, porque dependem do repositório e do arquivo. Trancar limpa (ver [lock]).
     */
    val selection = ListSelectionMode()

    /** Tamanho do `.tkeys` aberto (o blob conhecido, já cifrado): entra no aviso de tamanho ao anexar. */
    val vaultFileBytes: Long
        get() = if (session.isFor(_state.value.vaultUri)) session.known?.size?.toLong() ?: 0L else 0L

    /** Chaves biométricas por cofre (URI → hex). Só a do cofre atual é usada ([currentWrappedKey]). */
    @Volatile
    private var biometricKeys: Map<String, String> = emptyMap()
    private var encryptedImport: String? = null
    private var pendingKdbx: ByteArray? = null
    private var pendingExport: String? = null
    private var importJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // A chave biométrica única das versões antigas vira a do cofre atual antes de qualquer leitura.
            runCatching { settings.migrateLegacy() }
            launch {
                // Só quando a URI muda: o DataStore reemite a cada preferência gravada (tema, biometria), e reler o
                // arquivo nessas horas trocaria a base da detecção de mudança externa no meio do uso.
                settings.vaultUri.distinctUntilChanged().collect { uri ->
                    if (VaultSource.onUriObserved(session.knownUri, uri) == VaultSource.UriAction.Reload) {
                        session.forget()
                        _state.update {
                            it.copy(vaultUri = uri, vaultName = null, biometricAvailable = uri != null && biometricKeys.containsKey(uri))
                        }
                        if (uri != null) loadBlob(uri)
                    } else {
                        // O conhecido já é deste URI (o cofre que acabou de ser criado): só confirma o estado da tela
                        // e o nome/recentes, sem esquecer nem reler o arquivo.
                        _state.update {
                            it.copy(vaultUri = uri, biometricAvailable = uri != null && biometricKeys.containsKey(uri))
                        }
                        if (uri != null) rememberVault(uri)
                    }
                }
            }
            launch {
                settings.biometricKeys.collect { keys ->
                    biometricKeys = keys
                    _state.update { it.copy(biometricAvailable = it.vaultUri?.let(keys::containsKey) == true) }
                }
            }
        }
        viewModelScope.launch {
            settings.themeMode.collect { mode -> _state.update { it.copy(themeMode = mode) } }
        }
        viewModelScope.launch {
            settings.recentVaults.collect { list -> _state.update { it.copy(recentVaults = list) } }
        }
        viewModelScope.launch {
            settings.copyTotpAfterAutofill.collect { on ->
                AutofillPrefs.copyTotp = on
                _state.update { it.copy(copyTotpAfterAutofill = on) }
            }
        }
        viewModelScope.launch {
            settings.listFolderKey.collect { key -> _state.update { it.copy(rememberedFolderKey = key) } }
        }
    }

    /** A chave biométrica do cofre atual (nunca a de outro), ou null. */
    private fun currentWrappedKey(): Pair<String, String>? {
        val uri = _state.value.vaultUri ?: return null
        return biometricKeys[uri]?.let { uri to it }
    }

    // ── Abrir cofre existente ────────────────────────────────────────────

    /** URI escolhida no SAF (abrir). Persiste e carrega os bytes do arquivo. */
    fun onVaultUriChosen(uri: Uri) = switchVault(uri.toString())

    /**
     * Troca para outro cofre (um recente ou um arquivo escolhido agora): tranca o atual antes, para a chave de
     * um cofre nunca ficar aberta junto com o outro, e carrega o novo. Escolher o mesmo cofre de novo relê.
     */
    fun switchVault(uri: String) {
        if (_state.value.vault != null) lock()
        viewModelScope.launch {
            val current = settings.vaultUri.first()
            if (current == uri) {
                _state.update { it.copy(vaultUri = uri, biometricAvailable = biometricKeys.containsKey(uri)) }
                loadBlob(uri)
            } else {
                settings.setVaultUri(uri)
            }
        }
    }

    /**
     * Esquece um cofre da lista de recentes (e a chave biométrica dele). Nunca apaga o arquivo. Se não for o
     * cofre atual, devolve também a permissão de acesso ao documento.
     */
    fun forgetVault(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val biometricLeft = settings.forgetRecentVault(uri)
            if (biometricLeft == 0) runCatching { biometricKey.delete() }
            if (uri != _state.value.vaultUri) {
                runCatching {
                    getApplication<Application>().contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        }
    }

    /** Liga ou desliga a cópia do TOTP depois do autofill. */
    fun setCopyTotpAfterAutofill(enabled: Boolean) {
        viewModelScope.launch { settings.setCopyTotpAfterAutofill(enabled) }
    }

    /** Lembra a pasta escolhida no seletor da lista (null = todas). Estado de UI, nunca do cofre. */
    fun rememberListFolder(folderKey: String?) {
        viewModelScope.launch { settings.setListFolderKey(folderKey) }
    }

    private fun loadBlob(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                runCatching {
                    resolver.takePersistableUriPermission(
                        Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val bytes = resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                    ?: throw IOException(str(R.string.err_file_not_found))
                // A leitura pode ter demorado e o cofre mudou no meio (A→B→A com dois loadBlob): só adota se este
                // ainda é o cofre atual.
                if (!VaultSource.isCurrent(uri, _state.value.vaultUri)) return@launch
                session.load(uri, bytes)
                rememberVault(uri)
            } catch (e: Exception) {
                // Falha de uma leitura que já não é a do cofre atual não pode esquecer o cofre bom.
                if (!VaultSource.isCurrent(uri, _state.value.vaultUri)) return@launch
                session.forget()
                _state.update {
                    it.copy(
                        vaultUri = null,
                        error = str(R.string.msg_load_failed, e.message ?: ""),
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Nome do arquivo + lista de recentes do cofre atual, sem ler o cofre. */
    private fun rememberVault(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: Uri.parse(uri).lastPathSegment?.substringAfterLast('/')
            if (_state.value.vaultUri != uri) return@launch
            _state.update { it.copy(vaultName = name) }
            settings.touchRecentVault(uri, name)
        }
    }

    /** Desbloqueia com a master password (roda o Argon2id em background). */
    fun unlock(password: String) {
        if (password.isBlank()) {
            _state.update { it.copy(error = str(R.string.msg_password_empty)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                // Sem blob ou com o blob de OUTRO arquivo, o destrancar recusa sem exceção (a mensagem é a de sempre).
                val vault = session.unlock(_state.value.vaultUri, password)
                if (vault == null) {
                    _state.update { it.copy(error = str(R.string.msg_no_vault_loaded), busy = false) }
                    return@launch
                }
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, screen = Screen.Vault, busy = false) }
            } catch (e: TkeysError) {
                _state.update { it.copy(error = tkeysErrorMessage(e), busy = false) }
            }
        }
    }

    // ── Criar cofre novo ─────────────────────────────────────────────────

    /**
     * Cria o vault (vazio) e grava no documento escolhido no SAF. A senha vem
     * da tela só até aqui — o repositório guarda a chave derivada, não a senha.
     */
    fun onCreatedDocument(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            val resolver = getApplication<Application>().contentResolver
            // Antes de encostar no documento: se algo falhar aqui, o arquivo do "criar" está vazio e é apagado.
            val file = try {
                val file = session.createFile(Vault.empty(), password)
                withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                file
            } catch (e: Exception) {
                discardDocument(uri.toString())
                _state.update { it.copy(error = str(R.string.msg_create_failed, createFailureText(e)), busy = false) }
                return@launch
            }
            try {
                // `force`: o SAF acabou de criar/sobrescrever o documento — não há mudança externa a proteger.
                withContext(Dispatchers.IO) { session.writeCreated(uri.toString(), file, ResolverDocument(resolver, uri)) }
            } catch (e: Exception) {
                // A gravação pode ter começado: um cofre possivelmente válido não é apagado por falha de releitura.
                _state.update { it.copy(error = str(R.string.msg_create_failed, createFailureText(e)), busy = false) }
                return@launch
            }
            settings.setVaultUri(uri.toString())
            syncAutofillCache(repository.vault)
            _state.update { it.copy(vault = repository.vault, screen = Screen.Vault, busy = false) }
        }
    }

    /** O motivo de uma falha ao criar o cofre, do mesmo jeito que as outras gravações contam. */
    private fun createFailureText(e: Exception): String = when (e) {
        is VaultSaver.SaveFailure -> saveFailureText(e)
        is TkeysError -> tkeysErrorMessage(e)
        else -> e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
    }

    // ── Conflito com o disco (o arquivo mudou fora do app) ───────────────

    /**
     * Sobrescreve o arquivo com o vault em memória mesmo havendo mudança externa
     * (usuário confirmou no diálogo de conflito). Como as edições pendentes já
     * estão no vault em memória, isto serve de "retry" para qualquer ação que
     * tenha esbarrado no [VaultSaver.SaveFailure.ExternalChange].
     */
    fun forceSave() {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                persistAndCommit(repository.vault, notice = str(R.string.msg_vault_saved), force = true)
            } catch (e: Exception) {
                failSave(e, R.string.op_save)
            }
        }
    }

    /**
     * Recarrega o arquivo do disco, adotando a versão externa e descartando as
     * edições em memória não salvas (usuário confirmou no diálogo de conflito).
     * Reusa a chave da sessão atual, sem pedir a senha de novo.
     */
    fun reloadFromDisk() {
        val uri = _state.value.vaultUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val bytes = readDocumentBytes(resolver, Uri.parse(uri))
                    ?: throw IOException(str(R.string.err_file_not_found))
                val vault = session.reload(uri, bytes)
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, busy = false, notice = str(R.string.msg_reloaded)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.msg_reload_failed, e.message ?: ""), busy = false) }
            }
        }
    }

    /** Fecha o diálogo de conflito sem agir (usuário decide depois). */
    fun dismissExternalChange() {
        _state.update { it.copy(externalChange = false) }
    }

    // ── Desbloqueio biométrico (chave do vault cifrada no Keystore) ──────

    /** Cipher ENCRYPT pronto para o BiometricPrompt — ativar biometria. */
    fun newWrapCipher(): Cipher = biometricKey.createEncryptCipher()

    /** Cipher DECRYPT do cofre biométrico guardado — ou null se indisponível. */
    fun biometricDecryptCipher(): Cipher? {
        val (_, hex) = currentWrappedKey() ?: return null
        return runCatching { biometricKey.createDecryptCipher(Hex.decode(hex)) }.getOrNull()
    }

    /** Após a biometria: cifra a chave derivada e guarda no DataStore, presa a este cofre. */
    fun enableBiometricWithCipher(cipher: Cipher) {
        val key = repository.keyBytes() ?: return
        val uri = _state.value.vaultUri ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val wrapped = biometricKey.wrapWithCipher(key, cipher)
                settings.setBiometricKey(uri, Hex.encode(wrapped))
                _state.update { it.copy(notice = str(R.string.msg_biometric_enabled)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.biometric_unavailable, e.message ?: "")) }
            }
        }
    }

    /** Após a biometria: recupera a chave e abre o vault sem senha. */
    fun unlockWithBiometricCipher(cipher: Cipher) {
        val (uri, hex) = currentWrappedKey() ?: return
        // Sem o conteúdo conhecido DESTE cofre, a biometria não tem o que abrir: mensagem, não exceção.
        if (!session.isFor(_state.value.vaultUri)) {
            _state.update { it.copy(error = str(R.string.msg_no_vault_loaded)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val key = biometricKey.unwrapWithCipher(Hex.decode(hex), cipher)
                // O cofre pode ter sido trocado enquanto a digital era lida: a chave só abre o cofre dela.
                if (_state.value.vaultUri != uri) {
                    _state.update { it.copy(busy = false) }
                    return@launch
                }
                val vault = session.unlockWithKey(uri, key)
                if (vault == null) {
                    _state.update { it.copy(error = str(R.string.msg_no_vault_loaded), busy = false) }
                    return@launch
                }
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, screen = Screen.Vault, busy = false) }
            } catch (e: Exception) {
                when (e) {
                    // Cadastrar dedo/rosto novo invalida a chave do Keystore: nenhuma chave guardada serve mais.
                    is KeyPermanentlyInvalidatedException -> {
                        clearAllBiometric()
                        _state.update { it.copy(error = str(R.string.msg_biometric_changed), busy = false) }
                    }
                    // A chave guardada não abre este cofre (senha trocada no desktop, chave antiga): só a dele cai.
                    is TkeysError, is AEADBadTagException -> {
                        disableBiometric()
                        _state.update { it.copy(error = str(R.string.msg_biometric_changed), busy = false) }
                    }
                    else -> _state.update { it.copy(error = str(R.string.msg_biometric_failed, describeCause(e)), busy = false) }
                }
            }
        }
    }

    /** Desativa o desbloqueio biométrico deste cofre (os outros cofres continuam com o deles). */
    fun disableBiometric() {
        val uri = _state.value.vaultUri ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val left = settings.setBiometricKey(uri, null)
            // Sem cofre nenhum usando, a chave do Keystore também vai embora.
            if (left == 0) runCatching { biometricKey.delete() }
            _state.update { it.copy(biometricAvailable = false) }
        }
    }

    private fun clearAllBiometric() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { biometricKey.delete() }
            settings.clearBiometricKeys()
            _state.update { it.copy(biometricAvailable = false) }
        }
    }

    // ── Importar de outro gerenciador ────────────────────────────────────

    /** Arquivo escolhido no SAF: detecta formato e importa ou pede a senha. */
    fun onImportUriChosen(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val fileName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException(str(R.string.err_file_not_found))
                val content = bytes.toString(Charsets.UTF_8)

                when (val format = Importers.detectFormat(fileName, content)) {
                    ImportFormat.KDBX -> {
                        pendingKdbx = bytes
                        _state.update {
                            it.copy(
                                busy = false,
                                pendingImport = PendingImport(
                                    fileName ?: str(R.string.import_default_kdbx),
                                    needsPassword = true,
                                    source = ImportSource.KDBX,
                                ),
                            )
                        }
                    }
                    ImportFormat.BITWARDEN_ENCRYPTED -> {
                        encryptedImport = content
                        _state.update {
                            it.copy(
                                busy = false,
                                pendingImport = PendingImport(
                                    fileName ?: str(R.string.import_default_bitwarden),
                                    needsPassword = true,
                                    source = ImportSource.BITWARDEN,
                                ),
                            )
                        }
                    }
                    else -> performImport(Importers.parseText(content, format), fileName)
                }
            } catch (e: Exception) {
                encryptedImport = null
                pendingKdbx = null
                _state.update {
                    it.copy(busy = false, pendingImport = null, error = importErrorText(e))
                }
            }
        }
    }

    /** Senha do export: decifra Bitwarden ou KeePass e importa. */
    fun onImportPassword(password: String) {
        if (encryptedImport == null && pendingKdbx == null) return
        if (password.isBlank()) {
            _state.update { it.copy(error = str(R.string.msg_import_password_empty)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val items = pendingKdbx?.let { Importers.parseKdbx(it, password) }
                    ?: run {
                        val json = encryptedImport ?: return@launch
                        BitwardenImport.parse(BwDecrypt.decryptExport(json, password))
                    }
                performImport(items, null)
            } catch (e: Exception) {
                // Senha errada: mantém o diálogo aberto para tentar de novo.
                _state.update { it.copy(busy = false, error = importErrorText(e)) }
            }
        }
    }

    /** Cancela o import pendente. */
    fun dismissImport() {
        encryptedImport = null
        pendingKdbx = null
        _state.update { it.copy(pendingImport = null) }
    }

    /** Anexa os itens ao vault, recifra e grava no arquivo. */
    private suspend fun performImport(items: List<Item>, fileName: String?) {
        if (items.isEmpty()) {
            _state.update {
                it.copy(
                    busy = false,
                    pendingImport = null,
                    notice = fileName?.let { name -> str(R.string.msg_import_empty_in, name) }
                        ?: str(R.string.msg_import_empty),
                )
            }
            return
        }
        try {
            val merged = repository.appendItems(items)
            persistAndCommit(merged, notice = str(R.string.msg_imported, items.size))
            encryptedImport = null
            pendingKdbx = null
            _state.update { it.copy(pendingImport = null) }
        } catch (e: Exception) {
            encryptedImport = null
            pendingKdbx = null
            _state.update { it.copy(pendingImport = null) }
            failSave(e, R.string.op_import)
        }
    }

    // ── Editar / criar / excluir itens ───────────────────────────────────

    /**
     * Cria (id novo) ou atualiza (id existente) um item e grava no arquivo.
     *
     * [onCommitted] roda na thread da interface **só depois** de o cofre gravado ser adotado no estado — é ali
     * que a tela de detalhe é desenhada do cofre. Sair do editor antes da gravação deixava o detalhe com a
     * lista de anexos de antes (num cofre grande, por segundos); e, se a gravação falhasse, o editor já tinha
     * fechado. Falhou: nada de [onCommitted], o editor fica aberto com as edições e o erro.
     */
    fun saveItem(item: Item, onCommitted: () -> Unit = {}) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            val saved = try {
                val isNew = repository.vault.items.none { it.id == item.id }
                val updated = if (isNew) repository.addItem(item) else repository.updateItem(item)
                persistAndCommit(
                    updated,
                    notice = str(if (isNew) R.string.msg_item_added else R.string.msg_item_updated),
                )
                true
            } catch (e: Exception) {
                failSave(e, R.string.op_save_item)
                false
            }
            if (saved) withContext(Dispatchers.Main) { onCommitted() }
        }
    }

    /** Manda um item para a lixeira (como no desktop: só marca `deletedAt`) e grava no arquivo. */
    fun deleteItem(id: String) = persistOp(R.string.op_delete_item, R.string.msg_item_trashed) { repository.trashItem(id) }

    /** Tira um item da lixeira. */
    fun restoreItem(id: String) = persistOp(R.string.op_restore_item, R.string.msg_item_restored) { repository.restoreItem(id) }

    /** Exclui de vez um item que está na lixeira. */
    fun deleteItemForever(id: String) =
        persistOp(R.string.op_delete_item, R.string.msg_item_deleted_forever) { repository.deleteItem(id) }

    /** Alterna o favorito de um item e grava no arquivo. */
    fun toggleFavorite(id: String) = persistOp(R.string.op_favorite, notice = null) { repository.toggleFavorite(id) }

    // ── Anexos (lixeira de anexos do desktop) ────────────────────────────

    fun trashAttachment(itemId: String, attachmentId: String) =
        persistOp(R.string.op_attachment, R.string.msg_attachment_trashed) { repository.trashAttachment(itemId, attachmentId) }

    fun restoreAttachment(itemId: String, attachmentId: String) =
        persistOp(R.string.op_attachment, R.string.msg_attachment_restored) { repository.restoreAttachment(itemId, attachmentId) }

    fun deleteAttachmentForever(itemId: String, attachmentId: String) =
        persistOp(R.string.op_attachment, R.string.msg_attachment_deleted) {
            repository.deleteAttachmentForever(itemId, attachmentId)
        }

    // ── Pastas ───────────────────────────────────────────────────────────

    /** Cria uma pasta nova e grava no arquivo. */
    fun addFolder(name: String) = persistOp(R.string.op_create_folder, R.string.msg_folder_created) {
        repository.addFolder(Folder(UUID.randomUUID().toString(), name.trim()))
    }

    /** Renomeia uma pasta e grava no arquivo. */
    fun renameFolder(id: String, name: String) =
        persistOp(R.string.op_rename_folder, R.string.msg_folder_renamed) { repository.renameFolder(id, name.trim()) }

    /** Exclui uma pasta (os itens ficam sem pasta) e grava no arquivo. */
    fun deleteFolder(id: String) =
        persistOp(R.string.op_delete_folder, R.string.msg_folder_deleted) { repository.deleteFolder(id) }

    /**
     * Uma operação do repositório seguida da gravação conferida. [op] muda o cofre em memória; se a gravação
     * falha, a mensagem vem com o contexto [context] (ou vira o diálogo de conflito).
     */
    private fun persistOp(@StringRes context: Int, @StringRes notice: Int?, op: () -> Vault) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                persistAndCommit(op(), notice = notice?.let { str(it) })
            } catch (e: Exception) {
                failSave(e, context)
            }
        }
    }

    // ── Seleção múltipla da lista (lixeira e pasta em lote) ──────────────

    /**
     * Manda a seleção para a lixeira com UMA gravação, pela regra do núcleo ([VaultRepository.trashSelected]).
     * O repositório devolve o cofre CANDIDATO sem adotá-lo; a memória só é trocada pelo [persistAndCommit], depois
     * da gravação conferida. A recusa do lote (um item inválido, um item que já está na lixeira) ou a falha da
     * gravação deixam o cofre como estava — memória e arquivo — e a seleção continua para a pessoa tentar de novo.
     */
    fun trashSelected() {
        val ids = selection.ids()
        if (ids.isEmpty() || _state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val result = repository.trashSelected(ids)
                persistAndCommit(result.vault, notice = plural(R.plurals.msg_items_trashed, ids.size))
                // A seleção é estado de tela (Compose): fecha na thread da UI, não na que gravou.
                withContext(Dispatchers.Main) { selection.clear() }
            } catch (e: Exception) {
                failSave(e, R.string.op_delete_item)
            }
        }
    }

    /**
     * Move a seleção para [folderId] (`null` = sem pasta) com UMA gravação. O candidato só entra em memória depois
     * do OK da gravação ([persistAndCommit]); os itens que já estavam na pasta não são recarimbados e, se nenhum
     * mudou de fato, não há gravação e a seleção fica (nada aconteceu). Pasta inexistente é a recusa do núcleo.
     */
    fun moveSelected(folderId: String?) {
        val ids = selection.ids()
        if (ids.isEmpty() || _state.value.vaultUri == null) return
        val target = if (folderId == null) {
            str(R.string.folder_sem)
        } else {
            _state.value.vault?.folders?.firstOrNull { it.id == folderId }?.name ?: str(R.string.folder_sem)
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val result = repository.moveSelected(ids, folderId)
                if (result.changed == 0) {
                    // Já estavam nessa pasta: nada mudou, nada é gravado (nem a seleção, que continua útil).
                    _state.update { it.copy(busy = false) }
                    return@launch
                }
                persistAndCommit(result.vault, notice = plural(R.plurals.msg_items_moved, result.changed, target))
                withContext(Dispatchers.Main) { selection.clear() }
            } catch (e: Exception) {
                failSave(e, R.string.op_move_item)
            }
        }
    }

    // ── Importar de outro gerenciador (Credential Exchange da FIDO) ──────

    /**
     * Pede ao sistema as credenciais de outro gerenciador (o do Google, por exemplo) pelo Credential Exchange.
     * O Android mostra quem pode exportar; o que volta vem no formato CXF, vira itens de login e é gravado pelo
     * mesmo caminho das passkeys novas ([persistNewItems]). Precisa do Android 14+ e do Google Play services.
     */
    fun importFromOtherManager(activity: Activity) {
        if (_state.value.vaultUri == null || _state.value.busy) return
        // A troca passa pela tela do sistema e pode nunca voltar (Play services desatualizado, por exemplo):
        // o app não fica "ocupado" enquanto espera, e tocar de novo recomeça o pedido.
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                val request = ImportCredentialsRequest(
                    setOf(
                        CredentialTypes.CREDENTIAL_TYPE_BASIC_AUTH,
                        CredentialTypes.CREDENTIAL_TYPE_PUBLIC_KEY,
                        CredentialTypes.CREDENTIAL_TYPE_TOTP,
                        CredentialTypes.CREDENTIAL_TYPE_NOTE,
                    ),
                    setOf(KnownExtensions.KNOWN_EXTENSION_SHARED),
                )
                val response = ProviderEventsManager.create(activity).importCredentials(activity, request)
                _state.update { it.copy(busy = true) }
                val parsed = withContext(Dispatchers.Default) { CxfImport.parse(response.response.responseJson) }
                if (parsed.items.isEmpty()) {
                    _state.update { it.copy(busy = false, notice = str(R.string.msg_import_empty)) }
                    return@launch
                }
                val failure = persistNewItemsOrError(parsed.items)
                _state.update {
                    if (failure == null) {
                        it.copy(busy = false, notice = str(R.string.msg_cxf_imported, parsed.passwords, parsed.passkeys))
                    } else {
                        val detail = (failure as? TkeysError)?.let(::tkeysErrorMessage)
                            ?: failure.message?.takeIf { m -> m.isNotBlank() }
                            ?: describeCause(failure)
                        it.copy(busy = false, error = str(R.string.msg_op_failed, str(R.string.op_import), detail))
                    }
                }
            } catch (_: ImportCredentialsCancellationException) {
                _state.update { it.copy(busy = false) }
            } catch (_: ImportCredentialsNoExportOptionException) {
                _state.update { it.copy(busy = false, error = str(R.string.msg_cxf_no_provider)) }
            } catch (e: ImportCredentialsException) {
                val detail = e.errorMessage ?: e.message ?: e.type
                _state.update { it.copy(busy = false, error = str(R.string.msg_op_failed, str(R.string.op_import), detail)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = str(R.string.msg_op_failed, str(R.string.op_import), e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Recifra [updated] com nonce novo (sem depender de o repositório já o ter adotado), **valida o blob antes de
     * gravar** (decifra com a sessão e confere que reabre exatamente aquele vault) e, depois de gravar, **relê o
     * documento** para conferir que o provider não truncou/embaralhou nada — se falhou, reescreve uma vez. O cofre
     * [updated] só é adotado em memória (repositório e estado) depois de o arquivo gravado reabrir: uma gravação que
     * falha não deixa a memória à frente do disco. As operações de um item só, que já mutam o repositório antes de
     * chamar daqui, continuam como sempre foram.
     */
    private suspend fun persistAndCommit(updated: Vault, notice: String?, force: Boolean = false) {
        val uri = _state.value.vaultUri
            ?: throw IOException(str(R.string.err_no_file))
        val resolver = getApplication<Application>().contentResolver
        // Cifra + grava + confere contra o CANDIDATO + adota: o `VaultSaver` faz a sequência inteira na thread de IO.
        withContext(Dispatchers.IO) {
            saver.saveAndCommit(updated, ResolverDocument(resolver, Uri.parse(uri)), force)
        }
        syncAutofillCache(updated)
        _state.update { it.copy(vault = updated, busy = false, notice = notice) }
    }

    /** Lê os bytes do documento SAF (null se não der pra ler). */
    private suspend fun readDocumentBytes(resolver: ContentResolver, document: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(document)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Tratamento comum de falha de gravação: a mudança externa
     * ([VaultSaver.SaveFailure.ExternalChange]) vira o diálogo de conflito;
     * [TkeysError] e as demais variantes do [VaultSaver] viram a mensagem
     * mapeada do erro; o resto vira "contexto: mensagem".
     */
    private fun failSave(e: Exception, @StringRes context: Int) {
        if (e is VaultSaver.SaveFailure.ExternalChange) {
            _state.update { it.copy(externalChange = true, busy = false) }
        } else {
            val detail = when (e) {
                is VaultSaver.SaveFailure -> saveFailureText(e)
                is TkeysError -> tkeysErrorMessage(e)
                else -> e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
            }
            _state.update {
                it.copy(error = str(R.string.msg_op_failed, str(context), detail), busy = false)
            }
        }
    }

    /** Texto de uma falha do [VaultSaver]: as variantes não dependem de Android, o recurso é resolvido aqui. */
    private fun saveFailureText(e: VaultSaver.SaveFailure): String = when (e) {
        is VaultSaver.SaveFailure.NotTruncated -> str(R.string.err_write_not_truncated, e.written, e.found)
        is VaultSaver.SaveFailure.WriteFailed ->
            str(R.string.err_write_failed_cause, e.attempts.joinToString("; ") { (mode, cause) -> "$mode: ${describeCause(cause)}" })
        is VaultSaver.SaveFailure.RetryFailed -> str(R.string.err_write_retry_failed, e.cause?.let(::describeCause) ?: "")
        VaultSaver.SaveFailure.Unrecoverable -> str(R.string.err_write_unrecoverable)
        // No `failSave` a mudança externa vira o diálogo de conflito; quando a falha chega por outro caminho (criar
        // cofre), o texto é o de mudança externa, não o de "não reabre".
        is VaultSaver.SaveFailure.ExternalChange -> str(R.string.external_change_title)
    }

    /** Mensagem de erro do `.tkeys` mapeada por tipo (recursos, não o message interno). */
    private fun tkeysErrorMessage(e: TkeysError): String = when (e) {
        TkeysError.BadFormat -> str(R.string.tkeys_bad_format)
        is TkeysError.UnsupportedVersion -> str(R.string.tkeys_unsupported_version, e.version)
        TkeysError.Kdf -> str(R.string.tkeys_kdf)
        TkeysError.Decrypt -> str(R.string.tkeys_decrypt)
        TkeysError.Corrupted -> str(R.string.tkeys_corrupted)
        is TkeysError.NotConvertible -> str(R.string.tkeys_not_convertible)
        is TkeysError.InvalidItems -> str(R.string.tkeys_invalid_items)
        is TkeysError.CannotExport -> str(R.string.tkeys_cannot_export)
        is TkeysError.CannotMerge -> str(R.string.tkeys_cannot_merge)
    }

    /** O texto do erro de um importador de arquivo, no idioma da interface (o [ImportError] carrega o recurso). */
    private fun importErrorText(e: Exception): String =
        if (e is ImportError) str(e.resId, *e.args) else e.message ?: str(R.string.msg_import_generic)

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** O texto de um plural no idioma da interface; a contagem vai também nos argumentos (`%1$d`). */
    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, count, count, *args)

    /** Mantém o cache do autofill em sincronia com o estado do cofre. */
    private fun syncAutofillCache(vault: Vault?) {
        if (vault == null) {
            VaultAutofillCache.clear()
            AutofillSaveBridge.unregister()
            CredentialBridge.unregister()
        } else {
            VaultAutofillCache.set(vault)
            AutofillSaveBridge.register(::persistAutofillLogin)
            CredentialBridge.register(::persistNewItems)
        }
    }

    /**
     * Itens novos vindos do provedor de credenciais ou da importação (Credential Exchange): acrescenta e grava,
     * e só devolve true com o arquivo gravado e conferido. Se o arquivo mudou por fora (outra tela do app ou
     * outro aparelho), relê e tenta de novo uma vez — nada é sobrescrito.
     */
    private suspend fun persistNewItems(items: List<Item>): Boolean = persistNewItemsOrError(items) == null

    /** Como [persistNewItems], mas devolve a falha (null = gravado e conferido) para a mensagem ter a causa. */
    private suspend fun persistNewItemsOrError(items: List<Item>): Exception? = withContext(Dispatchers.Default) {
        suspend fun once() {
            val updated = repository.appendItems(items)
            persistAndCommit(updated, notice = null)
        }
        try {
            once()
            null
        } catch (_: VaultSaver.SaveFailure.ExternalChange) {
            try {
                val uri = _state.value.vaultUri ?: throw IOException(str(R.string.err_no_file))
                val bytes = readDocumentBytes(getApplication<Application>().contentResolver, Uri.parse(uri))
                    ?: throw IOException(str(R.string.err_file_not_found))
                session.reload(uri, bytes)
                once()
                null
            } catch (e: Exception) {
                e
            }
        } catch (e: Exception) {
            e
        }
    }

    /**
     * Credencial nova vinda de um SaveRequest do autofill: adiciona como item
     * de login e recifra/grava. Sem duplicar quando já existe a mesma
     * combinação usuário+senha+site.
     */
    private fun persistAutofillLogin(request: AutofillSaveBridge.LoginSaveRequest) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val vault = repository.vault
                val dupe = vault.items.any { item ->
                    item.kind == ItemKind.LOGIN &&
                        item.login?.username == request.username &&
                        item.login?.password == request.password &&
                        request.uris.isNotEmpty() &&
                        item.login?.uris?.intersect(request.uris.toSet())?.isNotEmpty() == true
                }
                if (dupe) return@launch

                val now = System.currentTimeMillis()
                val domain = request.uris.firstNotNullOfOrNull { AutofillMatcher.normalizeDomain(it) }
                val item = Item(
                    id = UUID.randomUUID().toString(),
                    kind = ItemKind.LOGIN,
                    name = domain?.takeIf { it.isNotBlank() }
                        ?: request.username.takeIf { it.isNotBlank() }
                        ?: str(R.string.autofill_default_name),
                    favorite = false,
                    folderId = null,
                    notes = "",
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    login = Login(
                        username = request.username,
                        password = request.password,
                        uris = request.uris,
                        totp = "",
                    ),
                    card = null,
                    identity = null,
                    passwordHistory = null,
                    customFields = null,
                    attachments = null,
                )
                repository.addItem(item)
                persistAndCommit(repository.vault, notice = null)
            } catch (_: Exception) {
                // Cofre trancou no meio do caminho ou falha de gravação: o
                // autofill segue normal e o usuário salva manualmente depois.
            }
        }
    }

    // ── Sincronização: levar o cofre para outra pasta / juntar com outra cópia ──

    /**
     * Destino escolhido no SAF para levar o cofre (pasta do OneDrive, Drive, rclone, Syncthing...): grava o cofre
     * cifrado lá, relê e confere que abre com a chave atual, e só então passa a usar o arquivo novo (recentes,
     * permissão e chave da biometria vão junto). O arquivo antigo fica como cópia de segurança. Qualquer falha:
     * continua no cofre antigo e mostra a causa.
     */
    fun onMoveTargetChosen(target: Uri) {
        val oldUri = _state.value.vaultUri ?: return
        if (target.toString() == oldUri) {
            _state.update { it.copy(error = str(R.string.sync_move_same_file)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val file = repository.save()
                repository.verifySaved(file)
                withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.takePersistableUriPermission(
                            target,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                val back = withContext(Dispatchers.IO) { writeDocument(resolver, target, file) }
                    ?: throw IOException(str(R.string.err_file_not_found))
                // Relido do destino: abre com a chave atual e é exatamente o cofre aberto.
                repository.verifySaved(back)

                val newUri = target.toString()
                val newName = displayName(resolver, target)
                val oldName = _state.value.vaultName ?: RecentVaults.fallbackName(oldUri)
                biometricKeys[oldUri]?.let { hex ->
                    settings.setBiometricKey(newUri, hex)
                    settings.setBiometricKey(oldUri, null)
                }
                // O arquivo antigo vira só cópia de segurança: sai da lista para não ser aberto por engano.
                settings.forgetRecentVault(oldUri)
                settings.touchRecentVault(newUri, newName)
                session.load(newUri, back)
                _state.update { it.copy(vaultUri = newUri, vaultName = newName, busy = false) }
                settings.setVaultUri(newUri)
                _state.update {
                    it.copy(notice = str(R.string.sync_move_done, newName ?: newUri, describeLocation(oldName, oldUri)))
                }
            } catch (e: Exception) {
                val detail = (e as? TkeysError)?.let(::tkeysErrorMessage) ?: e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
                _state.update { it.copy(busy = false, error = str(R.string.sync_move_failed, detail)) }
            }
        }
    }

    /** O caminho do arquivo quando o provedor mostra ("Download/localkeys.tkeys"); senão só o nome. */
    private fun describeLocation(name: String, uri: String): String {
        val docId = runCatching { java.net.URLDecoder.decode(uri, "UTF-8") }.getOrDefault(uri).substringAfterLast("/document/", "")
        // "primary:Download/x.tkeys" / "raw:/storage/.../x.tkeys" → o caminho; "msf:12" (sem caminho) → o nome.
        return if (docId.contains('/')) docId.substringAfter(':') else name
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Uma outra cópia em andamento: escolhida, aberta (ou esperando a senha) e já juntada. */
    data class SyncPending(
        val otherName: String,
        val needsPassword: Boolean,
        val summary: VaultMerge.Summary? = null,
        val passwordError: String? = null,
    )

    private var syncOtherUri: Uri? = null
    private var syncOtherBytes: ByteArray? = null
    private var syncOther: VaultRepository.OtherCopy? = null
    private var syncMerged: Vault? = null

    /** Outra cópia escolhida no SAF: tenta abrir com a chave atual; se não der, pede a senha dela. */
    fun onSyncOtherChosen(uri: Uri) {
        if (uri.toString() == _state.value.vaultUri) {
            _state.update { it.copy(error = str(R.string.sync_same_file)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException(str(R.string.err_file_not_found))
                val name = displayName(resolver, uri) ?: RecentVaults.fallbackName(uri.toString())
                syncOtherUri = uri
                syncOtherBytes = bytes
                val other = withContext(Dispatchers.Default) { repository.openOtherWithSessionKey(bytes) }
                if (other == null) {
                    _state.update { it.copy(busy = false, syncPending = SyncPending(name, needsPassword = true)) }
                } else {
                    prepareSync(name, other)
                }
            } catch (e: Exception) {
                clearSync()
                val detail = (e as? TkeysError)?.let(::tkeysErrorMessage) ?: e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
                _state.update { it.copy(busy = false, syncPending = null, error = str(R.string.sync_open_failed, detail)) }
            }
        }
    }

    /** Senha da outra cópia (quando a chave atual não abriu). Errada: o diálogo continua aberto. */
    fun onSyncPassword(password: String) {
        val bytes = syncOtherBytes ?: return
        val pending = _state.value.syncPending ?: return
        if (password.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, syncPending = pending.copy(passwordError = null)) }
            try {
                prepareSync(pending.otherName, repository.openOtherWithPassword(bytes, password))
            } catch (e: TkeysError) {
                _state.update { it.copy(busy = false, syncPending = pending.copy(passwordError = tkeysErrorMessage(e))) }
            }
        }
    }

    private fun prepareSync(name: String, other: VaultRepository.OtherCopy) {
        val result = VaultMerge.merge(repository.vault, other.vault)
        syncOther = other
        syncMerged = result.vault
        _state.update { it.copy(busy = false, syncPending = SyncPending(name, needsPassword = false, summary = result.summary)) }
    }

    /**
     * Confirmou o resumo: grava o resultado no cofre atual (com a conferência de sempre) e na outra cópia
     * (relida e conferida com a chave dela). Se a outra cópia falhar, o atual fica com a junção e o aviso diz.
     */
    fun confirmSync() {
        val merged = syncMerged ?: return
        val other = syncOther ?: return
        val otherUri = syncOtherUri ?: return
        val name = _state.value.syncPending?.otherName.orEmpty()
        _state.update { it.copy(syncPending = null) }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            val mergedJson = JSONObject(merged.toJson())
            try {
                if (!JsonEq.same(mergedJson, JSONObject(repository.vault.toJson()))) {
                    repository.replace(merged)
                    persistAndCommit(merged, notice = null)
                }
            } catch (e: Exception) {
                clearSync()
                failSave(e, R.string.op_sync)
                return@launch
            }
            val otherProblem = if (JsonEq.same(mergedJson, JSONObject(other.vault.toJson()))) {
                null
            } else {
                try {
                    val blob = repository.sealFor(other, merged)
                    val back = withContext(Dispatchers.IO) {
                        writeDocument(getApplication<Application>().contentResolver, otherUri, blob)
                    }
                    if (back == null || !repository.verifyFor(other, back, merged)) str(R.string.err_write_unrecoverable) else null
                } catch (e: Exception) {
                    e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
                }
            }
            clearSync()
            _state.update {
                if (otherProblem == null) {
                    it.copy(busy = false, notice = str(R.string.sync_done, name))
                } else {
                    it.copy(busy = false, error = str(R.string.sync_other_failed, name, otherProblem))
                }
            }
        }
    }

    /** Cancelou (ou fechou o diálogo): nada é gravado. */
    fun cancelSync() {
        clearSync()
        _state.update { it.copy(syncPending = null) }
    }

    private fun clearSync() {
        syncOtherUri = null
        syncOtherBytes = null
        syncOther = null
        syncMerged = null
    }

    // ── Juntar outro cofre (unidade 7, peça 3) ───────────────────────────

    /** O estado da junção: diálogo de senha do outro, resumo para confirmar, ou nada. A tela lê [mergeOther]. */
    val mergeOther = MergeOtherFlow()

    /** O outro arquivo lido (para o passo da senha) e o cofre dele (para refazer a junção se o aberto mudar). */
    private var mergeOtherBytes: ByteArray? = null
    private var mergeOtherSource: Vault? = null
    private var mergeMerged: Vault? = null

    /** O JSON do cofre ABERTO usado no cálculo do resumo: se ele mudar até o confirmar, a junção é refeita. */
    private var mergeBaseJson: String? = null

    /** O resultado de um passo calculado FORA da main; [applyMergeOutcome] é a única que mexe no Compose. */
    private sealed interface MergeOutcome {
        /** Não abriu com a chave da sessão: pede a senha do outro cofre (os bytes ficam para o passo da senha). */
        data class NeedsPassword(val name: String, val bytes: ByteArray) : MergeOutcome

        /** A junção foi calculada e cabe no teto: o resumo pode abrir. */
        data class Ready(
            val name: String,
            val merged: Vault,
            val other: Vault,
            val baseJson: String,
            val counts: MergeOtherCounts,
            val nothingChanges: Boolean,
            /** O cofre mudou depois do resumo anterior: o diálogo avisa que as contagens são novas. */
            val changed: Boolean = false,
        ) : MergeOutcome

        /** Senha errada: o diálogo continua com o motivo e a pessoa tenta de novo. */
        data class WrongPassword(val message: String) : MergeOutcome

        /** Recusa ou falha: fecha a junção e mostra o erro traduzido. */
        data class Failed(val message: String) : MergeOutcome
    }

    /** A leitura do outro arquivo com teto (provedor que não declara o tamanho). */
    private sealed interface CappedRead {
        data class Bytes(val bytes: ByteArray) : CappedRead
        data object TooBig : CappedRead
        data object Missing : CappedRead
        /** O fluxo falhou (IO/permissão): o motivo real vai para a mensagem, não "arquivo não encontrado". */
        data class Failed(val cause: Throwable) : CappedRead
    }

    /**
     * Outro `.tkeys` escolhido no SAF. O teto é conferido ANTES de ler — pelo tamanho declarado pelo provedor
     * (`OpenableColumns.SIZE`); se ele não declarar, a leitura é limitada ao que ainda cabe. Assim um arquivo grande
     * escolhido por engano no seletor é recusado sem alocar os bytes. Leitura, abertura e junção rodam fora da main;
     * a etapa da tela é aplicada numa passada só em [applyMergeOutcome].
     */
    fun onMergeOtherChosen(uri: Uri) {
        if (uri.toString() == _state.value.vaultUri) {
            _state.update { it.copy(error = str(R.string.merge_other_same_file)) }
            return
        }
        if (!mergeOther.begin()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = runCatching { openAndPrepare(uri) }.getOrElse { MergeOutcome.Failed(mergeOpenFailure(it)) }
            withContext(Dispatchers.Main) { applyMergeOutcome(outcome) }
        }
    }

    /**
     * Lê o outro arquivo dentro do teto e tenta abri-lo com a chave deste cofre. Roda fora da main e não toca em
     * estado de tela.
     */
    private suspend fun openAndPrepare(uri: Uri): MergeOutcome {
        val resolver = getApplication<Application>().contentResolver
        val name = withContext(Dispatchers.IO) { displayName(resolver, uri) } ?: RecentVaults.fallbackName(uri.toString())
        val declared = withContext(Dispatchers.IO) { documentSize(resolver, uri) }
        // Mesmo com tamanho declarado, a leitura é limitada ao que ainda cabe: provedor que mente ou arquivo que
        // cresce entre a consulta e a leitura não chega a alocar além do teto.
        val read = when (val check = MergeOtherLimit.precheck(vaultBytesForMerge(resolver), declared)) {
            MergeOtherPrecheck.UnknownVaultSize -> return MergeOutcome.Failed(str(R.string.merge_other_size_unknown))
            MergeOtherPrecheck.TooBig -> return MergeOutcome.Failed(str(R.string.merge_other_too_big))
            is MergeOtherPrecheck.ReadWhole ->
                withContext(Dispatchers.IO) { readDocumentBytesCapped(resolver, uri, MergeOtherLimit.MAX_BYTES - check.vaultBytes, declared) }
            is MergeOtherPrecheck.ReadCapped -> withContext(Dispatchers.IO) { readDocumentBytesCapped(resolver, uri, check.cap, null) }
        }
        val bytes = when (read) {
            is CappedRead.Bytes -> read.bytes
            CappedRead.TooBig -> return MergeOutcome.Failed(str(R.string.merge_other_too_big))
            CappedRead.Missing ->
                return MergeOutcome.Failed(str(R.string.merge_other_open_failed, str(R.string.err_file_not_found)))
            is CappedRead.Failed -> return MergeOutcome.Failed(mergeOpenFailure(read.cause))
        }
        val other = withContext(Dispatchers.Default) { repository.openOtherWithSessionKey(bytes) }
            ?: return MergeOutcome.NeedsPassword(name, bytes)
        return prepareMerge(name, other)
    }

    /**
     * Senha do outro cofre (quando a chave deste não abriu): o Argon2id roda fora da thread principal e a senha não
     * fica guardada. Só a senha errada ([TkeysError.Decrypt]) mantém o diálogo; arquivo corrompido ou que não é um
     * cofre fecha com o erro traduzido.
     */
    fun onMergeOtherPassword(password: String) {
        if (password.isBlank()) return
        // A etapa é conferida ANTES de `startPassword`: se o fluxo não estiver pedindo a senha, nada trava `working`.
        val stage = mergeOther.stage as? MergeOtherStage.NeedsPassword ?: return
        val bytes = mergeOtherBytes ?: return
        if (!mergeOther.startPassword()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = try {
                prepareMerge(stage.name, repository.openOtherWithPassword(bytes, password))
            } catch (e: TkeysError) {
                if (isWrongPassword(e)) MergeOutcome.WrongPassword(tkeysErrorMessage(e)) else MergeOutcome.Failed(tkeysErrorMessage(e))
            } catch (e: Exception) {
                MergeOutcome.Failed(mergeOpenFailure(e))
            }
            withContext(Dispatchers.Main) { applyMergeOutcome(outcome) }
        }
    }

    /**
     * A junção é calculada pela ponte (regra do núcleo) fora da thread principal; o resumo só aparece se o resultado
     * couber no teto. Nada é gravado aqui.
     */
    private fun prepareMerge(name: String, other: VaultRepository.OtherCopy): MergeOutcome {
        val baseJson = repository.vault.toJson()
        val result = mergeOtherVault(repository.vault, other.vault)
        val mergedJson = result.merged.toJson()
        if (MergeOtherLimit.resultOverLimit(MergeOtherLimit.utf8Size(mergedJson))) {
            return MergeOutcome.Failed(str(R.string.merge_other_result_too_big))
        }
        val nothing = JsonEq.same(JSONObject(mergedJson), JSONObject(baseJson))
        return MergeOutcome.Ready(name, result.merged, other.vault, baseJson, MergeOtherCounts.of(result), nothing)
    }

    /** Aplica a etapa da junção na main — o único lugar que escreve no estado Compose do fluxo. */
    private fun applyMergeOutcome(outcome: MergeOutcome) {
        when (outcome) {
            is MergeOutcome.NeedsPassword -> {
                mergeOtherBytes = outcome.bytes
                mergeOtherSource = null
                mergeMerged = null
                mergeBaseJson = null
                _state.update { it.copy(busy = false) }
                mergeOther.askPassword(outcome.name)
            }
            is MergeOutcome.WrongPassword -> {
                _state.update { it.copy(busy = false) }
                mergeOther.passwordRejected(outcome.message)
            }
            is MergeOutcome.Ready -> {
                mergeOtherBytes = null // a senha já foi usada (ou a chave da sessão serviu)
                mergeOtherSource = outcome.other
                mergeMerged = outcome.merged
                mergeBaseJson = outcome.baseJson
                _state.update { it.copy(busy = false) }
                mergeOther.ready(outcome.name, outcome.counts, outcome.nothingChanges, outcome.changed)
            }
            is MergeOutcome.Failed -> {
                clearMergeOther()
                _state.update { it.copy(busy = false, error = outcome.message) }
            }
        }
    }

    /** O que fazer ao confirmar: gravar o plano, reapresentar o resumo (o cofre mudou) ou recusar. */
    private sealed interface MergeApplyPlan {
        data class Write(val vault: Vault) : MergeApplyPlan
        data class Present(val outcome: MergeOutcome.Ready) : MergeApplyPlan
        data class Refused(val message: String) : MergeApplyPlan
    }

    /**
     * Confirmou o resumo: o cofre aberto recebe a junção e é gravado UMA vez ([persistAndCommit], com a conferência de
     * sempre); o outro arquivo não é tocado. Se o cofre mudou desde o resumo (autofill/passkey gravou no meio), a
     * junção é REFEITA e o resumo VOLTA com as contagens novas — nada é gravado até um novo confirmar, para o usuário
     * nunca gravar números que não viu.
     */
    fun confirmMergeOther() {
        val name = (mergeOther.stage as? MergeOtherStage.Ready)?.name ?: return
        if (!mergeOther.startApply()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val plan = try {
                mergeForApply(name)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    clearMergeOther()
                    failSave(e, R.string.op_merge)
                }
                return@launch
            }
            when (plan) {
                is MergeApplyPlan.Refused -> withContext(Dispatchers.Main) {
                    clearMergeOther()
                    _state.update { it.copy(busy = false, error = plan.message) }
                }
                is MergeApplyPlan.Present -> withContext(Dispatchers.Main) { applyMergeOutcome(plan.outcome) }
                is MergeApplyPlan.Write -> {
                    try {
                        persistAndCommit(plan.vault, notice = null)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            clearMergeOther()
                            failSave(e, R.string.op_merge)
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        clearMergeOther()
                        selection.clear()
                        _state.update { it.copy(busy = false, notice = str(R.string.merge_other_done, name)) }
                    }
                }
            }
        }
    }

    /**
     * A junção a gravar. Cofre aberto igual ao do resumo → o plano calculado; mudou → a junção é refeita sobre o
     * conteúdo de agora e volta como resumo novo (`changed`), não como gravação.
     */
    private fun mergeForApply(name: String): MergeApplyPlan {
        val currentJson = repository.vault.toJson()
        val base = mergeBaseJson
        val merged = mergeMerged
        if (base != null && merged != null && confirmAction(MergeOtherFreshness.isStale(base, currentJson)) == MergeConfirmAction.WritePlan) {
            return MergeApplyPlan.Write(merged)
        }
        val other = mergeOtherSource ?: return MergeApplyPlan.Refused(str(R.string.merge_other_changed))
        val result = mergeOtherVault(repository.vault, other)
        val mergedJson = result.merged.toJson()
        if (MergeOtherLimit.resultOverLimit(MergeOtherLimit.utf8Size(mergedJson))) {
            return MergeApplyPlan.Refused(str(R.string.merge_other_result_too_big))
        }
        val nothing = JsonEq.same(JSONObject(mergedJson), JSONObject(currentJson))
        return MergeApplyPlan.Present(
            MergeOutcome.Ready(name, result.merged, other, currentJson, MergeOtherCounts.of(result), nothing, changed = true),
        )
    }

    /** Cancelou (ou fechou o resumo): nada é gravado. */
    fun cancelMergeOther() {
        clearMergeOther()
        _state.update { it.copy(busy = false) }
    }

    private fun clearMergeOther() {
        mergeOtherBytes = null
        mergeOtherSource = null
        mergeMerged = null
        mergeBaseJson = null
        mergeOther.close()
    }

    /** O tamanho do `.tkeys` aberto: o blob conhecido DESTE cofre; senão, o tamanho do documento aberto; nada = null. */
    private fun vaultBytesForMerge(resolver: ContentResolver): Long? =
        if (session.isFor(_state.value.vaultUri)) {
            session.known?.size?.toLong()
        } else {
            _state.value.vaultUri?.let { documentSize(resolver, Uri.parse(it)) }
        }

    /** O `SIZE` que o provedor declara para o documento, ou null quando ele não informa (inclusive o -1). */
    private fun documentSize(resolver: ContentResolver, uri: Uri): Long? = declaredDocumentSize(
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull(),
    )

    /** Lê o documento com teto, sem Android: [CappedStream] aloca o declarado (limitado ao teto), não o BAOS. */
    private fun readDocumentBytesCapped(resolver: ContentResolver, document: Uri, cap: Long, declared: Long?): CappedRead {
        val input = try {
            resolver.openInputStream(document) ?: return CappedRead.Missing
        } catch (e: Exception) {
            return CappedRead.Failed(e)
        }
        return input.use { stream ->
            when (val result = CappedStream.read(stream, cap, declared)) {
                is CappedStream.Result.Bytes -> CappedRead.Bytes(result.bytes)
                CappedStream.Result.TooBig -> CappedRead.TooBig
                is CappedStream.Result.Failed -> CappedRead.Failed(result.cause)
            }
        }
    }

    /** O texto de uma falha ao abrir/juntar o outro cofre (a parte variável vem traduzida). */
    private fun mergeOpenFailure(e: Throwable): String {
        val detail = (e as? TkeysError)?.let(::tkeysErrorMessage) ?: e.message?.takeIf { it.isNotBlank() } ?: describeCause(e)
        return str(R.string.merge_other_open_failed, detail)
    }

    // ── Exportar ─────────────────────────────────────────────────────────

    /** "json" ou "csv": monta o conteúdo em claro e guarda até o SAF responder. */
    fun exportRequested(format: String) {
        try {
            pendingExport = when (format) {
                "csv" -> VaultExporter.toCsv(repository.vault)
                else -> VaultExporter.toJson(repository.vault)
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = str(R.string.msg_export_prepare_failed, e.message ?: "")) }
        }
    }

    /** Documento escolhido no SAF: grava o export em claro nele. */
    fun onExportUriChosen(uri: Uri) {
        val content = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                writeDocument(getApplication<Application>().contentResolver, uri, content.toByteArray(Charsets.UTF_8))
                _state.update { it.copy(notice = str(R.string.msg_export_saved)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.msg_export_save_failed, describeCause(e))) }
            }
        }
    }

    // ── Exportar a seleção para um cofre NOVO (unidade 6) ────────────────

    /** O "criar" do sistema devolveu o arquivo: o diálogo das senhas abre com o destino (o nome do cofre) à mostra. */
    fun exportVaultUriChosen(uri: Uri) {
        if (_state.value.vault == null || _state.value.busy || _state.value.exportVault != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(getApplication<Application>().contentResolver, uri)
            // O cofre pode ter trancado enquanto o seletor estava aberto: aí o destino não vale mais.
            if (_state.value.vault == null) return@launch
            _state.update { it.copy(exportVault = PendingExportVault(uri.toString(), name), exportVaultError = null) }
        }
    }

    /** Fechou o diálogo sem criar: o documento que o "criar" do sistema já criou (vazio) é apagado. */
    fun cancelExportVault() {
        val pending = _state.value.exportVault
        _state.update { it.copy(exportVault = null, exportVaultError = null) }
        pending?.let { discardDocument(it.uri) }
    }

    /**
     * Apaga um documento que o "criar" do sistema deixou **vazio** porque nada foi gravado nele. Só quando VAZIO: o
     * gerenciador pode devolver um arquivo que já existia ("substituir"), e um arquivo do usuário nunca é apagado
     * aqui. Melhor esforço: falha de exclusão é ignorada e não muda o fluxo.
     *
     * NÃO chamar depois que a gravação começou: um provedor pode ter escrito um cofre válido e falhado só na
     * releitura; apagar aí destruiria o arquivo bom.
     */
    private fun discardDocument(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val document = Uri.parse(uri)
            val declared = documentSize(resolver, document)
            if (!EmptyDocument.isDiscardable(declared) { documentFirstRead(resolver, document) }) return@launch
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
        }
    }

    /** "Vazio ou não" sem baixar o arquivo: 0 bytes = vazio, 1 = tem conteúdo, null = não deu para ler. */
    private fun documentFirstRead(resolver: ContentResolver, document: Uri): Long? = try {
        resolver.openInputStream(document)?.use { input -> if (input.read() < 0) 0L else 1L }
    } catch (_: Exception) {
        null
    }

    /**
     * Cria o cofre novo com a [password] (a [confirmation] só é comparada): a regra do núcleo e a cifra rodam fora
     * da thread principal; a gravação vai para IO. O cofre aberto e a seleção não mudam — a exportação só lê.
     */
    fun exportVault(password: String, confirmation: String) {
        val pending = _state.value.exportVault ?: return
        if (_state.value.busy) return
        when (exportPasswordProblem(password, confirmation)) {
            ExportPasswordProblem.Empty -> {
                _state.update { it.copy(exportVaultError = str(R.string.export_vault_password_empty)) }
                return
            }
            ExportPasswordProblem.Mismatch -> {
                _state.update { it.copy(exportVaultError = str(R.string.create_mismatch)) }
                return
            }
            null -> Unit
        }
        val ids = selection.ids()
        // Fecha o diálogo e marca o trabalho JÁ na thread da UI: um segundo toque não dispara outro cofre.
        _state.update { it.copy(busy = true, exportVault = null, exportVaultError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val file = try {
                exporter.build(repository.vault, ids, password)
            } catch (e: Exception) {
                // Nada foi gravado no documento: o vazio que o "criar" deixou não fica no disco.
                discardDocument(pending.uri)
                val detail = when (e) {
                    // A recusa do núcleo (seleção vazia ou id que sumiu do cofre) já tem o texto traduzido.
                    is TkeysError -> tkeysErrorMessage(e)
                    else -> str(R.string.msg_export_vault_failed, describeCause(e))
                }
                _state.update { it.copy(busy = false, error = detail) }
                return@launch
            }
            try {
                val resolver = getApplication<Application>().contentResolver
                withContext(Dispatchers.IO) {
                    exporter.write(ResolverDocument(resolver, Uri.parse(pending.uri)), file)
                }
                _state.update {
                    it.copy(busy = false, notice = str(R.string.msg_export_vault_saved, pending.name ?: pending.uri))
                }
            } catch (e: Exception) {
                // A gravação pode ter começado — inclusive ter gravado um cofre válido e falhado só na releitura:
                // o documento NÃO é apagado aqui.
                val detail = when (e) {
                    is VaultExport.Failure.Save -> str(R.string.msg_export_vault_failed, describeCause(e.cause ?: e))
                    else -> str(R.string.msg_export_vault_failed, describeCause(e))
                }
                _state.update { it.copy(busy = false, error = detail) }
            }
        }
    }

    // ── Travar / limpar ──────────────────────────────────────────────────

    fun lock() {
        // Trancar também fecha o modo de seleção: o cofre que volta não tem os ids marcados.
        selection.clear()
        // A junção em andamento guarda o outro cofre decifrado: trancar descarta.
        clearMergeOther()
        // Só a sessão sai: o conteúdo conhecido FICA, é o ciphertext do próximo destrancar (o `vaultUri` não re-semeia).
        session.lock()
        syncAutofillCache(null)
        _state.update { it.copy(vault = null, screen = Screen.Unlock, exportVault = null, exportVaultError = null) }
    }

    /** Tema do app ("system", "light" ou "dark"), gravado nas preferências. */
    fun setThemeMode(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
