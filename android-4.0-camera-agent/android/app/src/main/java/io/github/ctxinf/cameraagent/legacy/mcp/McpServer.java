package io.github.ctxinf.cameraagent.legacy.mcp;

import io.github.ctxinf.cameraagent.legacy.Settings;
import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.json.Json;
import io.github.ctxinf.cameraagent.legacy.util.Log;
import io.github.ctxinf.cameraagent.legacy.util.NetworkUtil;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) server, Streamable HTTP binding, revision
 * {@code 2026-07-28}.
 *
 * <p>That revision is what makes this practical on a 1 GB Android 4.x device:
 * it removed protocol-level sessions, the {@code initialize} handshake and the
 * standalone GET/SSE stream, so the whole transport is "POST one JSON-RPC
 * request, return one JSON object". There is no per-connection state to keep,
 * which means this class is a pure function of the request plus the camera.
 *
 * <p>Legacy clients ({@code 2025-11-25} and earlier) are still answered: they
 * open with an {@code initialize} request, which selects the handshake
 * behaviour. The spec calls this a "dual-era" server.
 *
 * <p>The transport lives in {@code HttpServer}; this class only turns a request
 * body plus headers into a response body.
 */
public class McpServer {

    /** The revision this server implements natively. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** Legacy revisions answered through the {@code initialize} handshake. */
    private static final String[] SUPPORTED_VERSIONS = {
            "2026-07-28", "2025-11-25", "2025-06-18",
    };

    public static final String SERVER_NAME = "android-camera-agent";
    public static final String SERVER_VERSION = "0.3.0";

    private static final String META_PROTOCOL = "io.modelcontextprotocol/protocolVersion";
    private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    // JSON-RPC and MCP error codes.
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int HEADER_MISMATCH = -32020;
    private static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** Snapshot timeout for a tool call. Generous: the camera may be cold. */
    private static final long SNAPSHOT_TIMEOUT_MS = 10000;

    /**
     * Cache hint for discovery and the tool catalogue. Both results are static
     * for this APK and contain no caller-specific data, so shared caching is
     * safe. A short TTL still lets an upgraded APK be noticed promptly.
     */
    private static final long CATALOGUE_TTL_MS = 300000;

    private final CameraManager cameraManager;
    private final Settings settings;

    /** Carries the HTTP status alongside the body, since MCP is status-sensitive. */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public McpServer(CameraManager cameraManager, Settings settings) {
        this.cameraManager = cameraManager;
        this.settings = settings;
    }

