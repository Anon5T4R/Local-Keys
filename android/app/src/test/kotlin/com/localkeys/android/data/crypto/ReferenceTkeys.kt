package com.localkeys.android.data.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * Implementação de REFERÊNCIA do `.tkeys` em Kotlin puro (libsodium via lazysodium-java): foi o código do app até o
 * núcleo Rust (UniFFI) assumir. Mora só nos testes, como um segundo avaliador independente: os testes de
 * `CrossCheckTest` provam que um cofre gravado pelo Rust abre aqui e o contrário, e `TkeysCryptoTest` confere o
 * cabeçalho. Não é código de produção — não use fora de teste.
 *
 * Layout (tudo little-endian): MAGIC "TKEYS\0"(6) | versão(1) | kdf_id(1) | m_cost u32 | t_cost u32 | p_cost u32 |
 * salt(16) | nonce(24) = 60 bytes de header (em claro, é o AAD), depois ciphertext + tag de 16.
 */
object RefFormat {
    const val MAGIC_LEN = 6
    val MAGIC = byteArrayOf('T'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 'Y'.code.toByte(), 'S'.code.toByte(), 0)
    const val FORMAT_VERSION = 1
    const val KDF_ARGON2ID = 1
    const val SALT_LEN = 16
    const val NONCE_LEN = 24
    const val KEY_LEN = 32
    const val HEADER_LEN = 6 + 1 + 1 + 4 + 4 + 4 + SALT_LEN + NONCE_LEN // = 60
}

/** Header parseado de um `.tkeys` (a referência independente do `read_kdf_params` do núcleo). */
data class RefHeader(
    val version: Int,
    val kdfId: Int,
    val params: KdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
)

fun refBuildHeader(params: KdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
    require(salt.size == RefFormat.SALT_LEN) { "salt deve ter ${RefFormat.SALT_LEN} bytes" }
    require(nonce.size == RefFormat.NONCE_LEN) { "nonce deve ter ${RefFormat.NONCE_LEN} bytes" }
    val out = ByteArray(RefFormat.HEADER_LEN)
    var pos = 0
    RefFormat.MAGIC.copyInto(out, pos); pos += RefFormat.MAGIC_LEN
    out[pos++] = RefFormat.FORMAT_VERSION.toByte()
    out[pos++] = RefFormat.KDF_ARGON2ID.toByte()
    writeLeU32(out, pos, params.mCostKib); pos += 4
    writeLeU32(out, pos, params.tCost); pos += 4
    writeLeU32(out, pos, params.pCost); pos += 4
    salt.copyInto(out, pos); pos += RefFormat.SALT_LEN
    nonce.copyInto(out, pos)
    return out
}

fun refParseHeader(file: ByteArray): RefHeader {
    require(file.size >= RefFormat.HEADER_LEN) { "arquivo menor que o header" }
    require(RefFormat.MAGIC.contentEquals(file.copyOfRange(0, RefFormat.MAGIC_LEN))) { "magic errado" }
    return RefHeader(
        version = file[6].toInt() and 0xFF,
        kdfId = file[7].toInt() and 0xFF,
        params = KdfParams(mCostKib = readLeU32(file, 8), tCost = readLeU32(file, 12), pCost = readLeU32(file, 16)),
        salt = file.copyOfRange(20, 36),
        nonce = file.copyOfRange(36, 60),
    )
}

private fun writeLeU32(out: ByteArray, offset: Int, value: Long) {
    val v = value.toInt()
    out[offset] = v.toByte()
    out[offset + 1] = (v ushr 8).toByte()
    out[offset + 2] = (v ushr 16).toByte()
    out[offset + 3] = (v ushr 24).toByte()
}

private fun readLeU32(file: ByteArray, offset: Int): Long {
    val b0 = file[offset].toInt() and 0xFF
    val b1 = file[offset + 1].toInt() and 0xFF
    val b2 = file[offset + 2].toInt() and 0xFF
    val b3 = file[offset + 3].toInt() and 0xFF
    return b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)
}

/** Argon2id (V0x13) + XChaCha20-Poly1305 com o libsodium, o jeito que o app fazia antes do núcleo Rust. */
class ReferenceTkeysCrypto {
    private val ls = LazySodiumJava(SodiumJava())

    fun randomBytes(n: Int): ByteArray = ls.randomBytesBuf(n)

    fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        val hexKey = ls.cryptoPwHash(
            password,
            RefFormat.KEY_LEN,
            salt,
            params.tCost,
            NativeLong(params.mCostKib * 1024),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        return Hex.decode(hexKey)
    }

    /** `header || ciphertext+tag`, com o nonce dado (para comparar byte a byte com o desktop). */
    fun seal(key: ByteArray, salt: ByteArray, params: KdfParams, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val header = refBuildHeader(params, salt, nonce)
        val cipher = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val cipherLen = longArrayOf(0)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            cipher, cipherLen, plaintext, plaintext.size.toLong(), header, header.size.toLong(), null, nonce, key,
        )
        check(ok) { "cifragem de referência falhou" }
        return header + cipher.copyOf(cipherLen[0].toInt())
    }

    fun sealWithFreshNonce(key: ByteArray, salt: ByteArray, params: KdfParams, plaintext: ByteArray): ByteArray =
        seal(key, salt, params, randomBytes(RefFormat.NONCE_LEN), plaintext)

    /** Decifra com a chave bruta; `null` se a autenticação falhar. */
    fun openWithKey(key: ByteArray, file: ByteArray): ByteArray? {
        val header = refParseHeader(file)
        val ciphertext = file.copyOfRange(RefFormat.HEADER_LEN, file.size)
        val aad = file.copyOfRange(0, RefFormat.HEADER_LEN)
        val plaintext = ByteArray(ciphertext.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val plainLen = longArrayOf(0)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext, plainLen, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), header.nonce, key,
        )
        return if (ok) plaintext else null
    }

    fun openVault(password: String, file: ByteArray): ByteArray? {
        val header = refParseHeader(file)
        return openWithKey(deriveKey(password, header.salt, header.params), file)
    }
}
