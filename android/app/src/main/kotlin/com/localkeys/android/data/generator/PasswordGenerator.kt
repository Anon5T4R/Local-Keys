package com.localkeys.android.data.generator

import java.security.SecureRandom

/**
 * Gerador de senhas e passphrases — port do `generator.rs` do desktop (o medidor de força simples mora em
 * [PasswordStrength]). Toda aleatoriedade vem do `SecureRandom` (CSPRNG do SO), nunca de `Random`.
 */
object PasswordGenerator {

    private val rng = SecureRandom()

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    data class Options(
        val length: Int = 20,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
    )

    /**
     * Gera uma senha respeitando as classes escolhidas, garantindo ao menos um
     * caractere de cada classe ativa (os obrigatórios também são sorteados e a
     * ordem final é embaralhada — Fisher-Yates).
     */
    fun generate(opts: Options = Options()): String {
        val classes = buildList {
            if (opts.lowercase) add(LOWER)
            if (opts.uppercase) add(UPPER)
            if (opts.digits) add(DIGITS)
            if (opts.symbols) add(SYMBOLS)
        }
        require(classes.isNotEmpty()) { "selecione ao menos uma classe de caractere" }
        val length = opts.length.coerceIn(4, 128)
        require(length >= classes.size) { "comprimento menor que o número de classes exigidas" }

        val pool = classes.joinToString("")
        val chars = CharArray(length)

        // Um obrigatório por classe ativa...
        classes.forEach { chars[classes.indexOf(it)] = it[rng.nextInt(it.length)] }
        // ...o resto do pool geral.
        for (i in classes.size until length) {
            chars[i] = pool[rng.nextInt(pool.length)]
        }
        // Embaralha (Fisher-Yates) para não fixar os obrigatórios no início.
        for (i in length - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    data class PassphraseOptions(
        val words: Int = 5,
        val separator: String = "-",
        val capitalize: Boolean = false,
        val includeNumber: Boolean = true,
    )

    /**
     * A wordlist grande da EFF (7776 palavras, a mesma do desktop), embutida como recurso Java para valer
     * também nos testes JVM. Linhas no formato `11111<TAB>abacus`.
     */
    val wordlist: List<String> by lazy {
        val stream = PasswordGenerator::class.java.getResourceAsStream("/wordlists/eff_large_en.txt")
            ?: throw IllegalStateException("wordlist ausente")
        stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line -> line.substringAfter('	', "").trim().takeIf { it.isNotEmpty() } }.toList()
        }
    }

    /** Passphrase estilo diceware: N palavras sorteadas (3 a 12), como o `generate_passphrase` do desktop. */
    fun passphrase(opts: PassphraseOptions = PassphraseOptions()): String {
        val list = wordlist
        val count = opts.words.coerceIn(3, 12)
        val words = MutableList(count) {
            val w = list[rng.nextInt(list.size)]
            if (opts.capitalize) w.replaceFirstChar { it.uppercaseChar() } else w
        }
        if (opts.includeNumber) {
            // Um dígito numa palavra aleatória (endurecimento leve contra dicionário).
            val idx = rng.nextInt(words.size)
            words[idx] = words[idx] + rng.nextInt(10)
        }
        val sep = opts.separator.ifEmpty { "-" }
        return words.joinToString(sep)
    }
}
