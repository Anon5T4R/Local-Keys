package com.localkeys.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.localkeys.android.MainActivity
import com.localkeys.android.R
import com.localkeys.android.data.totp.Totp
import com.localkeys.android.data.vault.Item

/** Monta as entradas (datasets) do autofill: login, senha e o código TOTP. */
internal object AutofillDatasets {

    /** Os campos da tela que o Local Keys sabe preencher (null = a tela não tem). */
    data class Fields(val usernameId: AutofillId?, val passwordId: AutofillId?, val otpId: AutofillId?) {
        val isEmpty: Boolean get() = usernameId == null && passwordId == null && otpId == null
        val hasLogin: Boolean get() = usernameId != null || passwordId != null
    }

    /** A linha da lista: o nome do item e, embaixo, o usuário (ou "Código TOTP" quando só o código entra). */
    fun presentation(context: Context, item: Item, otpOnly: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, item.name.ifBlank { context.getString(R.string.autofill_default_name) })
            val subtitle = if (otpOnly) context.getString(R.string.autofill_totp_label) else item.login?.username.orEmpty()
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    /**
     * O dataset com os valores de verdade. [code] = TOTP atual (null se o item não tem). Sem nada a preencher
     * nesta tela, devolve null.
     */
    @Suppress("DEPRECATION") // Dataset.Builder(RemoteViews)/setValue: a forma que funciona da API 28 à 36.
    fun filled(context: Context, item: Item, fields: Fields, code: String?): Dataset? {
        val login = item.login ?: return null
        val builder = Dataset.Builder(presentation(context, item, otpOnly = !fields.hasLogin))
        var any = false
        fields.usernameId?.let { builder.setValue(it, AutofillValue.forText(login.username)); any = true }
        fields.passwordId?.let { builder.setValue(it, AutofillValue.forText(login.password)); any = true }
        if (code != null) fields.otpId?.let { builder.setValue(it, AutofillValue.forText(code)); any = true }
        return if (any) builder.build() else null
    }

    /**
     * A entrada de qualquer login escolhido: os valores só são montados quando o usuário a escolhe, pela
     * [AutofillTotpActivity] (que registra o último uso; com TOTP, código fresco e, se [copy], cópia do código).
     * [requestCode] distingue as entradas
     * da mesma lista (senão o sistema reaproveitaria o mesmo PendingIntent para todas).
     */
    @Suppress("DEPRECATION")
    fun deferred(context: Context, item: Item, fields: Fields, copy: Boolean, requestCode: Int): Dataset {
        val intent = Intent(context, AutofillTotpActivity::class.java)
            .putExtra(AutofillTotpActivity.EXTRA_ITEM, item.id)
            .putExtra(AutofillTotpActivity.EXTRA_COPY, copy)
        fields.usernameId?.let { intent.putExtra(AutofillTotpActivity.EXTRA_USERNAME_ID, it) }
        fields.passwordId?.let { intent.putExtra(AutofillTotpActivity.EXTRA_PASSWORD_ID, it) }
        fields.otpId?.let { intent.putExtra(AutofillTotpActivity.EXTRA_OTP_ID, it) }
        val sender = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val builder = Dataset.Builder(presentation(context, item, otpOnly = !fields.hasLogin))
        listOfNotNull(fields.usernameId, fields.passwordId, fields.otpId).forEach { builder.setValue(it, null) }
        return builder.setAuthentication(sender).build()
    }

    /** O item tem um TOTP que gera código? */
    fun hasTotp(item: Item): Boolean =
        item.login?.totp?.let { Totp.atOrNull(it, System.currentTimeMillis() / 1000) } != null

    /**
     * A última linha da lista quando há mais logins do que cabem: "+N mais — abrir o Local Keys". Não
     * preenche nada: ao tocar, abre o app (onde a lista inteira do site está).
     */
    @Suppress("DEPRECATION")
    fun more(context: Context, fields: Fields, hidden: Int, requestCode: Int): Dataset {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sender = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val view = RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, context.resources.getQuantityString(R.plurals.autofill_more, hidden, hidden))
            setTextViewText(R.id.autofill_subtitle, context.getString(R.string.autofill_more_open))
        }
        val builder = Dataset.Builder(view)
        listOfNotNull(fields.usernameId, fields.passwordId, fields.otpId).forEach { builder.setValue(it, null) }
        return builder.setAuthentication(sender).build()
    }
}
