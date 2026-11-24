package com.localkeys.android.data.vault

import com.localkeys.android.data.store.DocumentIo
import com.localkeys.android.data.store.DocumentWriteException
import com.localkeys.android.data.store.DocumentWriter
import java.io.IOException

/**
 * A gravação CONFERIDA de um cofre — o passo comum do salvar, tanto da edição de um item quanto do lote da seleção:
 * cifra o candidato, grava no documento, relê e confere **contra o candidato** (não contra o cofre que ainda está em
 * memória) e só então o adota no repositório.
 *
 * Sem Android aqui: documento, cifra e repositório entram por parâmetro, então a sequência inteira roda na JVM dos
 * testes. As falhas saem tipadas ([SaveFailure]) e o `VaultViewModel` só as traduz para os textos da tela.
 */
class VaultSaver(private val repository: VaultRepository) {

    /** O último conteúdo CONHECIDO do documento (o que este app gravou/leu): detecta mudança externa antes de gravar. */
    @Volatile
    var known: ByteArray? = null
        private set

    /** Alimenta/limpa o conteúdo conhecido (carregar o cofre, reler do disco, esquecer o cofre). */
    fun seed(blob: ByteArray?) {
        known = blob
    }

    /** Por que a gravação não terminou (a UI resolve o texto; nada de recurso de Android aqui). */
    sealed class SaveFailure(cause: Throwable?) : Exception(cause) {
        /** O arquivo mudou fora do app desde [known] — não sobrescreve sem o usuário confirmar. */
        class ExternalChange : SaveFailure(null)

        /** O provedor gravou mas não truncou: sobraram [found] bytes depois dos [written]. */
        class NotTruncated(val written: Int, val found: Int) : SaveFailure(null)

        /** Nenhum modo de escrita funcionou; [attempts] = modo → o que o provedor lançou. */
        class WriteFailed(val attempts: List<Pair<String, Throwable>>) : SaveFailure(attempts.lastOrNull()?.second)

        /** A releitura não bateu e a segunda tentativa falhou. */
        class RetryFailed(cause: Throwable?) : SaveFailure(cause)

        /** Gravou duas vezes e a releitura continuou não batendo com o candidato. */
        object Unrecoverable : SaveFailure(null)
    }

    /**
     * Cifra [expected], grava em [document] com a conferência pós-gravação e só então o adota em memória.
     * `force` ignora a mudança externa (o usuário confirmou no diálogo de conflito). Devolve o cofre adotado.
     *
     * @throws SaveFailure quando o arquivo não pode ser gravado/conferido — e aí nada é adotado.
     */
    fun saveAndCommit(expected: Vault, document: DocumentIo, force: Boolean = false): Vault {
        val file = repository.seal(expected)
        repository.verifySaved(file, expected) // nunca grava um blob que não reabriria
        write(file, expected, document, force)
        repository.replace(expected)
        return expected
    }

    /**
     * Grava [file] (já cifrado) e confere a releitura contra [expected]; atualiza [known] no sucesso. Não adota
     * nada — o chamador decide (o cofre recém-criado já está no repositório).
     *
     * @throws SaveFailure quando o arquivo não pode ser gravado/conferido.
     */
    fun write(file: ByteArray, expected: Vault, document: DocumentIo, force: Boolean = false) {
        if (!force) {
            val disk = read(document)
            val knownNow = known
            if (disk != null && knownNow != null && !disk.contentEquals(knownNow)) throw SaveFailure.ExternalChange()
        }
        val first = writeOnce(document, file)
        if (matches(first, expected)) {
            known = file
            return
        }
        val retry = try {
            DocumentWriter.write(document, file)
        } catch (e: Exception) {
            throw SaveFailure.RetryFailed(e)
        }
        if (!matches(retry, expected)) throw SaveFailure.Unrecoverable
        known = file
    }

    /** Uma gravação, com as falhas do provedor traduzidas para [SaveFailure]. */
    private fun writeOnce(document: DocumentIo, file: ByteArray): ByteArray? = try {
        DocumentWriter.write(document, file)
    } catch (e: DocumentWriteException.NotTruncated) {
        throw SaveFailure.NotTruncated(e.written, e.found)
    } catch (e: DocumentWriteException.Failed) {
        throw SaveFailure.WriteFailed(e.attempts)
    }

    /**
     * A releitura reabre com a sessão e é exatamente [expected]? O provedor que não deixa reler (`null`) não passa:
     * como antes, isso cai na segunda tentativa e, se ela também não reler, em [SaveFailure.Unrecoverable].
     */
    private fun matches(blob: ByteArray?, expected: Vault): Boolean =
        blob != null && runCatching { repository.verifySaved(blob, expected) }.isSuccess

    /** O que está gravado agora (null quando o provedor não deixa ler). */
    private fun read(document: DocumentIo): ByteArray? = try {
        document.openInput()?.use { it.readBytes() }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }
}
