package io.github.ctxinf.cameraagent.adapters.http

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

object DummyFrameGenerator {
    // Minimal 1x1 valid JPEG fallback for environments where android.graphics isn't mocked (e.g. JVM tests)
    private val FALLBACK_JPEG = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48, 0x00, 0x48,
        0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00, 0x03, 0x02,
        0x02, 0x03, 0x02, 0x02, 0x03, 0x03, 0x03, 0x03, 0x04, 0x03, 0x03, 0x04,
        0x05, 0x08, 0x05, 0x05, 0x04, 0x04, 0x05, 0x0A, 0x07, 0x07, 0x06, 0x08,
        0x0C, 0x0A, 0x0C, 0x0C, 0x0B, 0x0A, 0x0B, 0x0B, 0x0D, 0x0E, 0x12, 0x10,
        0x0D, 0x0E, 0x11, 0x0E, 0x0B, 0x0B, 0x10, 0x16, 0x10, 0x11, 0x13, 0x14,
        0x15, 0x15, 0x15, 0x0C, 0x0F, 0x17, 0x18, 0x16, 0x14, 0x18, 0x12, 0x14,
        0x15, 0x14, 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01,
        0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF.toByte(), 0xC4.toByte(), 0x00,
        0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
        0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0xFF.toByte(), 0xDA.toByte(), 0x00,
        0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xBF.toByte(), 0x00, 0xFF.toByte(),
        0xD9.toByte()
    )

    @Volatile
    private var cachedJpeg: ByteArray? = null

    fun createDummyJpeg(
        width: Int = 1280,
        height: Int = 720,
        title: String = "CAMERA SERVICE SUSPENDED",
        subtitle: String = "服务已暂停 · 暂不提供实时画面"
    ): ByteArray {
        cachedJpeg?.let { return it }

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                ?: return FALLBACK_JPEG
            val canvas = Canvas(bitmap)

            // Dark grey background
            canvas.drawColor(Color.rgb(0x1A, 0x1A, 0x1A))

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Inner border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.rgb(0x44, 0x44, 0x44)
            canvas.drawRect(30f, 30f, width - 30f, height - 30f, paint)

            // Red warning circle
            val centerX = width / 2f
            val centerY = height / 2f - 40f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(0xD3, 0x2F, 0x2F)
            canvas.drawCircle(centerX, centerY, 52f, paint)

            // Exclamation mark
            paint.color = Color.WHITE
            paint.textSize = 60f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("!", centerX, centerY + 20f, paint)

            // Title
            paint.color = Color.WHITE
            paint.textSize = 34f
            canvas.drawText(title, centerX, centerY + 105f, paint)

            // Subtitle
            paint.color = Color.rgb(0xAA, 0xAA, 0xAA)
            paint.textSize = 22f
            paint.isFakeBoldText = false
            canvas.drawText(subtitle, centerX, centerY + 150f, paint)

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            val result = baos.toByteArray()
            if (result.isNotEmpty()) {
                cachedJpeg = result
                return result
            }
        } catch (t: Throwable) {
            // Ignored on unmocked JVM tests
        }

        return FALLBACK_JPEG
    }
}
