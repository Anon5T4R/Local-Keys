package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.OpenedVault
import com.localkeys.android.data.crypto.SessionKey
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError

/**
 * Estado vivo do vault na memória — espelho do `AppState` do desktop (`lib.rs`):
 * guarda só a sessão (chave derivada + salt/params) e o plaintext decifrado.
 * A **master password nunca é retida**; salvar usa `SessionKey.seal`, que sorteia
 * um nonce novo e recifra com a mesma chave.
 */
class VaultRepository(private val crypto: TkeysCrypto) {

    private var session: SessionKey? = null
    private var current: Vault? = null

    /** Está destrancado? (sessão viva + vault em memória) */
    val isUnlocked: Boolean get() = current != null

    /** Vault destrancado — lança se ainda estiver trancado. */
    val vault: Vault
        get() = current ?: throw IllegalStateException("vault não está destrancado")

    /** Abre um `.tkeys` com a master password (roda o Argon2id). */
    fun unlock(file: ByteArray, password: String): Vault {
        val opened = crypto.openVault(password, file)
        return open(opened)
    }

    /**
     * Abre com a **chave bruta** (32 bytes) recuperada do cofre biométrico do
     * SO — sem Argon2id. O salt/params vêm do header do próprio arquivo, então
     * a sessão de salvar fica igual à do desbloqueio por senha.
     */
    fun unlockWithKey(key: ByteArray, file: ByteArray): Vault {
        val opened = crypto.openVaultWithKey(key, file)
        return open(opened)
    }

    private fun open(opened: OpenedVault): Vault {
        val vault = try {
            Vault.parse(String(opened.plaintext))
        } catch (e: Exception) {
            // Decifrou com a senha certa, mas o conteúdo não é um vault válido —
            // arquivo corrompido/truncado, não "senha incorreta".
            throw TkeysError.Corrupted
        }
        session = opened.session
        current = vault
        return vault
    }

    /** Cria um vault novo e já deixa destrancado. Devolve os bytes cifrados. */
    fun create(vault: Vault, password: String): ByteArray {
        val created = crypto.createVault(password, vault.toJson().toByteArray())
        session = created.session
        current = vault
        return created.file
    }

    /** Recifra o vault atual com um nonce novo (mesmo salt/params). */
    fun save(): ByteArray = seal(vault)

    /**
     * Cifra [vault] com a sessão atual, **sem adotá-lo em memória** — o caminho de quem só troca o cofre depois da
     * gravação conferida (o lote da seleção no `VaultViewModel`).
     */
    fun seal(vault: Vault): ByteArray {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        return s.seal(vault.toJson().toByteArray())
    }

    /**
     * Valida um blob recém-cifrado antes de gravar: decifra com a chave da
     * sessão (sem re-rodar o Argon2id) e confere que o conteúdo é exatamente o
     * vault atual. Garante que nunca gravamos um arquivo que não reabriria.
     * Lança [TkeysError.Corrupted] se o blob não reabrir ou divergir do vault.
     */
    fun verifySaved(blob: ByteArray): Vault = verifySaved(blob, vault)

    /** [`verifySaved`] contra um [vault] que ainda não foi adotado em memória (o candidato do lote). */
    fun verifySaved(blob: ByteArray, vault: Vault): Vault {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        val plaintext = try {
            crypto.openWithKey(s.keyBytes(), blob)
        } catch (e: TkeysError) {
            throw TkeysError.Corrupted
        }
        // A decifração é autenticada: se o plaintext não for byte a byte o que
        // o save() selou, o blob foi cifrado com outro conteúdo — nunca gravar.
        if (!plaintext.contentEquals(vault.toJson().toByteArray())) throw TkeysError.Corrupted
        return vault
    }

    /** Cópia da chave derivada (32 bytes) para o cofre biométrico. */
    fun keyBytes(): ByteArray? = session?.keyBytes()

    /**
     * Adota um arquivo modificado externamente (ex.: outro dispositivo
     * sincronizou uma versão nova via OneDrive/Google Drive): decifra com a
     * chave da sessão atual (sem re-rodar o Argon2id) e substitui o vault em
     * memória. Lança [TkeysError.Decrypt] se a senha foi trocada externamente
     * (a chave já não casa — o caminho é travar e reabrir com a senha nova) e
     * [TkeysError.Corrupted] se o arquivo externo está corrompido.
     */
    fun reload(file: ByteArray): Vault {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        val plaintext = crypto.openWithKey(s.keyBytes(), file)
        val vault = try {
            Vault.parse(String(plaintext))
        } catch (e: Exception) {
            throw TkeysError.Corrupted
        }
        current = vault
        return vault
    }

    // ── Outra cópia do cofre (sincronizar) ───────────────────────────────

    /** Uma outra cópia aberta: o conteúdo e a chave dela (para gravar o resultado de volta com o sal dela). */
    class OtherCopy(val vault: Vault, val session: SessionKey)

