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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.kararead.data.model.prettyHost
import ch.lkmc.kararead.ui.components.LoadingState
import ch.lkmc.kararead.ui.components.MessageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var chromeVisible by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
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
                    assetLoader = viewModel.assetLoader,
                    onProgress = viewModel::onProgress,
                    onScrollDirection = { up -> chromeVisible = up || state.progress < 0.05f },
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
        )
    }
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
