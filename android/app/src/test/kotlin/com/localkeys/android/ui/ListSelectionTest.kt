package com.localkeys.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A seleção da lista do Android (`ListSelection`), em JVM pura: Ctrl+clique marca/desmarca, Shift+clique marca o
 * intervalo pela ordem visível e trocar filtro, busca ou pasta zera a seleção.
 */
class ListSelectionTest {

    private val visible = listOf("a", "b", "c", "d", "e")

    @Test
    fun `toggle adds in order and removes on the second click`() {
        val selection = ListSelection()
        selection.toggle("a")
        selection.toggle("b")
        assertEquals(listOf("a", "b"), selection.ids())
        assertEquals(2, selection.len)
        assertTrue(selection.contains("b"))

        selection.toggle("a")
        assertEquals(listOf("b"), selection.ids())
        assertFalse(selection.contains("a"))
    }

    @Test
    fun `selectOnly replaces the whole selection`() {
        val selection = ListSelection()
        selection.toggle("a")
        selection.toggle("b")

        selection.selectOnly("c")
        assertEquals(listOf("c"), selection.ids())

        selection.selectOnly("d")
        assertEquals(listOf("d"), selection.ids())
    }

    @Test
    fun `range selects the interval in the visible order`() {
        val selection = ListSelection()

        selection.range("b", "d", visible)
        assertEquals(listOf("b", "c", "d"), selection.ids())

        // De baixo para cima é o mesmo intervalo.
        selection.range("e", "c", visible)
        assertEquals(listOf("c", "d", "e"), selection.ids())

        // Ponta a ponta: o id só seleciona ele mesmo.
        selection.range("a", "a", visible)
        assertEquals(listOf("a"), selection.ids())
    }

    @Test
    fun `range with an id that left the visible list keeps the selection`() {
        val selection = ListSelection()
        selection.toggle("b")

        selection.range("a", "ghost", visible)
        assertEquals(listOf("b"), selection.ids())

        selection.range("ghost", "c", visible)
        assertEquals(listOf("b"), selection.ids())
    }

    @Test
    fun `changing the filter, the folder or the search clears the selection`() {
        val selection = ListSelection()
        selection.toggle("a")
        selection.toggle("b")

        selection.resetForFilterChange()
        assertTrue(selection.isEmpty)
        assertEquals(0, selection.len)
        assertTrue(selection.ids().isEmpty())

        // Limpar de novo não quebra nada.
        selection.resetForFilterChange()
        assertTrue(selection.isEmpty)
    }

    @Test
    fun `clear empties the selection`() {
        val selection = ListSelection()
        selection.toggle("a")

        selection.clear()
        assertTrue(selection.isEmpty)
    }
}
