package com.localkeys.android

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.localkeys.android.data.PlayServices
import com.localkeys.android.ui.HostCallbacks
import com.localkeys.android.ui.UpdateViewModel
import com.localkeys.android.ui.VaultViewModel
import com.localkeys.android.ui.launchVaultBiometric
import com.localkeys.android.ui.theme.LocalKeysAppTheme

/** AppCompatActivity (é uma FragmentActivity, o BiometricPrompt continua igual) para o idioma por app valer
 *  também antes do Android 13. */
class MainActivity : AppCompatActivity() {

    private val viewModel: VaultViewModel by viewModels()

    /** Atualizador opcional (desligado por padrão); independe do cofre. */
    private val updates: UpdateViewModel by viewModels()

    /** Senha nova digitada na tela de criar — guardada só até o SAF responder. */
    private var pendingCreatePassword: String? = null

    private val openVaultLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onVaultUriChosen(uri)
        }

    private val createVaultLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val password = pendingCreatePassword
            pendingCreatePassword = null
            if (uri != null && password != null) {
                viewModel.onCreatedDocument(uri, password)
            }
        }

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onImportUriChosen(uri)
        }

    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) viewModel.onExportUriChosen(uri)
        }

    /** "json" ou "csv": prepara o conteúdo e abre o SAF para escolher o destino. */
    private fun startExport(format: String) {
        val name = if (format == "csv") "localkeys-export.csv" else "localkeys-export.json"
        viewModel.exportRequested(format)
        exportLauncher.launch(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Troca o tema da splash pelo tema do app (sem isso a janela ficava com a barra de ação do tema da
        // splash por cima da do Compose: as duas barras empilhadas da v0.3).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Sem pedir a permissão de notificação na abertura: a única notificação do app é o aviso do autofill
        // ("destrave para salvar"), e ele cai para um toast quando as notificações estão desligadas
        // (LocalKeysAutofillService.notify). Quem quiser a notificação liga nas configurações do Android.
        val importOther: (() -> Unit)? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && PlayServices.isInstalled(this)) {
            { viewModel.importFromOtherManager(this) }
        } else {
            null
        }
        setContent {
            val state by viewModel.state.collectAsState()
            LocalKeysAppTheme(this, state.themeMode) {
                LocalKeysApp(
                    viewModel = viewModel,
                    updates = updates,
                    onPickDocument = {
                        openVaultLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onCreateDocument = { password ->
                        pendingCreatePassword = password
                        createVaultLauncher.launch("localkeys.tkeys")
                    },
                    callbacks = HostCallbacks(
                        onPickImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExport = ::startExport,
                        onRequestBiometric = { launchVaultBiometric(viewModel, it) },
                        onImportOther = importOther,
                        onOpenVault = { openVaultLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Verificação automática (só se ligada, no máximo a cada 24 h) e a volta da tela de permissão de instalar.
        updates.onResume()
    }
}
