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
        val names = VaultFilters.visible(items, KindFilter.ALL, FolderFilter.All, "").map { it.name }
        assertEquals(listOf("beta", "Alfa", "alfa2", "zeta"), names)
    }

    @Test
    fun lixeira_fica_fora_da_lista_mas_deletedAt_zero_nao_conta() {
        val items = listOf(
            item("1", "vivo"),
            item("2", "lixo", deletedAt = 1_700_000_000_000),
            item("3", "zero", deletedAt = 0),
        )
        val names = VaultFilters.visible(items, KindFilter.ALL, FolderFilter.All, "").map { it.name }
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
        fun ids(f: KindFilter) = VaultFilters.visible(all, f, FolderFilter.All, "").map { it.id }.toSet()
        assertEquals(setOf("t"), ids(KindFilter.WITH_TOTP))
        assertEquals(setOf("n"), ids(KindFilter.NOTES))
        assertEquals(setOf("c"), ids(KindFilter.CARDS))
        assertEquals(setOf("a"), ids(KindFilter.WITH_ATTACHMENTS))
        assertEquals(setOf("k"), ids(KindFilter.WITH_PASSKEY))
        assertEquals(setOf("f"), ids(KindFilter.FAVORITES))
        assertEquals(setOf("t", "a", "b", "k", "f"), ids(KindFilter.LOGINS))
        assertTrue(ids(KindFilter.IDENTITIES).isEmpty())
    }

    @Test
    fun pastas_todas_sem_pasta_e_uma_pasta() {
        val items = listOf(
            item("1", "a", folderId = "f1"),
            item("2", "b"),
            item("3", "c", folderId = "f2"),
            item("4", "d", folderId = ""),
        )
        fun ids(f: FolderFilter) = VaultFilters.visible(items, KindFilter.ALL, f, "").map { it.id }
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
        fun ids(q: String) = VaultFilters.visible(all, KindFilter.ALL, FolderFilter.All, q).map { it.id }
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
