package io.github.ctxinf.cameraagent.adapters.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.adapters.mqtt.MqttStatus
import io.github.ctxinf.cameraagent.bootstrap.AgentService
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 1001
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var rootLayout: View
    private lateinit var contentLayout: LinearLayout
    private lateinit var statusLed: View
    private lateinit var tvStatusText: TextView
    private lateinit var tbCameraEnable: ToggleButton
    private lateinit var cameraHardwareLed: View
    private lateinit var tvCameraHardwareStatus: TextView
    private lateinit var btnSettingsIcon: ImageButton

    // Status Bar Items
    private lateinit var tvMqttStatus: TextView
    private lateinit var btnFacingBack: Button
    private lateinit var btnFacingFront: Button

    // Modern Endpoint Card
    private lateinit var tvBaseEndpoint: TextView
    private lateinit var tvAuthBadge: TextView
    private lateinit var chipSnapshot: Chip
    private lateinit var chipStream: Chip
    private lateinit var chipStatus: Chip
    private lateinit var chipMcp: Chip
    private lateinit var chipCameraConfig: Chip
    private lateinit var chipWarmup: Chip
    private lateinit var chipHealth: Chip
    private lateinit var tvEndpointSummary: TextView

    private lateinit var tvDeviceStatus: TextView

    private var isDimmed = false
    private var isBinding = false

    private val dimTask = Runnable {
        setDimmed(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_main)
        AppLog.i("MainActivity onCreate()")

        rootLayout = findViewById(R.id.rootLayout)
        contentLayout = findViewById(R.id.contentLayout)
        statusLed = findViewById(R.id.statusLed)
        tvStatusText = findViewById(R.id.tvStatusText)
        tbCameraEnable = findViewById(R.id.tbCameraEnable)
        cameraHardwareLed = findViewById(R.id.cameraHardwareLed)
        tvCameraHardwareStatus = findViewById(R.id.tvCameraHardwareStatus)
        btnSettingsIcon = findViewById(R.id.btnSettingsIcon)

        tvMqttStatus = findViewById(R.id.tvMqttStatus)
        btnFacingBack = findViewById(R.id.btnFacingBack)
        btnFacingFront = findViewById(R.id.btnFacingFront)

        tvBaseEndpoint = findViewById(R.id.tvBaseEndpoint)
        tvAuthBadge = findViewById(R.id.tvAuthBadge)
        chipSnapshot = findViewById(R.id.chipSnapshot)
        chipStream = findViewById(R.id.chipStream)
        chipStatus = findViewById(R.id.chipStatus)
        chipMcp = findViewById(R.id.chipMcp)
        chipCameraConfig = findViewById(R.id.chipCameraConfig)
        chipWarmup = findViewById(R.id.chipWarmup)
        chipHealth = findViewById(R.id.chipHealth)
        tvEndpointSummary = findViewById(R.id.tvEndpointSummary)

        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)

        val switches = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val repo = (application as CameraAgentApp).appGraph.settingsRepository
        listOf("HTTP", "MCP", "MQTT").forEach { name ->
            val box = android.widget.CheckBox(this).apply {
                text = name
                isChecked = when (name) { "HTTP" -> repo.httpEnabled; "MCP" -> repo.mcpEnabled; else -> repo.mqttEnabled }
                setOnCheckedChangeListener { _, enabled ->
                    if (isBinding) return@setOnCheckedChangeListener
                    when (name) { "HTTP" -> repo.setHttpEnabled(enabled); "MCP" -> repo.setMcpEnabled(enabled); else -> repo.setMqttEnabled(enabled) }
                    val graph = (application as CameraAgentApp).appGraph
                    graph.appScope.launch {
                        try { graph.refreshNetworkListener() }
                        catch (e: Exception) { AppLog.e("Listener start failed", e) }
                    }
                    if (name == "MQTT") graph.mqttManager.restart()
                    refreshUi()
                }
            }
            box.tag = name
            switches.addView(box, android.widget.LinearLayout.LayoutParams(0, 48.dp(), 1f))
        }
        findViewById<LinearLayout>(R.id.topBar).addView(switches, 1)
        setupListeners()
        ensureCameraPermission()
        ensureServiceStarted()
        observeSystemState()
        wakeScreen()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private val statusRefresh = object : Runnable {
        override fun run() { refreshUi(); handler.postDelayed(this, 1000) }
    }

    // Draw status lights instead of relying on emoji glyphs on older Android fonts.
    private fun setStatusIndicator(view: TextView, line: String) {
        val resource = when {
            line.startsWith("🟢") -> R.drawable.led_green
            line.startsWith("🟠") -> R.drawable.led_amber
            else -> R.drawable.led_grey
        }
        val light = resources.getDrawable(resource, theme).mutate()
        light.setBounds(0, 0, 10.dp(), 10.dp())
        view.setCompoundDrawables(light, null, null, null)
        view.compoundDrawablePadding = 8.dp()
        view.text = line.substringAfter(' ')
    }

    private fun serviceLine(name: String, enabled: Boolean, running: Boolean): String {
        val master = (application as CameraAgentApp).appGraph.settingsRepository.serviceEnabled
        val icon = if (running) "🟢" else if (enabled && master) "🟠" else "⚪"
        val state = if (!enabled) "已停用" else if (!running) "未运行" else "运行中"
        return "$icon $name · $state"
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("MainActivity onResume()")
        ensureServiceStarted()
        refreshUi()
        handler.post(statusRefresh)
        wakeScreen()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dimTask)
        handler.removeCallbacks(statusRefresh)
    }

    private fun setupListeners() {
        btnSettingsIcon.setOnClickListener {
            wakeScreen()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvMqttStatus.setOnClickListener {
            wakeScreen()
            val status = (application as CameraAgentApp).appGraph.mqttManager.status.value
            val msg = when (status) {
                is MqttStatus.Disabled -> "MQTT: 未启用 (可在设置中配置并开启)"
                is MqttStatus.Connecting -> "MQTT: 正在连接..."
                is MqttStatus.Connected -> "MQTT: 已连接 (${status.broker})"
                is MqttStatus.Disconnected -> "MQTT: 连接断开 (${status.reason})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        tbCameraEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            wakeScreen()
            lifecycleScope.launch {
                val appGraph = (application as CameraAgentApp).appGraph
                val repo = appGraph.settingsRepository
                repo.setServiceEnabled(isChecked)
                try { appGraph.refreshNetworkListener() }
                catch (e: Exception) { AppLog.e("Listener start failed", e) }
                appGraph.mqttManager.restart()
                refreshUi()
            }
        }

        btnFacingBack.setOnClickListener {
            wakeScreen()
            setFacing(LensFacing.BACK)
        }

        btnFacingFront.setOnClickListener {
            wakeScreen()
            setFacing(LensFacing.FRONT)
        }

        setupChipListeners()
    }

    private var refreshEndpointDetail: (() -> Unit)? = null

    private fun setupChipListeners() {
        val appGraph = (application as CameraAgentApp).appGraph
        val repo = appGraph.settingsRepository

        chipSnapshot.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "JPEG Snapshot",
                path = "/snapshot.jpg",
                method = "GET, HEAD",
                contentType = "image/jpeg",
                description = "单张实时抓图拍照。已为 Home Assistant 通用相机优化 HEAD 请求探活机制，不引发无效唤醒。",
                authRequired = repo.authEnabled,
                haRole = "Home Assistant Generic Camera: still_image_url",
            )
        }

        chipStream.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "MJPEG Live Stream",
                path = "/video.mjpeg",
                method = "GET, HEAD",
                contentType = "multipart/x-mixed-replace; boundary=frameboundary",
                description = "标准连续 MJPEG 视频流传输。支持在电脑浏览器、VLC、Home Assistant 中直接播放。",
                authRequired = repo.authEnabled,
                haRole = "Home Assistant Generic Camera: stream_source",
            )
        }

        chipStatus.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Device Telemetry & Status",
                path = "/api/status",
                method = "GET",
                contentType = "application/json",
                description = "查询当前摄像头运行状态、前后镜头、分辨率、实际帧率、硬件限制、在线连接数与系统信息。",
                authRequired = repo.authEnabled,
            )
        }

        chipMcp.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Model Context Protocol (MCP)",
                path = "/mcp",
                method = "POST (JSON-RPC 2.0), GET (Discover)",
                contentType = "application/json",
                description = "标准 MCP 智能体协议。支持 get_snapshot, get_status, set_camera_facing, set_camera_enabled, set_camera_config, warmup_camera 等工具。",
                authRequired = repo.authEnabled,
            )
        }

        chipCameraConfig.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Camera Runtime Config",
                path = "/api/camera",
                method = "GET, POST, PATCH",
                contentType = "application/json",
                description = "通过 REST 接口读取或动态修改分辨率、FPS、JPEG 压缩质量、保活时长与镜头方向。",
                authRequired = repo.authEnabled,
            )
        }

        chipWarmup.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Hardware Warmup",
                path = "/api/v1/warmup",
                method = "POST",
                contentType = "application/json",
                description = "提前激活并预热 Camera2 硬件指定秒数（默认 5s），消除抓图首帧冷启动耗时。",
                authRequired = repo.authEnabled,
            )
        }

        chipHealth.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Health Probe",
                path = "/health",
                method = "GET",
                contentType = "application/json",
                description = "轻量级服务健康探活端点，用于反向代理或监控。即使开启 Basic Auth 也始终公开免密。",
                authRequired = false,
            )
        }
    }

    private fun showEndpointDetail(
        title: String,
        path: String,
        method: String,
        contentType: String,
        description: String,
        authRequired: Boolean,
        haRole: String? = null,
    ) {
        val appGraph = (application as CameraAgentApp).appGraph
        val ip = getIpAddress() ?: "127.0.0.1"
        val port = appGraph.settingsRepository.httpPort
        val fullUrl = "http://$ip:$port${appGraph.settingsRepository.httpPathPrefix}$path"

        refreshEndpointDetail = { showEndpointDetail(title, path, method, contentType, description, authRequired = appGraph.settingsRepository.authEnabled && path != "/health", haRole = haRole) }
        // Update inline summary box
        val inlineText = buildString {
            append("$title\n")
            append("URL:     $fullUrl\n")
            append("Method:  $method\n")
            append("Type:    $contentType\n")
            append("Auth:    ${if (authRequired) "需 Basic Auth" else "免密公开"}\n")
            if (haRole != null) {
                append("HA:      $haRole\n")
            }
            append("说明:    $description")
        }
        if (tvEndpointSummary.text.toString() != inlineText) tvEndpointSummary.text = inlineText
    }

    private fun setFacing(facing: LensFacing) {
        lifecycleScope.launch {
            val appGraph = (application as CameraAgentApp).appGraph
            val current = appGraph.coordinator.config.value
            if (current.facing != facing) {
                val updated = current.copy(facing = facing)
                appGraph.coordinator.updateConfig(updated)
                appGraph.settingsRepository.updateConfig(updated)
                refreshUi()
            }
        }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQ_CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val appGraph = (application as CameraAgentApp).appGraph
            appGraph.refreshCapabilities()
            refreshUi()
        }
    }

    private fun ensureServiceStarted() {
        if (!AgentService.isRunning) {
            AppLog.i("MainActivity: Starting AgentService in foreground...")
            val intent = Intent(this, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun observeSystemState() {
        val appGraph = (application as CameraAgentApp).appGraph

        // Camera state & config
        lifecycleScope.launch {
            appGraph.coordinator.state.collect {
                refreshUi()
            }
        }
        lifecycleScope.launch {
            appGraph.coordinator.config.collect {
                refreshUi()
            }
        }

        // MQTT connection state
        lifecycleScope.launch {
            appGraph.mqttManager.status.collect { refreshUi() }
        }
    }

    private fun refreshUi() {
        val appGraph = (application as CameraAgentApp).appGraph
        val config = appGraph.coordinator.config.value
        val state = appGraph.coordinator.state.value
        val repo = appGraph.settingsRepository

        listOf("HTTP", "MCP", "MQTT").forEach { name ->
            val box = findViewById<LinearLayout>(R.id.topBar).findViewWithTag<android.widget.CheckBox>(name)
            val checked = when (name) { "HTTP" -> repo.httpEnabled; "MCP" -> repo.mcpEnabled; else -> repo.mqttEnabled }
            if (box != null && box.isChecked != checked) {
                isBinding = true
                box.isChecked = checked
                isBinding = false
            }
        }
        // 1. Service Switch & Indicator
        isBinding = true
        tbCameraEnable.isChecked = repo.serviceEnabled
        isBinding = false

        if (!repo.serviceEnabled) {
            statusLed.setBackgroundResource(R.drawable.led_grey)
            tvStatusText.text = "整体服务 · 已关闭"
            tvStatusText.setTextColor(0xFFA3A8B7.toInt())
        } else {
            statusLed.setBackgroundResource(R.drawable.led_green)
            tvStatusText.text = "整体服务 · 已开启"
            tvStatusText.setTextColor(0xFFA8C7BA.toInt())
        }

        val listener = appGraph.httpServer
        setStatusIndicator(findViewById(R.id.tvHttpStatus),
            serviceLine("HTTP", repo.httpEnabled, repo.serviceEnabled && listener.isRunning && repo.httpEnabled))
        setStatusIndicator(findViewById(R.id.tvMcpStatus),
            serviceLine("MCP", repo.mcpEnabled, repo.serviceEnabled && listener.isRunning && repo.mcpEnabled))
        findViewById<TextView>(R.id.tvHttpConnections).text = "${listener.httpConnections} 个连接"
        findViewById<TextView>(R.id.tvMcpConnections).text = "${listener.mcpConnections} 个连接"
        val mqttLine = when {
            !repo.mqttEnabled -> "⚪ MQTT · 已停用"
            !repo.serviceEnabled -> "⚪ MQTT · 整体服务已关闭"
            appGraph.mqttManager.status.value is MqttStatus.Connected -> "🟢 MQTT · 已连接"
            appGraph.mqttManager.status.value is MqttStatus.Connecting -> "🟠 MQTT · 连接中"
            else -> "🟠 MQTT · 未连接"
        }
        setStatusIndicator(tvMqttStatus, mqttLine)

        // 2. Camera Hardware Physical State Indicator
        when (state) {
            is CameraState.Active -> {
                cameraHardwareLed.setBackgroundResource(R.drawable.led_green)
                tvCameraHardwareStatus.text = "相机传输中"
                tvCameraHardwareStatus.setTextColor(0xFFA8C7BA.toInt())
            }
            is CameraState.Retained -> {
                cameraHardwareLed.setBackgroundResource(R.drawable.led_green)
                tvCameraHardwareStatus.text = "相机使用中 · 预热保持"
            }
            is CameraState.Opening -> {
                cameraHardwareLed.setBackgroundResource(R.drawable.led_amber)
                tvCameraHardwareStatus.text = "相机打开中"
                tvCameraHardwareStatus.setTextColor(0xFFD7BE8E.toInt())
            }
            is CameraState.Failed -> {
                cameraHardwareLed.setBackgroundResource(R.drawable.led_grey)
                tvCameraHardwareStatus.text = "相机异常"
                tvCameraHardwareStatus.setTextColor(0xFFE6A5A5.toInt())
            }
            else -> {
                cameraHardwareLed.setBackgroundResource(R.drawable.led_grey)
                tvCameraHardwareStatus.text = "相机空闲"
                tvCameraHardwareStatus.setTextColor(0xFFA3A8B7.toInt())
            }
        }

        // 3. Compact Segmented Facing Switch in Top Bar
        if (config.facing == LensFacing.FRONT) {
            btnFacingFront.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFBAC6EA.toInt())
            btnFacingFront.setTextColor(0xFF111318.toInt())
            btnFacingBack.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF303643.toInt())
            btnFacingBack.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btnFacingBack.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFBAC6EA.toInt())
            btnFacingBack.setTextColor(0xFF111318.toInt())
            btnFacingFront.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF303643.toInt())
            btnFacingFront.setTextColor(0xFFFFFFFF.toInt())
        }

        val hasFront = appGraph.capabilities.any { it.facing == LensFacing.FRONT }
        btnFacingFront.isEnabled = hasFront

        // 4. Modern Endpoint Card & Auth Status
        val ip = getIpAddress() ?: "127.0.0.1"
        val port = repo.httpPort
        val baseUrl = "http://$ip:$port${repo.httpPathPrefix}"
        tvBaseEndpoint.text = baseUrl
        refreshEndpointDetail?.invoke()

        if (repo.authEnabled) {
            tvAuthBadge.visibility = View.VISIBLE
            tvAuthBadge.text = "认证 · ${repo.authUsername}"
            tvAuthBadge.setTextColor(0xFFD7BE8E.toInt())
            tvAuthBadge.setBackgroundColor(0xFF2E2415.toInt())

            chipSnapshot.text = "/snapshot.jpg"
            chipStream.text = "/video.mjpeg"
            chipStatus.text = "/api/status"
            chipMcp.text = "/mcp"
            chipCameraConfig.text = "/api/camera"
            chipWarmup.text = "/api/v1/warmup"
            chipHealth.text = "/health"
        } else {
            tvAuthBadge.visibility = View.GONE

            chipSnapshot.text = "/snapshot.jpg"
            chipStream.text = "/video.mjpeg"
            chipStatus.text = "/api/status"
            chipMcp.text = "/mcp"
            chipCameraConfig.text = "/api/camera"
            chipWarmup.text = "/api/v1/warmup"
            chipHealth.text = "/health"
        }

        val activeStreams = (state as? CameraState.Active)?.activeStreams ?: 0
        val serviceStatus = if (repo.serviceEnabled) "Service: ON" else "Service: SUSPENDED (Dummy)"
        tvDeviceStatus.text = "Device: ${Build.MODEL} | $serviceStatus | Cam: ${state::class.simpleName} | Streams: $activeStreams | IP: $ip"
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (itf in interfaces) {
                if (!itf.isUp || itf.isLoopback) continue
                val addrs = Collections.list(itf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    // --- Standby Dimming Logic ---

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        wakeScreen()
        return super.dispatchTouchEvent(ev)
    }

    private fun wakeScreen() {
        if (isDimmed) {
            setDimmed(false)
        }
        val appGraph = (application as CameraAgentApp).appGraph
        val timeoutMs = appGraph.settingsRepository.dimTimeoutSec * 1000L
        handler.removeCallbacks(dimTask)
        handler.postDelayed(dimTask, timeoutMs)
    }

    private fun setDimmed(dim: Boolean) {
        isDimmed = dim
        val appGraph = (application as CameraAgentApp).appGraph
        val lp = window.attributes
        lp.screenBrightness = if (dim) appGraph.settingsRepository.dimBrightness else -1f
        window.attributes = lp
        contentLayout.visibility = if (dim) View.INVISIBLE else View.VISIBLE
    }
}
