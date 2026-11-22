package com.localkeys.android.data.passkey

import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Passkey
import com.localkeys.android.data.vault.Vault

/**
 * Quais credenciais do cofre servem para quem pediu, sem nada de Android (testável na JVM).
 *
 * Regra de segurança: só entra o que casa com o site ou com o app que pediu. Nada de "sugestões" de outras
 * contas — um app malicioso não pode ver na lista as senhas que não são dele.
 */
object CredentialMatch {

    /** Uma passkey e o item que a guarda. */
    data class Key(val item: Item, val passkey: Passkey)

    /** Passkeys do site [rpId]; se o site listou ids aceitos, só essas. */
    fun passkeys(vault: Vault, rpId: String, allowedIds: Set<String>): List<Key> =
        vault.items
            .filter { it.deletedAt == null && it.kind == ItemKind.LOGIN }
            .flatMap { item -> item.livePasskeys.map { Key(item, it) } }
            .filter { it.passkey.rpId.equals(rpId, ignoreCase = true) }
            .filter { allowedIds.isEmpty() || it.passkey.credentialId in allowedIds }

    /**
     * Logins com senha para o [domain] (site, quando quem pede é um navegador) ou para o [packageName] (app).
     * Só os que casam: nunca uma lista de sugestões.
     */
    fun passwords(vault: Vault, domain: String?, packageName: String?): List<Item> =
        vault.items
            .filter { it.deletedAt == null && it.kind == ItemKind.LOGIN }
            .filter { !it.login?.password.isNullOrEmpty() }
            .filter { item ->
                val uris = item.login?.uris.orEmpty()
                uris.any { uri ->
                    (domain != null && AutofillMatcher.matchesDomain(uri, domain)) ||
                        (packageName != null && uri.trim().equals(packageName, ignoreCase = true)) ||
                        (packageName != null && uri.trim().equals("androidapp://$packageName", ignoreCase = true))
                }
            }

    /** "https://example.com" → "example.com" (o domínio que um navegador privilegiado informou). */
    fun domainOfOrigin(origin: String): String = AutofillMatcher.normalizeDomain(origin)
}
