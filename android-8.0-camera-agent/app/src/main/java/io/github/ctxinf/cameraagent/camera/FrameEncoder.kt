package io.github.ctxinf.cameraagent.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import io.github.ctxinf.cameraagent.domain.Frame
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import java.io.ByteArrayOutputStream

interface FrameEncoder {
    fun encodeToJpeg(frame: Frame, quality: Int = 80): ByteArray
}

class DefaultFrameEncoder : FrameEncoder {

    override fun encodeToJpeg(frame: Frame, quality: Int): ByteArray {
        val data = frame.data
        val clampedQuality = quality.coerceIn(1, 100)

        // 1. If data is already a valid JPEG (starts with SOI marker 0xFF, 0xD8), return directly
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            return data
        }

        // 2. Compress NV21 to JPEG using Android platform YuvImage
        return try {
            val yuvImage = YuvImage(
                data,
                ImageFormat.NV21,
                frame.width,
                frame.height,
                null,
            )
            val outputStream = ByteArrayOutputStream(data.size / 2)
            yuvImage.compressToJpeg(
                Rect(0, 0, frame.width, frame.height),
                clampedQuality,
                outputStream,
            )
            val rawJpeg = outputStream.toByteArray()
            if (rawJpeg.isEmpty()) {
                return data
            }

            // 3. Handle rotation if needed
            if (frame.rotation != 0) {
                rotateJpeg(rawJpeg, frame.rotation, clampedQuality)
            } else {
                rawJpeg
            }
        } catch (e: Exception) {
            AppLog.w("Failed to encode NV21 to JPEG, fallback to raw bytes", e)
            data
        }
    }

    private fun rotateJpeg(jpegBytes: ByteArray, degrees: Int, quality: Int): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            val stream = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            AppLog.w("Failed to rotate JPEG bitmap", e)
            jpegBytes
        }
    }
}
