package com.vincentzyu.androidhostvpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = Purple40,
    primaryContainer = KotlinPurple,
    onPrimaryContainer = DarkOnSurface,
    secondary = AccentMint,
    onSecondary = DarkBackground,
    secondaryContainer = AccentMintContainer,
    onSecondaryContainer = AccentMint,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = KotlinPurple,
    onPrimary = DarkOnSurface,
    primaryContainer = Purple80,
    onPrimaryContainer = Purple40,
    secondary = KotlinIndigo,
    onSecondary = DarkOnSurface,
    background = DarkOnSurface,
    surface = DarkOnSurface,
    onSurface = DarkBackground
)

@Composable
fun AndroidHostVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // 调试网络工具，暗色模式体验最佳，也可使用适配体系
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
