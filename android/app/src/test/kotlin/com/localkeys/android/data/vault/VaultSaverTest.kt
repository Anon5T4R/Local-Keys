package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.store.DocumentIo
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
 * A gravação conferida do cofre ([VaultSaver]), na JVM com a lib do host: o candidato do lote confere **contra ele
 * mesmo** (não contra o cofre que ainda está em memória), só é adotado depois do arquivo reabrir e o conteúdo
 * conhecido acompanha o que foi gravado. Uma gravação que falha não adota nada e não contamina o conhecido.
 */
class VaultSaverTest {

    private val password = "senha-do-teste"
    private val now = 1_700_000_000_000L
    private val crypto = TkeysCrypto()
    private val repository = VaultRepository(crypto)
    private val saver = VaultSaver(repository)
    private lateinit var initialFile: ByteArray
    private lateinit var document: MemoryDocument

    private fun item(id: String) = Item(
        id = id, kind = ItemKind.NOTE, name = "Item $id", favorite = false, folderId = null, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private fun setup() {
        initialFile = repository.create(Vault(version = 1, folders = emptyList(), items = listOf(item("a"), item("b"))), password)
        document = MemoryDocument(initialFile)
        saver.seed(initialFile)
    }

    /** Documento em memória — o mesmo [DocumentIo] que o SAF implementa no app. */
    private class MemoryDocument(var stored: ByteArray?) : DocumentIo {
        var writeFailure: Throwable? = null

        override fun openOutput(mode: String): OutputStream? {
            writeFailure?.let { throw it }
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
    fun o_lote_grava_confere_contra_o_candidato_e_so_entao_adota() {
        setup()
        val before = repository.vault
        val candidate = repository.trashSelected(listOf("a"), now).vault
        assertNull("o candidato ainda não foi adotado: a memória é o cofre de antes", repository.vault.items.first { it.id == "a" }.deletedAt)

        val committed = saver.saveAndCommit(candidate, document)

        assertEquals("o cofre gravado é o candidato", candidate, committed)
        assertEquals("a memória foi adotada", candidate, repository.vault)
        assertTrue("o arquivo mudou", !document.stored!!.contentEquals(before.toJson().toByteArray()))
        val reopened = Vault.parse(String(crypto.openVault(password, document.stored!!).plaintext))
        assertEquals("o arquivo tem o lote", now, reopened.items.first { it.id == "a" }.deletedAt)
        assertArrayEquals("o conteúdo conhecido acompanha o arquivo", document.stored, saver.known)
    }

    @Test
    fun uma_gravacao_que_falha_nao_adota_nem_contamina_o_conhecido() {
        setup()
        val before = repository.vault
        val candidate = repository.trashSelected(listOf("a"), now).vault
        document.writeFailure = IOException("provider refused")

        val failure = assertThrows(VaultSaver.SaveFailure.WriteFailed::class.java) {
            saver.saveAndCommit(candidate, document)
        }

        assertTrue("as tentativas do provedor vêm na falha: ${failure.attempts}", failure.attempts.isNotEmpty())
        assertEquals("nada foi adotado", before, repository.vault)
        assertArrayEquals("o conteúdo conhecido não muda", initialFile, saver.known)
        assertArrayEquals("o documento não foi alterado", initialFile, document.stored)
    }

    @Test
    fun a_edicao_comum_confere_contra_o_cofre_que_ja_mudou_em_memoria() {
        setup()
        val updated = repository.toggleFavorite("a")

        val committed = saver.saveAndCommit(updated, document)

        assertEquals(updated, committed)
        assertEquals(updated, repository.vault)
        val reopened = Vault.parse(String(crypto.openVault(password, document.stored!!).plaintext))
        assertTrue("o arquivo tem a edição", reopened.items.first { it.id == "a" }.favorite)
    }
}
