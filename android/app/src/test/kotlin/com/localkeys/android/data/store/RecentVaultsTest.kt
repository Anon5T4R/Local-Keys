package com.localkeys.android.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentVaultsTest {

    private val work = "content://com.microsoft.skydrive.content.StorageAccessProvider/document/trabalho.tkeys"
    private val home = "content://com.android.externalstorage.documents/document/primary%3Alocalkeys.tkeys"

    @Test
    fun `touch puts the vault on top without duplicates`() {
        var list = RecentVaults.touch(emptyList(), work, "trabalho.tkeys")
        list = RecentVaults.touch(list, home, "localkeys.tkeys")
        assertEquals(listOf(home, work), list.map { it.uri })
        list = RecentVaults.touch(list, work, null)
        assertEquals(listOf(work, home), list.map { it.uri })
        // Sem nome novo, fica o que já se sabia.
        assertEquals("trabalho.tkeys", list.first().name)
    }

    @Test
    fun `touch caps the list`() {
        var list = emptyList<RecentVault>()
        repeat(RecentVaults.MAX + 5) { list = RecentVaults.touch(list, "content://x/$it", "v$it") }
        assertEquals(RecentVaults.MAX, list.size)
        assertEquals("content://x/${RecentVaults.MAX + 4}", list.first().uri)
    }

    @Test
    fun `remove only forgets the entry`() {
        val list = listOf(RecentVault(work, "a"), RecentVault(home, "b"))
        assertEquals(listOf(home), RecentVaults.remove(list, work).map { it.uri })
    }

    @Test
    fun `encode and decode round trip`() {
        val list = listOf(RecentVault(work, "trabalho.tkeys"), RecentVault(home, "Pessoal \"casa\""))
        assertEquals(list, RecentVaults.decode(RecentVaults.encode(list)))
    }

    @Test
    fun `broken json becomes an empty list`() {
        assertTrue(RecentVaults.decode("{nada").isEmpty())
        assertTrue(RecentVaults.decode(null).isEmpty())
        val raw = "[{\"uri\":\"\"},{\"uri\":\"$home\"},7]"
        assertEquals(listOf(home), RecentVaults.decode(raw).map { it.uri })
    }

    @Test
    fun `fallback name is the file part of the uri`() {
        assertEquals("localkeys.tkeys", RecentVaults.fallbackName(home))
        assertEquals("localkeys.tkeys", RecentVaults.touch(emptyList(), home, " ").first().name)
    }

    @Test
    fun `biometric keys are kept per vault`() {
        val map = mapOf(work to "aa11", home to "bb22")
        val back = BiometricKeys.decode(BiometricKeys.encode(map))
        assertEquals(map, back)
        assertEquals("aa11", back[work])
        assertEquals(null, back["content://outro"])
        assertTrue(BiometricKeys.decode("lixo").isEmpty())
    }

    @Test
    fun `legacy biometric key goes to the vault that was open`() {
        assertEquals(mapOf(home to "cc33"), BiometricKeys.migrateLegacy(emptyMap(), "cc33", true, home))
        // Biometria desligada, sem cofre ou sem chave: nada migra.
        assertTrue(BiometricKeys.migrateLegacy(emptyMap(), "cc33", false, home).isEmpty())
        assertTrue(BiometricKeys.migrateLegacy(emptyMap(), "cc33", true, null).isEmpty())
        assertTrue(BiometricKeys.migrateLegacy(emptyMap(), null, true, home).isEmpty())
        // Já migrado: a chave nova do cofre vence a velha.
        assertEquals(mapOf(home to "nova"), BiometricKeys.migrateLegacy(mapOf(home to "nova"), "cc33", true, home))
    }
}
