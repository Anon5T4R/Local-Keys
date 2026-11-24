package com.localkeys.android.ui

import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.VaultSizeLimit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O aviso de tamanho ao anexar (unidade 8) no estado do editor: item novo conta os anexos pendentes, item existente
 * não conta duas vezes os que já estão no arquivo, e o aviso em si não mexe na lista — só o "Continuar" guarda.
 * Os botões do diálogo em si (Compose) ficam para o emulador.
 */
class AttachSizeWarningTest {

    private fun attachment(size: Long, id: String = "nova-$size") = Attachment(id = id, name = "nota.txt", size = size, mime = "text/plain", dataB64 = "YQ==")

    private fun savedItem(size: Long): Item = Item.parse(
        JSONObject().apply {
            put("id", "i1")
            put("kind", "login")
            put("name", "Item salvo")
            put("favorite", false)
            put("createdAt", 1)
            put("updatedAt", 2)
            put("deletedAt", JSONObject.NULL)
            put("attachments", JSONArray().put(attachment(size, id = "salvo").toJson()))
        },
    )

    @Test
    fun `a new item counts the attachments still pending`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        assertTrue(state.isNew)
        state.attachments += attachment(1L * 1024 * 1024)

        // Cofre de 18 MiB + pendente de 1 MiB + o novo de 1 MiB (cada um ~1,33 MiB em base64) passa de 20 MiB.
        val predicted = VaultSizeLimit.attachWarning(18L * 1024 * 1024, state.pendingAttachmentBytes, 1L * 1024 * 1024)

        assertNotNull("o aviso aparece", predicted)
        assertTrue(predicted!! > VaultSizeLimit.MAX_BYTES)
    }

    @Test
    fun `an existing item does not count its own attachments twice`() {
        val state = ItemEditorState(savedItem(5L * 1024 * 1024))
        assertFalse(state.isNew)
        assertTrue("o anexo salvo já está no arquivo", state.pendingAttachmentBytes.isEmpty())

        // O arquivo aberto já tem o anexo de 5 MiB; só o novo entra na conta (19 MiB + 100 B não passa dos 20 MiB).
        assertNull(VaultSizeLimit.attachWarning(19L * 1024 * 1024, state.pendingAttachmentBytes, 100))
    }

    @Test
    fun `an attachment added to a saved item in this session is pending`() {
        val state = ItemEditorState(savedItem(5L * 1024 * 1024))
        state.attachments += attachment(1L * 1024 * 1024)

        assertEquals("só o que foi adicionado nesta sessão conta", listOf(1L * 1024 * 1024), state.pendingAttachmentBytes)
        val predicted = VaultSizeLimit.attachWarning(18L * 1024 * 1024, state.pendingAttachmentBytes, 1L * 1024 * 1024)
        assertNotNull("18 MiB + dois pendentes de 1 MiB passam de 20 MiB", predicted)
    }

    @Test
    fun `cancelling leaves the attachment out and continuing adds it`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        val read = attachment(1L * 1024 * 1024)

        val predicted = VaultSizeLimit.attachWarning(25L * 1024 * 1024, emptyList(), read.size)
        assertNotNull(predicted)
        assertEquals("o aviso não mexe no estado", 0, state.attachments.size)

        // O que o botão "Continuar" faz: guarda o anexo já lido no item.
        state.attachments += read
        assertEquals(1, state.attachments.size)
    }

    @Test
    fun `below the cap a small attachment is added without the warning`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        val read = attachment(1024)

        assertNull(VaultSizeLimit.attachWarning(18L * 1024 * 1024, emptyList(), read.size))
        state.attachments += read
        assertEquals(1, state.attachments.size)
    }
}
