package io.github.ctxinf.cameraagent.adapters.ui

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.launch

class PreviewActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var previewSurface: SurfaceView
    private lateinit var tvPreviewStatus: TextView
    private lateinit var tvPreviewInfo: TextView
    private lateinit var btnTogglePreview: Button
    private lateinit var btnRotate: Button

    private var currentHolder: SurfaceHolder? = null
    private var isPreviewAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_preview)
        AppLog.i("PreviewActivity onCreate()")

        btnBack = findViewById(R.id.btnBack)
        previewSurface = findViewById(R.id.previewSurface)
        tvPreviewStatus = findViewById(R.id.tvPreviewStatus)
        tvPreviewInfo = findViewById(R.id.tvPreviewInfo)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        btnRotate = findViewById(R.id.btnRotate)

        btnBack.setOnClickListener { finish() }

        previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                AppLog.i("Preview surfaceCreated")
                currentHolder = holder
                startPreview()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                currentHolder = holder
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                AppLog.i("Preview surfaceDestroyed")
                stopPreview()
                currentHolder = null
            }
        })

        btnTogglePreview.setOnClickListener {
            if (isPreviewAttached) {
                stopPreview()
            } else {
                startPreview()
            }
        }

        btnRotate.setOnClickListener {
            rotateNext()
        }

        observeCameraState()
    }

    private fun startPreview() {
        val holder = currentHolder ?: return
        if (!holder.surface.isValid) return
        if (isPreviewAttached) return

        lifecycleScope.launch {
            try {
                AppLog.i("Attaching preview surface...")
                val appGraph = (application as CameraAgentApp).appGraph
                appGraph.coordinator.attachPreview(holder.surface)
                isPreviewAttached = true
                tvPreviewStatus.visibility = View.GONE
                btnTogglePreview.text = getString(R.string.preview_on)
                btnTogglePreview.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF404D70.toInt())
            } catch (e: Exception) {
                AppLog.e("Failed to attach preview: ${e.message}", e)
                tvPreviewStatus.text = "Preview Error: ${e.message}"
                tvPreviewStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun stopPreview() {
        val holder = currentHolder ?: return
        if (!isPreviewAttached) return

        lifecycleScope.launch {
            try {
                AppLog.i("Detaching preview surface...")
                val appGraph = (application as CameraAgentApp).appGraph
                appGraph.coordinator.detachPreview(holder.surface)
                isPreviewAttached = false
                tvPreviewStatus.text = "Preview Stopped"
                tvPreviewStatus.visibility = View.VISIBLE
                btnTogglePreview.text = getString(R.string.preview_off)
                btnTogglePreview.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF303643.toInt())
            } catch (e: Exception) {
                AppLog.w("Error detaching preview", e)
            }
        }
    }

    private fun rotateNext() {
        lifecycleScope.launch {
            try {
                val appGraph = (application as CameraAgentApp).appGraph
                val currentConfig = appGraph.coordinator.config.value
                val nextRotation = (currentConfig.rotationOffset + 90) % 360
                val updatedConfig = currentConfig.copy(rotationOffset = nextRotation)
                appGraph.coordinator.updateConfig(updatedConfig)
                appGraph.settingsRepository.updateConfig(updatedConfig)
                AppLog.i("Rotation updated to $nextRotation°")
            } catch (e: Exception) {
                AppLog.e("Failed to rotate camera: ${e.message}", e)
            }
        }
    }

    private fun observeCameraState() {
        val appGraph = (application as CameraAgentApp).appGraph
        lifecycleScope.launch {
            appGraph.coordinator.config.collect { config ->
                val state = appGraph.coordinator.state.value
                val info = "Lens: ${config.facing} | Target: ${config.targetWidth}x${config.targetHeight} @ ${config.targetFps}fps | " +
                        "Rotation Offset: ${config.rotationOffset}° | State: ${state::class.simpleName}"
                tvPreviewInfo.text = info
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
    }
}
