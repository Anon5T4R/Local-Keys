package com.localkeys.android.data.passkey

import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class WebAuthnTest {

    private val creation = """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"dXNlci0x","name":"ana@example.com",""" +
        """"displayName":"Ana"},"challenge":"Y2hhbGxlbmdl","pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""

    /** O registro sai no formato do WebAuthn: rpIdHash, flags com AT, id de 32 bytes e chave COSE ES256. */
    @Test
    fun criar_monta_o_atestado_none_com_a_chave_cose() {
        val request = WebAuthn.parseCreation(creation)
        assertTrue(request.supportsEs256)
        val client = WebAuthn.clientDataJson("webauthn.create", request.challenge, "https://example.com", null)
        val created = WebAuthn.create(request, client, userVerified = true, now = 1L)
        val json = JSONObject(created.responseJson)
        assertEquals(created.passkey.credentialId, json.getString("id"))
        val authData = WebAuthn.fromB64url(json.getJSONObject("response").getString("authenticatorData"))
        assertArrayEquals(WebAuthn.sha256("example.com".toByteArray()), authData.copyOfRange(0, 32))
        val flags = authData[32].toInt() and 0xff
        assertEquals("UP, UV, BE, BS e AT", 0x5D, flags)
        // AAGUID (16) e o tamanho do id (2) depois do contador.
        assertEquals(32, ((authData[53].toInt() and 0xff) shl 8) or (authData[54].toInt() and 0xff))
        assertArrayEquals(WebAuthn.fromB64url(created.passkey.credentialId), authData.copyOfRange(55, 87))
        // A chave COSE começa como mapa de 5 entradas: {1: 2, 3: -7, …}.
        assertEquals(0xA5, authData[87].toInt() and 0xff)
        assertEquals("ana@example.com", created.passkey.userName)
        assertEquals("dXNlci0x", created.passkey.userHandle)
    }

    /** A assinatura do login confere com a chave pública que foi entregue ao site no registro. */
    @Test
    fun a_assinatura_confere_com_a_chave_publica_do_registro() {
        val request = WebAuthn.parseCreation(creation)
        val created = WebAuthn.create(request, ByteArray(0), userVerified = true, now = 1L)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(WebAuthn.fromB64url(JSONObject(created.responseJson).getJSONObject("response").getString("publicKey"))),
        )
        val client = WebAuthn.clientDataJson("webauthn.get", "b3V0cm8", "https://example.com", null)
        val hash = WebAuthn.sha256(client)
        val assertion = JSONObject(WebAuthn.assert(created.passkey, client, hash, userVerified = true)).getJSONObject("response")
        val authData = WebAuthn.fromB64url(assertion.getString("authenticatorData"))
        assertEquals("UP, UV, BE e BS, sem AT", 0x1D, authData[32].toInt() and 0xff)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(authData + hash)
        assertTrue(verifier.verify(WebAuthn.fromB64url(assertion.getString("signature"))))
        assertEquals("dXNlci0x", assertion.getString("userHandle"))
    }

    /** A passkey criada atravessa o cofre (gravar e reler) sem perder campo. */
    @Test
    fun a_passkey_atravessa_o_cofre() {
        val created = WebAuthn.create(WebAuthn.parseCreation(creation), ByteArray(0), true, 7L)
        val item = CxfImport.parse(
            """{"accounts":[{"items":[{"title":"Example","credentials":[]}]}]}""",
        ).items
        assertTrue("item vazio não entra", item.isEmpty())
        val vault = Vault.parse("""{"version":1,"folders":[],"items":[]}""")
        val login = com.localkeys.android.data.vault.Item(
            id = "i", kind = ItemKind.LOGIN, name = "Example", favorite = false, folderId = null, notes = "",
            createdAt = 1, updatedAt = 1, deletedAt = null,
            login = com.localkeys.android.data.vault.Login("ana", "", listOf("https://example.com"), ""),
            card = null, identity = null, passwordHistory = null, customFields = null, attachments = null,
            passkeys = listOf(created.passkey),
        )
        val back = Vault.parse(vault.copy(items = listOf(login)).toJson())
        assertEquals(created.passkey, back.items[0].passkeys!![0])
    }

    /** O CXF do Google vira login com senha, passkey e TOTP; os campos podem vir como objeto com `value`. */
    @Test
    fun cxf_vira_logins_com_senha_passkey_e_totp() {
        val created = WebAuthn.create(WebAuthn.parseCreation(creation), ByteArray(0), true, 7L)
        val cxf = """{"version":{"major":1,"minor":0},"accounts":[{"id":"a","items":[""" +
            """{"id":"1","title":"Example","creationAt":1700000000,"scope":{"urls":["https://example.com"]},"credentials":[""" +
            """{"type":"basic-auth","username":{"fieldType":"string","value":"ana"},"password":{"fieldType":"concealed-string","value":"s3nha"}},""" +
            """{"type":"passkey","credentialId":"${created.passkey.credentialId}","rpId":"example.com","username":"ana",""" +
            """"userDisplayName":"Ana","userHandle":"dXNlci0x","key":"${created.passkey.key}"},""" +
            """{"type":"totp","secret":"JBSWY3DPEHPK3PXP"}]},""" +
            """{"id":"2","title":"Só nota","credentials":[{"type":"note","content":{"value":"texto"}}]},""" +
            """{"id":"3","credentials":[{"type":"wifi","ssid":"x"}]}]}]}"""
        val result = CxfImport.parse(cxf)
        assertEquals(1, result.passwords)
        assertEquals(1, result.passkeys)
        assertEquals(2, result.items.size)
        val login = result.items[0]
        assertEquals("ana", login.login!!.username)
        assertEquals("s3nha", login.login!!.password)
        assertEquals("JBSWY3DPEHPK3PXP", login.login!!.totp)
        assertEquals(listOf("https://example.com"), login.login!!.uris)
        assertEquals(1_700_000_000_000L, login.createdAt)
        assertEquals("example.com", login.passkeys!![0].rpId)
        assertEquals(ItemKind.NOTE, result.items[1].kind)
        assertEquals("texto", result.items[1].notes)
        // O wifi não entra, e o item que só tinha ele também não.
        assertEquals(2, result.skipped)
        // A passkey importada ainda assina.
        WebAuthn.privateKey(login.passkeys!![0])
    }
}
