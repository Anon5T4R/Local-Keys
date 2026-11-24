package com.localkeys.android.ui

import com.localkeys.android.data.crypto.MergeOtherResult
import com.localkeys.android.data.sync.MergeOtherLimit
import com.localkeys.android.data.sync.MergeOtherPrecheck
import com.localkeys.android.data.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A junção de outro cofre, em JVM pura: o teto de tamanho (a soma dos dois arquivos e o resultado), o tamanho em
 * UTF-8 sem alocar cópia, as contagens tiradas do resultado da ponte e o estado do fluxo — pendente do outro arquivo
 * (senha) → pronto (resumo) → aplicado/cancelado, com um trabalho por vez.
 */
class MergeOtherFlowTest {

    @Test
    fun `the two files together cannot pass the cap`() {
        assertTrue("vazio cabe", MergeOtherLimit.precheck(0, 0) is MergeOtherPrecheck.ReadWhole)
        assertTrue("exatamente no teto cabe", MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES - 10, 10) is MergeOtherPrecheck.ReadWhole)
        assertTrue("um byte a mais recusa", MergeOtherLimit.precheck(MergeOtherLimit.MAX_BYTES - 10, 11) is MergeOtherPrecheck.TooBig)
        assertTrue("um arquivo grande sozinho já recusa", MergeOtherLimit.precheck(0, MergeOtherLimit.MAX_BYTES + 1) is MergeOtherPrecheck.TooBig)
    }

    @Test
    fun `the merged json cannot pass the cap`() {
        assertFalse(MergeOtherLimit.resultOverLimit(MergeOtherLimit.MAX_BYTES))
        assertTrue(MergeOtherLimit.resultOverLimit(MergeOtherLimit.MAX_BYTES + 1))
    }

    @Test
    fun `the utf8 size matches the encoded bytes without allocating the copy`() {
        // ASCII, acento (2 bytes), símbolos de 3 bytes e um par substituto (emoji, 4 bytes).
        assertEquals(3L, MergeOtherLimit.utf8Size("abc"))
        assertEquals("acentuação".toByteArray().size.toLong(), MergeOtherLimit.utf8Size("acentuação"))
        assertEquals("€→".toByteArray().size.toLong(), MergeOtherLimit.utf8Size("€→"))
        assertEquals("🔑".toByteArray().size.toLong(), MergeOtherLimit.utf8Size("🔑"))
        assertEquals("a🔑é".toByteArray().size.toLong(), MergeOtherLimit.utf8Size("a🔑é"))
        assertEquals(0L, MergeOtherLimit.utf8Size(""))
    }

    @Test
    fun `the counts come from the bridge result`() {
        val result = MergeOtherResult(
            merged = Vault.empty(),
            added = 2,
            collisions = 1,
            alreadyPresent = 3,
            contentAlreadyPresent = 4,
            foldersAdded = 5,
            foldersMerged = 6,
        )
        assertEquals(
            MergeOtherCounts(added = 2, collisions = 1, alreadyPresent = 3, contentAlreadyPresent = 4, foldersAdded = 5, foldersMerged = 6),
            MergeOtherCounts.of(result),
        )
    }

    @Test
    fun `one job at a time`() {
        val flow = MergeOtherFlow()
        assertTrue(flow.begin())
        assertTrue("o passo está rodando", flow.working)
        assertFalse("dois toques não começam dois trabalhos", flow.begin())
        assertNull("nada aparece antes da resposta", flow.stage)
    }

    @Test
    fun `the flow asks for the password and retries with the error`() {
        val flow = MergeOtherFlow()
        flow.begin()
        flow.askPassword("outro.tkeys")

        val asking = flow.stage as? MergeOtherStage.NeedsPassword
        assertNotNull(asking)
        assertEquals("outro.tkeys", asking!!.name)
        assertNull(asking.error)
        assertFalse(flow.working)

        assertTrue(flow.startPassword())
        assertFalse("o botão trava enquanto o Argon2id roda", flow.startPassword())
        flow.passwordRejected("wrong password or corrupted/tampered file")
        assertEquals("wrong password or corrupted/tampered file", (flow.stage as MergeOtherStage.NeedsPassword).error)

        // Tentar de novo limpa o erro do diálogo.
        assertTrue(flow.startPassword())
        assertNull((flow.stage as MergeOtherStage.NeedsPassword).error)
    }

    @Test
    fun `the ready summary keeps the name and the counts until it is applied or cancelled`() {
        val flow = MergeOtherFlow()
        flow.begin()
        flow.askPassword("outro.tkeys")
        flow.startPassword()
        flow.ready("outro.tkeys", MergeOtherCounts(added = 1, foldersMerged = 1), nothingChanges = false)

        val ready = flow.stage as? MergeOtherStage.Ready
        assertNotNull(ready)
        assertEquals("outro.tkeys", ready!!.name)
        assertEquals(1, ready.counts.added)
        assertFalse(ready.nothingChanges)
        assertFalse(flow.working)

        assertTrue(flow.startApply())
        assertFalse("confirmar duas vezes não grava duas vezes", flow.startApply())
        flow.close()
        assertNull(flow.stage)
        assertFalse(flow.working)
        // Depois de fechar, a ação do menu começa de novo.
        assertTrue(flow.begin())
    }

    @Test
    fun `cancelling the summary leaves nothing pending`() {
        val flow = MergeOtherFlow()
        flow.begin()
        flow.askPassword("outro.tkeys")
        flow.startPassword()
        flow.ready("outro.tkeys", MergeOtherCounts(), nothingChanges = true)

        flow.close()
        assertNull(flow.stage)
        assertFalse(flow.working)
    }

    @Test
    fun `the opening step cannot apply without a summary`() {
        val flow = MergeOtherFlow()
        flow.begin()
        flow.askPassword("outro.tkeys")
        assertFalse("sem senha pendente não trava nada", flow.startApply())
        assertTrue(flow.startPassword())
        assertFalse("sem resumo calculado não há o que aplicar", flow.startApply())
    }
}
