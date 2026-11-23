package com.localkeys.android.data.sync

import com.localkeys.android.data.vault.Vault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Roda os casos comuns de `testdata/sync-merge/` (os mesmos que o desktop vai rodar) e confere a regra de
 * `docs/sync-merge.md`: o resultado tem o conteúdo de `expected.json` e o resumo de `summary.json`.
 */
class VaultMergeTest {

    private val casesDir: File = listOf("../../testdata/sync-merge", "testdata/sync-merge", "../testdata/sync-merge")
        .map(::File).first { it.isDirectory }

    private fun read(f: File) = f.readText(Charsets.UTF_8)

    @Test
    fun `all shared cases`() {
        val cases = casesDir.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("sem casos em $casesDir", cases.size >= 10)
        for (case in cases) {
            val a = Vault.parse(read(File(case, "a.json")))
            val b = Vault.parse(read(File(case, "b.json")))
            val result = VaultMerge.merge(a, b)
            val got = JSONObject(result.vault.toJson())
            val expected = JSONObject(read(File(case, "expected.json")))
            assertSameVault(case.name, expected, got)
            assertTrue("${case.name}: resumo", JsonEq.same(JSONObject(read(File(case, "summary.json"))), result.summary.toJson()))

            // As duas cópias ficam iguais: juntar o resultado com qualquer uma das duas de novo não muda nada.
            val again = VaultMerge.merge(result.vault, b)
            assertSameVault("${case.name} (de novo)", got, JSONObject(again.vault.toJson()))
        }
    }

    @Test
    fun `merging is idempotent and the result reopens`() {
        val case = File(casesDir, "09-anexos")
        val a = Vault.parse(read(File(case, "a.json")))
        val b = Vault.parse(read(File(case, "b.json")))
        val merged = VaultMerge.merge(a, b).vault
        assertEquals(merged, Vault.parse(merged.toJson()))
        val self = VaultMerge.merge(merged, merged)
        assertTrue(self.summary.nothingChanges)
        assertEquals(0, self.summary.conflicts)
    }

    @Test
    fun `json equality ignores key order but not array order`() {
        assertTrue(JsonEq.same(JSONObject("""{"a":1,"b":[1,2]}"""), JSONObject("""{"b":[1,2],"a":1}""")))
        assertTrue(!JsonEq.same(JSONObject("""{"b":[1,2]}"""), JSONObject("""{"b":[2,1]}""")))
        assertTrue(JsonEq.same(JSONObject("""{"a":null}"""), JSONObject("""{"a":null}""")))
        assertTrue(!JsonEq.same(JSONObject("""{"a":null}"""), JSONObject("""{"a":0}""")))
    }

    /** `items` e `folders` comparados por `id` (a ordem não faz parte da regra); o resto, igual. */
    private fun assertSameVault(name: String, expected: JSONObject, got: JSONObject) {
        val keys = expected.keys().asSequence().toSet()
        assertEquals("$name: chaves do cofre", keys, got.keys().asSequence().toSet())
        for (k in keys) {
            when (k) {
                "items", "folders" -> {
                    val e = byId(expected.getJSONArray(k))
                    val g = byId(got.getJSONArray(k))
                    assertEquals("$name: ids em $k", e.keys, g.keys)
                    e.forEach { (id, obj) -> assertTrue("$name: $k/$id\nesperado $obj\nveio ${g[id]}", JsonEq.same(obj, g[id])) }
                }
                else -> assertTrue("$name: $k", JsonEq.same(expected.get(k), got.get(k)))
            }
        }
    }

    private fun byId(arr: JSONArray): Map<String, JSONObject> =
        (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("id") }
}
