package io.github.ctxinf.cameraagent.legacy.gateway;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Outbound-only control channel to the gateway. The device always dials out, so
 * nothing needs to be reachable from the internet: no port forwarding, no CGNAT
 * or dynamic-IP problems, and no listening socket exposed to the world.
 *
 * <p>Wire format is newline-delimited JSON-ish frames, deliberately trivial so a
 * 4.x device needs no JSON library:
 * <pre>
 *   device -&gt; gateway  {"type":"hello","id":"...","sdk":18}
 *   device -&gt; gateway  {"type":"pong"}
 *   gateway -&gt; device  {"type":"ping"}
 *   gateway -&gt; device  {"type":"snapshot","req":"abc"}
 *   device -&gt; gateway  {"type":"snapshot_result","req":"abc","len":N}\n<N bytes>
 * </pre>
 *
 * Reconnects with exponential backoff; idle cost is one TCP connection plus a
 * heartbeat, which is what makes standby cheap.
 */
public class GatewayClient implements Runnable {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    /** Read timeout must exceed the server ping interval or we would churn. */
    private static final int READ_TIMEOUT_MS = 90000;
    private static final long BACKOFF_MIN_MS = 2000;
    private static final long BACKOFF_MAX_MS = 60000;

    private final String host;
    private final int port;
    private final String deviceId;
    private final CameraManager cameraManager;

    private volatile boolean running;
    private Thread thread;
    private volatile Socket socket;

    public GatewayClient(String host, int port, String deviceId, CameraManager cameraManager) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.cameraManager = cameraManager;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "gateway");
        thread.start();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        long backoff = BACKOFF_MIN_MS;
        while (running) {
            try {
                session();
                backoff = BACKOFF_MIN_MS;   // a clean session resets the backoff
            } catch (Exception e) {
                Log.w("gateway session ended: " + e);
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
        Log.i("gateway client stopped");
    }

    private void session() throws IOException {
        Socket s = new Socket();
        socket = s;
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            Log.i("gateway connected " + host + ":" + port);

            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));

            sendLine(out, "{\"type\":\"hello\",\"id\":\"" + deviceId
                    + "\",\"sdk\":" + android.os.Build.VERSION.SDK_INT
                    + ",\"snapshot\":true,\"mjpeg\":true}");

            String line;
            while (running && (line = in.readLine()) != null) {
                handleCommand(line, out);
            }
        } finally {
            socket = null;
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void handleCommand(String line, DataOutputStream out) throws IOException {
        String type = field(line, "type");
        if (type == null) {
            return;
        }
        if ("ping".equals(type)) {
            sendLine(out, "{\"type\":\"pong\"}");
        } else if ("snapshot".equals(type)) {
            String req = field(line, "req");
            byte[] jpeg = cameraManager.snapshotJpeg(8000);
            if (jpeg == null) {
                sendLine(out, "{\"type\":\"snapshot_result\",\"req\":\"" + safe(req)
                        + "\",\"error\":\"camera_unavailable\"}");
            } else {
                // Header line first, then the raw bytes, so the server can read
                // exactly len bytes without escaping or base64 overhead.
                sendLine(out, "{\"type\":\"snapshot_result\",\"req\":\"" + safe(req)
                        + "\",\"len\":" + jpeg.length + "}");
                out.write(jpeg);
                out.flush();
            }
        } else if ("status".equals(type)) {
            sendLine(out, "{\"type\":\"status_result\",\"state\":\""
                    + cameraManager.getState() + "\"}");
        } else {
            Log.d("unknown command: " + type);
        }
    }

    private static void sendLine(DataOutputStream out, String s) throws IOException {
        out.write(s.getBytes("UTF-8"));
        out.write('\n');
        out.flush();
    }

    /**
     * Pull a string/number value out of a flat JSON object. Good enough for the
     * fixed frames above and avoids shipping a JSON parser; org.json is present
     * on the device but this keeps allocation near zero on the heartbeat path.
     */
    static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? null : json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(i, end).trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\\", "").replace("\"", "");
    }
}
