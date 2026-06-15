package ch.lkmc.kararead.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import ch.lkmc.kararead.data.model.ReaderFont
import ch.lkmc.kararead.data.model.ReaderPreferences
import ch.lkmc.kararead.data.model.ReaderTheme
import ch.lkmc.kararead.reader.ReaderHtmlBuilder

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Reading", style = MaterialTheme.typography.titleLarge)

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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val fonts = ReaderFont.entries
                fonts.forEachIndexed { index, font ->
                    SegmentedButton(
                        selected = prefs.font == font,
                        onClick = { onFont(font) },
                        shape = SegmentedButtonDefaults.itemShape(index, fonts.size),
                    ) {
                        Text(
                            fontLabel(font),
                            fontFamily = when (font) {
                                ReaderFont.SERIF -> FontFamily.Serif
                                ReaderFont.MONO -> FontFamily.Monospace
                                else -> FontFamily.SansSerif
                            },
                        )
                    }
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
    ReaderFont.SERIF -> "Serif"
    ReaderFont.SANS -> "Sans"
    ReaderFont.MONO -> "Mono"
    ReaderFont.SYSTEM -> "System"
}
