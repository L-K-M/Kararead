package ch.lkmc.kararead.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
    accentColor: Int = DefaultAccent.toArgb(),
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
        dark -> accentedDark(Color(accentColor))
        else -> accentedLight(Color(accentColor))
    }
    val typography = remember(context) { kararreadTypography(context.assets) }

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}

private fun onColorFor(c: Color): Color = if (c.luminance() > 0.5f) Color.Black else Color.White

/** Re-tint the warm light scheme around a chosen accent (when dynamic color is off). */
private fun accentedLight(seed: Color) = LightColors.copy(
    primary = seed,
    onPrimary = onColorFor(seed),
    primaryContainer = lerp(seed, Color.White, 0.72f),
    onPrimaryContainer = lerp(seed, Color.Black, 0.55f),
    secondary = lerp(seed, Color.Black, 0.10f),
    secondaryContainer = lerp(seed, Color.White, 0.78f),
    tertiary = seed,
)

private fun accentedDark(seed: Color) = DarkColors.copy(
    primary = lerp(seed, Color.White, 0.45f),
    onPrimary = lerp(seed, Color.Black, 0.60f),
    primaryContainer = lerp(seed, Color.Black, 0.45f),
    onPrimaryContainer = lerp(seed, Color.White, 0.72f),
    secondary = lerp(seed, Color.White, 0.40f),
    secondaryContainer = lerp(seed, Color.Black, 0.40f),
    tertiary = lerp(seed, Color.White, 0.40f),
)
