package io.github.ctxinf.cameraagent.adapters.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.adapters.mqtt.MqttClient
import io.github.ctxinf.cameraagent.bootstrap.AgentService
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Size
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var etHttpPort: EditText
    private lateinit var rgFacing: RadioGroup
    private lateinit var rbFacingBack: RadioButton
    private lateinit var rbFacingFront: RadioButton
    private lateinit var spResolution: Spinner
    private lateinit var spFps: Spinner
    private lateinit var etQuality: EditText
    private lateinit var etDimTimeout: EditText
    private lateinit var etRetentionGracePeriod: EditText

    // Basic Auth Views
    private lateinit var cbAuthEnabled: CheckBox
    private lateinit var layoutAuthFields: LinearLayout
    private lateinit var etAuthUsername: EditText
    private lateinit var etAuthPassword: EditText

    // MQTT Views
    private lateinit var cbMqttEnabled: CheckBox
    private lateinit var layoutMqttFields: LinearLayout
    private lateinit var etMqttHost: EditText
    private lateinit var etMqttPort: EditText
    private lateinit var etMqttClientId: EditText
    private lateinit var etMqttUsername: EditText
    private lateinit var etMqttPassword: EditText
    private lateinit var etMqttTopicPrefix: EditText
    private lateinit var btnTestMqtt: Button
    private lateinit var tvMqttTestResult: TextView

    // Battery Optimization View
    private lateinit var btnBatteryOpt: Button

    private lateinit var btnSave: Button
    private lateinit var btnOpenDiagnostics: Button
    private lateinit var btnPreview: Button

    private var availableResolutions = listOf<Size>()
    private val availableFpsList = listOf(5, 10, 15, 20, 25, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        AppLog.i("SettingsActivity onCreate()")

        btnBack = findViewById(R.id.btnBack)
        etHttpPort = findViewById(R.id.etHttpPort)
        rgFacing = findViewById(R.id.rgFacing)
        rbFacingBack = findViewById(R.id.rbFacingBack)
        rbFacingFront = findViewById(R.id.rbFacingFront)
        spResolution = findViewById(R.id.spResolution)
        spFps = findViewById(R.id.spFps)
        etQuality = findViewById(R.id.etQuality)
        etDimTimeout = findViewById(R.id.etDimTimeout)
        etRetentionGracePeriod = findViewById(R.id.etRetentionGracePeriod)

        cbAuthEnabled = findViewById(R.id.cbAuthEnabled)
        layoutAuthFields = findViewById(R.id.layoutAuthFields)
        etAuthUsername = findViewById(R.id.etAuthUsername)
        etAuthPassword = findViewById(R.id.etAuthPassword)

        cbMqttEnabled = findViewById(R.id.cbMqttEnabled)
        layoutMqttFields = findViewById(R.id.layoutMqttFields)
        etMqttHost = findViewById(R.id.etMqttHost)
        etMqttPort = findViewById(R.id.etMqttPort)
        etMqttClientId = findViewById(R.id.etMqttClientId)
        etMqttUsername = findViewById(R.id.etMqttUsername)
        etMqttPassword = findViewById(R.id.etMqttPassword)
        etMqttTopicPrefix = findViewById(R.id.etMqttTopicPrefix)
        btnTestMqtt = findViewById(R.id.btnTestMqtt)
        tvMqttTestResult = findViewById(R.id.tvMqttTestResult)

        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnSave = findViewById(R.id.btnSave)
        btnOpenDiagnostics = findViewById(R.id.btnOpenDiagnostics)
        btnPreview = findViewById(R.id.btnPreview)

        setupListeners()
        setupForm()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptButton()
    }

    private fun setupListeners() {
        cbAuthEnabled.setOnCheckedChangeListener { _, isChecked ->
            layoutAuthFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        cbMqttEnabled.setOnCheckedChangeListener { _, isChecked ->
            layoutMqttFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnTestMqtt.setOnClickListener {
            testMqttConnection()
        }

        btnBatteryOpt.setOnClickListener {
            requestBatteryOptimizationBypass()
        }

        btnBack.setOnClickListener { finish() }

        btnOpenDiagnostics.setOnClickListener {
            AppLog.i("SettingsActivity: Opening DiagnosticsActivity")
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        btnPreview.setOnClickListener {
            AppLog.i("SettingsActivity: Opening PreviewActivity")
            startActivity(Intent(this, PreviewActivity::class.java))
        }

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun setupForm() {
        val appGraph = (application as CameraAgentApp).appGraph
        val repo = appGraph.settingsRepository
        val currentConfig = repo.config.value

        etHttpPort.setText(repo.httpPort.toString())
        etQuality.setText(currentConfig.jpegQuality.toString())
        etDimTimeout.setText(repo.dimTimeoutSec.toString())
        etRetentionGracePeriod.setText(currentConfig.retentionGracePeriodSec.toString())

        // Basic Auth
        cbAuthEnabled.isChecked = repo.authEnabled
        layoutAuthFields.visibility = if (repo.authEnabled) View.VISIBLE else View.GONE
        etAuthUsername.setText(repo.authUsername)
        etAuthPassword.setText(repo.authPassword)

        // MQTT
        cbMqttEnabled.isChecked = repo.mqttEnabled
        layoutMqttFields.visibility = if (repo.mqttEnabled) View.VISIBLE else View.GONE
        etMqttHost.setText(repo.mqttHost)
        etMqttPort.setText(repo.mqttPort.toString())
        etMqttClientId.setText(repo.mqttClientId)
        etMqttUsername.setText(repo.mqttUsername)
        etMqttPassword.setText(repo.mqttPassword)
        etMqttTopicPrefix.setText(repo.mqttTopicPrefix)

        refreshBatteryOptButton()

        if (currentConfig.facing == LensFacing.FRONT) {
            rbFacingFront.isChecked = true
        } else {
            rbFacingBack.isChecked = true
        }

        // Setup Resolutions
        val caps = if (appGraph.capabilities.isEmpty()) appGraph.refreshCapabilities() else appGraph.capabilities
        val activeCap = caps.firstOrNull { it.facing == currentConfig.facing } ?: caps.firstOrNull()
        availableResolutions = activeCap?.supportedSizes ?: listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480))

        val resStrings = availableResolutions.map { "${it.width} x ${it.height}" }
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resStrings)
        spResolution.adapter = resAdapter

        val curResIndex = availableResolutions.indexOfFirst {
            it.width == currentConfig.targetWidth && it.height == currentConfig.targetHeight
        }
        if (curResIndex >= 0) {
            spResolution.setSelection(curResIndex)
        }

        // Setup FPS
        val fpsStrings = availableFpsList.map { "$it fps" }
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsStrings)
        spFps.adapter = fpsAdapter

        val curFpsIndex = availableFpsList.indexOf(currentConfig.targetFps)
        if (curFpsIndex >= 0) {
            spFps.setSelection(curFpsIndex)
        }

        rgFacing.setOnCheckedChangeListener { _, checkedId ->
            val newFacing = if (checkedId == R.id.rbFacingFront) LensFacing.FRONT else LensFacing.BACK
            val cap = caps.firstOrNull { it.facing == newFacing } ?: caps.firstOrNull()
            if (cap != null) {
                availableResolutions = cap.supportedSizes
                val updatedResStrings = availableResolutions.map { "${it.width} x ${it.height}" }
                val updatedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, updatedResStrings)
                spResolution.adapter = updatedAdapter
            }
        }
    }

    private fun refreshBatteryOptButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
            if (isIgnoring) {
                btnBatteryOpt.text = "✓ 锁屏常驻: 已忽略电池优化 (正常)"
                btnBatteryOpt.backgroundTintList = ColorStateList.valueOf(0xFF404D70.toInt())
            } else {
                btnBatteryOpt.text = "⚠ 锁屏常驻: 点此授权忽略电池优化"
                btnBatteryOpt.backgroundTintList = ColorStateList.valueOf(0xFF675048.toInt())
            }
        }
    }

    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                Toast.makeText(this, "应用已处于电池优化白名单 (锁屏常驻已就绪)", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallback)
                }
            }
        }
    }

    private fun testMqttConnection() {
        val host = etMqttHost.text.toString().trim()
        val port = etMqttPort.text.toString().toIntOrNull() ?: 1883
        val clientId = etMqttClientId.text.toString().trim().ifEmpty { "test-client-${(1000..9999).random()}" }
        val user = etMqttUsername.text.toString().trim().ifEmpty { null }
        val pass = etMqttPassword.text.toString().trim().ifEmpty { null }

        tvMqttTestResult.visibility = View.VISIBLE
        tvMqttTestResult.text = "正在尝试连接 MQTT Broker ($host:$port)..."
        tvMqttTestResult.setTextColor(0xFFBAC6EA.toInt())
        btnTestMqtt.isEnabled = false

        lifecycleScope.launch {
            val result = MqttClient.testConnection(
                host = host,
                port = port,
                clientId = clientId,
                username = user,
                password = pass,
                timeoutMs = 5000,
            )
            btnTestMqtt.isEnabled = true
            tvMqttTestResult.text = result.message
            if (result.success) {
                tvMqttTestResult.setTextColor(0xFFA8C7BA.toInt())
            } else {
                tvMqttTestResult.setTextColor(0xFFE6A5A5.toInt())
            }
        }
    }

    private fun saveSettings() {
        val appGraph = (application as CameraAgentApp).appGraph
        val repo = appGraph.settingsRepository
        val oldPort = repo.httpPort

        val newPort = etHttpPort.text.toString().toIntOrNull() ?: 8080
        val newQuality = etQuality.text.toString().toIntOrNull()?.coerceIn(10, 100) ?: 80
        val newDimTimeout = etDimTimeout.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 30
        val newRetentionGrace = etRetentionGracePeriod.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 5
        val newFacing = if (rbFacingFront.isChecked) LensFacing.FRONT else LensFacing.BACK

        val selectedRes = if (spResolution.selectedItemPosition in availableResolutions.indices) {
            availableResolutions[spResolution.selectedItemPosition]
        } else {
            Size(1280, 720)
        }

        val selectedFps = if (spFps.selectedItemPosition in availableFpsList.indices) {
            availableFpsList[spFps.selectedItemPosition]
        } else {
            15
        }

        repo.setHttpPort(newPort)
        repo.setDimTimeoutSec(newDimTimeout)

        // Auth
        repo.setAuthEnabled(cbAuthEnabled.isChecked)
        repo.setAuthUsername(etAuthUsername.text.toString().trim().ifEmpty { "admin" })
        repo.setAuthPassword(etAuthPassword.text.toString().trim().ifEmpty { "password" })

        // MQTT
        repo.setMqttEnabled(cbMqttEnabled.isChecked)
        repo.setMqttHost(etMqttHost.text.toString().trim())
        repo.setMqttPort(etMqttPort.text.toString().toIntOrNull() ?: 1883)
        repo.setMqttClientId(etMqttClientId.text.toString().trim())
        repo.setMqttUsername(etMqttUsername.text.toString().trim())
        repo.setMqttPassword(etMqttPassword.text.toString().trim())
        repo.setMqttTopicPrefix(etMqttTopicPrefix.text.toString().trim().ifEmpty { "camera_agent" })
        appGraph.mqttManager.restart()

        val updatedConfig = repo.config.value.copy(
            facing = newFacing,
            targetWidth = selectedRes.width,
            targetHeight = selectedRes.height,
            targetFps = selectedFps,
            jpegQuality = newQuality,
            retentionGracePeriodSec = newRetentionGrace,
        )

        lifecycleScope.launch {
            appGraph.coordinator.updateConfig(updatedConfig)
            repo.updateConfig(updatedConfig)
            AppLog.i("Settings saved: Port=$newPort, Facing=$newFacing, Res=${selectedRes.width}x${selectedRes.height}, Fps=$selectedFps, Quality=$newQuality, RetentionGrace=${newRetentionGrace}s, Mqtt=${cbMqttEnabled.isChecked}")

            if (oldPort != newPort && AgentService.isRunning) {
                AppLog.i("HTTP port changed from $oldPort to $newPort, restarting AgentService...")
                stopService(Intent(this@SettingsActivity, AgentService::class.java))
                val startIntent = Intent(this@SettingsActivity, AgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            }

            Toast.makeText(this@SettingsActivity, "设置已保存 (Settings saved)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
