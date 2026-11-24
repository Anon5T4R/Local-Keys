package com.localkeys.android.data.crypto

import com.localkeys.android.ffi.FfiException
import com.localkeys.android.ffi.KdfParamsFfi
import com.localkeys.android.ffi.Session
import com.localkeys.android.ffi.createVault as ffiCreateVault
import com.localkeys.android.ffi.openVault as ffiOpenVault
import com.localkeys.android.ffi.openVaultWithKey as ffiOpenVaultWithKey

/**
 * Erros do `.tkeys`, espelhando o `CryptoError` do desktop.
 * `Decrypt` é proposital: senha errada e arquivo adulterado caem no mesmo caso.
 */
sealed class TkeysError : Exception() {
    object BadFormat : TkeysError() {
        override val message: String get() = "arquivo não é um vault .tkeys válido"
    }
    class UnsupportedVersion(val version: Int) : TkeysError() {
        override val message: String get() = "versão de formato .tkeys não suportada: $version"
    }
    object Kdf : TkeysError() {
        override val message: String get() = "falha ao derivar a chave (KDF)"
    }
    object Decrypt : TkeysError() {
        override val message: String get() = "senha incorreta ou arquivo corrompido/adulterado"
    }
    object Corrupted : TkeysError() {
        override val message: String get() = "arquivo corrompido (estrutura inválida)"
    }
}

/**
 * O `.tkeys` do app: formato, Argon2id e XChaCha20-Poly1305 são o MESMO código do desktop (`localkeys-core`, em
 * Rust), chamado por UniFFI (`crates/ffi`). Esta classe só traduz tipos e erros, para as telas e o repositório
 * continuarem como estavam.
 */
class TkeysCrypto {

    /**
     * Abre um `.tkeys` com a **chave bruta** (ex.: recuperada do cofre biométrico do SO). Devolve plaintext + sessão
     * viva (salt/params do arquivo) — o caminho do desbloqueio rápido, sem re-rodar o Argon2.
     */
    fun openVaultWithKey(key: ByteArray, file: ByteArray): OpenedVault {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        val opened = translating { ffiOpenVaultWithKey(key, file) }
        return OpenedVault(opened.plaintext, SessionKey(opened.session))
    }

    /** Decifra um `.tkeys` com a **chave bruta** (sem re-rodar o Argon2). */
    fun openWithKey(key: ByteArray, file: ByteArray): ByteArray = openVaultWithKey(key, file).plaintext

    /**
     * Cria um vault novo: deriva a chave da `password` e cifra o `plaintext` inicial. Devolve o arquivo + a sessão
     * (já fica "destrancado").
     */
    fun createVault(
        password: String,
        plaintext: ByteArray,
        params: KdfParams = TkeysFormat.defaultParams(),
    ): CreatedVault {
        val created = translating { ffiCreateVault(password, plaintext, params.toFfi()) }
        return CreatedVault(created.file, SessionKey(created.session))
    }

    /**
     * Abre um `.tkeys`: valida o header, deriva a chave e decifra. Devolve o plaintext e a sessão para salvar depois.
     * Falha **genérica** em senha errada OU adulteração.
     */
    fun openVault(password: String, file: ByteArray): OpenedVault {
        val opened = translating { ffiOpenVault(password, file) }
        return OpenedVault(opened.plaintext, SessionKey(opened.session))
    }
}

/** Resultado de criar um vault: bytes do arquivo cifrado + sessão viva. */
data class CreatedVault(
    val file: ByteArray,
    val session: SessionKey,
)

/** Resultado de abrir um vault: plaintext decifrado + sessão viva. */
data class OpenedVault(
    val plaintext: ByteArray,
    val session: SessionKey,
)

/**
 * Chave de sessão (a sessão do núcleo Rust): a chave derivada + salt/params do vault aberto. Permite recifrar
 * (salvar) sem re-rodar o Argon2 nem guardar a master password — só sorteia um nonce novo a cada save
 * (exigência do XChaCha20-Poly1305).
 */
class SessionKey internal constructor(private val session: Session) {
    /** Recifra o `plaintext` com a chave da sessão e um nonce novo. */
    fun seal(plaintext: ByteArray): ByteArray = translating { session.seal(plaintext) }

    /** Cópia da chave bruta (32 bytes) — só para o cofre do SO no desbloqueio rápido. */
    fun keyBytes(): ByteArray = session.keyBytes()
}

private fun KdfParams.toFfi() = KdfParamsFfi(mCost = mCostKib.toUInt(), tCost = tCost.toUInt(), pCost = pCost.toUInt())

/** O erro do núcleo vira o [TkeysError] que o resto do app já trata. */
private inline fun <T> translating(block: () -> T): T = try {
    block()
} catch (e: FfiException) {
    throw when (e) {
        is FfiException.BadFormat -> TkeysError.BadFormat
        is FfiException.UnsupportedVersion -> TkeysError.UnsupportedVersion(e.version.toInt())
        is FfiException.Kdf -> TkeysError.Kdf
        is FfiException.Decrypt -> TkeysError.Decrypt
        is FfiException.Corrupted -> TkeysError.Corrupted
        // Nenhum TkeysError existente descreve "item não convertível" e a peça da UI ainda não existe: o erro cai
        // no "estrutura inválida" do item (a variante própria entra quando a tela precisar da mensagem).
        is FfiException.NotConvertible -> TkeysError.Corrupted
    }
}
