package com.localkeys.android.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A confirmação do resumo da junção (unidade 7): se o cofre mudou desde o resumo, a junção é refeita e o resumo
 * REAPARECE com as contagens novas — nada é gravado sem o usuário ver. Sem mudança, grava o plano calculado.
 */
class MergeConfirmActionTest {

    @Test
    fun `a changed vault presents the summary again`() {
        assertEquals(MergeConfirmAction.PresentAgain, confirmAction(stale = true))
    }

    @Test
    fun `an unchanged vault writes the calculated plan`() {
        assertEquals(MergeConfirmAction.WritePlan, confirmAction(stale = false))
    }
}
