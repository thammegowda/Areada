package app.areada.reader.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BookCastServerTest {
    private val accessToken = "test-token-123"
    private lateinit var server: BookCastServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = BookCastServer(
            content = object : BookCastContent {
                override val bookId = "test-book"
                override val title = "Quoted \"Title\""
                override val author = "Test Author"
                override val format = "EPUB"
                override val language = "en"
                override val narrationAvailable = true
                override val chapters = listOf(
                    BookCastChapter("chapter-1", "One"),
                    BookCastChapter("chapter-2", "Two"),
                )

                override fun loadChapterHtml(chapterId: String): String? =
                    when (chapterId) {
                        "chapter-1" -> "<h1>One</h1><p>Hello from Android.</p>"
                        "chapter-2" -> "<h1>Two</h1><p>Another chapter.</p>"
                        else -> null
                    }

                override fun loadChapterNarration(chapterId: String): BookCastNarration? =
                    if (chapterId == "chapter-1") {
                        BookCastNarration(
                            language = "en-US",
                            chunks = listOf(
                                BookCastNarrationChunk(
                                    text = "Hello from Android.",
                                    start = 10,
                                    end = 29,
                                ),
                            ),
                        )
                    } else {
                        null
                    }
            },
            audioRenderer = object : BookCastAudioRenderer {
                override fun synthesize(
                    text: String,
                    language: String,
                    speechRate: Float,
                ): BookCastAudio =
                    BookCastAudio(
                        bytes = "RIFF-test-audio".toByteArray(),
                        contentType = "audio/wav",
                        durationMillis = 1_000,
                        spans = listOf(
                            BookCastAudioSpan(
                                start = 0,
                                end = 5,
                                startMillis = 0,
                                endMillis = 400,
                            ),
                        ),
                    )

                override fun close() = Unit
            },
            requestedPort = 0,
            accessToken = accessToken,
        )
        port = server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun servesCatalogManifestAndChapterWithCors() {
        val catalog = request("/v1/catalog.json")
        val manifest = request("/v1/books/test-book/manifest.json")
        val chapter = request("/v1/books/test-book/chapters/chapter-1.html")
        val narration = request("/v1/books/test-book/narration/chapter-1.json")
        val alignment = request("/v1/books/test-book/alignment/chapter-1/0.json")
        val audio = request("/v1/books/test-book/audio/chapter-1/0.wav")
        val audioRange = request(
            "/v1/books/test-book/audio/chapter-1/0.wav",
            rangeHeader = "bytes=5-8",
        )
        val audioSuffix = request(
            "/v1/books/test-book/audio/chapter-1/0.wav",
            rangeHeader = "bytes=-4",
        )
        val invalidRange = request(
            "/v1/books/test-book/audio/chapter-1/0.wav",
            rangeHeader = "bytes=99-100",
        )

        assertEquals(200, catalog.status)
        assertTrue(catalog.body.contains("Quoted \\\"Title\\\""))
        assertEquals("*", catalog.cors)
        assertEquals(200, manifest.status)
        assertTrue(
            manifest.body.contains(
                "\"href\": \"chapters/chapter-2.html?token=$accessToken\"",
            ),
        )
        assertTrue(
            manifest.body.contains(
                "\"narration\": \"narration/chapter-1.json?token=$accessToken\"",
            ),
        )
        assertEquals(200, chapter.status)
        assertTrue(chapter.body.contains("Hello from Android"))
        assertEquals(200, narration.status)
        assertTrue(narration.body.contains("\"chunks\""))
        assertEquals(200, alignment.status)
        assertTrue(alignment.body.contains("\"start\": 10"))
        assertEquals(200, audio.status)
        assertEquals("audio/wav", audio.contentType)
        assertEquals(206, audioRange.status)
        assertEquals("test", audioRange.body)
        assertEquals("bytes 5-8/15", audioRange.contentRange)
        assertEquals(206, audioSuffix.status)
        assertEquals("udio", audioSuffix.body)
        assertEquals(416, invalidRange.status)
        assertEquals("bytes */15", invalidRange.contentRange)
    }

    @Test
    fun rejectsUnknownRoutesAndUnsupportedMethods() {
        assertEquals(404, request("/v1/missing").status)
        assertEquals(405, request("/v1/health", method = "POST").status)
        assertEquals(401, request("/v1/catalog.json", authorized = false).status)
    }

    private fun request(
        path: String,
        method: String = "GET",
        authorized: Boolean = true,
        rangeHeader: String? = null,
    ): TestResponse {
        val separator = if ('?' in path) '&' else '?'
        val requestPath = if (authorized && path != "/v1/health") {
            "$path${separator}token=$accessToken"
        } else {
            path
        }
        val connection = URL("http://127.0.0.1:$port$requestPath")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        rangeHeader?.let { value ->
            connection.setRequestProperty("Range", value)
        }
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return TestResponse(
            status = status,
            body = body,
            cors = connection.getHeaderField("Access-Control-Allow-Origin"),
            contentType = connection.contentType,
            contentRange = connection.getHeaderField("Content-Range"),
        ).also {
            connection.disconnect()
        }
    }
}

private data class TestResponse(
    val status: Int,
    val body: String,
    val cors: String?,
    val contentType: String?,
    val contentRange: String?,
)
