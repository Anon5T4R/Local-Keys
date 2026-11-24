package com.localkeys.android.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A seleção múltipla da lista do Android, em JVM pura: o toque longo entra no modo e marca o item, o toque
 * alterna (e sai do modo quando o último sai), "selecionar tudo" pega os visíveis, fechar limpa, e trocar busca,
 * filtro ou pasta zera a seleção. O toque fora do modo continua abrindo o item.
 */
class ListSelectionModeTest {

    private val visible = listOf("a", "b", "c", "d")

    @Test
    fun `a long press enters the selection mode with that item`() {
        val mode = ListSelectionMode()
        assertFalse(mode.active)

        mode.onLongPress("b")
        assertTrue(mode.active)
        assertEquals(listOf("b"), mode.ids())
        assertEquals(1, mode.len)
        assertTrue(mode.contains("b"))
        assertFalse(mode.contains("a"))
    }

    @Test
    fun `in the mode a tap toggles and leaving it empty closes the mode`() {
        val mode = ListSelectionMode()
        mode.onLongPress("a")

        assertFalse("no modo o toque não abre o item", mode.onTap("b"))
        assertEquals(listOf("a", "b"), mode.ids())

        mode.onTap("a")
        assertEquals(listOf("b"), mode.ids())
        assertTrue(mode.active)

        mode.onTap("b")
        assertTrue("sair do último item fecha o modo", mode.isEmpty)
        assertFalse(mode.active)
    }

    @Test
    fun `a long press in the mode toggles like a tap`() {
        val mode = ListSelectionMode()
        mode.onLongPress("a")
        mode.onLongPress("a")
        assertTrue(mode.isEmpty)
        assertFalse(mode.active)
    }

    @Test
    fun `outside the mode a tap opens the item and selects nothing`() {
        val mode = ListSelectionMode()
        assertTrue("quem chamou abre o item", mode.onTap("a"))
        assertFalse(mode.active)
        assertTrue(mode.isEmpty)
    }

    @Test
    fun `select all takes the visible list`() {
        val mode = ListSelectionMode()
        mode.selectAll(visible)
        assertEquals(visible, mode.ids())
        assertEquals(visible.size, mode.len)
        assertTrue(mode.active)

        // Selecionar tudo de novo não duplica nem desmarca nada.
        mode.selectAll(visible)
        assertEquals(visible, mode.ids())
        assertTrue(mode.active)
    }

    @Test
    fun `select all with an empty list stays out of the mode`() {
        val mode = ListSelectionMode()
        mode.selectAll(emptyList())
        assertTrue(mode.isEmpty)
        assertFalse(mode.active)
    }

    @Test
    fun `closing the mode clears the selection`() {
        val mode = ListSelectionMode()
        mode.onLongPress("a")
        mode.onTap("c")

        mode.clear()
        assertTrue(mode.isEmpty)
        assertFalse(mode.active)
        // Depois de fechar, o toque volta a abrir o item.
        assertTrue(mode.onTap("a"))
        assertTrue(mode.isEmpty)
    }

    @Test
    fun `changing the search, the filter or the folder clears the selection`() {
        val mode = ListSelectionMode()
        mode.syncFilter("busca=;filtro=;pasta=")
        mode.onLongPress("a")
        mode.onTap("b")
        assertEquals(2, mode.len)

        // A mesma lista (recomposição, rotação): não perde a seleção.
        mode.syncFilter("busca=;filtro=;pasta=")
        assertEquals(2, mode.len)
        assertTrue(mode.active)

        // A lista mudou: os ids marcados podem nem estar nela.
        mode.syncFilter("busca=1lib;filtro=;pasta=")
        assertTrue(mode.isEmpty)
        assertFalse(mode.active)
    }

    /**
     * O que a tela lê (`len`/`contains`) é ESTADO OBSERVÁVEL: um valor derivado do snapshot recálcula a cada
     * mudança, então marcar/desmarcar, "selecionar tudo" e limpar recompõem a lista e a contagem do topo. Com um
     * `mutableSetOf` comum o valor ficaria preso e a tela mostraria "1 selecionado" com N ids no lote.
     */
    @Test
    fun `the selection state is observable`() {
        val mode = ListSelectionMode()
        var reads = 0
        val marked = derivedStateOf {
            reads += 1
            mode.len
        }
        assertEquals(0, marked.value)
        assertEquals("o valor derivado leu o estado uma vez", 1, reads)

        mode.onLongPress("a")
        Snapshot.sendApplyNotifications()
        assertEquals(1, marked.value)
        assertEquals("o toggle invalidou a leitura", 2, reads)

        mode.selectAll(listOf("a", "b", "c"))
        Snapshot.sendApplyNotifications()
        assertEquals(3, marked.value)
        assertEquals("selecionar tudo invalidou a leitura", 3, reads)

        mode.clear()
        Snapshot.sendApplyNotifications()
        assertEquals(0, marked.value)
        assertEquals("limpar invalidou a leitura", 4, reads)
    }
}
