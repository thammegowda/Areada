package app.areada.reader.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

internal fun configureOfflineTtsVoice(
    engine: TextToSpeech,
    locale: Locale,
    speechRate: Float,
): Boolean {
    val matchingVoice = engine.voices
        .orEmpty()
        .asSequence()
        .filterNot(Voice::isNetworkConnectionRequired)
        .filter { voice ->
            voice.locale.toLanguageTag().equals(locale.toLanguageTag(), ignoreCase = true) ||
                voice.locale.language.equals(locale.language, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<Voice> { voice -> voice.quality }
                .thenBy { voice -> voice.latency },
        )
        .firstOrNull()

    if (matchingVoice != null) {
        engine.voice = matchingVoice
        engine.setSpeechRate(speechRate)
        return true
    }

    val languageResult = engine.setLanguage(locale)
    val currentVoice = engine.voice
    if (
        languageResult < TextToSpeech.LANG_AVAILABLE ||
        currentVoice?.isNetworkConnectionRequired == true
    ) {
        return false
    }

    engine.setSpeechRate(speechRate)
    return true
}
