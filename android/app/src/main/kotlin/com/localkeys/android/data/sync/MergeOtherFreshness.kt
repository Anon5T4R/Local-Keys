package com.localkeys.android.data.sync

import org.json.JSONObject

/**
 * O cofre aberto mudou entre o cálculo do resumo da junção e a confirmação? Compara o JSON semântico (a ordem das
 * chaves não conta): um autofill/passkey que gravou no meio torna o resumo velho e obriga a refazer a junção sobre o
 * conteúdo de agora.
 */
object MergeOtherFreshness {
    fun isStale(baseJson: String, currentJson: String): Boolean = try {
        !JsonEq.same(JSONObject(baseJson), JSONObject(currentJson))
    } catch (_: Exception) {
        // Sem comparar não dá para garantir: refazer é o lado seguro (nada é gravado antes da confirmação).
        true
    }
}
