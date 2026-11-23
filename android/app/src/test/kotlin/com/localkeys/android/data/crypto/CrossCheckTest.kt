package com.localkeys.android.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cruzamento do núcleo Rust (o que o app usa, por UniFFI) com a implementação de REFERÊNCIA em Kotlin
 * ([ReferenceTkeysCrypto], o código antigo do app, em libsodium): um cofre gravado por um abre no outro, nos dois
 * sentidos, e o fixture gerado pelo Rust do desktop abre e recifra byte a byte igual nos dois caminhos.
 * Se um dia o formato mudasse de um lado só, este teste quebra.
 */
class CrossCheckTest {

    private val rust = TkeysCrypto()
    private val reference = ReferenceTkeysCrypto()

    private val light = KdfParams(mCostKib = 512, tCost = 1, pCost = 1)
    private val plaintext = TkeysCryptoTest.FIXTURE_PLAINTEXT.toByteArray()

    // ── O fixture do desktop, nos dois caminhos ──────────────────────────

    @Test
    fun o_fixture_do_desktop_abre_nos_dois_caminhos() {
        val file = TkeysCryptoTest.fixtureBytes()
        assertArrayEquals(plaintext, rust.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, file).plaintext)
        assertArrayEquals(plaintext, reference.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, file))
    }

    @Test
    fun a_chave_derivada_e_a_mesma_do_desktop_nos_dois_caminhos() {
        val file = TkeysCryptoTest.fixtureBytes()
        val header = refParseHeader(file)
        val viaReference = reference.deriveKey(TkeysCryptoTest.FIXTURE_PASSWORD, header.salt, header.params)
        // A chave que o desktop imprimiu (cargo test no crypto.rs) para este fixture.
        assertEquals("3bae09343f4ede7dc1ea3de521d6d2e035c356c2f04580abcd64f490c35ccc6a", Hex.encode(viaReference).lowercase())
        val viaRust = rust.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, file).session.keyBytes()
        assertArrayEquals(viaReference, viaRust)
    }

    // A recifragem com os MESMOS salt/nonce/chave do desktop sai idêntica ao fixture: prova a cifra e o AAD, não só
    // que os dois lados se abrem. (A referência faz a cifragem; o Rust prova que abre exatamente esses bytes.)
    @Test
    fun a_recifragem_do_fixture_e_identica_a_do_desktop() {
        val file = TkeysCryptoTest.fixtureBytes()
        val header = refParseHeader(file)
        val key = reference.deriveKey(TkeysCryptoTest.FIXTURE_PASSWORD, header.salt, header.params)
        val rebuilt = reference.seal(key, header.salt, header.params, header.nonce, plaintext)
        assertEquals("cifragem de referência != fixture do desktop", file.toList(), rebuilt.toList())
        assertArrayEquals(plaintext, rust.openVaultWithKey(key, rebuilt).plaintext)
    }

    // ── Gravado pelo Rust, aberto pela referência ────────────────────────

    @Test
    fun cofre_criado_pelo_rust_abre_na_referencia() {
        val created = rust.createVault("pw-rust", plaintext, light)
        assertEquals(light, refParseHeader(created.file).params)
        assertArrayEquals(plaintext, reference.openVault("pw-rust", created.file))
    }

    @Test
    fun cofre_regravado_pela_sessao_do_rust_abre_na_referencia() {
        val created = rust.createVault("pw-rust", "antes".toByteArray(), light)
        val resealed = created.session.seal(plaintext)
        val header = refParseHeader(resealed)
        // Mesmo salt e params, nonce novo: o que o app espera de cada gravação.
        assertEquals(refParseHeader(created.file).salt.toList(), header.salt.toList())
        assertArrayEquals(plaintext, reference.openWithKey(created.session.keyBytes(), resealed))
        assertArrayEquals(plaintext, reference.openVault("pw-rust", resealed))
    }

    // ── Gravado pela referência, aberto pelo Rust ────────────────────────

    @Test
    fun cofre_criado_pela_referencia_abre_no_rust() {
        val salt = reference.randomBytes(TkeysFormat.SALT_LEN)
        val key = reference.deriveKey("pw-ref", salt, light)
        val file = reference.sealWithFreshNonce(key, salt, light, plaintext)

        val opened = rust.openVault("pw-ref", file)
        assertArrayEquals(plaintext, opened.plaintext)
        assertArrayEquals(key, opened.session.keyBytes())

        // E a regravação do Rust sobre esse cofre volta a abrir na referência.
        val back = opened.session.seal("de volta".toByteArray())
        assertEquals("de volta", String(reference.openWithKey(key, back)!!))
    }

    @Test
    fun a_chave_bruta_da_referencia_abre_no_rust() {
        val salt = reference.randomBytes(TkeysFormat.SALT_LEN)
        val key = reference.deriveKey("pw-ref", salt, light)
        val file = reference.sealWithFreshNonce(key, salt, light, plaintext)
        assertArrayEquals(plaintext, rust.openWithKey(key, file))
    }
}
