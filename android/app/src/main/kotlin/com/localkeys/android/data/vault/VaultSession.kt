package com.localkeys.android.data.vault

import com.localkeys.android.data.store.DocumentIo

/**
 * A vida do cofre que o `VaultViewModel` delega e que roda na JVM sem Android: carregar o arquivo, destrancar (senha
 * ou biometria), trancar e criar no documento que o seletor acabou de devolver.
 *
 * O conteúdo CONHECIDO ([VaultSaver.known]) é o ciphertext do cofre aberto: é com ele que o `unlock` trabalha e é ele
 * que detecta mudança externa antes de gravar. **Trancar NÃO pode zerá-lo** — o fluxo do `vaultUri` tem
 * `distinctUntilChanged` e nada re-semeia o blob, então zerar no lock deixava o cofre impossível de reabrir. Uma
 * criação que falhe também não pode mexer nele: o cofre anterior continua destrancável.
 */
class VaultSession(private val repository: VaultRepository, private val saver: VaultSaver) {

    /** O ciphertext conhecido (o último arquivo lido/gravado); null só antes de carregar algum cofre. */
    val known: ByteArray? get() = saver.known

    /** Guarda o arquivo lido do disco como o conteúdo conhecido (o "carregar" do ViewModel). */
    fun load(file: ByteArray) {
        saver.seed(file)
    }

    /** Esquece o cofre carregado (troca de cofre/falha de leitura): o próximo destrancar não tem blob. */
    fun forget() {
        saver.seed(null)
    }

    /** Destranca com a senha sobre os bytes conhecidos (o Argon2id roda no chamador, fora da main). */
    fun unlock(password: String): Vault =
        repository.unlock(known ?: throw IllegalStateException("nenhum cofre carregado"), password)

    /** Destranca com a chave bruta (biometria) sobre os bytes conhecidos. */
    fun unlockWithKey(key: ByteArray): Vault =
        repository.unlockWithKey(key, known ?: throw IllegalStateException("nenhum cofre carregado"))

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
     * cofre ANTERIOR não pode bloquear a primeira gravação do novo.
     */
    fun writeCreated(file: ByteArray, document: DocumentIo) {
        saver.write(file, repository.vault, document, force = true)
    }

    /** Relê/adota o arquivo do disco com a sessão atual (o reload do conflito externo) e o guarda como conhecido. */
    fun reload(file: ByteArray): Vault {
        val vault = repository.reload(file)
        saver.seed(file)
        return vault
    }
}
