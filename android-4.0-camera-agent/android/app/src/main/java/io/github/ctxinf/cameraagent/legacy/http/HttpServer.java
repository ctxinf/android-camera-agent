package io.github.ctxinf.cameraagent.legacy.http;

import io.github.ctxinf.cameraagent.legacy.Settings;
import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.mcp.McpServer;
import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal HTTP/1.1 server exposing the camera to the LAN (Home Assistant,
 * browsers). Hand-rolled on raw sockets: no NanoHTTPD, no AndroidX, nothing that
 * would pull minSdk upward.
 *
 * <pre>
 *   GET  /snapshot.jpg   single JPEG
 *   GET  /video.mjpeg    multipart MJPEG stream
 *   GET  /api/status     JSON device/camera status
 *   POST /mcp            MCP endpoint (Streamable HTTP, revision 2026-07-28)
 * </pre>
 *
 * A small bounded thread pool caps concurrency; on a 1 GB device we would rather
 * refuse a connection than thrash.
 */
public class HttpServer implements Runnable {

    /** Single MCP endpoint path, as the Streamable HTTP binding requires. */
    private static final String MCP_PATH = "/mcp";

    private static final int MAX_CLIENTS = 4;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    /** Cap on a request body. MCP requests are small; this bounds the damage. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final int port;
    private final CameraManager cameraManager;
    private final Settings settings;
    private final io.github.ctxinf.cameraagent.legacy.DeviceStatus deviceStatus;
    private final McpServer mcpServer;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;

    public HttpServer(int port, CameraManager cameraManager, Settings settings,
                      io.github.ctxinf.cameraagent.legacy.DeviceStatus deviceStatus) {
        this.port = port;
        this.cameraManager = cameraManager;
        this.settings = settings;
        this.deviceStatus = deviceStatus;
        this.mcpServer = new McpServer(cameraManager, settings);
    }

    public int getPort() {
        return port;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        // Core size must equal the max: ThreadPoolExecutor only grows past the
        // core size once the queue is *full*, so a core of 1 makes every request
        // queue behind a long-lived MJPEG stream instead of running beside it.
        // A SynchronousQueue hands each connection straight to a thread.
        BlockingQueue<Runnable> queue = new java.util.concurrent.SynchronousQueue<Runnable>();
        workers = new ThreadPoolExecutor(MAX_CLIENTS, MAX_CLIENTS, 30, TimeUnit.SECONDS, queue);
        // Idle threads should die back; this is a 1 GB device.
        workers.allowCoreThreadTimeOut(true);
        // Over capacity: drop the newest rather than block the accept loop.
        workers.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        acceptThread = new Thread(this, "http-accept");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) { }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            Log.i("http listening on :" + port);
        } catch (IOException e) {
            Log.e("failed to bind :" + port + " - " + e);
            running = false;
            return;
        }

        while (running) {
            final Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    Log.w("accept failed: " + e);
                }
                continue;
            }
            workers.execute(new Runnable() {
                @Override
                public void run() {
                    handle(client);
                }
            });
        }
        Log.i("http stopped");
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 8192);

            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            // Headers we act on. The rest must still be consumed so the
            // client's request is fully read.
            String authHeader = null;
            String origin = null;
            String mcpVersion = null;
            String mcpMethod = null;
            String mcpName = null;
            int contentLength = 0;
            while (true) {
                String h = readLine(in);
                if (h == null || h.length() == 0) {
                    break;
                }
                if (authHeader == null && startsWithIgnoreCase(h, "Authorization:")) {
                    authHeader = headerValue(h, "Authorization:");
                } else if (origin == null && startsWithIgnoreCase(h, "Origin:")) {
                    origin = headerValue(h, "Origin:");
                } else if (mcpVersion == null && startsWithIgnoreCase(h, "MCP-Protocol-Version:")) {
                    mcpVersion = headerValue(h, "MCP-Protocol-Version:");
                } else if (mcpMethod == null && startsWithIgnoreCase(h, "Mcp-Method:")) {
                    mcpMethod = headerValue(h, "Mcp-Method:");
                } else if (mcpName == null && startsWithIgnoreCase(h, "Mcp-Name:")) {
                    mcpName = headerValue(h, "Mcp-Name:");
                } else if (startsWithIgnoreCase(h, "Content-Length:")) {
                    try {
                        contentLength = Integer.parseInt(headerValue(h, "Content-Length:"));
                    } catch (NumberFormatException e) {
                        sendError(out, 400, "Bad Request");
                        return;
                    }
                }
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(out, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }

            if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
                sendError(out, 405, "Method Not Allowed");
                return;
            }

            if (!authorized(authHeader)) {
                sendUnauthorized(out);
                return;
            }

            if (MCP_PATH.equals(path)) {
                if (!settings.mcpEnabled()) {
                    // Indistinguishable from a build without MCP, so a scan of a
                    // device that has it turned off reveals nothing.
                    sendError(out, 404, "Not Found");
                    return;
                }
                // DNS-rebinding defence: a browser on a malicious page cannot
                // set Origin, so a present-but-foreign Origin is an attack.
                // A missing Origin is a non-browser client and is allowed.
                if (origin != null && !isAllowedOrigin(origin)) {
                    sendError(out, 403, "Forbidden");
                    return;
                }
                if (!"POST".equals(method)) {
                    // The 2026-07-28 revision removed the GET stream and the
                    // DELETE session teardown; both are now 405.
                    sendError(out, 405, "Method Not Allowed");
                    return;
                }
                String reqBody = readBody(in, contentLength);
                if (reqBody == null) {
                    sendError(out, 413, "Payload Too Large");
                    return;
                }
                McpServer.Response r =
                        mcpServer.handle(reqBody, mcpVersion, mcpMethod, mcpName);
                sendJson(out, r.status, r.body);
            } else if ("/snapshot.jpg".equals(path)) {
                serveSnapshot(out);
            } else if ("/video.mjpeg".equals(path)) {
                serveMjpeg(client, out);
            } else if ("/api/status".equals(path)) {
                serveStatus(out);
            } else if ("/api/camera".equals(path)) {
                if ("GET".equals(method) || "HEAD".equals(method)) {
                    serveCameraConfig(out);
                } else {
                    String reqBody = readBody(in, contentLength);
                    if (reqBody == null) {
                        sendError(out, 413, "Payload Too Large");
                    } else {
                        updateCameraConfig(out, reqBody);
                    }
                }
            } else {
                sendError(out, 404, "Not Found");
            }
        } catch (IOException e) {
            // Client hung up mid-response: normal for MJPEG.
            Log.d("client closed: " + e);
        } catch (Exception e) {
            Log.w("handler error: " + e);
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private void serveSnapshot(OutputStream out) throws IOException {
        byte[] jpeg = cameraManager.snapshotJpeg(8000, settings.jpegQuality());
        if (jpeg == null) {
            sendError(out, 503, "Camera Unavailable");
            return;
        }
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: image/jpeg\r\n");
        h.append("Content-Length: ").append(jpeg.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        out.write(jpeg);
        out.flush();
    }

    private void serveMjpeg(Socket client, final OutputStream out) throws IOException {
        // RFC 2046: the header declares the boundary token, and each part is
        // delimited by "--" + that token. Declaring a token that already starts
        // with "--" and then emitting it verbatim is a common MJPEG bug that
        // stricter parsers (including Home Assistant's) reject.
        final String boundary = "frameboundary";
        final String delimiter = "--" + boundary;

        if (cameraManager.isAccessDenied()) {
            StringBuilder deniedHeaders = new StringBuilder();
            deniedHeaders.append("HTTP/1.1 200 OK\r\n");
            deniedHeaders.append("Content-Type: multipart/x-mixed-replace; boundary=")
                    .append(boundary).append("\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(deniedHeaders.toString().getBytes("US-ASCII"));
            while (running && cameraManager.isAccessDenied()) {
                byte[] jpeg = cameraManager.deniedJpeg(settings.jpegQuality());
                String part = delimiter + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + jpeg.length + "\r\n\r\n";
                out.write(part.getBytes("US-ASCII"));
                out.write(jpeg);
                out.write("\r\n".getBytes("US-ASCII"));
                out.flush();
                try { Thread.sleep(Math.max(500, 1000 / settings.mjpegFps())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return;
        }

        if (!cameraManager.acquire(8000)) {
            sendError(out, 503, "Camera Unavailable");
            return;
        }

        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: multipart/x-mixed-replace; boundary=").append(boundary).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        out.flush();

        // Hand frames from the camera thread to this socket thread via a
        // one-slot queue: if the client is slow we drop frames instead of
        // stalling the camera or growing a buffer.
        //
        // Crucially the listener only *copies* the NV21 frame; JPEG encoding
        // happens on this socket thread. Encoding inline on the camera thread
        // (~100ms/frame on 4.x hardware) exhausts the preview callback buffers,
        // which stalls delivery for every other listener -- a concurrent
        // snapshot would then time out waiting for a frame that never arrives.
        final ArrayBlockingQueue<byte[]> slot = new ArrayBlockingQueue<byte[]>(1);
        final int quality = settings.jpegQuality();
        final long minIntervalMs = 1000 / settings.mjpegFps();
        final long[] lastFrameAt = new long[1];
        final int[] frameDims = new int[2];

        CameraManager.FrameListener listener = new CameraManager.FrameListener() {
            @Override
            public void onFrame(byte[] nv21, int width, int height) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastFrameAt[0] < minIntervalMs) {
                    return;
                }
                lastFrameAt[0] = now;
                frameDims[0] = width;
                frameDims[1] = height;
                // Copy: the buffer is recycled by the camera as soon as we return.
                byte[] copy = new byte[nv21.length];
                System.arraycopy(nv21, 0, copy, 0, nv21.length);
                slot.poll();          // discard the stale frame, if any
                slot.offer(copy);
            }
        };

        cameraManager.addFrameListener(listener);
        try {
            int emptyPolls = 0;
            while (running && !client.isClosed()) {
                byte[] nv21;
                try {
                    nv21 = slot.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (nv21 == null) {
                    if (cameraManager.isAccessDenied()) {
                        byte[] denied = cameraManager.deniedJpeg(quality);
                        writeMjpegPart(out, delimiter, denied);
                        emptyPolls = 0;
                        continue;
                    }
                    if (++emptyPolls < 5) continue;
                    break;   // camera stalled
                }
                emptyPolls = 0;
                // Upright, so the stream matches the preview and the snapshot.
                byte[] jpeg = cameraManager.encodeJpegUpright(
                        nv21, frameDims[0], frameDims[1], quality);
                if (jpeg == null) {
                    continue;
                }
                StringBuilder part = new StringBuilder();
                part.append(delimiter).append("\r\n");
                part.append("Content-Type: image/jpeg\r\n");
                part.append("Content-Length: ").append(jpeg.length).append("\r\n\r\n");
                out.write(part.toString().getBytes("US-ASCII"));
                out.write(jpeg);
                out.write("\r\n".getBytes("US-ASCII"));
                out.flush();   // throws once the client goes away, ending the stream
            }
        } finally {
            cameraManager.removeFrameListener(listener);
            cameraManager.release();
        }
    }

    private static void writeMjpegPart(OutputStream out, String delimiter, byte[] jpeg)
            throws IOException {
        String part = delimiter + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + jpeg.length + "\r\n\r\n";
        out.write(part.getBytes("US-ASCII"));
        out.write(jpeg);
        out.write("\r\n".getBytes("US-ASCII"));
        out.flush();
    }

    private void serveStatus(OutputStream out) throws IOException {
        java.util.Map<String, Object> status = deviceStatus.snapshot();
        status.put("resolution", cameraManager.getActiveResolution() == null
                ? uprightResolution() : cameraManager.getActiveResolution());
        status.put("jpeg_quality", Integer.valueOf(settings.jpegQuality()));
        status.put("mjpeg_fps", Integer.valueOf(settings.mjpegFps()));
        status.put("auth", Boolean.valueOf(settings.authEnabled()));
        status.put("mcp", Boolean.valueOf(settings.mcpEnabled()));
        status.put("snapshot", Boolean.TRUE);
        status.put("mjpeg", Boolean.TRUE);
        status.put("h264", Boolean.FALSE);
        byte[] body = io.github.ctxinf.cameraagent.legacy.json.Json.write(status).getBytes("UTF-8");

        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: application/json\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private void serveCameraConfig(OutputStream out) throws IOException {
        java.util.Map<String, Object> data = io.github.ctxinf.cameraagent.legacy.json.Json.map();
        data.put("enabled", Boolean.valueOf(settings.cameraEnabled()));
        data.put("facing", settings.cameraFacing() == 1 ? "front" : "back");
        data.put("front_camera_disabled", Boolean.valueOf(settings.frontCameraDisabled()));
        sendJson(out, 200, io.github.ctxinf.cameraagent.legacy.json.Json.write(data));
    }

    /** POST {"enabled":bool,"facing":"front|back"}; either field is optional. */
    private void updateCameraConfig(OutputStream out, String body) throws IOException {
        try {
            java.util.Map<String, Object> data = io.github.ctxinf.cameraagent.legacy.json.Json.parseObject(body);
            Object enabled = data.get("enabled");
            if (enabled != null) {
                if (!(enabled instanceof Boolean)) { sendError(out, 400, "enabled must be boolean"); return; }
                settings.setCameraEnabled(((Boolean) enabled).booleanValue());
            }
            String facing = io.github.ctxinf.cameraagent.legacy.json.Json.str(data, "facing");
            if (facing != null) {
                if (!"front".equals(facing) && !"back".equals(facing)) {
                    sendError(out, 400, "facing must be front or back"); return;
                }
                if ("front".equals(facing) && settings.frontCameraDisabled()) {
                    sendError(out, 403, "Front Camera Disabled"); return;
                }
                settings.setCameraFacing("front".equals(facing) ? 1 : 0);
                cameraManager.setFacing(settings.cameraFacing());
            }
            cameraManager.setAccessPolicy(settings.cameraEnabled(), settings.frontCameraDisabled());
            cameraManager.reconfigure();
            serveCameraConfig(out);
        } catch (io.github.ctxinf.cameraagent.legacy.json.Json.JsonException e) {
            sendError(out, 400, "Invalid JSON");
        }
    }

