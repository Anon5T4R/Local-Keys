package com.localkeys.android.data.autofill

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldClassifierTest {

    private fun kind(hints: List<String> = emptyList(), autocomplete: String? = null, vararg texts: String) =
        FieldClassifier.classify(FieldSignals(hints, autocomplete, texts.toList()))

    private fun words(vararg texts: String) = kind(emptyList(), null, *texts)

    @Test
    fun `declared hints win`() {
        assertEquals(FieldKind.PASSWORD, kind(listOf("password")))
        assertEquals(FieldKind.USERNAME, kind(listOf("emailAddress")))
        assertEquals(FieldKind.OTP, kind(listOf("smsOTPCode")))
        assertEquals(FieldKind.OTP, kind(listOf("2faAppOTPCode")))
        assertEquals(FieldKind.OTP, kind(listOf("emailOTPCode")))
        assertEquals(FieldKind.OTP, kind(listOf("oneTimeCode")))
        // A senha não vira código por ter "code" no id.
        assertEquals(FieldKind.PASSWORD, kind(listOf("password"), null, "verification_code"))
    }

    @Test
    fun `html one-time-code`() {
        assertEquals(FieldKind.OTP, kind(autocomplete = "one-time-code"))
        assertEquals(FieldKind.OTP, kind(autocomplete = "section-login one-time-code"))
        // Outro autocomplete declarado não é código de login, mesmo com "code" no nome.
        assertEquals(FieldKind.NONE, kind(emptyList(), "postal-code", "zip_code"))
        assertEquals(FieldKind.NONE, kind(emptyList(), "cc-csc", "security code"))
        // "off"/"on" não dizem nada: valem as palavras.
        assertEquals(FieldKind.OTP, kind(emptyList(), "off", "otp"))
    }

    @Test
    fun `words point to a one-time code`() {
        for (text in listOf(
            "verificationCode", "Código de verificação", "com.binance.dev:id/otp_input", "2FA token",
            "Authenticator code", "one-time password", "mfaCode", "Digite o código", "totp",
        )) {
            assertEquals(text, FieldKind.OTP, words(text))
        }
    }

    @Test
    fun `codes that are not login codes are ignored`() {
        for (text in listOf(
            "ZIP code", "Postal code", "CEP", "Promo code", "Código do cupom", "Country code",
            "Card security code (CVV)", "Invite code", "captcha code", "csrf_token", "barcode", "Código do pedido",
        )) {
            assertEquals(text, FieldKind.NONE, words(text))
        }
    }

    @Test
    fun `password inputs without hints are passwords unless the words say code`() {
        val pw = { texts: List<String> -> FieldClassifier.classify(FieldSignals(texts = texts, isPasswordInput = true)) }
        assertEquals(FieldKind.PASSWORD, pw(emptyList()))
        assertEquals(FieldKind.PASSWORD, pw(listOf("com.app:id/password", "Senha")))
        assertEquals(FieldKind.OTP, pw(listOf("verification_code")))
        // Cupom em campo de senha continua não sendo código de login: vale como senha.
        assertEquals(FieldKind.PASSWORD, pw(listOf("promo code")))
        // HTML: autocomplete de senha e de usuário.
        assertEquals(FieldKind.PASSWORD, kind(autocomplete = "current-password"))
        assertEquals(FieldKind.PASSWORD, kind(autocomplete = "new-password"))
        assertEquals(FieldKind.USERNAME, kind(autocomplete = "username"))
        assertEquals(FieldKind.USERNAME, kind(autocomplete = "email"))
        // O código declarado vence o tipo senha.
        assertEquals(FieldKind.OTP, FieldClassifier.classify(FieldSignals(htmlAutocomplete = "one-time-code", isPasswordInput = true)))
    }

    @Test
    fun `fields without hints or words are nothing`() {
        assertEquals(FieldKind.NONE, kind())
        assertEquals(FieldKind.NONE, words("first_name", "Nome"))
        assertEquals(FieldKind.NONE, kind(listOf("postalAddress")))
    }

    @Test
    fun `tokens split camel case and strip accents`() {
        assertEquals(listOf("codigo", "de", "verificacao"), FieldClassifier.tokens("códigoDe_verificação"))
        assertEquals(listOf("otp", "input1"), FieldClassifier.tokens("otpInput1"))
    }

    @Test
    fun `fill plan`() {
        // Só campo de código: oferece só quem tem TOTP, sem copiar (o código já vai no campo).
        assertEquals(FillPlan.Skip, FillPlan.of(hasLoginField = false, hasOtpField = true, itemHasTotp = false, copySetting = true))
        assertEquals(FillPlan.Deferred(false), FillPlan.of(false, true, itemHasTotp = true, copySetting = true))
        // Usuário/senha de um login com TOTP: monta na hora e copia, se a opção estiver ligada.
        assertEquals(FillPlan.Deferred(true), FillPlan.of(true, false, itemHasTotp = true, copySetting = true))
        assertEquals(FillPlan.Direct, FillPlan.of(true, false, itemHasTotp = true, copySetting = false))
        // Sem TOTP: direto, como sempre.
        assertEquals(FillPlan.Direct, FillPlan.of(true, true, itemHasTotp = false, copySetting = true))
        // Tela com senha e código: os dois de uma vez, sem cópia.
        assertEquals(FillPlan.Deferred(false), FillPlan.of(true, true, itemHasTotp = true, copySetting = true))
        // Nada para preencher.
        assertEquals(FillPlan.Skip, FillPlan.of(false, false, itemHasTotp = true, copySetting = true))
    }
}
