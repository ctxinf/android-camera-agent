package io.github.ctxinf.cameraagent.legacy.mqtt;

import android.content.Context;

import io.github.ctxinf.cameraagent.legacy.DeviceStatus;
import io.github.ctxinf.cameraagent.legacy.Settings;
import io.github.ctxinf.cameraagent.legacy.json.Json;
import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.util.Map;

/** Publishes device metrics and Home Assistant MQTT Discovery messages. */
public final class MqttStatusPublisher implements Runnable {
    private static final long REPORT_INTERVAL_MS = 60000;
    private static final long PING_INTERVAL_MS = 25000;

    private final Context context;
    private final Settings settings;
    private final DeviceStatus deviceStatus;
    private final CameraManager cameraManager;
    private volatile boolean connected;
    public boolean isConnected() { return connected; }
    private volatile boolean running;
    private volatile MqttConnection activeConnection;
    private Thread thread;

    public MqttStatusPublisher(Context context, Settings settings, DeviceStatus deviceStatus,
                               CameraManager cameraManager) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.deviceStatus = deviceStatus;
        this.cameraManager = cameraManager;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "mqtt-status");
        thread.start();
    }

    public synchronized void stop() {
        connected = false;
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        MqttConnection connection = activeConnection;
        if (connection != null) {
            connection.close();
        }
        thread = null;
    }

    @Override
    public void run() {
        long backoff = 2000;
        while (running) {
            if (settings.manuallyExited() || !settings.mqttEnabled() || settings.mqttHost().trim().length() == 0) {
                sleep(5000);
                continue;
            }

            MqttConnection connection = null;
            String connectedAvailabilityTopic = null;
            try {
                String configuredHost = settings.mqttHost().trim();
                int configuredPort = settings.mqttPort();
                String configuredUser = settings.mqttUser();
                String configuredPass = settings.mqttPass();
                String deviceId = settings.mqttDeviceId();
                String prefix = settings.mqttPrefix();
                String stateTopic = prefix + "/" + deviceId + "/state";
                String availabilityTopic = prefix + "/" + deviceId + "/availability";
                String commandTopic = prefix + "/" + deviceId + "/camera/set";
                connectedAvailabilityTopic = availabilityTopic;
                String clientId = mqttClientId(deviceId);
                connection = new MqttConnection(configuredHost, configuredPort,
                        clientId, availabilityTopic, configuredUser, configuredPass);
                activeConnection = connection;
                connection.connect();
                connected = true;
                migrateLegacyDiscovery(connection, deviceId);
                connection.publish(availabilityTopic, "online", true);
                connection.subscribe("homeassistant/status");
                connection.subscribe(commandTopic);
                publishDiscovery(connection, deviceId, prefix, stateTopic, availabilityTopic, commandTopic);
                publishState(connection, stateTopic);
                Log.i("mqtt connected to " + settings.mqttHost() + ":" + settings.mqttPort());

                long nextReport = android.os.SystemClock.elapsedRealtime() + REPORT_INTERVAL_MS;
                long nextPing = android.os.SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
                backoff = 2000;
                while (running && settings.mqttEnabled()
                        && configuredHost.equals(settings.mqttHost().trim())
                        && configuredPort == settings.mqttPort()
                        && configuredUser.equals(settings.mqttUser())
                        && configuredPass.equals(settings.mqttPass())) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= nextReport) {
                        publishState(connection, stateTopic);
                        nextReport = now + REPORT_INTERVAL_MS;
                    }
                    if (now >= nextPing) {
                        connection.ping();
                        nextPing = now + PING_INTERVAL_MS;
                    }

                    MqttConnection.Packet packet = connection.poll();
                    MqttConnection.Incoming incoming = connection.incoming(packet);
                    if (incoming != null && commandTopic.equals(incoming.topic)) {
                        handleCameraCommand(incoming.payload);
                        publishState(connection, stateTopic);
                    } else if (incoming != null && "homeassistant/status".equals(incoming.topic)
                            && "online".equalsIgnoreCase(incoming.payload.trim())) {
                        publishDiscovery(connection, deviceId, prefix, stateTopic, availabilityTopic, commandTopic);
                        publishState(connection, stateTopic);
                    }
                }
            } catch (Exception e) {
                Log.w("mqtt session ended: " + e);
            } finally {
                connected = false;
                if (connection != null) {
                    try {
                        // A clean stop should not leave a retained "online"
                        // value behind. Network loss still relies on MQTT LWT.
                        if (connectedAvailabilityTopic != null) {
                            connection.publish(connectedAvailabilityTopic, "offline", true);
                        }
                    } catch (Exception ignored) {
                    }
                    connection.close();
                    if (activeConnection == connection) {
                        activeConnection = null;
                    }
                }
            }

            if (running) {
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60000);
            }
        }
        Log.i("mqtt status publisher stopped");
    }

    private void publishState(MqttConnection connection, String stateTopic) throws Exception {
        connection.publish(stateTopic, deviceStatus.snapshotJson(), true);
    }

    private void publishDiscovery(MqttConnection connection, String deviceId, String prefix,
                                  String stateTopic, String availabilityTopic,
                                  String commandTopic) throws Exception {
        Map<String, Object> root = Json.map();
        Map<String, Object> device = Json.map();
        java.util.List<Object> identifiers = Json.list();
        identifiers.add(deviceId);
        device.put("identifiers", identifiers);
        device.put("name", "Android Camera · " + deviceName() + " · " + shortId(deviceId));
        device.put("manufacturer", "Camera Agent");
        device.put("model", android.os.Build.MODEL == null ? "Android phone" : android.os.Build.MODEL);
        device.put("sw_version", appVersion());
        root.put("dev", device);

        Map<String, Object> origin = Json.map();
        origin.put("name", "Android Camera Agent");
        origin.put("sw_version", appVersion());
        root.put("o", origin);
        root.put("state_topic", stateTopic);
        root.put("availability_topic", availabilityTopic);
        root.put("payload_available", "online");
        root.put("payload_not_available", "offline");

        Map<String, Object> components = Json.map();
        components.put("battery", sensor("Battery", deviceId + "_battery", "battery", "%",
                "{{ value_json.battery_percent }}", false, false));
        components.put("battery_status", sensor("Battery status", deviceId + "_battery_status", null, null,
                "{{ value_json.battery_status }}", true, false));
        components.put("battery_temperature", sensor("Battery temperature", deviceId + "_battery_temperature",
                "temperature", "°C", "{{ value_json.battery_temperature_c }}", true, false));
        components.put("cpu", sensor("CPU usage", deviceId + "_cpu", null, "%",
                "{{ value_json.cpu_percent }}", true, true));
        components.put("memory", sensor("Available memory", deviceId + "_memory", null, "MB",
                "{{ value_json.memory_available_mb }}", true, true));
        components.put("storage", sensor("Available storage", deviceId + "_storage", null, "MB",
                "{{ value_json.storage_available_mb }}", true, true));
        components.put("camera", sensor("Camera state", deviceId + "_camera", null, null,
                "{{ value_json.state }}", true, false));
        components.put("http_enabled", control(deviceId, stateTopic, commandTopic, "http_enabled", "HTTP", "switch", null));
        components.put("mcp_enabled", control(deviceId, stateTopic, commandTopic, "mcp_enabled", "MCP", "switch", null));
        components.put("camera_mode", control(deviceId, stateTopic, commandTopic, "camera_mode", "Camera", "select", new String[]{"back", "front", "disabled"}));
        components.put("rotation", control(deviceId, stateTopic, commandTopic, "rotation", "Rotation", "select", new String[]{"0", "90", "180", "270"}));
        components.put("screen_brightness", control(deviceId, stateTopic, commandTopic, "screen_brightness", "Screen brightness", "number", null));
        root.put("cmps", components);

        String discoveryTopic = "homeassistant/device/" + deviceId + "/config";
        connection.publish(discoveryTopic, Json.write(root), true);
    }

    private void handleCameraCommand(String payload) {
        try {
            Map<String, Object> command = Json.parseObject(payload);
            if (command.isEmpty()) throw new IllegalArgumentException("Empty command");
            for (String key : command.keySet()) {
                if (!java.util.Arrays.asList("http_enabled", "mcp_enabled", "camera_mode", "rotation", "screen_brightness", "enabled", "facing").contains(key))
                    throw new IllegalArgumentException("Unknown command: " + key);
            }
            String mode = command.containsKey("camera_mode") ? Json.str(command, "camera_mode") : null;
            if (mode != null && !"back".equals(mode) && !"front".equals(mode) && !"disabled".equals(mode))
                throw new IllegalArgumentException("Invalid camera_mode");
            String facing = mode == null ? Json.str(command, "facing") : mode;
            if ("front".equals(facing) && settings.frontCameraDisabled())
                throw new SecurityException("Front camera is disabled on the device");
            Integer rotation = integer(command, "rotation", 0, 270);
            if (rotation != null && rotation.intValue() % 90 != 0) throw new IllegalArgumentException("Invalid rotation");
            Integer brightness = integer(command, "screen_brightness", 0, 100);
            Boolean http = bool(command, "http_enabled"), mcp = bool(command, "mcp_enabled");
            Boolean enabled = bool(command, "enabled");
            if (enabled != null) settings.setCameraEnabled(enabled.booleanValue());
            if (mode != null) settings.setCameraEnabled(!"disabled".equals(mode));
            if ("back".equals(facing) || "front".equals(facing)) settings.setCameraFacing("front".equals(facing) ? 1 : 0);
            if (rotation != null) settings.setRotationOffset(rotation.intValue());
            cameraManager.setFacing(settings.cameraFacing());
            cameraManager.setAccessPolicy(settings.cameraEnabled(), settings.frontCameraDisabled());
            cameraManager.setRotationOffset(settings.rotationOffset());
            if (http != null) settings.setHttpEnabled(http.booleanValue());
            if (mcp != null) settings.setMcpEnabled(mcp.booleanValue());
            if (http != null || mcp != null) {
                io.github.ctxinf.cameraagent.legacy.AgentService service = io.github.ctxinf.cameraagent.legacy.AgentService.get();
                if (service != null) service.restartHttpServer();
            }
            if (brightness != null) ((io.github.ctxinf.cameraagent.legacy.CameraAgentApp) context).setScreenBrightness(brightness.intValue());
            cameraManager.reconfigure();
            deviceStatus.invalidate();
        } catch (Exception e) {
            Log.w("invalid mqtt camera command: " + e);
        }
    }

    static Boolean bool(Map<String, Object> command, String key) {
        if (!command.containsKey(key)) return null;
        if (!(command.get(key) instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
        return (Boolean) command.get(key);
    }

    static Integer integer(Map<String, Object> command, String key, int min, int max) {
        if (!command.containsKey(key)) return null;
        int value;
        try { value = Integer.parseInt(String.valueOf(command.get(key))); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid " + key); }
        if (value < min || value > max) throw new IllegalArgumentException("Invalid " + key);
        return Integer.valueOf(value);
    }

    private Map<String, Object> control(String id, String state, String topic, String key,
                                       String name, String type, String[] choices) {
        Map<String, Object> out = Json.map();
        out.put("p", type); out.put("name", name); out.put("unique_id", id + "_" + key);
        out.put("state_topic", state); out.put("command_topic", topic);
        out.put("value_template", "{{ value_json." + key + " }}");
        if ("switch".equals(type)) {
            out.put("payload_on", "{\"" + key + "\":true}"); out.put("payload_off", "{\"" + key + "\":false}");
            out.put("state_on", "True"); out.put("state_off", "False");
        } else if ("select".equals(type)) {
            java.util.List<Object> options = Json.list();
            for (String choice : choices) options.add(choice);
            out.put("options", options);
            out.put("command_template", "{\"" + key + "\":\"{{ value }}\"}");
        } else {
            out.put("min", Integer.valueOf(0)); out.put("max", Integer.valueOf(100)); out.put("step", Integer.valueOf(1));
            out.put("unit_of_measurement", "%");
            out.put("command_template", "{\"" + key + "\":{{ value }}}");
        }
        return out;
    }

    private Map<String, Object> sensor(String name, String uniqueId, String deviceClass,
                                       String unit, String template, boolean diagnostic,
                                       boolean disabledByDefault) {
        Map<String, Object> sensor = Json.map();
        sensor.put("p", "sensor");
        sensor.put("name", name);
        sensor.put("unique_id", uniqueId);
        if (deviceClass != null) {
            sensor.put("device_class", deviceClass);
        }
        if (unit != null) {
            sensor.put("unit_of_measurement", unit);
        }
        if (template != null) {
            sensor.put("value_template", template);
        }
        if (diagnostic) {
            sensor.put("entity_category", "diagnostic");
        }
        if (disabledByDefault) {
            sensor.put("enabled_by_default", false);
        }
        return sensor;
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String deviceId() {
        String id = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (id == null || id.length() == 0) {
            id = "unknown";
        }
        return safeTopicPart(id);
    }

    /** Remove this installation's pre-0.5.4 ANDROID_ID discovery entry once. */
    private void migrateLegacyDiscovery(MqttConnection connection, String newDeviceId)
            throws Exception {
        if (settings.mqttDiscoveryMigrated()) {
            return;
        }
        String legacyId = deviceId();
        if (!legacyId.equals(newDeviceId)) {
            connection.publish("homeassistant/device/" + legacyId + "/config", "", true);
        }
        settings.setMqttDiscoveryMigrated();
    }

    private String deviceName() {
        String name = null;
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            try {
                name = android.provider.Settings.Global.getString(
                        context.getContentResolver(), "device_name");
            } catch (Exception ignored) { }
        }
        if (name == null || name.trim().length() == 0) {
            try {
                name = android.provider.Settings.Secure.getString(
                        context.getContentResolver(), "bluetooth_name");
            } catch (Exception ignored) { }
        }
        if (name == null || name.trim().length() == 0) {
            name = android.os.Build.MODEL;
        }
        return name == null || name.trim().length() == 0 ? "Android" : name.trim();
    }

    private static String shortId(String id) {
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }

    private static String mqttClientId(String deviceId) {
        String id = safeTopicPart("camera_agent_" + deviceId);
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    private static String safeTopicPart(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
