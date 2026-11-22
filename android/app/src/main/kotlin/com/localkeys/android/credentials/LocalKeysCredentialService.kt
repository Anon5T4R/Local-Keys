package com.localkeys.android.credentials

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.localkeys.android.R
import com.localkeys.android.data.vault.VaultAutofillCache

/**
 * O Local Keys como provedor do Credential Manager (Android 14+): senhas e passkeys.
 *
 * O serviço só monta as listas; quem entrega a credencial, cria a passkey ou destranca o cofre é a
 * [CredentialActivity], aberta pelo PendingIntent da entrada que o usuário tocar. Com o cofre trancado, a
 * resposta é só "Desbloquear o Local Keys" (o Android pede isso: sem cofre aberto não há o que listar).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class LocalKeysCredentialService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        try {
            val vault = VaultAutofillCache.unlocked
            if (vault == null) {
                val unlock = CredentialActivity.pendingIntent(this, CredentialActivity.ACTION_UNLOCK_GET, REQ_UNLOCK)
                callback.onResult(
                    BeginGetCredentialResponse.Builder()
                        .addAuthenticationAction(AuthenticationAction(getString(R.string.cred_unlock), unlock))
                        .build(),
                )
                return
            }
            callback.onResult(CredentialEntries.build(this, vault, request))
        } catch (e: Exception) {
            callback.onError(GetCredentialUnknownException(e.message))
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val action = when (request) {
            is BeginCreatePublicKeyCredentialRequest -> CredentialActivity.ACTION_CREATE_PASSKEY
            is BeginCreatePasswordCredentialRequest -> CredentialActivity.ACTION_CREATE_PASSWORD
            else -> {
                callback.onError(CreateCredentialUnknownException())
                return
            }
        }
        // A criação passa pela activity mesmo com o cofre trancado: ela destranca antes de gravar.
        val intent = CredentialActivity.pendingIntent(this, action, REQ_CREATE + action.hashCode())
        val vault = VaultAutofillCache.unlocked
        val entry = CreateEntry.Builder(getString(R.string.cred_create_account), intent)
            .setDescription(getString(R.string.cred_create_description))
            .apply {
                if (vault != null) {
                    setPasswordCredentialCount(vault.items.count { !it.login?.password.isNullOrEmpty() })
                    setPublicKeyCredentialCount(vault.items.sumOf { it.livePasskeys.size })
                }
            }
            .build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // O Local Keys não guarda sessão de login de ninguém: não há o que limpar.
        callback.onResult(null)
    }

    companion object {
        const val REQ_UNLOCK = 7000
        const val REQ_CREATE = 7100
    }
}

/** Monta as entradas de senha e de passkey para um pedido, com o cofre aberto. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object CredentialEntries {

    fun build(context: android.content.Context, vault: com.localkeys.android.data.vault.Vault, request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val builder = BeginGetCredentialResponse.Builder()
        val caller = request.callingAppInfo?.let { CallerOrigin.of(context, it) }
        var code = 7200
        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is androidx.credentials.provider.BeginGetPublicKeyCredentialOption -> {
                    val req = runCatching { com.localkeys.android.data.passkey.WebAuthn.parseAssertion(option.requestJson) }.getOrNull() ?: continue
                    // Navegador privilegiado: o site do pedido tem de ser o rpId (ou subdomínio dele).
                    if (caller?.browserDomain != null && !sameSite(caller.browserDomain, req.rpId)) continue
                    for (key in com.localkeys.android.data.passkey.CredentialMatch.passkeys(vault, req.rpId, req.allowedIds)) {
                        val pi = CredentialActivity.pendingIntent(
                            context,
                            CredentialActivity.ACTION_GET_PASSKEY,
                            code++,
                            Intent().putExtra(CredentialActivity.EXTRA_ITEM_ID, key.item.id)
                                .putExtra(CredentialActivity.EXTRA_CREDENTIAL_ID, key.passkey.credentialId),
                        )
                        builder.addCredentialEntry(
                            androidx.credentials.provider.PublicKeyCredentialEntry.Builder(
                                context,
                                key.passkey.userName.ifBlank { key.item.name },
                                pi,
                                option,
                            ).setDisplayName(key.passkey.userDisplayName.ifBlank { key.item.name }).build(),
                        )
                    }
                }
                is androidx.credentials.provider.BeginGetPasswordOption -> {
                    val items = com.localkeys.android.data.passkey.CredentialMatch.passwords(
                        vault,
                        caller?.browserDomain,
                        caller?.packageName?.takeIf { caller.browserDomain == null },
                    )
                    for (item in items) {
                        val pi = CredentialActivity.pendingIntent(
                            context,
                            CredentialActivity.ACTION_GET_PASSWORD,
                            code++,
                            Intent().putExtra(CredentialActivity.EXTRA_ITEM_ID, item.id),
                        )
                        builder.addCredentialEntry(
                            androidx.credentials.provider.PasswordCredentialEntry.Builder(
                                context,
                                item.login?.username.orEmpty().ifBlank { item.name },
                                pi,
                                option,
                            ).setDisplayName(item.name).build(),
                        )
                    }
                }
                else -> Unit
            }
        }
        return builder.build()
    }

    /** O site do navegador é o rpId ou um subdomínio dele ("login.example.com" pode usar "example.com"). */
    fun sameSite(browserDomain: String, rpId: String): Boolean {
        val d = browserDomain.lowercase()
        val r = rpId.lowercase().removePrefix("www.")
        return d == r || d.endsWith(".$r")
    }
}
