package ch.lkmc.kararead.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.data.model.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Connection")
            SettingRow(title = "Server", subtitle = state.serverUrl.ifBlank { "Not connected" })
            SettingRow(
                title = "Read-later list",
                subtitle = state.readLaterName ?: "Pick one in the Lists tab",
            )

            if (state.stats.hasAny) {
                HorizontalDivider()
                SectionHeader("Reading")
                SettingRow(
                    title = streakTitle(state.stats.currentStreakDays),
                    subtitle = readingStatsSubtitle(state.stats),
                )
            }

            HorizontalDivider()
            SectionHeader("Appearance")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("App theme", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(themeLabel(mode)) },
                        )
                    }
                }
            }
            ToggleSetting(
                title = "Dynamic color",
                subtitle = "Use Material You colors from your wallpaper (Android 12+)",
                checked = state.dynamicColor,
                onChange = viewModel::setDynamicColor,
            )

            HorizontalDivider()
            SectionHeader("Storage")
            SettingRow(
                title = "Offline cache",
                subtitle = "${state.cachedCount} article(s) cached for offline reading",
                onClick = viewModel::clearCache,
                actionLabel = "Clear",
            )

            HorizontalDivider()
            SectionHeader("About")
            SettingRow(title = "Kararead", subtitle = "Version $version · a calm reader for Karakeep")

            Column(Modifier.padding(16.dp)) {
                OutlinedButton(
                    onClick = { viewModel.signOut(onSignedOut) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && actionLabel == null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null && onClick != null) {
            OutlinedButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun themeLabel(mode: AppThemeMode) = when (mode) {
    AppThemeMode.SYSTEM -> "System"
    AppThemeMode.LIGHT -> "Light"
    AppThemeMode.DARK -> "Dark"
}

private fun streakTitle(days: Int): String = when (days) {
    0 -> "No active streak"
    1 -> "🔥 1-day streak"
    else -> "🔥 $days-day streak"
}

private fun readingStatsSubtitle(stats: ch.lkmc.kararead.util.ReadingStats): String {
    val parts = buildList {
        if (stats.todayMinutes > 0) add("${stats.todayMinutes} min read today")
        if (stats.longestStreakDays > 1) add("best ${stats.longestStreakDays} days")
        add("${stats.daysReadTotal} day(s) total")
    }
    return parts.joinToString(" · ")
}
