package com.localkeys.android.data.vault

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema do vault (dentro do blob cifrado) — espelho do `vault.rs` do desktop.
 * `version` permite migrar depois.
 *
 * ESCRITA CONSERVADORA, como no desktop: o que este app não conhece atravessa
 * intacto. Cada objeto guarda em [Extra] as chaves que não leu e as devolve ao
 * gravar; um item de tipo desconhecido (um `kind` novo do desktop) fica em
 * [Vault.otherItems], fora da lista, e volta para o arquivo como veio.
 */
data class Vault(
    val version: Int,
    val folders: List<Folder>,
    val items: List<Item>,
    val otherItems: List<Extra> = emptyList(),
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): String {
        val json = extra.base()
        json.put("version", version)
        val foldersArr = JSONArray()
        folders.forEach { foldersArr.put(it.toJson()) }
        json.put("folders", foldersArr)
        val itemsArr = JSONArray()
        items.forEach { itemsArr.put(it.toJson()) }
        otherItems.forEach { itemsArr.put(it.base()) }
        json.put("items", itemsArr)
        return json.toString()
    }

    companion object {
        private val KNOWN = setOf("version", "folders", "items")

        fun empty(): Vault = Vault(version = 1, folders = emptyList(), items = emptyList())

        fun parse(json: String): Vault {
            val obj = JSONObject(json)
            val version = obj.optInt("version", 1)
            val folders = obj.optJSONArray("folders")?.let { arr ->
                (0 until arr.length()).map { Folder.parse(arr.getJSONObject(it)) }
            } ?: emptyList()
            val items = mutableListOf<Item>()
            val others = mutableListOf<Extra>()
            obj.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val raw = arr.getJSONObject(i)
                    if (ItemKind.fromWireOrNull(raw.optString("kind", "")) != null) {
                        items += Item.parse(raw)
                    } else {
                        others += Extra(raw.toString())
                    }
                }
            }
            return Vault(version, folders, items, others, Extra.unknownKeys(obj, KNOWN))
        }
    }
}

/**
 * Chaves de um objeto JSON que este app não conhece, guardadas como texto (a
 * igualdade das data classes compara o conteúdo, não a identidade).
 */
data class Extra(val json: String) {
    /** Um `JSONObject` novo com as chaves guardadas; os campos conhecidos são gravados por cima. */
    fun base(): JSONObject = JSONObject(json)

    companion object {
        val NONE = Extra("{}")

        fun unknownKeys(obj: JSONObject, known: Set<String>): Extra {
            val out = JSONObject()
            obj.keys().forEach { key -> if (key !in known) out.put(key, obj.get(key)) }
            return if (out.length() == 0) NONE else Extra(out.toString())
        }
    }
}

data class Folder(
    val id: String,
    val name: String,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        fun parse(json: JSONObject): Folder = Folder(
            id = json.getString("id"),
            name = json.getString("name"),
            extra = Extra.unknownKeys(json, setOf("id", "name")),
        )
    }
}

enum class ItemKind(val wire: String) {
    LOGIN("login"),
    NOTE("note"),
    CARD("card"),
    IDENTITY("identity");

    companion object {
        fun fromWire(value: String): ItemKind =
            fromWireOrNull(value) ?: throw IllegalArgumentException("tipo de item desconhecido: $value")

        fun fromWireOrNull(value: String): ItemKind? = entries.firstOrNull { it.wire == value }
    }
}

