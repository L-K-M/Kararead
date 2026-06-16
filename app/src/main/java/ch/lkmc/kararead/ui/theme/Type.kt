package ch.lkmc.kararead.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App-chrome typography. The reader styles its own body via CSS (see reader/).
 *
 * Headlines and titles are set in Source Serif 4 — one of the bundled literary
 * serifs — instead of the platform's generic serif. That crafted face, kept at
 * restrained weights with a little negative tracking and roomier line-height, is
 * what gives the library its calm, "magazine" feel. Functional text (source
 * lines, excerpts, labels, buttons) stays in the platform sans, which is crisper
 * at small sizes.
 *
 * Built from the [AssetManager] (rather than as a top-level constant) because the
 * faces live in assets/fonts and are loaded as variable fonts.
 */
private fun serifFamily(assets: AssetManager): FontFamily {
    fun face(weight: FontWeight) = Font(
        path = "fonts/SourceSerif4.ttf",
        assetManager = assets,
        weight = weight,
        // Drive the variable font's weight axis explicitly so each declared
        // weight renders from the single file rather than via faux-bold.
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )
    return FontFamily(
        face(FontWeight.Normal),
        face(FontWeight.Medium),
        face(FontWeight.SemiBold),
        face(FontWeight.Bold),
    )
}

fun kararreadTypography(assets: AssetManager): Typography {
    val serif = serifFamily(assets)
    return Typography().copy(
        headlineMedium = TextStyle(
            fontFamily = serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = serif,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.1).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp,
        ),
    )
}
