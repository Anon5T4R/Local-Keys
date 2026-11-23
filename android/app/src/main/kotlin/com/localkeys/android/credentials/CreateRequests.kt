package com.localkeys.android.credentials

import android.os.Bundle
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCustomCredentialRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest

/**
 * Os pedidos de criação chegam tipados só quando quem pediu montou o pacote do jeito do androidx. Quem fala
 * direto com o Credential Manager do sistema pode mandar o pacote sem o "subtipo", e o androidx o entrega como
 * pedido *custom* do tipo passkey/senha. Sem tratar esse caso o Local Keys recusava a criação e o Android
 * passava para o próximo provedor (o do Google). Aqui os dois formatos viram a mesma coisa.
 */
object CreateRequests {
    private const val KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    private const val KEY_CLIENT_DATA_HASH = "androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH"
    private const val TYPE_PASSWORD_FRAMEWORK = "android.credentials.TYPE_PASSWORD_CREDENTIAL"

    enum class Kind { PASSKEY, PASSWORD }

    /** O que um pedido de criação de passkey traz: o JSON do WebAuthn e, de navegador, o hash do clientData. */
    class Passkey(val requestJson: String, val clientDataHash: ByteArray?)

    /** Que tipo de criação é (null = nada que o Local Keys guarde). */
    fun kindOf(request: BeginCreateCredentialRequest): Kind? = when (request) {
        is BeginCreatePublicKeyCredentialRequest -> Kind.PASSKEY
        is BeginCreatePasswordCredentialRequest -> Kind.PASSWORD
        // Custom (o pacote sem subtipo do Chrome, por exemplo): vale o tipo declarado.
        else -> kindOfType(request.type)
    }

    fun kindOfType(type: String): Kind? = when (type) {
        PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL -> Kind.PASSKEY
        PasswordCredential.TYPE_PASSWORD_CREDENTIAL, TYPE_PASSWORD_FRAMEWORK -> Kind.PASSWORD
        else -> null
    }

    /** O pedido de passkey, tipado ou custom. Null = não é passkey ou veio sem o JSON. */
    fun passkey(request: CreateCredentialRequest): Passkey? = when (request) {
        is CreatePublicKeyCredentialRequest -> Passkey(request.requestJson, request.clientDataHash)
        is CreateCustomCredentialRequest -> if (kindOfType(request.type) == Kind.PASSKEY) {
            fromBundles(request.credentialData, request.candidateQueryData)
        } else {
            null
        }
        else -> null
    }

    /** Usuário e senha de um pedido de senha, tipado ou custom. */
    fun password(request: CreateCredentialRequest): Pair<String, String>? = when (request) {
        is CreatePasswordRequest -> request.id to request.password
        is CreateCustomCredentialRequest -> if (kindOfType(request.type) == Kind.PASSWORD) {
            val id = request.credentialData.getString("androidx.credentials.BUNDLE_KEY_ID")
            val pw = request.credentialData.getString("androidx.credentials.BUNDLE_KEY_PASSWORD")
            if (id != null && pw != null) id to pw else null
        } else {
            null
        }
        else -> null
    }

    private fun fromBundles(vararg bundles: Bundle): Passkey? {
        val json = bundles.firstNotNullOfOrNull { it.getString(KEY_REQUEST_JSON) } ?: return null
        val hash = bundles.firstNotNullOfOrNull { it.getByteArray(KEY_CLIENT_DATA_HASH) }
        return Passkey(json, hash)
    }
}