data class Item(
    val id: String,
    val kind: ItemKind,
    val name: String,
    val favorite: Boolean,
    val folderId: String?,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val login: Login?,
    val card: Card?,
    val identity: Identity?,
    // Opcionais no desktop (`types.ts` usa `?`): omitidos no JSON quando ausentes.
    val passwordHistory: List<PasswordHistoryEntry>?,
    val customFields: List<CustomField>?,
    val attachments: List<Attachment>?,
    val extra: Extra = Extra.NONE,
    /** Passkeys deste login (o Local Keys como provedor de credenciais, ou importadas de outro gerenciador). */
    val passkeys: List<Passkey>? = null,
) {
    /** Passkeys vivas. */
    val livePasskeys: List<Passkey> get() = passkeys.orEmpty()

    /**
     * Na lixeira? Mesma regra do desktop e do oficial: `deletedAt` com um número diferente de 0 (o 0 é falso no
     * JS, `if (i.deletedAt)`). Um item na lixeira não aparece na lista, no autofill nem no export.
     */
    val inTrash: Boolean get() = (deletedAt ?: 0L) != 0L

    /** Anexos visíveis (fora da lixeira de anexos). */
    val liveAttachments: List<Attachment> get() = attachments.orEmpty().filter { it.live }

    /** Anexos na lixeira de anexos do item. */
    val trashedAttachments: List<Attachment> get() = attachments.orEmpty().filterNot { it.live }

    fun toJson(): JSONObject = extra.base().apply {
        put("id", id)
        put("kind", kind.wire)
        put("name", name)
        put("favorite", favorite)
        if (folderId != null) put("folderId", folderId) else put("folderId", JSONObject.NULL)
        put("notes", notes)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (deletedAt != null) put("deletedAt", deletedAt) else put("deletedAt", JSONObject.NULL)
        login?.let { put("login", it.toJson()) }
        card?.let { put("card", it.toJson()) }
        identity?.let { put("identity", it.toJson()) }
        // Uma parte apagada (null) sai do arquivo, mesmo que o JSON original a tivesse.
        if (login == null) remove("login")
        if (card == null) remove("card")
        if (identity == null) remove("identity")
        if (passwordHistory == null) remove("passwordHistory")
        if (customFields == null) remove("customFields")
        if (attachments == null) remove("attachments")
        passkeys?.let { put("passkeys", JSONArray().apply { it.forEach { e -> put(e.toJson()) } }) }
        if (passkeys == null) remove("passkeys")
        passwordHistory?.let { put("passwordHistory", JSONArray().apply { it.forEach { e -> put(e.toJson()) } }) }
        customFields?.let { put("customFields", JSONArray().apply { it.forEach { e -> put(e.toJson()) } }) }
        attachments?.let { put("attachments", JSONArray().apply { it.forEach { e -> put(e.toJson()) } }) }
    }

    companion object {
        fun parse(json: JSONObject): Item = Item(
            id = json.getString("id"),
            kind = ItemKind.fromWire(json.getString("kind")),
            name = json.optString("name", ""),
            favorite = json.optBoolean("favorite", false),
            folderId = if (json.isNull("folderId")) null else json.optString("folderId", ""),
            notes = json.optString("notes", ""),
            createdAt = json.optLong("createdAt", 0),
            updatedAt = json.optLong("updatedAt", 0),
            deletedAt = if (json.isNull("deletedAt")) null else json.optLong("deletedAt"),
            login = if (json.isNull("login")) null else json.optJSONObject("login")?.let { Login.parse(it) },
            card = if (json.isNull("card")) null else json.optJSONObject("card")?.let { Card.parse(it) },
            identity = if (json.isNull("identity")) null else json.optJSONObject("identity")?.let { Identity.parse(it) },
            passwordHistory = json.optJSONArray("passwordHistory")?.let { arr ->
                (0 until arr.length()).map { PasswordHistoryEntry.parse(arr.getJSONObject(it)) }
            },
            customFields = json.optJSONArray("customFields")?.let { arr ->
                (0 until arr.length()).map { CustomField.parse(arr.getJSONObject(it)) }
            },
            attachments = json.optJSONArray("attachments")?.let { arr ->
                (0 until arr.length()).map { Attachment.parse(arr.getJSONObject(it)) }
            },
            extra = Extra.unknownKeys(json, setOf("id", "kind", "name", "favorite", "folderId", "notes", "createdAt", "updatedAt", "deletedAt", "login", "card", "identity", "passwordHistory", "customFields", "attachments", "passkeys")),
            passkeys = json.optJSONArray("passkeys")?.let { arr ->
                (0 until arr.length()).map { Passkey.parse(arr.getJSONObject(it)) }
            },
        )
    }
}

data class Login(
    val username: String,
    val password: String,
    val uris: List<String>,
    val totp: String,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("username", username)
        put("password", password)
        put("uris", JSONArray().apply { uris.forEach { put(it) } })
        put("totp", totp)
    }

    companion object {
        fun parse(json: JSONObject): Login = Login(
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            uris = json.optJSONArray("uris")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            totp = json.optString("totp", ""),
            extra = Extra.unknownKeys(json, setOf("username", "password", "uris", "totp")),
        )
    }
}

data class Card(
    val cardholder: String,
    val brand: String,
    val number: String,
    val exp: String,
    val code: String,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("cardholder", cardholder)
        put("brand", brand)
        put("number", number)
        put("exp", exp)
        put("code", code)
    }

    companion object {
        fun parse(json: JSONObject): Card = Card(
            cardholder = json.optString("cardholder", ""),
            brand = json.optString("brand", ""),
            number = json.optString("number", ""),
            exp = json.optString("exp", ""),
            code = json.optString("code", ""),
            extra = Extra.unknownKeys(json, setOf("cardholder", "brand", "number", "exp", "code")),
        )
    }
}

