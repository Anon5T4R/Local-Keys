package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O lote da seleção múltipla aplicado ao cofre em memória pelo repositório, com a lib do host (a regra do núcleo
 * pela ponte): apagar vai para a lixeira, mover muda a pasta (e "sem pasta" é operação como as outras), e uma
 * recusa da regra deixa o cofre exatamente como estava. O esperado fica escrito à mão, como no `BatchTest`.
 */
class BatchVaultTest {

    private val crypto = TkeysCrypto()
    private val now = 1_700_000_000_000L
    private val password = "senha-do-teste"

    private fun item(id: String, folderId: String? = null) = Item(
        id = id, kind = ItemKind.NOTE, name = "Item $id", favorite = false, folderId = folderId, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private fun repository(vararg items: Item): VaultRepository {
        val repository = VaultRepository(crypto)
        repository.create(Vault(version = 1, folders = listOf(Folder("work", "Work")), items = items.toList()), password)
        return repository
    }

    /** O que o arquivo (recifrado pelo `save`) tem, relido fora do repositório. */
    private fun reopened(repository: VaultRepository): Vault =
        Vault.parse(String(crypto.openVault(password, repository.save()).plaintext))

    @Test
    fun a_lixeira_em_lote_marca_os_escolhidos_e_deixa_o_resto() {
        val repository = repository(item("a"), item("b"), item("c"))

        val result = repository.trashSelected(listOf("a", "c"), now)

        assertEquals(2, result.changed)
        assertEquals("o cofre em memória é o que a tela lê", result.vault, repository.vault)
        val byId = result.vault.items.associateBy { it.id }
        assertEquals(now, byId.getValue("a").deletedAt)
        assertEquals("o lote carimba os dois campos com o mesmo now", now, byId.getValue("a").updatedAt)
        assertEquals(now, byId.getValue("c").deletedAt)
        assertNull("b não estava na seleção", byId.getValue("b").deletedAt)
        assertEquals(2L, byId.getValue("b").updatedAt)
        assertEquals("a ordem do arquivo não muda", listOf("a", "b", "c"), result.vault.items.map { it.id })

        val saved = reopened(repository).items.associateBy { it.id }
        assertEquals(now, saved.getValue("a").deletedAt)
        assertNull(saved.getValue("b").deletedAt)
    }

    @Test
    fun mover_em_lote_para_uma_pasta_e_de_volta_para_sem_pasta() {
        val repository = repository(item("a"), item("b"), item("c"))

        val moved = repository.moveSelected(listOf("a", "b"), "work", now)
        assertEquals(2, moved.changed)
        assertEquals(listOf("a", "b", "c"), moved.vault.items.map { it.id })
        val byId = moved.vault.items.associateBy { it.id }
        assertEquals("work", byId.getValue("a").folderId)
        assertEquals(now, byId.getValue("a").updatedAt)
        assertEquals("work", byId.getValue("b").folderId)
        assertNull("c não estava na seleção", byId.getValue("c").folderId)

        val cleared = repository.moveSelected(listOf("a"), null, now + 5)
        assertEquals(1, cleared.changed)
        val after = cleared.vault.items.associateBy { it.id }
        assertNull("sem pasta é uma operação válida", after.getValue("a").folderId)
        assertEquals(now + 5, after.getValue("a").updatedAt)
        assertEquals("b fora da seleção ficou como estava", "work", after.getValue("b").folderId)
        assertEquals(now, after.getValue("b").updatedAt)

        val saved = reopened(repository).items.associateBy { it.id }
        assertNull(saved.getValue("a").folderId)
        assertEquals("work", saved.getValue("b").folderId)
    }

    @Test
    fun mover_para_a_pasta_em_que_os_itens_ja_estao_nao_muda_nada() {
        val repository = repository(item("a"), item("b"))
        repository.moveSelected(listOf("a", "b"), "work", now)
        val before = repository.vault

        val result = repository.moveSelected(listOf("a", "b"), "work", now + 99)

        assertEquals("nada mudou: nada a gravar", 0, result.changed)
        assertEquals(before, result.vault)
        assertEquals(before, repository.vault)
        assertEquals("o updatedAt não é recarimbado", now, repository.vault.items.first { it.id == "a" }.updatedAt)
    }

    @Test
    fun o_lote_recusado_nao_muda_o_cofre() {
        val repository = repository(item("a"), item("b"))
        repository.trashSelected(listOf("a"), now)
        val before = repository.vault

        // Um item do lote já está na lixeira: a regra recusa o lote inteiro.
        val alreadyTrashed = assertThrows(TkeysError.InvalidItems::class.java) {
            repository.trashSelected(listOf("b", "a"), now + 1)
        }
        assertTrue("a razão do núcleo chega inteira: ${alreadyTrashed.reason}", alreadyTrashed.reason.contains("already in the trash"))
        assertEquals("o cofre em memória não muda", before, repository.vault)
        assertNull("b não foi para a lixeira", repository.vault.items.first { it.id == "b" }.deletedAt)

        // Um id que não existe mais (o cofre mudou por fora, ou a lista ficou velha).
        assertThrows(TkeysError.InvalidItems::class.java) {
            repository.moveSelected(listOf("fantasma"), "work", now)
        }
        assertEquals(before, repository.vault)

        // A pasta de destino não existe.
        assertThrows(TkeysError.InvalidItems::class.java) {
            repository.moveSelected(listOf("b"), "sumiu", now)
        }
        assertEquals(before, repository.vault)
    }

    @Test
    fun id_repetido_no_lote_conta_uma_vez() {
        val repository = repository(item("a"))
        val result = repository.trashSelected(listOf("a", "a"), now)
        assertEquals(1, result.changed)
    }
}
