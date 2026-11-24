package com.localkeys.android.data.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A leitura com teto do outro cofre (unidade 7): com `SIZE` declarado aloca exatamente `min(declarado, teto)` (nada do
 * `ByteArrayOutputStream` que dobra a capacidade e copia), confere o byte sobrando, e o caminho sem declaração cresce
 * até o teto. O fluxo que lança sai com o motivo real, não como "arquivo não encontrado".
 */
class CappedStreamTest {

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    @Test
    fun `exactly the cap reads everything`() {
        val data = bytes(8)
        val result = CappedStream.read(ByteArrayInputStream(data), cap = 8, declared = 8)
        assertTrue(result is CappedStream.Result.Bytes)
        assertArrayEquals(data, (result as CappedStream.Result.Bytes).bytes)
    }

    @Test
    fun `one byte over the cap is refused`() {
        val read = CappedStream.read(ByteArrayInputStream(bytes(9)), cap = 8, declared = 8)
        assertEquals(CappedStream.Result.TooBig, read)
    }

    @Test
    fun `a declaration smaller than the real file keeps reading up to the cap`() {
        val data = bytes(6)
        val result = CappedStream.read(ByteArrayInputStream(data), cap = 8, declared = 4)
        assertTrue("o declarado mentiu para menos: lê o resto", result is CappedStream.Result.Bytes)
        assertArrayEquals(data, (result as CappedStream.Result.Bytes).bytes)
    }

    @Test
    fun `a smaller declaration still refuses one byte over the cap`() {
        assertEquals(CappedStream.Result.TooBig, CappedStream.read(ByteArrayInputStream(bytes(9)), cap = 8, declared = 4))
    }

    @Test
    fun `a declaration bigger than the real file returns what exists`() {
        val data = bytes(4)
        val result = CappedStream.read(ByteArrayInputStream(data), cap = 8, declared = 8)
        assertArrayEquals(data, (result as CappedStream.Result.Bytes).bytes)
    }

    @Test
    fun `without a declaration the read grows up to the cap`() {
        val data = bytes(5)
        val result = CappedStream.read(ByteArrayInputStream(data), cap = 8, declared = null)
        assertArrayEquals(data, (result as CappedStream.Result.Bytes).bytes)
        assertEquals(CappedStream.Result.TooBig, CappedStream.read(ByteArrayInputStream(bytes(9)), cap = 8, declared = null))
    }

    @Test
    fun `a stream that throws reports the real cause`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("provider died")
        }
        val result = CappedStream.read(failing, cap = 8, declared = null)
        assertTrue(result is CappedStream.Result.Failed)
        assertEquals("provider died", (result as CappedStream.Result.Failed).cause.message)
    }

    @Test
    fun `a stream that stops mid read is fine`() {
        val partial = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 2) 7 else -1
        }
        val result = CappedStream.read(partial, cap = 8, declared = 8)
        assertEquals(2, (result as CappedStream.Result.Bytes).bytes.size)
    }
}
