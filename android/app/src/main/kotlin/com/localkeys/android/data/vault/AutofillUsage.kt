package com.localkeys.android.data.vault

import android.content.Context

/**
 * Último uso de cada item no preenchimento automático (id do item → instante em ms). Fica em preferência
 * local do aparelho, fora do `.tkeys`, e só alimenta a ordem das sugestões
 * ([com.localkeys.android.data.autofill.AutofillMatcher.suggestionsFor]).
 *
 * SharedPreferences (leitura e escrita na hora): o serviço de autofill lê sem esperar o DataStore e a
 * tela que preenche grava antes de fechar.
 */
object AutofillUsage {

    private const val FILE = "autofill_last_use"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** O usuário escolheu [itemId] na lista do autofill agora. */
    fun record(context: Context, itemId: String) {
        prefs(context).edit().putLong(itemId, System.currentTimeMillis()).apply()
    }

    /** Instante do último uso por id do item (ids sem registro não aparecem). */
    fun lastUsed(context: Context): Map<String, Long> =
        prefs(context).all.mapNotNull { (id, value) -> (value as? Long)?.let { id to it } }.toMap()

    /** Esquece os registros de itens que não existem mais no cofre (a lista não cresce sem limite). */
    fun prune(context: Context, itemIds: Collection<String>) {
        val editor = prefs(context).edit()
        var changed = false
        for (id in prefs(context).all.keys) {
            if (id !in itemIds.toSet()) {
                editor.remove(id)
                changed = true
            }
        }
        if (changed) editor.apply()
    }
}
