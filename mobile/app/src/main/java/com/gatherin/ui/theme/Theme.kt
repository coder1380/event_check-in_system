package com.gatherin.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary           = BrandBlue,
    onPrimary         = Color.White,
    primaryContainer  = BrandBlueLight,
    onPrimaryContainer = NavyDark,
    secondary         = GreenSuccess,
    onSecondary       = Color.White,
    tertiary          = OrangeWarning,
    onTertiary        = Color.White,
    background        = LightGray,
    onBackground      = NavyDark,
    surface           = CardWhite,
    onSurface         = NavyDark,
    surfaceVariant    = Color(0xFFF1F5F9),
    onSurfaceVariant  = TextMuted,
    outline           = BorderGray,
    error             = RedError,
    onError           = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary           = BrandBlue,
    onPrimary         = Color.White,
    primaryContainer  = BrandBlueDark,
    onPrimaryContainer = Color.White,
    secondary         = GreenSuccess,
    onSecondary       = Color.White,
    background        = NavyDeep,
    onBackground      = Color.White,
    surface           = NavyDark,
    onSurface         = Color.White,
    surfaceVariant    = Color(0xFF1E293B),
    onSurfaceVariant  = SlateGray,
    outline           = Color(0xFF334155),
    error             = RedError,
    onError           = Color.White,
)

@Composable
fun GatherinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = GatherinTypography,
        content     = content
    )
}
