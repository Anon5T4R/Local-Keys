package com.localkeys.android.data.store

import java.io.InputStream

/**
 * Leitura de um fluxo com TETO, sem Android (recebe o [InputStream]): o ViewModel só abre o fluxo do resolver.
 *
 * Com o tamanho declarado, aloca exatamente `min(declarado, teto)` e lê até encher — em vez do
 * `ByteArrayOutputStream`, que dobra a capacidade e ainda copia no `toByteArray` (ler 20 MB segurava ~53 MB). Se
 * sobrar byte, o arquivo passa do teto quando o declarado já era o teto; se o provedor declarou MENOS do que o
 * arquivo real, a leitura continua pelo caminho que cresce, ainda com o teto. Sem declaração, só o caminho que
 * cresce.
 */
object CappedStream {

    /** O resultado da leitura: os bytes, o teto estourado ou a falha do fluxo (com o motivo real). */
    sealed interface Result {
        data class Bytes(val bytes: ByteArray) : Result
        data object TooBig : Result
        data class Failed(val cause: Throwable) : Result
    }

    fun read(input: InputStream, cap: Long, declared: Long?): Result = try {
        if (declared != null) readDeclared(input, cap, declared) else readGrowing(input, cap)
    } catch (e: Exception) {
        Result.Failed(e)
    }

    /** Aloca o que o provedor disse (limitado ao teto) e confere se sobrou byte depois de encher. */
    private fun readDeclared(input: InputStream, cap: Long, declared: Long): Result {
        val size = minOf(declared, cap).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = input.read(buffer, filled, size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled < size) return Result.Bytes(buffer.copyOf(filled)) // o arquivo é menor que o declarado
        // Encheu o declarado: exatamente igual é o caso normal; sobrando byte, ou o declarado era o teto (o arquivo
        // passa dele) ou o provedor mentiu para menos (aí continua, ainda com o teto).
        val extra = input.read()
        if (extra < 0) return Result.Bytes(buffer)
        if (size.toLong() >= cap) return Result.TooBig
        // O byte extra já lido também conta no teto.
        val rest = readGrowing(input, cap - filled - 1)
        return when (rest) {
            is Result.Bytes -> Result.Bytes(buffer + byteArrayOf(extra.toByte()) + rest.bytes)
            else -> rest
        }
    }

    /** Sem tamanho confiável: cresce em blocos, parando no teto. */
    private fun readGrowing(input: InputStream, cap: Long): Result {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) return Result.TooBig
            out.write(buffer, 0, read)
        }
        return Result.Bytes(out.toByteArray())
    }
}
