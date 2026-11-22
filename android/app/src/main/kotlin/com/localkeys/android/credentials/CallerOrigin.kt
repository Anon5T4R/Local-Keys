package com.localkeys.android.credentials

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import com.localkeys.android.R
import com.localkeys.android.data.passkey.WebAuthn

/**
 * De onde vem um pedido de credencial.
 *
 * - **Navegador privilegiado** (Chrome, Firefox, Edge… na lista `res/raw/privileged_browsers.json`, a mesma do
 *   Google): o próprio navegador diz o site (`origin`) e já mandou o hash do `clientDataJSON`. Vale o site.
 * - **App nativo**: a origem é o hash do certificado do app (`android:apk-key-hash:…`), e o `clientDataJSON` é
 *   montado aqui.
 *
 * Um app qualquer não consegue se passar por navegador: o Android confere o certificado contra a lista.
 */
@RequiresApi(Build.VERSION_CODES.P)
class CallerOrigin private constructor(
    /** "https://site" (navegador) ou "android:apk-key-hash:…" (app). */
    val origin: String,
    /** Site do pedido, quando veio de um navegador privilegiado. */
    val browserDomain: String?,
    val packageName: String,
) {
    val isBrowser: Boolean get() = browserDomain != null

    companion object {
        fun of(context: Context, info: CallingAppInfo): CallerOrigin {
            val allowlist = context.resources.openRawResource(R.raw.privileged_browsers).bufferedReader().use { it.readText() }
            val browserOrigin = runCatching { info.getOrigin(allowlist) }.getOrNull()?.takeIf { it.isNotBlank() }
            if (browserOrigin != null) {
                return CallerOrigin(browserOrigin.trimEnd('/'), domainOf(browserOrigin), info.packageName)
            }
            val cert = info.signingInfo.apkContentsSigners.firstOrNull()?.toByteArray() ?: ByteArray(0)
            return CallerOrigin("android:apk-key-hash:" + WebAuthn.b64url(WebAuthn.sha256(cert)), null, info.packageName)
        }

        private fun domainOf(origin: String): String =
            origin.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").lowercase()
    }
}
