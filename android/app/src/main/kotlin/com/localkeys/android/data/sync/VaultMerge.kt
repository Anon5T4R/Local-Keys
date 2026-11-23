package com.localkeys.android.data.sync

import com.localkeys.android.data.vault.Extra
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.Vault
import org.json.JSONArray
import org.json.JSONObject

/**
 * Junta duas cópias do mesmo cofre ("Sincronizar com outra cópia"). As regras estão em `docs/sync-merge.md` na
 * raiz do repositório e os casos de teste em `testdata/sync-merge/` — o desktop (Rust) segue os mesmos.
 *
 * Resumo: itens por `id`; de um lado só, ficam; dos dois, ganha o de maior **tempo efetivo**
 * (`updatedAt`, `deletedAt` do item e dos anexos — mandar para a lixeira não mexe no `updatedAt`); empate com
 * conteúdo diferente = fica o do cofre atual e conta como conflito. Pastas: união por `id`, nome do atual.
 * Tipos desconhecidos seguem a mesma regra. O que é do cofre (versão, chaves desconhecidas) fica o do atual.
 * Nada de chave nova: o formato não muda.
 */
object VaultMerge {

    data class Summary(
        /** Itens que só existiam na outra cópia. */
        val added: Int = 0,
        /** Itens em que a outra cópia era mais nova (fora lixeira/restauração). */
        val updated: Int = 0,
        /** Itens que a outra cópia mandou para a lixeira. */
        val trashed: Int = 0,
        /** Itens que a outra cópia tirou da lixeira. */
        val restored: Int = 0,
        /** Mesmo tempo, conteúdo diferente: ficou o do cofre atual. */
        val conflicts: Int = 0,
        /** Pastas que só existiam na outra cópia. */
        val foldersAdded: Int = 0,
        /** Itens que a outra cópia vai ganhar ou trocar (só existiam aqui, ou aqui eram mais novos). */
        val toOther: Int = 0,
        /** Pastas que a outra cópia vai ganhar. */
        val foldersToOther: Int = 0,
    ) {
        /** Nada muda no cofre atual nem na outra cópia. */
        val nothingChanges: Boolean get() = added + updated + trashed + restored + foldersAdded + toOther + foldersToOther == 0

        fun toJson(): JSONObject = JSONObject()
            .put("added", added).put("updated", updated).put("trashed", trashed).put("restored", restored)
            .put("conflicts", conflicts).put("foldersAdded", foldersAdded).put("toOther", toOther)
            .put("foldersToOther", foldersToOther)
    }

    data class Result(val vault: Vault, val summary: Summary)

    fun merge(current: Vault, other: Vault): Result {
        var s = Summary()

        // Itens conhecidos e de tipo desconhecido entram juntos: a regra é a mesma, pelo JSON de cada um.
        val curEntries = entries(current)
        val othEntries = entries(other)
        val othById = othEntries.associateBy { it.id }
        val curIds = curEntries.map { it.id }.toSet()

        val merged = mutableListOf<Entry>()
        for (c in curEntries) {
            val o = othById[c.id]
            if (o == null) {
                merged += c
                s = s.copy(toOther = s.toOther + 1)
                continue
            }
            val same = JsonEq.same(c.json, o.json)
            val winner = when {
                same -> c
                o.time > c.time -> o
                o.time < c.time -> c
                else -> {
                    s = s.copy(conflicts = s.conflicts + 1)
                    c
                }
            }
            if (!same) {
                if (winner === o) {
                    s = when {
                        o.inTrash && !c.inTrash -> s.copy(trashed = s.trashed + 1)
                        !o.inTrash && c.inTrash -> s.copy(restored = s.restored + 1)
                        else -> s.copy(updated = s.updated + 1)
                    }
                } else {
                    s = s.copy(toOther = s.toOther + 1)
                }
            }
            merged += winner
        }
        for (o in othEntries) {
            if (o.id !in curIds) {
                merged += o
                s = s.copy(added = s.added + 1)
            }
        }

        val curFolderIds = current.folders.map { it.id }.toSet()
        val newFolders = other.folders.filter { it.id !in curFolderIds }
        s = s.copy(foldersAdded = newFolders.size)
        val folders: List<Folder> = current.folders + newFolders
        // A outra cópia também ganha as pastas que só existiam aqui.
        val otherFolderIds = other.folders.map { it.id }.toSet()
        s = s.copy(foldersToOther = current.folders.count { it.id !in otherFolderIds })

        val items = mutableListOf<Item>()
        val others = mutableListOf<Extra>()
        merged.forEach { e -> if (e.item != null) items += e.item else others += Extra(e.json.toString()) }
        val vault = current.copy(folders = folders, items = items, otherItems = others)
        return Result(vault, s)
    }

    /** Um item (conhecido ou não) visto pelo JSON: id, tempo efetivo, lixeira. */
    private class Entry(val id: String, val json: JSONObject, val item: Item?) {
        val time: Long = effectiveTime(json)
        val inTrash: Boolean = (json.optLongOrNull("deletedAt") ?: 0L) != 0L
    }

    private fun entries(v: Vault): List<Entry> =
        v.items.map { Entry(it.id, it.toJson(), it) } +
            v.otherItems.mapNotNull { raw ->
                val json = raw.base()
                json.optString("id", "").takeIf { it.isNotEmpty() }?.let { Entry(it, json, null) }
            }

    /**
     * Tempo efetivo de um item: o maior entre `updatedAt`, o `deletedAt` do item e os `deletedAt` dos anexos.
     * Mandar para a lixeira (item ou anexo) grava só o `deletedAt`, sem tocar no `updatedAt` — sem isso a
     * lixeira nunca "ganharia" de uma cópia sem ela.
     */
    fun effectiveTime(json: JSONObject): Long {
        var t = json.optLongOrNull("updatedAt") ?: 0L
        json.optLongOrNull("deletedAt")?.let { t = maxOf(t, it) }
        json.optJSONArray("attachments")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optLongOrNull("deletedAt")?.let { t = maxOf(t, it) }
        }
        return t
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong()
}

/**
 * Igualdade de JSON pelo conteúdo (ordem das chaves não importa; a dos arrays, sim). O `org.json` do Android
 * não tem `similar`, e comparar o texto falharia com chaves em outra ordem.
 */
object JsonEq {
    fun same(a: Any?, b: Any?): Boolean = when {
        a == null || a == JSONObject.NULL -> b == null || b == JSONObject.NULL
        b == null || b == JSONObject.NULL -> false
        a is JSONObject && b is JSONObject -> {
            val ka = a.keys().asSequence().toSet()
            val kb = b.keys().asSequence().toSet()
            ka == kb && ka.all { same(a.opt(it), b.opt(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> if (isIntegral(a) && isIntegral(b)) a.toLong() == b.toLong() else a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun isIntegral(n: Number) = n is Int || n is Long || n is Short || n is Byte ||
        (n is Double && n == Math.floor(n) && !n.isInfinite()) || (n is Float && n.toDouble() == Math.floor(n.toDouble()))
}
