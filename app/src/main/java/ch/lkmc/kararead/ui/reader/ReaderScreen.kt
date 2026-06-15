package ch.lkmc.kararead.ui.reader

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val ttsVoiceId by viewModel.ttsVoiceId.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val highlightsJson by viewModel.highlightsJson.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var chromeVisible by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showVoicePicker by remember { mutableStateOf(false) }
    val pager = remember { ReaderPager() }

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
                    onScrollDirection = { up -> chromeVisible = up || state.progress < 0.05f },
                    onTap = { chromeVisible = !chromeVisible },
                    onSelection = { text, start, end -> viewModel.addHighlight(text, start, end) },
                    onHighlightTap = { id -> pendingDeleteId = id },
                    highlightsJson = highlightsJson,
                    pager = pager,
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
                    IconButton(onClick = viewModel::toggleArchived) {
                        Icon(
                            if (state.archived) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (state.archived) "Mark unread" else "Mark as read",
                            tint = if (state.archived) MaterialTheme.colorScheme.primary
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
            onDelete = viewModel::removeHighlight,
        )
    }

    pendingDeleteId?.let { id ->
        val hl = viewModel.highlight(id)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Remove highlight?") },
            text = { hl?.text?.let { Text("“${it.take(140)}”") } },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.removeHighlight(id); pendingDeleteId = null
                }) { Text("Remove") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightsSheet(
    highlights: List<ch.lkmc.kararead.data.model.Highlight>,
    onDismiss: () -> Unit,
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
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        hl.text ?: "(highlight)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
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
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}
