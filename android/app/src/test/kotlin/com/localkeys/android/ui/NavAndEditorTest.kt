package com.localkeys.android.ui

import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Extra
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Passkey
import com.localkeys.android.data.vault.PasswordHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAndEditorTest {

    // ── Pilha de navegação ───────────────────────────────────────────────

    @Test
    fun rotas_vao_e_voltam_como_texto() {
        listOf(Route.Detail("a"), Route.Edit("b"), Route.Edit(null), Route.Trash, Route.Folders, Route.Settings).forEach {
            assertEquals(it, Route.decode(it.encode()))
        }
        assertNull(Route.decode("lixo"))
    }

    @Test
    fun abrir_item_empilha_no_celular_e_troca_no_tablet() {
        val stack = listOf(Route.Detail("a"))
        assertEquals(listOf(Route.Detail("a"), Route.Detail("b")), NavStack.openItem(stack, "b", twoPane = false))
        assertEquals(listOf(Route.Detail("b")), NavStack.openItem(stack + Route.Edit("a"), "b", twoPane = true))
    }

    @Test
    fun salvar_tira_o_editor_e_deixa_o_detalhe_sem_duplicar() {
        assertEquals(listOf(Route.Detail("a")), NavStack.afterSave(listOf(Route.Detail("a"), Route.Edit("a")), "a"))
        assertEquals(listOf(Route.Detail("n")), NavStack.afterSave(listOf(Route.Edit(null)), "n"))
    }

    @Test
    fun item_na_lixeira_sai_da_pilha() {
        val stack = listOf(Route.Detail("x"), Route.Edit("x"), Route.Detail("y"))
        assertEquals(listOf(Route.Detail("y")), NavStack.removeItem(stack, "x"))
    }

    @Test
    fun tablet_separa_painel_e_tela_cheia() {
        assertEquals(Route.Detail("a"), NavStack.paneRoute(listOf(Route.Detail("a"))))
        assertNull(NavStack.fullScreenRoute(listOf(Route.Detail("a"))))
        assertEquals(Route.Settings, NavStack.fullScreenRoute(listOf(Route.Detail("a"), Route.Settings)))
        assertNull(NavStack.paneRoute(listOf(Route.Detail("a"), Route.Settings)))
    }

    // ── Estado do editor ─────────────────────────────────────────────────

    private val passkey = Passkey("cid", "github.com", "joao", "João", "uh", "chave", createdAt = 5)

    private fun login(): Item = Item(
        id = "i1", kind = ItemKind.LOGIN, name = "GitHub", favorite = true, folderId = "f1", notes = "n",
        createdAt = 10, updatedAt = 20, deletedAt = null,
        login = Login("joao", "velha", listOf("https://github.com"), "", extra = Extra("""{"autofillOnPageLoad":true}""")),
        card = null, identity = null,
        passwordHistory = listOf(PasswordHistoryEntry("antiga", 1)), customFields = null,
        attachments = listOf(Attachment("a1", "x.txt", 1, "text/plain", "eA==")),
        extra = Extra("""{"reprompt":1}"""),
        passkeys = listOf(passkey),
    )

    @Test
    fun editor_recem_aberto_nao_esta_sujo_e_preserva_o_que_nao_mostra() {
        val original = login()
        val state = ItemEditorState(original)
        assertFalse(state.isDirty)
        state.password = "nova"
        assertTrue(state.isDirty)
        val saved = state.build(now = 99)
        assertEquals("nova", saved.login?.password)
        assertEquals(99L, saved.updatedAt)
        assertEquals(10L, saved.createdAt)
        // Passkeys, extras do item e do login, histórico e anexos atravessam.
        assertEquals(original.passkeys, saved.passkeys)
        assertEquals(original.extra, saved.extra)
        assertEquals(original.login?.extra, saved.login?.extra)
        assertEquals(original.passwordHistory, saved.passwordHistory)
        assertEquals(original.attachments, saved.attachments)
    }

    @Test
    fun item_novo_nasce_com_a_pasta_do_filtro_e_sites_vazios_somem() {
        val state = ItemEditorState(null, defaultFolderId = "f9")
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
        state.name = "  Banco  "
        state.uris[0] = "  "
        state.uris.add("banco.com")
        val item = state.build(now = 7)
        assertEquals("Banco", item.name)
        assertEquals("f9", item.folderId)
        assertEquals(listOf("banco.com"), item.login?.uris)
        assertEquals(7L, item.createdAt)
        assertNull(item.deletedAt)
        assertNull(item.attachments)
    }

    @Test
    fun remover_anexo_salvo_vai_para_a_lixeira_e_anexo_novo_some() {
        val state = ItemEditorState(login())
        state.trashAttachment("a1", now = 50)
        assertEquals(50L, state.attachments.single().deletedAt)
        state.attachments += Attachment("novo", "y.txt", 1, "text/plain", "eQ==")
        state.trashAttachment("novo")
        assertEquals(listOf("a1"), state.attachments.map { it.id })
    }

    @Test
    fun trocar_o_tipo_de_um_item_novo_so_grava_a_parte_do_tipo() {
        val state = ItemEditorState(null)
        state.name = "Nota"
        state.username = "sobra"
        state.kind = ItemKind.NOTE
        val item = state.build(now = 1)
        assertEquals(ItemKind.NOTE, item.kind)
        assertNull(item.login)
    }
}
