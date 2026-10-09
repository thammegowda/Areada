package app.areada.reader.cast

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.areada.reader.tts.configureOfflineTtsVoice
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

internal class AndroidBookCastTtsRenderer(
    context: Context,
) : BookCastAudioRenderer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val synthesisLock = ReentrantLock()
    private val cacheDirectory = File(appContext.cacheDir, "areada/bookcast-tts")
    private val alignmentByCacheKey = ConcurrentHashMap<String, CachedAudioAlignment>()

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var closed = false

    override fun synthesize(
        text: String,
        language: String,
        speechRate: Float,
    ): BookCastAudio = synthesisLock.withLock {
        if (closed) {
            throw BookCastAudioException("Bookcast speech renderer is closed")
        }
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > TextToSpeech.getMaxSpeechInputLength()) {
            throw BookCastAudioException("Narration chunk has an unsupported length")
        }

        val engine = requireEngine()
        val locale = language
            .takeUnless { tag -> tag.isBlank() || tag == "und" }
            ?.let(Locale::forLanguageTag)
            ?: Locale.getDefault()
        val cleanRate = speechRate.coerceIn(MinSpeechRate, MaxSpeechRate)
        if (!configureOfflineTtsVoice(engine, locale, cleanRate)) {
            throw BookCastAudioException(
                "No offline Android voice is installed for ${locale.displayLanguage}",
            )
        }

        cacheDirectory.mkdirs()
        if (!cacheDirectory.isDirectory) {
            throw BookCastAudioException("Unable to create the narration cache")
        }
        val cacheKey = audioCacheKey(
            text = cleanText,
            language = locale.toLanguageTag(),
            speechRate = cleanRate,
            voiceName = engine.voice?.name.orEmpty(),
        )
        val cachedFile = File(cacheDirectory, "$cacheKey.wav")
        val cachedAlignment = alignmentByCacheKey[cacheKey]
        if (
            cachedAlignment != null &&
            cachedFile.isFile &&
            cachedFile.length() > MinimumWaveBytes
        ) {
            cachedFile.setLastModified(System.currentTimeMillis())
            return@withLock cachedFile.toBookCastAudio(cachedAlignment)
        }

        val temporaryFile = File(cacheDirectory, "$cacheKey.${UUID.randomUUID()}.tmp.wav")
        try {
            val trace = synthesizeToFile(
                engine = engine,
                text = cleanText,
                outputFile = temporaryFile,
            )
            if (!temporaryFile.isFile || temporaryFile.length() <= MinimumWaveBytes) {
                throw BookCastAudioException("Android TTS produced an empty audio file")
            }
            if (!temporaryFile.renameTo(cachedFile)) {
                temporaryFile.copyTo(cachedFile, overwrite = true)
            }
            val audioBytes = cachedFile.readBytes()
            val durationMillis = waveDurationMillis(audioBytes)
                ?: trace.estimatedDurationMillis()
                ?: throw BookCastAudioException("Unable to determine narration duration")
            val alignment = CachedAudioAlignment(
                durationMillis = durationMillis,
                spans = trace.alignmentSpans(
                    text = cleanText,
                    durationMillis = durationMillis,
                ),
            )
            alignmentByCacheKey[cacheKey] = alignment
            cachedFile.setLastModified(System.currentTimeMillis())
            pruneCache()
            BookCastAudio(
                bytes = audioBytes,
                contentType = "audio/wav",
                durationMillis = alignment.durationMillis,
                spans = alignment.spans,
            )
        } finally {
            if (temporaryFile.exists() && temporaryFile != cachedFile) {
                temporaryFile.delete()
            }
        }
    }

    override fun close() {
        closed = true
        val engine = textToSpeech
        textToSpeech = null
        if (engine != null) {
            mainHandler.post {
                engine.stop()
                engine.shutdown()
            }
        }
    }

    private fun requireEngine(): TextToSpeech {
        textToSpeech?.let { return it }
        val constructionLatch = CountDownLatch(1)
        val initializationLatch = CountDownLatch(1)
        val engineReference = AtomicReference<TextToSpeech?>()
        val initializationResult = AtomicInteger(TextToSpeech.ERROR)
        val constructionError = AtomicReference<RuntimeException?>()

        mainHandler.post {
            try {
                val engine = TextToSpeech(appContext) { status ->
                    initializationResult.set(status)
                    initializationLatch.countDown()
                }
                engineReference.set(engine)
            } catch (error: RuntimeException) {
                constructionError.set(error)
                initializationLatch.countDown()
            } finally {
                constructionLatch.countDown()
            }
        }

        awaitLatch(
            latch = constructionLatch,
            timeoutSeconds = EngineInitializationTimeoutSeconds,
            timeoutMessage = "Android TTS construction timed out",
        )
        constructionError.get()?.let { error ->
            throw BookCastAudioException("Android TTS could not start", error)
        }
        awaitLatch(
            latch = initializationLatch,
            timeoutSeconds = EngineInitializationTimeoutSeconds,
            timeoutMessage = "Android TTS initialization timed out",
        )

        val engine = engineReference.get()
            ?: throw BookCastAudioException("Android TTS did not return an engine")
        if (initializationResult.get() != TextToSpeech.SUCCESS) {
            mainHandler.post(engine::shutdown)
            throw BookCastAudioException("Android TTS initialization failed")
        }
        if (closed) {
            mainHandler.post(engine::shutdown)
            throw BookCastAudioException("Bookcast speech renderer is closed")
        }

        textToSpeech = engine
        return engine
    }

    private fun synthesizeToFile(
        engine: TextToSpeech,
        text: String,
        outputFile: File,
    ): SynthesisTrace {
        val utteranceId = "bookcast:${UUID.randomUUID()}"
        val completionLatch = CountDownLatch(1)
        val synthesisError = AtomicReference<String?>()
        val trace = SynthesisTrace()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(completedUtteranceId: String?) = Unit

            override fun onBeginSynthesis(
                completedUtteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (completedUtteranceId == utteranceId) {
                    trace.sampleRate.set(sampleRateInHz)
                    trace.audioFormat.set(audioFormat)
                    trace.channelCount.set(channelCount)
                }
            }

            override fun onAudioAvailable(
                completedUtteranceId: String?,
                audio: ByteArray?,
            ) {
                if (completedUtteranceId == utteranceId && audio != null) {
                    trace.audioBytes.addAndGet(audio.size.toLong())
                }
            }

            override fun onRangeStart(
                completedUtteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                if (completedUtteranceId == utteranceId) {
                    trace.ranges += RawTtsRange(start, end, frame)
                }
            }

            override fun onDone(completedUtteranceId: String?) {
                if (completedUtteranceId == utteranceId) {
                    completionLatch.countDown()
                }
            }

            @Deprecated("Deprecated by Android but still called by some engines")
            override fun onError(completedUtteranceId: String?) {
                reportSynthesisError(completedUtteranceId, "Android TTS synthesis failed")
            }

            override fun onError(
                completedUtteranceId: String?,
                errorCode: Int,
            ) {
                reportSynthesisError(
                    completedUtteranceId,
                    "Android TTS synthesis failed with code $errorCode",
                )
            }

            private fun reportSynthesisError(
                completedUtteranceId: String?,
                message: String,
            ) {
                if (completedUtteranceId == utteranceId) {
                    synthesisError.set(message)
                    completionLatch.countDown()
                }
            }
        })

        val result = engine.synthesizeToFile(
            text,
            Bundle(),
            outputFile,
            utteranceId,
        )
        if (result != TextToSpeech.SUCCESS) {
            throw BookCastAudioException("Android TTS rejected the narration chunk")
        }
        try {
            if (!completionLatch.await(SynthesisTimeoutSeconds, TimeUnit.SECONDS)) {
                engine.stop()
                throw BookCastAudioException("Android TTS synthesis timed out")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            engine.stop()
            throw BookCastAudioException("Android TTS synthesis was interrupted", error)
        }
        synthesisError.get()?.let { message ->
            throw BookCastAudioException(message)
        }
        return trace
    }

    private fun awaitLatch(
        latch: CountDownLatch,
        timeoutSeconds: Long,
        timeoutMessage: String,
    ) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw BookCastAudioException(timeoutMessage)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BookCastAudioException("Android TTS initialization was interrupted", error)
        }
    }

    private fun pruneCache() {
        val files = cacheDirectory
            .listFiles { file -> file.isFile && file.extension == "wav" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            val nextBytes = retainedBytes + file.length().coerceAtLeast(0L)
            if (index >= MaxCachedChunks || nextBytes > MaxCacheBytes) {
                file.delete()
                alignmentByCacheKey.remove(file.nameWithoutExtension)
            } else {
                retainedBytes = nextBytes
            }
        }
    }
}

