package ch.lkmc.kararead.ui.reader

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ch.lkmc.kararead.data.model.prettyHost
import ch.lkmc.kararead.ui.components.LoadingState
import ch.lkmc.kararead.ui.components.MessageState

/** Granularity of the reading-time tally, in seconds. */
private const val READING_TICK_SECONDS = 15L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val ttsVoiceId by viewModel.ttsVoiceId.collectAsStateWithLifecycle()
    val ttsRate by viewModel.ttsRate.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val highlightsJson by viewModel.highlightsJson.collectAsStateWithLifecycle()
    val nextUp by viewModel.nextUp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var chromeVisible by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var pendingHighlightId by remember { mutableStateOf<String?>(null) }
    var showVoicePicker by remember { mutableStateOf(false) }
    val pager = remember { ReaderPager() }
    // Measured height of the end-of-article "Done · Next" badge, so paging can
    // avoid scrolling text behind it (see pageCoverCssPx below).
    var finishFabHeightPx by remember { mutableStateOf(0) }

    // Immersive reading: the system status bar follows the reader chrome, so it
    // hides while you read and returns on tap. Restore it when leaving.
    val window = remember(view) {
        var c: android.content.Context? = view.context
        var w: android.view.Window? = null
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) { w = c.window; break }
            c = c.baseContext
        }
        w
    }
    androidx.compose.runtime.DisposableEffect(window) {
        onDispose {
            window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, view)
                    .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(chromeVisible, window) {
        window?.let {
            val controller = androidx.core.view.WindowCompat.getInsetsController(it, view)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val bars = androidx.core.view.WindowInsetsCompat.Type.statusBars()
            if (chromeVisible) controller.show(bars) else controller.hide(bars)
        }
    }

    // Transient feedback (e.g. highlight saved / failed).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Narration couldn't start (no TTS engine / no readable text): tell the user
    // rather than leaving "Listen" looking like it did nothing.
    androidx.compose.runtime.LaunchedEffect(speech.failed) {
        if (speech.failed) {
            android.widget.Toast.makeText(
                context, "Couldn't start narration on this device", android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Follow narration: keep the article scrolled to roughly the sentence being
    // spoken, so listening and reading stay in sync.
    androidx.compose.runtime.LaunchedEffect(speech.index, speech.speaking, speech.total) {
        if (speech.speaking && speech.total > 1) {
            pager.scrollTo(speech.index / (speech.total - 1f))
        }
    }

    // B5: advance to the next unread article (optionally marking this one read).
    val advance: (Boolean) -> Unit = { archiveFirst ->
        scope.launch {
            val next = viewModel.nextArticle(archiveFirst)
            if (next != null) {
                onOpenReader(next)
            } else {
                android.widget.Toast.makeText(
                    context, "You're all caught up ✨", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // D5: a brief "resume" nudge so the silent scroll jump on reopen feels
    // intentional. Shows only when reopening meaningfully mid-article.
    val bookmarkKey = state.article?.bookmark?.id
    var showResume by remember(bookmarkKey) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(bookmarkKey, state.initialProgress) {
        if (bookmarkKey != null && state.initialProgress in 0.05f..0.95f) {
            showResume = true
            kotlinx.coroutines.delay(3500)
            showResume = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
    }

    // Tally foreground reading time (powers the reading streak), only while the
    // reader is actually resumed on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(READING_TICK_SECONDS * 1000L)
                viewModel.recordReadingSeconds(READING_TICK_SECONDS)
            }
        }
    }

    // Persist the reading position the moment we're backgrounded, so it survives
    // a background process kill — onCleared() isn't guaranteed in that case, and
    // the debounced write may not have run yet.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Claim the hardware volume keys for page turning while this screen is shown.
    val volumeController = remember(context) { context.findVolumeKeyController() }
    androidx.compose.runtime.DisposableEffect(volumeController, prefs.volumeKeyPaging) {
        if (volumeController != null && prefs.volumeKeyPaging) {
            volumeController.setVolumeKeyHandler { up ->
                pager.page(if (up) -1 else 1)
                true
            }
        }
        onDispose { volumeController?.setVolumeKeyHandler(null) }
    }

    // The "Done · Next" badge shows near the end and covers the bottom-right text.
    // Tell the WebView how much vertical space it occupies (nav-bar inset + its own
    // height + margin), in CSS px, so paging doesn't land text behind it.
    val finishVisible =
        state.article != null && !state.archived && !speech.active && state.progress >= 0.92f
    val density = LocalDensity.current
    val navBottomDp = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density) / density.density
    val pageCoverCssPx = if (finishVisible && finishFabHeightPx > 0) {
        (navBottomDp + 16f + finishFabHeightPx / density.density).toInt()
    } else {
        0
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingState()
            state.error != null && state.article == null -> MessageState(
                title = "Couldn't open this article",
                subtitle = state.error,
                emoji = "😕",
                actionLabel = "Retry",
                onAction = { viewModel.load(forceRefresh = true) },
            )
            state.article != null -> {
                ReaderWebView(
                    article = state.article!!,
                    prefs = prefs,
                    baseUrl = viewModel.serverOrigin,
                    initialProgress = state.initialProgress,
                    initialAnchor = state.initialAnchor,
                    assetLoader = viewModel.assetLoader,
                    onProgress = viewModel::onProgress,
                    onScrollDirection = { up ->
                        // Auto-hide while reading down; reveal is tap-only (so a
                        // scroll-up while reading doesn't pop the chrome back).
                        if (!up) chromeVisible = false
                    },
                    onTap = { chromeVisible = !chromeVisible },
                    onSelection = { text, start, end -> viewModel.addHighlight(text, start, end) },
                    onHighlightTap = { id -> pendingHighlightId = id },
                    highlightsJson = highlightsJson,
                    pager = pager,
                    pageBottomCoverPx = pageCoverCssPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Top chrome (auto-hides while reading down).
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val bm = state.article?.bookmark
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            bm?.siteName ?: bm?.url?.let { prettyHost(it) } ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val left = ch.lkmc.kararead.util.minutesLeft(
                            bm?.readingTimeMinutes, state.progress,
                        )
                        val sub = when {
                            state.archived -> "Read"
                            left == 0 -> "Almost done"
                            left != null -> "$left min left"
                            else -> null
                        }
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavourite) {
                        Icon(
                            if (state.favourited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favourite",
                            tint = if (state.favourited) MaterialTheme.colorScheme.primary
                            else LocalContentColorSafe(),
                        )
                    }
                    IconButton(onClick = { showControls = true }) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Reading settings")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.archived) "Mark as unread" else "Mark as read") },
                                leadingIcon = {
                                    Icon(
                                        if (state.archived) Icons.Filled.RadioButtonUnchecked
                                        else Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { overflowOpen = false; viewModel.toggleArchived() },
                            )
                            if (viewModel.canListen && !speech.active) {
                                DropdownMenuItem(
                                    text = { Text("Listen") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                                    },
                                    onClick = { overflowOpen = false; viewModel.listen() },
                                )
                            }
                            if (highlights.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Highlights (${highlights.size})") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.FormatQuote, contentDescription = null)
                                    },
                                    onClick = { overflowOpen = false; showHighlights = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export highlights") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.IosShare, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.highlightsMarkdown()?.let {
                                            shareText(context, it, state.article?.bookmark?.displayTitle)
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Open original") },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.url()?.let { openUrl(context, it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.url()?.let { shareUrl(context, it, state.article?.bookmark?.displayTitle) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Next in queue →") },
                                onClick = { overflowOpen = false; advance(false) },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { overflowOpen = false; viewModel.load(forceRefresh = true) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            )
        }

        // D5: brief "resume" nudge, just below where the top bar sits.
        AnimatedVisibility(
            visible = showResume,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
        ) {
            val left = ch.lkmc.kararead.util.minutesLeft(
                state.article?.bookmark?.readingTimeMinutes, state.initialProgress,
            )
            val label = when {
                left == null -> "Resumed where you left off"
                left <= 0 -> "Resumed · almost done"
                else -> "Resumed · $left min left"
            }
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Thin reading-progress line, pinned unobtrusively to the bottom.
        if (state.article != null) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }

        // B5: when you reach the end, a gentle "finish & continue" affordance.
        AnimatedVisibility(
            visible = finishVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                modifier = Modifier.onSizeChanged { finishFabHeightPx = it.height },
                onClick = { advance(true) },
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                text = {
                    Column {
                        Text(if (nextUp != null) "Done · Next" else "Done")
                        nextUp?.let {
                            Text(
                                it.displayTitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                    }
                },
            )
        }

        // Narration ("listen") mini-player.
        AnimatedVisibility(
            visible = speech.active,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            ListenBar(
                speech = speech,
                rate = ttsRate,
                onCycleRate = { viewModel.setSpeechRate(nextSpeechRate(ttsRate)) },
                onToggle = viewModel::toggleSpeech,
                onPrev = { viewModel.skipSpeech(-1) },
                onNext = { viewModel.skipSpeech(1) },
                onVoice = { showVoicePicker = true }.takeIf { voices.isNotEmpty() },
                onStop = viewModel::stopSpeech,
            )
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            voices = voices,
            selectedId = ttsVoiceId,
            onPick = { viewModel.setVoice(it); showVoicePicker = false },
            onDismiss = { showVoicePicker = false },
        )
    }

    if (showControls) {
        ReaderControlsSheet(
            prefs = prefs,
            onDismiss = { showControls = false },
            onTheme = viewModel::setTheme,
            onFont = viewModel::setFont,
            onFontScale = viewModel::setFontScale,
            onLineHeight = viewModel::setLineHeight,
            onMargin = viewModel::setMargin,
            onJustify = viewModel::setJustify,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onVolumeKeyPaging = viewModel::setVolumeKeyPaging,
        )
    }

    if (showHighlights) {
        HighlightsSheet(
            highlights = highlights,
            onDismiss = { showHighlights = false },
            onEdit = { id -> showHighlights = false; pendingHighlightId = id },
            onDelete = viewModel::removeHighlight,
        )
    }

    pendingHighlightId?.let { id ->
        val hl = viewModel.highlight(id)
        if (hl == null) {
            pendingHighlightId = null
        } else {
            HighlightNoteDialog(
                highlight = hl,
                onDismiss = { pendingHighlightId = null },
                onSaveNote = { note -> viewModel.updateHighlightNote(id, note) },
                onDelete = { viewModel.removeHighlight(id); pendingHighlightId = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightNoteDialog(
    highlight: ch.lkmc.kararead.data.model.Highlight,
    onDismiss: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlight") },
        text = {
            Column {
                highlight.text?.let {
                    Text(
                        "“${it.take(220)}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSaveNote(note); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDelete,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightsSheet(
    highlights: List<ch.lkmc.kararead.data.model.Highlight>,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Highlights", style = MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            highlights.forEach { hl ->
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(hl.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            hl.text ?: "(highlight)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        hl.note?.takeIf { it.isNotBlank() }?.let { note ->
                            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { onDelete(hl.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Delete highlight")
                    }
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun ListenBar(
    speech: ch.lkmc.kararead.tts.SpeechState,
    rate: Float,
    onCycleRate: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onVoice: (() -> Unit)?,
    onStop: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.Replay10, contentDescription = "Previous sentence")
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (speech.speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (speech.speaking) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.Forward10, contentDescription = "Next sentence")
            }
            Text(
                text = "Listening · ${(speech.index + 1).coerceAtMost(speech.total)}/${speech.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.TextButton(
                onClick = onCycleRate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Text(
                    formatSpeechRate(rate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (onVoice != null) {
                IconButton(onClick = onVoice) {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Choose voice")
                }
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<ch.lkmc.kararead.tts.VoiceInfo>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Voice", style = MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.heightIn(max = 420.dp),
            ) {
                items(voices, key = { it.id }) { voice ->
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(voice.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = voice.id == selectedId,
                            onClick = { onPick(voice.id) },
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(voice.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                voice.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Narration speeds the rate button cycles through. */
private val SPEECH_RATES = listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f)

/** Next speed in the cycle, snapping to the nearest current step first. */
private fun nextSpeechRate(current: Float): Float {
    val nearest = SPEECH_RATES.minByOrNull { kotlin.math.abs(it - current) } ?: 1.0f
    val idx = SPEECH_RATES.indexOf(nearest)
    return SPEECH_RATES[(idx + 1) % SPEECH_RATES.size]
}

/** Compact label, e.g. "1×" or "1.25×". */
private fun formatSpeechRate(rate: Float): String {
    val s = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
    return "$s×"
}

private tailrec fun android.content.Context.findVolumeKeyController(): ch.lkmc.kararead.VolumeKeyController? =
    when (this) {
        is ch.lkmc.kararead.VolumeKeyController -> this
        is android.content.ContextWrapper -> baseContext.findVolumeKeyController()
        else -> null
    }

@Composable
private fun LocalContentColorSafe(): Color = MaterialTheme.colorScheme.onSurfaceVariant

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

private fun shareUrl(context: android.content.Context, url: String, title: String?) {
    shareText(context, url, title)
}

private fun shareText(context: android.content.Context, text: String, title: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}
