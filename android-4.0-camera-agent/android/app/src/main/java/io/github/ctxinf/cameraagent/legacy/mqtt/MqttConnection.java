package io.github.ctxinf.cameraagent.legacy.mqtt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Small MQTT 3.1.1 QoS-0 connection. It deliberately uses only java.net so
 * the application stays installable on API 14 devices without a dependency.
 */
final class MqttConnection {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int MAX_PACKET_BYTES = 1024 * 1024;

    static final class Packet {
        final int header;
        final byte[] body;

        Packet(int header, byte[] body) {
            this.header = header;
            this.body = body;
        }

        int type() {
            return (header >> 4) & 0x0f;
        }
    }

    private final String host;
    private final int port;
    private final String clientId;
    private final String willTopic;
    private final String username;
    private final String password;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int packetId = 1;

    MqttConnection(String host, int port, String clientId, String willTopic,
                   String username, String password) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.willTopic = willTopic;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(10000);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "MQTT");
        body.write(4); // MQTT 3.1.1

        boolean hasUser = username.length() > 0;
        boolean hasPassword = hasUser && password.length() > 0;
        // Clean session + will flag + will retain. QoS is 0.
        int flags = 0x02 | 0x04 | 0x20;
        if (hasUser) {
            flags |= 0x80;
        }
        if (hasPassword) {
            flags |= 0x40;
        }
        body.write(flags);
        writeShort(body, 60); // keep alive seconds
        writeUtf8(body, clientId);
        writeUtf8(body, willTopic);
        writeUtf8(body, "offline");
        if (hasUser) {
            writeUtf8(body, username);
        }
        if (hasPassword) {
            writeUtf8(body, password);
        }
        send(0x10, body.toByteArray());

        Packet response = readPacket();
        if (response.type() != 2 || response.body.length < 2) {
            throw new IOException("invalid MQTT CONNACK response");
        }
        int returnCode = response.body[1] & 0xff;
        if (returnCode != 0) {
            throw new IOException(connackError(returnCode));
        }
        socket.setSoTimeout(READ_TIMEOUT_MS);
    }

    private static String connackError(int code) {
        switch (code) {
            case 1: return "Broker rejected connection: unsupported MQTT protocol";
            case 2: return "Broker rejected connection: invalid client ID";
            case 3: return "Broker rejected connection: service unavailable";
            case 4: return "Broker rejected connection: bad username or password";
            case 5: return "Broker rejected connection: not authorized";
            default: return "Broker rejected connection (CONNACK " + code + ")";
        }
    }

    void publish(String topic, String payload, boolean retain) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        byte[] bytes = payload.getBytes("UTF-8");
        body.write(bytes);
        send(retain ? 0x31 : 0x30, body.toByteArray());
    }

    void subscribe(String topic) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int id = nextPacketId();
        writeShort(body, id);
        writeUtf8(body, topic);
        body.write(0); // requested QoS 0
        send(0x82, body.toByteArray());

        socket.setSoTimeout(10000);
        while (true) {
            Packet response = readPacket();
            if (response.type() == 9 && response.body.length >= 3
                    && readUnsignedShort(response.body, 0) == id) {
                if ((response.body[2] & 0xff) == 0x80) {
                    throw new IOException("MQTT subscription rejected");
                }
                socket.setSoTimeout(READ_TIMEOUT_MS);
                return;
            }
        }
    }

    Packet poll() throws IOException {
        try {
            return readPacket();
        } catch (SocketTimeoutException timeout) {
            return null;
        }
    }

    /** Respond to an incoming QoS-0 PUBLISH and return its topic/payload. */
    Incoming incoming(Packet packet) throws IOException {
        if (packet == null || packet.type() != 3) {
            return null;
        }
        int qos = (packet.header >> 1) & 0x03;
        int topicLength = readUnsignedShort(packet.body, 0);
        int offset = 2;
        String topic = new String(packet.body, offset, topicLength, "UTF-8");
        offset += topicLength;
        if (qos == 1) {
            int id = readUnsignedShort(packet.body, offset);
            offset += 2;
            ByteArrayOutputStream ack = new ByteArrayOutputStream();
            writeShort(ack, id);
            send(0x40, ack.toByteArray());
        } else if (qos == 2) {
            throw new IOException("MQTT QoS 2 is not supported");
        }
        return new Incoming(topic, new String(packet.body, offset,
                packet.body.length - offset, "UTF-8"));
    }

    void ping() throws IOException {
        send(0xc0, new byte[0]);
    }

    void close() {
        try {
            if (output != null) {
                output.write(0xe0);
                output.write(0);
                output.flush();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        input = null;
        output = null;
    }

    private Packet readPacket() throws IOException {
        int header = input.read();
        if (header < 0) {
            throw new IOException("MQTT connection closed");
        }
        int multiplier = 1;
        int remaining = 0;
        int digit;
        do {
            digit = input.read();
            if (digit < 0) {
                throw new IOException("truncated MQTT packet");
            }
            remaining += (digit & 0x7f) * multiplier;
            multiplier *= 128;
            if (multiplier > 128 * 128 * 128 * 128 || remaining > MAX_PACKET_BYTES) {
                throw new IOException("MQTT packet too large");
            }
        } while ((digit & 0x80) != 0);
        byte[] body = new byte[remaining];
        input.readFully(body);
        return new Packet(header, body);
    }

    private void send(int header, byte[] body) throws IOException {
        output.write(header);
        writeRemainingLength(output, body.length);
        output.write(body);
        output.flush();
    }

    private int nextPacketId() {
        int id = packetId++;
        if (packetId > 65535) {
            packetId = 1;
        }
        return id;
    }

    private static void writeRemainingLength(DataOutputStream out, int value) throws IOException {
        do {
            int digit = value % 128;
            value /= 128;
            if (value > 0) {
                digit |= 0x80;
            }
            out.write(digit);
        } while (value > 0);
    }

    private static void writeUtf8(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        writeShort(out, bytes.length);
        out.write(bytes);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static int readUnsignedShort(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 2 > bytes.length) {
            throw new IOException("malformed MQTT packet");
        }
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    static final class Incoming {
        final String topic;
        final String payload;

        Incoming(String topic, String payload) {
            this.topic = topic;
            this.payload = payload;
        }
    }
}
