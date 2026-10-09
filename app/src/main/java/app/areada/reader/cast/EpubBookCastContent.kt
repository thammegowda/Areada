package app.areada.reader.cast

import app.areada.data.reader.ReaderDocument
import app.areada.data.reader.ReaderPreferences
import app.areada.reader.epub.EpubBook
import app.areada.reader.epub.EpubEngine
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class EpubBookCastContent(
    document: ReaderDocument,
    private val book: EpubBook,
) : BookCastContent {
    override val bookId: String =
        "phone-${document.uriString.hashCode().toUInt().toString(16)}"
    override val title: String = book.title.ifBlank { document.title }
    override val author: String = book.author ?: "Areada phone"
    override val format: String = "EPUB"
    override val language: String = book.language
    override val chapters: List<BookCastChapter> =
        book.chapters.mapIndexed { index, chapter ->
            BookCastChapter(
                id = "chapter-${index + 1}",
                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
            )
        }

    private val chapterIndexById = chapters
        .mapIndexed { index, chapter -> chapter.id to index }
        .toMap()
    private val htmlByChapterId = ConcurrentHashMap<String, String>()
    private val renderLock = Any()

    override fun loadChapterHtml(chapterId: String): String? {
        htmlByChapterId[chapterId]?.let { return it }
        val chapterIndex = chapterIndexById[chapterId] ?: return null
        return synchronized(renderLock) {
            htmlByChapterId[chapterId] ?: renderChapter(chapterIndex).also { html ->
                htmlByChapterId[chapterId] = html
            }
        }
    }

    private fun renderChapter(chapterIndex: Int): String {
        val rendered = runBlocking {
            EpubEngine.render(
                book = book,
                chapterIndex = chapterIndex,
                preferences = ReaderPreferences(),
            )
        }
        return sanitizeBookCastHtml(
            renderedHtml = rendered.html,
            fallbackTitle = chapters[chapterIndex].title,
        )
    }
}

internal fun sanitizeBookCastHtml(
    renderedHtml: String,
    fallbackTitle: String,
): String {
    val document = Jsoup.parse(renderedHtml)
    document.select(
        "script, style, link, base, meta, iframe, object, embed, form, input, " +
            "button, textarea, select, option, img, svg, picture, source, " +
            "video, audio, canvas",
    ).remove()
    document.select("*").forEach { element ->
        element.attributes().asList()
            .filterNot { attribute ->
                attribute.key.lowercase(Locale.ROOT) in SafeHtmlAttributes
            }
            .forEach { attribute -> element.removeAttr(attribute.key) }
    }

    val body = document.body()
    if (body.text().isBlank()) {
        body.empty()
        body.appendElement("h1").text(fallbackTitle)
        body.appendElement("p").text("This EPUB section contains no readable text.")
    }
    return body.html()
}

private val SafeHtmlAttributes = setOf(
    "colspan",
    "dir",
    "lang",
    "reversed",
    "rowspan",
    "start",
    "type",
)