private fun File.toBookCastAudio(alignment: CachedAudioAlignment): BookCastAudio =
    BookCastAudio(
        bytes = readBytes(),
        contentType = "audio/wav",
        durationMillis = alignment.durationMillis,
        spans = alignment.spans,
    )

private data class CachedAudioAlignment(
    val durationMillis: Long,
    val spans: List<BookCastAudioSpan>,
)

private data class RawTtsRange(
    val start: Int,
    val end: Int,
    val frame: Int,
)

private class SynthesisTrace {
    val sampleRate = AtomicInteger()
    val audioFormat = AtomicInteger()
    val channelCount = AtomicInteger()
    val audioBytes = AtomicLong()
    val ranges: MutableList<RawTtsRange> =
        Collections.synchronizedList(mutableListOf())

    fun estimatedDurationMillis(): Long? {
        val bytesPerSample = when (audioFormat.get()) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return null
        }
        val bytesPerSecond =
            sampleRate.get().toLong() *
                channelCount.get().toLong() *
                bytesPerSample
        if (bytesPerSecond <= 0L || audioBytes.get() <= 0L) {
            return null
        }
        return (audioBytes.get() * 1_000L / bytesPerSecond).coerceAtLeast(1L)
    }

    fun alignmentSpans(
        text: String,
        durationMillis: Long,
    ): List<BookCastAudioSpan> {
        val cleanRanges = synchronized(ranges) {
            ranges
                .asSequence()
                .filter { range ->
                    range.start in text.indices &&
                        range.end in (range.start + 1)..text.length &&
                        range.frame >= 0
                }
                .distinctBy { range -> Triple(range.start, range.end, range.frame) }
                .sortedBy(RawTtsRange::frame)
                .toList()
        }
        val rate = sampleRate.get()
        if (cleanRanges.size >= 2 && rate > 0) {
            return cleanRanges.mapIndexed { index, range ->
                val startMillis =
                    (range.frame.toLong() * 1_000L / rate)
                        .coerceIn(0L, (durationMillis - 1L).coerceAtLeast(0L))
                val endMillis = cleanRanges
                    .getOrNull(index + 1)
                    ?.let { next ->
                        next.frame.toLong() * 1_000L / rate
                    }
                    ?.coerceIn(startMillis, durationMillis)
                    ?: durationMillis
                BookCastAudioSpan(
                    start = range.start,
                    end = range.end,
                    startMillis = startMillis,
                    endMillis = endMillis
                        .coerceAtLeast(startMillis + 1L)
                        .coerceAtMost(durationMillis),
                )
            }
        }
        return estimatedWordSpans(text, durationMillis)
    }
}

