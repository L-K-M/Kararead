package ch.lkmc.kararead.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.kararead.data.model.AppThemeMode

private val LightColors = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    secondaryContainer = md_light_secondaryContainer,
    tertiary = md_light_tertiary,
    background = md_light_background,
    surface = md_light_surface,
    surfaceVariant = md_light_surfaceVariant,
    onSurface = md_light_onSurface,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    secondaryContainer = md_dark_secondaryContainer,
    tertiary = md_dark_tertiary,
    background = md_dark_background,
    surface = md_dark_surface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurface = md_dark_onSurface,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
)

@Composable
fun KararreadTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = KararreadTypography,
        content = content,
    )
}
