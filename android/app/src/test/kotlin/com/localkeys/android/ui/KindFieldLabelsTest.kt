package com.localkeys.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Os rótulos dos campos que a mudança de tipo GERA: o nome que o campo extra ganha DENTRO do cofre. Ele é dado
 * gravado, não tela: o mesmo item convertido tem que nascer com os mesmos nomes no Android e no desktop
 * (`field_labels()` do `src/main.rs`). Este teste lê os dois `strings.xml` do app e compara com a tabela do
 * desktop — o rótulo do formulário pode ser outro ("Website", "Chave TOTP (2FA)"), porque ele não vai para o
 * cofre; o do campo gerado, não.
 *
 * A tabela é a mesma que o editor passa para a ponte (`FieldLabelsFfi` em `ItemEditorScreen`).
 */
class KindFieldLabelsTest {

    /** Um campo da ponte, o recurso com o texto e o que o desktop grava em cada idioma. */
    private data class Label(val bridgeField: String, val resource: String, val english: String, val portuguese: String)

    private val labels = listOf(
        Label("username", "field_username", "Username", "Usuário"),
        Label("password", "field_password", "Password", "Senha"),
        Label("website", "kind_field_website", "URL", "URL"),
        Label("totp", "kind_field_totp", "TOTP", "TOTP"),
        Label("cardholder", "field_cardholder", "Cardholder", "Titular"),
        Label("brand", "field_brand", "Brand", "Bandeira"),
        Label("cardNumber", "field_card_number", "Card number", "Número do cartão"),
        Label("expiry", "field_exp_mask", "Expiry (MM/YY)", "Validade (MM/AA)"),
        Label("securityCode", "field_code", "Security code", "Código de segurança"),
        Label("firstName", "field_first_name", "First name", "Nome"),
        Label("lastName", "field_last_name", "Last name", "Sobrenome"),
        Label("email", "kind_field_email", "E-mail", "E-mail"),
        Label("phone", "field_phone", "Phone", "Telefone"),
        Label("address", "field_address", "Address", "Endereço"),
    )

    /** As strings de um `strings.xml` por nome. O diretório do módulo é o de trabalho dos testes de unidade. */
    private fun strings(directory: String): Map<String, String> {
        val candidates = listOf(
            File("src/main/res/$directory/strings.xml"),
            File("app/src/main/res/$directory/strings.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("achei o strings.xml de $directory em ${candidates.map { it.absolutePath }}", file != null)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val out = mutableMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            out[name] = node.textContent
        }
        return out
    }

    @Test
    fun os_campos_gerados_nascem_com_os_nomes_do_desktop_nos_dois_idiomas() {
        val english = strings("values")
        val portuguese = strings("values-pt-rBR")
        for (label in labels) {
            assertEquals(
                "${label.bridgeField}: o inglês gerado tem que ser o do desktop",
                label.english,
                english[label.resource],
            )
            assertEquals(
                "${label.bridgeField}: o pt-BR gerado tem que ser o do desktop",
                label.portuguese,
                portuguese[label.resource],
            )
        }
    }
}
