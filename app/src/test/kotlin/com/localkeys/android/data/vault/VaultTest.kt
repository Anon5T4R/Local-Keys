package com.localkeys.android.data.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {

    companion object {
        // Mesmo JSON do fixture cripto (gerado no desktop).
        val SAMPLE_JSON: String =
            """{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],"items":""" +
                """[{"id":"i1","kind":"login","name":"gmail","favorite":false,"folderId":"f1","notes":"conta principal","createdAt":1710000000000,"updatedAt":1710000000000,"deletedAt":null,"login":{"username":"joao@gmail.com","password":"hunter2","uris":["https://gmail.com"],"totp":"JBSWY3DPEHPK3PXP"},"passwordHistory":[],"customFields":[],"attachments":[]},""" +
                """{"id":"i2","kind":"note","name":"Wi-Fi","favorite":true,"folderId":null,"notes":"SSID: casa\nPW: rede123","createdAt":1710000000001,"updatedAt":1710000000001,"deletedAt":null}]}"""
    }

    @Test
    fun parse_do_json_do_desktop() {
        val vault = Vault.parse(SAMPLE_JSON)
        assertEquals(1, vault.version)
        assertEquals(1, vault.folders.size)
        assertEquals("f1", vault.folders[0].id)
        assertEquals("Pessoal", vault.folders[0].name)

        assertEquals(2, vault.items.size)

        val login = vault.items[0]
        assertEquals(ItemKind.LOGIN, login.kind)
        assertEquals("gmail", login.name)
        assertFalse(login.favorite)
        assertEquals("f1", login.folderId)
        assertEquals("conta principal", login.notes)
        assertEquals(1710000000000, login.createdAt)
        assertNull(login.deletedAt)
        val lg = login.login!!
        assertEquals("joao@gmail.com", lg.username)
        assertEquals("hunter2", lg.password)
        assertEquals(listOf("https://gmail.com"), lg.uris)
        assertEquals("JBSWY3DPEHPK3PXP", lg.totp)
        assertNull(login.card)
        assertNull(login.identity)

        val note = vault.items[1]
        assertEquals(ItemKind.NOTE, note.kind)
        assertEquals("Wi-Fi", note.name)
        assertTrue(note.favorite)
        assertNull(note.folderId)
        assertEquals("SSID: casa\nPW: rede123", note.notes)
        assertNull(note.login)
        assertNull(note.card)
        assertNull(note.identity)
    }

    @Test
    fun roundtrip_json_e_semanticamente_igual() {
        val vault = Vault.parse(SAMPLE_JSON)
        val roundTrip = Vault.parse(vault.toJson())
        assertTrue(JSONObject(SAMPLE_JSON).similar(JSONObject(roundTrip.toJson())))
    }

    @Test
    fun roundtrip_com_anexos_e_historico_de_senhas() {
        val item = Vault.parse(SAMPLE_JSON).items[0].copy(
            attachments = listOf(Attachment("a1", "doc.pdf", 4, "application/pdf", "AQID")),
            passwordHistory = listOf(
                PasswordHistoryEntry("antiga", 1710000000000),
                PasswordHistoryEntry("mais-antiga", 1710000000001),
            ),
        )
        val out = Vault(1, emptyList(), listOf(item)).toJson()
        val back = Vault.parse(out)

        assertEquals(1, back.items[0].attachments?.size)
        assertEquals("a1", back.items[0].attachments?.first()?.id)
        assertEquals("doc.pdf", back.items[0].attachments?.first()?.name)
        assertEquals(4L, back.items[0].attachments?.first()?.size)
        assertEquals("application/pdf", back.items[0].attachments?.first()?.mime)
        assertEquals("AQID", back.items[0].attachments?.first()?.dataB64)

        assertEquals(2, back.items[0].passwordHistory?.size)
        assertEquals("antiga", back.items[0].passwordHistory?.first()?.password)
        assertEquals(1710000000000, back.items[0].passwordHistory?.first()?.at)

        assertTrue(JSONObject(out).similar(JSONObject(back.toJson())))
    }

    @Test
    fun campos_opcionais_ausentes_ficam_null_e_omitidos_no_json() {
        val item = Vault.parse(SAMPLE_JSON).items[0].copy(
            passwordHistory = null,
            customFields = null,
            attachments = null,
        )
        val json = item.toJson()
        assertFalse(json.has("passwordHistory"))
        assertFalse(json.has("customFields"))
        assertFalse(json.has("attachments"))
    }

    /**
     * Escrita conservadora, como no desktop: um campo que este app não conhece
     * (de uma versão mais nova do desktop) atravessa uma edição e um save, em
     * todos os níveis, e um item de tipo desconhecido volta para o arquivo como veio.
     */
    @Test
    fun campos_e_itens_desconhecidos_atravessam_um_save() {
        val json = """{"version":1,"futuro":{"a":1},"folders":[{"id":"f1","name":"P","cor":"azul"}],"items":[""" +
            """{"id":"i1","kind":"login","name":"g","favorite":false,"folderId":"f1","notes":"","createdAt":1,"updatedAt":1,"deletedAt":null,""" +
            """"passkeys":[{"rp":"x"}],"login":{"username":"u","password":"p","uris":[],"totp":"","autofill":"off"},""" +
            """"customFields":[{"id":"c","name":"n","value":"v","hidden":false,"tipo":"url"}],""" +
            """"attachments":[{"id":"a","name":"n.txt","size":1,"mime":"text/plain","dataB64":"QQ==","hash":"h"}]},""" +
            """{"id":"i2","kind":"passkey","name":"novo tipo","x":[1,2]}]}"""
        val vault = Vault.parse(json)
        assertEquals("o tipo desconhecido não entra na lista", 1, vault.items.size)
        val edited = vault.copy(items = vault.items.map { it.copy(name = "gmail", login = it.login!!.copy(password = "nova")) })
        val out = JSONObject(edited.toJson())

        assertEquals(1, out.getJSONObject("futuro").getInt("a"))
        assertEquals("azul", out.getJSONArray("folders").getJSONObject(0).getString("cor"))
        val items = out.getJSONArray("items")
        assertEquals(2, items.length())
        val item = items.getJSONObject(0)
        assertEquals("gmail", item.getString("name"))
        assertEquals("x", item.getJSONArray("passkeys").getJSONObject(0).getString("rp"))
        assertEquals("nova", item.getJSONObject("login").getString("password"))
        assertEquals("off", item.getJSONObject("login").getString("autofill"))
        assertEquals("url", item.getJSONArray("customFields").getJSONObject(0).getString("tipo"))
        assertEquals("h", item.getJSONArray("attachments").getJSONObject(0).getString("hash"))
        val other = items.getJSONObject(1)
        assertEquals("passkey", other.getString("kind"))
        assertEquals(2, other.getJSONArray("x").length())
    }

    /** A lixeira de anexos do desktop (`deletedAt`) é lida e gravada de volta. */
    @Test
    fun anexo_na_lixeira_do_desktop_nao_esta_vivo() {
        val json = """{"version":1,"folders":[],"items":[{"id":"i1","kind":"note","name":"n","favorite":false,""" +
            """"folderId":null,"notes":"","createdAt":1,"updatedAt":1,"deletedAt":null,"attachments":[""" +
            """{"id":"a","name":"vivo.txt","size":1,"mime":"text/plain","dataB64":"QQ==","deletedAt":null},""" +
            """{"id":"b","name":"lixo.txt","size":1,"mime":"text/plain","dataB64":"QQ==","deletedAt":5}]}]}"""
        val atts = Vault.parse(json).items[0].attachments!!
        assertTrue(atts[0].live)
        assertFalse(atts[1].live)
        val out = JSONObject(Vault.parse(json).toJson()).getJSONArray("items").getJSONObject(0).getJSONArray("attachments")
        assertTrue(out.getJSONObject(0).isNull("deletedAt"))
        assertEquals(5L, out.getJSONObject(1).getLong("deletedAt"))
    }

    /** Uma parte apagada no app sai do arquivo, mesmo que o JSON original a tivesse. */
    @Test
    fun parte_apagada_sai_do_arquivo() {
        val item = Vault.parse(SAMPLE_JSON).items[0]
        val json = item.copy(login = null).toJson()
        assertFalse(json.has("login"))
    }

    @Test
    fun empty_vault() {
        val empty = Vault.empty()
        assertEquals(1, empty.version)
        assertTrue(empty.folders.isEmpty())
        assertTrue(empty.items.isEmpty())
    }
}
