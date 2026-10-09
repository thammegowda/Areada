package app.areada.reader.cast

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

internal const val DefaultBookCastPort = 8787

internal data class BookCastChapter(
    val id: String,
    val title: String,
)

internal interface BookCastContent {
    val bookId: String
    val title: String
    val author: String
    val format: String
    val language: String
    val chapters: List<BookCastChapter>

    fun loadChapterHtml(chapterId: String): String?
}

internal class BookCastServer(
    private val content: BookCastContent,
    private val requestedPort: Int = DefaultBookCastPort,
    val accessToken: String = generateAccessToken(),
) : Closeable {
    private val stateLock = Any()
    private val logger = Logger.getLogger(BookCastServer::class.java.name)
    private var serverSocket: ServerSocket? = null
    private var clientExecutor: ExecutorService? = null
    private val clientSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    init {
        require(SafeIdentifier.matches(content.bookId)) {
            "Book cast id must be lowercase kebab-case"
        }
        require(content.chapters.isNotEmpty()) {
            "Book cast requires at least one chapter"
        }
        require(content.chapters.all { SafeIdentifier.matches(it.id) }) {
            "Book cast chapter ids must be lowercase kebab-case"
        }
        require(content.chapters.map(BookCastChapter::id).distinct().size == content.chapters.size) {
            "Book cast chapter ids must be unique"
        }
        require(SafeAccessToken.matches(accessToken)) {
            "Book cast access token is invalid"
        }
    }

    fun start(): Int = synchronized(stateLock) {
        serverSocket
            ?.takeUnless(ServerSocket::isClosed)
            ?.let { return it.localPort }

        val socket = ServerSocket(
            requestedPort,
            RequestBacklog,
            InetAddress.getLoopbackAddress(),
        )
        val executor = Executors.newFixedThreadPool(
            MaxClientThreads,
            BookCastThreadFactory("bookcast-client"),
        )
        val thread = Thread(
            { acceptRequests(socket, executor) },
            "bookcast-accept",
        ).apply {
            isDaemon = true
        }

        serverSocket = socket
        clientExecutor = executor
        thread.start()
        socket.localPort
    }

    override fun close() {
        val socket: ServerSocket?
        val executor: ExecutorService?
        synchronized(stateLock) {
            socket = serverSocket
            executor = clientExecutor
            serverSocket = null
            clientExecutor = null
        }
        runCatching { socket?.close() }
            .onFailure { error -> logger.log(Level.WARNING, "Unable to close book cast socket", error) }
        clientSockets.forEach { client ->
            runCatching { client.close() }
                .onFailure { error ->
                    logger.log(Level.FINE, "Unable to close book cast client", error)
                }
        }
        clientSockets.clear()
        executor?.shutdownNow()
    }

    private fun acceptRequests(
        socket: ServerSocket,
        executor: ExecutorService,
    ) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                clientSockets += client
                try {
                    executor.execute {
                        try {
                            client.use(::handleClient)
                        } finally {
                            clientSockets -= client
                        }
                    }
                } catch (error: RuntimeException) {
                    clientSockets -= client
                    client.close()
                    if (!socket.isClosed) {
                        throw error
                    }
                }
            } catch (error: SocketException) {
                if (!socket.isClosed) {
                    logger.log(Level.WARNING, "Book cast socket failed", error)
                }
                return
            } catch (error: IOException) {
                if (!socket.isClosed) {
                    logger.log(Level.WARNING, "Unable to accept book cast request", error)
                }
            } catch (error: RuntimeException) {
                if (!socket.isClosed) {
                    logger.log(Level.WARNING, "Unable to dispatch book cast request", error)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = SocketReadTimeoutMillis
        try {
            val request = readRequest(socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII))
            writeResponse(socket, route(request))
        } catch (error: BadRequestException) {
            writeResponse(socket, response(400, "Bad Request", "text/plain; charset=utf-8", error.message.orEmpty()))
        } catch (error: Exception) {
            logger.log(Level.WARNING, "Book cast request failed", error)
            runCatching {
                writeResponse(
                    socket,
                    response(
                        500,
                        "Internal Server Error",
                        "text/plain; charset=utf-8",
                        "Unable to serve this book",
                    ),
                )
            }.onFailure { responseError ->
                logger.log(Level.FINE, "Unable to return book cast error response", responseError)
            }
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.method == "OPTIONS") {
            return response(204, "No Content", "text/plain; charset=utf-8", "")
        }
        if (request.method != "GET") {
            return response(
                405,
                "Method Not Allowed",
                "text/plain; charset=utf-8",
                "Only GET and OPTIONS are supported",
                extraHeaders = mapOf("Allow" to "GET, OPTIONS"),
            )
        }
        if (
            request.path != "/v1/health" &&
            !tokensMatch(request.accessToken, accessToken)
        ) {
            return response(
                401,
                "Unauthorized",
                "text/plain; charset=utf-8",
                "Missing or invalid bookcast access token",
            )
        }

        return when (request.path) {
            "/v1/health" -> response(
                200,
                "OK",
                "application/json; charset=utf-8",
                """{"service":"Areada Bookcast","version":1}""",
            )
            "/v1/catalog.json" -> response(
                200,
                "OK",
                "application/json; charset=utf-8",
                catalogJson(),
            )
            "/v1/books/${content.bookId}/manifest.json" -> response(
                200,
                "OK",
                "application/json; charset=utf-8",
                manifestJson(),
            )
            "/v1/books/${content.bookId}/cover.svg" -> response(
                200,
                "OK",
                "image/svg+xml; charset=utf-8",
                coverSvg(),
            )
            else -> chapterResponse(request.path) ?: response(
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                "Not found",
            )
        }
    }

    private fun chapterResponse(path: String): HttpResponse? {
        val prefix = "/v1/books/${content.bookId}/chapters/"
        if (!path.startsWith(prefix) || !path.endsWith(".html")) {
            return null
        }
        val chapterId = path.removePrefix(prefix).removeSuffix(".html")
        if (content.chapters.none { it.id == chapterId }) {
            return response(404, "Not Found", "text/plain; charset=utf-8", "Chapter not found")
        }
        val html = content.loadChapterHtml(chapterId)
            ?: return response(404, "Not Found", "text/plain; charset=utf-8", "Chapter not found")
        return response(200, "OK", "text/html; charset=utf-8", html)
    }

    private fun catalogJson(): String =
        """
        {
          "books": [
            {
              "id": ${jsonString(content.bookId)},
              "title": ${jsonString(content.title)},
              "author": ${jsonString(content.author)},
              "format": ${jsonString(content.format)},
              "chapterCount": ${content.chapters.size},
              "cover": ${jsonString("books/${content.bookId}/cover.svg?token=$accessToken")},
              "manifest": ${jsonString("books/${content.bookId}/manifest.json?token=$accessToken")}
            }
          ]
        }
        """.trimIndent()

    private fun manifestJson(): String {
        val chapters = content.chapters.joinToString(",\n") { chapter ->
            """
            {
              "id": ${jsonString(chapter.id)},
              "title": ${jsonString(chapter.title)},
              "href": ${jsonString("chapters/${chapter.id}.html?token=$accessToken")}
            }
            """.trimIndent().prependIndent("    ")
        }
        return """
            {
              "id": ${jsonString(content.bookId)},
              "title": ${jsonString(content.title)},
              "author": ${jsonString(content.author)},
              "language": ${jsonString(content.language)},
              "chapters": [
            $chapters
              ]
            }
        """.trimIndent()
    }

    private fun coverSvg(): String {
        val title = xmlText(content.title)
        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="600" height="900" viewBox="0 0 600 900">
              <defs>
                <linearGradient id="cover" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#17273d"/>
                  <stop offset="1" stop-color="#7a5b48"/>
                </linearGradient>
              </defs>
              <rect width="600" height="900" fill="url(#cover)"/>
              <circle cx="455" cy="180" r="72" fill="#f4d69d" opacity=".78"/>
              <text x="54" y="610" fill="#fff" font-family="serif" font-size="48">$title</text>
              <text x="58" y="682" fill="#fff" opacity=".68" font-family="sans-serif" font-size="22">CAST FROM AREADA</text>
            </svg>
        """.trimIndent()
    }

    private fun readRequest(reader: BufferedReader): HttpRequest {
        val requestLine = readHttpLine(reader)
            ?: throw BadRequestException("Missing request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            throw BadRequestException("Invalid request line")
        }

        var headerCount = 0
        while (true) {
            val line = readHttpLine(reader)
                ?: throw BadRequestException("Incomplete request headers")
            if (line.isEmpty()) {
                break
            }
            headerCount += 1
            if (headerCount > MaxHeaderCount) {
                throw BadRequestException("Request headers are too large")
            }
        }

        val target = runCatching { URI(parts[1]) }
            .getOrElse { throw BadRequestException("Invalid request target") }
        val path = target.path
            ?.takeIf(String::isNotBlank)
            ?: throw BadRequestException("Invalid request target")
        return HttpRequest(
            method = parts[0].uppercase(),
            path = path,
            accessToken = queryParameter(target.rawQuery, "token"),
        )
    }

    private fun writeResponse(
        socket: Socket,
        response: HttpResponse,
    ) {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val headerText = buildString {
            append("HTTP/1.1 ${response.statusCode} ${response.reason}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n")
            response.extraHeaders.forEach { (name, value) ->
                append("$name: $value\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        socket.getOutputStream().use { output ->
            output.write(headerText)
            output.write(body)
            output.flush()
        }
    }

    private fun response(
        statusCode: Int,
        reason: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = HttpResponse(
        statusCode = statusCode,
        reason = reason,
        contentType = contentType,
        body = body,
        extraHeaders = extraHeaders,
    )
}

private data class HttpRequest(
    val method: String,
    val path: String,
    val accessToken: String?,
)

private data class HttpResponse(
    val statusCode: Int,
    val reason: String,
    val contentType: String,
    val body: String,
    val extraHeaders: Map<String, String>,
)

private class BadRequestException(message: String) : IllegalArgumentException(message)

private fun readHttpLine(reader: BufferedReader): String? {
    val line = StringBuilder()
    while (true) {
        val next = reader.read()
        if (next < 0) {
            return if (line.isEmpty()) {
                null
            } else {
                throw BadRequestException("Incomplete HTTP line")
            }
        }
        if (next == '\n'.code) {
            if (line.lastOrNull() == '\r') {
                line.setLength(line.length - 1)
            }
            return line.toString()
        }
        if (line.length >= MaxLineLength) {
            throw BadRequestException("HTTP line is too long")
        }
        line.append(next.toChar())
    }
}

private class BookCastThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun xmlText(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun queryParameter(
    rawQuery: String?,
    name: String,
): String? =
    rawQuery
        ?.split('&')
        ?.asSequence()
        ?.map { parameter -> parameter.split('=', limit = 2) }
        ?.firstOrNull { parts -> parts.firstOrNull() == name }
        ?.getOrNull(1)

private fun tokensMatch(
    candidate: String?,
    expected: String,
): Boolean {
    if (candidate == null) {
        return false
    }
    return MessageDigest.isEqual(
        candidate.toByteArray(StandardCharsets.US_ASCII),
        expected.toByteArray(StandardCharsets.US_ASCII),
    )
}

private fun generateAccessToken(): String {
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val SafeIdentifier = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val SafeAccessToken = Regex("^[a-zA-Z0-9_-]{12,128}$")
private const val RequestBacklog = 8
private const val MaxClientThreads = 4
private const val SocketReadTimeoutMillis = 5_000
private const val MaxLineLength = 8_192
private const val MaxHeaderCount = 100
