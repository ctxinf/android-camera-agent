package io.github.ctxinf.cameraagent.adapters.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.preference.CheckBoxPreference
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

/** Platform preferences, matching the legacy edition. Existing typed settings stay intact. */
@Suppress("DEPRECATION")
class SettingsActivity : PreferenceActivity() {
    private val graph get() = (application as CameraAgentApp).appGraph
    private val repo get() = graph.settingsRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var resolution: ListPreference
    private lateinit var battery: Preference
    private val authFields = mutableListOf<Preference>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceScreen = preferenceManager.createPreferenceScreen(this)
        listView.setBackgroundColor(getColor(R.color.ui_background))
        val inset = (8 * resources.displayMetrics.density).toInt()
        listView.setPadding(inset, 0, inset, 0)

        val display = category(R.string.cat_display)
        number(display, "dim_timeout_sec", R.string.pref_dim_timeout, repo.dimTimeoutSec, 5..3600, R.string.unit_seconds) { repo.setDimTimeoutSec(it) }
        number(display, "dim_brightness", R.string.pref_dim_brightness, (repo.dimBrightness * 100).toInt(), 0..100, R.string.unit_percent) { repo.setDimBrightness(it / 100f) }
        battery = action(display, "battery", R.string.btn_battery_opt) { requestBatteryBypass() }

        val camera = category(R.string.cat_camera)
        toggle(camera, "camera_enabled", R.string.pref_camera_access, repo.config.value.enabled) { enabled ->
            updateCamera { it.copy(enabled = enabled) }
        }
        val caps = graph.capabilities.ifEmpty { graph.refreshCapabilities() }
        val facings = caps.map { it.facing }.distinct().ifEmpty { listOf(LensFacing.BACK) }
        choice(camera, "camera_facing", R.string.pref_default_facing,
            facings.map { if (it == LensFacing.FRONT) getString(R.string.facing_front) else getString(R.string.facing_back) },
            facings.map { it.name }, repo.config.value.facing.name) { value ->
            updateCamera { it.copy(facing = LensFacing.valueOf(value)) }
            refreshResolutions(LensFacing.valueOf(value))
        }
        resolution = choice(camera, "resolution", R.string.pref_resolution, emptyList(), emptyList(), "") { value ->
            val (width, height) = value.split('x').map { it.toInt() }
            updateCamera { it.copy(targetWidth = width, targetHeight = height) }
        }
        refreshResolutions(repo.config.value.facing)
        number(camera, "target_fps", R.string.pref_target_fps, repo.config.value.targetFps, 1..30, R.string.unit_fps) { fps -> updateCamera { it.copy(targetFps = fps) } }
        number(camera, "jpeg_quality", R.string.pref_jpeg_quality, repo.config.value.jpegQuality, 10..100) { quality -> updateCamera { it.copy(jpegQuality = quality) } }
        number(camera, "retention_grace_sec", R.string.pref_retention_grace_period, repo.config.value.retentionGracePeriodSec, 0..3600, R.string.unit_seconds) { seconds -> updateCamera { it.copy(retentionGracePeriodSec = seconds) } }

        val network = category(R.string.cat_network)
        toggle(network, "http_enabled", R.string.pref_http_enabled, repo.httpEnabled) {
            repo.setHttpEnabled(it)
            refreshNetworkServices()
        }
        toggle(network, "mcp_enabled", R.string.pref_mcp_enabled, repo.mcpEnabled) {
            repo.setMcpEnabled(it)
            refreshNetworkServices()
        }
        number(network, "http_port", R.string.pref_http_port, repo.httpPort, 1024..65535) { port ->
            if (port != repo.httpPort) {
                repo.setHttpPort(port)
                if (AgentService.isRunning) graph.appScope.launch {
                    try {
                        graph.refreshNetworkListener()
                    } catch (e: Exception) {
                        AppLog.e("HTTP restart failed", e)
                        runOnUiThread { toast(getString(R.string.http_restart_failed)) }
                    }
                }
                graph.mqttManager.restart()
            }
        }
        edit(network, "http_path_prefix", R.string.pref_http_path_prefix, repo.httpPathPrefix,
            hint = getString(R.string.http_path_prefix_hint), normalize = HttpPathPrefix::normalize) {
            repo.setHttpPathPrefix(it)
            graph.mqttManager.restart()
        }
        toggle(network, "auth_enabled", R.string.pref_auth_enabled, repo.authEnabled) { enabled ->
            repo.setAuthEnabled(enabled)
            authFields.forEach { it.isEnabled = enabled }
        }
        authFields += edit(network, "auth_username", R.string.pref_auth_username, repo.authUsername) { repo.setAuthUsername(it) }
        authFields += edit(network, "auth_password", R.string.pref_auth_password, repo.authPassword, password = true) { repo.setAuthPassword(it) }
        authFields.forEach { it.isEnabled = repo.authEnabled }

