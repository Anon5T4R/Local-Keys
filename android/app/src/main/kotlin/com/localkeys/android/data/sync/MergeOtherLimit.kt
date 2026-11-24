package com.localkeys.android.data.sync

import com.localkeys.android.data.vault.VaultSizeLimit

/**
 * O teto conservador da junção de outro cofre no Android — o MESMO número do aviso ao anexar
 * ([VaultSizeLimit.MAX_BYTES]), porque os dois são o limite de memória desta plataforma.
 *
 * A junção segura o cofre aberto, o outro decifrado, o resultado do núcleo e o JSON do resultado ao mesmo tempo — a
 * revisora estimou o pico em ~5–6× a soma dos dois JSONs. A medição está no commit `5cadc29` (testes ignorados
 * `crates/core/tests/measure_large_vault.rs` e `LargeVaultMeasureTest.kt`): desktop (release) salva 290/521/1.043 ms
 * com 50/100/200 MB; Android (JVM do host, heap de 256 MB) um cofre de 50 MB **não salva**, 100 MB estoura no parse.
 * Por isso 20 MB de soma é o teto que deixa folga para os dois lados e o resultado; acima disso, a mensagem manda
 * juntar no desktop.
 */
object MergeOtherLimit {
    /** Soma máxima dos dois arquivos `.tkeys` (o aberto e o outro): o teto do Android, num lugar só. */
    const val MAX_BYTES: Long = VaultSizeLimit.MAX_BYTES

    /** O JSON juntado passou do teto? */
    fun resultOverLimit(mergedJsonBytes: Long): Boolean = mergedJsonBytes > MAX_BYTES

    /**
     * A decisão ANTES de ler o outro arquivo, pelos tamanhos declarados (o do cofre aberto e o do escolhido). É o que
     * impede a leitura de um arquivo grande antes da mensagem "junte pelo desktop".
     */
    fun precheck(vaultBytes: Long?, otherDeclaredBytes: Long?): MergeOtherPrecheck {
        val vault = vaultBytes ?: return MergeOtherPrecheck.UnknownVaultSize
        if (vault > MAX_BYTES) return MergeOtherPrecheck.TooBig
        if (otherDeclaredBytes != null) {
            return if (otherDeclaredBytes > MAX_BYTES - vault) MergeOtherPrecheck.TooBig else MergeOtherPrecheck.ReadWhole(vault)
        }
        return MergeOtherPrecheck.ReadCapped(vault, (MAX_BYTES - vault).coerceAtLeast(0))
    }

    /**
     * Tamanho em bytes UTF-8 de [text], sem alocar a cópia: o resultado da junção pode ter dezenas de MB e não vale
     * gastar mais um array só para medi-lo.
     */
    fun utf8Size(text: String): Long {
        var size = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i += 1
                    4
                }
                else -> 3
            }
            i += 1
        }
        return size
    }
}
