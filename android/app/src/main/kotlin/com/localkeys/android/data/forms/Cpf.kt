package com.localkeys.android.data.forms

/**
 * Ajuda para o campo personalizado "CPF": formata enquanto digita e avisa (sem bloquear) se o dígito
 * verificador não fecha. O valor continua texto livre: o que foi colado em outro formato fica como está.
 */
object Cpf {

    /** O campo chama "CPF"? (o atalho cria com esse nome; quem digitar o nome à mão também ganha a ajuda) */
    fun isCpfField(name: String): Boolean = name.trim().equals("CPF", ignoreCase = true)

    /**
     * Formata se o texto for só dígitos (e a pontuação do próprio CPF): "12345678909" → "123.456.789-09",
     * parcial também ("1234" → "123.4"). Outro formato (letras, barras…) volta igual.
     */
    fun format(raw: String): String {
        if (raw.any { !it.isDigit() && it != '.' && it != '-' && it != ' ' }) return raw
        val d = raw.filter { it.isDigit() }
        if (d.length > 11) return raw
        val out = StringBuilder()
        d.forEachIndexed { i, c ->
            when (i) {
                3, 6 -> out.append('.')
                9 -> out.append('-')
            }
            out.append(c)
        }
        return out.toString()
    }

    /** CPF completo (11 dígitos) com os dois verificadores certos. Sequências repetidas não valem. */
    fun isValid(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }
        if (d.length != 11 || d.all { it == d[0] }) return false
        val n = d.map { it - '0' }
        fun check(len: Int): Int {
            val sum = (0 until len).sumOf { n[it] * (len + 1 - it) }
            val r = (sum * 10) % 11
            return if (r == 10) 0 else r
        }
        return check(9) == n[9] && check(10) == n[10]
    }

    /** Avisar? Só com os 11 dígitos digitados e o verificador errado. */
    fun warning(raw: String): Boolean = raw.count { it.isDigit() } == 11 && !isValid(raw)
}