        val mqtt = category(R.string.pref_mqtt_title)
        toggle(mqtt, "mqtt_enabled", R.string.pref_mqtt_enabled, repo.mqttEnabled) { enabled ->
            repo.setMqttEnabled(enabled)
            graph.mqttManager.restart()
        }
        edit(mqtt, "mqtt_host", R.string.pref_mqtt_host, repo.mqttHost) { repo.setMqttHost(it); graph.mqttManager.restart() }
        number(mqtt, "mqtt_port", R.string.pref_mqtt_port, repo.mqttPort, 1..65535) { repo.setMqttPort(it); graph.mqttManager.restart() }
        edit(mqtt, "mqtt_client_id", R.string.pref_mqtt_client_id, repo.mqttClientId,
            normalize = { it.trim().ifEmpty { throw IllegalArgumentException(getString(R.string.value_required)) } }) { repo.setMqttClientId(it); graph.mqttManager.restart() }
        edit(mqtt, "mqtt_username", R.string.pref_auth_username, repo.mqttUsername) { repo.setMqttUsername(it); graph.mqttManager.restart() }
        edit(mqtt, "mqtt_password", R.string.pref_auth_password, repo.mqttPassword, password = true) { repo.setMqttPassword(it); graph.mqttManager.restart() }
        edit(mqtt, "mqtt_topic_prefix", R.string.pref_mqtt_topic_prefix, repo.mqttTopicPrefix,
            normalize = { it.trim().ifEmpty { "camera_agent" } }) { repo.setMqttTopicPrefix(it); graph.mqttManager.restart() }
        action(mqtt, "mqtt_test", R.string.btn_test_mqtt) { testMqtt(it) }

        val tools = category(R.string.cat_tools)
        action(tools, "preview", R.string.btn_preview) { startActivity(Intent(this, PreviewActivity::class.java)) }
        action(tools, "diagnostics", R.string.btn_open_diagnostics) { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
    }

    private fun refreshNetworkServices() {
        graph.appScope.launch {
            try { graph.refreshNetworkListener() }
            catch (e: Exception) {
                AppLog.e("Listener restart failed", e)
                runOnUiThread { toast(getString(R.string.http_restart_failed)) }
            }
        }
    }

    private fun category(title: Int) = PreferenceCategory(this).also {
        it.setTitle(title)
        preferenceScreen.addPreference(it)
    }

    private fun action(parent: PreferenceCategory, key: String, title: Int, onClick: (Preference) -> Unit) = Preference(this).also {
        it.key = key
        it.setTitle(title)
        parent.addPreference(it)
        it.setOnPreferenceClickListener { pref -> onClick(pref); true }
    }

    private fun edit(parent: PreferenceCategory, key: String, title: Int, value: String,
                     password: Boolean = false, hint: String? = null,
                     normalize: (String) -> String = { it.trim() }, save: (String) -> Unit): EditTextPreference {
        val pref = EditTextPreference(this)
        pref.key = key
        pref.isPersistent = false
        pref.setTitle(title)
        pref.dialogTitle = getString(title)
        pref.dialogMessage = hint
        pref.editText.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        fun showSummary(text: String) {
            pref.summary = when {
                text.isEmpty() && key == "http_path_prefix" -> getString(R.string.http_path_prefix_empty)
                text.isEmpty() -> getString(R.string.pref_unset)
                password -> "••••••"
                else -> text
            }
        }
        pref.text = value
        showSummary(value)
        parent.addPreference(pref)
        pref.setOnPreferenceChangeListener { _, newValue ->
            try {
                val normalized = normalize(newValue.toString())
                save(normalized)
                pref.text = normalized
                showSummary(normalized)
            } catch (e: IllegalArgumentException) { toast(e.message ?: getString(R.string.invalid_value)) }
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(pref.editText.windowToken, 0)
            false // Store through the typed repository, never PreferenceManager's string store.
        }
        return pref
    }

