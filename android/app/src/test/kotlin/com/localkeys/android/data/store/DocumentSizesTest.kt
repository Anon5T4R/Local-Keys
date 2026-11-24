package com.localkeys.android.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/**
 * A decisão de apagar (ou não) o documento do "criar": só vazio. Um gerenciador pode devolver um arquivo existente no
 * "substituir" e o `SIZE` pode vir -1 (desconhecido).
 */
class DocumentSizesTest {

    @Test
    fun `a declared size of minus one is unknown`() {
        assertNull(declaredDocumentSize(-1))
        assertNull(declaredDocumentSize(null))
        assertEquals(0L, declaredDocumentSize(0))
        assertEquals(42L, declaredDocumentSize(42))
    }

    @Test
    fun `an empty document can be discarded`() {
        assertTrue(EmptyDocument.isDiscardable(declaredSize = 0, readSize = { error("não lê o que já sabe") }))
        assertTrue(EmptyDocument.isDiscardable(declaredSize = null, readSize = { 0 }))
    }

    @Test
    fun `a document with content is never discarded`() {
        assertEquals(false, EmptyDocument.isDiscardable(declaredSize = 10, readSize = { 0 }))
        assertEquals(false, EmptyDocument.isDiscardable(declaredSize = null, readSize = { 10 }))
        assertEquals(false, EmptyDocument.isDiscardable(declaredSize = -1, readSize = { 10 }))
    }

    @Test
    fun `a document that cannot be read is not discarded`() {
        assertEquals(false, EmptyDocument.isDiscardable(declaredSize = null, readSize = { null }))
    }
}
