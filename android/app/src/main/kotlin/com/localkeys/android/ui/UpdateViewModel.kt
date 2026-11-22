package com.localkeys.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localkeys.android.BuildConfig
import com.localkeys.android.data.store.SettingsStore
import com.localkeys.android.data.store.UpdatePrefs
import com.localkeys.android.data.update.AndroidRelease
import com.localkeys.android.data.update.AppVersion
import com.localkeys.android.data.update.ReleasePicker
import com.localkeys.android.data.update.UpdateException
import com.localkeys.android.data.update.UpdateFailure
import com.localkeys.android.data.update.UpdateInstaller
import com.localkeys.android.data.update.UpdateNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * O atualizador opcional. Desligado por padrão: só há rede com a verificação automática ligada (no máximo uma
 * vez a cada 24 h, ao abrir/voltar ao app) ou no "verificar agora". Não lê nem grava nada do cofre — por isso vive
 * num ViewModel à parte do [VaultViewModel] e funciona com o cofre trancado.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    /** Resultado da última verificação. */
    sealed interface CheckStatus {
        data object Never : CheckStatus
        data object UpToDate : CheckStatus
        data class Available(val version: String) : CheckStatus
        data class Failed(val failure: UpdateFailure, val code: Int) : CheckStatus
    }

    /** O que está acontecendo com a atualização em si. */
    sealed interface Progress {
        data class Downloading(val version: String, val read: Long, val total: Long) : Progress
        data class Installing(val version: String) : Progress
    }

    /** Aviso único (snackbar) sobre uma instalação: feita, cancelada ou que falhou. */
    sealed interface Notice {
        data class Updated(val version: String) : Notice
        data object Aborted : Notice
        data class Failed(val message: String) : Notice
        data class Error(val failure: UpdateFailure, val code: Int) : Notice
    }

    data class UiState(
        val autoCheck: Boolean = false,
        val checking: Boolean = false,
        val lastCheckAt: Long = 0L,
        val status: CheckStatus = CheckStatus.Never,
        val available: AndroidRelease? = null,
        val dismissedVersion: String? = null,
        val progress: Progress? = null,
        /** Falta liberar "instalar apps desconhecidos": a UI explica e leva à tela do sistema. */
        val needsInstallPermission: Boolean = false,
        val notice: Notice? = null,
    ) {
        val currentVersion: String get() = BuildConfig.VERSION_NAME

        /** O cartão no topo da lista: há versão nova não dispensada, ou a atualização está em curso. */
        val showBanner: Boolean get() = progress != null || (available != null && available.version != dismissedVersion)
    }

    private val settings = SettingsStore(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** O usuário foi liberar a permissão de instalar; ao voltar com ela, a atualização segue sozinha. */
    private var resumeAfterPermission = false

    init {
        viewModelScope.launch {
            settings.updatePrefs.collect { prefs -> applyPrefs(prefs) }
        }
        viewModelScope.launch {
            val prefs = settings.updatePrefs.first()
            val pending = prefs.pendingVersion
            if (pending != null && pending == BuildConfig.VERSION_NAME) {
                settings.setUpdatePending(null)
                settings.setUpdateNotice("ok:$pending")
            }
            UpdateInstaller.clearDownloads(getApplication())
        }
    }

    private fun applyPrefs(prefs: UpdatePrefs) {
        // Release guardada que não é mais nova que a instalada (o app já foi atualizado) não conta.
        val available = AndroidRelease.fromJson(prefs.availableJson)
            ?.takeIf { AppVersion.isNewer(it.version, BuildConfig.VERSION_NAME) }
        val status = when {
            available != null -> CheckStatus.Available(available.version)
            prefs.lastStatus == null -> CheckStatus.Never
            prefs.lastStatus.startsWith("error:") -> {
                val parts = prefs.lastStatus.split(':')
                val failure = UpdateFailure.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: UpdateFailure.NETWORK
                CheckStatus.Failed(failure, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
            else -> CheckStatus.UpToDate
        }
        val notice = when (val raw = prefs.installNotice) {
            null -> null
            com.localkeys.android.data.update.UpdateInstallReceiver.NOTICE_ABORTED -> Notice.Aborted
            else -> when {
                raw.startsWith("ok:") -> Notice.Updated(raw.removePrefix("ok:"))
                else -> Notice.Failed(raw.removePrefix("fail:"))
            }
        }
        _state.update { s ->
            // Falha de instalação vinda do receiver encerra o "instalando".
            val progress = if (notice != null && s.progress is Progress.Installing) null else s.progress
            s.copy(
                autoCheck = prefs.autoCheck,
                lastCheckAt = prefs.lastCheckAt,
                status = status,
                available = available,
                dismissedVersion = prefs.dismissedVersion,
                notice = notice ?: s.notice.takeIf { it is Notice.Error },
                progress = progress,
            )
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch {
            settings.setUpdateAuto(enabled)
            if (enabled) maybeAutoCheck()
        }
    }

    /** Ao abrir ou voltar ao app: verifica se a automática está ligada e já passaram 24 h. */
    fun onResume() {
        if (resumeAfterPermission) {
            resumeAfterPermission = false
            if (UpdateInstaller.canInstall(getApplication())) startUpdate()
        }
        viewModelScope.launch { maybeAutoCheck() }
    }

    private suspend fun maybeAutoCheck() {
        val prefs = settings.updatePrefs.first()
        if (!prefs.autoCheck) return
        val now = System.currentTimeMillis()
        // Relógio que voltou no tempo também libera a verificação.
        if (prefs.lastCheckAt in (now - AUTO_INTERVAL_MS + 1)..now) return
        check()
    }

    fun checkNow() {
        viewModelScope.launch { check() }
    }

    private suspend fun check() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        val now = System.currentTimeMillis()
        try {
            val json = withContext(Dispatchers.IO) { UpdateNet.fetchReleasesJson() }
            val newer = withContext(Dispatchers.Default) { ReleasePicker.newerThan(json, BuildConfig.VERSION_NAME) }
            settings.setUpdateCheckResult(now, if (newer != null) "available" else "uptodate", newer?.toJson())
        } catch (e: UpdateException) {
            settings.setUpdateCheckResult(now, "error:${e.failure.name}:${e.code}", null)
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    fun dismissBanner() {
        val version = _state.value.available?.version ?: return
        viewModelScope.launch { settings.setUpdateDismissed(version) }
    }

    /** Baixa e instala a versão achada. Sem a permissão de instalar, pede antes (ver [openInstallPermission]). */
    fun startUpdate() {
        val release = _state.value.available ?: return
        if (_state.value.progress != null) return
        val app = getApplication<Application>()
        if (!UpdateInstaller.canInstall(app)) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        _state.update { it.copy(progress = Progress.Downloading(release.version, 0, release.apkSize), notice = null) }
        viewModelScope.launch {
            val dir = UpdateInstaller.updatesDir(app)
            val apk = File(dir, "local-keys-${release.version}.apk")
            try {
                withContext(Dispatchers.IO) {
                    UpdateInstaller.clearDownloads(app)
                    UpdateNet.downloadApk(release, apk) { read, total ->
                        _state.update { it.copy(progress = Progress.Downloading(release.version, read, total)) }
                    }
                }
                _state.update { it.copy(progress = Progress.Installing(release.version)) }
                settings.setUpdateNotice(null)
                settings.setUpdatePending(release.version)
                withContext(Dispatchers.IO) { UpdateInstaller.install(app, apk) }
            } catch (e: UpdateException) {
                settings.setUpdatePending(null)
                _state.update { it.copy(progress = null, notice = Notice.Error(e.failure, e.code)) }
            } finally {
                withContext(Dispatchers.IO) { apk.delete() }
            }
        }
    }

    /** Abre "instalar apps desconhecidos" do Local Keys; ao voltar com a permissão, a atualização continua. */
    fun openInstallPermission(context: Context) {
        _state.update { it.copy(needsInstallPermission = false) }
        resumeAfterPermission = true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
        try {
            context.startActivity(intent)
        } catch (_: RuntimeException) {
            resumeAfterPermission = false
            _state.update { it.copy(notice = Notice.Error(UpdateFailure.INSTALL, 0)) }
        }
    }

    fun dismissInstallPermission() {
        _state.update { it.copy(needsInstallPermission = false) }
    }

    fun consumeNotice() {
        val had = _state.value.notice
        _state.update { it.copy(notice = null) }
        if (had != null && had !is Notice.Error) viewModelScope.launch { settings.setUpdateNotice(null) }
    }

    private companion object {
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
