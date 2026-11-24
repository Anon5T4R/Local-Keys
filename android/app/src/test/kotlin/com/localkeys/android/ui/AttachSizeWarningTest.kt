package com.localkeys.android.ui

import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.PendingAttachment
import com.localkeys.android.data.vault.VaultSizeLimit
import com.localkeys.android.data.vault.gateAttachment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fiação do aviso de tamanho ao anexar (unidade 8): a decisão (`gateAttachment`) e a aplicação no estado
 * (`applyAttachment`) — item novo conta os pendentes, item existente não conta duas vezes, "Cancelar" não guarda e
 * "Continuar" guarda. Os botões do diálogo em si (Compose) ficam para o emulador.
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
        val gate = gateAttachment(18L * 1024 * 1024, state.pendingAttachmentBytes, attachment(1L * 1024 * 1024))

        assertTrue("o aviso aparece", gate is PendingAttachment.Confirm)
        assertTrue((gate as PendingAttachment.Confirm).predictedBytes > VaultSizeLimit.MAX_BYTES)
    }

    @Test
    fun `an existing item does not count its own attachments twice`() {
        val state = ItemEditorState(savedItem(5L * 1024 * 1024))
        assertFalse(state.isNew)
        assertTrue("o anexo salvo já está no arquivo", state.pendingAttachmentBytes.isEmpty())

        // O arquivo aberto já tem o anexo de 5 MiB; só o novo entra na conta (19 MiB + 100 B não passa dos 20 MiB).
        assertNull(VaultSizeLimit.attachWarning(19L * 1024 * 1024, state.pendingAttachmentBytes, 100))
        assertTrue(gateAttachment(19L * 1024 * 1024, state.pendingAttachmentBytes, attachment(100)) is PendingAttachment.Ready)
    }

    @Test
    fun `an attachment added to a saved item in this session is pending`() {
        val state = ItemEditorState(savedItem(5L * 1024 * 1024))
        state.attachments += attachment(1L * 1024 * 1024)

        assertEquals("só o que foi adicionado nesta sessão conta", listOf(1L * 1024 * 1024), state.pendingAttachmentBytes)
        assertTrue(
            "18 MiB + dois pendentes de 1 MiB passam de 20 MiB",
            gateAttachment(18L * 1024 * 1024, state.pendingAttachmentBytes, attachment(1L * 1024 * 1024)) is PendingAttachment.Confirm,
        )
    }

    @Test
    fun `cancelling leaves the attachment out and continuing adds it`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        val read = attachment(1L * 1024 * 1024)

        val gate = gateAttachment(25L * 1024 * 1024, emptyList(), read)
        assertTrue(gate is PendingAttachment.Confirm)

        state.applyAttachment(gate, proceed = false)
        assertEquals("Cancelar não guarda", 0, state.attachments.size)

        state.applyAttachment(gate, proceed = true)
        assertEquals("Continuar guarda", 1, state.attachments.size)
        assertEquals(read, state.attachments.first())
    }

    @Test
    fun `below the cap the attachment is applied without the warning`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        val read = attachment(1024)

        val gate = gateAttachment(18L * 1024 * 1024, emptyList(), read)
        assertTrue(gate is PendingAttachment.Ready)
        state.applyAttachment(gate, proceed = true)
        assertEquals(1, state.attachments.size)
    }
}