data class Identity(
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val address: String,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("firstName", firstName)
        put("lastName", lastName)
        put("email", email)
        put("phone", phone)
        put("address", address)
    }

    companion object {
        fun parse(json: JSONObject): Identity = Identity(
            firstName = json.optString("firstName", ""),
            lastName = json.optString("lastName", ""),
            email = json.optString("email", ""),
            phone = json.optString("phone", ""),
            address = json.optString("address", ""),
            extra = Extra.unknownKeys(json, setOf("firstName", "lastName", "email", "phone", "address")),
        )
    }
}

data class PasswordHistoryEntry(
    val password: String,
    val at: Long,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("password", password)
        put("at", at)
    }

    companion object {
        fun parse(json: JSONObject): PasswordHistoryEntry = PasswordHistoryEntry(
            password = json.optString("password", ""),
            at = json.optLong("at", 0),
            extra = Extra.unknownKeys(json, setOf("password", "at")),
        )
    }
}

data class CustomField(
    val id: String,
    val name: String,
    val value: String,
    val hidden: Boolean,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("id", id)
        put("name", name)
        put("value", value)
        put("hidden", hidden)
    }

    companion object {
        fun parse(json: JSONObject): CustomField = CustomField(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            value = json.optString("value", ""),
            hidden = json.optBoolean("hidden", false),
            extra = Extra.unknownKeys(json, setOf("id", "name", "value", "hidden")),
        )
    }
}

data class Attachment(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val dataB64: String,
    /** Lixeira de anexos do desktop: com data, o anexo está na lixeira e não aparece. */
    val deletedAt: Long? = null,
    val extra: Extra = Extra.NONE,
) {
    val live: Boolean get() = deletedAt == null

    fun toJson(): JSONObject = extra.base().apply {
        put("id", id)
        put("name", name)
        put("size", size)
        put("mime", mime)
        put("dataB64", dataB64)
        put("deletedAt", deletedAt ?: JSONObject.NULL)
    }

    companion object {
        fun parse(json: JSONObject): Attachment = Attachment(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            size = json.optLong("size", 0),
            mime = json.optString("mime", ""),
            dataB64 = json.optString("dataB64", ""),
            deletedAt = if (json.isNull("deletedAt")) null else json.optLong("deletedAt"),
            extra = Extra.unknownKeys(json, setOf("id", "name", "size", "mime", "dataB64", "deletedAt")),
        )
    }
}

/**
 * Uma passkey (credencial WebAuthn), nos campos do formato de troca da FIDO (CXF): o que entra de uma
 * importação sai igual numa exportação. Binários em base64url sem preenchimento.
 *
 * A chave privada fica dentro do cofre cifrado (como no Bitwarden e no KeePassDX), em PKCS#8.
 */
data class Passkey(
    /** Id da credencial que o site guarda (base64url). */
    val credentialId: String,
    /** Domínio do site dono da passkey ("example.com"). */
    val rpId: String,
    val userName: String,
    val userDisplayName: String,
    /** Id do usuário no site (base64url). */
    val userHandle: String,
    /** Chave privada P-256 em PKCS#8 (base64url). */
    val key: String,
    /** Algoritmo COSE: -7 é ES256, o único que o Local Keys cria. */
    val alg: Int = ES256,
    val createdAt: Long,
    val extra: Extra = Extra.NONE,
) {
    fun toJson(): JSONObject = extra.base().apply {
        put("credentialId", credentialId)
        put("rpId", rpId)
        put("userName", userName)
        put("userDisplayName", userDisplayName)
        put("userHandle", userHandle)
        put("key", key)
        put("alg", alg)
        put("createdAt", createdAt)
    }

    companion object {
        const val ES256 = -7

        fun parse(json: JSONObject): Passkey = Passkey(
            credentialId = json.optString("credentialId", ""),
            rpId = json.optString("rpId", ""),
            userName = json.optString("userName", ""),
            userDisplayName = json.optString("userDisplayName", ""),
            userHandle = json.optString("userHandle", ""),
            key = json.optString("key", ""),
            alg = json.optInt("alg", ES256),
            createdAt = json.optLong("createdAt", 0),
            extra = Extra.unknownKeys(
                json,
                setOf("credentialId", "rpId", "userName", "userDisplayName", "userHandle", "key", "alg", "createdAt"),
            ),
        )
    }
}
