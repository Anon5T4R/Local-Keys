package com.localkeys.android.data.autofill

import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatcherTest {

    private fun login(username: String, vararg uris: String): Login =
        Login(username = username, password = "p", uris = uris.toList(), totp = "")

    private fun vault(vararg logins: Login): Vault {
        val items = logins.mapIndexed { i, l ->
            com.localkeys.android.data.vault.Item(
                id = "id$i", kind = ItemKind.LOGIN, name = l.username, favorite = false,
                folderId = null, notes = "", createdAt = 1, updatedAt = 1, deletedAt = null,
                login = l, card = null, identity = null, passwordHistory = emptyList(),
                customFields = emptyList(), attachments = emptyList(),
            )
        }
        return Vault(version = 1, folders = emptyList(), items = items)
    }

    @Test
    fun normaliza_dominios_de_todo_tipo() {
        assertEquals("example.com", AutofillMatcher.normalizeDomain("https://www.Example.com/login?x=1"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("example.com"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("http://example.com:8080/"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("https://user:pass@example.com"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("   https://example.com  "))
        assertEquals("", AutofillMatcher.normalizeDomain(""))
        assertEquals("", AutofillMatcher.normalizeDomain("   "))
    }

    @Test
    fun casa_dominio_exato_e_subdominio_dos_dois_lados() {
        assertTrue(AutofillMatcher.matchesDomain("https://google.com", "https://google.com"))
        assertTrue(AutofillMatcher.matchesDomain("https://mail.google.com", "google.com"))
        assertTrue(AutofillMatcher.matchesDomain("google.com", "https://mail.google.com"))
        assertTrue(AutofillMatcher.matchesDomain("https://www.github.com", "https://github.com"))
    }

    @Test
    fun nao_casa_dominios_nao_relacionados() {
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://google.com", "https://goggle.com"))
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://a.com", "https://b.com"))
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://google.com", ""))
    }

    @Test
    fun filtra_logins_pelo_dominio_e_ordena_exato_primeiro() {
        val v = vault(
            login("outro@x.com", "https://foo.com"),
            login("joao@gmail.com", "https://mail.google.com"),
            login("trabalho@x.com", "https://x.com"),
        )
        val result = AutofillMatcher.loginsFor(v, packageName = null, webDomain = "https://mail.google.com")
        assertEquals(1, result.size)
        assertEquals("joao@gmail.com", result[0].username)
    }

    @Test
    fun sem_casa_nao_oferece_nada() {
        val v = vault(
            login("a@x.com", "https://a.com"),
            login("b@x.com", "https://b.com"),
            login("c@x.com", "https://c.com"),
            login("d@x.com", "https://d.com"),
            login("e@x.com", "https://e.com"),
            login("f@x.com", "https://f.com"),
        )
        val result = AutofillMatcher.loginsFor(v, packageName = null, webDomain = "https://nada.com")
        // Segurança: um site ou app que não casa com nenhum login não vê senha nenhuma.
        assertEquals(emptyList<Login>(), result)
    }

    @Test
    fun pacote_casa_por_igualdade_e_lixeira_fica_fora() {
        val base = vault(
            login("certo@x.com", "com.banco.app"),
            login("parecido@x.com", "com.banco.appx"),
            login("lixo@x.com", "com.banco.app"),
        )
        val v = base.copy(items = base.items.mapIndexed { i, it -> if (i == 2) it.copy(deletedAt = 5L) else it })
        val result = AutofillMatcher.loginsFor(v, packageName = "com.banco.app", webDomain = null)
        assertEquals(listOf("certo@x.com"), result.map { it.username })
    }

    @Test
    fun vault_nulo_ou_vazio_nao_da_nada() {
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(null, "com.x", "https://x.com"))
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(vault(), "com.x", "https://x.com"))
    }

    @Test
    fun ignora_itens_que_nao_sao_login() {
        val v = Vault(
            version = 1,
            folders = emptyList(),
            items = listOf(
                com.localkeys.android.data.vault.Item(
                    id = "n", kind = ItemKind.NOTE, name = "nota", favorite = false,
                    folderId = null, notes = "x", createdAt = 1, updatedAt = 1, deletedAt = null,
                    login = null, card = null, identity = null, passwordHistory = emptyList(),
                    customFields = emptyList(), attachments = emptyList(),
                ),
            ),
        )
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(v, "com.x", "https://x.com"))
    }

    @Test
    fun itens_com_totp_seguem_as_mesmas_regras_de_casamento() {
        val binance = login("joao", "com.binance.dev").copy(totp = "JBSWY3DPEHPK3PXP")
        val outro = login("maria", "com.outro.app").copy(totp = "JBSWY3DPEHPK3PXP")
        val v = vault(binance, outro)
        val items = AutofillMatcher.itemsFor(v, "com.binance.dev", null)
        assertEquals(listOf("id0"), items.map { it.id })
        assertEquals("JBSWY3DPEHPK3PXP", items.single().login?.totp)
        // App sem login cadastrado: nenhum código é oferecido.
        assertTrue(AutofillMatcher.itemsFor(v, "com.malicioso", null).isEmpty())
        // Site no navegador: vale o domínio, não o pacote do navegador.
        assertTrue(AutofillMatcher.itemsFor(v, "com.binance.dev", "phishing.example").isEmpty())
    }

    // ── ordem das sugestões e sinal de "há mais" ────────────────────────────────────────────────────────

    private val site = "https://site.com"

    private fun item(i: Int, favorite: Boolean = false, user: String = "u$i"): com.localkeys.android.data.vault.Item =
        com.localkeys.android.data.vault.Item(
            id = "id$i", kind = ItemKind.LOGIN, name = "site $i", favorite = favorite,
            folderId = null, notes = "", createdAt = 1, updatedAt = 1, deletedAt = null,
            login = login(user, site), card = null, identity = null, passwordHistory = emptyList(),
            customFields = emptyList(), attachments = emptyList(),
        )

    private fun siteVault(vararg items: com.localkeys.android.data.vault.Item): Vault =
        Vault(version = 1, folders = emptyList(), items = items.toList())

    private fun order(vault: Vault, lastUsed: Map<String, Long> = emptyMap()): List<String> =
        AutofillMatcher.suggestionsFor(vault, packageName = null, webDomain = site, lastUsed = lastUsed).shown.map { it.id }

    @Test
    fun favorito_vem_antes_de_nao_favorito() {
        // O favorito nunca foi usado; os outros, sim. Favorito vence; entre os outros, o mais recente.
        val v = siteVault(item(0), item(1, favorite = true), item(2))
        assertEquals(listOf("id1", "id2", "id0"), order(v, lastUsed = mapOf("id0" to 500L, "id2" to 700L)))
    }

    @Test
    fun entre_iguais_o_uso_mais_recente_vem_primeiro() {
        val v = siteVault(item(0), item(1), item(2))
        // Nunca usado (0) fica depois de quem já foi usado.
        assertEquals(listOf("id2", "id0", "id1"), order(v, lastUsed = mapOf("id0" to 500L, "id2" to 900L)))
        // Entre favoritos vale a mesma regra.
        val favs = siteVault(item(0, favorite = true), item(1, favorite = true))
        assertEquals(listOf("id1", "id0"), order(favs, lastUsed = mapOf("id0" to 500L, "id1" to 600L)))
    }

    @Test
    fun empatados_mantem_a_ordem_do_vault() {
        val v = siteVault(item(0, favorite = true), item(1), item(2), item(3, favorite = true))
        assertEquals(listOf("id0", "id3", "id1", "id2"), order(v))
        // Mesmo momento de uso = empate também.
        assertEquals(listOf("id0", "id3", "id1", "id2"), order(v, lastUsed = mapOf("id1" to 10L, "id2" to 10L)))
    }

    @Test
    fun a_lista_mostra_no_maximo_oito() {
        val v = siteVault(*(0 until 9).map { item(it) }.toTypedArray())
        val s = AutofillMatcher.suggestionsFor(v, null, site, emptyMap())
        assertEquals(AutofillMatcher.MAX_DATASETS, s.shown.size)
        // Quem ficou de fora por ordem do cofre entra quando foi o último usado.
        assertEquals(listOf("id7", "id6"), order(v, lastUsed = mapOf("id7" to 900L, "id6" to 800L)).take(2))
    }
    @Test
    fun onze_casamentos_dao_oito_mostrados_e_tres_a_mais() {
        val v = siteVault(*(0 until 11).map { item(it) }.toTypedArray())
        val s = AutofillMatcher.suggestionsFor(v, packageName = null, webDomain = site, lastUsed = emptyMap())
        assertEquals(8, s.shown.size)
        assertEquals(3, s.hidden)
        assertTrue(s.hasMore)
        // O que ficou de fora não aparece na lista.
        assertTrue(s.shown.none { it.id in setOf("id8", "id9", "id10") })
    }

    @Test
    fun sem_excesso_nao_ha_sinal_de_mais() {
        for (n in listOf(0, 1, 8)) {
            val v = siteVault(*(0 until n).map { item(it) }.toTypedArray())
            val s = AutofillMatcher.suggestionsFor(v, packageName = null, webDomain = site, lastUsed = emptyMap())
            assertEquals(n, s.shown.size)
            assertEquals(0, s.hidden)
            assertTrue(!s.hasMore)
        }
    }

    @Test
    fun o_excesso_conta_so_o_que_casa() {
        // 11 logins no cofre, mas 3 são de outro site: 8 casam, não há "a mais".
        val others = (0 until 3).map { i -> item(i).copy(login = login("outro$i", "https://outro.com")) }
        val v = siteVault(*others.toTypedArray(), *(3 until 11).map { item(it) }.toTypedArray())
        val s = AutofillMatcher.suggestionsFor(v, packageName = null, webDomain = site, lastUsed = emptyMap())
        assertEquals(8, s.shown.size)
        assertEquals(0, s.hidden)
    }
}
