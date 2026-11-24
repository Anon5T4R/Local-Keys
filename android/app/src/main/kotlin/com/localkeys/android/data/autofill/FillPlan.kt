package com.localkeys.android.data.autofill

/** Como oferecer um login casado na lista do autofill. */
sealed interface FillPlan {
    /** Nada desta tela serve para o item (ex.: só há campo de código e o item não tem TOTP). */
    data object Skip : FillPlan

    /** Usuário e senha, sem TOTP envolvido (nada a copiar depois de preencher). */
    data object Direct : FillPlan

    /** Montado na hora da escolha (código TOTP fresco); [copyTotp] = copiar o código depois. */
    data class Deferred(val copyTotp: Boolean) : FillPlan

    companion object {
        /**
         * [hasLoginField] = a tela tem usuário ou senha; [hasOtpField] = tem campo de código; [itemHasTotp] = o
         * item gera código; [copySetting] = "Copiar o TOTP depois do autofill". A cópia só vale quando o código
         * não vai direto para um campo (aí copiar seria só barulho).
         */
        fun of(hasLoginField: Boolean, hasOtpField: Boolean, itemHasTotp: Boolean, copySetting: Boolean): FillPlan =
            when {
                !hasLoginField && !(hasOtpField && itemHasTotp) -> Skip
                !itemHasTotp -> Direct
                hasOtpField -> Deferred(copyTotp = false)
                copySetting -> Deferred(copyTotp = true)
                else -> Direct
            }
    }
}
