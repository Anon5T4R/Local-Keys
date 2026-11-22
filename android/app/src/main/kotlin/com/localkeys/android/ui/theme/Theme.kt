package com.localkeys.android.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cores da marca (mesmas do app-icon do desktop): índigo #6366F1 → #4338CA. Paleta própria, sem cor dinâmica:
// o app fica igual em todo aparelho e combina com o desktop.
val Indigo = Color(0xFF6366F1)
val IndigoDark = Color(0xFF4338CA)
val IndigoLight = Color(0xFFEEF0FF)

/** Âmbar da estrela de favorito (legível no claro e no escuro). */
val FavoriteAmber = Color(0xFFF5A700)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E1FF),
    onPrimaryContainer = Color(0xFF1E1A6B),
    secondary = Color(0xFF5C5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF7A5368),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF2F1124),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFECF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFC2C1FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC2C1FF),
    onPrimary = Color(0xFF2A2785),
    primaryContainer = Color(0xFF3F3B9E),
    onPrimaryContainer = Color(0xFFE2E1FF),
    secondary = Color(0xFFC5C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFEAB9D2),
    onTertiary = Color(0xFF472639),
    tertiaryContainer = Color(0xFF603C50),
    onTertiaryContainer = Color(0xFFFFD8EA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF34343A),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4F46E5),
)

@Composable
fun LocalKeysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

/**
 * Tema do app conforme a escolha das configurações ("system", "light" ou "dark") em [activity]: aplica as cores
 * e acerta os ícones das barras do sistema (claros no tema escuro, escuros no claro), já que o tema escolhido
 * pode diferir do tema do aparelho.
 */
@Composable
fun LocalKeysAppTheme(activity: ComponentActivity, themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    LaunchedEffect(dark) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    LocalKeysTheme(darkTheme = dark, content = content)
}
