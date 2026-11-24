package com.localkeys.android.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Toast
import com.localkeys.android.R
import com.localkeys.android.data.totp.Totp
import com.localkeys.android.data.vault.AutofillUsage
import com.localkeys.android.data.vault.VaultAutofillCache
import com.localkeys.android.ui.copyToClipboard

/**
 * Tela invisível que o sistema abre quando o usuário escolhe uma entrada na lista do autofill. Monta o
 * preenchimento na hora (com o código TOTP **atual**, não o de quando a lista apareceu), devolve ao
 * sistema e, se o login tem TOTP e a opção estiver ligada, copia o código para a área de transferência
 * (marcado como sensível). É também onde o último uso do item é registrado (a ordem da próxima lista).
 *
 * Nada de segredo viaja no Intent: só o id do item e os ids dos campos; os valores vêm do cofre aberto
 * ([VaultAutofillCache]). Cofre trancado no meio do caminho = nada é preenchido.
 */
class AutofillTotpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = buildResult()
        if (result == null) {
            setResult(RESULT_CANCELED)
        } else {
            AutofillUsage.record(applicationContext, intent.getStringExtra(EXTRA_ITEM).orEmpty())
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result.first))
            if (result.second != null && intent.getBooleanExtra(EXTRA_COPY, false)) {
                copyToClipboard(this, getString(R.string.autofill_totp_label), result.second!!, sensitive = true)
                Toast.makeText(applicationContext, R.string.autofill_totp_copied, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun buildResult(): Pair<android.service.autofill.Dataset, String?>? {
        val itemId = intent.getStringExtra(EXTRA_ITEM) ?: return null
        val item = VaultAutofillCache.unlocked?.items?.firstOrNull { it.id == itemId && it.deletedAt == null } ?: return null
        val login = item.login ?: return null
        val code = Totp.atOrNull(login.totp, System.currentTimeMillis() / 1000)?.code
        val fields = AutofillDatasets.Fields(
            usernameId = autofillId(EXTRA_USERNAME_ID),
            passwordId = autofillId(EXTRA_PASSWORD_ID),
            otpId = autofillId(EXTRA_OTP_ID),
        )
        val dataset = AutofillDatasets.filled(this, item, fields, code) ?: return null
        return dataset to code
    }

    private fun autofillId(key: String): AutofillId? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    companion object {
        const val EXTRA_ITEM = "item"
        const val EXTRA_USERNAME_ID = "username_id"
        const val EXTRA_PASSWORD_ID = "password_id"
        const val EXTRA_OTP_ID = "otp_id"
        const val EXTRA_COPY = "copy_totp"
    }
}
