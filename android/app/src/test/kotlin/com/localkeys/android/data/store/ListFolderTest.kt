package com.localkeys.android.data.store

import com.localkeys.android.ui.FolderFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class ListFolderTest {

    private val work = "pasta-trabalho"
    private val home = "pasta-casa"

    @Test
    fun `the saved key is the folder filter key`() {
        // Codificar/decodificar: a forma guardada é a mesma que o seletor restaura (null = todas, "" = sem pasta,
        // o id da pasta nos outros casos).
        listOf(null, "", work).forEach { key -> assertEquals(key, FolderFilter.fromKey(key).key) }
    }

    @Test
    fun `a remembered folder opens in that folder`() {
        assertEquals(work, ListFolder.openKey(work, listOf(work, home)))
    }

    @Test
    fun `all and no-folder are remembered too`() {
        assertEquals(null, ListFolder.openKey(null, listOf(work, home)))
        assertEquals("", ListFolder.openKey("", listOf(work, home)))
    }

    @Test
    fun `a deleted folder opens in all`() {
        assertEquals(null, ListFolder.openKey(work, listOf(home)))
        assertEquals(null, ListFolder.openKey(work, emptyList()))
    }
}
