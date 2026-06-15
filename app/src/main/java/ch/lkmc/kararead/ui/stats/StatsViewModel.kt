package ch.lkmc.kararead.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.kararead.data.repository.KarakeepRepository
import ch.lkmc.kararead.util.DayMinutes
import ch.lkmc.kararead.util.ReadingStats
import ch.lkmc.kararead.util.computeReadingStats
import ch.lkmc.kararead.util.minutesInLastDays
import ch.lkmc.kararead.util.recentDaysSeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class StatsUiState(
    val stats: ReadingStats = ReadingStats(),
    val last14Days: List<DayMinutes> = emptyList(),
    val minutesThisWeek: Int = 0,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    repository: KarakeepRepository,
) : ViewModel() {

    val state: StateFlow<StatsUiState> =
        repository.readingSecondsByDate()
            .map { byDate ->
                StatsUiState(
                    stats = computeReadingStats(byDate),
                    last14Days = recentDaysSeries(byDate, days = 14),
                    minutesThisWeek = minutesInLastDays(byDate, days = 7),
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, StatsUiState())
}
