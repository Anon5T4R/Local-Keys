package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.crypto.subsetVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exportar a seleção para um cofre novo (unidade 6 do pedido 027), com a lib do host: a regra é a do núcleo, pela
 * ponte, e recebe só os itens selecionados e as pastas do cofre (o cofre inteiro custaria memória num cofre grande).
 *
 * O esperado é um caso FIXO, escrito à mão — o mesmo de `crates/ffi/src/lib.rs` (exportar_a_selecao_devolve_o_cofre_novo)
 * e de `crates/core/tests/vault_export.rs`: os itens vão inteiros (o anexo junto), só a pasta referenciada entra e o
 * resultado é o JSON de um `.tkeys` comum (`version` 1).
 */
class SubsetVaultTest {

    private fun noteWithAttachment() = Item(
        id = "a", kind = ItemKind.NOTE, name = "A", favorite = false, folderId = "work", notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null,
        attachments = listOf(
            Attachment(id = "att-1", name = "f.txt", size = 2, mime = "text/plain", dataB64 = "aGk="),
        ),
    )

    private fun loginWithoutFolder() = Item(
        id = "b", kind = ItemKind.LOGIN, name = "B", favorite = true, folderId = null, notes = "n",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = Login(username = "b", password = "p", uris = emptyList(), totp = ""),
        card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    /** O caso fixo: pastas do cofre e os itens SELECIONADOS, na ordem da seleção. */
    private val folders = listOf(Folder("work", "Work"), Folder("home", "Home"))
    private val selected = listOf(noteWithAttachment(), loginWithoutFolder())

    /** O JSON do cofre novo, byte a byte — o mesmo pinado pelo teste da ponte no Rust. */
    private val expected =
        """{"folders":[{"id":"work","name":"Work"}],"items":[{"attachments":[{"dataB64":"aGk=","deletedAt":null,"id":"att-1","mime":"text/plain","name":"f.txt","size":2}],"createdAt":1,"deletedAt":null,"favorite":false,"folderId":"work","id":"a","kind":"note","name":"A","notes":"","updatedAt":2},{"createdAt":1,"deletedAt":null,"favorite":true,"folderId":null,"id":"b","kind":"login","login":{"password":"p","totp":"","uris":[],"username":"b"},"name":"B","notes":"n","updatedAt":2}],"version":1}"""

    @Test
    fun a_selecao_vira_um_cofre_novo_com_os_itens_inteiros_e_so_a_pasta_usada() {
        assertEquals(expected, subsetVault(folders, selected))
    }

    @Test
    fun o_cofre_novo_reabre_como_cofre() {
        val out = Vault.parse(subsetVault(folders, selected))

        assertEquals(1, out.version)
        assertEquals(listOf("work"), out.folders.map { it.id })
        assertEquals(listOf("a", "b"), out.items.map { it.id })
        assertEquals("o anexo vai junto", "f.txt", out.items.first { it.id == "a" }.attachments?.single()?.name)
        assertEquals("b não tem pasta", null, out.items.first { it.id == "b" }.folderId)
    }

    @Test
    fun selecao_vazia_e_recusa() {
        val error = assertThrows(TkeysError.CannotExport::class.java) {
            subsetVault(folders, emptyList())
        }
        assertTrue(error.reason, error.reason.contains("no items selected"))
    }
}
