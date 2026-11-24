package com.localkeys.android.data.autofill

import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault

/**
 * Lógica pura de casar os logins do vault com o site/pacote que pediu
 * preenchimento. Roda na JVM nos testes — sem nenhum Android.
 */
object AutofillMatcher {

    /** Limite de datasets sugeridos por pedido (evita lista infinita). */
    const val MAX_DATASETS = 8


    /**
     * Normaliza um domínio/site para comparação: remove esquema, credenciais,
     * porta, caminho, query, fragmento e `www.`.
     *   "https://www.Example.com/login?x=1" -> "example.com"
     *   "example.com"                        -> "example.com"
     */
    fun normalizeDomain(raw: String): String {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isBlank()) return ""
        val noScheme = trimmed.substringAfter("://", trimmed)
        val noCredentials = noScheme.substringAfterLast('@', noScheme)
        val host = noCredentials
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
        return host.trim()
    }

    /** Host de uma URI do vault ("https://mail.google.com/xx" → "google.com"? não — mail.google.com). */
    fun hostOfUri(uri: String): String = normalizeDomain(uri)

    /**
     * Casa um site cadastrado no item com o domínio do site que pediu fill.
     * Cobre exato e subdomínio dos dois lados (item com "google.com" casa com
     * "mail.google.com" e vice-versa).
     */
    fun matchesDomain(uri: String, domain: String): Boolean {
        val host = hostOfUri(uri)
        val dom = normalizeDomain(domain)
        if (host.isBlank() || dom.isBlank()) return false
        return host == dom || host.endsWith(".$dom") || dom.endsWith(".$host")
    }

    /**
     * Logins para o pedido de autofill: os que casam com o domínio do site ou com o pacote do app (igualdade,
     * não "contém"), fora da lixeira. Se nada casar, nada é oferecido.
     */
    fun loginsFor(vault: Vault?, packageName: String?, webDomain: String?): List<Login> =
        itemsFor(vault, packageName, webDomain).mapNotNull { it.login }

    /** Como [loginsFor], mas devolve os itens (nome para a lista do autofill, id para achar o TOTP na hora de
     * preencher), sem lembrar do último uso. */
    fun itemsFor(vault: Vault?, packageName: String?, webDomain: String?): List<Item> =
        suggestionsFor(vault, packageName, webDomain, emptyMap()).shown

    /**
     * As sugestões de um pedido: [Suggestions.shown] é o que a lista mostra (até [MAX_DATASETS]) e
     * [Suggestions.hidden] é quantas casaram e ficaram fora da lista. Ordem: favorito primeiro; entre iguais, o
     * último uso mais recente ([lastUsed] por id do item; sem registro = nunca usado); empatados, a ordem do
     * cofre.
     */
    fun suggestionsFor(vault: Vault?, packageName: String?, webDomain: String?, lastUsed: Map<String, Long>): Suggestions {
        val ranked = matchingItems(vault, packageName, webDomain)
            .sortedWith(compareByDescending<Item> { it.favorite }.thenByDescending { lastUsed[it.id] ?: 0L })
        return Suggestions(ranked.take(MAX_DATASETS), (ranked.size - MAX_DATASETS).coerceAtLeast(0))
    }

    /** Os itens que casam com o site ou com o app, na ordem do cofre. */
    private fun matchingItems(vault: Vault?, packageName: String?, webDomain: String?): List<Item> {
        val v = vault ?: return emptyList()
        val logins = v.items
            .filter { it.kind == ItemKind.LOGIN && it.login != null && it.deletedAt == null }
            .distinctBy { it.login!!.username to it.login.password }
        if (logins.isEmpty()) return emptyList()

        val domain = webDomain?.let { normalizeDomain(it) }
        val matched = if (domain != null) {
            logins.filter { item -> item.login!!.uris.any { matchesDomain(it, domain) } }
        } else {
            emptyList()
        }
        val byPackage = if (domain == null && packageName != null) {
            logins.filter { item ->
                item.login!!.uris.any {
                    val uri = it.trim().lowercase()
                    uri == packageName.lowercase() || uri == "androidapp://${packageName.lowercase()}"
                }
            }
        } else {
            emptyList()
        }

        // Só o que casa com o site ou com o app. Sem "sugestões" de outras contas: um app malicioso não pode
        // ver na lista as senhas que não são dele.
        return (matched + byPackage).distinctBy { it.id }
    }

    /** O que a lista do autofill mostra quando há mais do que [MAX_DATASETS]. */
    data class Suggestions(val shown: List<Item>, val hidden: Int) {
        val hasMore: Boolean get() = hidden > 0
    }
}
