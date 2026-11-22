package com.localkeys.android.data.passkey

import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Passkey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lê o formato de troca de credenciais da FIDO (CXF 1.0), o que o Gerenciador de Senhas do Google e os outros
 * gerenciadores entregam pelo Credential Exchange, e transforma em itens de login do cofre.
 *
 * Entram: senha (`basic-auth`), passkey (`passkey`), TOTP (`totp`) e nota (`note`). Um item do CXF com mais de
 * uma credencial vira um item só (login com senha, passkeys e TOTP). O resto é ignorado e contado.
 *
 * Os campos de texto podem vir como string ou como "campo editável" (`{"fieldType": …, "value": …}`): os dois
 * jeitos são aceitos.
 */
object CxfImport {

    data class Result(val items: List<Item>, val passwords: Int, val passkeys: Int, val skipped: Int)

    fun parse(json: String, now: Long = System.currentTimeMillis()): Result {
        val root = JSONObject(json)
        val items = mutableListOf<Item>()
        var passwords = 0
        var passkeys = 0
        var skipped = 0
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        for (a in 0 until accounts.length()) {
            val cxfItems = accounts.getJSONObject(a).optJSONArray("items") ?: continue
            for (i in 0 until cxfItems.length()) {
                val cxf = cxfItems.getJSONObject(i)
                val credentials = cxf.optJSONArray("credentials") ?: JSONArray()
                var username = ""
                var password = ""
                var totp = ""
                val notes = mutableListOf<String>()
                val keys = mutableListOf<Passkey>()
                for (c in 0 until credentials.length()) {
                    val cred = credentials.getJSONObject(c)
                    when (cred.optString("type")) {
                        "basic-auth" -> {
                            username = field(cred, "username")
                            password = field(cred, "password")
                            passwords++
                        }
                        "passkey" -> {
                            val pk = Passkey(
                                credentialId = cred.optString("credentialId"),
                                rpId = cred.optString("rpId"),
                                userName = field(cred, "username"),
                                userDisplayName = field(cred, "userDisplayName"),
                                userHandle = cred.optString("userHandle"),
                                key = cred.optString("key"),
                                createdAt = seconds(cxf.optLong("creationAt", 0)).takeIf { it > 0 } ?: now,
                            )
                            if (pk.credentialId.isNotBlank() && pk.rpId.isNotBlank() && pk.key.isNotBlank()) {
                                keys += pk
                                passkeys++
                                if (username.isBlank()) username = pk.userName
                            } else {
                                skipped++
                            }
                        }
                        "totp" -> totp = field(cred, "secret")
                        "note" -> field(cred, "content").takeIf { it.isNotBlank() }?.let { notes += it }
                        else -> skipped++
                    }
                }
                if (password.isBlank() && keys.isEmpty() && totp.isBlank()) {
                    if (notes.isEmpty()) skipped++ else items += note(cxf, notes, now)
                    continue
                }
                val uris = urls(cxf).ifEmpty { keys.map { "https://${it.rpId}" }.distinct() }
                val created = seconds(cxf.optLong("creationAt", 0)).takeIf { it > 0 } ?: now
                items += Item(
                    id = UUID.randomUUID().toString(),
                    kind = ItemKind.LOGIN,
                    name = cxf.optString("title").ifBlank { uris.firstOrNull()?.let(::host) ?: username.ifBlank { "Importado" } },
                    favorite = cxf.optBoolean("favorite", false),
                    folderId = null,
                    notes = notes.joinToString("\n\n"),
                    createdAt = created,
                    updatedAt = seconds(cxf.optLong("modifiedAt", 0)).takeIf { it > 0 } ?: created,
                    deletedAt = null,
                    login = Login(username = username, password = password, uris = uris, totp = totp),
                    card = null,
                    identity = null,
                    passwordHistory = null,
                    customFields = null,
                    attachments = null,
                    passkeys = keys.ifEmpty { null },
                )
            }
        }
        return Result(items, passwords, passkeys, skipped)
    }

    /** Campo de texto direto ou "campo editável" com `value`. */
    private fun field(obj: JSONObject, name: String): String {
        val v = obj.opt(name) ?: return ""
        return when (v) {
            is JSONObject -> v.optString("value", "")
            JSONObject.NULL -> ""
            else -> v.toString()
        }
    }

    /** URLs do escopo do item (sites e apps Android). */
    private fun urls(cxf: JSONObject): List<String> {
        val scope = cxf.optJSONObject("scope") ?: return emptyList()
        val out = mutableListOf<String>()
        scope.optJSONArray("urls")?.let { arr -> for (i in 0 until arr.length()) out += arr.optString(i) }
        scope.optJSONArray("androidApps")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("bundleId")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.filter { it.isNotBlank() }.distinct()
    }

    private fun note(cxf: JSONObject, notes: List<String>, now: Long): Item = Item(
        id = UUID.randomUUID().toString(),
        kind = ItemKind.NOTE,
        name = cxf.optString("title").ifBlank { "Nota importada" },
        favorite = false,
        folderId = null,
        notes = notes.joinToString("\n\n"),
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
        login = null,
        card = null,
        identity = null,
        passwordHistory = null,
        customFields = null,
        attachments = null,
    )

    /** O CXF guarda datas em segundos; o cofre, em milissegundos. */
    private fun seconds(value: Long): Long = if (value in 1 until 100_000_000_000L) value * 1000 else value

    private fun host(uri: String): String = uri.substringAfter("://").substringBefore('/').removePrefix("www.")
}
