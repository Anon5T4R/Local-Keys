package com.localkeys.android.data.store

/**
 * O que o provedor declara sobre um documento do SAF.
 *
 * Alguns gerenciadores oferecem "substituir" no "criar" e devolvem a URI de um arquivo que JÁ existia; outros
 * declaram `SIZE = -1` quando não sabem. Nenhuma dessas respostas pode virar "arquivo vazio" por engano.
 */

/** O `SIZE` declarado é confiável? Provedores devolvem -1 quando não sabem. */
fun declaredDocumentSize(raw: Long?): Long? = raw?.takeIf { it >= 0 }

/**
 * O documento pode ser apagado? Só quando está VAZIO — nunca um arquivo do usuário. Sem `SIZE` declarado, a leitura
 * decide (0 bytes = vazio; não deu para ler = não apaga).
 */
object EmptyDocument {
    fun isDiscardable(declaredSize: Long?, readSize: () -> Long?): Boolean = when {
        declaredSize != null -> declaredSize == 0L
        else -> readSize() == 0L
    }
}
