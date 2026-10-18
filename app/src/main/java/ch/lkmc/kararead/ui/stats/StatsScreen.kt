package ch.lkmc.kararead.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.ui.components.MessageState
import ch.lkmc.kararead.util.DayMinutes
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Stats") }) }) { padding ->
        if (!state.stats.hasAny) {
            MessageState(
                modifier = Modifier.padding(padding),
                title = "No reading yet",
                subtitle = "Open an article and your reading streak will grow here.",
                emoji = "📈",
            )
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            StreakHero(
                days = state.stats.currentStreakDays,
                longest = state.stats.longestStreakDays,
                forgiven = state.stats.streakForgivenRecently,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Today", "${state.stats.todayMinutes}", "min", Modifier.weight(1f))
                StatTile("This week", "${state.minutesThisWeek}", "min", Modifier.weight(1f))
                StatTile("Days read", "${state.stats.daysReadTotal}", "total", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Text("Last 14 days", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            BarChart(state.last14Days)
            Spacer(Modifier.height(24.dp))
            Text("Reading habit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Heatmap(state.heatmap)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StreakHero(days: Int, longest: Int, forgiven: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔥", style = MaterialTheme.typography.displaySmall)
            if (days >= 1) {
                Text(
                    "$days",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    if (days == 1) "day streak" else "days streak",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                // No active streak: encourage rather than show a deflating "0".
                Text(
                    "Read today to start a streak",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (longest > days && longest > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Best: $longest days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            if (forgiven && days >= 1) {
                // A quiet day slipped by, but the streak held — say so kindly so
                // the missed day doesn't feel like a glitch.
                Spacer(Modifier.height(6.dp))
                Text(
                    "🌿 A quiet day, forgiven — your streak lives on",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarChart(days: List<DayMinutes>) {
    val maxMinutes = (days.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val fraction = day.minutes.toFloat() / maxMinutes
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (day.minutes > 0) {
                    Text(
                        "${day.minutes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        // Reserve a minimum visible nub even for zero days.
                        .height(6.dp + (90.dp * fraction))
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day.isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // LocalLocale (not Locale.getDefault()): observable, so a
                    // system locale change recomposes the labels (compose-ui
                    // lint NonObservableLocale is error-severity).
                    day.date.dayOfWeek.getDisplayName(
                        TextStyle.NARROW,
                        LocalLocale.current.platformLocale,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A GitHub-style contribution grid of the reading habit: one small square per
 * day, columns = weeks (Mon…Sun top to bottom), tinted by minutes read.
 */
@Composable
private fun Heatmap(days: List<ch.lkmc.kararead.util.HeatmapDay>) {
    if (days.isEmpty()) return
    val weeks = days.chunked(7)
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    // Four intensity buckets by minutes read (0 = empty).
    fun cellColor(minutes: Int): androidx.compose.ui.graphics.Color = when {
        minutes <= 0 -> empty
        minutes < 5 -> base.copy(alpha = 0.30f)
        minutes < 15 -> base.copy(alpha = 0.52f)
        minutes < 30 -> base.copy(alpha = 0.74f)
        else -> base
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    day.future -> androidx.compose.ui.graphics.Color.Transparent
                                    else -> cellColor(day.minutes)
                                },
                            )
                            .then(
                                if (day.isToday) {
                                    Modifier.border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        RoundedCornerShape(3.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}