    /**
     * Tenta abrir outra cópia com a chave da sessão atual (mesmo cofre copiado = mesmo sal = mesma chave).
     * Null se a chave não serve (cópia com outra senha, ou criada à parte com a mesma senha: sal diferente).
     */
    fun openOtherWithSessionKey(file: ByteArray): OtherCopy? {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        val opened = try {
            crypto.openVaultWithKey(s.keyBytes(), file)
        } catch (e: TkeysError.Decrypt) {
            return null
        }
        return OtherCopy(parseOrCorrupted(opened.plaintext), opened.session)
    }

    /** Abre outra cópia com a senha dela (roda o Argon2id). Lança [TkeysError] se não abrir. */
    fun openOtherWithPassword(file: ByteArray, password: String): OtherCopy {
        val opened = crypto.openVault(password, file)
        return OtherCopy(parseOrCorrupted(opened.plaintext), opened.session)
    }

    /** Cifra [vault] para a outra cópia (chave e sal dela). */
    fun sealFor(other: OtherCopy, vault: Vault): ByteArray = other.session.seal(vault.toJson().toByteArray())

    /** O que foi relido da outra cópia reabre com a chave dela e é exatamente [vault]? */
    fun verifyFor(other: OtherCopy, blob: ByteArray, vault: Vault): Boolean = try {
        crypto.openWithKey(other.session.keyBytes(), blob).contentEquals(vault.toJson().toByteArray())
    } catch (e: TkeysError) {
        false
    }

    /** Troca o vault em memória pelo resultado da junção (a gravação é do chamador, como nos outros). */
    fun replace(vault: Vault): Vault {
        if (current == null) throw IllegalStateException("vault não está destrancado")
        current = vault
        return vault
    }

    private fun parseOrCorrupted(plaintext: ByteArray): Vault = try {
        Vault.parse(String(plaintext))
    } catch (e: Exception) {
        throw TkeysError.Corrupted
    }

