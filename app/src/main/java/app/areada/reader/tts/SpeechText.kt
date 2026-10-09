package app.areada.reader.tts

import java.text.BreakIterator
import java.util.Locale

private const val DefaultMaxSpeechSegmentCharacters = 3_500

internal data class SpeechSegment(
    val text: String,
    val start: Int,
    val end: Int,
)

internal fun segmentSpeechText(
    source: String,
    locale: Locale,
    maxCharacters: Int = DefaultMaxSpeechSegmentCharacters,
): List<SpeechSegment> {
    require(maxCharacters > 0) { "maxCharacters must be positive" }

    val text = normalizeSpeechText(source)
    if (text.isBlank()) {
        return emptyList()
    }

    val iterator = BreakIterator.getSentenceInstance(locale)
    iterator.setText(text)
    val segments = mutableListOf<SpeechSegment>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        appendBoundedSpeechSegment(
            destination = segments,
            source = text,
            start = start,
            end = end,
            maxCharacters = maxCharacters,
        )
        start = end
        end = iterator.next()
    }

    if (segments.isEmpty()) {
        appendBoundedSpeechSegment(
            destination = segments,
            source = text,
            start = 0,
            end = text.length,
            maxCharacters = maxCharacters,
        )
    }
    return segments
}

internal fun normalizeSpeechText(source: String): String =
    source
        .replace('\u00A0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun appendBoundedSpeechSegment(
    destination: MutableList<SpeechSegment>,
    source: String,
    start: Int,
    end: Int,
    maxCharacters: Int,
) {
    var segmentStart = start
    var segmentEnd = end
    while (segmentStart < segmentEnd && source[segmentStart].isWhitespace()) {
        segmentStart += 1
    }
    while (segmentEnd > segmentStart && source[segmentEnd - 1].isWhitespace()) {
        segmentEnd -= 1
    }

    while (segmentStart < segmentEnd) {
        if (segmentEnd - segmentStart <= maxCharacters) {
            destination += SpeechSegment(
                text = source.substring(segmentStart, segmentEnd),
                start = segmentStart,
                end = segmentEnd,
            )
            return
        }

        val hardEnd = segmentStart + maxCharacters
        val preferredEnd = source.lastIndexOf(' ', hardEnd)
            .takeIf { it >= segmentStart + maxCharacters / 2 }
            ?: hardEnd
        destination += SpeechSegment(
            text = source.substring(segmentStart, preferredEnd).trimEnd(),
            start = segmentStart,
            end = preferredEnd,
        )
        segmentStart = preferredEnd
        while (segmentStart < segmentEnd && source[segmentStart].isWhitespace()) {
            segmentStart += 1
        }
    }
}
