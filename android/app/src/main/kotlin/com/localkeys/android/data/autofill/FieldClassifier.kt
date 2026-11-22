package com.localkeys.android.data.autofill

import java.text.Normalizer

/** O que um campo da tela pede, para o autofill. */
enum class FieldKind { USERNAME, PASSWORD, OTP, NONE }

/**
 * O que se sabe de um campo de texto, já tirado do `ViewNode` (a parte Android fica no serviço):
 *  - [autofillHints]: as dicas declaradas pelo app (`View.AUTOFILL_HINT_*`, `HintConstants` do androidx);
 *  - [htmlAutocomplete]: o atributo `autocomplete` do `<input>` (páginas no navegador);
 *  - [texts]: o resto que dá pista — id do recurso, dica, `name`/`id`/`placeholder`/`aria-label` do HTML,
 *    descrição de acessibilidade.
 */
data class FieldSignals(
    val autofillHints: List<String> = emptyList(),
    val htmlAutocomplete: String? = null,
    val texts: List<String> = emptyList(),
)

/**
 * Classifica campos para o autofill. Usuário e senha só por dica declarada (como sempre foi); o campo de
 * código de uso único (TOTP/2FA) também por dica declarada e, sem dica, pelas palavras do campo ("code",
 * "código", "otp", "2fa", "token", "verification"…), descartando os "códigos" que não são de login (CEP,
 * cupom, cartão, país…). Lógica pura, testada na JVM.
 */
object FieldClassifier {

    private val PASSWORD_HINTS = setOf("password", "currentpassword", "newpassword")
    private val USERNAME_HINTS = setOf("username", "emailaddress", "loginid", "texturi")

    /** Dicas de código de uso único: `HintConstants` (smsOTPCode, 2faAppOTPCode, emailOTPCode…) e o HTML. */
    private fun isOtpHint(hint: String): Boolean {
        val h = hint.lowercase().replace("-", "").replace("_", "")
        return "otp" in h || h == "onetimecode" || h == "2fa" || h.startsWith("2faapp")
    }

    private val OTP_WORDS = setOf(
        "otp", "totp", "2fa", "mfa", "code", "codigo", "token", "verification", "verify", "verificacao",
        "verificar", "authenticator", "autenticador", "authenticacao", "autenticacao",
    )
    private val OTP_JOINED = listOf("onetime", "twofactor", "2fa", "otp", "mfa")

    /** "Códigos" que não são de login: postal, cupom, convite, cartão, telefone, captcha, CSRF… */
    private val NOT_OTP_WORDS = setOf(
        "zip", "zipcode", "postal", "postcode", "cep", "country", "area", "phone", "telefone", "celular", "mobile",
        "promo", "promotional", "coupon", "cupom", "voucher", "gift", "discount", "desconto", "referral",
        "invite", "invitation", "convite", "captcha", "csrf", "xsrf", "cvc", "cvv", "cvv2", "card", "cartao",
        "bar", "barcode", "qr", "color", "colour", "currency", "language", "tracking", "rastreio", "order",
        "pedido", "product", "produto", "sku", "search", "busca", "pesquisa", "api",
    )

    fun classify(signals: FieldSignals): FieldKind {
        val hints = signals.autofillHints.map { it.lowercase() }
        if (hints.any { it in PASSWORD_HINTS }) return FieldKind.PASSWORD
        if (hints.any(::isOtpHint)) return FieldKind.OTP
        if (hints.any { it in USERNAME_HINTS }) return FieldKind.USERNAME
        signals.htmlAutocomplete?.let { ac ->
            val tokens = ac.lowercase().split(' ').filter { it.isNotBlank() }
            if ("one-time-code" in tokens) return FieldKind.OTP
            // Outro autocomplete declarado ("postal-code", "cc-csc"…) diz o que o campo é: não é código de login.
            if (tokens.any { it != "on" && it != "off" }) return FieldKind.NONE
        }
        // Sem dica declarada: as palavras do campo.
        val words = signals.texts.flatMap(::tokens).toSet()
        if (words.isEmpty() || words.any { it in NOT_OTP_WORDS }) return FieldKind.NONE
        val joined = words.joinToString("")
        return if (words.any { it in OTP_WORDS } || OTP_JOINED.any { it in joined }) FieldKind.OTP else FieldKind.NONE
    }

    /** "verificationCode" / "codigo_de_verificação" / "otp-input" → palavras minúsculas e sem acento. */
    fun tokens(text: String): List<String> {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val split = plain.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return split.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    }
}