    /**
     * Handle one POST to the MCP endpoint.
     *
     * @param body      raw request body
     * @param headerVersion value of the {@code MCP-Protocol-Version} header, or null
     * @param headerMethod  value of the {@code Mcp-Method} header, or null
     * @param headerName    value of the {@code Mcp-Name} header, or null
     */
    public Response handle(String body, String headerVersion, String headerMethod,
                           String headerName) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(body);
        } catch (Json.JsonException e) {
            return error(null, PARSE_ERROR, "Parse error: " + e.getMessage(), 400, null);
        }

        Object id = req.get("id");
        String method = Json.str(req, "method");
        if (method == null) {
            return error(id, INVALID_REQUEST, "Missing 'method'", 400, null);
        }
        Map<String, Object> params = Json.obj(req, "params");

        // A notification has no id and expects 202 with an empty body. The
        // modern revision defines none, but a stray one must not be answered
        // with a JSON-RPC response.
        boolean isNotification = !req.containsKey("id");

        // Legacy clients open with initialize; that selects handshake semantics
        // for this request and is answered regardless of the modern headers,
        // which such clients do not send.
        if ("initialize".equals(method)) {
            return ok(result(id, legacyInitializeResult(params)));
        }
        if (isNotification) {
            return new Response(202, "");
        }

        Map<String, Object> meta = (params == null) ? null : Json.obj(params, "_meta");
        String bodyVersion = (meta == null) ? null : Json.str(meta, META_PROTOCOL);

        // Header/body agreement. The point is that an intermediary routing on
        // the header and this server acting on the body can never disagree.
        Response mismatch = validateHeaders(id, method, params,
                headerVersion, headerMethod, headerName, bodyVersion);
        if (mismatch != null) {
            return mismatch;
        }

        String version = (bodyVersion != null) ? bodyVersion : headerVersion;
        if (version != null && !isSupported(version)) {
            Map<String, Object> data = Json.map();
            data.put("supported", supportedList());
            data.put("requested", version);
            return error(id, UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported protocol version", 400, data);
        }

        try {
            if ("server/discover".equals(method)) {
                return ok(result(id, discoverResult()));
            }
            if ("tools/list".equals(method)) {
                return ok(result(id, toolsListResult()));
            }
            if ("tools/call".equals(method)) {
                return ok(result(id, toolsCallResult(params)));
            }
            if ("ping".equals(method)) {
                return ok(result(id, Json.map()));
            }
            // Unknown method is a 404 with a JSON-RPC body, which is what lets a
            // client tell a modern server apart from a stale HTTP+SSE endpoint.
            return error(id, METHOD_NOT_FOUND, "Method not found: " + method, 404, null);
        } catch (Exception e) {
            Log.e("mcp handler failed: " + e);
            return error(id, INTERNAL_ERROR, "Internal error: " + e, 500, null);
        }
    }

    /**
     * Enforce the mirrored-header rules. Only applied to modern requests: a
     * request with no version anywhere is treated as legacy and passed through,
     * per the backward-compatibility rules.
     */
    private Response validateHeaders(Object id, String method, Map<String, Object> params,
                                     String headerVersion, String headerMethod,
                                     String headerName, String bodyVersion) {
        boolean modern = (bodyVersion != null) || (headerVersion != null);
        if (!modern) {
            return null;
        }
        if (headerVersion == null) {
            return error(id, HEADER_MISMATCH, "Missing MCP-Protocol-Version header", 400, null);
        }
        if (bodyVersion != null && !bodyVersion.equals(headerVersion)) {
            return error(id, HEADER_MISMATCH, "MCP-Protocol-Version header '" + headerVersion
                    + "' does not match body value '" + bodyVersion + "'", 400, null);
        }
        if (headerMethod == null) {
            return error(id, HEADER_MISMATCH, "Missing Mcp-Method header", 400, null);
        }
        if (!headerMethod.equals(method)) {
            return error(id, HEADER_MISMATCH, "Mcp-Method header '" + headerMethod
                    + "' does not match body value '" + method + "'", 400, null);
        }
        // Mcp-Name mirrors params.name and is required for tools/call.
        if ("tools/call".equals(method)) {
            String bodyName = (params == null) ? null : Json.str(params, "name");
            if (headerName == null) {
                return error(id, HEADER_MISMATCH, "Missing Mcp-Name header", 400, null);
            }
            String decoded = decodeHeaderValue(headerName);
            if (bodyName != null && !decoded.equals(bodyName)) {
                return error(id, HEADER_MISMATCH, "Mcp-Name header '" + decoded
                        + "' does not match body value '" + bodyName + "'", 400, null);
            }
        }
        return null;
    }

    /**
     * Undo the {@code =?base64?...?=} sentinel the spec defines for header
     * values that cannot be written as plain ASCII.
     */
    private static String decodeHeaderValue(String raw) {
        String v = raw.trim();
        if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length() > 11) {
            String inner = v.substring("=?base64?".length(), v.length() - 2);
            try {
                return new String(android.util.Base64.decode(inner, android.util.Base64.DEFAULT),
                        "UTF-8");
            } catch (Exception e) {
                return v;   // not decodable; compare verbatim and let it fail loudly
            }
        }
        return v;
    }

    private static boolean isSupported(String version) {
        for (int i = 0; i < SUPPORTED_VERSIONS.length; i++) {
            if (SUPPORTED_VERSIONS[i].equals(version)) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> supportedList() {
        List<Object> out = Json.list();
        for (int i = 0; i < SUPPORTED_VERSIONS.length; i++) {
            out.add(SUPPORTED_VERSIONS[i]);
        }
        return out;
    }

    // --- results ------------------------------------------------------------

    private Map<String, Object> discoverResult() {
        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        putPublicCatalogueCacheHint(r);
        r.put("supportedVersions", supportedList());

        Map<String, Object> caps = Json.map();
        caps.put("tools", Json.map());
        r.put("capabilities", caps);

        Map<String, Object> meta = Json.map();
        meta.put(META_SERVER_INFO, serverInfo());
        r.put("_meta", meta);

        r.put("instructions",
                "Controls an Android phone used as a fixed remote camera. "
                + "get_snapshot returns a still image from the camera; the camera is "
                + "off by default and is powered on only for the duration of a call, "
                + "so the first capture after an idle period takes about a second. "
                + "get_status reports camera and network state without touching the "
                + "hardware. set_camera_config changes resolution and JPEG quality.");
        return r;
    }

    /** Legacy InitializeResult, for clients that still open with a handshake. */
    private Map<String, Object> legacyInitializeResult(Map<String, Object> params) {
        // Echo the client's version when we support it, else offer our newest.
        String asked = (params == null) ? null : Json.str(params, "protocolVersion");
        String agreed = (asked != null && isSupported(asked)) ? asked : "2025-11-25";

        Map<String, Object> r = Json.map();
        r.put("protocolVersion", agreed);
        Map<String, Object> caps = Json.map();
        caps.put("tools", Json.map());
        r.put("capabilities", caps);
        r.put("serverInfo", serverInfo());
        r.put("instructions", "Android remote camera agent. Use get_snapshot to capture "
                + "a still image, get_status for camera state.");
        return r;
    }

    private static Map<String, Object> serverInfo() {
        Map<String, Object> info = Json.map();
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return info;
    }

    private Map<String, Object> toolsListResult() {
        List<Object> tools = Json.list();

        // get_snapshot
        Map<String, Object> snap = Json.map();
        snap.put("name", "get_snapshot");
        snap.put("title", "Capture camera image");
        snap.put("description",
                "Capture a still image from the phone's camera and return it as an image. "
                + "The camera is opened on demand and closed again shortly after, so a "
                + "capture from cold takes roughly one second.");
        Map<String, Object> snapSchema = Json.map();
        snapSchema.put("type", "object");
        Map<String, Object> snapProps = Json.map();
        Map<String, Object> qualityProp = Json.map();
        qualityProp.put("type", "integer");
        qualityProp.put("minimum", Integer.valueOf(10));
        qualityProp.put("maximum", Integer.valueOf(100));
        qualityProp.put("description", "JPEG quality 10-100. Defaults to the configured value.");
        snapProps.put("quality", qualityProp);
        snapSchema.put("properties", snapProps);
        snapSchema.put("additionalProperties", Boolean.FALSE);
        snap.put("inputSchema", snapSchema);
        tools.add(snap);

        // get_status
        Map<String, Object> status = Json.map();
        status.put("name", "get_status");
        status.put("title", "Camera and device status");
        status.put("description",
                "Report camera state, resolution, active client count and network address. "
                + "Does not open the camera.");
        Map<String, Object> statusSchema = Json.map();
        statusSchema.put("type", "object");
        statusSchema.put("additionalProperties", Boolean.FALSE);
        status.put("inputSchema", statusSchema);
        Map<String, Object> statusOut = Json.map();
        statusOut.put("type", "object");
        Map<String, Object> statusOutProps = Json.map();
        statusOutProps.put("state", typed("string", "Camera state: CLOSED, OPENING or OPEN"));
        statusOutProps.put("resolution", typed("string", "Active preview resolution, WxH"));
        statusOutProps.put("clients", typed("integer", "Number of open camera leases"));
        statusOutProps.put("address", typed("string", "LAN address of the HTTP server"));
        statusOutProps.put("model", typed("string", "Device model"));
        statusOut.put("properties", statusOutProps);
        status.put("outputSchema", statusOut);
        tools.add(status);

        // set_camera_config
        Map<String, Object> config = Json.map();
        config.put("name", "set_camera_config");
        config.put("title", "Change camera configuration");
        config.put("description",
                "Change the camera resolution and/or JPEG quality. The resolution must be "
                + "one the hardware supports; the closest supported size is used otherwise.");
        Map<String, Object> configSchema = Json.map();
        configSchema.put("type", "object");
        Map<String, Object> configProps = Json.map();
        configProps.put("resolution",
                typed("string", "Preview resolution as WxH, for example 640x480"));
        Map<String, Object> q2 = typed("integer", "JPEG quality 10-100");
        q2.put("minimum", Integer.valueOf(10));
        q2.put("maximum", Integer.valueOf(100));
        configProps.put("quality", q2);
        configSchema.put("properties", configProps);
        configSchema.put("additionalProperties", Boolean.FALSE);
        config.put("inputSchema", configSchema);
        tools.add(config);

        tools.add(simpleTool("get_camera_facing", "Get selected camera",
                "Return front/back selection and access policy."));

        Map<String, Object> facing = simpleTool("set_camera_facing", "Select front/back camera",
                "Switch the selected lens. The front-camera security policy cannot be bypassed.");
        Map<String, Object> facingSchema = Json.map();
        facingSchema.put("type", "object");
        Map<String, Object> facingProps = Json.map();
        facingProps.put("facing", typed("string", "front or back"));
        facingSchema.put("properties", facingProps);
        java.util.List<Object> facingRequired = Json.list(); facingRequired.add("facing");
        facingSchema.put("required", facingRequired);
        facingSchema.put("additionalProperties", Boolean.FALSE);
        facing.put("inputSchema", facingSchema);
        tools.add(facing);

        Map<String, Object> enabled = simpleTool("set_camera_enabled", "Enable camera service",
                "Open or close the final camera access security gate while keeping APIs online.");
        Map<String, Object> enabledSchema = Json.map(); enabledSchema.put("type", "object");
        Map<String, Object> enabledProps = Json.map();
        enabledProps.put("enabled", typed("boolean", "Whether hardware camera access is allowed"));
        enabledSchema.put("properties", enabledProps);
        java.util.List<Object> enabledRequired = Json.list(); enabledRequired.add("enabled");
        enabledSchema.put("required", enabledRequired); enabledSchema.put("additionalProperties", Boolean.FALSE);
        enabled.put("inputSchema", enabledSchema);
        tools.add(enabled);

        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        putPublicCatalogueCacheHint(r);
        r.put("tools", tools);
        return r;
    }

    /** Add the cache metadata required on cacheable 2026-07-28 results. */
    private static void putPublicCatalogueCacheHint(Map<String, Object> result) {
        result.put("ttlMs", Long.valueOf(CATALOGUE_TTL_MS));
        result.put("cacheScope", "public");
    }

    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> m = Json.map();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> simpleTool(String name, String title, String description) {
        Map<String, Object> tool = Json.map();
        tool.put("name", name); tool.put("title", title); tool.put("description", description);
        Map<String, Object> schema = Json.map(); schema.put("type", "object");
        schema.put("additionalProperties", Boolean.FALSE); tool.put("inputSchema", schema);
        return tool;
    }

    private Map<String, Object> toolsCallResult(Map<String, Object> params) {
        String name = (params == null) ? null : Json.str(params, "name");
        if (name == null) {
            return toolError("Missing tool name");
        }
        Map<String, Object> args = Json.obj(params, "arguments");

        if ("get_snapshot".equals(name)) {
            return callSnapshot(args);
        }
        if ("get_status".equals(name)) {
            return callStatus();
        }
        if ("set_camera_config".equals(name)) {
            return callSetConfig(args);
        }
        if ("get_camera_facing".equals(name)) return cameraControlResult("Camera selection");
        if ("set_camera_facing".equals(name)) {
            if (!settings.cameraEnabled()) return toolError("Camera is disabled locally; enable it on the device");
            String facing = args == null ? null : Json.str(args, "facing");
            if (!"front".equals(facing) && !"back".equals(facing)) return toolError("facing must be front or back");
            if ("front".equals(facing) && settings.frontCameraDisabled()) return toolError("Front camera is disabled by the highest-priority policy");
            settings.setCameraFacing("front".equals(facing) ? 1 : 0);
            cameraManager.setFacing(settings.cameraFacing()); cameraManager.reconfigure();
            return cameraControlResult("Camera switched");
        }
        if ("set_camera_enabled".equals(name)) {
            Object value = args == null ? null : args.get("enabled");
            if (!(value instanceof Boolean)) return toolError("enabled must be boolean");
            return toolError("Camera enable/disable is device-only");
        }
        return toolError("Unknown tool: " + name);
    }

    private Map<String, Object> cameraControlResult(String message) {
        Map<String, Object> data = Json.map();
        data.put("enabled", Boolean.valueOf(settings.cameraEnabled()));
        data.put("facing", settings.cameraFacing() == 1 ? "front" : "back");
        data.put("front_camera_disabled", Boolean.valueOf(settings.frontCameraDisabled()));
        data.put("access_denied", Boolean.valueOf(cameraManager.isAccessDenied()));
        Map<String, Object> text = Json.map(); text.put("type", "text");
        text.put("text", message + ": " + Json.write(data));
        List<Object> content = Json.list(); content.add(text);
        Map<String, Object> result = Json.map(); result.put("resultType", "complete");
        result.put("content", content); result.put("structuredContent", data); result.put("isError", Boolean.FALSE);
        return result;
    }

    private Map<String, Object> callSnapshot(Map<String, Object> args) {
        int quality = settings.jpegQuality();
        Integer q = (args == null) ? null : Json.integer(args, "quality");
        if (q != null) {
            quality = Math.max(10, Math.min(100, q.intValue()));
        }

        byte[] jpeg = cameraManager.snapshotJpeg(SNAPSHOT_TIMEOUT_MS, quality);
        if (jpeg == null) {
            // A tool execution error, not a protocol error: the model can retry.
            return toolError("Camera unavailable: the capture timed out or the "
                    + "camera could not be opened.");
        }

        Map<String, Object> image = Json.map();
        image.put("type", "image");
        image.put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP));
        image.put("mimeType", "image/jpeg");

        String res = cameraManager.getActiveResolution();
        Map<String, Object> text = Json.map();
        text.put("type", "text");
        text.put("text", "Captured " + (res == null ? settings.resolution() : res)
                + " JPEG, " + jpeg.length + " bytes, quality " + quality + ".");

        List<Object> content = Json.list();
        content.add(text);
        content.add(image);

        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        r.put("content", content);
        r.put("isError", Boolean.FALSE);
        return r;
    }

    private Map<String, Object> callStatus() {
        String ip = NetworkUtil.getLocalIpAddress();
        String res = cameraManager.getActiveResolution();

        Map<String, Object> data = Json.map();
        data.put("state", cameraManager.getState().toString());
        data.put("resolution", res == null ? settings.resolution() : res);
        data.put("clients", Integer.valueOf(cameraManager.getLeaseCount()));
        data.put("address", ip == null ? "offline" : (ip + ":" + settings.httpPort()));
        data.put("model", android.os.Build.MODEL);
        data.put("camera_enabled", Boolean.valueOf(settings.cameraEnabled()));
        data.put("facing", settings.cameraFacing() == 1 ? "front" : "back");
        data.put("front_camera_disabled", Boolean.valueOf(settings.frontCameraDisabled()));

        Map<String, Object> text = Json.map();
        text.put("type", "text");
        text.put("text", Json.write(data));

        List<Object> content = Json.list();
        content.add(text);

        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        r.put("content", content);
        // Also as structured content, matching the declared outputSchema.
        r.put("structuredContent", data);
        r.put("isError", Boolean.FALSE);
        return r;
    }

    private Map<String, Object> callSetConfig(Map<String, Object> args) {
        if (args != null && args.containsKey("enabled")) return toolError("Camera enable/disable is device-only");
        if (args == null || args.isEmpty()) {
            return toolError("Nothing to change: pass resolution and/or quality.");
        }
        StringBuilder changed = new StringBuilder();

        String resolution = Json.str(args, "resolution");
        if (resolution != null) {
            if (resolution.indexOf('x') < 0) {
                return toolError("Invalid resolution '" + resolution
                        + "'. Expected a value like 640x480.");
            }
            settings.raw().edit().putString(Settings.KEY_RESOLUTION, resolution).commit();
            changed.append("resolution=").append(resolution);
        }

        Integer quality = Json.integer(args, "quality");
        if (quality != null) {
            int q = quality.intValue();
            if (q < 10 || q > 100) {
                return toolError("Invalid quality " + q + ". Expected 10-100.");
            }
            settings.raw().edit().putString(Settings.KEY_JPEG_QUALITY, String.valueOf(q)).commit();
            if (changed.length() > 0) {
                changed.append(", ");
            }
            changed.append("quality=").append(q);
        }

        if (changed.length() == 0) {
            return toolError("Nothing to change: pass resolution and/or quality.");
        }

        // Push the new size into the live camera. A no-op when it is closed,
        // which is the normal case.
        cameraManager.setPreviewSize(settings.resolutionWidth(), settings.resolutionHeight());
        cameraManager.reconfigure();

        Map<String, Object> text = Json.map();
        text.put("type", "text");
        text.put("text", "Updated " + changed + ".");
        List<Object> content = Json.list();
        content.add(text);

        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        r.put("content", content);
        r.put("isError", Boolean.FALSE);
        return r;
    }

    /** A tool-level failure: reported in the result so the model can react to it. */
    private static Map<String, Object> toolError(String message) {
        Map<String, Object> text = Json.map();
        text.put("type", "text");
        text.put("text", message);
        List<Object> content = Json.list();
        content.add(text);

        Map<String, Object> r = Json.map();
        r.put("resultType", "complete");
        r.put("content", content);
        r.put("isError", Boolean.TRUE);
        return r;
    }

    // --- envelopes ----------------------------------------------------------

    private static Response ok(String body) {
        return new Response(200, body);
    }

    private static String result(Object id, Map<String, Object> result) {
        Map<String, Object> m = Json.map();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return Json.write(m);
    }

    private static Response error(Object id, int code, String message, int status,
                                  Map<String, Object> data) {
        Map<String, Object> err = Json.map();
        err.put("code", Integer.valueOf(code));
        err.put("message", message);
        if (data != null) {
            err.put("data", data);
        }
        Map<String, Object> m = Json.map();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return new Response(status, Json.write(m));
    }
}