    /**
     * HTTP Basic auth. Disabled by default; a configured-but-empty username is
     * treated as disabled rather than as a lock-out with no way in.
     */
    private boolean authorized(String authHeader) {
        if (!settings.authEnabled()) {
            return true;
        }
        String user = settings.authUser();
        String pass = settings.authPass();
        if (user == null || user.length() == 0) {
            return true;
        }
        if (authHeader == null || !startsWithIgnoreCase(authHeader, "Basic ")) {
            return false;
        }
        String encoded = authHeader.substring("Basic ".length()).trim();
        String decoded;
        try {
            decoded = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
                    "UTF-8");
        } catch (Exception e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String gotUser = decoded.substring(0, colon);
        String gotPass = decoded.substring(colon + 1);
        // & not &&: both comparisons always run, so a wrong username and a wrong
        // password cost the same.
        return constantTimeEquals(user, gotUser)
                & constantTimeEquals(pass == null ? "" : pass, gotPass);
    }

    /** Compare without an early exit, so response time does not leak the prefix length. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x;
        byte[] y;
        try {
            x = a.getBytes("UTF-8");
            y = b.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void sendUnauthorized(OutputStream out) throws IOException {
        byte[] body = "Unauthorized".getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 401 Unauthorized\r\n");
        h.append("WWW-Authenticate: Basic realm=\"Camera Agent\"\r\n");
        h.append("Content-Type: text/plain\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private static String headerValue(String line, String name) {
        return line.substring(name.length()).trim();
    }

    /**
     * Read exactly Content-Length bytes. The body is read as UTF-8; MCP requires
     * JSON-RPC messages to be UTF-8 encoded.
     */
    private static String readBody(InputStream in, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return "";
        }
        if (contentLength > MAX_BODY_BYTES) {
            return null;
        }
        byte[] buf = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(buf, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return new String(buf, 0, read, "UTF-8");
    }

