package com.localkeys.android.data.crypto

import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.ffi.FfiException
import com.localkeys.android.ffi.moveItems as bridgeMoveItems
import com.localkeys.android.ffi.trashItems as bridgeTrashItems
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O lote da unidade 5 do pedido 027 pela ponte UniFFI (`crates/ffi` → `batch` do núcleo): apagar (para a lixeira) e
 * mover de pasta vários itens de uma vez. O esperado fica escrito à mão aqui, não derivado do núcleo; a ponte do app
 * (`trashItems`/`moveItems` do `TkeysCrypto`, que traduz o erro para o `TkeysError`) é testada junto.
 */
class BatchTest {

    /** Um item do cofre. */
    private fun item(id: String, name: String, folderId: String?) = Item(
        id = id, kind = ItemKind.NOTE, name = name, favorite = false, folderId = folderId, notes = "",
        createdAt = 1, updatedAt = 2, deletedAt = null,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    /** O mesmo item depois da operação — os campos que a regra toca vêm explícitos. */
    private fun result(id: String, name: String, folderId: String?, updatedAt: Long, deletedAt: Long?) = Item(
        id = id, kind = ItemKind.NOTE, name = name, favorite = false, folderId = folderId, notes = "",
        createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
        login = null, card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private val selected = listOf(item("a", "A", null), item("b", "B", "work"))

    private fun itemsOf(json: String): List<Item> {
        val array = JSONArray(json)
        return (0 until array.length()).map { Item.parse(array.getJSONObject(it)) }
    }

    @Test
    fun a_lixeira_em_lote_chega_com_o_mesmo_agora_em_todos() {
        val json = JSONArray(selected.map { it.toJson() }).toString()

        val trashed = itemsOf(bridgeTrashItems(json, 1700uL))

        assertEquals(
            listOf(result("a", "A", null, updatedAt = 1700, deletedAt = 1700), result("b", "B", "work", updatedAt = 1700, deletedAt = 1700)),
            trashed,
        )
    }

    @Test
    fun mover_em_lote_para_uma_pasta_e_para_sem_pasta() {
        val json = JSONArray(selected.map { it.toJson() }).toString()

        val moved = itemsOf(bridgeMoveItems(json, "home", 1700uL))
        assertEquals(
            listOf(result("a", "A", "home", updatedAt = 1700, deletedAt = null), result("b", "B", "home", updatedAt = 1700, deletedAt = null)),
            moved,
        )

        val cleared = itemsOf(bridgeMoveItems(json, null, 1700uL))
        assertEquals(
            listOf(result("a", "A", null, updatedAt = 2, deletedAt = null), result("b", "B", null, updatedAt = 1700, deletedAt = null)),
            cleared,
        )
    }

    @Test
    fun o_app_manda_so_os_itens_selecionados_e_traduz_o_erro() {
        // O caminho que as telas vão usar: itens tipados entram, itens tipados (com as datas do lote) saem.
        assertEquals(
            listOf(result("a", "A", null, updatedAt = 1700, deletedAt = 1700), result("b", "B", "work", updatedAt = 1700, deletedAt = 1700)),
            trashItems(selected, 1700),
        )

        // Lote inválido (um item já na lixeira): nada muda e o erro chega tipado, com a razão do núcleo.
        val alreadyTrashed = selected + item("c", "C", null).copy(deletedAt = 9)
        val refused = assertThrows(TkeysError.InvalidItems::class.java) { trashItems(alreadyTrashed, 1700) }
        assertEquals("item `c` is already in the trash", refused.reason)

        val naoObjeto = assertThrows(FfiException.InvalidItems::class.java) { bridgeMoveItems("{}", null, 1uL) }
        assertTrue("JSON que não é lista: ${naoObjeto.reason}", naoObjeto.reason.startsWith("invalid items JSON: "))
    }
}
