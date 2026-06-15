package ch.lkmc.kararead.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import ch.lkmc.kararead.reader.ReaderHtmlBuilder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderControlsSheet(
    prefs: ReaderPreferences,
    onDismiss: () -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onMargin: (Int) -> Unit,
    onJustify: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onVolumeKeyPaging: (Boolean) -> Unit,
    onPagedMode: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Reading", style = MaterialTheme.typography.titleLarge)

            // Live preview, so size/spacing/typeface/theme changes are visible
            // without leaving the sheet.
            PreviewCard(prefs)

            // Theme swatches
            SectionLabel("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    ThemeSwatch(
                        theme = theme,
                        selected = prefs.theme == theme,
                        onClick = { onTheme(theme) },
                    )
                }
            }

            // Font family
            SectionLabel("Typeface")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = prefs.font == font,
                        onClick = { onFont(font) },
                        label = { Text(fontLabel(font), fontFamily = composeFamily(font)) },
                    )
                }
            }

            // Font size
            SliderRow(
                label = "Text size",
                value = prefs.fontScale,
                valueRange = 0.7f..2.0f,
                steps = 12,
                onChange = onFontScale,
                valueText = "${(prefs.fontScale * 100).toInt()}%",
            )

            // Line height
            SliderRow(
                label = "Line spacing",
                value = prefs.lineHeight,
                valueRange = 1.2f..2.2f,
                steps = 9,
                onChange = onLineHeight,
                valueText = String.format("%.1f", prefs.lineHeight),
            )

            // Margins
            SliderRow(
                label = "Margins",
                value = prefs.horizontalMargin.toFloat(),
                valueRange = 0f..48f,
                steps = 11,
                onChange = { onMargin(it.toInt()) },
                valueText = "${prefs.horizontalMargin}",
            )

            ToggleRow("Paged reading (swipe to turn)", prefs.pagedMode, onPagedMode)
            ToggleRow("Justify text", prefs.justify, onJustify)
            ToggleRow("Keep screen on", prefs.keepScreenOn, onKeepScreenOn)
            ToggleRow("Volume keys turn pages", prefs.volumeKeyPaging, onVolumeKeyPaging)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = ReaderHtmlBuilder.paletteFor(theme)
    val bg = runCatching { Color(android.graphics.Color.parseColor(palette.background)) }
        .getOrDefault(Color.White)
    val fg = runCatching { Color(android.graphics.Color.parseColor(palette.text)) }
        .getOrDefault(Color.Black)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bg)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            } else {
                Text("Aa", color = fg, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(themeLabel(theme), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    valueText: String,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(label)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun themeLabel(theme: ReaderTheme) = when (theme) {
    ReaderTheme.LIGHT -> "Light"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.DARK -> "Dark"
    ReaderTheme.BLACK -> "Black"
}

private fun fontLabel(font: ReaderFont) = when (font) {
    ReaderFont.LITERATA -> "Literata"
    ReaderFont.LORA -> "Lora"
    ReaderFont.SOURCE_SERIF -> "Source Serif"
    ReaderFont.NEWSREADER -> "Newsreader"
    ReaderFont.CRIMSON -> "Crimson"
    ReaderFont.BITTER -> "Bitter"
    ReaderFont.INTER -> "Inter"
    ReaderFont.ATKINSON -> "Atkinson"
    ReaderFont.SYSTEM -> "System"
    ReaderFont.MONO -> "Mono"
}

/** Closest Compose family for chip/preview rendering (the reader itself uses the bundled font). */
private fun composeFamily(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.LITERATA, ReaderFont.LORA, ReaderFont.SOURCE_SERIF,
    ReaderFont.NEWSREADER, ReaderFont.CRIMSON, ReaderFont.BITTER -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

@Composable
private fun PreviewCard(prefs: ReaderPreferences) {
    val palette = ReaderHtmlBuilder.paletteFor(prefs.theme)
    fun parse(hex: String, fallback: Color) =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
    val bg = parse(palette.background, Color.White)
    val fg = parse(palette.text, Color.Black)
    val secondary = parse(palette.secondary, fg)
    // Mirror the reader CSS: 19px base, scaled, with the unitless line-height.
    val bodySize = (19f * prefs.fontScale).sp
    val family = composeFamily(prefs.font)

    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "The Quiet Hour",
                color = fg,
                fontFamily = family,
                fontSize = (bodySize.value * 1.35f).sp,
                lineHeight = (bodySize.value * 1.35f * 1.2f).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Good type disappears, leaving only the words. Adjust the size, " +
                    "spacing and typeface until reading feels effortless.",
                color = fg,
                fontFamily = family,
                fontSize = bodySize,
                lineHeight = (bodySize.value * prefs.lineHeight).sp,
                textAlign = if (prefs.justify) TextAlign.Justify else TextAlign.Start,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Preview",
                color = secondary,
                fontFamily = FontFamily.SansSerif,
                fontSize = (bodySize.value * 0.7f).sp,
            )
        }
    }
}
