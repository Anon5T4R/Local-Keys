package com.localkeys.android.data.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Uma release do Keys Android publicada no GitHub, com o APK que a atualização baixa. */
data class AndroidRelease(
    val version: String,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** SHA-256 em hex minúsculo do campo `digest` do asset; null quando o GitHub não o publicou. */
    val sha256: String?,
) {
    /** Forma guardada nas preferências (a última release achada sobrevive ao reinício). */
    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("tag", tag)
        .put("apkName", apkName)
        .put("apkUrl", apkUrl)
        .put("apkSize", apkSize)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(raw: String?): AndroidRelease? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(raw)
                AndroidRelease(
                    version = o.getString("version"),
                    tag = o.getString("tag"),
                    apkName = o.getString("apkName"),
                    apkUrl = o.getString("apkUrl"),
                    apkSize = o.getLong("apkSize"),
                    sha256 = if (o.isNull("sha256")) null else Sha256.normalize(o.getString("sha256")),
                ).takeIf { ReleasePicker.isAllowedDownload(it.apkUrl) && AppVersion.parse(it.version) != null }
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/**
 * Escolhe, da lista de releases do repositório (`GET /repos/{owner}/{repo}/releases`), a do Keys Android. O
 * repositório também publica o desktop (`vX.Y.Z`, a "latest"): só valem tags `android-vX.Y.Z` que não sejam
 * rascunho nem pré-release e que tragam um `.apk`. Pura (org.json), testada na JVM.
 */
object ReleasePicker {
    const val TAG_PREFIX = "android-v"

    /** Todas as releases Android válidas, da mais nova para a mais velha. Lança [UpdateException] (PARSE). */
    fun candidates(json: String): List<AndroidRelease> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            throw UpdateException(UpdateFailure.PARSE)
        }
        val out = mutableListOf<AndroidRelease>()
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            parseRelease(release)?.let(out::add)
        }
        return out.sortedByDescending { AppVersion.parse(it.version) }
    }

    /** A release Android mais nova publicada, ou null se não há nenhuma. */
    fun newest(json: String): AndroidRelease? = candidates(json).firstOrNull()

    /** A release mais nova se ela for mais nova que [currentVersion]; null quando o app está em dia. */
    fun newerThan(json: String, currentVersion: String): AndroidRelease? =
        newest(json)?.takeIf { AppVersion.isNewer(it.version, currentVersion) }

    /** O APK só vem do github.com por https (o GitHub redireciona para o próprio CDN, também por https). */
    fun isAllowedDownload(url: String): Boolean = url.startsWith("https://github.com/")

    private fun parseRelease(o: JSONObject): AndroidRelease? {
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val tag = o.optString("tag_name", "")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val version = tag.removePrefix(TAG_PREFIX)
        val parsed = AppVersion.parse(version) ?: return null
        if (parsed.pre.isNotEmpty()) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true) || !isAllowedDownload(url)) continue
            val digest = if (asset.isNull("digest")) null else asset.optString("digest", "")
            return AndroidRelease(
                version = version,
                tag = tag,
                apkName = name,
                apkUrl = url,
                apkSize = asset.optLong("size", -1L),
                sha256 = Sha256.fromGithubDigest(digest),
            )
        }
        return null
    }
}
