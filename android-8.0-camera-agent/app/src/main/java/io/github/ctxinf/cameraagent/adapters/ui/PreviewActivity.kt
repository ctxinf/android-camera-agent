package io.github.ctxinf.cameraagent.adapters.ui

import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.widget.FrameLayout
import io.github.ctxinf.cameraagent.domain.CameraState
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

    private lateinit var btnBack: View
    private lateinit var previewSurface: TextureView
    private lateinit var tvPreviewStatus: TextView
    private lateinit var tvPreviewInfo: TextView
    private lateinit var btnTogglePreview: Button
    private lateinit var btnRotate: Button

    private var previewOutput: Surface? = null
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

        previewSurface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                updatePreviewGeometry()
                previewOutput = Surface(texture)
                startPreview()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { updatePreviewGeometry() }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePreviewOutput()
                return true
            }
        }
        (previewSurface.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreviewGeometry()
        }

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
        val surface = previewOutput ?: return
        if (!surface.isValid) return
        if (isPreviewAttached) return

        lifecycleScope.launch {
            try {
                AppLog.i("Attaching preview surface...")
                val appGraph = (application as CameraAgentApp).appGraph
                appGraph.coordinator.attachPreview(surface)
                isPreviewAttached = true
                tvPreviewStatus.visibility = View.GONE
                btnTogglePreview.text = getString(R.string.preview_on)
                btnTogglePreview.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.control_selected_bg))
                btnTogglePreview.setTextColor(getColor(R.color.control_selected_text))
            } catch (e: Exception) {
                AppLog.e("Failed to attach preview: ${e.message}", e)
                tvPreviewStatus.text = getString(R.string.preview_error, e.message)
                tvPreviewStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun stopPreview() {
        val surface = previewOutput ?: return
        if (!isPreviewAttached) return

        lifecycleScope.launch {
            try {
                AppLog.i("Detaching preview surface...")
                val appGraph = (application as CameraAgentApp).appGraph
                appGraph.coordinator.detachPreview(surface)
                isPreviewAttached = false
                tvPreviewStatus.text = getString(R.string.ui_preview_stopped)
                tvPreviewStatus.visibility = View.VISIBLE
                btnTogglePreview.text = getString(R.string.preview_off)
                btnTogglePreview.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.control_normal_bg))
                btnTogglePreview.setTextColor(getColor(R.color.control_normal_text))
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
                appGraph.cameraAccess.updateLocal { it.copy(rotationOffset = nextRotation) }
                AppLog.i("Rotation updated to $nextRotation°")
            } catch (e: Exception) {
                AppLog.e("Failed to rotate camera: ${e.message}", e)
            }
        }
    }

    private fun observeCameraState() {
        val graph = (application as CameraAgentApp).appGraph
        lifecycleScope.launch {
            graph.coordinator.state.collect { refreshPreview() }
        }
        lifecycleScope.launch {
            graph.coordinator.config.collect { refreshPreview() }
        }
    }

    private fun refreshPreview() {
        val graph = (application as CameraAgentApp).appGraph
        val config = graph.coordinator.config.value
        val state = graph.coordinator.state.value
        tvPreviewInfo.text = "${if (config.facing == io.github.ctxinf.cameraagent.domain.LensFacing.FRONT) getString(R.string.facing_front) else getString(R.string.facing_back)} · " +
            "${config.targetWidth}×${config.targetHeight} · ${config.targetFps} fps · " +
            getString(R.string.ui_rotation, config.rotationOffset, state::class.simpleName)
        btnRotate.text = getString(R.string.ui_clockwise, config.rotationOffset)
        updatePreviewGeometry()
    }

    private fun updatePreviewGeometry() {
        val graph = (application as CameraAgentApp).appGraph
        val config = graph.coordinator.config.value
        val active = graph.coordinator.state.value as? CameraState.Active
        val width = active?.width ?: config.targetWidth
        val height = active?.height ?: config.targetHeight
        val sensor = graph.capabilities.firstOrNull { it.facing == config.facing }?.sensorOrientation ?: 0
        val rotation = (sensor + config.rotationOffset) % 360
        val container = previewSurface.parent as View
        if (container.width == 0 || container.height == 0) return
        val swap = rotation % 180 != 0
        val scale = minOf(container.width.toFloat() / (if (swap) height else width),
            container.height.toFloat() / (if (swap) width else height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        previewSurface.surfaceTexture?.setDefaultBufferSize(width, height)
        val params = previewSurface.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) {
            previewSurface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
        previewSurface.rotation = rotation.toFloat()
    }

    private fun releasePreviewOutput() {
        val surface = previewOutput ?: return
        previewOutput = null
        isPreviewAttached = false
        val graph = (application as CameraAgentApp).appGraph
        graph.appScope.launch {
            graph.coordinator.detachPreview(surface)
            surface.release()
        }
    }

    override fun onDestroy() {
        releasePreviewOutput()
        super.onDestroy()
    }
}
