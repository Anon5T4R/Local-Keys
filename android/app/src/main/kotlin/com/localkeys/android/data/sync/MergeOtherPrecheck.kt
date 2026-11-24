package com.localkeys.android.data.sync

/**
 * A decisão ANTES de ler o outro cofre, com o que o sistema/provedor declara de tamanho. Puro e testável na JVM: o
 * ViewModel só executa a leitura depois disto.
 */
sealed interface MergeOtherPrecheck {
    /** Dá para ler o arquivo inteiro: a soma cabe no teto. [vaultBytes] é o tamanho do cofre aberto. */
    data class ReadWhole(val vaultBytes: Long) : MergeOtherPrecheck

    /** O provedor não declarou o tamanho: ler com teto de [cap] e recusar se passar. */
    data class ReadCapped(val vaultBytes: Long, val cap: Long) : MergeOtherPrecheck

    /** O escolhido (ou a soma) já passa do teto: recusa sem ler. */
    data object TooBig : MergeOtherPrecheck

    /** Não deu para saber o tamanho do cofre aberto: recusa, porque o teto não pode ser conferido. */
    data object UnknownVaultSize : MergeOtherPrecheck
}
