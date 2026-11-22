package com.localkeys.android.data.forms

import com.localkeys.android.data.totp.OtpAuth
import com.localkeys.android.data.totp.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {

    // ── otpauth ──────────────────────────────────────────────────────────

    private fun ok(raw: String): OtpAuth.Parsed = (OtpAuth.parse(raw) as OtpAuth.Result.Ok).value

    @Test
    fun `otpauth with issuer and account`() {
        val p = ok("otpauth://totp/Binance:joao%40example.com?secret=jbswy3dpehpk3pxp&issuer=Binance")
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
        assertEquals("Binance", p.issuer)
        assertEquals("joao@example.com", p.account)
        assertNull(p.unsupported)
    }

    @Test
    fun `issuer from the label when the parameter is missing`() {
        val p = ok("otpauth://totp/GitHub:joao?secret=JBSWY3DPEHPK3PXP")
        assertEquals("GitHub", p.issuer)
        assertEquals("joao", p.account)
        val noIssuer = ok("otpauth://totp/joao?secret=JBSWY3DPEHPK3PXP")
        assertNull(noIssuer.issuer)
        assertEquals("joao", noIssuer.account)
    }

    @Test
    fun `bare keys with spaces, lowercase and padding`() {
        assertEquals("JBSWY3DPEHPK3PXP", ok("jbsw y3dp ehpk 3pxp").secret)
        assertEquals("JBSWY3DPEHPK3PXP", ok("JBSWY3DP-EHPK3PXP==").secret)
        // O que vai para o cofre gera o mesmo código que o desktop geraria da chave.
        assertEquals(Totp.generate("JBSWY3DPEHPK3PXP", 59), Totp.generate(ok("jbsw y3dp ehpk 3pxp").secret, 59))
    }

    @Test
    fun `non default parameters are flagged`() {
        val p = ok("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256")
        assertEquals(OtpAuth.Unsupported(8, 60, "SHA256"), p.unsupported)
        assertNull(ok("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=6&period=30&algorithm=SHA1").unsupported)
    }

    @Test
    fun `invalid inputs say why`() {
        assertEquals(OtpAuth.Result.Invalid(OtpAuth.Reason.EMPTY), OtpAuth.parse("  "))
        assertEquals(OtpAuth.Result.Invalid(OtpAuth.Reason.NOT_TOTP), OtpAuth.parse("otpauth://hotp/X?secret=JBSWY3DP&counter=1"))
        assertEquals(OtpAuth.Result.Invalid(OtpAuth.Reason.NO_SECRET), OtpAuth.parse("otpauth://totp/X?issuer=X"))
        assertEquals(OtpAuth.Result.Invalid(OtpAuth.Reason.BAD_KEY), OtpAuth.parse("senha1!"))
        assertEquals(OtpAuth.Result.Invalid(OtpAuth.Reason.BAD_KEY), OtpAuth.parse("otpauth://totp/X?secret=0189"))
    }

    // ── cartão ───────────────────────────────────────────────────────────

    @Test
    fun `brands from the number`() {
        val cases = mapOf(
            "4111111111111111" to CardFormat.Brand.VISA,
            "5555555555554444" to CardFormat.Brand.MASTERCARD,
            "2221000000000009" to CardFormat.Brand.MASTERCARD,
            "378282246310005" to CardFormat.Brand.AMEX,
            "6362970000457013" to CardFormat.Brand.ELO,
            "5067000000000000" to CardFormat.Brand.ELO,
            "4389350000000000" to CardFormat.Brand.ELO,
            "6062825624254001" to CardFormat.Brand.HIPERCARD,
            "30569309025904" to CardFormat.Brand.DINERS,
            "6011111111111117" to CardFormat.Brand.DISCOVER,
            "3530111333300000" to CardFormat.Brand.JCB,
        )
        cases.forEach { (number, brand) -> assertEquals(number, brand, CardFormat.brandOf(number)) }
        assertNull(CardFormat.brandOf(""))
        assertNull(CardFormat.brandOf("9999"))
    }

    @Test
    fun `groups of four and amex 4-6-5`() {
        assertEquals("4111 1111 1111 1111", CardFormat.group("4111111111111111"))
        assertEquals("4111 11", CardFormat.group("411111"))
        assertEquals("3782 822463 10005", CardFormat.group("378282246310005"))
        assertEquals("4111111111111111", CardFormat.digits("4111 1111-1111 1111"))
    }

    @Test
    fun `luhn warns only on complete wrong numbers`() {
        assertTrue(CardFormat.luhnOk("4111 1111 1111 1111"))
        assertFalse(CardFormat.luhnWarning("4111111111111111"))
        assertTrue(CardFormat.luhnWarning("4111111111111112"))
        assertFalse(CardFormat.luhnWarning("411111111111")) // incompleto: sem aviso ainda
    }

    @Test
    fun `expiry and cvv masks`() {
        assertEquals("1", CardFormat.expiry("1"))
        assertEquals("12", CardFormat.expiry("12"))
        assertEquals("12/3", CardFormat.expiry("123"))
        assertEquals("12/30", CardFormat.expiry("12/30"))
        assertEquals("01/2027", CardFormat.expiry("01/2027"))
        assertEquals("123", CardFormat.cvv("1a2b3"))
        assertEquals("1234", CardFormat.cvv("123456"))
    }

    // ── CPF ──────────────────────────────────────────────────────────────

    @Test
    fun `cpf formats digits and keeps other formats`() {
        assertEquals("529.982.247-25", Cpf.format("52998224725"))
        assertEquals("529.982.2", Cpf.format("5299822"))
        assertEquals("529.982.247-25", Cpf.format("529.982.247-25"))
        assertEquals("529982247/25", Cpf.format("529982247/25"))
        assertEquals("CPF 123", Cpf.format("CPF 123"))
    }

    @Test
    fun `cpf check digit`() {
        assertTrue(Cpf.isValid("529.982.247-25"))
        assertFalse(Cpf.isValid("529.982.247-26"))
        assertFalse(Cpf.isValid("111.111.111-11"))
        assertTrue(Cpf.warning("52998224726"))
        assertFalse(Cpf.warning("5299822472")) // incompleto
        assertTrue(Cpf.isCpfField(" cpf "))
        assertFalse(Cpf.isCpfField("CPF do pai"))
    }
}
