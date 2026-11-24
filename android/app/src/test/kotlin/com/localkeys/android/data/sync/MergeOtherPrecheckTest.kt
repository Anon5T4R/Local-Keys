package com.localkeys.android.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decisão ANTES de ler o outro cofre (unidade 7, o teto de 20 MB): tamanho declarado acima do teto recusa sem ler;
 * sem declaração, a leitura é limitada ao que ainda cabe; e sem o tamanho do cofre aberto também recusa.
 */
class MergeOtherPrecheckTest {

    @Test
    fun `a declared file above the cap is refused before reading`() {
        assertEquals(MergeOtherPrecheck.TooBig, MergeOtherLimit.precheck(0, MergeOtherLimit.MAX_BYTES + 1))
        assertEquals(MergeOtherPrecheck.TooBig, MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES - 10, 11))
        assertTrue("um arquivo grande sozinho já recusa", MergeOtherLimit.precheck(0, 200L * 1024 * 1024) is MergeOtherPrecheck.TooBig)
    }

    @Test
    fun `exactly at the cap still reads`() {
        assertEquals(MergeOtherPrecheck.ReadWhole(MergeOtherLimit.MAX_BYTES - 10), MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES - 10, 10))
        assertEquals(MergeOtherPrecheck.ReadWhole(0), MergeOtherLimit.precheck(0, MergeOtherLimit.MAX_BYTES))
    }

    @Test
    fun `without a declared size the read is capped by what still fits`() {
        val check = MergeOtherLimit.precheck(5L * 1024 * 1024, null)
        assertTrue("sem declaração, lê com teto: $check", check is MergeOtherPrecheck.ReadCapped)
        check as MergeOtherPrecheck.ReadCapped
        assertEquals(5L * 1024 * 1024, check.vaultBytes)
        assertEquals(MergeOtherLimit.MAX_BYTES - 5L * 1024 * 1024, check.cap)

        val almostFull = MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES - 1, null) as MergeOtherPrecheck.ReadCapped
        assertEquals(1L, almostFull.cap)
    }

    @Test
    fun `an unknown open vault size refuses instead of assuming zero`() {
        assertEquals(MergeOtherPrecheck.UnknownVaultSize, MergeOtherLimit.precheck(null, 1))
        assertEquals(MergeOtherPrecheck.UnknownVaultSize, MergeOtherLimit.precheck(null, null))
    }

    @Test
    fun `an open vault already above the cap is refused`() {
        assertEquals(MergeOtherPrecheck.TooBig, MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES + 1, 0))
        assertEquals(MergeOtherPrecheck.TooBig, MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES + 1, null))
    }
}
