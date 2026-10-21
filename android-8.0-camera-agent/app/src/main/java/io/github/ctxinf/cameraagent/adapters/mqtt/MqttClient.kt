package io.github.ctxinf.cameraagent.adapters.mqtt

import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class MqttTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long = 0,
)

sealed interface MqttStatus {
    object Disabled : MqttStatus
    object Connecting : MqttStatus
    data class Connected(val broker: String) : MqttStatus
    data class Disconnected(val reason: String) : MqttStatus
}

/**
 * Lightweight, zero-dependency MQTT 3.1.1 client.
 */
class MqttClient(
    val host: String,
    val port: Int,
    val clientId: String,
    val username: String? = null,
    val password: String? = null,
    val keepAliveSec: Int = 60,
) {

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    companion object {
        const val PACKET_CONNECT = 0x10
        const val PACKET_CONNACK = 0x20
        const val PACKET_PUBLISH = 0x30
        const val PACKET_PINGREQ = 0xC0
        const val PACKET_PINGRESP = 0xD0
        const val PACKET_DISCONNECT = 0xE0

        suspend fun testConnection(
            host: String,
            port: Int,
            clientId: String,
            username: String? = null,
            password: String? = null,
            timeoutMs: Int = 5000,
        ): MqttTestResult = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            if (host.isBlank()) {
                return@withContext MqttTestResult(false, "Broker host cannot be empty")
            }
            val client = MqttClient(host, port, clientId, username, password)
            try {
                client.connectInternal(timeoutMs)
                val latency = System.currentTimeMillis() - start
                client.disconnect()
                MqttTestResult(true, "连接成功 (CONNACK RC=0, 延迟 ${latency}ms)", latency)
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - start
                val msg = when {
                    e.message?.contains("refused", ignoreCase = true) == true -> "连接被拒绝 (Connection Refused)"
                    e.message?.contains("timeout", ignoreCase = true) == true -> "连接超时 (Connection Timeout)"
                    else -> e.message ?: "未知错误"
                }
                MqttTestResult(false, "连接失败: $msg", latency)
            }
        }
    }

    suspend fun connect(timeoutMs: Int = 8000) = withContext(Dispatchers.IO) {
        connectInternal(timeoutMs)
    }

    private fun connectInternal(timeoutMs: Int) {
        val s = Socket()
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(host, port), timeoutMs)

        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val inp = DataInputStream(BufferedInputStream(s.getInputStream()))

        // Build MQTT CONNECT Packet
        val variableHeader = ByteArrayOutputStream()
        // Protocol Name: "MQTT"
        writeUtf8(variableHeader, "MQTT")
        // Protocol Level: 4 (v3.1.1)
        variableHeader.write(0x04)

        // Connect Flags
        var flags = 0x02 // Clean session
        if (!username.isNullOrEmpty()) {
            flags = flags or 0x80
        }
        if (!password.isNullOrEmpty()) {
            flags = flags or 0x40
        }
        variableHeader.write(flags)

        // Keep Alive (2 bytes)
        variableHeader.write((keepAliveSec shr 8) and 0xFF)
        variableHeader.write(keepAliveSec and 0xFF)

        // Payload
        val payload = ByteArrayOutputStream()
        writeUtf8(payload, clientId)
        if (!username.isNullOrEmpty()) {
            writeUtf8(payload, username)
        }
        if (!password.isNullOrEmpty()) {
            writeUtf8(payload, password)
        }

        val varBytes = variableHeader.toByteArray()
        val payBytes = payload.toByteArray()
        val remainingLength = varBytes.size + payBytes.size

        // Send CONNECT
        out.write(PACKET_CONNECT)
        writeRemainingLength(out, remainingLength)
        out.write(varBytes)
        out.write(payBytes)
        out.flush()

        // Read CONNACK
        val packetType = inp.readUnsignedByte()
        if ((packetType and 0xF0) != PACKET_CONNACK) {
            s.close()
            throw IOException("Expected CONNACK (0x20), received: 0x${Integer.toHexString(packetType)}")
        }

        val remLen = readRemainingLength(inp)
        if (remLen != 2) {
            s.close()
            throw IOException("Invalid CONNACK remaining length: $remLen")
        }

        val ackFlags = inp.readUnsignedByte()
        val returnCode = inp.readUnsignedByte()

        if (returnCode != 0) {
            s.close()
            val reason = when (returnCode) {
                1 -> "Unacceptable protocol version"
                2 -> "Identifier rejected"
                3 -> "Server unavailable"
                4 -> "Bad username or password"
                5 -> "Not authorized"
                else -> "Code $returnCode"
            }
            throw IOException("CONNACK rejected: $reason")
        }

        s.soTimeout = 60_000
        socket = s
        outputStream = out
        inputStream = inp
        isConnected = true
        AppLog.i("MqttClient: Connected to $host:$port as $clientId (SessionPresent=${ackFlags and 0x01})")
    }

    suspend fun publish(topic: String, message: String, retain: Boolean = false) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Not connected")
        val payloadBytes = message.toByteArray(Charsets.UTF_8)

        val variableHeader = ByteArrayOutputStream()
        writeUtf8(variableHeader, topic)
        val varBytes = variableHeader.toByteArray()

        val remainingLength = varBytes.size + payloadBytes.size
        var header = PACKET_PUBLISH
        if (retain) header = header or 0x01

        synchronized(out) {
            out.write(header)
            writeRemainingLength(out, remainingLength)
            out.write(varBytes)
            out.write(payloadBytes)
            out.flush()
        }
        AppLog.d("MqttClient: Published to $topic (${payloadBytes.size} bytes)")
    }

    data class Message(val topic: String, val payload: String)

    suspend fun subscribe(topic: String) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Not connected")
        val body = ByteArrayOutputStream()
        body.write(0); body.write(1)
        writeUtf8(body, topic)
        body.write(0)
        synchronized(out) {
            out.write(0x82)
            writeRemainingLength(out, body.size())
            out.write(body.toByteArray()); out.flush()
        }
    }

    // A single reader handles subscriptions, incoming commands and heartbeat replies.
    suspend fun receive(): Message? = withContext(Dispatchers.IO) {
        val inp = inputStream ?: throw IOException("Not connected")
        val header = inp.readUnsignedByte()
        val length = readRemainingLength(inp)
        if (length > 1024 * 1024) throw IOException("MQTT packet too large")
        val body = ByteArray(length)
        inp.readFully(body)
        when (header and 0xf0) {
            0x90 -> {
                if (body.size < 3 || body.drop(2).any { it.toInt() and 0xff == 0x80 })
                    throw IOException("MQTT subscription rejected")
                null
            }
            PACKET_PUBLISH -> {
                val data = DataInputStream(body.inputStream())
                val topicLength = data.readUnsignedShort()
                val topicBytes = ByteArray(topicLength)
                data.readFully(topicBytes)
                when ((header shr 1) and 3) {
                    0 -> Unit
                    1 -> {
                        val id = data.readUnsignedShort()
                        val out = outputStream ?: throw IOException("Not connected")
                        synchronized(out) {
                            out.write(byteArrayOf(0x40, 2, (id shr 8).toByte(), id.toByte()))
                            out.flush()
                        }
                    }
                    else -> throw IOException("Unsupported MQTT QoS")
                }
                // Retained commands must not reapply settings after reconnecting.
                if (header and 1 != 0) null
                else Message(topicBytes.toString(Charsets.UTF_8), data.readBytes().toString(Charsets.UTF_8))
            }
            else -> null
        }
    }

    suspend fun sendPing() = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Not connected")
        synchronized(out) { out.write(byteArrayOf(0xc0.toByte(), 0)); out.flush() }
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext false
        val inp = inputStream ?: return@withContext false
        try {
            synchronized(out) {
                out.write(PACKET_PINGREQ)
                out.write(0x00)
                out.flush()
            }
            val resp = inp.readUnsignedByte()
            val len = inp.readUnsignedByte()
            resp == PACKET_PINGRESP && len == 0
        } catch (e: Exception) {
            AppLog.w("MqttClient ping failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        isConnected = false
        try {
            outputStream?.let { out ->
                synchronized(out) {
                    out.write(PACKET_DISCONNECT)
                    out.write(0x00)
                    out.flush()
                }
            }
        } catch (ignored: Exception) {}

        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
        AppLog.i("MqttClient: Disconnected from $host:$port")
    }

    private fun writeUtf8(stream: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        stream.write((bytes.size shr 8) and 0xFF)
        stream.write(bytes.size and 0xFF)
        stream.write(bytes)
    }

    private fun writeRemainingLength(out: DataOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 128
            }
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readRemainingLength(inp: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var encodedByte: Int
        do {
            encodedByte = inp.readUnsignedByte()
            value += (encodedByte and 127) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) {
                throw IOException("Malformed remaining length")
            }
        } while ((encodedByte and 128) != 0)
        return value
    }
}
