package com.localkeys.android.data.sync

import com.localkeys.android.data.crypto.TkeysError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Só a senha errada mantém o diálogo da senha do outro cofre (unidade 7): os outros erros de abertura fecham a
 * junção com o texto traduzido, senão o usuário fica preso pedindo a senha de um arquivo que não é um cofre.
 */
class MergeOtherPasswordPolicyTest {

    @Test
    fun `a wrong password keeps the dialog`() {
        assertTrue(isWrongPassword(TkeysError.Decrypt))
    }

    @Test
    fun `the other open errors close with the translated error`() {
        assertFalse(isWrongPassword(TkeysError.BadFormat))
        assertFalse(isWrongPassword(TkeysError.UnsupportedVersion(2)))
        assertFalse(isWrongPassword(TkeysError.Corrupted))
        assertFalse(isWrongPassword(TkeysError.Kdf))
        assertFalse(isWrongPassword(TkeysError.CannotMerge("x")))
    }
}
