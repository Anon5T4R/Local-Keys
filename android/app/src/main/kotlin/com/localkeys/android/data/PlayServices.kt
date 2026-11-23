package com.localkeys.android.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * O Google Play services (`com.google.android.gms`) está instalado? Só a importação de outro gerenciador
 * (Credential Exchange) depende dele; num aparelho sem Google, a opção some e o resto do app segue igual.
 * O pacote é declarado em `<queries>` no manifesto (sem isso, no Android 11+, ele nunca seria visto).
 */
object PlayServices {
    const val PACKAGE = "com.google.android.gms"

    fun isInstalled(context: Context): Boolean =
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(PACKAGE, 0)
            }
            info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
