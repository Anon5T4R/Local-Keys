package com.localkeys.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun le_versoes_validas_e_recusa_o_resto() {
        assertEquals(AppVersion(0, 4, 1), AppVersion.parse("0.4.1"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(0, 5, 0, listOf("rc", "1")), AppVersion.parse("0.5.0-rc.1"))
        assertEquals(AppVersion(0, 5, 0), AppVersion.parse("0.5.0+build.7"))
        listOf("", "0.4", "0.4.1.2", "a.b.c", "01.2.3", "0.4.1-", "0.4.1-a..b", "android-v0.4.1").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }

    @Test
    fun compara_numericamente_e_nao_como_texto() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("0.4.10", "0.4.9"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.99.99"))
        assertTrue(AppVersion.isNewer("0.5.0", "0.4.1"))
        assertFalse(AppVersion.isNewer("0.4.1", "0.4.1"))
        assertFalse(AppVersion.isNewer("0.4.0", "0.4.1"))
    }

    @Test
    fun pre_release_vale_menos_que_a_final() {
        assertTrue(AppVersion.isNewer("0.5.0", "0.5.0-rc.1"))
        assertFalse(AppVersion.isNewer("0.5.0-rc.1", "0.5.0"))
        assertTrue(AppVersion.isNewer("0.5.0-rc.2", "0.5.0-rc.1"))
        assertTrue(AppVersion.isNewer("0.5.0-rc.10", "0.5.0-rc.9"))
        assertTrue(AppVersion.isNewer("0.5.0-beta", "0.5.0-alpha"))
        assertTrue(AppVersion.isNewer("0.5.0-alpha.1", "0.5.0-alpha"))
        assertTrue(AppVersion.isNewer("0.5.0-rc.1", "0.4.9"))
    }

    @Test
    fun versao_ilegivel_nunca_e_mais_nova() {
        assertFalse(AppVersion.isNewer("lixo", "0.4.1"))
        assertFalse(AppVersion.isNewer("9.9.9", "lixo"))
    }
}
