package com.localkeys.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256Test {

    /** SHA-256 de "abc" (FIPS 180-2, apêndice B.1). */
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun le_o_digest_do_github() {
        assertEquals(abc, Sha256.fromGithubDigest("sha256:$abc"))
        assertEquals(abc, Sha256.fromGithubDigest("SHA256:" + abc.uppercase()))
        assertNull(Sha256.fromGithubDigest(null))
        assertNull(Sha256.fromGithubDigest(""))
        assertNull(Sha256.fromGithubDigest("sha512:$abc"))
        assertNull(Sha256.fromGithubDigest("sha256:1234"))
        assertNull(Sha256.fromGithubDigest("sha256:" + "z".repeat(64)))
        assertNull(Sha256.fromGithubDigest(abc))
    }

    @Test
    fun calcula_e_confere() {
        assertEquals(abc, Sha256.of("abc".byteInputStream()))
        assertTrue(Sha256.matches(abc, abc))
        assertFalse(Sha256.matches(abc, "0".repeat(64)))
        // Sem digest publicado não há o que conferir (o Android ainda exige a mesma assinatura).
        assertTrue(Sha256.matches(abc, null))
    }
}
