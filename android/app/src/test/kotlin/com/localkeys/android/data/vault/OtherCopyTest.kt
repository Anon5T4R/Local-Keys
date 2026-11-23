package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.KdfParams
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.sync.VaultMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A outra cópia de "Sincronizar com outra cópia": abrir com a chave atual (cópia do mesmo arquivo) ou com a
 * senha dela (cofre com outro sal), gravar o resultado com a chave DELA e conferir.
 */
class OtherCopyTest {

    private lateinit var crypto: TkeysCrypto
    private lateinit var repository: VaultRepository

    // Argon2id leve para o teste não demorar (o formato guarda os parâmetros no cabeçalho).
    private val light = KdfParams(mCostKib = 8 * 1024, tCost = 1, pCost = 1)

    private fun item(id: String, name: String, updatedAt: Long) = Item(
        id = id, kind = ItemKind.NOTE, name = name, favorite = false, folderId = null, notes = "",
        createdAt = 1, updatedAt = updatedAt, deletedAt = null, login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    @Before
    fun setUp() {
        crypto = TkeysCrypto()
        repository = VaultRepository(crypto)
    }

    @Test
    fun `a copy of the same file opens with the session key and gets the merge back`() {
        val original = Vault.empty().copy(items = listOf(item("a", "A", 1)))
        val file = crypto.createVault("senha", original.toJson().toByteArray(), light).file
        repository.unlock(file, "senha")
        repository.replace(repository.vault.copy(items = repository.vault.items + item("b", "B", 2)))

        // A outra cópia: o mesmo arquivo, editado em outro aparelho.
        val otherVault = original.copy(items = listOf(item("a", "A editado", 5)))
        val otherFile = repository.openOtherWithSessionKey(file)!!.session.seal(otherVault.toJson().toByteArray())
        val other = repository.openOtherWithSessionKey(otherFile)
        assertNotNull(other)
        assertEquals(otherVault, other!!.vault)

        val merged = VaultMerge.merge(repository.vault, other.vault).vault
        assertEquals(listOf("A editado", "B"), merged.items.map { it.name })
        val blob = repository.sealFor(other, merged)
        assertTrue(repository.verifyFor(other, blob, merged))
        assertFalse(repository.verifyFor(other, blob, otherVault))
        // E o arquivo gravado abre com a senha de sempre.
        assertEquals(merged, Vault.parse(String(crypto.openVault("senha", blob).plaintext)))
    }

    @Test
    fun `a vault created apart needs its own password`() {
        val mine = crypto.createVault("senha", Vault.empty().toJson().toByteArray(), light).file
        val theirs = crypto.createVault("outra", Vault.empty().copy(items = listOf(item("x", "X", 1))).toJson().toByteArray(), light).file
        repository.unlock(mine, "senha")
        assertNull(repository.openOtherWithSessionKey(theirs))
        assertThrows(TkeysError::class.java) { repository.openOtherWithPassword(theirs, "errada") }
        val other = repository.openOtherWithPassword(theirs, "outra")
        val merged = VaultMerge.merge(repository.vault, other.vault).vault
        val blob = repository.sealFor(other, merged)
        // Gravado com a chave e o sal da outra: abre com a senha dela, não com a deste cofre.
        assertEquals(merged, Vault.parse(String(crypto.openVault("outra", blob).plaintext)))
        assertThrows(TkeysError::class.java) { crypto.openVault("senha", blob) }
    }
}
