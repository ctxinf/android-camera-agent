package io.github.ctxinf.cameraagent.adapters.http

import io.github.ctxinf.cameraagent.camera.AlwaysClosePolicy
import io.github.ctxinf.cameraagent.camera.CameraCoordinator
import io.github.ctxinf.cameraagent.camera.DefaultFrameEncoder
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.fake_camera.FakeCameraBackend
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultHttpServerTest {

    private class FakeSettingsRepository(
        initialConfig: CameraConfig = CameraConfig(),
        private var _httpPort: Int = 0,
        private var _dimTimeoutSec: Int = 30,
        private var _dimBrightness: Float = 0.01f,
    ) : SettingsRepository {
        private var background = true
        private var exited = false
        private var frontDisabled = false
        private var http = true
        private var mcp = true
        override val backgroundEnabled get() = background
        override val manuallyExited get() = exited
        override val frontCameraDisabled get() = frontDisabled
        override fun setManuallyExited(exited: Boolean) { this.exited = exited }
        override fun setFrontCameraDisabled(disabled: Boolean) { frontDisabled = disabled }
        override val httpEnabled get() = http
        override val mcpEnabled get() = mcp
        override fun setBackgroundEnabled(enabled: Boolean) { background = enabled }
        override fun setHttpEnabled(enabled: Boolean) { http = enabled }
        override fun setMcpEnabled(enabled: Boolean) { mcp = enabled }
        private val _config = MutableStateFlow(initialConfig)
        override val config: StateFlow<CameraConfig> = _config.asStateFlow()

        override var httpPathPrefix: String = ""
            private set
        override fun setHttpPathPrefix(prefix: String) {
            httpPathPrefix = io.github.ctxinf.cameraagent.domain.HttpPathPrefix.normalize(prefix)
        }
        override val httpPort: Int get() = _httpPort
        override val dimTimeoutSec: Int get() = _dimTimeoutSec
        override val dimBrightness: Float get() = _dimBrightness
        override val retentionGracePeriodSec: Int get() = _config.value.retentionGracePeriodSec
        private var _authEnabled = false
        private var _authUsername = "admin"
        private var _authPassword = "password"

        override val authEnabled: Boolean get() = _authEnabled
        override val authUsername: String get() = _authUsername
        override val authPassword: String get() = _authPassword

        override fun setHttpPort(port: Int) { _httpPort = port }
        override fun setDimTimeoutSec(seconds: Int) { _dimTimeoutSec = seconds }
        override fun setDimBrightness(brightness: Float) { _dimBrightness = brightness }
        override fun setRetentionGracePeriodSec(seconds: Int) {
            _config.value = _config.value.copy(retentionGracePeriodSec = seconds)
        }
        override fun setAuthEnabled(enabled: Boolean) { _authEnabled = enabled }
        override fun setAuthUsername(username: String) { _authUsername = username }
        override fun setAuthPassword(password: String) { _authPassword = password }

        private var _mqttEnabled = false
        private var _mqttHost = ""
        private var _mqttPort = 1883
        private var _mqttUsername = ""
        private var _mqttPassword = ""
        private var _mqttClientId = "camera-agent"
        private var _mqttTopicPrefix = "camera_agent"

        override val mqttEnabled: Boolean get() = _mqttEnabled
        override val mqttHost: String get() = _mqttHost
        override val mqttPort: Int get() = _mqttPort
        override val mqttUsername: String get() = _mqttUsername
        override val mqttPassword: String get() = _mqttPassword
        override val mqttClientId: String get() = _mqttClientId
        override val mqttTopicPrefix: String get() = _mqttTopicPrefix

        override fun setMqttEnabled(enabled: Boolean) { _mqttEnabled = enabled }
        override fun setMqttHost(host: String) { _mqttHost = host }
        override fun setMqttPort(port: Int) { _mqttPort = port }
        override fun setMqttUsername(username: String) { _mqttUsername = username }
        override fun setMqttPassword(password: String) { _mqttPassword = password }
        override fun setMqttClientId(clientId: String) { _mqttClientId = clientId }
        override fun setMqttTopicPrefix(prefix: String) { _mqttTopicPrefix = prefix }

        override suspend fun updateConfig(config: CameraConfig) {
            _config.value = config
        }
    }

    private var serverPort = 0
    private lateinit var testScope: kotlinx.coroutines.CoroutineScope
    private lateinit var fakeBackend: FakeCameraBackend
    private lateinit var coordinator: CameraCoordinator
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var httpServer: DefaultHttpServer

    private fun findFreePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Before
    fun setUp() {
        testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        serverPort = findFreePort()
        fakeBackend = FakeCameraBackend()
        coordinator = CameraCoordinator(
            backend = fakeBackend,
            parentScope = testScope,
            retentionPolicy = AlwaysClosePolicy(),
        )
        settingsRepo = FakeSettingsRepository(_httpPort = serverPort)
        httpServer = DefaultHttpServer(
            cameraController = coordinator,
            frameEncoder = DefaultFrameEncoder(),
            settingsRepository = settingsRepo,
            getHardwareLevel = { "FULL" },
            serverScope = testScope,
        )
        kotlinx.coroutines.runBlocking {
            httpServer.start(serverPort)
        }
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking {
            httpServer.stop()
        }
        coordinator.shutdown()
        testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun requestStatus(path: String, authorization: String? = null): Int {
        val conn = URL("http://127.0.0.1:$serverPort$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        if (authorization != null) conn.setRequestProperty("Authorization", authorization)
        return try { conn.responseCode } finally { conn.disconnect() }
    }

    @Test
    fun `prefix mounts all aliases and rejects unprefixed or partial paths`() {
        settingsRepo.setHttpPathPrefix(" /cameras//front/ ")
        assertEquals(200, requestStatus("/cameras/front/health?probe=1"))
        assertEquals(200, requestStatus("/cameras/front/api/status"))
        assertEquals(200, requestStatus("/cameras/front/api/v1/status"))
        assertEquals(200, requestStatus("/cameras/front/api/camera"))
        assertEquals(200, requestStatus("/cameras/front/api/v1/config"))
        assertEquals(200, requestStatus("/cameras/front/mcp"))
        assertEquals(200, requestStatus("/cameras/front/api/mcp"))
        assertEquals(200, requestStatus("/cameras/front/api/v1/mcp"))
        for (path in listOf("/health", "/api/status", "/mcp", "/cameras/frontal/health", "/cameras/frontier/api/status")) {
            assertEquals(path, 404, requestStatus(path))
        }
    }

    @Test
    fun `prefixed protected endpoints retain auth while health stays public`() {
        settingsRepo.setHttpPathPrefix("/cam")
        settingsRepo.setAuthEnabled(true)
        assertEquals(200, requestStatus("/cam/health"))
        assertEquals(401, requestStatus("/cam/api/status"))
        assertEquals(401, requestStatus("/cam/mcp"))
        assertEquals(200, requestStatus("/cam/api/status", "Basic YWRtaW46cGFzc3dvcmQ="))
        assertEquals(404, requestStatus("/health"))
    }

    @Test
    fun `changing and clearing prefix applies without restarting server`() {
        settingsRepo.setHttpPathPrefix("/first")
        assertEquals(200, requestStatus("/first/health"))
        settingsRepo.setHttpPathPrefix("/second")
        assertEquals(404, requestStatus("/first/health"))
        assertEquals(200, requestStatus("/second/health"))
        settingsRepo.setHttpPathPrefix("/")
        assertEquals(200, requestStatus("/health"))
        assertEquals(404, requestStatus("/second/health"))
    }

    @Test
    fun `HTTP and MCP route switches are independent for all aliases`() {
        for (http in listOf(false, true)) for (mcp in listOf(false, true)) {
            settingsRepo.setHttpEnabled(http)
            settingsRepo.setMcpEnabled(mcp)
            for (path in listOf("/health", "/api/status", "/api/v1/status", "/api/camera")) {
                assertEquals(path, if (http) 200 else 404, requestStatus(path))
            }
            for (path in listOf("/mcp", "/api/mcp", "/api/v1/mcp")) {
                assertEquals(path, if (mcp) 200 else 404, requestStatus(path))
            }
        }
        settingsRepo.setHttpEnabled(true)
        settingsRepo.setMcpEnabled(true)
        settingsRepo.setBackgroundEnabled(false)
        assertEquals(200, requestStatus("/health"))
        assertEquals(200, requestStatus("/mcp"))
        settingsRepo.setManuallyExited(true)
        assertEquals(404, requestStatus("/health"))
        assertEquals(404, requestStatus("/mcp"))
    }

    @Test
    fun `disabling MCP closes its request while preserving HTTP connections`() {
        Socket("127.0.0.1", serverPort).use { http ->
            Socket("127.0.0.1", serverPort).use { mcp ->
                http.soTimeout = 3000
                mcp.soTimeout = 3000
                http.getOutputStream().write("POST /api/camera HTTP/1.1\r\nContent-Length: 2\r\n\r\n".toByteArray())
                mcp.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 2\r\n\r\n".toByteArray())
                val deadline = System.nanoTime() + 3_000_000_000L
                while ((httpServer.httpConnections != 1 || httpServer.mcpConnections != 1) &&
                    System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(1, httpServer.httpConnections)
                assertEquals(1, httpServer.mcpConnections)
                settingsRepo.setMcpEnabled(false)
                httpServer.closeDisabledConnections()
                assertEquals(-1, mcp.getInputStream().read())
                http.getOutputStream().write("{}".toByteArray())
                val response = http.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(httpServer.isRunning)
            }
        }
    }

    @Test
    fun `connections are counted by protocol until the socket closes`() {
        Socket("127.0.0.1", serverPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\n".toByteArray())
            val deadline = System.nanoTime() + 3_000_000_000L
            while (httpServer.mcpConnections == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, httpServer.mcpConnections)
            assertEquals(0, httpServer.httpConnections)
            kotlinx.coroutines.runBlocking { httpServer.stop() }
            assertEquals(-1, socket.getInputStream().read())
        }
        assertEquals(0, httpServer.mcpConnections)
    }

    @Test
    fun `health endpoint returns 200 OK with status ok`() {
        val url = URL("http://127.0.0.1:$serverPort/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val response = conn.inputStream.bufferedReader().readText()
        assertTrue(response.contains(""""status":"ok""""))
    }

    @Test
    fun `status endpoint returns 200 OK with camera state JSON`() {
        val url = URL("http://127.0.0.1:$serverPort/api/status")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val response = conn.inputStream.bufferedReader().readText()
        assertTrue(response.contains("cameraState"))
        assertTrue(response.contains("cameraEnabled"))
    }

    @Test
    fun `config endpoint GET returns current camera config`() {
        val url = URL("http://127.0.0.1:$serverPort/api/camera")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val response = conn.inputStream.bufferedReader().readText()
        assertTrue(response.contains("targetWidth"))
        assertTrue(response.contains("targetFps"))
    }

    @Test
    fun `config endpoint POST updates camera config`() {
        val socket = Socket("127.0.0.1", serverPort)
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        val body = """{"targetWidth":640,"targetHeight":480,"targetFps":10}"""
        val req = "POST /api/camera HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$serverPort\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                body

        writer.write(req)
        writer.flush()

        val statusLine = reader.readLine()
        assertTrue(statusLine.contains("200 OK"))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.isEmpty()) break
        }
        val responseBody = reader.readLine()
        assertTrue(responseBody.contains(""""targetWidth":640"""))
        assertTrue(responseBody.contains(""""targetHeight":480"""))
        assertTrue(responseBody.contains(""""targetFps":10"""))

        socket.close()
    }

    @Test
    fun `unknown path returns 404 Not Found`() {
        val url = URL("http://127.0.0.1:$serverPort/nonexistent_path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(404, conn.responseCode)
    }

    @Test
    fun `when service is disabled snapshot returns 200 OK with dummy jpeg and does not open camera`() {
        kotlinx.coroutines.runBlocking {
            coordinator.setEnabled(false)
        }

        val url = URL("http://127.0.0.1:$serverPort/snapshot.jpg")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertEquals("image/jpeg", conn.contentType)
        val bytes = conn.inputStream.readBytes()
        assertTrue(bytes.isNotEmpty())

        // Physical camera must NOT be opened!
        org.junit.Assert.assertFalse(fakeBackend.isOpened)
    }

    @Test
    fun `when service is disabled api v1 snapshot returns 200 OK with dummy jpeg`() {
        kotlinx.coroutines.runBlocking {
            coordinator.setEnabled(false)
        }

        val url = URL("http://127.0.0.1:$serverPort/api/v1/snapshot")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertEquals("image/jpeg", conn.contentType)
        val bytes = conn.inputStream.readBytes()
        assertTrue(bytes.isNotEmpty())
        org.junit.Assert.assertFalse(fakeBackend.isOpened)
    }

    @Test
    fun `when service is disabled video mjpeg returns 200 OK multipart dummy stream`() {
        kotlinx.coroutines.runBlocking {
            coordinator.setEnabled(false)
        }

        val url = URL("http://127.0.0.1:$serverPort/video.mjpeg")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("multipart/x-mixed-replace"))

        val input = conn.inputStream
        val buffer = ByteArray(1024)
        val read = input.read(buffer)
        assertTrue(read > 0)
        val text = String(buffer, 0, read)
        assertTrue(text.contains("image/jpeg"))

        conn.disconnect()
        org.junit.Assert.assertFalse(fakeBackend.isOpened)
    }

    @Test
    fun `warmup endpoint pre-activates camera and returns 200 OK`() {
        org.junit.Assert.assertFalse(fakeBackend.isOpened)

        val url = URL("http://127.0.0.1:$serverPort/api/v1/warmup")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.write("""{"durationSec":5}""".toByteArray())
        conn.outputStream.flush()

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        assertTrue(body.contains(""""status":"ok""""))
        assertTrue(body.contains("warmupDurationMs"))

        // Physical camera backend must be pre-activated!
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
    }

    @Test
    fun `config endpoint GET and POST updates retentionGracePeriodSec`() {
        // 1. GET initial config
        val getUrl = URL("http://127.0.0.1:$serverPort/api/v1/config")
        val getConn = getUrl.openConnection() as HttpURLConnection
        assertEquals(200, getConn.responseCode)
        val initialBody = getConn.inputStream.bufferedReader().readText()
        assertTrue(initialBody.contains("retentionGracePeriodSec"))

        // 2. POST update retentionGracePeriodSec to 10
        val postConn = getUrl.openConnection() as HttpURLConnection
        postConn.requestMethod = "POST"
        postConn.doOutput = true
        postConn.outputStream.write("""{"retentionGracePeriodSec":10}""".toByteArray())
        postConn.outputStream.flush()

        assertEquals(200, postConn.responseCode)
        val postBody = postConn.inputStream.bufferedReader().readText()
        assertTrue(postBody.contains(""""retentionGracePeriodSec":10"""))

        // 3. Verify coordinator config was updated
        assertEquals(10, coordinator.config.value.retentionGracePeriodSec)
        assertEquals(10, settingsRepo.config.value.retentionGracePeriodSec)
    }

    @Test
    fun `basic auth blocks unauthorized requests when enabled`() {
        settingsRepo.setAuthEnabled(true)
        settingsRepo.setAuthUsername("myuser")
        settingsRepo.setAuthPassword("mypassword")

        // 1. Unauthenticated request to /api/status should return 401
        val url = URL("http://127.0.0.1:$serverPort/api/status")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        assertEquals(401, conn.responseCode)
        assertEquals("""Basic realm="CameraAgent"""", conn.getHeaderField("WWW-Authenticate"))

        // 2. /health is exempt from auth
        val healthUrl = URL("http://127.0.0.1:$serverPort/health")
        val healthConn = healthUrl.openConnection() as HttpURLConnection
        assertEquals(200, healthConn.responseCode)

        // 3. Request with valid Basic auth header should succeed
        val authConn = url.openConnection() as HttpURLConnection
        val credentials = java.util.Base64.getEncoder().encodeToString("myuser:mypassword".toByteArray())
        authConn.setRequestProperty("Authorization", "Basic $credentials")
        assertEquals(200, authConn.responseCode)
    }

    @Test
    fun `HA generic camera HEAD request for snapshot returns 200 OK headers without body`() {
        kotlinx.coroutines.runBlocking {
            coordinator.setEnabled(false)
        }

        val url = URL("http://127.0.0.1:$serverPort/snapshot.jpg")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "HEAD"

        assertEquals(200, conn.responseCode)
        assertEquals("image/jpeg", conn.contentType)
        // HEAD must return 0 bytes in body
        val bytes = conn.inputStream.readBytes()
        assertEquals(0, bytes.size)
    }

    @Test
    fun `HA generic camera HEAD request for stream returns 200 OK headers`() {
        val url = URL("http://127.0.0.1:$serverPort/video.mjpeg")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "HEAD"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("multipart/x-mixed-replace"))
        val bytes = conn.inputStream.readBytes()
        assertEquals(0, bytes.size)
    }

    @Test
    fun `mcp endpoint initialize and tools list returns valid jsonrpc`() {
        val url = URL("http://127.0.0.1:$serverPort/mcp")

        // 1. Initialize
        val initConn = url.openConnection() as HttpURLConnection
        initConn.requestMethod = "POST"
        initConn.doOutput = true
        initConn.outputStream.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""".toByteArray())
        initConn.outputStream.flush()

        assertEquals(200, initConn.responseCode)
        val initBody = initConn.inputStream.bufferedReader().readText()
        assertTrue(initBody.contains(""""protocolVersion":""""))
        assertTrue(initBody.contains(""""camera-agent""""))

        // 2. Tools list
        val listConn = url.openConnection() as HttpURLConnection
        listConn.requestMethod = "POST"
        listConn.doOutput = true
        listConn.outputStream.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""".toByteArray())
        listConn.outputStream.flush()

        assertEquals(200, listConn.responseCode)
        val listBody = listConn.inputStream.bufferedReader().readText()
        assertTrue(listBody.contains("get_snapshot"))
        assertTrue(listBody.contains("get_status"))
        assertTrue(listBody.contains("set_camera_facing"))
        assertTrue(listBody.contains("set_camera_enabled"))
        assertTrue(listBody.contains("set_camera_config"))
        assertTrue(listBody.contains("warmup_camera"))

        // 3. Tool call get_status
        val callConn = url.openConnection() as HttpURLConnection
        callConn.requestMethod = "POST"
        callConn.doOutput = true
        callConn.outputStream.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_status","arguments":{}}}""".toByteArray())
        callConn.outputStream.flush()

        assertEquals(200, callConn.responseCode)
        val callBody = callConn.inputStream.bufferedReader().readText()
        assertTrue(callBody.contains("cameraState"))

        // 4. Tool call set_camera_facing
        val facingConn = url.openConnection() as HttpURLConnection
        facingConn.requestMethod = "POST"
        facingConn.doOutput = true
        facingConn.outputStream.write("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"set_camera_facing","arguments":{"facing":"FRONT"}}}""".toByteArray())
        facingConn.outputStream.flush()

        assertEquals(200, facingConn.responseCode)
        val facingBody = facingConn.inputStream.bufferedReader().readText()
        assertTrue(facingBody.contains("Camera facing successfully updated to FRONT"))
        assertEquals(LensFacing.FRONT, coordinator.config.value.facing)
    }
}
