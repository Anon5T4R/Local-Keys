package com.localkeys.android.data.sync

import com.localkeys.android.data.vault.Vault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O cofre aberto mudou entre o resumo da junção e a confirmação (autofill/passkey gravou no meio): a comparação é
 * semântica (a ordem das chaves não conta) e um JSON que não compara conta como mudado — refazer é o lado seguro.
 */
class MergeOtherFreshnessTest {

    private fun vaultJson(name: String) = Vault.parse(
        """{"version":1,"folders":[],"items":[{"id":"i1","kind":"note","name":"$name","favorite":false,
            "folderId":null,"notes":"","createdAt":1,"updatedAt":2,"deletedAt":null}]}""",
    ).toJson()

    @Test
    fun `the same vault written again is not stale`() {
        val base = vaultJson("A")
        assertFalse(MergeOtherFreshness.isStale(base, vaultJson("A")))
    }

    @Test
    fun `a changed vault is stale`() {
        assertTrue(MergeOtherFreshness.isStale(vaultJson("A"), vaultJson("B")))
    }

    @Test
    fun `the same content in a different key order is not stale`() {
        val base = """{"version":1,"folders":[],"items":[]}"""
        val reordered = """{"items":[],"folders":[],"version":1}"""
        assertFalse(MergeOtherFreshness.isStale(base, reordered))
    }

    @Test
    fun `a json that does not compare counts as stale`() {
        assertTrue(MergeOtherFreshness.isStale("não é json", vaultJson("A")))
    }
}
