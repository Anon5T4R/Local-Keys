package com.localkeys.android.data.import

import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.Identity
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Import do export **não-cifrado** do Bitwarden (JSON) → itens do LocalKeys.
 * Port do `parseBitwardenJson` do desktop (`src/import.ts`): mapeia os 4 tipos
 * (1 login, 2 nota, 3 cartão, 4 identidade) e preserva favorito.
 */
object BitwardenImport {

    fun parse(text: String): List<Item> {
        val objects = try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } else {
                val obj = JSONObject(text)
                val arr = obj.optJSONArray("items") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
        } catch (e: Exception) {
            throw ImportError("JSON inválido: ${e.message}")
        }
        return objects.map(::bwToItem)
    }

    private fun bwKind(t: Int): ItemKind = when (t) {
        2 -> ItemKind.NOTE
        3 -> ItemKind.CARD
        4 -> ItemKind.IDENTITY
        else -> ItemKind.LOGIN
    }

    private fun bwToItem(b: JSONObject): Item {
        val kind = bwKind(b.optInt("type", 1))
        val now = System.currentTimeMillis()
        val item = Item(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = b.text("name").ifEmpty { "(sem nome)" },
            favorite = b.optBoolean("favorite", false),
            folderId = null,
            notes = b.text("notes"),
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
        return when {
            kind == ItemKind.LOGIN && !b.isNull("login") ->
                item.copy(login = bwLogin(b.getJSONObject("login")))
            kind == ItemKind.CARD && !b.isNull("card") ->
                item.copy(card = bwCard(b.getJSONObject("card")))
            kind == ItemKind.IDENTITY && !b.isNull("identity") ->
                item.copy(identity = bwIdentity(b.getJSONObject("identity")))
            else -> item
        }
    }

    private fun bwLogin(l: JSONObject): Login {
        val uris = l.optJSONArray("uris")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.text("uri")?.takeIf { it.isNotEmpty() }
            }
        } ?: emptyList()
        return Login(
            username = l.text("username"),
            password = l.text("password"),
            uris = uris.ifEmpty { listOf("") },
            totp = Importers.extractSecret(l.text("totp")),
        )
    }

    private fun bwCard(c: JSONObject): Card = Card(
        cardholder = c.text("cardholderName"),
        brand = c.text("brand"),
        number = c.text("number"),
        exp = listOf(c.text("expMonth"), c.text("expYear"))
            .filter { it.isNotEmpty() }
            .joinToString("/"),
        code = c.text("code"),
    )

    private fun bwIdentity(i: JSONObject): Identity = Identity(
        firstName = i.text("firstName"),
        lastName = i.text("lastName"),
        email = i.text("email"),
        phone = i.text("phone"),
        address = listOf(
            i.text("address1"),
            i.text("city"),
            i.text("state"),
            i.text("postalCode"),
            i.text("country"),
        ).filter { it.isNotEmpty() }.joinToString(", "),
    )
}

/**
 * Texto do campo, vazio se ele faltar ou vier `null`. O export do Bitwarden traz `"totp": null`, `"notes": null`…
 * e o `optString` devolve a palavra "null" nesses casos (todo login ganhava um TOTP falso).
 */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
