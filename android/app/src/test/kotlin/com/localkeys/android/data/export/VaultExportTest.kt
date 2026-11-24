package com.localkeys.android.data.export

import com.localkeys.android.data.crypto.KdfParams
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.store.DocumentIo
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * O cofre NOVO da seleção ([VaultExport]), na JVM com a lib do host: os bytes gerados reabrem com a senha, levam
 * exatamente os itens escolhidos (com o anexo junto) e só as pastas que eles usam; senha trocada não abre e as
 * recusas do núcleo chegam tipadas. A senha vazia/diferente é a função pura [exportPasswordProblem].
 */
class VaultExportTest {

    private val password = "senha-do-cofre-novo"
    private val light = KdfParams(mCostKib = 512, tCost = 1, pCost = 1)
    private val exporter = VaultExport()

    private fun item(id: String, folderId: String? = null, withAttachment: Boolean = false) = Item(
        id = id, kind = ItemKind.NOTE, name = "Item $id", favorite = false, folderId = folderId, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null,
        attachments = if (withAttachment) {
            listOf(Attachment(id = "att-$id", name = "nota.txt", size = 2, mime = "text/plain", dataB64 = "aGk="))
        } else {
            null
        },
    )

    private val work = Folder("work", "Work")
    private val home = Folder("home", "Home")
    private val vault = Vault(
        version = 1,
        folders = listOf(work, home),
        items = listOf(item("a", folderId = "work", withAttachment = true), item("b", folderId = "home"), item("c")),
    )

    private fun idsOf(json: JSONObject, key: String): List<String> {
        val array = json.getJSONArray(key)
        return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
    }

    /** Documento em memória (o mesmo [DocumentIo] que o SAF implementa) com falha opcional. */
    private class MemoryDocument(private val failure: Throwable? = null) : DocumentIo {
        var stored: ByteArray? = null

        override fun openOutput(mode: String): OutputStream? {
            failure?.let { throw it }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    stored = toByteArray()
                    super.close()
                }
            }
        }

        override fun openInput(): InputStream? = stored?.inputStream()
    }

    @Test
    fun o_cofre_novo_reabre_com_a_senha_e_leva_so_a_selecao_e_a_pasta_usada() {
        val file = exporter.build(vault, listOf("a", "c"), password, light)

        val opened = TkeysCrypto().openVault(password, file)
        val json = JSONObject(String(opened.plaintext))
        assertEquals("só os itens escolhidos, na ordem", listOf("a", "c"), idsOf(json, "items"))
        assertEquals("só a pasta que um item usa", listOf("work"), idsOf(json, "folders"))
        assertEquals(1, json.getInt("version"))
        val attachments = json.getJSONArray("items").getJSONObject(0).getJSONArray("attachments")
        assertEquals("o anexo vai junto", 1, attachments.length())
        assertEquals("nota.txt", attachments.getJSONObject(0).getString("name"))
    }

    @Test
    fun a_senha_trocada_nao_abre_o_cofre_novo() {
        val file = exporter.build(vault, listOf("a"), password, light)

        assertThrows(TkeysError.Decrypt::class.java) { TkeysCrypto().openVault("outra-senha", file) }
    }

    @Test
    fun selecao_vazia_e_id_inexistente_sao_a_recusa_tipada_do_nucleo() {
        assertThrows(TkeysError.CannotExport::class.java) { exporter.build(vault, emptyList(), password, light) }
        assertThrows(TkeysError.CannotExport::class.java) { exporter.build(vault, listOf("a", "fantasma"), password, light) }
    }

    @Test
    fun a_senha_vazia_ou_diferente_bloqueia() {
        assertEquals(ExportPasswordProblem.Empty, exportPasswordProblem("", ""))
        assertEquals(ExportPasswordProblem.Empty, exportPasswordProblem("", "senha"))
        assertEquals(ExportPasswordProblem.Mismatch, exportPasswordProblem("uma", "outra"))
        assertNull("senha igual e não vazia passa", exportPasswordProblem("senha", "senha"))
    }

    @Test
    fun a_gravacao_no_documento_escreve_os_bytes_do_cofre_novo() {
        val file = exporter.build(vault, listOf("a"), password, light)
        val document = MemoryDocument()

        exporter.write(document, file)

        assertArrayEquals(file, document.stored)
    }

    @Test
    fun falha_de_gravacao_vira_erro_tipado() {
        val document = MemoryDocument(IOException("provider refused"))

        val failure = assertThrows(VaultExport.Failure.Save::class.java) { exporter.write(document, byteArrayOf(1, 2)) }

        assertTrue("a causa do provedor viaja: ${failure.cause}", failure.cause is IOException)
    }
}
