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
    fun a_mudanca_externa_bloqueia_a_gravacao_sem_adotar_nem_reescrever() {
        setup()
        val before = repository.vault
        val candidate = repository.trashSelected(listOf("a"), now).vault
        // O disco mudou fora do app: não é o conteúdo conhecido.
        val changedOnDisk = byteArrayOf(9, 9, 9)
        document.stored = changedOnDisk

        assertThrows(VaultSaver.SaveFailure.ExternalChange::class.java) { saver.saveAndCommit(candidate, document) }

        assertEquals("nada foi adotado", before, repository.vault)
        assertArrayEquals("o conteúdo conhecido não muda", initialFile, saver.known)
        assertArrayEquals("o documento não foi reescrito", changedOnDisk, document.stored)
    }

    @Test
    fun o_force_grava_por_cima_da_mudanca_externa() {
        setup()
        val candidate = repository.trashSelected(listOf("a"), now).vault
        document.stored = byteArrayOf(9, 9, 9)

        saver.saveAndCommit(candidate, document, force = true)

        assertEquals("o cofre foi adotado", candidate, repository.vault)
        assertArrayEquals("o arquivo ficou com o candidato", document.stored, saver.known)
        val reopened = Vault.parse(String(crypto.openVault(password, document.stored!!).plaintext))
        assertEquals("o arquivo tem o lote", now, reopened.items.first { it.id == "a" }.deletedAt)
    }

    /** Provedor que grava por cima sem truncar: sempre sobra um rabo depois dos bytes novos. */
    private class NoTruncateDocument(var stored: ByteArray) : DocumentIo {
        override fun openOutput(mode: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                stored = toByteArray() + ByteArray(16)
                super.close()
            }
        }

        override fun openInput(): InputStream = stored.inputStream()
    }

    @Test
    fun provedor_que_nao_trunca_vira_not_truncated_e_nao_adota() {
        setup()
        val before = repository.vault
        val candidate = repository.trashSelected(listOf("a"), now).vault
        val noTruncate = NoTruncateDocument(initialFile)

        val failure = assertThrows(VaultSaver.SaveFailure.NotTruncated::class.java) { saver.saveAndCommit(candidate, noTruncate) }

        assertTrue("sobrou conteúdo depois do que foi gravado: $failure", failure.found > failure.written)
        assertEquals("nada foi adotado", before, repository.vault)
        assertArrayEquals("o conteúdo conhecido não muda", initialFile, saver.known)
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
    fun o_cofre_novo_depois_de_trancar_grava_sem_mudanca_externa() {
        setup()
        assertTrue("o cofre anterior tinha um conteúdo conhecido", saver.known != null)
        // O SAF acabou de criar o documento: está vazio.
        val fresh = MemoryDocument(ByteArray(0))
        val session = VaultSession(repository, saver)
        val file = session.createFile(Vault.empty(), password)
        // O passo do "criar cofre" no ViewModel: grava com force (o SAF acabou de criar o arquivo).
        session.writeCreated(file, fresh)

        assertEquals("o vazio do documento não é mudança externa", repository.vault, Vault.parse(String(crypto.openVault(password, fresh.stored!!).plaintext)))
        assertArrayEquals("o documento ficou com o cofre novo", fresh.stored, saver.known)
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