private fun estimatedWordSpans(
    text: String,
    durationMillis: Long,
): List<BookCastAudioSpan> =
    Regex("""\S+""")
        .findAll(text)
        .map { match ->
            val startMillis =
                (durationMillis * match.range.first / text.length.coerceAtLeast(1))
                    .coerceIn(0L, (durationMillis - 1L).coerceAtLeast(0L))
            val endOffset = match.range.last + 1
            val endMillis =
                (durationMillis * endOffset / text.length.coerceAtLeast(1))
                    .coerceIn(startMillis, durationMillis)
            BookCastAudioSpan(
                start = match.range.first,
                end = endOffset,
                startMillis = startMillis,
                endMillis = endMillis
                    .coerceAtLeast(startMillis + 1L)
                    .coerceAtMost(durationMillis),
            )
        }
        .toList()

private fun waveDurationMillis(bytes: ByteArray): Long? {
    if (
        bytes.size < MinimumWaveBytes ||
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE"
    ) {
        return null
    }
    val byteRate = littleEndianInt(bytes, 28)
    if (byteRate <= 0) {
        return null
    }
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val chunkName = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
        val chunkSize = littleEndianInt(bytes, offset + 4)
        if (chunkSize < 0) {
            return null
        }
        if (chunkName == "data") {
            return (chunkSize.toLong() * 1_000L / byteRate).coerceAtLeast(1L)
        }
        offset += 8 + chunkSize + (chunkSize and 1)
    }
    return null
}

private fun littleEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int {
    if (offset < 0 || offset + 4 > bytes.size) {
        return -1
    }
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

private fun audioCacheKey(
    text: String,
    language: String,
    speechRate: Float,
    voiceName: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val rateBucket = (speechRate * 100f).roundToInt()
    for (value in listOf(language, rateBucket.toString(), voiceName, text)) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private const val MinSpeechRate = 0.5f
private const val MaxSpeechRate = 2f
private const val EngineInitializationTimeoutSeconds = 12L
private const val SynthesisTimeoutSeconds = 30L
private const val MinimumWaveBytes = 44L
private const val MaxCachedChunks = 256
private const val MaxCacheBytes = 64L * 1024L * 1024L
