package com.localkeys.android.ui

import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.PendingAttachment
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.data.vault.VaultRepository
import com.localkeys.android.data.vault.gateAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O caminho do editor até o cofre para um ANEXO novo: a decisão do aviso guarda o anexo no estado do editor, o
 * `build()` o leva no item e o repositório o mantém. É o "dado íntegro" do achado da tela de detalhe (a lista de
 * anexos que não atualizava logo depois de salvar); o item salvo tem de sair daqui com o anexo.
 */
class ItemEditorSaveTest {

    private fun item() = Item(
        id = "i1", kind = ItemKind.NOTE, name = "Nota", favorite = false, folderId = null, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private fun attachment() = Attachment(id = "a1", name = "nota.txt", size = 3, mime = "text/plain", dataB64 = "YWJj")

    @Test
    fun `the item saved by the editor keeps the attachment added in this session`() {
        val crypto = TkeysCrypto()
        val repository = VaultRepository(crypto)
        repository.create(Vault(version = 1, folders = emptyList(), items = listOf(item())), "senha")

        val state = ItemEditorState(repository.vault.items.first())
        val gate = gateAttachment(vaultBytes = 0, pendingRawBytes = emptyList(), attachment())
        assertTrue("um anexo pequeno num cofre vazio cabe", gate is PendingAttachment.Ready)
        state.applyAttachment(gate, proceed = true)

        val built = state.build(now = 10)
        assertEquals("o build leva o anexo", listOf("a1"), built.attachments?.map { it.id })

        repository.updateItem(built)
        assertEquals("o cofre em memória fica com o anexo", listOf("a1"), repository.vault.items.first().liveAttachments.map { it.id })
        assertEquals(
            "e o JSON gravado também",
            listOf("a1"),
            Vault.parse(repository.vault.toJson()).items.first().liveAttachments.map { it.id },
        )
    }
}
