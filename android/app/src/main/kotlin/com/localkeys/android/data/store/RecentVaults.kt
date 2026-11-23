package com.localkeys.android.data.store

import org.json.JSONArray
import org.json.JSONObject

/** Um cofre aberto recentemente: a URI do SAF (com permissão persistida) e o nome que o provedor mostra. */
data class RecentVault(val uri: String, val name: String)

/**
 * A lista de cofres recentes (trabalho, pessoal…), do mais recente para o mais antigo, guardada em JSON nas
 * preferências. Só referências: tirar da lista esquece o cofre, nunca apaga o arquivo.
 */
object RecentVaults {
    const val MAX = 10

    /** Põe (ou sobe) [uri] no topo, com o nome mais novo; corta em [MAX]. */
    fun touch(list: List<RecentVault>, uri: String, name: String?): List<RecentVault> {
        val previous = list.firstOrNull { it.uri == uri }
        val label = name?.takeIf { it.isNotBlank() } ?: previous?.name ?: fallbackName(uri)
        return (listOf(RecentVault(uri, label)) + list.filterNot { it.uri == uri }).take(MAX)
    }

    fun remove(list: List<RecentVault>, uri: String): List<RecentVault> = list.filterNot { it.uri == uri }

    fun encode(list: List<RecentVault>): String =
        JSONArray().apply {
            list.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        }.toString()

    /** Lê o que [encode] gravou; entrada estragada vira lista vazia (nunca derruba o app). */
    fun decode(raw: String?): List<RecentVault> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecentVault(uri, o.optString("name").ifBlank { fallbackName(uri) })
            }.distinctBy { it.uri }
        }.getOrDefault(emptyList())
    }

    /** Nome de reserva: o último pedaço da URI ("…/document/primary%3Alocalkeys.tkeys" → "localkeys.tkeys"). */
    fun fallbackName(uri: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(uri, "UTF-8") }.getOrDefault(uri)
        return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { uri }
    }
}

/**
 * As chaves de cofre cifradas pela biometria, **uma por cofre** (URI → `iv || ct` em hex). A chave de um cofre
 * só é usada para destrancar aquele cofre: trocar de cofre nunca tenta a chave de outro.
 */
object BiometricKeys {
    fun encode(map: Map<String, String>): String =
        JSONObject().apply { map.forEach { (uri, hex) -> put(uri, hex) } }.toString()

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * Migra a chave única de antes (v0.5.0 e anteriores, sem dono): ela só pode ser do cofre que estava aberto,
     * então passa a ser dele. Sem cofre ou sem biometria ligada, a chave velha é descartada.
     */
    fun migrateLegacy(
        current: Map<String, String>,
        legacyHex: String?,
        legacyEnabled: Boolean,
        vaultUri: String?,
    ): Map<String, String> {
        if (legacyHex.isNullOrBlank() || !legacyEnabled || vaultUri == null || current.containsKey(vaultUri)) {
            return current
        }
        return current + (vaultUri to legacyHex)
    }
}
