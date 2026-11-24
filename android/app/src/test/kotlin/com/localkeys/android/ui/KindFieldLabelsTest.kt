package com.localkeys.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Os rótulos dos campos que a mudança de tipo GERA: o nome que o campo extra ganha DENTRO do cofre. Ele é dado
 * gravado, não tela: o mesmo item convertido tem que nascer com os mesmos nomes no Android e no desktop.
 *
 * A fonte é o próprio desktop: este teste lê `field_labels()` do `src/main.rs` (os `tr("…")` de cada campo) e o
 * catálogo `i18n/locales/pt-br/localkeys.tsv`, e compara com os dois `strings.xml` do app. Se o desktop mudar um
 * rótulo — ou a tradução — o teste quebra, em vez de a cópia à mão envelhecer calada. O rótulo do FORMULÁRIO pode
 * ser outro ("Website", "Chave TOTP (2FA)"); o do campo gerado, não.
 */
class KindFieldLabelsTest {

    /** Um campo de `field_labels()` do desktop e o recurso do Android com o mesmo texto. */
    private data class Label(val field: String, val resource: String)

    private val labels = listOf(
        Label("username", "field_username"),
        Label("password", "field_password"),
        Label("website", "kind_field_website"),
        Label("totp", "kind_field_totp"),
        Label("cardholder", "field_cardholder"),
        Label("brand", "field_brand"),
        Label("card_number", "field_card_number"),
        Label("expiry", "field_exp_mask"),
        Label("security_code", "field_code"),
        Label("first_name", "field_first_name"),
        Label("last_name", "field_last_name"),
        Label("email", "kind_field_email"),
        Label("phone", "field_phone"),
        Label("address", "field_address"),
    )

    /** A raiz do repo: o diretório do módulo (`android/app`) não é fixo no runner, então sobe até achar o desktop. */
    private fun repoRoot(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (File(directory, "src/main.rs").isFile && File(directory, "android").isDirectory) return directory
            directory = directory.parentFile
        }
        throw AssertionError("não achei a raiz do repo a partir de ${File(".").absolutePath}")
    }

    /** `field_labels()` do desktop: o campo → o `tr("…")` do struct. */
    private fun desktopLabels(): Map<String, String> {
        val source = File(repoRoot(), "src/main.rs").readText()
        val signature = source.indexOf("fn field_labels()")
        assertTrue("field_labels() existe no src/main.rs do desktop", signature >= 0)
        val open = source.indexOf('{', signature)
        val close = source.indexOf("\n}", open)
        assertTrue("o corpo de field_labels() fecha", open >= 0 && close > open)
        val body = source.substring(open + 1, close)
        return Regex("""(\w+):\s*tr\("([^"]*)"\)\.into\(\)""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** O catálogo pt-BR do desktop: `\t<chave>\t<tradução>`. */
    private fun portugueseCatalog(): Map<String, String> {
        val lines = File(repoRoot(), "i18n/locales/pt-br/localkeys.tsv").readLines()
        val out = mutableMapOf<String, String>()
        for (line in lines) {
            val columns = line.split('\t')
            if (columns.size >= 3 && columns[1].isNotBlank()) out[columns[1]] = columns[2]
        }
        return out
    }

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
        val desktop = desktopLabels()
        val desktopPortuguese = portugueseCatalog()
        val english = strings("values")
        val portuguese = strings("values-pt-rBR")

        assertEquals(
            "field_labels() do desktop tem os mesmos 14 campos da tabela",
            labels.map { it.field }.sorted(),
            desktop.keys.sorted(),
        )

        for (label in labels) {
            val key = desktop.getValue(label.field)
            assertEquals(
                "${label.field}: o inglês do Android tem que ser o tr(…) do desktop",
                key,
                english[label.resource],
            )
            val translated = desktopPortuguese[key]
            assertTrue("${label.field}: o catálogo pt-BR do desktop traduz \"$key\"", translated != null)
            assertEquals(
                "${label.field}: o pt-BR do Android tem que ser a tradução do desktop",
                translated,
                portuguese[label.resource],
            )
        }
    }
}
