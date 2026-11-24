package com.localkeys.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A máquina de estados da pasta na lista (`ListFilterState`), em JVM pura: a preferência que chega depois entra,
 * mas nunca por cima de uma pasta que a pessoa já escolheu ou que a rotação restaurou. Nada é regravado ao abrir.
 */
class ListFilterStateTest {

    private val work = "pasta-trabalho"

    @Test
    fun `a remembered folder that arrives later is applied`() {
        // Primeiro desenho sem a preferência (o DataStore ainda não leu): a lista abre em "todas".
        val state = ListFilterState("", false, ListFilter.NONE, null)
        assertNull(state.folderKey)

        // A preferência chega alguns quadros depois e entra — sem regravar nada.
        var saved: String? = "nao-mexer"
        state.onFolderChanged = { saved = it }
        state.openIn(work)
        assertEquals(work, state.folderKey)
        assertEquals(FolderFilter.In(work), state.folder)
        assertEquals("nao-mexer", saved)

        // Uma segunda chegada não muda mais nada.
        state.openIn("outra-pasta")
        assertEquals(work, state.folderKey)
    }

    @Test
    fun `a folder chosen by the user is not overwritten by the late preference`() {
        val state = ListFilterState("", false, ListFilter.NONE, null)
        var saved: String? = null
        state.onFolderChanged = { saved = it }

        // A pessoa escolheu "sem pasta" antes de a preferência chegar.
        state.folderKey = FolderFilter.NoFolder.key
        assertEquals("", saved)

        // A lembrança antiga não pode desfazer a escolha.
        state.openIn(work)
        assertEquals(FolderFilter.NoFolder.key, state.folderKey)
        assertEquals("", saved)
    }

    @Test
    fun `a folder restored by the rotation is not overwritten`() {
        // O que a rotação/morte do processo restaura (o `Saver`) chega com `restored = true`.
        val restored = ListFilterState("", false, ListFilter.NONE, work, restored = true)
        var saved: String? = "nao-mexer"
        restored.onFolderChanged = { saved = it }

        restored.openIn(null)
        assertEquals(work, restored.folderKey)
        restored.openIn("outra-pasta")
        assertEquals(work, restored.folderKey)
        assertEquals("nao-mexer", saved)
    }
}
