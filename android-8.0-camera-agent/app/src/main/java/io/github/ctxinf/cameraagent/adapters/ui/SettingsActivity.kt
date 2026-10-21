package io.github.ctxinf.cameraagent.adapters.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.text.InputFilter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.adapters.mqtt.MqttClient
import io.github.ctxinf.cameraagent.bootstrap.AgentService
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.HttpPathPrefix
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val graph get() = (application as CameraAgentApp).appGraph
    private val repo get() = graph.settingsRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isBinding = false

    // Display
    private lateinit var tvDimTimeoutValue: TextView
    private lateinit var tvDimBrightnessValue: TextView
    private lateinit var tvBatteryValue: TextView
    private lateinit var tvThemeModeValue: TextView

    // Camera
    private lateinit var switchDisableFront: MaterialSwitch
    private lateinit var tvCameraFacingValue: TextView
    private lateinit var tvResolutionValue: TextView
    private lateinit var tvTargetFpsValue: TextView
    private lateinit var tvJpegQualityValue: TextView
    private lateinit var tvRetentionGraceValue: TextView

    // Network
    private lateinit var tvHttpPortValue: TextView
    private lateinit var tvHttpPrefixValue: TextView
    private lateinit var switchAuthEnabled: MaterialSwitch
    private lateinit var itemAuthUsername: View
    private lateinit var divAuthUsername: View
    private lateinit var tvAuthUsernameValue: TextView
    private lateinit var itemAuthPassword: View
    private lateinit var divAuthPassword: View
    private lateinit var tvAuthPasswordValue: TextView

    // MQTT
    private lateinit var switchMqttEnabled: MaterialSwitch
    private lateinit var tvMqttHostValue: TextView
    private lateinit var tvMqttPortValue: TextView
    private lateinit var tvMqttClientIdValue: TextView
    private lateinit var tvMqttTopicPrefixValue: TextView
    private lateinit var tvMqttTestValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener { finish() }

        initViews()
        setupClickListeners()
        refreshUi()
    }

    private fun initViews() {
        // Display
        tvDimTimeoutValue = findViewById(R.id.tvDimTimeoutValue)
        tvDimBrightnessValue = findViewById(R.id.tvDimBrightnessValue)
        tvBatteryValue = findViewById(R.id.tvBatteryValue)
        tvThemeModeValue = findViewById(R.id.tvThemeModeValue)

        // Camera
        switchDisableFront = findViewById(R.id.switchDisableFront)
        tvCameraFacingValue = findViewById(R.id.tvCameraFacingValue)
        tvResolutionValue = findViewById(R.id.tvResolutionValue)
        tvTargetFpsValue = findViewById(R.id.tvTargetFpsValue)
        tvJpegQualityValue = findViewById(R.id.tvJpegQualityValue)
        tvRetentionGraceValue = findViewById(R.id.tvRetentionGraceValue)

        // Network
        tvHttpPortValue = findViewById(R.id.tvHttpPortValue)
        tvHttpPrefixValue = findViewById(R.id.tvHttpPrefixValue)
        switchAuthEnabled = findViewById(R.id.switchAuthEnabled)
        itemAuthUsername = findViewById(R.id.itemAuthUsername)
        divAuthUsername = findViewById(R.id.divAuthUsername)
        tvAuthUsernameValue = findViewById(R.id.tvAuthUsernameValue)
        itemAuthPassword = findViewById(R.id.itemAuthPassword)
        divAuthPassword = findViewById(R.id.divAuthPassword)
        tvAuthPasswordValue = findViewById(R.id.tvAuthPasswordValue)

        // MQTT
        switchMqttEnabled = findViewById(R.id.switchMqttEnabled)
        tvMqttHostValue = findViewById(R.id.tvMqttHostValue)
        tvMqttPortValue = findViewById(R.id.tvMqttPortValue)
        tvMqttClientIdValue = findViewById(R.id.tvMqttClientIdValue)
        tvMqttTopicPrefixValue = findViewById(R.id.tvMqttTopicPrefixValue)
        tvMqttTestValue = findViewById(R.id.tvMqttTestValue)
    }

    private fun setupClickListeners() {
        // --- Display ---
        findViewById<View>(R.id.itemDimTimeout).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_dim_timeout),
                currentValue = repo.dimTimeoutSec,
                range = 5..3600,
                unit = "秒"
            ) { value ->
                repo.setDimTimeoutSec(value)
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemDimBrightness).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_dim_brightness),
                currentValue = (repo.dimBrightness * 100).toInt(),
                range = 0..100,
                unit = "%"
            ) { value ->
                repo.setDimBrightness(value / 100f)
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemBattery).setOnClickListener {
            requestBatteryBypass()
        }

        findViewById<View>(R.id.itemThemeMode).setOnClickListener {
            val themes = listOf(
                "跟随系统 (默认)" to "system",
                "浅色模式" to "light",
                "深色模式" to "dark"
            )
            val currentIndex = themes.indexOfFirst { it.second == repo.themeMode }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("外观与深色模式")
                .setSingleChoiceItems(themes.map { it.first }.toTypedArray(), currentIndex) { dialog, which ->
                    val selectedMode = themes[which].second
                    repo.setThemeMode(selectedMode)
                    (application as CameraAgentApp).applyTheme(selectedMode)
                    refreshUi()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // --- Camera ---
        switchDisableFront.setOnCheckedChangeListener { _, disabled ->
            if (isBinding) return@setOnCheckedChangeListener
            graph.appScope.launch(Dispatchers.Main.immediate) {
                graph.cameraAccess.setFrontDisabled(disabled)
                refreshUi()
            }
            if (disabled) Toast.makeText(this, "前置摄像头已禁用", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.itemCameraFacing).setOnClickListener {
            val caps = graph.capabilities.ifEmpty { graph.refreshCapabilities() }
            val facings = caps.map { it.facing }.distinct().ifEmpty { listOf(LensFacing.BACK) }
            val labels = facings.map { if (it == LensFacing.FRONT) getString(R.string.facing_front) else getString(R.string.facing_back) }
            val currentIdx = facings.indexOf(repo.config.value.facing).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pref_default_facing)
                .setSingleChoiceItems(labels.toTypedArray(), currentIdx) { dialog, which ->
                    val chosen = facings[which]
                    if (chosen == LensFacing.FRONT && repo.frontCameraDisabled) {
                        Toast.makeText(this, "前置摄像头已在设置中禁用", Toast.LENGTH_SHORT).show()
                    } else {
                        updateCamera { it.copy(facing = chosen) }
                        refreshUi()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        findViewById<View>(R.id.itemResolution).setOnClickListener {
            val facing = repo.config.value.facing
            val config = repo.config.value
            val current = "${config.targetWidth}x${config.targetHeight}"
            val supported = graph.capabilities.firstOrNull { it.facing == facing }?.supportedSizes.orEmpty()
                .map { "${it.width}x${it.height}" }
            val values = (supported + current).distinct()
            val labels = values.map { it.replace("x", " × ") }
            val currentIdx = values.indexOf(current).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pref_resolution)
                .setSingleChoiceItems(labels.toTypedArray(), currentIdx) { dialog, which ->
                    val (w, h) = values[which].split('x').map { it.toInt() }
                    updateCamera { it.copy(targetWidth = w, targetHeight = h) }
                    refreshUi()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        findViewById<View>(R.id.itemTargetFps).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_target_fps),
                currentValue = repo.config.value.targetFps,
                range = 1..30,
                unit = "帧/秒"
            ) { fps ->
                updateCamera { it.copy(targetFps = fps) }
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemJpegQuality).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_jpeg_quality),
                currentValue = repo.config.value.jpegQuality,
                range = 10..100,
                unit = ""
            ) { quality ->
                updateCamera { it.copy(jpegQuality = quality) }
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemRetentionGrace).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_retention_grace_period),
                currentValue = repo.config.value.retentionGracePeriodSec,
                range = 0..3600,
                unit = "秒"
            ) { seconds ->
                updateCamera { it.copy(retentionGracePeriodSec = seconds) }
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemHttpBindAddress).setOnClickListener { showBindAddressDialog() }
        // --- Network ---
        findViewById<View>(R.id.itemHttpPort).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_http_port),
                currentValue = repo.httpPort,
                range = 1024..65535,
                unit = ""
            ) { port ->
                if (port != repo.httpPort) {
                    repo.setHttpPort(port)
                    refreshNetworkServices()
                    graph.mqttManager.restart()
                    refreshUi()
                }
            }
        }

        findViewById<View>(R.id.itemHttpPrefix).setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_http_path_prefix),
                currentValue = repo.httpPathPrefix,
                hint = getString(R.string.http_path_prefix_hint)
            ) { raw ->
                val normalized = HttpPathPrefix.normalize(raw)
                repo.setHttpPathPrefix(normalized)
                graph.mqttManager.restart()
                refreshUi()
            }
        }

        switchAuthEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isBinding) return@setOnCheckedChangeListener
            repo.setAuthEnabled(enabled)
            refreshUi()
        }

        itemAuthUsername.setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_auth_username),
                currentValue = repo.authUsername,
                hint = "输入用户名"
            ) { username ->
                if (username.isNotBlank()) {
                    repo.setAuthUsername(username)
                    refreshUi()
                }
            }
        }

        itemAuthPassword.setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_auth_password),
                currentValue = repo.authPassword,
                hint = "输入访问密码",
                isPassword = true
            ) { password ->
                if (password.isNotBlank()) {
                    repo.setAuthPassword(password)
                    refreshUi()
                }
            }
        }

        findViewById<View>(R.id.itemMqttUsername).setOnClickListener {
            showTextDialog(getString(R.string.pref_mqtt_username), repo.mqttUsername) {
                repo.setMqttUsername(it)
                graph.mqttManager.restart()
                refreshUi()
            }
        }
        findViewById<View>(R.id.itemMqttPassword).setOnClickListener {
            showTextDialog(getString(R.string.pref_mqtt_password), repo.mqttPassword, isPassword = true, trimValue = false) {
                repo.setMqttPassword(it)
                graph.mqttManager.restart()
                refreshUi()
            }
        }
        // --- MQTT ---
        switchMqttEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isBinding) return@setOnCheckedChangeListener
            repo.setMqttEnabled(enabled)
            graph.mqttManager.restart()
            refreshUi()
        }

        findViewById<View>(R.id.itemMqttHost).setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_mqtt_host),
                currentValue = repo.mqttHost,
                hint = "例如: 192.168.1.5 或 broker.emqx.io"
            ) { host ->
                repo.setMqttHost(host)
                graph.mqttManager.restart()
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemMqttPort).setOnClickListener {
            showNumberDialog(
                title = getString(R.string.pref_mqtt_port),
                currentValue = repo.mqttPort,
                range = 1..65535,
                unit = ""
            ) { port ->
                repo.setMqttPort(port)
                graph.mqttManager.restart()
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemMqttClientId).setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_mqtt_client_id),
                currentValue = repo.mqttClientId,
                hint = "客户端唯一标识"
            ) { id ->
                if (id.isNotBlank()) {
                    repo.setMqttClientId(id)
                    graph.mqttManager.restart()
                    refreshUi()
                }
            }
        }

        findViewById<View>(R.id.itemMqttTopicPrefix).setOnClickListener {
            showTextDialog(
                title = getString(R.string.pref_mqtt_topic_prefix),
                currentValue = repo.mqttTopicPrefix,
                hint = "例如: camera_agent"
            ) { prefix ->
                repo.setMqttTopicPrefix(prefix.ifBlank { "camera_agent" })
                graph.mqttManager.restart()
                refreshUi()
            }
        }

        findViewById<View>(R.id.itemMqttTest).setOnClickListener {
            testMqtt()
        }

        // --- Tools ---
        findViewById<View>(R.id.itemPreview).setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }

        findViewById<View>(R.id.itemDiagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        // Danger Exit
        findViewById<Button>(R.id.btnExitApp).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("强制退出程序")
                .setMessage("停止所有连接和相机，退出进程并禁止自启动，直到你再次手动打开应用。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("退出") { _, _ ->
                    (application as CameraAgentApp).forceExit()
                }
                .show()
        }
    }

    private fun refreshUi() {
        isBinding = true

        // Display
        tvDimTimeoutValue.text = "${repo.dimTimeoutSec} 秒"
        tvDimBrightnessValue.text = "${(repo.dimBrightness * 100).toInt()}%"
        tvBatteryValue.text = if (ignoresBatteryOptimization()) getString(R.string.battery_opt_enabled) else getString(R.string.battery_opt_hint)
        tvThemeModeValue.text = when (repo.themeMode) {
            "light" -> "浅色模式"
            "dark" -> "深色模式"
            else -> "跟随系统 (默认)"
        }

        // Camera
        switchDisableFront.isChecked = repo.frontCameraDisabled
        val config = repo.config.value
        tvCameraFacingValue.text = if (config.facing == LensFacing.FRONT) getString(R.string.facing_front) else getString(R.string.facing_back)
        tvResolutionValue.text = "${config.targetWidth} × ${config.targetHeight}"
        tvTargetFpsValue.text = "${config.targetFps} 帧/秒"
        tvJpegQualityValue.text = "${config.jpegQuality}"
        tvRetentionGraceValue.text = "${config.retentionGracePeriodSec} 秒"

        // Network
        findViewById<TextView>(R.id.tvHttpBindAddressValue).text = repo.httpBindAddress
        tvHttpPortValue.text = "${repo.httpPort}"
        tvHttpPrefixValue.text = repo.httpPathPrefix.ifEmpty { getString(R.string.http_path_prefix_empty) }
        switchAuthEnabled.isChecked = repo.authEnabled
        val authVisible = if (repo.authEnabled) View.VISIBLE else View.GONE
        itemAuthUsername.visibility = authVisible
        divAuthUsername.visibility = authVisible
        itemAuthPassword.visibility = authVisible
        divAuthPassword.visibility = authVisible
        tvAuthUsernameValue.text = repo.authUsername
        tvAuthPasswordValue.text = if (repo.authPassword.isNotEmpty()) "••••••" else getString(R.string.pref_unset)

        // MQTT
        findViewById<TextView>(R.id.tvMqttUsernameValue).text = repo.mqttUsername.ifEmpty { getString(R.string.pref_unset) }
        findViewById<TextView>(R.id.tvMqttPasswordValue).text = if (repo.mqttPassword.isEmpty()) getString(R.string.pref_unset) else "••••••"
        switchMqttEnabled.isChecked = repo.mqttEnabled
        tvMqttHostValue.text = repo.mqttHost.ifEmpty { getString(R.string.pref_unset) }
        tvMqttPortValue.text = "${repo.mqttPort}"
        tvMqttClientIdValue.text = repo.mqttClientId
        tvMqttTopicPrefixValue.text = repo.mqttTopicPrefix

        isBinding = false
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun ignoresBatteryOptimization(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryBypass() {
        if (ignoresBatteryOptimization()) {
            Toast.makeText(this, R.string.battery_opt_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (request.resolveActivity(packageManager) != null) {
            startActivity(request)
        } else {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun testMqtt() {
        tvMqttTestValue.text = getString(R.string.mqtt_testing)
        uiScope.launch {
            val result = MqttClient.testConnection(
                repo.mqttHost,
                repo.mqttPort,
                repo.mqttClientId + "-test",
                repo.mqttUsername.ifEmpty { null },
                repo.mqttPassword.ifEmpty { null },
                5000
            )
            tvMqttTestValue.text = if (result.success) getString(R.string.mqtt_test_success) else getString(R.string.mqtt_test_failed)
        }
    }

    private fun updateCamera(change: (CameraConfig) -> CameraConfig) {
        graph.appScope.launch(Dispatchers.Main.immediate) {
            try {
                graph.cameraAccess.updateLocal(change)
                refreshUi()
            } catch (e: SecurityException) {
                Toast.makeText(this@SettingsActivity, e.message ?: "相机访问被禁用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshNetworkServices() {
        graph.appScope.launch {
            try {
                graph.refreshNetworkListener()
            } catch (e: Exception) {
                AppLog.e("Listener restart failed", e)
                runOnUiThread { Toast.makeText(this@SettingsActivity, R.string.http_restart_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showBindAddressDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val parts = repo.httpBindAddress.split('.')
        val inputs = (0..3).map { index ->
            if (index > 0) row.addView(TextView(this).apply { text = "." })
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(3))
                gravity = android.view.Gravity.CENTER
                contentDescription = "IPv4 第 ${index + 1} 段"
                setText(parts.getOrElse(index) { "0" })
                row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        container.addView(row)
        val addresses = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct().sorted()
        }.getOrDefault(emptyList())
        val options = listOf("选择本机 IP…", "0.0.0.0（所有接口）") + addresses
        container.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    val address = if (position == 1) "0.0.0.0" else options[position]
                    address.split('.').forEachIndexed { index, part -> inputs[index].setText(part) }
                }
            }
        })
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_http_bind_address)
            .setMessage("0.0.0.0 监听所有接口；HTTP 与 MCP 共用此监听地址。")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = inputs.map { it.text.toString().toIntOrNull() }
                if (values.any { it == null || it !in 0..255 }) {
                    inputs.forEachIndexed { index, input ->
                        if (values[index] == null || values[index] !in 0..255) input.error = "请输入 0–255"
                    }
                } else {
                    repo.setHttpBindAddress(values.joinToString("."))
                    refreshNetworkServices()
                    graph.mqttManager.restart()
                    refreshUi()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showNumberDialog(
        title: String,
        currentValue: Int,
        range: IntRange,
        unit: String,
        onSave: (Int) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
            setTextColor(getColor(R.color.ui_text))
            setBackgroundResource(R.drawable.surface_field)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val container = FrameLayout(this).apply {
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(if (unit.isNotEmpty()) "范围: ${range.first} ~ ${range.last} $unit" else "范围: ${range.first} ~ ${range.last}")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val num = input.text.toString().toIntOrNull()
                if (num != null && num in range) {
                    onSave(num)
                } else {
                    Toast.makeText(this, getString(R.string.number_range, range.first, range.last), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTextDialog(
        title: String,
        currentValue: String,
        hint: String? = null,
        isPassword: Boolean = false,
        trimValue: Boolean = true,
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = if (isPassword) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setText(currentValue)
            setSelection(text.length)
            this.hint = hint
            setHintTextColor(getColor(R.color.ui_text_tertiary))
            setTextColor(getColor(R.color.ui_text))
            setBackgroundResource(R.drawable.surface_field)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val container = FrameLayout(this).apply {
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(if (trimValue) input.text.toString().trim() else input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
