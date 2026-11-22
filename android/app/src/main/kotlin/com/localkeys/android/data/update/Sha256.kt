package com.localkeys.android.data.update

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 do APK baixado e o campo `digest` dos assets do GitHub ("sha256:<hex>", publicado desde 2025). */
object Sha256 {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** "sha256:ABC…" → "abc…"; null se ausente, de outro algoritmo ou malformado (a verificação é pulada). */
    fun fromGithubDigest(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        val algo = digest.substringBefore(':', "")
        if (!algo.equals("sha256", ignoreCase = true)) return null
        return normalize(digest.substringAfter(':'))
    }

    /** Hex de 64 dígitos em minúsculas, ou null. */
    fun normalize(hex: String): String? = hex.trim().lowercase().takeIf { HEX64.matches(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    /** O arquivo confere com o digest publicado? Sem digest publicado ([expected] null) não há o que conferir. */
    fun matches(actualHex: String, expected: String?): Boolean =
        expected == null || MessageDigest.isEqual(actualHex.toByteArray(), expected.toByteArray())
}
