package com.localkeys.android.data.crypto

import com.localkeys.android.ffi.FfiException
import com.localkeys.android.ffi.FieldLabelsFfi
import com.localkeys.android.ffi.changeItemKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A regra de mudar o tipo de um item pela ponte UniFFI (`crates/ffi` → `change_kind` do núcleo): o item convertido
 * é comparado com o esperado do núcleo (o mesmo que `crates/core/tests/change_kind.rs` trava), e o erro chega
 * tipado. Nada de tela: a UI entra na peça seguinte.
 */
class ChangeKindTest {

    /** Rótulos vazios: o núcleo cai nos padrões em inglês (o app manda os do idioma dele). */
    private val labels = FieldLabelsFfi(
        username = "",
        password = "",
        website = "",
        totp = "",
        cardholder = "",
        brand = "",
        cardNumber = "",
        expiry = "",
        securityCode = "",
        firstName = "",
        lastName = "",
        email = "",
        phone = "",
        address = "",
    )

    private val login = """
        {"id":"i1","kind":"login","name":"Gmail","favorite":true,"folderId":"f1","notes":"n",
         "createdAt":1,"updatedAt":2,"deletedAt":null,
         "login":{"username":"joao","password":"segredo","uris":["https://a","https://b"],"totp":"JBSWY3DPEHPK3PXP"},
         "customFields":[{"id":"c1","name":"Extra","value":"v","hidden":false}],
         "unknownKey":42}
    """.trimIndent()

    @Test
    fun login_vira_nota_e_o_resultado_bate_com_o_esperado_do_nucleo() {
        val converted = JSONObject(changeItemKind(login, "note", labels))

        // O resto do item atravessa idêntico.
        assertEquals("note", converted.getString("kind"))
        assertEquals("i1", converted.getString("id"))
        assertEquals("Gmail", converted.getString("name"))
        assertEquals("n", converted.getString("notes"))
        assertEquals("f1", converted.getString("folderId"))
        assertEquals(true, converted.getBoolean("favorite"))
        assertEquals(42, converted.getInt("unknownKey"))
        assertEquals(false, converted.has("login"))

        // Esperado do núcleo: o campo antigo na frente, os gerados depois, com os ocultos ocultos. Os ids dos campos
        // gerados são UUIDs novos a cada chamada e não entram na comparação.
        val expected = listOf(
            Triple("Extra", "v", false),
            Triple("Username", "joao", false),
            Triple("Password", "segredo", true),
            Triple("Website", "https://a", false),
            Triple("Website 2", "https://b", false),
            Triple("TOTP", "JBSWY3DPEHPK3PXP", true),
        )

        assertEquals(expected, fieldsOf(converted))
    }

    @Test
    fun erro_de_conversao_chega_como_not_convertible() {
        assertThrows(FfiException.NotConvertible::class.java) { changeItemKind("{}", "note", labels) }
        assertThrows(FfiException.NotConvertible::class.java) { changeItemKind("não é json", "note", labels) }
        assertThrows(FfiException.NotConvertible::class.java) {
            changeItemKind("""{"id":"i1","kind":"login"}""", "otp", labels)
        }
    }

    /** Os `customFields` como triplos (nome, valor, oculto) — os `id` são novos a cada conversão e ficam de fora. */
    private fun fieldsOf(item: JSONObject): List<Triple<String, String, Boolean>> {
        val fields = item.getJSONArray("customFields")
        return (0 until fields.length()).map { index ->
            val field = fields.getJSONObject(index)
            Triple(field.getString("name"), field.getString("value"), field.getBoolean("hidden"))
        }
    }
}
