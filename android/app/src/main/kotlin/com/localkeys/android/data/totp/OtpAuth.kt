package com.localkeys.android.data.totp

import java.net.URLDecoder

/**
 * Lê o que vem de um QR de 2FA ou da área de transferência: um `otpauth://totp/Emissor:conta?secret=…` ou só a
 * chave base32. O que vai para o cofre é **só a chave base32 limpa** ([secret]) — é o que o desktop lê no
 * campo `login.totp` (ele gera o código direto da chave e não entende a URI).
 */
object OtpAuth {

    data class Parsed(
        /** Chave base32 limpa (maiúsculas, sem espaços, hífens nem `=`). */
        val secret: String,
        /** Emissor (`issuer=` ou o pedaço antes do ":" no rótulo), se veio. */
        val issuer: String?,
        /** Conta (o pedaço depois do ":" no rótulo), se veio. */
        val account: String?,
        /**
         * O QR pede algo que o Local Keys não gera (só 6 dígitos, 30 s e SHA-1, como o desktop): a chave é
         * guardada mesmo assim, mas os códigos podem não bater. Null = padrão.
         */
        val unsupported: Unsupported?,
    )

    data class Unsupported(val digits: Int?, val period: Int?, val algorithm: String?)

    sealed interface Result {
        data class Ok(val value: Parsed) : Result
        /** Vazio, URI sem `secret`, `otpauth://hotp` (contador) ou chave que não é base32. */
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, NOT_TOTP, NO_SECRET, BAD_KEY }

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (!text.startsWith("otpauth://", ignoreCase = true)) {
            return bareKey(text)?.let { Result.Ok(Parsed(it, null, null, null)) } ?: Result.Invalid(Reason.BAD_KEY)
        }
        val afterScheme = text.substring("otpauth://".length)
        val type = afterScheme.substringBefore('/').lowercase()
        if (type != "totp") return Result.Invalid(Reason.NOT_TOTP)
        val label = decode(afterScheme.substringAfter('/', "").substringBefore('?'))
        val params = afterScheme.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() }
            .associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', "")) }
        val secretRaw = params["secret"]?.takeIf { it.isNotBlank() } ?: return Result.Invalid(Reason.NO_SECRET)
        val secret = bareKey(secretRaw) ?: return Result.Invalid(Reason.BAD_KEY)

        val labelIssuer = label.substringBefore(':', "").trim().takeIf { label.contains(':') && it.isNotEmpty() }
        val account = (if (label.contains(':')) label.substringAfter(':') else label).trim().ifEmpty { null }
        val issuer = params["issuer"]?.trim()?.ifEmpty { null } ?: labelIssuer

        val digits = params["digits"]?.toIntOrNull()?.takeIf { it != Totp.DIGITS }
        val period = params["period"]?.toIntOrNull()?.takeIf { it.toLong() != Totp.PERIOD }
        val algorithm = params["algorithm"]?.uppercase()?.takeIf { it.isNotEmpty() && it != "SHA1" }
        val unsupported = if (digits != null || period != null || algorithm != null) Unsupported(digits, period, algorithm) else null
        return Result.Ok(Parsed(secret, issuer, account, unsupported))
    }

    /** A chave limpa se for base32 que decodifica, senão null. Aceita espaços, hífens, minúsculas e `=`. */
    fun bareKey(raw: String): String? {
        val cleaned = Totp.sanitize(raw).trimEnd('=')
        if (cleaned.isEmpty() || cleaned.any { it !in BASE32 }) return null
        return runCatching { Base32.decode(cleaned) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cleaned }
    }

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
