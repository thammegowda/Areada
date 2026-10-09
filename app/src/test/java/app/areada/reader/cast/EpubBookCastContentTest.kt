package app.areada.reader.cast

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
