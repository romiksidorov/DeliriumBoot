package com.delirium.deliriumboot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DeliriumColorScheme = darkColorScheme(
    primary = DeliriumPrimary,
    secondary = DeliriumSecondary,
    background = DeliriumBackground,
    surface = DeliriumSurface,
    surfaceVariant = DeliriumSurfaceVariant,
    error = DeliriumError,
    onPrimary = DeliriumBackground,
    onSecondary = DeliriumBackground,
    onBackground = DeliriumText,
    onSurface = DeliriumText,
    onError = DeliriumBackground
)

@Composable
fun DeliriumBootTheme(content: @Composable () -> Unit) {
    // Приложение всегда использует фирменную тёмную тему "Delirium",
    // независимо от системных настроек.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DeliriumColorScheme,
        typography = DeliriumTypography,
        content = content
    )
}
