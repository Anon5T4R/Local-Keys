package com.localkeys.android.data.crypto

import com.localkeys.android.data.sync.JsonEq
import com.localkeys.android.data.vault.Vault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Paridade da junção de um cofre DIFERENTE (unidade 7) pela ponte: roda as fixtures `other-*` de
 * `testdata/sync-merge/` com a lib do host e confere com o esperado escrito à mão (o mesmo que o núcleo roda). O id
 * gerado na colisão é ligado ao `<new>` do esperado; a comparação de itens/pastas é por `id` e a de cada objeto pelo
 * conteúdo. Cada caso roda DUAS vezes: a segunda passada tem de ser idempotente.
 *
 * O pino de "chave ausente × `null`" (fixture 15) não consegue falhar aqui: o `Vault.parse` do Android normaliza
 * ausente para `null` antes da ponte, então os dois lados já são `null`. O pino de verdade é o runner Rust.
 */
class MergeOtherVaultTest {

    private val casesDir: File = listOf("../../testdata/sync-merge", "testdata/sync-merge", "../testdata/sync-merge")
        .map(::File).first { it.isDirectory }

    private fun read(file: File) = file.readText(Charsets.UTF_8)

    @Test
    fun `as fixtures de outro cofre batem com o esperado`() {
        val bases = casesDir.listFiles { f ->
            f.isFile && f.name.startsWith("other-") && f.name.endsWith("-base.json")
        }!!.sortedBy { it.name }
        assertTrue("sem fixtures other-* em $casesDir", bases.size >= 10)

        for (baseFile in bases) {
            val name = baseFile.name.removeSuffix("-base.json")
            val base = Vault.parse(read(baseFile))
            val other = Vault.parse(read(File(casesDir, "$name-other.json")))

            val result = mergeOtherVault(base, other)
            val mergedJson = JSONObject(result.merged.toJson())

            val expected = bindNewIds(JSONObject(read(File(casesDir, "$name-expected.json"))), mergedJson, base, other)
            assertSameVault(name, expected, mergedJson)

            val summary = JSONObject(read(File(casesDir, "$name-summary.json")))
            assertEquals("$name: added", summary.getInt("added"), result.added)
            assertEquals("$name: collisions", summary.getInt("collisions"), result.collisions)
            assertEquals("$name: alreadyPresent", summary.getInt("alreadyPresent"), result.alreadyPresent)
            assertEquals("$name: contentAlreadyPresent", summary.getInt("contentAlreadyPresent"), result.contentAlreadyPresent)
            assertEquals("$name: foldersAdded", summary.getInt("foldersAdded"), result.foldersAdded)
            assertEquals("$name: foldersMerged", summary.getInt("foldersMerged"), result.foldersMerged)

            // Idempotente: juntar o RESULTADO com o mesmo outro cofre de novo não muda nada nem conta pasta unida.
            val again = mergeOtherVault(result.merged, other)
            assertSameVault("$name (de novo)", mergedJson, JSONObject(again.merged.toJson()))
            assertEquals(
                "$name: a segunda passada não adiciona nada nem conta pasta unida",
                0,
                again.added + again.collisions + again.foldersAdded + again.foldersMerged,
            )
        }
    }

    /** Troca os `"<new>"` do esperado pelos ids que a regra gerou (a colisão ganha um id aleatório). */
    private fun bindNewIds(expected: JSONObject, merged: JSONObject, base: Vault, other: Vault): JSONObject {
        val taken = itemsOf(JSONObject(base.toJson())).keys + itemsOf(JSONObject(other.toJson())).keys
        val fresh = itemsOf(merged).keys.filter { it !in taken }
        val out = JSONObject(expected.toString())
        val items = out.getJSONArray("items")
        var used = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("id") == "<new>") {
                item.put("id", fresh[used])
                used++
            }
        }
        assertEquals("todo id gerado tem o seu `<new>` no esperado", fresh.size, used)
        return out
    }

    private fun itemsOf(vault: JSONObject): Map<String, JSONObject> {
        val array = vault.getJSONArray("items")
        return (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("id") }
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
