package com.localkeys.android.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.text.InputType
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.localkeys.android.MainActivity
import com.localkeys.android.R
import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.autofill.FieldClassifier
import com.localkeys.android.data.autofill.FieldKind
import com.localkeys.android.data.autofill.FieldSignals
import com.localkeys.android.data.autofill.FillPlan
import com.localkeys.android.data.vault.AutofillPrefs
import com.localkeys.android.data.vault.AutofillUsage
import com.localkeys.android.data.vault.AutofillSaveBridge
import com.localkeys.android.data.vault.VaultAutofillCache

/**
 * Autofill nativo do Android (API do SO, zero Play Services) para login, senha e o código TOTP (campo de
 * código de uso único: o código atual do login casado; ver [FieldClassifier] e [FillPlan]). Escolher qualquer
 * entrada passa pela [AutofillTotpActivity], que registra o último uso, monta o código TOTP na hora (quando o
 * login tem) e, se ligado, o copia.
 * Roda no processo do app: enquanto o cofre está destrancado o [VaultAutofillCache]
 * tem o vault em memória e a entrada escolhida é preenchida sem pedir desbloqueio; se estiver trancado,
 * o picker oferece "Desbloquear o LocalKeys" (abre o app).
 *
 * Preenchimento e salvamento (SaveRequest) de credenciais novas — v0.2.0 só
 * preenchia; desde a v0.3.0 também grava logins novos quando o cofre está aberto.
 */
class LocalKeysAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)

        val auth = findAuthFields(structure)
        val fields = AutofillDatasets.Fields(auth.usernameId, auth.passwordId, auth.otpId)

        // Tela com senha: o Android oferece "Salvar no Local Keys?" depois do envio (casando ou não, trancado ou
        // não; com o cofre trancado o onSaveRequest só avisa para destrancar, nada é gravado).
        val saveInfo = saveInfoFor(auth)

        val vault = VaultAutofillCache.unlocked
        if (vault == null) {
            callback.onSuccess(unlockResponse(fields, saveInfo))
            return
        }

        if (fields.isEmpty) {
            callback.onSuccess(null)
            return
        }

        val webDomain = webDomainOf(structure)
        val packageName = structure.activityComponent?.packageName

        // Mesmas regras de casamento para senha e TOTP: só o site ou o app que pediu, nunca outros.
        // Favorito e último usado primeiro; o que passa de MAX_DATASETS vira a linha "+N mais".
        AutofillUsage.prune(this, vault.items.map { it.id })
        val suggestions = AutofillMatcher.suggestionsFor(vault, packageName, webDomain, AutofillUsage.lastUsed(this))
        val items = suggestions.shown
        val response = FillResponse.Builder()
        var count = 0
        items.forEachIndexed { index, item ->
            val plan = FillPlan.of(
                hasLoginField = fields.hasLogin,
                hasOtpField = fields.otpId != null,
                itemHasTotp = AutofillDatasets.hasTotp(item),
                copySetting = AutofillPrefs.copyTotp,
            )
            // Toda entrada passa pela AutofillTotpActivity, que registra o último uso: sem TOTP ([FillPlan.Direct]),
            // só usuário e senha; com TOTP, o código sai fresco e é copiado se o plano manda.
            if (plan == FillPlan.Skip) return@forEachIndexed
            val copyTotp = (plan as? FillPlan.Deferred)?.copyTotp == true
            response.addDataset(AutofillDatasets.deferred(this, item, fields, copyTotp, REQUEST_BASE + index))
            count++
        }
        if (suggestions.hasMore) {
            response.addDataset(AutofillDatasets.more(this, fields, suggestions.hidden, REQUEST_MORE))
            count++
        }
        saveInfo?.let { response.setSaveInfo(it) }
        callback.onSuccess(if (count > 0 || saveInfo != null) response.build() else null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val auth = structure?.let { findAuthFields(it) }
        val username = auth?.usernameValue
        val password = auth?.passwordValue

        if (structure == null || auth == null || password == null) {
            callback.onSuccess()
            return
        }
        // Só grava quando há senha preenchida; sem usuário ainda dá pra salvar
        // (campo de usuário vazio é legítimo em alguns cadastros).
        // Página no navegador: o site (não o pacote do navegador, que casaria com qualquer página). App: o pacote.
        val uris = webDomainOf(structure)?.let { listOf("https://$it") }
            ?: listOfNotNull(structure.activityComponent?.packageName)

        if (!AutofillSaveBridge.save(
                AutofillSaveBridge.LoginSaveRequest(
                    username = username ?: "",
                    password = password,
                    uris = uris,
                )
            )
        ) {
            // Cofre trancado: não há como gravar sem destravar. Um aviso leve
            // orienta abrir o app e salvar manualmente.
            notify(getString(R.string.autofill_save_locked))
        }
        callback.onSuccess()
    }

    /**
     * Feedback leve do autofill. O app não pede a permissão de notificação (Android 13+) ao abrir: sem ela, as
     * notificações ficam desligadas e o aviso sai como toast — curto, mas chega. Com as notificações ligadas
     * (Android 12 ou antes, ou o usuário ligou nas configurações do sistema), vira notificação de baixa
     * prioridade que abre o app.
     */
    private fun notify(text: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.autofill_notify_channel),
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_localkeys)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, notification) }
    }

    companion object {
        private const val NOTIF_CHANNEL = "autofill_save"
        private const val NOTIF_ID = 1001

        /** Base dos requestCodes dos PendingIntents das entradas da lista (um por entrada). */
        private const val REQUEST_BASE = 100

        /** requestCode da linha "+N mais" (fora da faixa das entradas da lista). */
        private const val REQUEST_MORE = 1000
    }

    /**
     * Cofre trancado: devolve uma resposta de "autenticação" — o picker mostra
     * o atalho e, ao tocar, abre o app para o usuário destrancar. Depois de
     * voltar ao campo, um novo onFillRequest encontra o vault aberto.
     */
    private fun unlockResponse(fields: AutofillDatasets.Fields, saveInfo: SaveInfo?): FillResponse? {
        val ids = listOfNotNull(fields.usernameId, fields.passwordId, fields.otpId).toTypedArray()
        if (ids.isEmpty()) return null
        val unlockSender = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val view = RemoteViews(packageName, R.layout.autofill_unlock)
        return FillResponse.Builder()
            .setAuthentication(ids, unlockSender, view)
            .apply { saveInfo?.let { setSaveInfo(it) } }
            .build()
    }

    /** "Salvar no Local Keys?": obrigatório o campo de senha, opcional o de usuário. Sem senha na tela, nada. */
    private fun saveInfoFor(auth: AuthFields): SaveInfo? {
        val password = auth.passwordId ?: return null
        val builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(password))
        auth.usernameId?.let { builder.setOptionalIds(arrayOf(it)) }
        return builder.build()
    }

    /**
     * O site da página (navegador): o primeiro `webDomain` da árvore. No Chrome ele fica no nó da página, não na
     * raiz da janela. Null = app nativo (casa pelo pacote).
     */
    private fun webDomainOf(structure: AssistStructure): String? {
        fun find(node: AssistStructure.ViewNode): String? {
            node.webDomain?.takeIf { it.isNotBlank() }?.let { return it }
            for (i in 0 until node.childCount) find(node.getChildAt(i))?.let { return it }
            return null
        }
        for (w in 0 until structure.windowNodeCount) find(structure.getWindowNodeAt(w).rootViewNode)?.let { return it }
        return null
    }

    /** Campos encontrados (ids para o fill + valores p/ o save). */
    private class AuthFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val otpId: AutofillId?,
        val usernameValue: String?,
        val passwordValue: String?,
    )

    /** Procura os campos de usuário, senha e código de uso único na árvore da tela assistida. */
    private fun findAuthFields(structure: AssistStructure): AuthFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var otpId: AutofillId? = null
        var usernameValue: String? = null
        var passwordValue: String? = null

        fun textOf(node: AssistStructure.ViewNode): String? {
            val value = node.autofillValue
            return if (value?.isText == true) value.textValue.toString() else null
        }

        // Último campo de texto comum antes da senha: o usuário, quando o app não diz qual é (o caso comum).
        var lastText: AssistStructure.ViewNode? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (usernameId != null && passwordId != null && otpId != null) return
            val id = node.autofillId
            if (id != null) {
                when (FieldClassifier.classify(signalsOf(node))) {
                    FieldKind.PASSWORD -> if (passwordId == null) {
                        passwordId = id
                        passwordValue = textOf(node)
                        val guess = lastText
                        if (usernameId == null && guess != null) {
                            usernameId = guess.autofillId
                            usernameValue = textOf(guess)
                        }
                    }
                    FieldKind.USERNAME -> if (usernameId == null) {
                        usernameId = id
                        usernameValue = textOf(node)
                    }
                    FieldKind.OTP -> if (otpId == null) otpId = id
                    FieldKind.NONE -> if (passwordId == null && isPlainTextInput(node)) lastText = node
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }

        for (w in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(w).rootViewNode)
        }
        return AuthFields(usernameId, passwordId, otpId, usernameValue, passwordValue)
    }

    /**
     * As pistas de um campo para o [FieldClassifier]. As palavras (id, dica, atributos HTML) só contam em campo
     * de texto editável: um rótulo "Digite o código" ao lado não é campo.
     */
    private fun signalsOf(node: AssistStructure.ViewNode): FieldSignals {
        val html = node.htmlInfo
        val attrs = html?.attributes.orEmpty().associate { (it.first ?: "").lowercase() to (it.second ?: "") }
        val editable = node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            (html == null || html.tag.equals("input", ignoreCase = true)) &&
            attrs["type"]?.lowercase() !in setOf("hidden", "submit", "button", "checkbox", "radio")
        val texts = if (editable) {
            listOfNotNull(
                node.idEntry,
                node.hint,
                node.contentDescription?.toString(),
                attrs["name"],
                attrs["id"],
                attrs["placeholder"],
                attrs["aria-label"],
                attrs["label"],
            )
        } else {
            emptyList()
        }
        return FieldSignals(
            autofillHints = node.autofillHints.orEmpty().toList(),
            htmlAutocomplete = attrs["autocomplete"] ?: (if (attrs["type"].equals("email", ignoreCase = true)) "email" else null),
            texts = texts,
            isPasswordInput = editable && (isPasswordType(node.inputType) || attrs["type"].equals("password", ignoreCase = true)),
        )
    }

    /** Campo de texto editável e visível, de uma linha (candidato a usuário). */
    private fun isPlainTextInput(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillType != View.AUTOFILL_TYPE_TEXT || node.visibility != View.VISIBLE) return false
        val html = node.htmlInfo
        if (html != null) {
            if (!html.tag.equals("input", ignoreCase = true)) return false
            val type = html.attributes.orEmpty().firstOrNull { it.first.equals("type", ignoreCase = true) }?.second?.lowercase()
            return type == null || type in setOf("text", "email", "tel")
        }
        val cls = node.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_TEXT || cls == InputType.TYPE_CLASS_PHONE || (node.inputType == 0 && node.className?.contains("EditText") == true)
    }

    /** inputType de texto com variação de senha (o "numberPassword" de PIN não conta). */
    private fun isPasswordType(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
}
