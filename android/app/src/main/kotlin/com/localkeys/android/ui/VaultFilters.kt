package com.localkeys.android.ui

import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind

/** Tipo de item (escolha única no painel de filtros). */
enum class KindFilter {
    ALL,
    LOGINS,
    NOTES,
    CARDS,
    IDENTITIES,
}

/** "Mostrar só" (várias de uma vez, combinadas com E). */
enum class ShowOnly {
    FAVORITES,
    WITH_TOTP,
    WITH_ATTACHMENTS,
    WITH_PASSKEY,
}

/** Filtros do painel: um tipo e qualquer combinação de [ShowOnly]. A pasta fica de fora (seletor próprio). */
data class ListFilter(val kind: KindFilter = KindFilter.ALL, val only: Set<ShowOnly> = emptySet()) {
    /** Quantos filtros estão ligados (o número do botão "Filtrar (n)"). */
    val activeCount: Int get() = (if (kind != KindFilter.ALL) 1 else 0) + only.size

    /** Forma salvável: "LOGINS|FAVORITES,WITH_TOTP". */
    fun encode(): String = kind.name + "|" + only.sortedBy { it.ordinal }.joinToString(",") { it.name }

    companion object {
        val NONE = ListFilter()

        /** Inverso de [encode]; nome desconhecido é ignorado (a restauração nunca derruba a lista). */
        fun decode(raw: String?): ListFilter {
            if (raw.isNullOrEmpty()) return NONE
            val kind = KindFilter.entries.firstOrNull { it.name == raw.substringBefore('|') } ?: KindFilter.ALL
            val only = raw.substringAfter('|', "").split(',')
                .mapNotNull { name -> ShowOnly.entries.firstOrNull { it.name == name } }
                .toSet()
            return ListFilter(kind, only)
        }
    }
}

/**
 * Pasta escolhida no seletor da lista. [key] é a forma salvável (sobrevive a rotação e à morte do processo):
 * null = todas, "" = sem pasta, o id da pasta nos outros casos.
 */
sealed interface FolderFilter {
    val key: String?

    data object All : FolderFilter {
        override val key: String? = null
    }

    data object NoFolder : FolderFilter {
        override val key: String = ""
    }

    data class In(val id: String) : FolderFilter {
        override val key: String = id
    }

    companion object {
        fun fromKey(key: String?): FolderFilter = when (key) {
            null -> All
            "" -> NoFolder
            else -> In(key)
        }
    }
}

/** Lógica pura da lista (sem Android): o que aparece, em que ordem e com que subtítulo. */
object VaultFilters {

    /** Itens visíveis: fora da lixeira, no filtro e na pasta, casando com a busca; favoritos primeiro, depois nome. */
    fun visible(items: List<Item>, filter: ListFilter, folder: FolderFilter, query: String): List<Item> {
        val q = query.trim().lowercase()
        return items
            .asSequence()
            .filter { !it.inTrash }
            .filter { matchesKind(it, filter.kind) }
            .filter { item -> filter.only.all { matchesOnly(item, it) } }
            .filter { matchesFolder(it, folder) }
            .filter { q.isEmpty() || matchesQuery(it, q) }
            .sortedWith(ORDER)
            .toList()
    }

    /** Favoritos primeiro, depois nome sem diferenciar maiúsculas (desempate pelo id, para a ordem ser estável). */
    val ORDER: Comparator<Item> = compareByDescending<Item> { it.favorite }
        .thenBy { it.name.lowercase() }
        .thenBy { it.id }

    fun matchesKind(item: Item, kind: KindFilter): Boolean = when (kind) {
        KindFilter.ALL -> true
        KindFilter.LOGINS -> item.kind == ItemKind.LOGIN
        KindFilter.NOTES -> item.kind == ItemKind.NOTE
        KindFilter.CARDS -> item.kind == ItemKind.CARD
        KindFilter.IDENTITIES -> item.kind == ItemKind.IDENTITY
    }

    fun matchesOnly(item: Item, only: ShowOnly): Boolean = when (only) {
        ShowOnly.FAVORITES -> item.favorite
        ShowOnly.WITH_TOTP -> hasTotp(item)
        ShowOnly.WITH_ATTACHMENTS -> item.liveAttachments.isNotEmpty()
        ShowOnly.WITH_PASSKEY -> item.livePasskeys.isNotEmpty()
    }

    fun matchesFolder(item: Item, folder: FolderFilter): Boolean = when (folder) {
        FolderFilter.All -> true
        FolderFilter.NoFolder -> item.folderId.isNullOrEmpty()
        is FolderFilter.In -> item.folderId == folder.id
    }

    /** Busca em [q] já minúsculo: nome, usuário, sites, notas, cartão, identidade, passkeys e nomes de campos. */
    fun matchesQuery(item: Item, q: String): Boolean {
        val haystack = buildList {
            add(item.name)
            add(item.notes)
            item.login?.let { add(it.username); addAll(it.uris) }
            item.card?.let { add(it.brand); add(it.cardholder) }
            item.identity?.let { add(it.firstName); add(it.lastName); add(it.email); add(it.phone) }
            item.livePasskeys.forEach { add(it.rpId); add(it.userName) }
            item.customFields?.forEach { field ->
                add(field.name)
                if (!field.hidden) add(field.value)
            }
        }
        return haystack.any { it.lowercase().contains(q) }
    }

    fun hasTotp(item: Item): Boolean = item.login?.totp?.isNotBlank() == true

    /** Linha de baixo da lista: usuário, bandeira e final do cartão, nome da pessoa, começo da nota. */
    fun subtitle(item: Item): String = when (item.kind) {
        ItemKind.LOGIN -> item.login?.username?.takeIf { it.isNotBlank() }
            ?: item.login?.uris?.firstOrNull()?.let(::hostOf)
            ?: item.livePasskeys.firstOrNull()?.userName.orEmpty()
        ItemKind.CARD -> item.card?.let { card ->
            val digits = card.number.filter { it.isDigit() }
            val last4 = if (digits.length >= 4) "•••• " + digits.takeLast(4) else ""
            listOf(card.brand.trim(), last4).filter { it.isNotEmpty() }.joinToString("  ")
                .ifEmpty { card.cardholder.trim() }
        }.orEmpty()
        ItemKind.IDENTITY -> item.identity?.let { id ->
            listOf(id.firstName, id.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { id.email }
        }.orEmpty()
        ItemKind.NOTE -> item.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** "https://www.exemplo.com/login" → "www.exemplo.com" (só para exibir). */
    fun hostOf(uri: String): String {
        val noScheme = uri.trim().substringAfter("://", uri.trim())
        return noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    }

    /** Endereço que dá para abrir no navegador: sem esquema vira https://. Null se não parece um site. */
    fun openableUrl(uri: String): String? {
        val t = uri.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return t
        if (t.contains("://")) return null // androidapp:// e afins não abrem no navegador
        return if (t.contains('.')) "https://$t" else null
    }
}
