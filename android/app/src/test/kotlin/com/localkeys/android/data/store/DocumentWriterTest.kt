package com.localkeys.android.data.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Provedores falsos que falham como os de nuvem (OneDrive) falham no "wt". */
class DocumentWriterTest {

    /**
     * Um "arquivo" em memória. [truncates] diz quais modos truncam de verdade; [failOpen]/[failWrite] dizem
     * em quais modos abrir ou escrever lança.
     */
    private class FakeDoc(
        initial: ByteArray,
        val truncates: Set<String> = setOf("wt", "rwt"),
        val failOpen: Map<String, () -> Throwable> = emptyMap(),
        val failWrite: Map<String, () -> Throwable> = emptyMap(),
        val nullStream: Set<String> = emptySet(),
        val unreadable: Boolean = false,
    ) : DocumentIo {
        var content: ByteArray = initial
        val opened = mutableListOf<String>()

        override fun openOutput(mode: String): OutputStream? {
            opened += mode
            failOpen[mode]?.let { throw it() }
            if (mode in nullStream) return null
            if (mode in truncates) content = ByteArray(0)
            return object : OutputStream() {
                var pos = 0
                override fun write(b: Int) {
                    failWrite[mode]?.let { throw it() }
                    if (pos < content.size) content[pos] = b.toByte() else content += b.toByte()
                    pos++
                }
            }
        }

        override fun openInput(): InputStream? {
            if (unreadable) throw IOException("no read")
            return ByteArrayInputStream(content)
        }
    }

    private val newBytes = "novo cofre".toByteArray()
    private val oldLonger = "cofre antigo bem mais comprido".toByteArray()

    @Test
    fun `wt that works is the only attempt`() {
        val doc = FakeDoc(oldLonger)
        val back = DocumentWriter.write(doc, newBytes)
        assertArrayEquals(newBytes, back)
        assertEquals(listOf("wt"), doc.opened)
    }

    @Test
    fun `wt refused with UnsupportedOperationException falls back to w`() {
        val doc = FakeDoc(
            ByteArray(0),
            failOpen = mapOf("wt" to { UnsupportedOperationException("mode wt") }),
        )
        assertArrayEquals(newBytes, DocumentWriter.write(doc, newBytes))
        assertEquals(listOf("wt", "w"), doc.opened)
    }

    @Test
    fun `SecurityException and IllegalStateException on wt also fall back`() {
        for (thrower in listOf<() -> Throwable>({ SecurityException("nope") }, { IllegalStateException("x") })) {
            val doc = FakeDoc(ByteArray(0), failOpen = mapOf("wt" to thrower))
            assertArrayEquals(newBytes, DocumentWriter.write(doc, newBytes))
        }
    }

    @Test
    fun `EBADF while writing in wt retries the whole write with w`() {
        // O descritor do "wt" abre mas falha no write (o EBADF do OneDrive), depois de ter truncado.
        val doc = FakeDoc(
            oldLonger,
            truncates = setOf("wt", "w"),
            failWrite = mapOf("wt" to { IOException("write failed", RuntimeException("EBADF (Bad file descriptor)")) }),
        )
        assertArrayEquals(newBytes, DocumentWriter.write(doc, newBytes))
        assertEquals(listOf("wt", "w"), doc.opened)
    }

    @Test
    fun `null stream counts as a failure`() {
        val doc = FakeDoc(ByteArray(0), nullStream = setOf("wt"))
        assertArrayEquals(newBytes, DocumentWriter.write(doc, newBytes))
        assertEquals(listOf("wt", "w"), doc.opened)
    }

    @Test
    fun `w that does not truncate is recovered with rwt`() {
        val doc = FakeDoc(
            oldLonger,
            truncates = setOf("rwt"),
            failOpen = mapOf("wt" to { IllegalArgumentException("bad mode") }),
        )
        assertArrayEquals(newBytes, DocumentWriter.write(doc, newBytes))
        assertEquals(listOf("wt", "w", "rwt"), doc.opened)
    }

    @Test
    fun `w leaving trailing bytes with no way to truncate is a clear error`() {
        val doc = FakeDoc(
            oldLonger,
            truncates = emptySet(),
            failOpen = mapOf("wt" to { IllegalArgumentException("bad mode") }),
        )
        try {
            DocumentWriter.write(doc, newBytes)
            fail("deveria acusar a sobra")
        } catch (e: DocumentWriteException.NotTruncated) {
            assertEquals(newBytes.size, e.written)
            assertEquals(oldLonger.size, e.found)
        }
    }

    @Test
    fun `every mode failing reports each cause`() {
        val doc = FakeDoc(
            ByteArray(0),
            failOpen = mapOf("wt" to { UnsupportedOperationException("no wt") }),
            failWrite = mapOf("w" to { IOException("write failed: EBADF (Bad file descriptor)") }),
        )
        try {
            DocumentWriter.write(doc, newBytes)
            fail("deveria falhar")
        } catch (e: DocumentWriteException.Failed) {
            assertEquals(listOf("wt", "w"), e.attempts.map { it.first })
            assertTrue(e.message!!.contains("no wt"))
            assertTrue(e.message!!.contains("EBADF"))
        }
    }

    @Test
    fun `unreadable document returns null so the caller rejects it`() {
        val doc = FakeDoc(ByteArray(0), unreadable = true)
        assertEquals(null, DocumentWriter.write(doc, newBytes))
    }

    @Test
    fun `describeCause walks the cause chain`() {
        val e = IOException("write failed", RuntimeException("EBADF (Bad file descriptor)"))
        val text = describeCause(e)
        assertTrue(text, text.contains("IOException: write failed"))
        assertTrue(text, text.contains("EBADF"))
    }
}
