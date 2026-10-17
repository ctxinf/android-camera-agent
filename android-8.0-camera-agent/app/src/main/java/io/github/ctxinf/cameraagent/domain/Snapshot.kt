package io.github.ctxinf.cameraagent.domain

data class Snapshot(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Snapshot

        if (!jpegBytes.contentEquals(other.jpegBytes)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (rotation != other.rotation) return false
        if (timestampMs != other.timestampMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotation
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
