package com.localkeys.android.data.passkey

import com.localkeys.android.data.vault.Passkey
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * O autenticador WebAuthn do Local Keys, sem nada de Android: cria uma passkey (ES256, P-256) e assina um
 * pedido de login. Segue a especificação WebAuthn nível 2 (dados do autenticador, chave COSE, atestado "none").
 *
 * O Android (Credential Manager) entrega o JSON do pedido do site e recebe de volta o JSON da resposta; este
 * objeto monta os dois lados.
 */
object WebAuthn {

    /** AAGUID do Local Keys: zeros, como os autenticadores que não fazem atestado. */
    private val AAGUID = ByteArray(16)

    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04
    private const val FLAG_BE = 0x08
    private const val FLAG_BS = 0x10
    private const val FLAG_AT = 0x40

    private val random = SecureRandom()

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromB64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Flags de uma passkey sincronizável (fica no cofre): presença, verificação e "backup". */
    private fun flags(userVerified: Boolean): Int =
        FLAG_UP or FLAG_BE or FLAG_BS or (if (userVerified) FLAG_UV else 0)

    /** O que o site pediu para criar (só o que o Local Keys usa). */
    data class CreationRequest(
        val rpId: String,
        val rpName: String,
        val userHandle: String,
        val userName: String,
        val userDisplayName: String,
        val challenge: String,
        val supportsEs256: Boolean,
        val excludedIds: Set<String>,
    )

    fun parseCreation(requestJson: String): CreationRequest {
        val o = JSONObject(requestJson)
        val rp = o.getJSONObject("rp")
        val user = o.getJSONObject("user")
        val params = o.optJSONArray("pubKeyCredParams") ?: JSONArray()
        val es256 = (0 until params.length()).any { params.getJSONObject(it).optInt("alg") == Passkey.ES256 }
        val exclude = o.optJSONArray("excludeCredentials") ?: JSONArray()
        return CreationRequest(
            rpId = rp.optString("id", ""),
            rpName = rp.optString("name", ""),
            userHandle = user.getString("id"),
            userName = user.optString("name", ""),
            userDisplayName = user.optString("displayName", ""),
            challenge = o.getString("challenge"),
            supportsEs256 = es256 || params.length() == 0,
            excludedIds = (0 until exclude.length()).map { exclude.getJSONObject(it).optString("id") }.toSet(),
        )
    }

    /** O que o site pediu para entrar. */
    data class AssertionRequest(val rpId: String, val challenge: String, val allowedIds: Set<String>)

    fun parseAssertion(requestJson: String): AssertionRequest {
        val o = JSONObject(requestJson)
        val allow = o.optJSONArray("allowCredentials") ?: JSONArray()
        return AssertionRequest(
            rpId = o.optString("rpId", ""),
            challenge = o.getString("challenge"),
            allowedIds = (0 until allow.length()).map { allow.getJSONObject(it).optString("id") }.toSet(),
        )
    }

