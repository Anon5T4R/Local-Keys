package com.localkeys.android.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O teto de tamanho do Android (unidade 8), na JVM: o base64 de cada anexo, o previsto com cofre novo, com anexos
 * pendentes e exatamente no teto (que ainda não avisa) versus um byte acima.
 */
class VaultSizeLimitTest {

    @Test
    fun `base64 grows in threes`() {
        assertEquals(0L, VaultSizeLimit.base64Bytes(0))
        assertEquals(4L, VaultSizeLimit.base64Bytes(1))
        assertEquals(4L, VaultSizeLimit.base64Bytes(3))
        assertEquals(8L, VaultSizeLimit.base64Bytes(4))
        assertEquals(13_981_016L, VaultSizeLimit.base64Bytes(10L * 1024 * 1024))
    }

    @Test
    fun `a new vault counts only the attachments`() {
        assertEquals(0L, VaultSizeLimit.predictedVaultBytes(0, emptyList()))
        assertEquals(4L * 1024 * 1024, VaultSizeLimit.predictedVaultBytes(0, listOf(3L * 1024 * 1024)))
    }

    @Test
    fun `the prediction sums the open vault and the pending attachments`() {
        // 500 bytes de cofre + um pendente de 3 MiB (cru → base64) + outro de 1 byte.
        val predicted = VaultSizeLimit.predictedVaultBytes(500, listOf(3L * 1024 * 1024, 1))
        assertEquals(500 + 4L * 1024 * 1024 + 4, predicted)
    }

    @Test
    fun `the warning appears only above the cap`() {
        assertFalse("exatamente no teto ainda não avisa", VaultSizeLimit.exceedsWarnLimit(VaultSizeLimit.MAX_BYTES))
        assertFalse(
            "a soma exata no teto ainda não avisa",
            VaultSizeLimit.exceedsWarnLimit(VaultSizeLimit.MAX_BYTES - 4),
        )
        assertTrue("um byte acima avisa", VaultSizeLimit.exceedsWarnLimit(VaultSizeLimit.MAX_BYTES + 1))
        assertNull(VaultSizeLimit.attachWarning(VaultSizeLimit.MAX_BYTES - 4, emptyList(), 3))
        assertTrue("um byte acima avisa", VaultSizeLimit.attachWarning(VaultSizeLimit.MAX_BYTES - 4, emptyList(), 4) != null)
    }

    @Test
    fun `a new vault with many pending attachments can reach the cap with small files`() {
        // Cofre novo (0) + cinco pendentes de 4 MiB: 5 × 5,33 MiB em base64 passa de 20 MiB.
        val pending = List(5) { 4L * 1024 * 1024 }
        val warning = VaultSizeLimit.attachWarning(0, pending, 1)
        assertTrue("o aviso aparece: ${warning ?: "sem aviso"}", warning != null && warning > VaultSizeLimit.MAX_BYTES)
    }
}
