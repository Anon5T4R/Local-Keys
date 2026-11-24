package com.localkeys.android.ui

import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Extra
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.ffi.FieldLabelsFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mudar o tipo de um item no editor do Android: o estado (seletor só na edição, trocar e voltar sem converter,
 * aviso do TOTP condicionado) e o caminho completo pela lib do host — `prepareSave` → `changeItemKind` → regra
 * do núcleo, a MESMA do desktop. O esperado das conversões fica escrito à mão aqui, não derivado do núcleo.
 */
class ItemKindChangeTest {

    /** Os rótulos que o editor manda: os do desktop (`field_labels()`), que é o que vai gravado no cofre. */
    private val labels = FieldLabelsFfi(
        username = "Username",
        password = "Password",
        website = "URL",
        totp = "TOTP",
        cardholder = "Cardholder",
        brand = "Brand",
        cardNumber = "Card number",
        expiry = "Expiry (MM/YY)",
        securityCode = "Security code",
        firstName = "First name",
        lastName = "Last name",
        email = "E-mail",
        phone = "Phone",
        address = "Address",
    )

    private fun login() = Item(
        id = "i1", kind = ItemKind.LOGIN, name = "Gmail", favorite = true, folderId = "f1", notes = "nota",
        createdAt = 10, updatedAt = 20, deletedAt = null,
        login = Login("joao", "segredo", listOf("https://gmail.com"), "JBSWY3DPEHPK3PXP"),
        card = null, identity = null,
        passwordHistory = null, customFields = null, attachments = null,
    )

    private fun card() = Item(
        id = "i2", kind = ItemKind.CARD, name = "Nubank", favorite = false, folderId = null, notes = "",
        createdAt = 10, updatedAt = 20, deletedAt = null,
        login = null,
        card = Card("João da Silva", "Elo", "6362970000457013", "01/27", "123"),
        identity = null,
        passwordHistory = null, customFields = listOf(CustomField("c1", "PIN", "1234", hidden = true)),
        attachments = null,
    )

    /** O esperado dos campos extras, na ordem do arquivo: (nome, valor, oculto). */
    private fun fieldsOf(item: Item): List<Triple<String, String, Boolean>> =
        item.customFields.orEmpty().map { Triple(it.name, it.value, it.hidden) }

    @Test
    fun o_seletor_de_tipo_so_existe_na_edicao_de_item_existente() {
        val novo = ItemEditorState(null)
        assertFalse("item novo mantém o tipo do \"+\"", novo.canChangeKind)
        assertNull("item novo não tem alvo de conversão", novo.targetKind)

        val edicao = ItemEditorState(login())
        assertTrue(edicao.canChangeKind)
        assertEquals(ItemKind.LOGIN, edicao.targetKind)
        assertFalse("mesmo tipo: sem aviso e sem conversão", edicao.kindChanged)
    }

    @Test
    fun trocar_o_tipo_e_voltar_ao_original_nao_converte() {
        val state = ItemEditorState(login())
        state.targetKind = ItemKind.NOTE
        assertTrue(state.kindChanged)
        assertTrue("o seletor é uma alteração pendente", state.isDirty)
        state.targetKind = ItemKind.LOGIN
        assertFalse(state.kindChanged)

        val item = (state.prepareSave(now = 99, labels = labels) as ItemEditorState.Save.Ready).item
        assertEquals(ItemKind.LOGIN, item.kind)
        assertNull("nenhum campo extra nasceu", item.customFields)
        assertEquals("segredo", item.login?.password)
        assertEquals(99L, item.updatedAt)
    }

    @Test
    fun o_aviso_do_totp_so_aparece_com_segredo_e_tipo_novo_sem_login() {
        val comSegredo = ItemEditorState(login())
        comSegredo.targetKind = ItemKind.NOTE
        assertTrue(comSegredo.totpBecomesHiddenField)

        val semSegredo = ItemEditorState(login().copy(login = Login("joao", "segredo", emptyList(), "")))
        semSegredo.targetKind = ItemKind.NOTE
        assertFalse("sem chave TOTP não há o que avisar", semSegredo.totpBecomesHiddenField)

        val cartao = ItemEditorState(card())
        cartao.targetKind = ItemKind.IDENTITY
        assertFalse("cartão não tem TOTP", cartao.totpBecomesHiddenField)
    }

