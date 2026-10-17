package io.github.ctxinf.cameraagent.camera

import android.media.Image

object YuvUtils {

    /**
     * Converts an android.media.Image in YUV_420_888 format into an NV21 byte array.
     *
     * Respects plane row strides and pixel strides across all device vendors and HAL implementations.
     * NV21 format consists of a full-resolution Y plane followed by an interleaved VU plane.
     */
    fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val planes = image.planes

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // 1. Copy Y plane
        val yBuffer = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var yOffset = 0
            for (row in 0 until height) {
                if (yPixelStride == 1) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, yOffset, width)
                    yOffset += width
                } else {
                    for (col in 0 until width) {
                        nv21[yOffset++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                    }
                }
            }
        }

        // 2. Interleave UV planes into VU (NV21 format: V then U)
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        val uvHeight = height / 2
        val uvWidth = width / 2
        var uvOffset = ySize

        for (row in 0 until uvHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until uvWidth) {
                nv21[uvOffset++] = vBuffer.get(vRowStart + col * vPixelStride)
                nv21[uvOffset++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }

        return nv21
    }
}