    /**
     * O `clientDataJSON` que o autenticador monta quando o sistema não entregou o hash pronto (app nativo
     * chamando o Credential Manager). Com navegador privilegiado, quem monta é o navegador e chega só o hash.
     */
    fun clientDataJson(type: String, challenge: String, origin: String, packageName: String?): ByteArray {
        val o = JSONObject()
        o.put("type", type)
        o.put("challenge", challenge)
        o.put("origin", origin)
        if (packageName != null) o.put("androidPackageName", packageName)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** Uma passkey nova e a resposta de registro para o site. */
    data class Created(val passkey: Passkey, val responseJson: String)

    /**
     * Cria a passkey: par de chaves P-256, id aleatório de 32 bytes e o atestado "none".
     * [clientDataJson] vai na resposta; [clientDataHash], se o sistema mandou, vale no lugar do hash dele.
     */
    fun create(
        request: CreationRequest,
        clientDataJson: ByteArray,
        userVerified: Boolean,
        now: Long,
    ): Created {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val credentialId = ByteArray(32).also { random.nextBytes(it) }
        val publicKey = pair.public as ECPublicKey
        val authData = authenticatorData(request.rpId, flags(userVerified) or FLAG_AT) + attestedCredentialData(credentialId, publicKey)
        val attestation = Cbor.map(
            listOf(
                Cbor.text("fmt") to Cbor.text("none"),
                Cbor.text("attStmt") to Cbor.map(emptyList()),
                Cbor.text("authData") to Cbor.bytes(authData),
            ),
        )
        val passkey = Passkey(
            credentialId = b64url(credentialId),
            rpId = request.rpId,
            userName = request.userName,
            userDisplayName = request.userDisplayName,
            userHandle = request.userHandle,
            key = b64url(pair.private.encoded),
            createdAt = now,
        )
        val response = JSONObject()
            .put("clientDataJSON", b64url(clientDataJson))
            .put("attestationObject", b64url(attestation))
            .put("authenticatorData", b64url(authData))
            .put("transports", JSONArray(listOf("internal", "hybrid")))
            .put("publicKeyAlgorithm", Passkey.ES256)
            .put("publicKey", b64url(publicKey.encoded))
        val json = JSONObject()
            .put("id", passkey.credentialId)
            .put("rawId", passkey.credentialId)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", JSONObject().put("credProps", JSONObject().put("rk", true)))
        return Created(passkey, json.toString())
    }

    /**
     * Assina um pedido de login com a passkey. [clientDataHash] é o SHA-256 do `clientDataJSON`: o do sistema
     * (navegador privilegiado) ou o do [clientDataJson] montado aqui.
     */
    fun assert(
        passkey: Passkey,
        clientDataJson: ByteArray,
        clientDataHash: ByteArray,
        userVerified: Boolean,
    ): String {
        val authData = authenticatorData(passkey.rpId, flags(userVerified))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey(passkey))
        signer.update(authData + clientDataHash)
        val signature = signer.sign()
        val response = JSONObject()
            .put("clientDataJSON", b64url(clientDataJson))
            .put("authenticatorData", b64url(authData))
            .put("signature", b64url(signature))
            .put("userHandle", passkey.userHandle)
        return JSONObject()
            .put("id", passkey.credentialId)
            .put("rawId", passkey.credentialId)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", JSONObject())
            .toString()
    }

    fun privateKey(passkey: Passkey): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(fromB64url(passkey.key)))

    /** rpIdHash (32) | flags (1) | contador (4, sempre 0: passkey sincronizável não conta). */
    fun authenticatorData(rpId: String, flags: Int): ByteArray =
        sha256(rpId.toByteArray(Charsets.UTF_8)) + byteArrayOf(flags.toByte(), 0, 0, 0, 0)

    /** AAGUID (16) | tamanho do id (2) | id | chave pública COSE. */
    private fun attestedCredentialData(credentialId: ByteArray, publicKey: ECPublicKey): ByteArray {
        val length = byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte())
        return AAGUID + length + credentialId + coseKey(publicKey)
    }

    /** Chave EC2 em COSE: {1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x, -3: y}. */
    fun coseKey(publicKey: ECPublicKey): ByteArray = Cbor.map(
        listOf(
            Cbor.int(1) to Cbor.int(2),
            Cbor.int(3) to Cbor.int(Passkey.ES256.toLong()),
            Cbor.int(-1) to Cbor.int(1),
            Cbor.int(-2) to Cbor.bytes(coordinate(publicKey.w.affineX)),
            Cbor.int(-3) to Cbor.bytes(coordinate(publicKey.w.affineY)),
        ),
    )

    /** Coordenada da curva em 32 bytes sem sinal (o BigInteger às vezes traz um zero a mais ou a menos). */
    private fun coordinate(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** O mínimo de CBOR (RFC 8949) que o WebAuthn usa: inteiros, bytes, texto e mapas, na forma canônica. */
    object Cbor {
        private fun head(major: Int, value: Long): ByteArray {
            val m = major shl 5
            return when {
                value < 24 -> byteArrayOf((m or value.toInt()).toByte())
                value < 0x100 -> byteArrayOf((m or 24).toByte(), value.toByte())
                value < 0x10000 -> byteArrayOf((m or 25).toByte(), (value shr 8).toByte(), value.toByte())
                else -> byteArrayOf(
                    (m or 26).toByte(),
                    (value shr 24).toByte(),
                    (value shr 16).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }
        }

        fun int(value: Long): ByteArray = if (value >= 0) head(0, value) else head(1, -1 - value)

        fun int(value: Int): ByteArray = int(value.toLong())

        fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value

        fun text(value: String): ByteArray {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            return head(3, utf8.size.toLong()) + utf8
        }

        fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(head(5, entries.size.toLong()))
            entries.forEach { (k, v) ->
                out.write(k)
                out.write(v)
            }
            return out.toByteArray()
        }
    }
}
