package com.localkeys.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test de inicialização: abre o [MainActivity] num emulador de verdade e
 * espera a tela de desbloqueio renderizar.
 *
 * Existe porque o crash da v0.1.0 era de startup — o manifest apontava para uma
 * classe Application que não existe (`LocalKeysApp` era uma função Composable),
 * então o processo morria com ClassNotFoundException ANTES de qualquer UI. Isso
 * não aparece no `assembleDebug` (compilar não valida nome de classe do
 * manifest) nem nos testes JVM — só rodando o app.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    // O app pede a permissão de notificação ao abrir (Android 13+): o diálogo do sistema cobria a tela e o
    // teste nunca achava o texto. Concedida aqui, a tela sobe direto. A ordem importa: antes da activity.
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appAbreNaTelaDeDesbloqueio() {
        // Emulador de CI pode demorar a subir a primeira hierarquia Compose;
        // `fetchSemanticsNodes` lança "No compose hierarchies found" se ainda
        // não existe, então capamos a exceção e pollamos em vez de falhar imediato.
        rule.waitUntil(condition = {
            runCatching {
                rule.onAllNodesWithText("LocalKeys").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }, timeoutMillis = 120_000)
        rule.onNodeWithText("LocalKeys").assertIsDisplayed()
    }
}