    @Test
    fun login_vira_nota_pela_ponte_com_o_esperado_escrito_a_mao() {
        val state = ItemEditorState(login())
        state.targetKind = ItemKind.NOTE
        val item = (state.prepareSave(now = 99, labels = labels) as ItemEditorState.Save.Ready).item

        assertEquals(ItemKind.NOTE, item.kind)
        assertEquals("Gmail", item.name)
        assertEquals("nota", item.notes)
        assertEquals("f1", item.folderId)
        assertTrue(item.favorite)
        assertNull("o objeto login sai do item", item.login)
        assertNull(item.card)
        assertNull(item.identity)
        assertEquals("o resto do item atravessa", 10L, item.createdAt)
        assertEquals("a gravação carimba updatedAt", 99L, item.updatedAt)
        assertEquals(
            listOf(
                Triple("Username", "joao", false),
                Triple("Password", "segredo", true),
                Triple("URL", "https://gmail.com", false),
                Triple("TOTP", "JBSWY3DPEHPK3PXP", true),
            ),
            fieldsOf(item),
        )
    }

    @Test
    fun cartao_vira_login_pela_ponte_com_o_esperado_escrito_a_mao() {
        val state = ItemEditorState(card())
        state.targetKind = ItemKind.LOGIN
        val item = (state.prepareSave(now = 7, labels = labels) as ItemEditorState.Save.Ready).item

        assertEquals(ItemKind.LOGIN, item.kind)
        assertNull("o objeto card sai do item", item.card)
        assertNull("o tipo novo nasce sem objeto; os campos dele aparecem vazios ao reabrir", item.login)
        assertEquals(
            listOf(
                // O campo que o item já tinha fica na frente, intacto.
                Triple("PIN", "1234", true),
                Triple("Cardholder", "João da Silva", false),
                Triple("Brand", "Elo", false),
                Triple("Card number", "6362970000457013", true),
                Triple("Expiry (MM/YY)", "01/27", false),
                Triple("Security code", "123", true),
            ),
            fieldsOf(item),
        )
        assertEquals(7L, item.updatedAt)
    }

    @Test
    fun a_conversao_recusada_nao_grava_nada_e_o_editor_continua_intacto() {
        val comCampoEstranho = login().let { it.copy(login = it.login?.copy(extra = Extra("""{"passkeyId":"abc"}"""))) }
        val state = ItemEditorState(comCampoEstranho)
        state.name = "Gmail editado"
        state.targetKind = ItemKind.NOTE

        val refused = state.prepareSave(now = 1, labels = labels) as ItemEditorState.Save.Refused
        assertTrue("a razão do núcleo vem no erro: ${refused.reason}", refused.reason.contains("passkeyId"))

        // Como no desktop: o seletor voltou ao tipo original e as edições da pessoa continuam no formulário.
        assertEquals(ItemKind.LOGIN, state.targetKind)
        assertEquals("Gmail editado", state.name)
        assertEquals(ItemKind.LOGIN, state.build(now = 1).kind)
    }

    @Test
    fun a_saida_inesperada_da_ponte_recusa_o_salvar_e_nao_derruba_o_editor() {
        // O núcleo devolvendo algo que não vira um `Item` (JSON quebrado, campo faltando) não pode derrubar o
        // salvar: vira a mesma recusa da conversão, sem gravar nada e sem fechar o formulário.
        val state = ItemEditorState(
            initial = login(),
            convert = { _, _, _ -> throw org.json.JSONException("saída inesperada do núcleo") },
        )
        state.name = "Gmail editado"
        state.targetKind = ItemKind.NOTE

        val refused = state.prepareSave(now = 1, labels = labels) as ItemEditorState.Save.Refused
        assertTrue("o motivo vem preenchido: ${refused.reason}", refused.reason.isNotBlank())
        assertEquals(ItemKind.LOGIN, state.targetKind)
        assertEquals("Gmail editado", state.name)
        assertEquals(ItemKind.LOGIN, state.build(now = 1).kind)
    }
}
