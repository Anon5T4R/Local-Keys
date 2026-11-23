package com.localkeys.android.data.forms

/**
 * Regras do formulário de cartão. O cofre guarda o número **só com dígitos** (é o que o Bitwarden exporta e o
 * que os importadores gravam); os grupos de 4 (Amex 4-6-5) são só da tela. A validade fica como `MM/AA` (ou
 * `MM/AAAA`, que é o que vem do Bitwarden); o código, só dígitos.
 */
object CardFormat {

    const val MAX_DIGITS = 19

    enum class Brand(val label: String) {
        VISA("Visa"), MASTERCARD("Mastercard"), AMEX("Amex"), ELO("Elo"), HIPERCARD("Hipercard"),
        DINERS("Diners"), DISCOVER("Discover"), JCB("JCB"),
    }

    fun digits(s: String): String = s.filter { it.isDigit() }

    /**
     * Bandeira pelo começo do número. Elo e Hipercard antes de Visa/Discover/Diners: vários BINs deles começam
     * igual aos das bandeiras grandes. Null = não dá para saber (ainda).
     */
    fun brandOf(number: String): Brand? {
        val d = digits(number)
        if (d.isEmpty()) return null
        fun prefix(n: Int): Int? = d.take(n).takeIf { it.length == n }?.toInt()
        val p6 = prefix(6)
        if (p6 != null && isElo(p6)) return Brand.ELO
        if (p6 == 606282 || d.startsWith("384100") || d.startsWith("384140") || d.startsWith("384160")) return Brand.HIPERCARD
        val p2 = prefix(2)
        val p3 = prefix(3)
        val p4 = prefix(4)
        return when {
            p2 == 34 || p2 == 37 -> Brand.AMEX
            p4 != null && p4 in 3528..3589 -> Brand.JCB
            p3 != null && p3 in 300..305 -> Brand.DINERS
            p2 == 36 || p2 == 38 || p2 == 39 -> Brand.DINERS
            d.startsWith("4") -> Brand.VISA
            p2 != null && p2 in 51..55 -> Brand.MASTERCARD
            p4 != null && p4 in 2221..2720 -> Brand.MASTERCARD
            d.startsWith("6011") || d.startsWith("65") -> Brand.DISCOVER
            p3 != null && p3 in 644..649 -> Brand.DISCOVER
            p6 != null && p6 in 622126..622925 -> Brand.DISCOVER
            else -> null
        }
    }

    private val ELO_RANGES = listOf(
        401178..401179, 431274..431274, 438935..438935, 451416..451416, 457393..457393, 457631..457632,
        504175..504175, 506699..506778, 509000..509999, 627780..627780, 636297..636297, 636368..636368,
        650031..650033, 650035..650051, 650405..650439, 650485..650538, 650541..650598, 650700..650718,
        650720..650727, 650901..650920, 651652..651679, 655000..655019, 655021..655058,
    )

    private fun isElo(bin: Int): Boolean = ELO_RANGES.any { bin in it }

    /** Tamanhos dos grupos na tela: Amex 4-6-5; o resto de 4 em 4. */
    fun groups(number: String): List<Int> =
        if (brandOf(number) == Brand.AMEX) listOf(4, 6, 5) else List(5) { 4 }

    /** "4111111111111111" → "4111 1111 1111 1111"; Amex "378282246310005" → "3782 822463 10005". */
    fun group(number: String): String {
        val d = digits(number)
        val out = StringBuilder()
        var i = 0
        for (size in groups(d)) {
            if (i >= d.length) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(d, i, minOf(i + size, d.length))
            i += size
        }
        if (i < d.length) out.append(' ').append(d, i, d.length)
        return out.toString()
    }

    /** Luhn: só diz se fecha. Quem chama decide quando avisar (número completo) — nunca bloqueia. */
    fun luhnOk(number: String): Boolean {
        val d = digits(number)
        if (d.length < 12) return false
        var sum = 0
        d.reversed().forEachIndexed { i, c ->
            var n = c - '0'
            if (i % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return sum % 10 == 0
    }

    /** Avisar do Luhn? Só com o número completo (13+ dígitos) e que não fecha. */
    fun luhnWarning(number: String): Boolean = digits(number).length >= 13 && !luhnOk(number)

    /** Validade enquanto digita: "1" → "1", "12" → "12", "123" → "12/3", "12/2027" → "12/2027". */
    fun expiry(raw: String): String {
        val d = digits(raw).take(6)
        return if (d.length <= 2) d else d.take(2) + "/" + d.drop(2)
    }

    /** O código de segurança: só dígitos, até 4. */
    fun cvv(raw: String): String = digits(raw).take(4)
}
