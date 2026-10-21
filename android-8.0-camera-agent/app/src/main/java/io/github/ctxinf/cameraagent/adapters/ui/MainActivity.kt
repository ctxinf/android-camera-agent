package io.github.ctxinf.cameraagent.adapters.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.adapters.mqtt.MqttStatus
import io.github.ctxinf.cameraagent.bootstrap.AgentService
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
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
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var btnSettingsIcon: ImageButton

    // Services Card
    private lateinit var dotHttp: View
    private lateinit var tvHttpStatusText: TextView
    private lateinit var tvHttpConnections: TextView
    private lateinit var switchHttp: MaterialSwitch

    private lateinit var dotMcp: View
    private lateinit var tvMcpStatusText: TextView
    private lateinit var tvMcpConnections: TextView
    private lateinit var switchMcp: MaterialSwitch

    private lateinit var layoutMqttRow: LinearLayout
    private lateinit var dotMqtt: View
    private lateinit var tvMqttStatusText: TextView
    private lateinit var tvMqttConnections: TextView
    private lateinit var switchMqtt: MaterialSwitch

    // Camera Card
    private lateinit var cameraHardwareLed: View
    private lateinit var tvCameraHardwareStatus: TextView
    private lateinit var btnFacingBack: Button
    private lateinit var btnFacingFront: Button
    private lateinit var btnFacingDisabled: Button
    private lateinit var btnRotation: Button
    private lateinit var btnLocalPreview: Button

    // Connections & Endpoints Card
    private lateinit var tvAuthBadge: TextView
    private lateinit var tvBaseEndpoint: TextView
    private lateinit var btnCopyUrl: ImageButton
    private lateinit var btnQuickSnapshot: Button
    private lateinit var btnQuickVideo: Button
    private lateinit var chipSnapshot: Chip
    private lateinit var chipStream: Chip
    private lateinit var chipStatus: Chip
    private lateinit var chipMcp: Chip
    private lateinit var chipCameraConfig: Chip
    private lateinit var chipWarmup: Chip
    private lateinit var chipHealth: Chip
    private lateinit var tvEndpointSummary: TextView

    // Background Service Card (At Bottom)
    private lateinit var badgeStatusContainer: FrameLayout
    private lateinit var ivStatusBadgeIcon: ImageView
    private lateinit var tvMainServiceTitle: TextView
    private lateinit var switchMainService: MaterialSwitch

    // Footer
    private lateinit var tvDeviceStatus: TextView

    private var isDimmed = false
    private var isBinding = false
    private var refreshEndpointDetail: (() -> Unit)? = null

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

        initViews()
        setupListeners()
        ensureCameraPermission()
        ensureServiceStarted()
        observeSystemState()
        wakeScreen()
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
        contentLayout = findViewById(R.id.contentLayout)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        btnSettingsIcon = findViewById(R.id.btnSettingsIcon)

        // Services
        dotHttp = findViewById(R.id.dotHttp)
        tvHttpStatusText = findViewById(R.id.tvHttpStatusText)
        tvHttpConnections = findViewById(R.id.tvHttpConnections)
        switchHttp = findViewById(R.id.switchHttp)

        dotMcp = findViewById(R.id.dotMcp)
        tvMcpStatusText = findViewById(R.id.tvMcpStatusText)
        tvMcpConnections = findViewById(R.id.tvMcpConnections)
        switchMcp = findViewById(R.id.switchMcp)

        layoutMqttRow = findViewById(R.id.layoutMqttRow)
        dotMqtt = findViewById(R.id.dotMqtt)
        tvMqttStatusText = findViewById(R.id.tvMqttStatusText)
        tvMqttConnections = findViewById(R.id.tvMqttConnections)
        switchMqtt = findViewById(R.id.switchMqtt)

        // Camera
        cameraHardwareLed = findViewById(R.id.cameraHardwareLed)
        tvCameraHardwareStatus = findViewById(R.id.tvCameraHardwareStatus)
        btnFacingBack = findViewById(R.id.btnFacingBack)
        btnFacingFront = findViewById(R.id.btnFacingFront)
        btnFacingDisabled = findViewById(R.id.btnFacingDisabled)
        btnRotation = findViewById(R.id.btnRotation)
        btnLocalPreview = findViewById(R.id.btnLocalPreview)

        // Endpoints
        tvAuthBadge = findViewById(R.id.tvAuthBadge)
        tvBaseEndpoint = findViewById(R.id.tvBaseEndpoint)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        btnQuickSnapshot = findViewById(R.id.btnQuickSnapshot)
        btnQuickVideo = findViewById(R.id.btnQuickVideo)
        chipSnapshot = findViewById(R.id.chipSnapshot)
        chipStream = findViewById(R.id.chipStream)
        chipStatus = findViewById(R.id.chipStatus)
        chipMcp = findViewById(R.id.chipMcp)
        chipCameraConfig = findViewById(R.id.chipCameraConfig)
        chipWarmup = findViewById(R.id.chipWarmup)
        chipHealth = findViewById(R.id.chipHealth)
        tvEndpointSummary = findViewById(R.id.tvEndpointSummary)

        // Bottom Background Service Card
        badgeStatusContainer = findViewById(R.id.badgeStatusContainer)
        ivStatusBadgeIcon = findViewById(R.id.ivStatusBadgeIcon)
        tvMainServiceTitle = findViewById(R.id.tvMainServiceTitle)
        switchMainService = findViewById(R.id.switchMainService)

        // Footer
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
    }

    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
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
        btnThemeToggle.setOnClickListener {
            wakeScreen()
            val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val nextMode = if (isNight) "light" else "dark"
            val appGraph = (application as CameraAgentApp).appGraph
            appGraph.settingsRepository.setThemeMode(nextMode)
            (application as CameraAgentApp).applyTheme(nextMode)
            Toast.makeText(this, if (nextMode == "dark") getString(R.string.ui_switched_to_dark_mode) else getString(R.string.ui_switched_to_light_mode), Toast.LENGTH_SHORT).show()
        }

        btnSettingsIcon.setOnClickListener {
            wakeScreen()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val appGraph = (application as CameraAgentApp).appGraph
        val repo = appGraph.settingsRepository

        // Main Background Service Switch
        switchMainService.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            wakeScreen()
            lifecycleScope.launch {
                repo.setBackgroundEnabled(isChecked)
                (application as CameraAgentApp).refreshBackgroundPolicy()
                refreshUi()
            }
        }

        // HTTP Switch
        switchHttp.setOnCheckedChangeListener { _, enabled ->
            if (isBinding) return@setOnCheckedChangeListener
            wakeScreen()
            repo.setHttpEnabled(enabled)
            appGraph.appScope.launch {
                try {
                    appGraph.refreshNetworkListener()
                } catch (e: Exception) {
                    AppLog.e("HTTP restart failed", e)
                }
            }
            refreshUi()
        }

        // MCP Switch
        switchMcp.setOnCheckedChangeListener { _, enabled ->
            if (isBinding) return@setOnCheckedChangeListener
            wakeScreen()
            repo.setMcpEnabled(enabled)
            appGraph.appScope.launch {
                try {
                    appGraph.refreshNetworkListener()
                } catch (e: Exception) {
                    AppLog.e("MCP restart failed", e)
                }
            }
            refreshUi()
        }

        // MQTT Switch
        switchMqtt.setOnCheckedChangeListener { _, enabled ->
            if (isBinding) return@setOnCheckedChangeListener
            wakeScreen()
            repo.setMqttEnabled(enabled)
            appGraph.mqttManager.restart()
            refreshUi()
        }

        layoutMqttRow.setOnClickListener {
            wakeScreen()
            val status = appGraph.mqttManager.status.value
            val msg = when (status) {
                is MqttStatus.Disabled -> getString(R.string.ui_mqtt_not_enabled_can_be_configured_and_turned_on_in_settings)
                is MqttStatus.Connecting -> getString(R.string.ui_mqtt_connecting)
                is MqttStatus.Connected -> getString(R.string.ui_mqtt_connected, status.broker)
                is MqttStatus.Disconnected -> getString(R.string.ui_mqtt_connection_lost, status.reason)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Camera facing buttons
        btnFacingBack.setOnClickListener {
            wakeScreen()
            setFacing(LensFacing.BACK)
        }

        btnFacingFront.setOnClickListener {
            wakeScreen()
            val hasFront = appGraph.capabilities.any { it.facing == LensFacing.FRONT }
            if (!hasFront || repo.frontCameraDisabled) {
                Toast.makeText(this, if (!hasFront) getString(R.string.ui_device_has_no_front_camera) else getString(R.string.ui_the_front_camera_is_disabled_in_settings), Toast.LENGTH_SHORT).show()
            } else {
                setFacing(LensFacing.FRONT)
            }
        }

        btnFacingDisabled.setOnClickListener {
            wakeScreen()
            lifecycleScope.launch {
                appGraph.cameraAccess.updateLocal { it.copy(enabled = false) }
                refreshUi()
            }
        }

        btnRotation.setOnClickListener {
            wakeScreen()
            lifecycleScope.launch {
                appGraph.cameraAccess.updateLocal {
                    it.copy(rotationOffset = (it.rotationOffset + 90) % 360)
                }
            }
        }

        btnLocalPreview.setOnClickListener {
            wakeScreen()
            startActivity(Intent(this, PreviewActivity::class.java))
        }

        // Copy URL
        btnCopyUrl.setOnClickListener {
            wakeScreen()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Camera Agent URL", tvBaseEndpoint.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.ui_the_access_address_has_been_copied_to_the_clipboard), Toast.LENGTH_SHORT).show()
        }

        btnQuickSnapshot.setOnClickListener {
            wakeScreen()
            openUrlOrDetail("/snapshot.jpg")
        }

        btnQuickVideo.setOnClickListener {
            wakeScreen()
            openUrlOrDetail("/video.mjpeg")
        }

        listOf(chipSnapshot, chipStream).forEach { chip ->
            chip.shapeAppearanceModel = chip.shapeAppearanceModel.toBuilder()
                .setTopRightCornerSize(0f)
                .setBottomRightCornerSize(0f)
                .build()
        }
        setupChipListeners()
    }

    private fun openUrlOrDetail(path: String) {
        val appGraph = (application as CameraAgentApp).appGraph
        val ip = getIpAddress() ?: "127.0.0.1"
        val port = appGraph.settingsRepository.httpPort
        val fullUrl = "http://$ip:$port${appGraph.settingsRepository.httpPathPrefix}$path"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
            startActivity(intent)
        } catch (_: Exception) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", fullUrl))
            Toast.makeText(this, getString(R.string.ui_copied, fullUrl), Toast.LENGTH_SHORT).show()
        }
    }

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
                description = getString(R.string.ui_take_a_single_real_time_snapshot_supports_head_activity_detection),
                authRequired = repo.authEnabled,
                haRole = "Home Assistant MJPEG IP Camera: Still Image URL"
            )
        }

        chipStream.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "MJPEG Live Stream",
                path = "/video.mjpeg",
                method = "GET, HEAD",
                contentType = "multipart/x-mixed-replace; boundary=frameboundary",
                description = getString(R.string.ui_standard_continuous_mjpeg_video_streaming_supports_browser_and_vl),
                authRequired = repo.authEnabled,
                haRole = "Home Assistant MJPEG IP Camera: MJPEG URL"
            )
        }

        chipStatus.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Device Telemetry & Status",
                path = "/api/status",
                method = "GET",
                contentType = "application/json",
                description = getString(R.string.ui_query_the_current_camera_running_status_front_and_rear_lenses_res),
                authRequired = repo.authEnabled
            )
        }

        chipMcp.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Model Context Protocol (MCP)",
                path = "/mcp",
                method = "POST (JSON-RPC 2.0), GET (Discover)",
                contentType = "application/json",
                description = getString(R.string.ui_standard_mcp_agent_protocol_supports_get_snapshot_get_status_set_),
                authRequired = repo.authEnabled
            )
        }

        chipCameraConfig.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Camera Runtime Config",
                path = "/api/camera",
                method = "GET, POST, PATCH",
                contentType = "application/json",
                description = getString(R.string.ui_read_or_dynamically_modify_the_resolution_fps_jpeg_compression_qu),
                authRequired = repo.authEnabled
            )
        }

        chipWarmup.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Hardware Warmup",
                path = "/api/v1/warmup",
                method = "POST",
                contentType = "application/json",
                description = getString(R.string.ui_activate_and_warm_up_the_camera2_hardware_for_a_specified_number_),
                authRequired = repo.authEnabled
            )
        }

        chipHealth.setOnClickListener {
            wakeScreen()
            showEndpointDetail(
                title = "Health Probe",
                path = "/health",
                method = "GET",
                contentType = "application/json",
                description = getString(R.string.ui_lightweight_service_health_probe_endpoint_password_free_access_is),
                authRequired = false
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

        refreshEndpointDetail = {
            showEndpointDetail(
                title, path, method, contentType, description,
                authRequired = appGraph.settingsRepository.authEnabled && path != "/health",
                haRole = haRole
            )
        }

        val inlineText = buildString {
            append("$title\n")
            append("URL:     $fullUrl\n")
            append("Method:  $method\n")
            append("Type:    $contentType\n")
            append("Auth:    ${if (authRequired) getString(R.string.pref_auth_enabled) else getString(R.string.state_off)}\n")
            if (haRole != null) {
                append("HA:      $haRole\n")
            }
            append(getString(R.string.ui_description, description))
        }
        if (tvEndpointSummary.text.toString() != inlineText) {
            tvEndpointSummary.text = inlineText
        }
    }

    private fun setFacing(facing: LensFacing) {
        lifecycleScope.launch {
            val appGraph = (application as CameraAgentApp).appGraph
            if (facing == LensFacing.FRONT && appGraph.settingsRepository.frontCameraDisabled) {
                Toast.makeText(this@MainActivity, getString(R.string.ui_the_front_camera_is_disabled_in_settings), Toast.LENGTH_SHORT).show()
                return@launch
            }
            appGraph.cameraAccess.updateLocal { it.copy(facing = facing, enabled = true) }
            refreshUi()
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
        if ((application as CameraAgentApp).appGraph.settingsRepository.manuallyExited) return
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

        lifecycleScope.launch {
            appGraph.coordinator.state.collect { refreshUi() }
        }
        lifecycleScope.launch {
            appGraph.coordinator.config.collect { refreshUi() }
        }
        lifecycleScope.launch {
            appGraph.mqttManager.status.collect { refreshUi() }
        }
    }

    private val httpCountDisplay = ConnectionCountDisplay()
    private val mcpCountDisplay = ConnectionCountDisplay()

    private fun refreshUi() {
        val appGraph = (application as CameraAgentApp).appGraph
        val config = appGraph.coordinator.config.value
        val state = appGraph.coordinator.state.value
        val repo = appGraph.settingsRepository
        val listener = appGraph.httpServer

        isBinding = true

        // Theme Toggle Icon
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        btnThemeToggle.setImageResource(if (isNight) R.drawable.ic_brightness else R.drawable.ic_dark_mode)
        btnThemeToggle.contentDescription = if (isNight) getString(R.string.ui_switch_to_light_mode) else getString(R.string.ui_switch_to_dark_mode)

        // 1. Services Card
        switchHttp.isChecked = repo.httpEnabled
        val httpRunning = listener.isRunning && repo.httpEnabled
        dotHttp.setBackgroundResource(if (httpRunning) R.drawable.led_green else R.drawable.led_grey)
        tvHttpStatusText.text = if (httpRunning) getString(R.string.ui_running) else getString(R.string.ui_deactivated)
        tvHttpStatusText.setTextColor(getColor(if (httpRunning) R.color.ui_success else R.color.ui_text_secondary))
        tvHttpConnections.text = getString(R.string.ui_connections, httpCountDisplay.update(if (httpRunning) listener.httpConnections else 0, android.os.SystemClock.elapsedRealtime(), !httpRunning))

        switchMcp.isChecked = repo.mcpEnabled
        val mcpRunning = repo.backgroundEnabled && listener.isRunning && repo.mcpEnabled
        dotMcp.setBackgroundResource(if (mcpRunning) R.drawable.led_green else R.drawable.led_grey)
        tvMcpStatusText.text = if (mcpRunning) getString(R.string.ui_running) else getString(R.string.ui_deactivated)
        tvMcpStatusText.setTextColor(getColor(if (mcpRunning) R.color.ui_success else R.color.ui_text_secondary))
        tvMcpConnections.text = getString(R.string.ui_connections, mcpCountDisplay.update(if (mcpRunning) listener.mcpConnections else 0, android.os.SystemClock.elapsedRealtime(), !mcpRunning))

        switchMqtt.isChecked = repo.mqttEnabled
        val mqttStatus = appGraph.mqttManager.status.value
        when {
            !repo.mqttEnabled -> {
                dotMqtt.setBackgroundResource(R.drawable.led_grey)
                tvMqttStatusText.text = getString(R.string.ui_deactivated)
                tvMqttStatusText.setTextColor(getColor(R.color.ui_text_secondary))
                tvMqttConnections.text = getString(R.string.ui_0_connections)
            }
            mqttStatus is MqttStatus.Connected -> {
                dotMqtt.setBackgroundResource(R.drawable.led_green)
                tvMqttStatusText.text = getString(R.string.ui_connected)
                tvMqttStatusText.setTextColor(getColor(R.color.ui_success))
                tvMqttConnections.text = mqttStatus.broker
            }
            mqttStatus is MqttStatus.Connecting -> {
                dotMqtt.setBackgroundResource(R.drawable.led_amber)
                tvMqttStatusText.text = getString(R.string.ui_connecting)
                tvMqttStatusText.setTextColor(getColor(R.color.ui_warning))
                tvMqttConnections.text = getString(R.string.ui_connecting_2)
            }
            else -> {
                dotMqtt.setBackgroundResource(R.drawable.led_amber)
                tvMqttStatusText.text = getString(R.string.ui_not_connected)
                tvMqttStatusText.setTextColor(getColor(R.color.ui_warning))
                tvMqttConnections.text = getString(R.string.ui_not_connected)
            }
        }

        // 2. Camera Card
        val isCamActive = state is CameraState.Active || state is CameraState.Retained
        val isCamOpening = state is CameraState.Opening || state is CameraState.Closing
        cameraHardwareLed.setBackgroundResource(
            when {
                isCamActive -> R.drawable.led_green
                isCamOpening -> R.drawable.led_amber
                else -> R.drawable.led_grey
            }
        )
        tvCameraHardwareStatus.text = when {
            !config.enabled -> getString(R.string.ui_disabled_2)
            isCamActive -> getString(R.string.ui_running)
            isCamOpening -> getString(R.string.ui_starting)
            else -> getString(R.string.ui_standby)
        }
        tvCameraHardwareStatus.setTextColor(
            getColor(
                when {
                    !config.enabled -> R.color.ui_error
                    isCamActive -> R.color.ui_success
                    isCamOpening -> R.color.ui_warning
                    else -> R.color.ui_success
                }
            )
        )

        // Segmented buttons
        val isBack = config.enabled && config.facing == LensFacing.BACK
        val isFront = config.enabled && config.facing == LensFacing.FRONT
        val isDisabled = !config.enabled

        updateSegmentedButton(btnFacingBack, isBack)
        updateSegmentedButton(btnFacingFront, isFront)
        updateSegmentedButton(btnFacingDisabled, isDisabled)

        val hasFront = appGraph.capabilities.any { it.facing == LensFacing.FRONT }
        btnFacingFront.alpha = if (hasFront && !repo.frontCameraDisabled) 1f else 0.4f

        btnRotation.text = getString(R.string.ui_rotate, config.rotationOffset)
        updateSegmentedButton(btnRotation, config.rotationOffset != 0)

        // 3. Endpoints Card
        val ip = getIpAddress() ?: "127.0.0.1"
        val port = repo.httpPort
        val baseUrl = "http://$ip:$port${repo.httpPathPrefix}"
        tvBaseEndpoint.text = baseUrl
        refreshEndpointDetail?.invoke()

        if (repo.authEnabled) {
            tvAuthBadge.visibility = View.VISIBLE
            tvAuthBadge.text = getString(R.string.ui_certification, repo.authUsername)
        } else {
            tvAuthBadge.visibility = View.GONE
        }

        // 4. Background Service Card (At Bottom)
        switchMainService.isChecked = repo.backgroundEnabled
        if (repo.backgroundEnabled) {
            badgeStatusContainer.backgroundTintList = ColorStateList.valueOf(getColor(R.color.badge_running_bg))
            ivStatusBadgeIcon.setImageResource(R.drawable.ic_check)
            ivStatusBadgeIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.badge_running_icon))
            tvMainServiceTitle.text = getString(R.string.ui_background_service_is_enabled)
        } else {
            badgeStatusContainer.backgroundTintList = ColorStateList.valueOf(getColor(R.color.badge_stopped_bg))
            ivStatusBadgeIcon.setImageResource(R.drawable.ic_power_settings_new)
            ivStatusBadgeIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.badge_stopped_icon))
            tvMainServiceTitle.text = getString(R.string.ui_background_service_stopped)
        }

        val activeStreams = (state as? CameraState.Active)?.activeStreams ?: 0
        val serviceStatus = if (repo.backgroundEnabled) getString(R.string.ui_backstage_on) else getString(R.string.ui_backend_close)
        tvDeviceStatus.text = getString(R.string.ui_lens_stream_ip, Build.MODEL, serviceStatus, state::class.simpleName, activeStreams, ip)

        isBinding = false
    }

    private fun updateSegmentedButton(button: Button, selected: Boolean) {
        button.isSelected = selected
        val bgColor = if (selected) getColor(R.color.control_selected_bg) else getColor(R.color.control_normal_bg)
        val textColor = if (selected) getColor(R.color.control_selected_text) else getColor(R.color.control_normal_text)

        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.setTextColor(textColor)
        if (button is MaterialButton) {
            button.iconTint = ColorStateList.valueOf(textColor)
        } else {
            button.compoundDrawableTintList = ColorStateList.valueOf(textColor)
        }
    }

    private fun getIpAddress(): String? {
        val bound = (application as CameraAgentApp).appGraph.settingsRepository.httpBindAddress
        if (bound != "0.0.0.0") return bound
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
        } catch (_: Exception) {}
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
        lp.screenBrightness = (application as CameraAgentApp).screenBrightnessOverride() ?: if (dim) appGraph.settingsRepository.dimBrightness else -1f
        window.attributes = lp
        contentLayout.visibility = if (dim) View.INVISIBLE else View.VISIBLE
    }
}
