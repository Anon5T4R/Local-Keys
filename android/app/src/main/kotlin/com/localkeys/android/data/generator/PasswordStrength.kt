package com.localkeys.android.data.generator

import kotlin.math.ln

/**
 * Estimativa local e simples da força de uma senha (o desktop usa o zxcvbn; aqui não há essa dependência).
 * Conta o tamanho do alfabeto usado e o comprimento, e pune o que é óbvio: senhas comuns, um caractere só
 * repetido e sequências do teclado ou do alfabeto. Serve para orientar, não para garantir.
 */
object PasswordStrength {

    /** 0 (péssima) a 4 (ótima), a mesma escala do zxcvbn do desktop. */
    data class Result(val score: Int, val bits: Double)

    private val COMMON = setOf(
        "123456", "1234567", "12345678", "123456789", "1234567890", "password", "senha", "senha123",
        "qwerty", "qwerty123", "abc123", "111111", "000000", "admin", "letmein", "iloveyou", "welcome",
        "123123", "654321", "brasil", "password1", "mudar123",
    )
    private val SEQUENCES = listOf("abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiop", "asdfghjkl", "zxcvbnm")

    fun estimate(password: String): Result {
        if (password.isEmpty()) return Result(0, 0.0)
        if (password.lowercase() in COMMON) return Result(0, 0.0)
        var pool = 0
        if (password.any { it in 'a'..'z' }) pool += 26
        if (password.any { it in 'A'..'Z' }) pool += 26
        if (password.any { it in '0'..'9' }) pool += 10
        if (password.any { it.code in 33..126 && !it.isLetterOrDigit() }) pool += 33
        if (password.any { it == ' ' }) pool += 1
        if (password.any { it.code > 126 }) pool += 100
        val effective = effectiveLength(password)
        val bits = effective * (ln(pool.coerceAtLeast(1).toDouble()) / ln(2.0))
        val score = when {
            bits < 28 -> 0
            bits < 36 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
        return Result(score, bits)
    }

    /**
     * Comprimento "útil": cada caractere que repete o anterior ou continua uma sequência conhecida vale um
     * quarto de um caractere novo.
     */
    private fun effectiveLength(password: String): Double {
        val lower = password.lowercase()
        var total = 1.0
        for (i in 1 until lower.length) {
            val prev = lower[i - 1]
            val cur = lower[i]
            val predictable = cur == prev || SEQUENCES.any { seq ->
                val at = seq.indexOf(prev)
                at >= 0 && at + 1 < seq.length && seq[at + 1] == cur
            }
            total += if (predictable) 0.25 else 1.0
        }
        return total
    }
}
