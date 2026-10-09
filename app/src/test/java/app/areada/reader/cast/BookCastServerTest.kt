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

        assertEquals(200, catalog.status)
        assertTrue(catalog.body.contains("Quoted \\\"Title\\\""))
        assertEquals("*", catalog.cors)
        assertEquals(200, manifest.status)
        assertTrue(
            manifest.body.contains(
                "\"href\": \"chapters/chapter-2.html?token=$accessToken\"",
            ),
        )
        assertEquals(200, chapter.status)
        assertTrue(chapter.body.contains("Hello from Android"))
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
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return TestResponse(
            status = status,
            body = body,
            cors = connection.getHeaderField("Access-Control-Allow-Origin"),
        ).also {
            connection.disconnect()
        }
    }
}

private data class TestResponse(
    val status: Int,
    val body: String,
    val cors: String?,
)