    /**
     * Anexa itens importados ao vault atual (em memória) e devolve o vault novo.
     * A gravação no arquivo é responsabilidade do chamador (`save`).
     */
    fun appendItems(items: List<Item>): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val merged = v.copy(items = v.items + items)
        current = merged
        return merged
    }

    /** Adiciona um item novo ao vault atual (em memória). */
    fun addItem(item: Item): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(items = v.items + item)
        current = updated
        return updated
    }

    /**
     * Substitui um item existente (mesmo id) no vault atual. Se a senha de um
     * login mudou, guarda a anterior no histórico (mais recente primeiro,
     * cap 20) — mesmo comportamento do `updateItem` do desktop.
     */
    fun updateItem(item: Item): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val prev = v.items.firstOrNull { it.id == item.id }
        var updated = item
        if (
            item.kind == ItemKind.LOGIN &&
            prev?.login != null &&
            item.login != null &&
            prev.login.password.isNotEmpty() &&
            prev.login.password != item.login.password
        ) {
            val history = listOf(PasswordHistoryEntry(prev.login.password, System.currentTimeMillis())) +
                (item.passwordHistory ?: emptyList())
            updated = item.copy(passwordHistory = history.take(20))
        }
        val result = v.copy(items = v.items.map { if (it.id == item.id) updated else it })
        current = result
        return result
    }

    /**
     * Exclui DE VEZ (fora da lixeira): tira o item do array, sem deixar rastro — o `deleteForever` do desktop.
     * A exclusão normal da interface é [trashItem].
     */
    fun deleteItem(id: String): Vault = mutate { v -> v.copy(items = v.items.filterNot { it.id == id }) }

    // ── Lote da seleção múltipla (lixeira e pasta) ───────────────────────
    //
    // A regra é a do núcleo (`batch.rs`, a mesma do desktop) pela ponte: tudo ou nada, um `now` só para o lote e
    // só os itens selecionados atravessam. **Nada é adotado em memória aqui**: o chamador grava o candidato e só
    // depois chama `replace` (o `VaultViewModel.persistAndCommit` faz isso depois do OK da gravação) — uma gravação
    // que falha não deixa o cofre em memória à frente do arquivo.

    /** O que o lote calculou: o cofre candidato (ainda NÃO adotado) e quantos itens mudaram de fato. */
    class BatchResult(val vault: Vault, val changed: Int)

    /**
     * Calcula a lixeira dos [ids] pela regra do núcleo ([com.localkeys.android.data.crypto.trashItems]): tudo ou
     * nada, um `now` só, `deletedAt` e `updatedAt` em todos. Id que não existe mais é recusa
     * ([TkeysError.InvalidItems]), como no desktop. O cofre em memória não muda: quem grava e adota é o chamador.
     */
    fun trashSelected(ids: List<String>, now: Long = System.currentTimeMillis()): BatchResult {
        val currentVault = current ?: throw IllegalStateException("vault não está destrancado")
        val selected = selectedItems(currentVault, ids)
        if (selected.isEmpty()) return BatchResult(currentVault, 0)
        val updated = com.localkeys.android.data.crypto.trashItems(selected, now)
        return BatchResult(replaceSelected(currentVault, updated), updated.size)
    }

    /**
     * Calcula a mudança de pasta dos [ids] para [folderId] (`null` = sem pasta), pela regra do núcleo
     * ([com.localkeys.android.data.crypto.moveItems]) com os ids das pastas que o cofre conhece — pasta de destino
     * inexistente é recusa do núcleo, não uma checagem reescrita aqui. Item que já estava no destino não é
     * recarimbado e não entra na contagem: com `changed == 0` nada há para gravar. O cofre em memória não muda.
     */
    fun moveSelected(ids: List<String>, folderId: String?, now: Long = System.currentTimeMillis()): BatchResult {
        val currentVault = current ?: throw IllegalStateException("vault não está destrancado")
        val selected = selectedItems(currentVault, ids)
        if (selected.isEmpty()) return BatchResult(currentVault, 0)
        val folderIds = currentVault.folders.map { it.id }
        val updated = com.localkeys.android.data.crypto.moveItems(selected, folderId, folderIds, now)
        val changed = updated.zip(selected).count { (after, before) -> after != before }
        if (changed == 0) return BatchResult(currentVault, 0)
        return BatchResult(replaceSelected(currentVault, updated), changed)
    }

    /** Os itens do lote, na ordem pedida e sem repetir id; id que não existe no cofre é a recusa do lote. */
    private fun selectedItems(vault: Vault, ids: List<String>): List<Item> = ids.distinct().map { id ->
        vault.items.firstOrNull { it.id == id } ?: throw TkeysError.InvalidItems("item `$id` does not exist")
    }

    /** O cofre com os itens do lote trocados pelos que a regra devolveu, na ordem do arquivo. */
    private fun replaceSelected(vault: Vault, updated: List<Item>): Vault {
        val byId = updated.associateBy { it.id }
        return vault.copy(items = vault.items.map { byId[it.id] ?: it })
    }

    /**
     * Manda o item para a lixeira: marca `deletedAt` e SÓ ele, como o `trashItem` do oficial e o `delete_login`
     * do desktop (não toca em `updatedAt`). O item continua no arquivo e volta com [restoreItem].
     */
    fun trashItem(id: String, now: Long = System.currentTimeMillis()): Vault =
        mutate { v -> v.copy(items = v.items.map { if (it.id == id) it.copy(deletedAt = now) else it }) }

    /** Restaura da lixeira: zera `deletedAt` e não mexe em mais nada (o `restoreItem` do oficial). */
    fun restoreItem(id: String): Vault =
        mutate { v -> v.copy(items = v.items.map { if (it.id == id) it.copy(deletedAt = null) else it }) }

    /** Itens na lixeira, na ordem do arquivo (como o filtro "trash" do oficial). */
    fun trashedItems(): List<Item> = vault.items.filter { it.inTrash }

    // ── Anexos (lixeira de anexos do desktop) ────────────────────────────

    /** Manda um anexo para a lixeira do item (marca `deletedAt`). */
    fun trashAttachment(itemId: String, attachmentId: String, now: Long = System.currentTimeMillis()): Vault =
        mapAttachment(itemId, attachmentId) { it.copy(deletedAt = now) }

    /** Restaura um anexo da lixeira (limpa `deletedAt`). */
    fun restoreAttachment(itemId: String, attachmentId: String): Vault =
        mapAttachment(itemId, attachmentId) { it.copy(deletedAt = null) }

    /** Exclui um anexo de vez (remove do array do item). */
    fun deleteAttachmentForever(itemId: String, attachmentId: String): Vault = mutate { v ->
        v.copy(
            items = v.items.map { item ->
                if (item.id != itemId) return@map item
                item.copy(attachments = item.attachments?.filterNot { it.id == attachmentId })
            },
        )
    }

    private fun mapAttachment(itemId: String, attachmentId: String, transform: (Attachment) -> Attachment): Vault =
        mutate { v ->
            v.copy(
                items = v.items.map { item ->
                    if (item.id != itemId) return@map item
                    item.copy(attachments = item.attachments?.map { if (it.id == attachmentId) transform(it) else it })
                },
            )
        }

    private fun mutate(transform: (Vault) -> Vault): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = transform(v)
        current = updated
        return updated
    }

    /** Alterna o favorito de um item. */
    fun toggleFavorite(id: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(items = v.items.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
        current = updated
        return updated
    }

    /** Adiciona uma pasta nova ao vault (em memória). */
    fun addFolder(folder: Folder): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(folders = v.folders + folder)
        current = updated
        return updated
    }

    /** Renomeia uma pasta. */
    fun renameFolder(id: String, name: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(folders = v.folders.map { if (it.id == id) it.copy(name = name) else it })
        current = updated
        return updated
    }

    /** Exclui uma pasta e desvincula os itens dela (os itens ficam sem pasta). */
    fun deleteFolder(id: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(
            folders = v.folders.filterNot { it.id == id },
            items = v.items.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
        current = updated
        return updated
    }

    /** Descarta a sessão e o plaintext (travar o cofre). */
    fun lock() {
        session = null
        current = null
    }
}
