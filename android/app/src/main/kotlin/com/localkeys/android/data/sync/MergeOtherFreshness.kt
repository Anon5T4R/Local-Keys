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

/** O que fazer quando a pessoa confirma o resumo da junção. */
enum class MergeConfirmAction {
    /** O cofre não mudou: grava o plano calculado. */
    WritePlan,

    /** O cofre mudou: refaz a junção e VOLTA AO RESUMO com as contagens novas (nada é gravado ainda). */
    PresentAgain,
}

/** A decisão: cofre mudou desde o resumo → reapresenta; não mudou → grava o calculado. */
fun confirmAction(stale: Boolean): MergeConfirmAction = if (stale) MergeConfirmAction.PresentAgain else MergeConfirmAction.WritePlan
