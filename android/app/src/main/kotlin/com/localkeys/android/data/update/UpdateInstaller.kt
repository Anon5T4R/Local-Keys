package com.localkeys.android.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.localkeys.android.R
import com.localkeys.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Instala o APK baixado pelo [PackageInstaller] do sistema. O Android só aceita a atualização se o APK for do
 * mesmo pacote e assinado com o mesmo certificado do app instalado; a confirmação do usuário (quando o sistema a
 * pede) chega ao [UpdateInstallReceiver]. O APK é apagado assim que entra na sessão.
 */
object UpdateInstaller {

    /** Pasta dos downloads do atualizador (no cache: o sistema pode limpar, e o app limpa ao terminar). */
    fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

    fun clearDownloads(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.delete() }
    }

    /** "Instalar apps desconhecidos" liberado para o Local Keys? Sem isso o sistema recusa a sessão. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Cria a sessão, copia o APK e a entrega ao sistema. Lança [UpdateException] (INSTALL). */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                // Os bytes já estão na sessão: o arquivo do cache não serve mais.
                apk.delete()
                session.commit(statusReceiver(context, sessionId).intentSender)
            }
        } catch (e: IOException) {
            abandon(installer, sessionId)
            throw UpdateException(UpdateFailure.INSTALL, cause = e)
        } catch (e: SecurityException) {
            abandon(installer, sessionId)
            throw UpdateException(UpdateFailure.INSTALL, cause = e)
        } finally {
            apk.delete()
        }
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        if (sessionId < 0) return
        try {
            installer.abandonSession(sessionId)
        } catch (_: SecurityException) {
            // Sessão já fechada pelo sistema: nada a abandonar.
        }
    }

    private fun statusReceiver(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
        // Mutável: o sistema preenche o status e o intent de confirmação. O intent é explícito (nosso receiver).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

/**
 * Resultado da sessão do [UpdateInstaller]. Pedido de confirmação → abre a tela do sistema; falha → aviso
 * guardado para a próxima abertura e um toast. O sucesso é notado pelo próprio app novo ao abrir (a versão
 * instalada passa a ser a pendente). Não exportado: só o PendingIntent do app chega aqui.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: RuntimeException) {
                    record(context, "fail:" + context.getString(R.string.update_error_install), toast = true)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstaller.clearDownloads(context)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> record(context, NOTICE_ABORTED, toast = false)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.update_error_install)
                record(context, "fail:$message", toast = true)
            }
        }
    }

    private fun record(context: Context, notice: String, toast: Boolean) {
        UpdateInstaller.clearDownloads(context)
        if (toast) {
            val text = context.getString(R.string.update_failed_notice, notice.removePrefix("fail:"))
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = SettingsStore(app)
                store.setUpdatePending(null)
                store.setUpdateNotice(notice)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.localkeys.android.UPDATE_INSTALL_STATUS"
        const val NOTICE_ABORTED = "aborted"
    }
}
