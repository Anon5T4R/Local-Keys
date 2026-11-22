package com.localkeys.android.ui

import com.localkeys.android.data.totp.OtpAuth
import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os recursos novos do editor (QR do TOTP, cartão, atalhos da identidade, item livre) só usam os campos que o
 * cofre já tem: nada de chave nova nem tipo novo, e o JSON continua com a forma que o desktop lê.
 */
class EditorFeaturesTest {

    private val itemKeys = setOf(
        "id", "kind", "name", "favorite", "folderId", "notes", "createdAt", "updatedAt", "deletedAt",
        "login", "card", "identity", "passwordHistory", "customFields", "attachments", "passkeys",
    )

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun roundTrip(item: Item): JSONObject {
        val json = Vault(version = 1, folders = emptyList(), items = listOf(item)).toJson()
        val back = Vault.parse(json)
        assertEquals(item, back.items.single())
        return JSONObject(json).getJSONArray("items").getJSONObject(0)
    }

    @Test
    fun `totp from a qr stores only the base32 key and prefills empty fields`() {
        val state = ItemEditorState(null, preset = NewItemPreset.LOGIN)
        state.applyTotp((OtpAuth.parse("otpauth://totp/Binance:joao%40x.com?secret=jbswy3dpehpk3pxp&issuer=Binance") as OtpAuth.Result.Ok).value)
        assertEquals("Binance", state.name)
        assertEquals("joao@x.com", state.username)
        val o = roundTrip(state.build(now = 1))
        assertEquals("JBSWY3DPEHPK3PXP", o.getJSONObject("login").getString("totp"))
        assertTrue(keys(o) - itemKeys == emptySet<String>())
        assertEquals(setOf("username", "password", "uris", "totp"), keys(o.getJSONObject("login")))
    }

    @Test
    fun `qr never overwrites what was typed`() {
        val state = ItemEditorState(null)
        state.name = "Minha corretora"
        state.username = "eu"
        state.applyTotp(OtpAuth.Parsed("JBSWY3DPEHPK3PXP", "Binance", "joao", null))
        assertEquals("Minha corretora", state.name)
        assertEquals("eu", state.username)
    }

    @Test
    fun `otpauth typed into the field is saved as the key`() {
        val state = ItemEditorState(null)
        state.name = "X"
        state.totp = "otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP"
        assertEquals("JBSWY3DPEHPK3PXP", state.build(now = 1).login?.totp)
    }

    @Test
    fun `card stores digits, detected brand and masked expiry in the existing fields`() {
        val state = ItemEditorState(null, preset = NewItemPreset.CARD)
        assertEquals(ItemKind.CARD, state.kind)
        state.name = "Nubank"
        state.updateNumber("5555 5555 5555 4444")
        assertEquals("Mastercard", state.brand)
        state.updateExp("1230")
        state.updateCode("12a3")
        val o = roundTrip(state.build(now = 1))
        val card = o.getJSONObject("card")
        assertEquals(setOf("cardholder", "brand", "number", "exp", "code"), keys(card))
        assertEquals("5555555555554444", card.getString("number"))
        assertEquals("12/30", card.getString("exp"))
        assertEquals("123", card.getString("code"))
        assertFalse(o.has("login"))
    }

    @Test
    fun `typed brand wins over detection and clearing it brings detection back`() {
        val state = ItemEditorState(null, preset = NewItemPreset.CARD)
        state.updateNumber("4111")
        assertEquals("Visa", state.brand)
        state.updateBrand("Visa Infinite")
        state.updateNumber("5555555555554444")
        assertEquals("Visa Infinite", state.brand)
        state.updateBrand("")
        state.updateNumber("555555555555444")
        assertEquals("Mastercard", state.brand)
    }

    @Test
    fun `existing card with a custom brand keeps it`() {
        val item = Item(
            id = "c", kind = ItemKind.CARD, name = "C", favorite = false, folderId = null, notes = "",
            createdAt = 1, updatedAt = 1, deletedAt = null, login = null,
            card = Card("J", "Cartão da empresa", "4111111111111111", "01/2027", "123"),
            identity = null, passwordHistory = null, customFields = null, attachments = null,
        )
        val state = ItemEditorState(item)
        assertFalse(state.isDirty)
        state.updateNumber("5555555555554444")
        assertEquals("Cartão da empresa", state.brand)
        assertEquals("01/2027", state.build(now = 2).card?.exp)
    }

    @Test
    fun `identity shortcuts and blank item are plain custom fields`() {
        val identity = ItemEditorState(null, preset = NewItemPreset.IDENTITY)
        identity.name = "Eu"
        identity.addNamedField("CPF")
        identity.customFields[0] = identity.customFields[0].copy(value = "529.982.247-25")
        identity.addNamedField("")
        val o = roundTrip(identity.build(now = 1))
        val fields = o.getJSONArray("customFields")
        assertEquals(2, fields.length())
        assertEquals(setOf("id", "name", "value", "hidden"), keys(fields.getJSONObject(0)))
        assertEquals("CPF", fields.getJSONObject(0).getString("name"))

        val blank = ItemEditorState(null, preset = NewItemPreset.BLANK)
        assertEquals(ItemKind.NOTE, blank.kind)
        assertEquals(1, blank.customFields.size)
        blank.name = "Wi-Fi do escritório"
        val b = roundTrip(blank.build(now = 1))
        assertEquals("note", b.getString("kind"))
        assertTrue(keys(b) - itemKeys == emptySet<String>())
    }

    @Test
    fun `new item route keeps the preset`() {
        val r = Route.Edit(null, NewItemPreset.BLANK)
        assertEquals(r, Route.decode(r.encode()))
        assertEquals(Route.Edit(null), Route.decode(Route.Edit(null).encode()))
    }
}
