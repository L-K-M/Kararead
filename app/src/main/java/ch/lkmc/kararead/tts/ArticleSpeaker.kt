package ch.lkmc.kararead.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import javax.inject.Inject

/** Observable state of article narration. */
data class SpeechState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false,
) {
    val speaking: Boolean get() = active && !paused
}

/** A selectable TTS voice. [id] is the engine's voice name; [label]/[detail] for display. */
data class VoiceInfo(
    val id: String,
    val label: String,
    val detail: String,
)

/**
 * Reads an article's plain text aloud with Android [TextToSpeech]. The text is
 * split into chunks (one utterance each) so progress can be tracked and the user
 * can pause/resume and skip. Android TTS has no true pause, so pause = stop +
 * remember position; resume re-speaks from the current chunk.
 *
 * Not a singleton: owned by the reader's ViewModel, which calls [shutdown] when
 * the reader goes away.
 */
class ArticleSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart: (() -> Unit)? = null

    private var chunks: List<String> = emptyList()

    /** The voice the user prefers (engine voice name); applied once the engine is ready. */
    var preferredVoiceId: String? = null

    /**
     * Narration speed (0.5×–3×). Applied to the engine immediately when set, or
     * deferred to engine-ready. Mirrors [preferredVoiceId].
     */
    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 3.0f)
            tts?.setSpeechRate(field)
        }

    // --- Audio focus & "becoming noisy" (so narration behaves like a player) ---
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.speaking) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss && _state.value.paused) {
                    pausedByFocusLoss = false
                    enqueueFrom(_state.value.index)
                }
            }
        }
    }

    // Pause when headphones are unplugged (or BT audio disconnects), like any
    // media player — so narration doesn't suddenly play out of the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && _state.value.speaking) {
                pause()
            }
        }
    }

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault().takeIf {
                    tts?.isLanguageAvailable(it) == TextToSpeech.LANG_AVAILABLE ||
                        tts?.isLanguageAvailable(it) == TextToSpeech.LANG_COUNTRY_AVAILABLE
                } ?: Locale.ENGLISH
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts?.setSpeechRate(speechRate)
                tts?.setOnUtteranceProgressListener(listener)
                publishVoices()
                applyPreferredVoice()
                pendingStart?.invoke()
                pendingStart = null
            } else {
                _state.update { it.copy(active = false, failed = true) }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    /** Hand back audio focus and stop listening for headset-unplug events. */
    private fun releaseAudio() {
        pausedByFocusLoss = false
        focusRequest?.let { req -> audioManager?.abandonAudioFocusRequest(req) }
        focusRequest = null
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    private fun publishVoices() {
        val current = Locale.getDefault().language
        val list = runCatching {
            tts?.voices.orEmpty()
                .filterNot { it.isNetworkConnectionRequired }
                .filterNot { it.features.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(
                    compareByDescending<android.speech.tts.Voice> { it.locale.language == current }
                        .thenBy { it.locale.displayName }
                        .thenByDescending { it.quality },
                )
                .map { v ->
                    VoiceInfo(
                        id = v.name,
                        label = v.locale.displayName.ifBlank { v.name },
                        detail = v.name,
                    )
                }
                // Some engines expose multiple voices with the same name; keep one
                // per id so the picker's list keys stay unique (else it crashes).
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
        _voices.value = list
    }

    private fun applyPreferredVoice() {
        val id = preferredVoiceId ?: return
        tts?.voices?.firstOrNull { it.name == id }?.let { tts?.voice = it }
    }

    /** Switch the narration voice; re-speaks the current sentence so it takes effect. */
    fun setVoice(id: String) {
        preferredVoiceId = id
        tts?.voices?.firstOrNull { it.name == id }?.let { tts?.voice = it }
        val s = _state.value
        if (s.active && !s.paused) enqueueFrom(s.index)
    }

    /** Change narration speed; re-speaks the current sentence so it takes effect now. */
    fun changeSpeechRate(rate: Float) {
        speechRate = rate // setter applies to the engine
        val s = _state.value
        if (s.active && !s.paused) enqueueFrom(s.index)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.toIntOrNull()?.let { i ->
                _state.update { it.copy(index = i) }
            }
        }

        override fun onDone(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            if (i >= chunks.lastIndex) {
                releaseAudio()
                _state.update { SpeechState() } // finished
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            // Let the queue continue; mark failed only if nothing has played.
        }
    }

    /**
     * Begin (or restart) narration of [text], starting near the reader's current
     * position ([startFraction], 0..1) rather than always at the top.
     */
    fun start(text: String?, startFraction: Float = 0f) {
        val prepared = chunkText(text)
        if (prepared.isEmpty()) {
            _state.update { it.copy(failed = true) }
            return
        }
        chunks = prepared
        val startIndex = (startFraction.coerceIn(0f, 1f) * prepared.lastIndex)
            .toInt().coerceIn(0, prepared.lastIndex)
        val begin = { enqueueFrom(startIndex) }
        if (ready) begin() else { pendingStart = begin; ensureEngine() }
    }

    fun togglePlayPause() {
        val s = _state.value
        if (!s.active) return
        if (s.paused) enqueueFrom(s.index) else pause()
    }

    private fun pause() {
        tts?.stop()
        _state.update { it.copy(paused = true) }
    }

    fun skipBy(delta: Int) {
        val s = _state.value
        if (!s.active) return
        enqueueFrom((s.index + delta).coerceIn(0, chunks.lastIndex))
    }

    fun stop() {
        tts?.stop()
        chunks = emptyList()
        releaseAudio()
        _state.value = SpeechState()
    }

    private fun enqueueFrom(start: Int) {
        val engine = tts ?: return
        // Claim audio focus (so other media ducks/pauses) and watch for the
        // headset being pulled, then begin speaking.
        pausedByFocusLoss = false
        requestAudioFocus()
        registerNoisy()
        engine.stop()
        for (i in start..chunks.lastIndex) {
            val mode = if (i == start) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunks[i], mode, null, i.toString())
        }
        _state.value = SpeechState(active = true, paused = false, index = start, total = chunks.size)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        releaseAudio()
        _state.value = SpeechState()
    }

    companion object {
        /** Split into sentence-ish chunks short enough to track and skip. */
        fun chunkText(text: String?): List<String> {
            val clean = text?.trim().orEmpty()
            if (clean.isEmpty()) return emptyList()
            // Break on sentence terminators followed by whitespace, and on blank lines.
            return clean
                .split(Regex("(?<=[.!?])\\s+|\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
