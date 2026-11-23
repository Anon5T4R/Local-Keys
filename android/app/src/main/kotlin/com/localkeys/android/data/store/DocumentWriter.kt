package com.localkeys.android.data.store

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * O documento do SAF visto pelo [DocumentWriter]: abrir para escrever num modo ("wt", "w", "rwt") e reler.
 * Na vida real é o ContentResolver ([ResolverDocument]); nos testes, streams falsos que falham do jeito que os
 * provedores de nuvem falham.
 */
interface DocumentIo {
    /** Pode lançar qualquer coisa (IOException, UnsupportedOperationException, SecurityException…) ou dar null. */
    fun openOutput(mode: String): OutputStream?

    fun openInput(): InputStream?
}

/** Falha de gravação, com a causa de cada tentativa (o usuário vê a causa, não só "não deu"). */
sealed class DocumentWriteException(message: String, cause: Throwable?) : IOException(message, cause) {
    /** Nenhum modo de escrita funcionou. [attempts] = modo → o que o provedor lançou. */
    class Failed(val attempts: List<Pair<String, Throwable>>) :
        DocumentWriteException(
            attempts.joinToString("; ") { (mode, e) -> "$mode: ${describeCause(e)}" },
            attempts.lastOrNull()?.second,
        )

    /** O provedor gravou mas não truncou: sobrou o fim do arquivo anterior depois dos bytes novos. */
    class NotTruncated(val written: Int, val found: Int) :
        DocumentWriteException("the provider kept $found bytes after writing $written", null)
}

/**
 * Grava um documento inteiro do zero, à prova dos provedores de nuvem (OneDrive, Drive…).
 *
 * O "w" do SAF não trunca em todo provedor (um cofre que encolheu ficava com o rabo do anterior), então tenta
 * "wt" primeiro. Mas provedor de nuvem às vezes recusa o "wt" com uma exceção qualquer, ou entrega um descritor
 * que só falha no `write` (EBADF): por isso a gravação **inteira** (abrir, escrever, fechar) fica dentro de cada
 * tentativa, e qualquer falha cai para o "w". Depois de gravar, relê: se o arquivo ficou maior que o gravado
 * (o "w" não truncou), tenta uma vez o "rwt" e, se ainda sobrar, acusa [DocumentWriteException.NotTruncated]
 * em vez de deixar bytes velhos no fim em silêncio.
 *
 * Devolve os bytes relidos (null se o provedor não deixou reler) para o chamador conferir que o arquivo reabre.
 */
object DocumentWriter {

    fun write(doc: DocumentIo, bytes: ByteArray): ByteArray? {
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (mode in listOf("wt", "w")) {
            val error = attempt(doc, mode, bytes)
            if (error != null) {
                failures += mode to error
                continue
            }
            val back = readBack(doc)
            if (back == null || back.size <= bytes.size) return back
            return truncateRecovery(doc, bytes, back.size)
        }
        throw DocumentWriteException.Failed(failures)
    }

    /** O provedor deixou sobra: "rwt" é a última chance de truncar. */
    private fun truncateRecovery(doc: DocumentIo, bytes: ByteArray, found: Int): ByteArray? {
        if (attempt(doc, "rwt", bytes) != null) throw DocumentWriteException.NotTruncated(bytes.size, found)
        val back = readBack(doc)
        if (back != null && back.size > bytes.size) throw DocumentWriteException.NotTruncated(bytes.size, back.size)
        return back
    }

    /** Abre, escreve tudo e fecha. Null = deu certo; senão, a falha (de qualquer tipo, só não Error). */
    private fun attempt(doc: DocumentIo, mode: String, bytes: ByteArray): Throwable? =
        try {
            val out = doc.openOutput(mode) ?: throw IOException("the provider returned no stream")
            out.use {
                it.write(bytes)
                it.flush()
            }
            null
        } catch (e: IOException) {
            e
        } catch (e: RuntimeException) {
            e
        }

    private fun readBack(doc: DocumentIo): ByteArray? =
        try {
            doc.openInput()?.use { it.readBytes() }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * Texto curto da causa para o usuário: o tipo e a mensagem, descendo pelas causas (a ErrnoException do EBADF
 * vem embrulhada numa IOException). Sem pilha.
 */
fun describeCause(e: Throwable): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < 4) {
        val msg = current.message?.takeIf { it.isNotBlank() }
        val text = if (msg != null) "${current.javaClass.simpleName}: $msg" else current.javaClass.simpleName
        if (parts.none { it.contains(msg ?: text) }) parts += text
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return parts.joinToString(" ← ")
}
