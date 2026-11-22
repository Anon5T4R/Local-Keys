package com.localkeys.android.ui

import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Passkey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFiltersTest {

    private fun item(
        id: String,
        name: String,
        kind: ItemKind = ItemKind.LOGIN,
        favorite: Boolean = false,
        folderId: String? = null,
        deletedAt: Long? = null,
        login: Login? = if (kind == ItemKind.LOGIN) Login("", "", emptyList(), "") else null,
        card: Card? = null,
        notes: String = "",
        attachments: List<Attachment>? = null,
        passkeys: List<Passkey>? = null,
        customFields: List<CustomField>? = null,
    ) = Item(
        id, kind, name, favorite, folderId, notes, 1, 1, deletedAt, login, card, null, null, customFields,
        attachments, passkeys = passkeys,
    )

    private val passkey = Passkey("cid", "github.com", "joao", "João", "uh", "k", createdAt = 1)

    @Test
    fun favoritos_primeiro_depois_nome_sem_caixa() {
        val items = listOf(item("1", "zeta"), item("2", "Alfa"), item("3", "beta", favorite = true), item("4", "alfa2"))
        val names = VaultFilters.visible(items, ListFilter.NONE, FolderFilter.All, "").map { it.name }
        assertEquals(listOf("beta", "Alfa", "alfa2", "zeta"), names)
    }

    @Test
    fun lixeira_fica_fora_da_lista_mas_deletedAt_zero_nao_conta() {
        val items = listOf(
            item("1", "vivo"),
            item("2", "lixo", deletedAt = 1_700_000_000_000),
            item("3", "zero", deletedAt = 0),
        )
        val names = VaultFilters.visible(items, ListFilter.NONE, FolderFilter.All, "").map { it.name }
        assertEquals(listOf("vivo", "zero"), names)
    }

    @Test
    fun filtros_por_tipo_e_por_recurso() {
        val totp = item("t", "com totp", login = Login("u", "p", emptyList(), "JBSWY3DPEHPK3PXP"))
        val note = item("n", "nota", kind = ItemKind.NOTE)
        val card = item("c", "cartao", kind = ItemKind.CARD, card = Card("J", "Visa", "4111 1111 1111 1234", "12/30", "123"))
        val att = Attachment("a", "x.pdf", 10, "application/pdf", "AA==")
        val withAtt = item("a", "com anexo", attachments = listOf(att))
        val onlyTrashedAtt = item("b", "anexo na lixeira", attachments = listOf(att.copy(deletedAt = 5)))
        val withKey = item("k", "com passkey", passkeys = listOf(passkey))
        val fav = item("f", "fav", favorite = true)
        val all = listOf(totp, note, card, withAtt, onlyTrashedAtt, withKey, fav)
        fun ids(f: KindFilter) = VaultFilters.visible(all, ListFilter(f), FolderFilter.All, "").map { it.id }.toSet()
        fun ids(o: ShowOnly) = VaultFilters.visible(all, ListFilter(only = setOf(o)), FolderFilter.All, "").map { it.id }.toSet()
        assertEquals(setOf("t"), ids(ShowOnly.WITH_TOTP))
        assertEquals(setOf("n"), ids(KindFilter.NOTES))
        assertEquals(setOf("c"), ids(KindFilter.CARDS))
        assertEquals(setOf("a"), ids(ShowOnly.WITH_ATTACHMENTS))
        assertEquals(setOf("k"), ids(ShowOnly.WITH_PASSKEY))
        assertEquals(setOf("f"), ids(ShowOnly.FAVORITES))
        assertEquals(setOf("t", "a", "b", "k", "f"), ids(KindFilter.LOGINS))
        assertTrue(ids(KindFilter.IDENTITIES).isEmpty())
    }

    @Test
    fun filtros_combinados_sao_e() {
        val totp = Login("u", "p", emptyList(), "JBSWY3DPEHPK3PXP")
        val favTotp = item("1", "fav com totp", favorite = true, login = totp)
        val fav = item("2", "fav sem totp", favorite = true)
        val onlyTotp = item("3", "totp sem fav", login = totp)
        val favKey = item("4", "fav com passkey", favorite = true, passkeys = listOf(passkey))
        val favNote = item("5", "nota favorita", kind = ItemKind.NOTE, favorite = true)
        val all = listOf(favTotp, fav, onlyTotp, favKey, favNote)
        fun ids(f: ListFilter, folder: FolderFilter = FolderFilter.All) =
            VaultFilters.visible(all, f, folder, "").map { it.id }.toSet()
        assertEquals(setOf("1"), ids(ListFilter(only = setOf(ShowOnly.FAVORITES, ShowOnly.WITH_TOTP))))
        assertEquals(setOf("1", "2", "4"), ids(ListFilter(KindFilter.LOGINS, setOf(ShowOnly.FAVORITES))))
        assertEquals(setOf("5"), ids(ListFilter(KindFilter.NOTES, setOf(ShowOnly.FAVORITES))))
        assertTrue(ids(ListFilter(KindFilter.NOTES, setOf(ShowOnly.WITH_TOTP))).isEmpty())
        assertTrue(ids(ListFilter(only = setOf(ShowOnly.WITH_TOTP, ShowOnly.WITH_PASSKEY))).isEmpty())
        assertTrue(ids(ListFilter(only = setOf(ShowOnly.FAVORITES)), FolderFilter.In("x")).isEmpty())
        assertEquals(5, ids(ListFilter.NONE).size)
    }

    @Test
    fun contagem_e_forma_salvavel_do_filtro() {
        assertEquals(0, ListFilter.NONE.activeCount)
        val f = ListFilter(KindFilter.CARDS, setOf(ShowOnly.WITH_PASSKEY, ShowOnly.FAVORITES))
        assertEquals(3, f.activeCount)
        assertEquals(1, ListFilter(only = setOf(ShowOnly.WITH_TOTP)).activeCount)
        assertEquals(f, ListFilter.decode(f.encode()))
        assertEquals(ListFilter.NONE, ListFilter.decode(ListFilter.NONE.encode()))
        assertEquals(ListFilter.NONE, ListFilter.decode(null))
        // Nome antigo ("FAVORITES" era um tipo na 0.4) ou desconhecido não derruba a restauração.
        assertEquals(ListFilter(only = setOf(ShowOnly.WITH_TOTP)), ListFilter.decode("FAVORITES|WITH_TOTP,XYZ"))
    }

    @Test
    fun pastas_todas_sem_pasta_e_uma_pasta() {
        val items = listOf(
            item("1", "a", folderId = "f1"),
            item("2", "b"),
            item("3", "c", folderId = "f2"),
            item("4", "d", folderId = ""),
        )
        fun ids(f: FolderFilter) = VaultFilters.visible(items, ListFilter.NONE, f, "").map { it.id }
        assertEquals(listOf("1", "2", "3", "4"), ids(FolderFilter.All))
        assertEquals(listOf("2", "4"), ids(FolderFilter.NoFolder))
        assertEquals(listOf("3"), ids(FolderFilter.In("f2")))
    }

    @Test
    fun chave_da_pasta_vai_e_volta() {
        listOf(FolderFilter.All, FolderFilter.NoFolder, FolderFilter.In("abc")).forEach {
            assertEquals(it, FolderFilter.fromKey(it.key))
        }
    }

    @Test
    fun busca_olha_usuario_site_passkey_e_campo_visivel_mas_nao_campo_oculto() {
        val a = item("1", "Banco", login = Login("maria@x.com", "s", listOf("https://banco.com.br"), ""))
        val b = item("2", "Git", passkeys = listOf(passkey))
        val c = item(
            "3",
            "Wi-Fi",
            customFields = listOf(CustomField("c1", "rede", "casa", false), CustomField("c2", "pin", "segredo", true)),
        )
        val all = listOf(a, b, c)
        fun ids(q: String) = VaultFilters.visible(all, ListFilter.NONE, FolderFilter.All, q).map { it.id }
        assertEquals(listOf("1"), ids("MARIA"))
        assertEquals(listOf("1"), ids("banco.com"))
        assertEquals(listOf("2"), ids("github"))
        assertEquals(listOf("3"), ids("casa"))
        assertTrue(ids("segredo").isEmpty())
        assertEquals(3, ids("   ").size)
    }

    @Test
    fun subtitulo_por_tipo() {
        assertEquals("joao", VaultFilters.subtitle(item("1", "x", login = Login("joao", "", emptyList(), ""))))
        assertEquals(
            "site.com",
            VaultFilters.subtitle(item("1", "x", login = Login("", "", listOf("https://site.com/login"), ""))),
        )
        assertEquals(
            "Visa  •••• 1234",
            VaultFilters.subtitle(item("c", "c", kind = ItemKind.CARD, card = Card("J", "Visa", "4111 1111 1111 1234", "", ""))),
        )
        assertEquals("primeira", VaultFilters.subtitle(item("n", "n", kind = ItemKind.NOTE, notes = "\n primeira \nsegunda")))
    }

    @Test
    fun url_que_abre_no_navegador() {
        assertEquals("https://exemplo.com", VaultFilters.openableUrl("exemplo.com"))
        assertEquals("http://a.b/c", VaultFilters.openableUrl("http://a.b/c"))
        assertNull(VaultFilters.openableUrl("androidapp://com.foo"))
        assertNull(VaultFilters.openableUrl("sem ponto"))
        assertNull(VaultFilters.openableUrl(""))
    }
}
