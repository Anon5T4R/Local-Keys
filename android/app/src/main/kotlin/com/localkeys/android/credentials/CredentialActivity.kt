package com.localkeys.android.credentials

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.provider.PendingIntentHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.localkeys.android.R
import com.localkeys.android.data.passkey.WebAuthn
import com.localkeys.android.data.vault.CredentialBridge
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.data.vault.VaultAutofillCache
import com.localkeys.android.ui.UnlockScreen
import com.localkeys.android.ui.VaultViewModel
import com.localkeys.android.ui.launchVaultBiometric
import com.localkeys.android.ui.theme.LocalKeysAppTheme
import com.localkeys.android.ui.verifyUser
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Tela do provedor de credenciais. Faz uma coisa por vez, conforme a ação do PendingIntent:
 * destrancar e devolver a lista, entregar uma senha, entregar uma passkey, ou criar uma senha ou passkey.
 *
 * Se o cofre estiver trancado, mostra antes a mesma tela de desbloqueio do app (senha ou biometria). Usar ou
 * criar passkey pede a confirmação do dono do aparelho (digital, rosto ou bloqueio de tela), a não ser que ele
 * tenha acabado de destrancar o cofre aqui.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialActivity : AppCompatActivity() {

    private val viewModel: VaultViewModel by viewModels()
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val alreadyOpen = VaultAutofillCache.unlocked
        setContent {
            val state by viewModel.state.collectAsState()
            LocalKeysAppTheme(this, state.themeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val vault = VaultAutofillCache.unlocked ?: state.vault
                    if (vault == null) {
                        UnlockScreen(
                            state = state,
                            onPickVault = {},
                            onCreateVault = {},
                            onUnlock = viewModel::unlock,
                            onBiometricUnlock = { launchVaultBiometric(viewModel, com.localkeys.android.ui.BiometricRequest.Unlock) },
                            onErrorShown = viewModel::clearError,
                            // As margens do sistema já entram pelo Scaffold: a tela não as aplica de novo.
                            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        LaunchedEffect(vault) {
                            if (!handled) {
                                handled = true
                                handle(vault, justUnlocked = alreadyOpen == null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handle(vault: Vault, justUnlocked: Boolean) {
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_UNLOCK_GET -> returnEntries(vault)
            ACTION_GET_PASSWORD -> getPassword(vault)
            ACTION_GET_PASSKEY -> withUser(justUnlocked, getString(R.string.cred_verify_use)) { ok ->
                if (ok) getPasskey(vault) else failGet(GetCredentialCancellationException())
            }
            ACTION_CREATE_PASSWORD -> createPassword()
            ACTION_CREATE_PASSKEY -> withUser(justUnlocked, getString(R.string.cred_verify_create)) { ok ->
                if (ok) createPasskey(vault) else failCreate(CreateCredentialCancellationException())
            }
            else -> finish()
        }
    }

    private fun withUser(justUnlocked: Boolean, title: String, then: (Boolean) -> Unit) {
        if (justUnlocked) then(true) else verifyUser(title, then)
    }

    /** Cofre destrancado a partir da lista do sistema: devolve as entradas do pedido original. */
    private fun returnEntries(vault: Vault) {
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: return finish()
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, CredentialEntries.build(this, vault, request))
        setResult(RESULT_OK, result)
        finish()
    }

    private fun getPassword(vault: Vault) {
        val item = vault.items.firstOrNull { it.id == intent.getStringExtra(EXTRA_ITEM_ID) }
        val login = item?.login
        if (login == null || login.password.isEmpty()) return failGet(GetCredentialUnknownException(getString(R.string.cred_not_found)))
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PasswordCredential(login.username, login.password)))
        setResult(RESULT_OK, result)
        finish()
    }

    private fun getPasskey(vault: Vault) {
        try {
            val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
                ?: return failGet(GetCredentialUnknownException())
            val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
                ?: return failGet(GetCredentialUnknownException())
            val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
            val passkey = vault.items.firstOrNull { it.id == intent.getStringExtra(EXTRA_ITEM_ID) }
                ?.livePasskeys?.firstOrNull { it.credentialId == credentialId }
                ?: return failGet(GetCredentialUnknownException(getString(R.string.cred_not_found)))
            val assertion = WebAuthn.parseAssertion(option.requestJson)
            val caller = CallerOrigin.of(this, request.callingAppInfo)
            val (clientData, hash) = clientData("webauthn.get", assertion.challenge, caller, option.clientDataHash)
            val json = WebAuthn.assert(passkey, clientData, hash, userVerified = true)
            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(json)))
            setResult(RESULT_OK, result)
            finish()
        } catch (e: Exception) {
            failGet(GetCredentialUnknownException(e.message))
        }
    }

    private fun createPassword() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            ?: return failCreate(CreateCredentialUnknownException())
        val (userId, secret) = CreateRequests.password(request.callingRequest)
            ?: return failCreate(CreateCredentialUnknownException("unsupported password request: ${request.callingRequest.type}"))
        val caller = CallerOrigin.of(this, request.callingAppInfo)
        val site = caller.browserDomain
        val item = newLogin(
            name = site ?: appLabel(caller.packageName),
            login = Login(userId, secret, listOf(site?.let { "https://$it" } ?: caller.packageName), ""),
        )
        save(item) { ok ->
            if (!ok) return@save failCreate(CreateCredentialUnknownException(getString(R.string.cred_save_failed)))
            val result = Intent()
            PendingIntentHandler.setCreateCredentialResponse(result, CreatePasswordResponse())
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun createPasskey(vault: Vault) {
        try {
            val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                ?: return failCreate(CreateCredentialUnknownException())
            val create = CreateRequests.passkey(request.callingRequest)
                ?: return failCreate(CreateCredentialUnknownException("unsupported passkey request: ${request.callingRequest.type}"))
            val caller = CallerOrigin.of(this, request.callingAppInfo)
            val parsed = WebAuthn.parseCreation(create.requestJson)
            val rpId = parsed.rpId.ifBlank { caller.browserDomain.orEmpty() }
            if (rpId.isBlank()) return failCreate(CreateCredentialUnknownException(getString(R.string.cred_no_site)))
            if (caller.browserDomain != null && !CredentialEntries.sameSite(caller.browserDomain, rpId)) {
                return failCreate(CreatePublicKeyCredentialDomException(NotSupportedError(), getString(R.string.cred_wrong_site)))
            }
            if (!parsed.supportsEs256) {
                return failCreate(CreatePublicKeyCredentialDomException(NotSupportedError(), getString(R.string.cred_alg_unsupported)))
            }
            val existing = vault.items.flatMap { it.livePasskeys }.map { it.credentialId }.toSet()
            if (parsed.excludedIds.any { it in existing }) {
                return failCreate(CreatePublicKeyCredentialDomException(InvalidStateError(), getString(R.string.cred_already_exists)))
            }
            val request2 = parsed.copy(rpId = rpId)
            val (clientData, _) = clientData("webauthn.create", parsed.challenge, caller, create.clientDataHash)
            val created = WebAuthn.create(request2, clientData, userVerified = true, now = System.currentTimeMillis())
            val item = newLogin(
                name = parsed.rpName.ifBlank { rpId },
                login = Login(parsed.userName, "", listOf("https://$rpId"), ""),
            ).copy(passkeys = listOf(created.passkey))
            save(item) { ok ->
                if (!ok) return@save failCreate(CreateCredentialUnknownException(getString(R.string.cred_save_failed)))
                val result = Intent()
                PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(created.responseJson))
                setResult(RESULT_OK, result)
                finish()
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalKeysCred", "criação de passkey falhou", e)
            failCreate(CreateCredentialUnknownException(e.message))
        }
    }

    /**
     * O `clientDataJSON` e o hash dele. Navegador privilegiado mandou o hash pronto (ele montou o JSON com o
     * site): vale o dele, e o JSON da resposta é só um marcador que o navegador troca. App nativo: monta aqui.
     */
    private fun clientData(type: String, challenge: String, caller: CallerOrigin, systemHash: ByteArray?): Pair<ByteArray, ByteArray> {
        if (systemHash != null) return "{}".toByteArray() to systemHash
        val json = WebAuthn.clientDataJson(type, challenge, caller.origin, caller.packageName.takeIf { !caller.isBrowser })
        return json to WebAuthn.sha256(json)
    }

    private fun newLogin(name: String, login: Login): Item {
        val now = System.currentTimeMillis()
        return Item(
            id = UUID.randomUUID().toString(),
            kind = ItemKind.LOGIN,
            name = name,
            favorite = false,
            folderId = null,
            notes = "",
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            login = login,
            card = null,
            identity = null,
            passwordHistory = null,
            customFields = null,
            attachments = null,
        )
    }

    private fun save(item: Item, done: (Boolean) -> Unit) {
        lifecycleScope.launch { done(CredentialBridge.save(listOf(item))) }
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun failGet(e: GetCredentialException) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, e)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun failCreate(e: CreateCredentialException) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, e)
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "com.localkeys.android.credentials.ACTION"
        const val EXTRA_ITEM_ID = "com.localkeys.android.credentials.ITEM_ID"
        const val EXTRA_CREDENTIAL_ID = "com.localkeys.android.credentials.CREDENTIAL_ID"

        const val ACTION_UNLOCK_GET = "unlock-get"
        const val ACTION_GET_PASSWORD = "get-password"
        const val ACTION_GET_PASSKEY = "get-passkey"
        const val ACTION_CREATE_PASSWORD = "create-password"
        const val ACTION_CREATE_PASSKEY = "create-passkey"

        /** PendingIntent mutável (o Android põe o pedido nele) e único por [requestCode]. */
        fun pendingIntent(context: Context, action: String, requestCode: Int, extras: Intent = Intent()): PendingIntent {
            val intent = Intent(context, CredentialActivity::class.java)
                .putExtras(extras)
                .putExtra(EXTRA_ACTION, action)
                .setPackage(context.packageName)
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
