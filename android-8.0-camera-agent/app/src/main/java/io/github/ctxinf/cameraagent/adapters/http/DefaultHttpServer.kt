package io.github.ctxinf.cameraagent.adapters.http

import android.os.Build
import android.os.SystemClock
import io.github.ctxinf.cameraagent.camera.FrameEncoder
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.DeviceStatus
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Snapshot
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import io.github.ctxinf.cameraagent.domain.Frame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ctxinf.cameraagent.adapters.mcp.McpHandler
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DefaultHttpServer(
    private val cameraController: CameraController,
    private val frameEncoder: FrameEncoder,
    private val settingsRepository: SettingsRepository,
    private val getHardwareLevel: () -> String? = { null },
    private val dummyFrameProvider: (() -> ByteArray)? = null,
    private val serverScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val mcpHandler: McpHandler = McpHandler(
        cameraController = cameraController,
        settingsRepository = settingsRepository,
        getHardwareLevel = getHardwareLevel,
        dummyFrameProvider = dummyFrameProvider,
    ),
) : HttpServer {

    companion object {
        private const val MAX_WORKERS = 8
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val BOUNDARY = "frameboundary"
        private const val DELIMITER = "--$BOUNDARY"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Volatile
    private var _isRunning: Boolean = false
    override val isRunning: Boolean
        get() = _isRunning

    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    private var acceptJob: Job? = null

    private val lock = Any()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val clients = java.util.concurrent.ConcurrentHashMap<Socket, Boolean>()
    override fun closeDisabledConnections() {
        clients.forEach { (socket, mcp) ->
            if (settingsRepository.manuallyExited ||
                (if (mcp) !settingsRepository.mcpEnabled else !settingsRepository.httpEnabled)) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }
    override val httpConnections get() = clients.values.count { !it }
    override val mcpConnections get() = clients.values.count { it }

    override suspend fun start(port: Int) {
        synchronized(lock) {
            if (_isRunning) {
                AppLog.i("HttpServer is already running")
                return
            }

            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(settingsRepository.httpBindAddress, port))
                serverSocket = socket

                executor = Executors.newFixedThreadPool(MAX_WORKERS) as ThreadPoolExecutor
                _isRunning = true
                AppLog.i("HttpServer started listening on port $port")

                acceptJob = serverScope.launch {
                    acceptLoop(socket)
                }
            } catch (e: Exception) {
                AppLog.e("HttpServer failed to bind to port $port", e)
                _isRunning = false
                throw e
            }
        }
    }

    override suspend fun stop() {
        val toCloseSocket: ServerSocket?
        val toCancelJob: Job?
        val toShutdownExecutor: ThreadPoolExecutor?

        synchronized(lock) {
            if (!_isRunning) return
            _isRunning = false
            AppLog.i("Stopping HttpServer...")

            toCloseSocket = serverSocket
            serverSocket = null

            toCancelJob = acceptJob
            acceptJob = null

            toShutdownExecutor = executor
            executor = null
        }

        try {
            toCloseSocket?.close()
        } catch (ignored: Exception) {}

        toCancelJob?.cancel()

        sockets.forEach { try { it.close() } catch (_: Exception) {} }
        sockets.clear()

        toShutdownExecutor?.shutdownNow()
        try {
            toShutdownExecutor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (ignored: Exception) {}

        AppLog.i("HttpServer stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (_isRunning && !server.isClosed) {
            try {
                val client = server.accept()
                sockets.add(client)
                val pool = executor
                try {
                    if (!_isRunning || pool == null) {
                        sockets.remove(client)
                        client.close()
                    } else pool.execute { handleClient(client) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(client)
                    client.close()
                }
            } catch (e: IOException) {
                if (_isRunning) {
                    AppLog.w("HttpServer accept error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true

            val input = client.getInputStream()
            val output = BufferedOutputStream(client.getOutputStream(), 16 * 1024)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendError(output, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            var path = parts[1]
            val queryIdx = path.indexOf('?')
            if (queryIdx >= 0) {
                path = path.substring(0, queryIdx)
            }

            path = io.github.ctxinf.cameraagent.domain.HttpPathPrefix.strip(path, settingsRepository.httpPathPrefix)
                ?: run {
                    sendError(output, 404, "Not Found")
                    return
                }

            val isMcp = path in setOf("/mcp", "/api/mcp", "/api/v1/mcp")
            if (settingsRepository.manuallyExited || if (isMcp) !settingsRepository.mcpEnabled else !settingsRepository.httpEnabled) {
                sendError(output, 404, "Not Found")
                return
            }
            clients[client] = isMcp

            // Parse headers
            var contentLength = 0
            var authHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring("Content-Length:".length).trim().toIntOrNull() ?: 0
                } else if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substring("Authorization:".length).trim()
                }
            }

            // Enforce Basic Auth if enabled (exempt health endpoint)
            if (settingsRepository.authEnabled && path != "/health") {
                if (!checkAuth(authHeader)) {
                    sendUnauthorized(output)
                    return
                }
            }

            when {
                path == "/health" -> {
                    sendJson(output, 200, """{"status":"ok"}""")
                }

                path == "/snapshot.jpg" || path == "/api/v1/snapshot" -> {
                    handleSnapshot(output, isHead = (method == "HEAD"))
                }

                path == "/video.mjpeg" || path == "/api/v1/stream.mjpeg" -> {
                    handleMjpegStream(client, output, isHead = (method == "HEAD"))
                }

                path == "/mcp" || path == "/api/mcp" || path == "/api/v1/mcp" -> {
                    if (method == "POST") {
                        val body = readBody(input, contentLength)
                        val response = mcpHandler.handleRequest(body)
                        if (response.isNotEmpty()) {
                            sendJson(output, 200, response)
                        } else {
                            val header = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray(Charsets.US_ASCII))
                            output.flush()
                        }
                    } else if (method == "GET") {
                        val response = mcpHandler.handleRequest("""{"jsonrpc":"2.0","id":1,"method":"server/discover"}""")
                        sendJson(output, 200, response)
                    } else {
                        sendError(output, 405, "Method Not Allowed")
                    }
                }

                path == "/api/status" || path == "/api/v1/status" -> {
                    handleStatus(output)
                }

                path == "/api/camera" || path == "/api/v1/config" -> {
                    if (method == "GET" || method == "HEAD") {
                        handleGetConfig(output)
                    } else if (method == "POST" || method == "PATCH") {
                        val body = readBody(input, contentLength)
                        handleUpdateConfig(output, body)
                    } else {
                        sendError(output, 405, "Method Not Allowed")
                    }
                }

                path == "/api/v1/warmup" || path == "/api/v1/activate" || path == "/api/warmup" || path == "/api/activate" -> {
                    val body = if (method == "POST" || method == "PATCH") readBody(input, contentLength) else ""
                    handleWarmup(output, body)
                }

                else -> {
                    sendError(output, 404, "Not Found")
                }
            }
        } catch (e: SocketException) {
            // Client hung up or connection reset, normal for MJPEG streaming
        } catch (e: Exception) {
            AppLog.w("Error processing HTTP request: ${e.message}")
        } finally {
            clients.remove(client)
            sockets.remove(client)
            try {
                client.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun checkAuth(authHeader: String?): Boolean {
        if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
            return false
        }
        val b64 = authHeader.substring(6).trim()
        return try {
            val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            val colonIdx = decoded.indexOf(':')
            if (colonIdx == -1) return false
            val user = decoded.substring(0, colonIdx)
            val pass = decoded.substring(colonIdx + 1)
            user == settingsRepository.authUsername && pass == settingsRepository.authPassword
        } catch (e: Exception) {
            false
        }
    }

    private fun sendUnauthorized(out: OutputStream) {
        val body = """{"error":"Unauthorized"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 401 Unauthorized\r\n" +
                "WWW-Authenticate: Basic realm=\"CameraAgent\"\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun handleSnapshot(out: OutputStream, isHead: Boolean = false) {
        if (!cameraController.config.value.enabled) {
            val dummyBytes = dummyFrameProvider?.invoke() ?: DummyFrameGenerator.createDummyJpeg()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${dummyBytes.size}\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(header.toByteArray(Charsets.US_ASCII))
            if (!isHead) {
                out.write(dummyBytes)
            }
            out.flush()
            return
        }

        if (isHead) {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }

        try {
            val snapshot: Snapshot = runBlocking {
                cameraController.takeSnapshot()
            }

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${snapshot.jpegBytes.size}\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(snapshot.jpegBytes)
            out.flush()
        } catch (e: Exception) {
            AppLog.e("Failed to capture snapshot for HTTP request: ${e.message}", e)
            sendError(out, 503, "Camera Unavailable: ${e.message}")
        }
    }

    private fun handleMjpegStream(client: Socket, out: OutputStream, isHead: Boolean = false) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.flush()
        if (isHead) return
        client.soTimeout = 0
        val token = "mjpeg_${UUID.randomUUID().toString().take(8)}"
        var lastSentTime = 0L
        var lastFrameAt = SystemClock.elapsedRealtime()
        try {
            runBlocking {
                val frames = Channel<Frame>(Channel.CONFLATED)
                var collector: Job? = null
                try {
                    while (_isRunning && !client.isClosed) {
                        val jpegBytes: ByteArray
                        if (!cameraController.config.value.enabled) {
                            collector?.cancelAndJoin()
                            collector = null
                            while (frames.tryReceive().isSuccess) { /* discard old real frames */ }
                            jpegBytes = dummyFrameProvider?.invoke() ?: DummyFrameGenerator.createDummyJpeg()
                        } else {
                            if (collector == null) {
                                val flow = try { cameraController.openStream(token) }
                                catch (e: io.github.ctxinf.cameraagent.domain.CameraDisabledException) { continue }
                                collector = launch { flow.collect { frames.trySend(it) } }
                            }
                            val frame = withTimeoutOrNull(500) { frames.receive() }
                            if (frame == null) {
                                // A local privacy transition may invalidate the coordinator's old stream.
                                if (SystemClock.elapsedRealtime() - lastFrameAt > 5000) {
                                    collector?.cancelAndJoin()
                                    collector = null
                                    lastFrameAt = SystemClock.elapsedRealtime()
                                }
                                continue
                            }
                            lastFrameAt = SystemClock.elapsedRealtime()
                            if (!cameraController.config.value.enabled) continue
                            val now = SystemClock.elapsedRealtime()
                            val interval = 1000L / cameraController.config.value.targetFps.coerceIn(1, 30)
                            if (now - lastSentTime < interval - 8) continue
                            lastSentTime = now
                            jpegBytes = frameEncoder.encodeToJpeg(frame, cameraController.config.value.jpegQuality)
                            if (!cameraController.config.value.enabled) continue
                        }
                        val partHeader = "$DELIMITER\r\nContent-Type: image/jpeg\r\n" +
                                "Content-Length: ${jpegBytes.size}\r\n\r\n"
                        out.write(partHeader.toByteArray(Charsets.US_ASCII))
                        out.write(jpegBytes)
                        out.write("\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                        if (!cameraController.config.value.enabled) delay(500)
                    }
                } finally {
                    collector?.cancelAndJoin()
                    frames.close()
                    cameraController.closeStream(token)
                }
            }
        } catch (e: Exception) {
            AppLog.d("MJPEG stream closed: ${e.message}")
        }
    }

    private fun handleStatus(out: OutputStream) {
        val state = cameraController.state.value
        val config = cameraController.config.value

        val deviceStatus = DeviceStatus(
            cameraState = when (state) {
                is CameraState.Disabled -> "Disabled"
                is CameraState.Closed -> "Closed"
                is CameraState.Opening -> "Opening"
                is CameraState.Active -> "Active"
                is CameraState.Retained -> "Retained"
                is CameraState.Closing -> "Closing"
                is CameraState.Failed -> "Failed"
            },
            cameraId = (state as? CameraState.Active)?.cameraId ?: (state as? CameraState.Retained)?.cameraId,
            facing = config.facing,
            width = (state as? CameraState.Active)?.width ?: config.targetWidth,
            height = (state as? CameraState.Active)?.height ?: config.targetHeight,
            fps = config.targetFps,
            jpegQuality = config.jpegQuality,
            activeStreams = (state as? CameraState.Active)?.activeStreams ?: 0,
            lastFrameTimestampNs = null,
            lastError = (state as? CameraState.Failed)?.reason,
            cameraEnabled = config.enabled,
            androidSdk = Build.VERSION.SDK_INT,
            hardwareLevel = getHardwareLevel(),
        )

        val jsonStr = json.encodeToString(DeviceStatus.serializer(), deviceStatus)
        sendJson(out, 200, jsonStr)
    }

    private fun handleGetConfig(out: OutputStream) {
        val config = cameraController.config.value
        val jsonStr = json.encodeToString(io.github.ctxinf.cameraagent.domain.CameraConfig.serializer(), config)
        sendJson(out, 200, jsonStr)
    }

    private fun handleUpdateConfig(out: OutputStream, body: String) {
        try {
            val jsonElement = json.parseToJsonElement(body).jsonObject
            var current = cameraController.config.value

            if ("enabled" in jsonElement) {
                sendError(out, 403, "Camera enable/disable is device-only")
                return
            }
            jsonElement["facing"]?.jsonPrimitive?.content?.let { facingStr ->
                try {
                    val facing = LensFacing.valueOf(facingStr.uppercase())
                    current = current.copy(facing = facing)
                } catch (ignored: Exception) {}
            }
            jsonElement["targetWidth"]?.jsonPrimitive?.intOrNull?.let {
                current = current.copy(targetWidth = it)
            }
            jsonElement["targetHeight"]?.jsonPrimitive?.intOrNull?.let {
                current = current.copy(targetHeight = it)
            }
            jsonElement["targetFps"]?.jsonPrimitive?.intOrNull?.let {
                current = current.copy(targetFps = it.coerceIn(1, 60))
            }
            jsonElement["jpegQuality"]?.jsonPrimitive?.intOrNull?.let {
                current = current.copy(jpegQuality = it.coerceIn(10, 100))
            }
            jsonElement["rotationOffset"]?.jsonPrimitive?.intOrNull?.let {
                current = current.copy(rotationOffset = it)
            }
            val retentionGrace = jsonElement["retentionGracePeriodSec"]?.jsonPrimitive?.intOrNull
                ?: jsonElement["retention_grace_sec"]?.jsonPrimitive?.intOrNull
            if (retentionGrace != null) {
                current = current.copy(retentionGracePeriodSec = retentionGrace.coerceIn(0, 3600))
            }

            val updated = runBlocking {
                val res = cameraController.updateConfig(current)
                res
            }

            val jsonStr = json.encodeToString(io.github.ctxinf.cameraagent.domain.CameraConfig.serializer(), updated)
            sendJson(out, 200, jsonStr)
        } catch (e: SecurityException) {
            sendError(out, 403, e.message ?: "Camera access denied")
        } catch (e: Exception) {
            sendError(out, 400, "Invalid JSON: ${e.message}")
        }
    }

    private fun handleWarmup(out: OutputStream, body: String) {
        if (!cameraController.config.value.enabled) {
            sendError(out, 503, "Camera service is suspended")
            return
        }

        var durationMs = 5000L
        if (body.isNotEmpty()) {
            try {
                val jsonElement = json.parseToJsonElement(body).jsonObject
                val durationSec = jsonElement["durationSec"]?.jsonPrimitive?.intOrNull
                    ?: jsonElement["duration"]?.jsonPrimitive?.intOrNull
                if (durationSec != null && durationSec > 0) {
                    durationMs = (durationSec * 1000L).coerceIn(1000L, 60_000L)
                }
            } catch (ignored: Exception) {}
        }

        try {
            runBlocking {
                cameraController.warmup(durationMs)
            }
            val response = """{"status":"ok","cameraState":"Active","warmupDurationMs":$durationMs,"message":"Camera activated and warming up"}"""
            sendJson(out, 200, response)
        } catch (e: Exception) {
            AppLog.e("Failed to warmup camera: ${e.message}", e)
            sendError(out, 503, "Failed to activate camera: ${e.message}")
        }
    }

    private fun sendJson(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Response"
        }
        val header = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun sendError(out: OutputStream, status: Int, message: String) {
        val body = """{"error":"$message"}"""
        sendJson(out, status, body)
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\n'.code) {
                val str = baos.toString("US-ASCII")
                return if (str.endsWith("\r")) str.substring(0, str.length - 1) else str
            }
            baos.write(b)
        }
        return if (baos.size() == 0) null else baos.toString("US-ASCII")
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val r = input.read(bytes, totalRead, length - totalRead)
            if (r == -1) break
            totalRead += r
        }
        return String(bytes, 0, totalRead, Charsets.UTF_8)
    }
}
