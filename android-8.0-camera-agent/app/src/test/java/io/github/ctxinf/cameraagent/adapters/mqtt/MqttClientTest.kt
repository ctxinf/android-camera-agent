package io.github.ctxinf.cameraagent.adapters.mqtt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class MqttClientTest {

    @Test
    fun `test successful connect and disconnect with mock broker`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort

        thread {
            try {
                val clientSocket = server.accept()
                val inp = DataInputStream(clientSocket.getInputStream())
                val out = DataOutputStream(clientSocket.getOutputStream())

                // 1. Read CONNECT packet
                val packetType = inp.readUnsignedByte()
                assertEquals(0x10, packetType)
                // Read remaining length
                val len = inp.readUnsignedByte()
                val bytes = ByteArray(len)
                inp.readFully(bytes)

                // 2. Send CONNACK (0x20, length 2, sessionPresent=0, returnCode=0)
                out.write(0x20)
                out.write(0x02)
                out.write(0x00)
                out.write(0x00)
                out.flush()

                // 3. Read DISCONNECT packet (0xE0, length 0)
                val disconType = inp.readUnsignedByte()
                val disconLen = inp.readUnsignedByte()
                assertEquals(0xE0, disconType)
                assertEquals(0, disconLen)

                clientSocket.close()
            } catch (e: Exception) {
                // Ignore closing
            } finally {
                server.close()
            }
        }

        val client = MqttClient("127.0.0.1", port, "test-client")
        client.connect(3000)
        assertTrue(client.isConnected)

        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun `test testConnection helper function with mock broker`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort

        thread {
            try {
                val clientSocket = server.accept()
                val inp = DataInputStream(clientSocket.getInputStream())
                val out = DataOutputStream(clientSocket.getOutputStream())

                val packetType = inp.readUnsignedByte()
                assertEquals(0x10, packetType)
                val len = inp.readUnsignedByte()
                val bytes = ByteArray(len)
                inp.readFully(bytes)

                // Send CONNACK OK
                out.write(0x20)
                out.write(0x02)
                out.write(0x00)
                out.write(0x00)
                out.flush()

                // Disconnect
                inp.readUnsignedByte()
                inp.readUnsignedByte()

                clientSocket.close()
            } catch (e: Exception) {
            } finally {
                server.close()
            }
        }

        val result = MqttClient.testConnection("127.0.0.1", port, "test-client", timeoutMs = 3000)
        assertTrue(result.success)
        assertTrue(result.message.contains("连接成功"))
    }

    @Test
    fun `test testConnection failure when broker unreachable`() = runBlocking {
        // Choose an unlikely port
        val result = MqttClient.testConnection("127.0.0.1", 59999, "test-client", timeoutMs = 500)
        assertFalse(result.success)
        assertTrue(result.message.contains("连接失败"))
    }
}
