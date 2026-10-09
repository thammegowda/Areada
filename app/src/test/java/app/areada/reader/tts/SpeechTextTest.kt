package app.areada.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpeechTextTest {
    @Test
    fun normalizesWhitespaceLikeTheWebViewIndex() {
        assertEquals(
            "Chapter One First sentence. Second sentence.",
            normalizeSpeechText(
                "  Chapter\u00A0One\n\n First\t sentence. \r\n Second sentence.  ",
            ),
        )
    }

    @Test
    fun segmentsTextIntoSentences() {
        val segments = segmentSpeechText(
            "One sentence. Another sentence! Is this the third? Yes.",
            Locale.US,
        )

        assertEquals(
            listOf(
                "One sentence.",
                "Another sentence!",
                "Is this the third?",
                "Yes.",
            ),
            segments.map { it.text },
        )
        segments.forEach { segment ->
            val normalized =
                normalizeSpeechText("One sentence. Another sentence! Is this the third? Yes.")
            assertEquals(segment.text, normalized.substring(segment.start, segment.end))
        }
    }

    @Test
    fun hardSplitsTextBelowEngineLimit() {
        val segments = segmentSpeechText(
            "word ".repeat(80),
            Locale.US,
            maxCharacters = 40,
        )

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 40 })
        assertEquals(
            "word ".repeat(80).trim(),
            segments.joinToString(" ") { it.text },
        )
    }

    @Test
    fun returnsNoSegmentsForBlankText() {
        assertEquals(emptyList<SpeechSegment>(), segmentSpeechText(" \n\t ", Locale.US))
    }

    @Test
    fun repeatedSentencesRetainDistinctDocumentOffsets() {
        val source = "Again. Again."
        val segments = segmentSpeechText(source, Locale.US)

        assertEquals(listOf(0, 7), segments.map { it.start })
        assertEquals(listOf(6, 13), segments.map { it.end })
    }
}