    /**
     * Accept only same-origin-ish requests: loopback, or an Origin whose host is
     * this device. Anything else is a page on another site trying to reach the
     * agent through the user's browser.
     */
    private boolean isAllowedOrigin(String origin) {
        String host = origin;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);   // strip port, but not from a bare IPv6
        }
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        String own = io.github.ctxinf.cameraagent.legacy.util.NetworkUtil.getLocalIpAddress();
        return own != null && own.equals(host);
    }

    private void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(" ").append(reasonFor(status)).append("\r\n");
        if (body.length > 0) {
            h.append("Content-Type: application/json\r\n");
        }
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static String reasonFor(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default:  return "OK";
        }
    }

    /** The configured size, swapped if the rotation will turn it a quarter turn. */
    private String uprightResolution() {
        int r = cameraManager.getFrameRotation();
        boolean swap = (r == 90 || r == 270);
        return swap
                ? (settings.resolutionHeight() + "x" + settings.resolutionWidth())
                : (settings.resolutionWidth() + "x" + settings.resolutionHeight());
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendError(OutputStream out, int code, String reason) throws IOException {
        byte[] body = reason.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        h.append("Content-Type: text/plain\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    /** Read a CRLF-terminated line without over-reading into the body. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) {
                throw new IOException("header line too long");
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }
}
