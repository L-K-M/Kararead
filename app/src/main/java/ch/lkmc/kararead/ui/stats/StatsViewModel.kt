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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.LocalDateTime
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

    // Re-emits at every local midnight. Without it, "today" was evaluated only
    // when the Room flow emitted (i.e. on writes), so an app alive across
    // midnight kept yesterday's minutes as "today" and highlighted the wrong
    // bar until the next reading session.
    private val dayTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
        }
    }

    val state: StateFlow<StatsUiState> =
        combine(repository.readingSecondsByDate(), dayTicker) { byDate, _ ->
            StatsUiState(
                stats = computeReadingStats(byDate),
                last14Days = recentDaysSeries(byDate, days = 14),
                minutesThisWeek = minutesInLastDays(byDate, days = 7),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, StatsUiState())
}
