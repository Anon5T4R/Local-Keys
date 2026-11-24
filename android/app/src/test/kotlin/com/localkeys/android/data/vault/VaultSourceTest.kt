package com.localkeys.android.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As decisões de "qual arquivo é o cofre aberto" (achados da revisão):
 * - o coletor do `vaultUri` não pode esquecer o conteúdo quando o URI que chega é o do cofre recém-criado (o
 *   conhecido já é dele);
 * - uma leitura atrasada de A, depois de trocar para B, é descartada (senão o blob de A ficaria com o URI de B).
 */
class VaultSourceTest {

    private val a = "content://docs/a.tkeys"
    private val b = "content://docs/b.tkeys"

    @Test
    fun `the created vault sends its own uri back and the known content is kept`() {
        assertEquals(VaultSource.UriAction.Keep, VaultSource.onUriObserved(a, a))
    }

    @Test
    fun `another vault or none reloads`() {
        assertEquals(VaultSource.UriAction.Reload, VaultSource.onUriObserved(a, b))
        assertEquals(VaultSource.UriAction.Reload, VaultSource.onUriObserved(a, null))
        assertEquals(VaultSource.UriAction.Reload, VaultSource.onUriObserved(null, a))
    }

    @Test
    fun `a delayed read of the previous vault is discarded`() {
        assertTrue(VaultSource.isCurrent(a, a))
        assertFalse("A depois de trocar para B", VaultSource.isCurrent(a, b))
        assertFalse("sem cofre atual", VaultSource.isCurrent(a, null))
    }
}
