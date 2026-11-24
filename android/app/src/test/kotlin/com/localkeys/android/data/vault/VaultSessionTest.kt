package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.store.DocumentIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A sequência de vida do cofre que o ViewModel delega (unidade 7, correção do BLOCKER): abrir → trancar → destrancar
 * o MESMO cofre → criar outro num documento vazio → falhar ao criar mais um → o cofre anterior continua
 * destrancável. O conteúdo conhecido (o ciphertext) e o URI dele andam JUNTOS: não podem ser zerados no trancar nem
 * numa criação que falhou, e destrancar com o blob de outro arquivo recusa em vez de abrir o cofre errado.
 */
class VaultSessionTest {

    private val password = "senha-do-teste"
    private val uriA = "content://docs/a.tkeys"
    private val uriB = "content://docs/b.tkeys"
    private val uriC = "content://docs/c.tkeys"
    private val crypto = TkeysCrypto()
    private val repository = VaultRepository(crypto)
    private val saver = VaultSaver(repository)
    private val session = VaultSession(repository, saver)

    private fun item(id: String) = Item(
        id = id, kind = ItemKind.NOTE, name = "Item $id", favorite = false, folderId = null, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private fun vaultA() = Vault(version = 1, folders = emptyList(), items = listOf(item("a")))

    /** Documento em memória (o mesmo [DocumentIo] que o SAF implementa no app). */
    private class MemoryDocument(var stored: ByteArray?) : DocumentIo {
        override fun openOutput(mode: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                stored = toByteArray()
                super.close()
            }
        }

        override fun openInput(): InputStream? = stored?.inputStream()
    }

    /** Provedor que recusa a gravação (a criação falha depois de cifrar). */
    private class RefusingDocument : DocumentIo {
        override fun openOutput(mode: String): OutputStream? = throw IOException("provider refused")
        override fun openInput(): InputStream? = null
    }

    @Test
    fun `abrir trancar destrancar criar falhar e destrancar de novo`() {
        // Abrir o cofre A: o arquivo lido do disco vira o conteúdo conhecido, junto com o URI dele.
        val fileA = session.createFile(vaultA(), password)
        session.load(uriA, fileA)
        assertEquals(vaultA(), session.unlock(uriA, password))

        // Trancar: a sessão sai, o ciphertext FICA.
        session.lock()
        assertArrayEquals("trancar mantém o ciphertext (o unlock depende dele)", fileA, session.known)
        assertEquals(uriA, session.knownUri)
        assertEquals("o cofre A reabre depois de trancar", vaultA(), session.unlock(uriA, password))

        // Criar B num documento vazio que o SAF acabou de criar: grava com force, sem ExternalChange.
        val docB = MemoryDocument(ByteArray(0))
        val fileB = session.createFile(Vault.empty(), password)
        session.writeCreated(uriB, fileB, docB)
        assertArrayEquals("o documento do criar ficou com o cofre novo", docB.stored, session.known)
        assertEquals("o cofre novo registra o próprio URI", uriB, session.knownUri)
        assertNotEquals("é outro cofre", fileA.toList(), fileB.toList())

        // Criar C que falha na gravação: o conhecido (e o URI) do B ficam intactos.
        session.lock()
        val beforeC = session.known!!.copyOf()
        val fileC = session.createFile(Vault(version = 1, folders = emptyList(), items = listOf(item("c"))), password)
        assertThrows(VaultSaver.SaveFailure.WriteFailed::class.java) { session.writeCreated(uriC, fileC, RefusingDocument()) }
        assertArrayEquals("a criação que falha não zera o conhecido", beforeC, session.known)
        assertEquals("nem troca o URI", uriB, session.knownUri)

        // E o cofre B continua destrancável depois da falha.
        session.lock()
        assertEquals("o cofre B continua reabrindo", Vault.empty(), session.unlock(uriB, password))
    }

    @Test
    fun `carregar outro arquivo troca o par e esquecer zera os dois`() {
        val fileA = session.createFile(vaultA(), password)
        session.load(uriA, fileA)
        assertTrue(session.isFor(uriA))
        assertTrue(session.known!!.contentEquals(fileA))

        session.forget()
        assertNull(session.known)
        assertNull(session.knownUri)
        assertNull("sem blob não destranca", session.unlock(uriA, password))
    }

    @Test
    fun `destrancar com o blob de outro URI recusa`() {
        val fileA = session.createFile(vaultA(), password)
        session.load(uriA, fileA)

        assertNull("URI de outro cofre não abre A", session.unlock(uriB, password))
        assertNull("sem URI atual não abre", session.unlock(null, password))
        assertEquals("o URI certo continua abrindo", vaultA(), session.unlock(uriA, password))
    }

    @Test
    fun `a biometria destranca o cofre certo depois de trancar`() {
        val created = crypto.createVault(password, vaultA().toJson().toByteArray())
        session.load(uriA, created.file)
        session.lock()

        assertNull("a chave de outro URI não abre", session.unlockWithKey(uriB, created.session.keyBytes()))
        assertEquals("a chave certa abre o cofre do URI", vaultA(), session.unlockWithKey(uriA, created.session.keyBytes()))
    }

    @Test
    fun `o reload adota o arquivo e guarda o par`() {
        val fileA = session.createFile(vaultA(), password)
        session.load(uriA, fileA)
        val reloaded = session.reload(uriA, fileA)
        assertEquals(vaultA(), reloaded)
        assertArrayEquals(fileA, session.known)
        assertEquals(uriA, session.knownUri)
    }
}
