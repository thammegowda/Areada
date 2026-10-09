package app.areada.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ReaderTtsStatus {
    INITIALIZING,
    READY,
    PLAYING,
    PAUSED,
    ERROR,
}

internal enum class ReaderTtsError {
    INITIALIZATION_FAILED,
    NO_OFFLINE_VOICE,
    NO_READABLE_TEXT,
    SYNTHESIS_FAILED,
}

internal data class ReaderTtsState(
    val status: ReaderTtsStatus = ReaderTtsStatus.INITIALIZING,
    val contentKey: String? = null,
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val currentSentence: String = "",
    val sentenceStart: Int = -1,
    val sentenceEnd: Int = -1,
    val wordStart: Int = -1,
    val wordEnd: Int = -1,
    val sessionActive: Boolean = false,
    val completionCount: Int = 0,
    val error: ReaderTtsError? = null,
) {
    val isPlaying: Boolean
        get() = status == ReaderTtsStatus.PLAYING

    val isPaused: Boolean
        get() = status == ReaderTtsStatus.PAUSED

    val canPlay: Boolean
        get() = sentenceCount > 0 && status != ReaderTtsStatus.INITIALIZING
}

internal class AndroidTtsController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (
            change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pause()
        }
    }
    private val audioFocusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
        } else {
            null
        }

    private val _state = MutableStateFlow(ReaderTtsState())
    val state: StateFlow<ReaderTtsState> = _state.asStateFlow()

    private var textToSpeech: TextToSpeech? = null
    private var initialized = false
    private var released = false
    private var pendingPlay = false
    private var generation = 0
    private var segments: List<SpeechSegment> = emptyList()
    private var locale: Locale = Locale.getDefault()
    private var speechRate = 1f

    fun configure(nextLocale: Locale, nextSpeechRate: Float) {
        runOnMain {
            val cleanRate = nextSpeechRate.coerceIn(0.5f, 2f)
            val localeChanged = locale.toLanguageTag() != nextLocale.toLanguageTag()
            locale = nextLocale
            speechRate = cleanRate
            textToSpeech?.setSpeechRate(cleanRate)

            if (initialized && localeChanged) {
                val resume = _state.value.isPlaying
                stopSynthesis(keepPosition = true)
                if (configureOfflineVoice()) {
                    _state.value = _state.value.copy(
                        status = ReaderTtsStatus.READY,
                        error = null,
                    )
                    if (resume) {
                        play()
                    }
                }
            }
        }
    }

    fun setContent(
        contentKey: String,
        text: String,
        startFraction: Float,
        autoPlay: Boolean = false,
    ) {
        runOnMain {
            if (_state.value.contentKey == contentKey) {
                if (autoPlay) {
                    play()
                }
                return@runOnMain
            }

            stopSynthesis(keepPosition = false)
            segments = segmentSpeechText(text, locale)
            val startIndex = if (segments.isEmpty()) {
                0
            } else {
                (startFraction.coerceIn(0f, 1f) * segments.size)
                    .roundToInt()
                    .coerceIn(0, segments.lastIndex)
            }
            _state.value = _state.value.copy(
                status = if (initialized) ReaderTtsStatus.READY else ReaderTtsStatus.INITIALIZING,
                contentKey = contentKey,
                sentenceIndex = startIndex,
                sentenceCount = segments.size,
                currentSentence = segments.getOrNull(startIndex)?.text.orEmpty(),
                sentenceStart = segments.getOrNull(startIndex)?.start ?: -1,
                sentenceEnd = segments.getOrNull(startIndex)?.end ?: -1,
                wordStart = -1,
                wordEnd = -1,
                sessionActive = _state.value.sessionActive || autoPlay,
                error = null,
            )
            if (autoPlay) {
                play()
            }
        }
    }

    fun togglePlayback(startFraction: Float? = null) {
        runOnMain {
            when (_state.value.status) {
                ReaderTtsStatus.PLAYING -> pause()
                ReaderTtsStatus.PAUSED -> play()
                else -> {
                    startFraction?.let(::seekToFraction)
                    play()
                }
            }
        }
    }

    fun play() {
        runOnMain {
            _state.value = _state.value.copy(sessionActive = true)
            if (segments.isEmpty()) {
                setError(ReaderTtsError.NO_READABLE_TEXT)
                return@runOnMain
            }
            if (!initialized) {
                pendingPlay = true
                startEngine()
                return@runOnMain
            }
            if (!configureOfflineVoice()) {
                return@runOnMain
            }
            if (!requestAudioFocus()) {
                setError(ReaderTtsError.SYNTHESIS_FAILED)
                return@runOnMain
            }
            speakCurrentSegment()
        }
    }

    private fun startEngine() {
        if (released || textToSpeech != null) {
            return
        }
        _state.value = _state.value.copy(
            status = ReaderTtsStatus.INITIALIZING,
            error = null,
        )
        try {
            textToSpeech = TextToSpeech(appContext) { result ->
                runOnMain {
                    if (released) {
                        return@runOnMain
                    }
                    if (result != TextToSpeech.SUCCESS) {
                        initialized = false
                        textToSpeech?.shutdown()
                        textToSpeech = null
                        setError(ReaderTtsError.INITIALIZATION_FAILED)
                        return@runOnMain
                    }

                    initialized = true
                    textToSpeech?.setAudioAttributes(audioAttributes)
                    installProgressListener()
                    if (!configureOfflineVoice()) {
                        return@runOnMain
                    }
                    _state.value = _state.value.copy(
                        status = ReaderTtsStatus.READY,
                        error = null,
                    )
                    if (pendingPlay) {
                        pendingPlay = false
                        play()
                    }
                }
            }
        } catch (_: RuntimeException) {
            textToSpeech = null
            setError(ReaderTtsError.INITIALIZATION_FAILED)
        }
    }

    fun pause() {
        runOnMain {
            if (!_state.value.isPlaying) {
                return@runOnMain
            }
            pendingPlay = false
            generation += 1
            textToSpeech?.stop()
            abandonAudioFocus()
            _state.value = _state.value.copy(
                status = ReaderTtsStatus.PAUSED,
                wordStart = -1,
                wordEnd = -1,
            )
        }
    }

    fun previousSentence() {
        seekToSentence(_state.value.sentenceIndex - 1)
    }

    fun nextSentence() {
        seekToSentence(_state.value.sentenceIndex + 1)
    }

    fun dismiss() {
        runOnMain {
            stopSynthesis(keepPosition = true)
            _state.value = _state.value.copy(
                status = if (initialized) ReaderTtsStatus.READY else ReaderTtsStatus.INITIALIZING,
                sessionActive = false,
                sentenceStart = -1,
                sentenceEnd = -1,
                error = null,
            )
        }
    }

    fun release() {
        runOnMain {
            if (released) {
                return@runOnMain
            }
            released = true
            pendingPlay = false
            generation += 1
            abandonAudioFocus()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        }
    }

    private fun seekToSentence(index: Int) {
        runOnMain {
            if (segments.isEmpty()) {
                return@runOnMain
            }
            val wasPlaying = _state.value.isPlaying
            generation += 1
            textToSpeech?.stop()
            val cleanIndex = index.coerceIn(0, segments.lastIndex)
            _state.value = _state.value.copy(
                status = if (wasPlaying) ReaderTtsStatus.PLAYING else ReaderTtsStatus.PAUSED,
                sentenceIndex = cleanIndex,
                currentSentence = segments[cleanIndex].text,
                sentenceStart = segments[cleanIndex].start,
                sentenceEnd = segments[cleanIndex].end,
                wordStart = -1,
                wordEnd = -1,
                error = null,
            )
            if (wasPlaying) {
                speakCurrentSegment()
            }
        }
    }

    private fun seekToFraction(fraction: Float) {
        if (segments.isEmpty()) {
            return
        }
        val index = (fraction.coerceIn(0f, 1f) * segments.size)
            .roundToInt()
            .coerceIn(0, segments.lastIndex)
        _state.value = _state.value.copy(
            sentenceIndex = index,
            currentSentence = segments[index].text,
            sentenceStart = segments[index].start,
            sentenceEnd = segments[index].end,
            wordStart = -1,
            wordEnd = -1,
        )
    }

    private fun installProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                runOnMain {
                    if (!matchesCurrentUtterance(parsed)) {
                        return@runOnMain
                    }
                    _state.value = _state.value.copy(
                        status = ReaderTtsStatus.PLAYING,
                        error = null,
                    )
                }
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                runOnMain {
                    if (!matchesCurrentUtterance(parsed)) {
                        return@runOnMain
                    }
                    val sentenceLength = _state.value.currentSentence.length
                    _state.value = _state.value.copy(
                        wordStart = start.coerceIn(0, sentenceLength),
                        wordEnd = end.coerceIn(0, sentenceLength),
                    )
                }
            }

            override fun onDone(utteranceId: String?) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                runOnMain {
                    if (!matchesCurrentUtterance(parsed)) {
                        return@runOnMain
                    }
                    if (_state.value.sentenceIndex < segments.lastIndex) {
                        val nextIndex = _state.value.sentenceIndex + 1
                        _state.value = _state.value.copy(
                            sentenceIndex = nextIndex,
                            currentSentence = segments[nextIndex].text,
                            sentenceStart = segments[nextIndex].start,
                            sentenceEnd = segments[nextIndex].end,
                            wordStart = -1,
                            wordEnd = -1,
                        )
                        speakCurrentSegment()
                    } else {
                        abandonAudioFocus()
                        _state.value = _state.value.copy(
                            status = ReaderTtsStatus.READY,
                            wordStart = -1,
                            wordEnd = -1,
                            completionCount = _state.value.completionCount + 1,
                        )
                    }
                }
            }

            @Deprecated("Deprecated by Android but still called by some engines")
            override fun onError(utteranceId: String?) {
                handleSynthesisError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSynthesisError(utteranceId)
            }
        })
    }

    private fun handleSynthesisError(utteranceId: String?) {
        val parsed = parseUtteranceId(utteranceId) ?: return
        runOnMain {
            if (matchesCurrentUtterance(parsed)) {
                abandonAudioFocus()
                setError(ReaderTtsError.SYNTHESIS_FAILED)
            }
        }
    }

    private fun speakCurrentSegment() {
        if (segments.isEmpty()) {
            setError(ReaderTtsError.NO_READABLE_TEXT)
            return
        }

        val index = _state.value.sentenceIndex.coerceIn(0, segments.lastIndex)
        generation += 1
        val utteranceId = utteranceId(generation, index)
        textToSpeech?.setSpeechRate(speechRate)
        _state.value = _state.value.copy(
            status = ReaderTtsStatus.PLAYING,
            sentenceIndex = index,
            currentSentence = segments[index].text,
            sentenceStart = segments[index].start,
            sentenceEnd = segments[index].end,
            wordStart = -1,
            wordEnd = -1,
            error = null,
        )
        val result = textToSpeech?.speak(
            segments[index].text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (result != TextToSpeech.SUCCESS) {
            abandonAudioFocus()
            setError(ReaderTtsError.SYNTHESIS_FAILED)
        }
    }

    private fun configureOfflineVoice(): Boolean {
        val engine = textToSpeech ?: return false
        if (configureOfflineTtsVoice(engine, locale, speechRate)) {
            return true
        }
        setError(ReaderTtsError.NO_OFFLINE_VOICE)
        return false
    }

    private fun stopSynthesis(keepPosition: Boolean) {
        pendingPlay = false
        generation += 1
        textToSpeech?.stop()
        abandonAudioFocus()
        if (!keepPosition) {
            segments = emptyList()
        }
    }

    private fun setError(error: ReaderTtsError) {
        pendingPlay = false
        _state.value = _state.value.copy(
            status = ReaderTtsStatus.ERROR,
            sessionActive = true,
            wordStart = -1,
            wordEnd = -1,
            error = error,
        )
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(requireNotNull(audioFocusRequest))
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun utteranceId(generation: Int, index: Int): String =
        "areada:$generation:$index"

    private fun parseUtteranceId(utteranceId: String?): Pair<Int, Int>? {
        val parts = utteranceId?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != "areada") {
            return null
        }
        val parsedGeneration = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return parsedGeneration to index
    }

    private fun matchesCurrentUtterance(parsed: Pair<Int, Int>): Boolean =
        parsed.first == generation && parsed.second == _state.value.sentenceIndex

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
