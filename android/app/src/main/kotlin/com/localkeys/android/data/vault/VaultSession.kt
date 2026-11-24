package com.localkeys.android.data.vault

import com.localkeys.android.data.store.DocumentIo

/**
 * A vida do cofre que o `VaultViewModel` delega e que roda na JVM sem Android: carregar o arquivo, destrancar (senha
 * ou biometria), trancar e criar no documento que o seletor acabou de devolver.
 *
 * O conteúdo CONHECIDO (o ciphertext em [VaultSaver.known]) **sabe de que arquivo é** ([knownUri]): é com ele que o
 * `unlock` trabalha e é ele que detecta mudança externa antes de gravar. Trancar NÃO pode zerá-lo — o fluxo do
 * `vaultUri` não re-semeia o blob — e uma criação que falhe também não mexe nele. Guardar o par (URI, bytes) evita o
 * pior caso: destrancar o arquivo A com o blob de B quando dois `loadBlob` se sobrepõem.
 */
class VaultSession(private val repository: VaultRepository, private val saver: VaultSaver) {

    /** O URI do arquivo cujo ciphertext está em [known]; null antes de carregar algum cofre. */
    @Volatile
    var knownUri: String? = null
        private set

    /** O ciphertext conhecido (o último arquivo lido/gravado); null só antes de carregar algum cofre. */
    val known: ByteArray? get() = saver.known

    /** O cofre carregado corresponde a [uri]? (URI certa E blob presente.) */
    fun isFor(uri: String?): Boolean = uri != null && knownUri == uri && known != null

    /** Guarda o par (URI, bytes) do cofre lido do disco. */
    fun load(uri: String, file: ByteArray) {
        saver.seed(file)
        knownUri = uri
    }

    /** Esquece o cofre carregado (troca de cofre/falha de leitura): o próximo destrancar não tem blob. */
    fun forget() {
        knownUri = null
        saver.seed(null)
    }

    /**
     * Destranca com a senha, exigindo que o conteúdo conhecido seja do [uri] atual. `null` = nenhum cofre carregado
     * (ou o cofre carregado é outro) — o chamador traduz para "nenhum cofre carregado", sem exceção.
     */
    fun unlock(uri: String?, password: String): Vault? {
        if (!isFor(uri)) return null
        val blob = known ?: return null
        return repository.unlock(blob, password)
    }

    /** Destranca com a chave bruta (biometria), com a mesma exigência de URI de [unlock]. */
    fun unlockWithKey(uri: String?, key: ByteArray): Vault? {
        if (!isFor(uri)) return null
        val blob = known ?: return null
        return repository.unlockWithKey(key, blob)
    }

    /** Tranca: o repositório esquece a sessão; o conteúdo conhecido FICA (é o ciphertext do próximo destrancar). */
    fun lock() {
        repository.lock()
    }

    /** Cria e valida o `.tkeys` do cofre novo. Nada é gravado ainda — o chamador separa falha de criação de falha de
     * gravação (só a primeira pode apagar o documento vazio do seletor). */
    fun createFile(vault: Vault, password: String): ByteArray {
        val file = repository.create(vault, password)
        repository.verifySaved(file) // nunca entrega um blob que não reabriria
        return file
    }

    /**
     * Grava o `.tkeys` recém-criado no documento do seletor com `force = true`: o SAF acabou de criar/sobrescrever
     * esse arquivo (o usuário escolheu "substituir"), então não existe mudança externa a proteger e o conhecido do
     * cofre ANTERIOR não pode bloquear a primeira gravação do novo. No sucesso, o cofre novo passa a ser o conhecido
     * DESTE documento: o coletor do `vaultUri` vai ver o mesmo URI e não precisa reler (nem há janela com blob nulo).
     */
    fun writeCreated(uri: String, file: ByteArray, document: DocumentIo) {
        saver.write(file, repository.vault, document, force = true)
        knownUri = uri
    }

    /** Relê/adota o arquivo do disco com a sessão atual (o reload do conflito externo) e guarda o par (URI, bytes). */
    fun reload(uri: String, file: ByteArray): Vault {
        val vault = repository.reload(file)
        saver.seed(file)
        knownUri = uri
        return vault
    }
}
