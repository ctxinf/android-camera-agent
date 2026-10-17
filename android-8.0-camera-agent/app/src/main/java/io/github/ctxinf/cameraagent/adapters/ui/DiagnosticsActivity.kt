package io.github.ctxinf.cameraagent.adapters.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.bootstrap.AgentService
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCameraInfo: TextView
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var btnBack: View
    private lateinit var btnProbe: Button
    private lateinit var btnStartService: Button
    private lateinit var btnSnapshot: Button
    private lateinit var btnClearLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        AppLog.i("DiagnosticsActivity onCreate()")

        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvStatus = findViewById(R.id.tvStatus)
        tvCameraInfo = findViewById(R.id.tvCameraInfo)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        btnBack = findViewById(R.id.btnBack)
        btnProbe = findViewById(R.id.btnProbe)
        btnStartService = findViewById(R.id.btnStartService)
        btnSnapshot = findViewById(R.id.btnSnapshot)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        btnBack.setOnClickListener {
            finish()
        }

        setupDeviceInfo()
        setupLogListener()
        setupButtons()
        refreshStatusAndCameras()
    }

    override fun onResume() {
        super.onResume()
        setupDeviceInfo()
        refreshStatusAndCameras()
    }

    private fun setupDeviceInfo() {
        val ip = getIpAddress() ?: "Unknown"
        val info = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}) | IP: $ip"
        tvDeviceInfo.text = info
    }

    private fun setupButtons() {
        btnProbe.setOnClickListener {
            AppLog.i("Manual camera probe requested")
            val appGraph = (application as CameraAgentApp).appGraph
            val caps = appGraph.refreshCapabilities()
            renderCameraInfo(caps)
        }

        btnStartService.setOnClickListener {
            if (AgentService.isRunning) {
                stopAgentService()
            } else {
                startAgentService()
            }
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = ""
        }

        btnSnapshot.setOnClickListener {
            triggerSnapshot()
        }
    }

    private fun triggerSnapshot() {
        lifecycleScope.launch {
            try {
                AppLog.i("Diagnostics: Triggering CameraCoordinator.takeSnapshot()...")
                val appGraph = (application as CameraAgentApp).appGraph
                val startMs = System.currentTimeMillis()
                val snapshot = appGraph.cameraController.takeSnapshot()
                val duration = System.currentTimeMillis() - startMs
                AppLog.i(
                    "Snapshot SUCCESS in ${duration}ms: ${snapshot.width}x${snapshot.height}, " +
                        "${snapshot.jpegBytes.size} bytes, rotation=${snapshot.rotation}°"
                )
            } catch (e: Exception) {
                AppLog.e("Snapshot failed: ${e.message}", e)
            }
        }
    }

    private fun startAgentService() {
        if (!AgentService.isRunning) {
            AppLog.i("Starting AgentService...")
            val intent = Intent(this, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.postDelayed({ refreshStatusAndCameras() }, 300)
        }
    }

    private fun stopAgentService() {
        if (AgentService.isRunning) {
            AppLog.i("Stopping AgentService...")
            stopService(Intent(this, AgentService::class.java))
            tvStatus.postDelayed({ refreshStatusAndCameras() }, 300)
        }
    }

    private fun setupLogListener() {
        lifecycleScope.launch {
            AppLog.logFlow.collect { line ->
                tvLogs.append(line + "\n")
                svLogs.post {
                    svLogs.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun refreshStatusAndCameras() {
        val hasCamPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val serviceStatus = if (AgentService.isRunning) "RUNNING" else "STOPPED"
        tvStatus.text = "Service: $serviceStatus | Camera Permission: ${if (hasCamPermission) "GRANTED" else "DENIED"}"

        btnStartService.text = if (AgentService.isRunning) "Stop Service" else "Start Service"

        val appGraph = (application as CameraAgentApp).appGraph
        val caps = if (appGraph.capabilities.isEmpty()) {
            appGraph.refreshCapabilities()
        } else {
            appGraph.capabilities
        }
        renderCameraInfo(caps)
    }

    private fun renderCameraInfo(caps: List<CameraCapabilities>) {
        if (caps.isEmpty()) {
            tvCameraInfo.text = "Cameras: None found or permission denied"
            return
        }

        val sb = StringBuilder()
        sb.append("Cameras detected: ${caps.size}\n")
        for (c in caps) {
            val topSizes = c.supportedSizes.take(3).joinToString(", ") { "${it.width}x${it.height}" }
            sb.append("• Camera [${c.cameraId}] ${c.facing} (Level: ${c.hardwareLevel}, Orientation: ${c.sensorOrientation}°)\n")
            sb.append("  Resolutions (${c.supportedSizes.size}): $topSizes...\n")
        }
        tvCameraInfo.text = sb.toString().trimEnd()
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.startsWith("wlan") || intf.name.startsWith("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        val host = addr.hostAddress
                        if (!addr.isLoopbackAddress && host != null && !host.contains(':')) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
