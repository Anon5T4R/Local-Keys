package com.localkeys.android.data.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Por que a verificação ou a atualização não deu certo (a UI traduz cada um). */
enum class UpdateFailure { NETWORK, HTTP, PARSE, TOO_LARGE, DIGEST, STORAGE, INSTALL }

class UpdateException(val failure: UpdateFailure, val code: Int = 0, cause: Throwable? = null) :
    IOException("update: $failure ${if (code != 0) code else ""}".trim(), cause)

/**
 * A única rede do app: ler as releases do repositório no GitHub e baixar o APK escolhido. Só https, sem
 * cookies nem dados do usuário; o User-Agent é genérico. Nada aqui toca o cofre.
 */
object UpdateNet {
    /**
     * Repositórios de releases, na ordem de consulta: a org primeiro; o antigo só é consultado se a org
     * responder 404 (a mudança ainda não aconteceu ou já foi desfeita).
     */
    val RELEASE_REPOS: List<String> = listOf("Styx-Studios/Local-Keys", "Anon5T4R/Local-Keys")

    fun releasesUrl(repo: String): String = "https://api.github.com/repos/$repo/releases?per_page=30"
    private const val USER_AGENT = "LocalKeys-Android"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    /**
     * O JSON da lista de releases: consulta [RELEASE_REPOS] na ordem e cai para o seguinte só quando o
     * anterior responder 404. Qualquer outro resultado — resposta boa, 403 de cota, rede, teto de tamanho —
     * encerra a consulta (erro segue como veio). [fetch] lê uma URL e lança [UpdateException].
     */
    fun fetchReleasesJson(fetch: (url: String) -> String = ::fetchFrom): String {
        var repo = 0
        while (true) {
            try {
                return fetch(releasesUrl(RELEASE_REPOS[repo]))
            } catch (e: UpdateException) {
                val next = nextRepoAfter(repo, e) ?: throw e
                repo = next
            }
        }
    }

    /** Depois do repo de índice [tried] falhar com [failure]: o índice do próximo a consultar, ou null para parar. */
    internal fun nextRepoAfter(tried: Int, failure: UpdateException?): Int? =
        if (failure != null && failure.failure == UpdateFailure.HTTP && failure.code == 404 && tried + 1 < RELEASE_REPOS.size) {
            tried + 1
        } else {
            null
        }

    private fun fetchFrom(url: String): String {
        val conn = open(url, accept = "application/vnd.github+json")
        try {
            val bytes = conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) }
            return String(bytes, Charsets.UTF_8)
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.NETWORK, cause = e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Baixa o APK de [release] para [dest], com teto de [MAX_APK_BYTES], informando o progresso
     * (`baixados, total`; total -1 se desconhecido). Confere o SHA-256 publicado quando existe; se não confere,
     * apaga o arquivo e lança [UpdateFailure.DIGEST].
     */
    fun downloadApk(release: AndroidRelease, dest: File, onProgress: (Long, Long) -> Unit) {
        if (!ReleasePicker.isAllowedDownload(release.apkUrl)) throw UpdateException(UpdateFailure.PARSE)
        if (release.apkSize > MAX_APK_BYTES) throw UpdateException(UpdateFailure.TOO_LARGE)
        dest.parentFile?.mkdirs()
        val conn = open(release.apkUrl, accept = "application/octet-stream")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            if (total > MAX_APK_BYTES) throw UpdateException(UpdateFailure.TOO_LARGE)
            val out = try {
                dest.outputStream()
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.STORAGE, cause = e)
            }
            out.use { sink ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReport = 0L
                    onProgress(0, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > MAX_APK_BYTES) throw UpdateException(UpdateFailure.TOO_LARGE)
                        md.update(buf, 0, n)
                        sink.write(buf, 0, n)
                        if (read - lastReport >= 256 * 1024) {
                            lastReport = read
                            onProgress(read, total)
                        }
                    }
                    onProgress(read, total)
                    if (total > 0 && read != total) throw UpdateException(UpdateFailure.NETWORK)
                }
            }
            if (!Sha256.matches(Sha256.hex(md.digest()), release.sha256)) {
                throw UpdateException(UpdateFailure.DIGEST)
            }
        } catch (e: UpdateException) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            dest.delete()
            throw UpdateException(UpdateFailure.NETWORK, cause = e)
        } finally {
            conn.disconnect()
        }
    }

    /** Abre a conexão seguindo os redirecionamentos à mão: só https (o github.com manda o APK para o CDN). */
    private fun open(start: String, accept: String): HttpsURLConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val conn = try {
                val parsed = URL(url)
                if (parsed.protocol != "https") throw UpdateException(UpdateFailure.NETWORK)
                parsed.openConnection() as HttpsURLConnection
            } catch (e: UpdateException) {
                throw e
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.NETWORK, cause = e)
            } catch (e: ClassCastException) {
                throw UpdateException(UpdateFailure.NETWORK, cause = e)
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (url.startsWith("https://api.github.com/")) conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw UpdateException(UpdateFailure.NETWORK, cause = e)
            }
            when (code) {
                HttpURLConnection.HTTP_OK -> return conn
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) throw UpdateException(UpdateFailure.HTTP, code)
                    url = try {
                        URL(URL(url), location).toString()
                    } catch (e: IOException) {
                        throw UpdateException(UpdateFailure.HTTP, code, e)
                    }
                }
                else -> {
                    conn.disconnect()
                    throw UpdateException(UpdateFailure.HTTP, code)
                }
            }
        }
        throw UpdateException(UpdateFailure.NETWORK)
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw UpdateException(UpdateFailure.TOO_LARGE)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
