package io.github.ctxinf.cameraagent.camera

import android.graphics.ImageFormat
import android.view.Surface
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.Size

data class CaptureSessionPlan(
    val cameraId: String,
    val outputSize: Size,
    val imageFormat: Int = ImageFormat.YUV_420_888,
    val fpsRange: FpsRange,
    val rotation: Int,
    val previewSurface: Surface? = null,
)
