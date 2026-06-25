package ch.lkmc.kararead.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * A single transient message in the stack. [id] is a stable, monotonically
 * increasing key so re-ordering or removal never reuses a composition slot.
 */
data class StackMessage(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Auto-dismiss delay; `null` keeps the message until acted on or dismissed. */
    val durationMillis: Long? = MessageStackState.SHORT_MILLIS,
)

/**
 * Holds the currently visible transient messages. Unlike a [androidx.compose.material3.SnackbarHostState]
 * (which shows one message at a time and queues the rest behind it), this lets
 * several messages live at once so they can be rendered as a visible stack.
 */
class MessageStackState(private val maxVisible: Int = 3) {
    val messages = mutableStateListOf<StackMessage>()
    private var nextId = 0L

    fun show(
        text: String,
        actionLabel: String? = null,
        durationMillis: Long? = SHORT_MILLIS,
        onAction: (() -> Unit)? = null,
    ) {
        // Drop the oldest messages so the stack never grows without bound.
        while (messages.size >= maxVisible) {
            messages.removeAt(0)
        }
        messages.add(
            StackMessage(
                id = nextId++,
                text = text,
                actionLabel = actionLabel,
                onAction = onAction,
                durationMillis = durationMillis,
            ),
        )
    }

    fun dismiss(id: Long) {
        messages.removeAll { it.id == id }
    }

    companion object {
        const val SHORT_MILLIS = 4000L

        /** Longer window for messages offering an undo, so there's time to react. */
        const val ACTION_MILLIS = 6000L
    }
}

/**
 * Renders [state]'s messages as a bottom-anchored vertical stack. Newest sits at
 * the bottom (where a lone snackbar would normally appear); older messages stack
 * above it. Each card animates itself in and out and clears itself from the stack.
 */
@Composable
fun MessageStackHost(
    state: MessageStackState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.messages.forEach { message ->
            MessageCard(
                message = message,
                onRemove = { state.dismiss(message.id) },
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: StackMessage,
    onRemove: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    // Animate in on first composition.
    transitionState.targetState = true

    // Auto-dismiss after the configured delay (if any).
    LaunchedEffect(message.id) {
        message.durationMillis?.let { duration ->
            delay(duration)
            transitionState.targetState = false
        }
    }

    // Once the exit animation has fully run, drop the message from the stack.
    LaunchedEffect(message.id) {
        snapshotFlow { transitionState.isIdle && !transitionState.currentState }
            .first { it }
        onRemove()
    }

    AnimatedVisibility(
        visibleState = transitionState,
        modifier = Modifier.fillMaxWidth(),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Snackbar(
            modifier = Modifier.fillMaxWidth(),
            action = message.actionLabel?.let { label ->
                {
                    TextButton(
                        onClick = {
                            message.onAction?.invoke()
                            transitionState.targetState = false
                        },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = SnackbarDefaults.actionColor,
                        ),
                    ) {
                        Text(label)
                    }
                }
            },
        ) {
            Text(message.text)
        }
    }
}
