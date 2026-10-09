package app.areada.reader.cast

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EpubBookCastContentTest {
    @Test
    fun removesActiveContentAndUnapprovedAttributes() {
        val html = sanitizeBookCastHtml(
            renderedHtml = """
                <html>
                  <body>
                    <h1 class="book-title" onclick="alert(1)">Safe title</h1>
                    <table background="https://example.invalid/tracker.png">
                      <tr><td colspan="2">Safe text</td></tr>
                    </table>
                    <img src="file:///private/cover.jpg" alt="Cover">
                    <script>alert(1)</script>
                  </body>
                </html>
            """.trimIndent(),
            fallbackTitle = "Fallback",
        )
        val body = Jsoup.parseBodyFragment(html).body()

        assertFalse(body.select("script, img").isNotEmpty())
        assertFalse(body.selectFirst("h1")?.hasAttr("class") == true)
        assertFalse(body.selectFirst("h1")?.hasAttr("onclick") == true)
        assertFalse(body.selectFirst("table")?.hasAttr("background") == true)
        assertEquals("2", body.selectFirst("td")?.attr("colspan"))
        assertTrue(body.text().contains("Safe title"))
        assertTrue(body.text().contains("Safe text"))
    }

    @Test
    fun replacesImageOnlySectionsWithReadableFallback() {
        val html = sanitizeBookCastHtml(
            renderedHtml = "<html><body><img src=\"cover.jpg\"></body></html>",
            fallbackTitle = "Cover",
        )
        val body = Jsoup.parseBodyFragment(html).body()

        assertEquals("Cover", body.selectFirst("h1")?.text())
        assertTrue(body.text().contains("no readable text"))
    }

    @Test
    fun groupsSentencesIntoParagraphAlignedNarrationChunks() {
        val html = """
            <p>First paragraph contains enough words for a useful spoken passage.</p>
            <p>Second paragraph continues the same thought with a little more detail.</p>
            <p>Third paragraph starts a new grouped narration passage for playback.</p>
            <p>Fourth paragraph finishes the sample with stable global text offsets.</p>
        """.trimIndent()
        val text = Jsoup.parseBodyFragment(html).body().text()
        val chunks = buildBookCastNarrationChunks(
            sanitizedHtml = html,
            readableText = text,
            locale = Locale.US,
            targetCharacters = 100,
            maxCharacters = 180,
        )

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { chunk -> chunk.text.length <= 180 })
        chunks.forEach { chunk ->
            assertEquals(chunk.text, text.substring(chunk.start, chunk.end))
        }
        assertEquals(text, chunks.joinToString(" ") { chunk -> chunk.text })
    }
}
