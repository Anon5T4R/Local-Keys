package com.localkeys.android

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.localkeys.android.ui.VaultViewModel
import com.localkeys.android.ui.launchVaultBiometric
import com.localkeys.android.ui.theme.LocalKeysTheme

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // API 33+: a notificação de feedback do autofill precisa de permissão.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            LocalKeysTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LocalKeysApp(
                        viewModel = viewModel,
                        onPickDocument = {
                            openVaultLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onCreateDocument = { password ->
                            pendingCreatePassword = password
                            createVaultLauncher.launch("localkeys.tkeys")
                        },
                        onPickImport = {
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onExport = ::startExport,
                        onRequestBiometric = { launchVaultBiometric(viewModel, it) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

}
