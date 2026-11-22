package com.localkeys.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localkeys.android.data.forms.CardFormat
import com.localkeys.android.data.totp.OtpAuth
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Identity
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import java.util.UUID

/**
 * Estado do editor de item. [initial] null = item novo (tipo escolhido na tela).
 *
 * O item salvo parte de uma CÓPIA do original (`initial.copy`): o que o editor não mostra — passkeys, chaves
 * desconhecidas em `extra`, histórico de senhas, anexos na lixeira — atravessa intacto, como no desktop.
 */
class ItemEditorState(
    private val initial: Item?,
    defaultFolderId: String? = null,
    /** Item novo: o que foi escolhido no "+" (null = login, como antes). */
    preset: NewItemPreset? = null,
) {
    val isNew: Boolean = initial == null
    private val id: String = initial?.id ?: UUID.randomUUID().toString()

    var kind by mutableStateOf(initial?.kind ?: preset?.kind ?: ItemKind.LOGIN)
    var name by mutableStateOf(initial?.name ?: "")
    var notes by mutableStateOf(initial?.notes ?: "")
    var favorite by mutableStateOf(initial?.favorite ?: false)
    var folderId by mutableStateOf(initial?.folderId ?: defaultFolderId)

    var username by mutableStateOf(initial?.login?.username ?: "")
    var password by mutableStateOf(initial?.login?.password ?: "")
    val uris = mutableStateListOf<String>().apply { addAll(initial?.login?.uris.orEmpty().ifEmpty { listOf("") }) }
    var totp by mutableStateOf(initial?.login?.totp ?: "")

    var cardholder by mutableStateOf(initial?.card?.cardholder ?: "")
    var brand by mutableStateOf(initial?.card?.brand ?: "")
        private set

    /** Só os dígitos (é o que vai para o cofre); os grupos de 4 são da tela. */
    var number by mutableStateOf(CardFormat.digits(initial?.card?.number ?: ""))
        private set
    var exp by mutableStateOf(initial?.card?.exp ?: "")
        private set
    var code by mutableStateOf(initial?.card?.code ?: "")
        private set

    /** A bandeira atual foi preenchida pelo app (então o número pode trocá-la); digitada pela pessoa, fica. */
    private var brandAuto: Boolean = brand.isBlank() || brand == CardFormat.brandOf(number)?.label

    var firstName by mutableStateOf(initial?.identity?.firstName ?: "")
    var lastName by mutableStateOf(initial?.identity?.lastName ?: "")
    var email by mutableStateOf(initial?.identity?.email ?: "")
    var phone by mutableStateOf(initial?.identity?.phone ?: "")
    var address by mutableStateOf(initial?.identity?.address ?: "")

    val customFields = mutableStateListOf<CustomField>().apply { addAll(initial?.customFields.orEmpty()) }

    /** Todos os anexos, inclusive os da lixeira (a tela só mostra os vivos). */
    val attachments = mutableStateListOf<Attachment>().apply { addAll(initial?.attachments.orEmpty()) }

    init {
        // Item livre: uma nota com um campo personalizado vazio já aberto (o desktop abre igual, é só uma nota).
        if (initial == null && preset == NewItemPreset.BLANK) addField()
    }

    private val baseline: Item = build(now = 0)

    // ── Cartão ───────────────────────────────────────────────────────────

    /** Número digitado (com ou sem espaços): guarda só os dígitos e acerta a bandeira se ela for automática. */
    fun updateNumber(raw: String) {
        number = CardFormat.digits(raw).take(CardFormat.MAX_DIGITS)
        if (brandAuto || brand.isBlank()) {
            brand = CardFormat.brandOf(number)?.label.orEmpty()
            brandAuto = true
        }
    }

    /** Bandeira digitada pela pessoa: vale a dela (apagar tudo devolve a detecção automática). */
    fun updateBrand(raw: String) {
        brand = raw
        brandAuto = raw.isBlank()
    }

    fun updateExp(raw: String) {
        exp = CardFormat.expiry(raw)
    }

    fun updateCode(raw: String) {
        code = CardFormat.cvv(raw)
    }

    // ── TOTP ─────────────────────────────────────────────────────────────

    /**
     * Chave lida de um QR ou colada: guarda só a chave base32 (o que o desktop lê) e, se vierem na URI, põe o
     * emissor no nome e a conta no usuário — só quando estão vazios; nunca sobrescreve.
     */
    fun applyTotp(parsed: OtpAuth.Parsed) {
        totp = parsed.secret
        if (name.isBlank()) (parsed.issuer ?: parsed.account)?.let { name = it }
        if (kind == ItemKind.LOGIN && username.isBlank()) parsed.account?.let { username = it }
    }

    /**
     * O que vai para `login.totp`. Mexido nesta edição e colado como `otpauth://…`: só a chave base32, que é o
     * que o desktop lê. Intocado: fica exatamente como estava.
     */
    private fun storedTotp(): String {
        val t = totp.trim()
        if (t == initial?.login?.totp?.trim()) return t
        val parsed = OtpAuth.parse(t)
        return if (t.startsWith("otpauth://", ignoreCase = true) && parsed is OtpAuth.Result.Ok) parsed.value.secret else t
    }

    // ── Identidade ───────────────────────────────────────────────────────

    /** Atalho de documento: um campo personalizado com o nome já posto e o valor livre. */
    fun addNamedField(name: String) {
        customFields += CustomField(UUID.randomUUID().toString(), name, "", hidden = false)
    }

    val canSave: Boolean get() = name.isNotBlank()

    /** Algo mudou desde que o editor abriu? (guarda do "voltar") */
    val isDirty: Boolean get() = build(now = 0) != baseline

    fun addField() {
        customFields += CustomField(UUID.randomUUID().toString(), "", "", hidden = false)
    }

    /** Como no desktop: remover manda o anexo para a lixeira do item (some da tela, fica no arquivo). */
    fun trashAttachment(attachmentId: String, now: Long = System.currentTimeMillis()) {
        val i = attachments.indexOfFirst { it.id == attachmentId }
        if (i < 0) return
        val att = attachments[i]
        // Anexo que nasceu nesta edição e nunca foi gravado: some de vez, não há o que guardar na lixeira.
        val wasSaved = initial?.attachments?.any { it.id == attachmentId } == true
        if (wasSaved) attachments[i] = att.copy(deletedAt = now) else attachments.removeAt(i)
    }

    /** O item como vai para o cofre. [now] vira o `updatedAt` (e o `createdAt` de um item novo). */
    fun build(now: Long): Item {
        val cleanUris = uris.map { it.trim() }.filter { it.isNotEmpty() }
        val base = initial ?: Item(
            id = id,
            kind = kind,
            name = "",
            favorite = false,
            folderId = null,
            notes = "",
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
        return base.copy(
            kind = kind,
            name = name.trim(),
            favorite = favorite,
            folderId = folderId,
            notes = notes.trimEnd(),
            updatedAt = now,
            login = if (kind == ItemKind.LOGIN) {
                (initial?.login ?: Login("", "", emptyList(), "")).copy(
                    username = username.trim(),
                    password = password,
                    uris = cleanUris,
                    totp = storedTotp(),
                )
            } else {
                initial?.login
            },
            card = if (kind == ItemKind.CARD) {
                (initial?.card ?: Card("", "", "", "", "")).copy(
                    cardholder = cardholder.trim(),
                    brand = brand.trim(),
                    number = number.trim(),
                    exp = exp.trim(),
                    code = code.trim(),
                )
            } else {
                initial?.card
            },
            identity = if (kind == ItemKind.IDENTITY) {
                (initial?.identity ?: Identity("", "", "", "", "")).copy(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    email = email.trim(),
                    phone = phone.trim(),
                    address = address.trim(),
                )
            } else {
                initial?.identity
            },
            customFields = customFields.toList().takeIf { it.isNotEmpty() || initial?.customFields != null },
            attachments = attachments.toList().takeIf { it.isNotEmpty() || initial?.attachments != null },
        )
    }
}

/** O que o "+" da lista oferece. [BLANK] é uma nota com um campo personalizado vazio: sem formato pronto. */
enum class NewItemPreset(val kind: ItemKind) {
    LOGIN(ItemKind.LOGIN),
    CARD(ItemKind.CARD),
    IDENTITY(ItemKind.IDENTITY),
    NOTE(ItemKind.NOTE),
    BLANK(ItemKind.NOTE),
}
