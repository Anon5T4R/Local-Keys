package com.localkeys.android.data.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseAndStrengthTest {

    @Test
    fun wordlist_tem_7776_palavras_como_no_desktop() {
        assertEquals(7776, PasswordGenerator.wordlist.size)
        assertEquals("abacus", PasswordGenerator.wordlist.first())
    }

    @Test
    fun passphrase_tem_o_numero_de_palavras_e_o_separador() {
        val p = PasswordGenerator.passphrase(
            PasswordGenerator.PassphraseOptions(words = 6, separator = ".", includeNumber = false),
        )
        val parts = p.split(".")
        assertEquals(6, parts.size)
        assertTrue(parts.all { it in PasswordGenerator.wordlist })
    }

    @Test
    fun passphrase_com_numero_e_maiuscula() {
        val p = PasswordGenerator.passphrase(
            PasswordGenerator.PassphraseOptions(words = 4, capitalize = true, includeNumber = true),
        )
        val parts = p.split("-")
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.first().isUpperCase() })
        assertEquals(1, p.count { it.isDigit() })
    }

    @Test
    fun passphrase_respeita_os_limites() {
        val few = PasswordGenerator.passphrase(PasswordGenerator.PassphraseOptions(words = 1, includeNumber = false))
        val many = PasswordGenerator.passphrase(PasswordGenerator.PassphraseOptions(words = 40, includeNumber = false))
        assertEquals(3, few.split("-").size)
        assertEquals(12, many.split("-").size)
    }

    @Test
    fun forca_distingue_fraca_de_forte() {
        assertEquals(0, PasswordStrength.estimate("").score)
        assertEquals(0, PasswordStrength.estimate("123456").score)
        assertEquals(0, PasswordStrength.estimate("Password").score)
        assertTrue(PasswordStrength.estimate("aaaaaaaaaaaa").score <= 1)
        assertTrue(PasswordStrength.estimate("abcdefgh").score <= 1)
        assertEquals(4, PasswordStrength.estimate(PasswordGenerator.generate()).score)
        assertEquals(4, PasswordStrength.estimate(PasswordGenerator.passphrase()).score)
        assertTrue(PasswordStrength.estimate("Tr0ub4dor&3").score in 2..3)
    }
}
