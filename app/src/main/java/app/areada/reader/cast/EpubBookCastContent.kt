package app.areada.reader.cast

import app.areada.data.reader.ReaderDocument
import app.areada.data.reader.ReaderPreferences
import app.areada.reader.epub.EpubBook
import app.areada.reader.epub.EpubEngine
import app.areada.reader.tts.normalizeSpeechText
import app.areada.reader.tts.segmentSpeechText
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
    override val narrationAvailable: Boolean = true
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
    private val chapterPayloads = ConcurrentHashMap<String, BookCastChapterPayload>()
    private val renderLock = Any()
    private val narrationLocale = language
        .takeUnless { tag -> tag.isBlank() || tag == "und" }
        ?.let(Locale::forLanguageTag)
        ?: Locale.getDefault()

    override fun loadChapterHtml(chapterId: String): String? =
        loadChapterPayload(chapterId)?.html

    override fun loadChapterNarration(chapterId: String): BookCastNarration? =
        loadChapterPayload(chapterId)?.narration

    private fun loadChapterPayload(chapterId: String): BookCastChapterPayload? {
        chapterPayloads[chapterId]?.let { return it }
        val chapterIndex = chapterIndexById[chapterId] ?: return null
        return synchronized(renderLock) {
            chapterPayloads[chapterId] ?: renderChapter(chapterIndex).also { payload ->
                chapterPayloads[chapterId] = payload
            }
        }
    }

    private fun renderChapter(chapterIndex: Int): BookCastChapterPayload {
        val rendered = runBlocking {
            EpubEngine.render(
                book = book,
                chapterIndex = chapterIndex,
                preferences = ReaderPreferences(),
            )
        }
        val html = sanitizeBookCastHtml(
            renderedHtml = rendered.html,
            fallbackTitle = chapters[chapterIndex].title,
        )
        val readableText = Jsoup.parseBodyFragment(html).body().text()
        val narration = BookCastNarration(
            language = narrationLocale.toLanguageTag(),
            chunks = buildBookCastNarrationChunks(
                sanitizedHtml = html,
                readableText = readableText,
                locale = narrationLocale,
            ),
        )
        return BookCastChapterPayload(
            html = html,
            narration = narration,
        )
    }
}

private data class BookCastChapterPayload(
    val html: String,
    val narration: BookCastNarration,
)

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

internal fun buildBookCastNarrationChunks(
    sanitizedHtml: String,
    readableText: String,
    locale: Locale,
    targetCharacters: Int = TargetNarrationChunkCharacters,
    maxCharacters: Int = MaxNarrationChunkCharacters,
): List<BookCastNarrationChunk> {
    require(targetCharacters in 1..maxCharacters) {
        "Narration chunk target must be within the maximum"
    }
    val text = normalizeSpeechText(readableText)
    if (text.isEmpty()) {
        return emptyList()
    }
    val sentences = segmentSpeechText(
        source = text,
        locale = locale,
        maxCharacters = maxCharacters,
    )
    if (sentences.isEmpty()) {
        return emptyList()
    }
    val paragraphEnds = paragraphEndOffsets(sanitizedHtml, text)
    val chunks = mutableListOf<BookCastNarrationChunk>()
    var chunkStart = sentences.first().start
    var chunkEnd = chunkStart

    fun finishChunk() {
        if (chunkEnd <= chunkStart) {
            return
        }
        chunks += BookCastNarrationChunk(
            text = text.substring(chunkStart, chunkEnd),
            start = chunkStart,
            end = chunkEnd,
        )
    }

    for ((sentenceIndex, sentence) in sentences.withIndex()) {
        if (
            chunkEnd > chunkStart &&
            sentence.end - chunkStart > maxCharacters
        ) {
            finishChunk()
            chunkStart = sentence.start
        }
        chunkEnd = sentence.end
        val size = chunkEnd - chunkStart
        val paragraphEnded = paragraphEnds.any { end ->
            end in (sentence.end - ParagraphBoundaryTolerance)..(
                sentence.end + ParagraphBoundaryTolerance
            )
        }
        if (size >= maxCharacters || size >= targetCharacters && paragraphEnded) {
            finishChunk()
            chunkStart = sentences
                .getOrNull(sentenceIndex + 1)
                ?.start
                ?: chunkEnd
            chunkEnd = chunkStart
        }
    }
    finishChunk()
    return chunks
}

private fun paragraphEndOffsets(
    sanitizedHtml: String,
    text: String,
): Set<Int> {
    val body = Jsoup.parseBodyFragment(sanitizedHtml).body()
    var searchStart = 0
    return buildSet {
        body.select(NarrationBlockSelector)
            .filter { element ->
                element.parents()
                    .takeWhile { ancestor -> ancestor != body }
                    .none { ancestor -> ancestor.normalName() in NarrationBlockTags }
            }
            .forEach { element ->
                val blockText = normalizeSpeechText(element.text())
                if (blockText.isEmpty()) {
                    return@forEach
                }
                val start = text.indexOf(blockText, startIndex = searchStart)
                if (start >= 0) {
                    val end = start + blockText.length
                    add(end)
                    searchStart = end
                }
            }
    }
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

private val NarrationBlockTags = setOf(
    "blockquote",
    "dd",
    "dt",
    "figcaption",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "li",
    "p",
    "pre",
)
private const val NarrationBlockSelector =
    "blockquote, dd, dt, figcaption, h1, h2, h3, h4, h5, h6, li, p, pre"
private const val TargetNarrationChunkCharacters = 700
private const val MaxNarrationChunkCharacters = 1_200
private const val ParagraphBoundaryTolerance = 1
