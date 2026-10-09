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

internal data class BookCastNarrationChunk(
    val text: String,
    val start: Int,
    val end: Int,
)

internal data class BookCastNarration(
    val language: String,
    val chunks: List<BookCastNarrationChunk>,
)

internal data class BookCastAudio(
    val bytes: ByteArray,
    val contentType: String,
    val durationMillis: Long,
    val spans: List<BookCastAudioSpan>,
)

internal data class BookCastAudioSpan(
    val start: Int,
    val end: Int,
    val startMillis: Long,
    val endMillis: Long,
)

internal interface BookCastAudioRenderer : Closeable {
    fun synthesize(
        text: String,
        language: String,
        speechRate: Float,
    ): BookCastAudio
}

internal class BookCastAudioException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface BookCastContent {
    val bookId: String
    val title: String
    val author: String
    val format: String
    val language: String
    val chapters: List<BookCastChapter>
    val narrationAvailable: Boolean
        get() = false

    fun loadChapterHtml(chapterId: String): String?

    fun loadChapterNarration(chapterId: String): BookCastNarration? = null
}

internal class BookCastServer(
    private val content: BookCastContent,
    private val audioRenderer: BookCastAudioRenderer? = null,
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
        runCatching { audioRenderer?.close() }
            .onFailure { error ->
                logger.log(Level.WARNING, "Unable to close book cast audio renderer", error)
            }
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
            !tokensMatch(request.queryParameters["token"], accessToken)
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
            else ->
                narrationResponse(request.path)
                    ?: alignmentResponse(request)
                    ?: audioResponse(request)
                    ?: chapterResponse(request.path)
                    ?: response(
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

    private fun narrationResponse(path: String): HttpResponse? {
        val prefix = "/v1/books/${content.bookId}/narration/"
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null
        }
        val chapterId = path.removePrefix(prefix).removeSuffix(".json")
        if (content.chapters.none { chapter -> chapter.id == chapterId }) {
            return response(404, "Not Found", "text/plain; charset=utf-8", "Chapter not found")
        }
        val narration = content.loadChapterNarration(chapterId)
            ?: return response(
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                "Narration is unavailable",
            )
        return response(
            200,
            "OK",
            "application/json; charset=utf-8",
            narrationJson(chapterId, narration),
        )
    }

    private fun audioResponse(request: HttpRequest): HttpResponse? {
        val prefix = "/v1/books/${content.bookId}/audio/"
        if (!request.path.startsWith(prefix) || !request.path.endsWith(".wav")) {
            return null
        }
        val route = parseChunkRoute(request.path, prefix, ".wav")
            ?: return response(404, "Not Found", "text/plain; charset=utf-8", "Audio not found")
        val resolved = resolveNarrationChunk(route)
            ?: return response(404, "Not Found", "text/plain; charset=utf-8", "Narration chunk not found")
        val speechRate = speechRate(request)
            ?: return response(
                400,
                "Bad Request",
                "text/plain; charset=utf-8",
                "Speech rate must be between $MinSpeechRate and $MaxSpeechRate",
            )
        if (audioRenderer == null) {
            return response(
                501,
                "Not Implemented",
                "text/plain; charset=utf-8",
                "Audio narration is unavailable",
            )
        }
        val audio = synthesizeNarrationAudio(
            narration = resolved.narration,
            chunk = resolved.chunk,
            speechRate = speechRate,
        ) ?: return response(
            503,
            "Service Unavailable",
            "text/plain; charset=utf-8",
            "Unable to synthesize narration",
        )
        return rangedAudioResponse(
            audio = audio,
            rangeHeader = request.headers["range"],
        )
    }

    private fun alignmentResponse(request: HttpRequest): HttpResponse? {
        val prefix = "/v1/books/${content.bookId}/alignment/"
        if (!request.path.startsWith(prefix) || !request.path.endsWith(".json")) {
            return null
        }
        val route = parseChunkRoute(request.path, prefix, ".json")
            ?: return response(
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                "Alignment not found",
            )
        val resolved = resolveNarrationChunk(route)
            ?: return response(
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                "Narration chunk not found",
            )
        val speechRate = speechRate(request)
            ?: return response(
                400,
                "Bad Request",
                "text/plain; charset=utf-8",
                "Speech rate must be between $MinSpeechRate and $MaxSpeechRate",
            )
        if (audioRenderer == null) {
            return response(
                501,
                "Not Implemented",
                "text/plain; charset=utf-8",
                "Audio narration is unavailable",
            )
        }
        val audio = synthesizeNarrationAudio(
            narration = resolved.narration,
            chunk = resolved.chunk,
            speechRate = speechRate,
        ) ?: return response(
            503,
            "Service Unavailable",
            "text/plain; charset=utf-8",
            "Unable to synthesize narration",
        )
        return response(
            statusCode = 200,
            reason = "OK",
            contentType = "application/json; charset=utf-8",
            body = alignmentJson(
                chapterId = route.chapterId,
                chunkIndex = route.chunkIndex,
                chunk = resolved.chunk,
                audio = audio,
            ),
        )
    }

    private fun parseChunkRoute(
        path: String,
        prefix: String,
        suffix: String,
    ): BookCastChunkRoute? {
        val parts = path
            .removePrefix(prefix)
            .removeSuffix(suffix)
            .split('/')
        if (parts.size != 2) {
            return null
        }
        val chunkIndex = parts[1].toIntOrNull() ?: return null
        return BookCastChunkRoute(
            chapterId = parts[0],
            chunkIndex = chunkIndex,
        )
    }

    private fun resolveNarrationChunk(
        route: BookCastChunkRoute,
    ): ResolvedNarrationChunk? {
        val narration = content.loadChapterNarration(route.chapterId) ?: return null
        val chunk = narration.chunks.getOrNull(route.chunkIndex) ?: return null
        return ResolvedNarrationChunk(
            narration = narration,
            chunk = chunk,
        )
    }

    private fun speechRate(request: HttpRequest): Float? {
        val speechRate = request.queryParameters["rate"]
            ?.toFloatOrNull()
            ?: DefaultSpeechRate
        return speechRate.takeIf { rate -> rate in MinSpeechRate..MaxSpeechRate }
    }

    private fun synthesizeNarrationAudio(
        narration: BookCastNarration,
        chunk: BookCastNarrationChunk,
        speechRate: Float,
    ): BookCastAudio? {
        val renderer = audioRenderer ?: return null
        return try {
            renderer.synthesize(
                text = chunk.text,
                language = narration.language,
                speechRate = speechRate,
            )
        } catch (error: BookCastAudioException) {
            logger.log(Level.WARNING, "Unable to synthesize book cast audio", error)
            null
        }
    }

    private fun alignmentJson(
        chapterId: String,
        chunkIndex: Int,
        chunk: BookCastNarrationChunk,
        audio: BookCastAudio,
    ): String {
        val spans = audio.spans.joinToString(",\n") { span ->
            """
            {
              "start": ${chunk.start + span.start},
              "end": ${chunk.start + span.end},
              "startMs": ${span.startMillis},
              "endMs": ${span.endMillis}
            }
            """.trimIndent().prependIndent("    ")
        }
        return """
            {
              "chapterId": ${jsonString(chapterId)},
              "chunkIndex": $chunkIndex,
              "durationMs": ${audio.durationMillis},
              "spans": [
            $spans
              ]
            }
        """.trimIndent()
    }

    private fun rangedAudioResponse(
        audio: BookCastAudio,
        rangeHeader: String?,
    ): HttpResponse {
        val totalBytes = audio.bytes.size
        val baseHeaders = mapOf("Accept-Ranges" to "bytes")
        if (rangeHeader.isNullOrBlank()) {
            return binaryResponse(
                statusCode = 200,
                reason = "OK",
                contentType = audio.contentType,
                body = audio.bytes,
                extraHeaders = baseHeaders,
            )
        }

        val range = parseByteRange(rangeHeader, totalBytes)
            ?: return binaryResponse(
                statusCode = 416,
                reason = "Range Not Satisfiable",
                contentType = "text/plain; charset=utf-8",
                body = "Requested audio range is invalid".toByteArray(
                    StandardCharsets.UTF_8,
                ),
                extraHeaders = baseHeaders + (
                    "Content-Range" to "bytes */$totalBytes"
                    ),
            )
        return binaryResponse(
            statusCode = 206,
            reason = "Partial Content",
            contentType = audio.contentType,
            body = audio.bytes.copyOfRange(range.first, range.last + 1),
            extraHeaders = baseHeaders + (
                "Content-Range" to
                    "bytes ${range.first}-${range.last}/$totalBytes"
                ),
        )
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
            val narrationField = if (content.narrationAvailable) {
                ",\n" +
                    "  \"narration\": " +
                    jsonString("narration/${chapter.id}.json?token=$accessToken")
            } else {
                ""
            }
            """
            {
              "id": ${jsonString(chapter.id)},
              "title": ${jsonString(chapter.title)},
              "href": ${jsonString("chapters/${chapter.id}.html?token=$accessToken")}$narrationField
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

    private fun narrationJson(
        chapterId: String,
        narration: BookCastNarration,
    ): String {
        val chunks = narration.chunks.mapIndexed { index, chunk ->
            """
            {
              "index": $index,
              "text": ${jsonString(chunk.text)},
              "start": ${chunk.start},
              "end": ${chunk.end},
              "audio": ${jsonString("audio/$chapterId/$index.wav?token=$accessToken")},
              "alignment": ${jsonString("alignment/$chapterId/$index.json?token=$accessToken")}
            }
            """.trimIndent().prependIndent("    ")
        }.joinToString(",\n")
        return """
            {
              "chapterId": ${jsonString(chapterId)},
              "language": ${jsonString(narration.language)},
              "chunks": [
            $chunks
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
        val headers = linkedMapOf<String, String>()
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
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw BadRequestException("Invalid request header")
            }
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }

        val target = runCatching { URI(parts[1]) }
            .getOrElse { throw BadRequestException("Invalid request target") }
        val path = target.path
            ?.takeIf(String::isNotBlank)
            ?: throw BadRequestException("Invalid request target")
        return HttpRequest(
            method = parts[0].uppercase(),
            path = path,
            queryParameters = queryParameters(target.rawQuery),
            headers = headers,
        )
    }

    private fun writeResponse(
        socket: Socket,
        response: HttpResponse,
    ) {
        val body = response.body
        val headerText = buildString {
            append("HTTP/1.1 ${response.statusCode} ${response.reason}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Range\r\n")
            append("Access-Control-Expose-Headers: Accept-Ranges, Content-Range\r\n")
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
    ): HttpResponse = binaryResponse(
        statusCode = statusCode,
        reason = reason,
        contentType = contentType,
        body = body.toByteArray(StandardCharsets.UTF_8),
        extraHeaders = extraHeaders,
    )

    private fun binaryResponse(
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
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
    val queryParameters: Map<String, String>,
    val headers: Map<String, String>,
)

private data class BookCastChunkRoute(
    val chapterId: String,
    val chunkIndex: Int,
)

private data class ResolvedNarrationChunk(
    val narration: BookCastNarration,
    val chunk: BookCastNarrationChunk,
)

private data class HttpResponse(
    val statusCode: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
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

private fun queryParameters(rawQuery: String?): Map<String, String> =
    rawQuery
        ?.split('&')
        ?.mapNotNull { parameter ->
            val parts = parameter.split('=', limit = 2)
            val name = parts.firstOrNull()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            name to parts.getOrElse(1) { "" }
        }
        ?.toMap()
        .orEmpty()

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

private fun parseByteRange(
    header: String,
    totalBytes: Int,
): IntRange? {
    if (totalBytes <= 0) {
        return null
    }
    val match = ByteRangeRegex.matchEntire(header.trim()) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    if (startText.isEmpty() && endText.isEmpty()) {
        return null
    }

    val start: Int
    val end: Int
    if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull()
            ?.takeIf { length -> length > 0L }
            ?: return null
        start = (totalBytes - suffixLength.coerceAtMost(totalBytes.toLong()))
            .toInt()
        end = totalBytes - 1
    } else {
        val parsedStart = startText.toLongOrNull()
            ?.takeIf { value -> value in 0 until totalBytes.toLong() }
            ?: return null
        start = parsedStart.toInt()
        end = if (endText.isEmpty()) {
            totalBytes - 1
        } else {
            endText.toLongOrNull()
                ?.coerceAtMost((totalBytes - 1).toLong())
                ?.toInt()
                ?: return null
        }
        if (end < start) {
            return null
        }
    }
    return start..end
}

private fun generateAccessToken(): String {
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val SafeIdentifier = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val SafeAccessToken = Regex("^[a-zA-Z0-9_-]{12,128}$")
private val ByteRangeRegex = Regex("""bytes=(\d*)-(\d*)""")
private const val RequestBacklog = 8
private const val MaxClientThreads = 4
private const val SocketReadTimeoutMillis = 5_000
private const val MaxLineLength = 8_192
private const val MaxHeaderCount = 100
private const val DefaultSpeechRate = 1f
private const val MinSpeechRate = 0.5f
private const val MaxSpeechRate = 2f