    private fun number(parent: PreferenceCategory, key: String, title: Int, value: Int, range: IntRange,
                       unit: Int? = null, save: (Int) -> Unit): EditTextPreference {
        val pref = edit(parent, key, title, value.toString(), normalize = { raw ->
            val number = raw.toIntOrNull()
            require(number != null && number in range) { getString(R.string.number_range, range.first, range.last) }
            number.toString()
        }) { save(it.toInt()) }
        pref.editText.inputType = InputType.TYPE_CLASS_NUMBER
        pref.dialogMessage = getString(R.string.number_range, range.first, range.last)
        fun summary(text: String) = if (unit == null) text else getString(unit, text.toInt())
        pref.summary = summary(pref.text)
        val validateAndSave = pref.onPreferenceChangeListener
        pref.setOnPreferenceChangeListener { p, v ->
            validateAndSave.onPreferenceChange(p, v)
            pref.summary = summary(pref.text)
            false
        }
        return pref
    }

    private fun toggle(parent: PreferenceCategory, key: String, title: Int, value: Boolean, save: (Boolean) -> Unit) {
        val pref = CheckBoxPreference(this)
        pref.key = key
        pref.isPersistent = false
        pref.setTitle(title)
        pref.isChecked = value
        parent.addPreference(pref)
        pref.setOnPreferenceChangeListener { _, newValue -> save(newValue as Boolean); true }
    }

    private fun choice(parent: PreferenceCategory, key: String, title: Int, labels: List<String>, values: List<String>,
                       current: String, save: (String) -> Unit): ListPreference = ListPreference(this).also { pref ->
        pref.key = key
        pref.isPersistent = false
        pref.setTitle(title)
        pref.dialogTitle = getString(title)
        pref.entries = labels.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.value = current
        pref.summary = pref.entry
        parent.addPreference(pref)
        pref.setOnPreferenceChangeListener { _, value ->
            save(value.toString())
            pref.value = value.toString()
            pref.summary = pref.entry
            false
        }
    }

    private fun refreshResolutions(facing: LensFacing) {
        val config = repo.config.value
        val current = "${config.targetWidth}x${config.targetHeight}"
        val supported = graph.capabilities.firstOrNull { it.facing == facing }?.supportedSizes.orEmpty()
            .map { "${it.width}x${it.height}" }
        val values = (supported + current).distinct()
        resolution.entries = values.map { it.replace("x", " × ") }.toTypedArray()
        resolution.entryValues = values.toTypedArray()
        resolution.value = current
        resolution.summary = resolution.entry
    }

    private fun updateCamera(change: (CameraConfig) -> CameraConfig) {
        // Application scope lets an accepted setting finish applying even after Back is pressed.
        graph.appScope.launch(Dispatchers.Main.immediate) {
            val config = change(repo.config.value)
            repo.updateConfig(config)
            graph.coordinator.updateConfig(config)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::battery.isInitialized) battery.setSummary(if (ignoresBatteryOptimization()) R.string.battery_opt_enabled else R.string.battery_opt_hint)
    }

    private fun ignoresBatteryOptimization() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryBypass() {
        if (ignoresBatteryOptimization()) return
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (request.resolveActivity(packageManager) != null) startActivity(request)
        else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun testMqtt(pref: Preference) {
        pref.isEnabled = false
        pref.setSummary(R.string.mqtt_testing)
        uiScope.launch {
            val result = MqttClient.testConnection(repo.mqttHost, repo.mqttPort,
                repo.mqttClientId + "-test", repo.mqttUsername.ifEmpty { null }, repo.mqttPassword.ifEmpty { null }, 5000)
            pref.isEnabled = true
            pref.setSummary(if (result.success) R.string.mqtt_test_success else R.string.mqtt_test_failed)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
