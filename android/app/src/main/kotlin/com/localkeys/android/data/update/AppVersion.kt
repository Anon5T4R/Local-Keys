package com.localkeys.android.data.update

/**
 * Versão semver "X.Y.Z" com pré-release opcional ("0.5.0-rc.1"). Pura (sem Android), testada na JVM.
 * Pré-release vale menos que a versão final de mesmo número; metadado de build ("+abc") é ignorado.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) :
    Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // Sem pré-release > com pré-release.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePre(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val CORE = Regex("""^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$""")
        private val PRE_PART = Regex("""^[0-9A-Za-z-]+$""")

        /** "0.4.1", "v0.4.1" ou "0.5.0-rc.1"; null para qualquer outra coisa. */
        fun parse(raw: String): AppVersion? {
            val s = raw.trim().removePrefix("v").substringBefore('+')
            val core = s.substringBefore('-')
            val preRaw = if (s.contains('-')) s.substringAfter('-') else null
            val m = CORE.matchEntire(core) ?: return null
            val pre = preRaw?.split('.') ?: emptyList()
            if (preRaw != null && (pre.isEmpty() || pre.any { !PRE_PART.matches(it) })) return null
            return AppVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), pre)
        }

        /** [candidate] é estritamente mais nova que [current]? Versão ilegível nunca é "mais nova". */
        fun isNewer(candidate: String, current: String): Boolean {
            val c = parse(candidate) ?: return false
            val cur = parse(current) ?: return false
            return c > cur
        }

        private fun comparePre(a: String, b: String): Int {
            val na = a.toLongOrNull()
            val nb = b.toLongOrNull()
            return when {
                na != null && nb != null -> compareValues(na, nb)
                na != null -> -1 // numérico vale menos que alfanumérico (semver §11)
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
