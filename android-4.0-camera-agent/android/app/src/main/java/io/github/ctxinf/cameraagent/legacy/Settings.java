package io.github.ctxinf.cameraagent.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Typed accessors for the user-visible settings, in one place so the service,
 * the HTTP server and the settings UI cannot disagree about key names or
 * defaults.
 *
 * <p>Everything is stored as a String: the settings UI is built from
 * EditTextPreference/ListPreference, which always write strings, and reading an
 * int key that a preference wrote as a string throws ClassCastException.
 */
public final class Settings {

    public static final String KEY_HTTP_PATH_PREFIX = "http_path_prefix";
    public static final String KEY_HTTP_ENABLED = "http_enabled";
    public void setHttpEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_HTTP_ENABLED, enabled).commit(); }
    public void setMcpEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_MCP_ENABLED, enabled).commit(); }
    public boolean httpEnabled() { return prefs.getBoolean(KEY_HTTP_ENABLED, true); }
    public boolean backgroundEnabled() { return prefs.getBoolean("background_enabled", true); }
    public void setBackgroundEnabled(boolean enabled) { prefs.edit().putBoolean("background_enabled", enabled).commit(); }
    public boolean manuallyExited() { return prefs.getBoolean("manually_exited", false); }
    public void setManuallyExited(boolean exited) { prefs.edit().putBoolean("manually_exited", exited).commit(); }

    public static final String KEY_HTTP_BIND_ADDRESS = "http_bind_address";
    public String httpBindAddress() { return prefs.getString(KEY_HTTP_BIND_ADDRESS, "0.0.0.0"); }

    public static final String KEY_HTTP_PORT = "http_port";
    public static final String KEY_AUTH_ENABLED = "auth_enabled";
    public static final String KEY_AUTH_USER = "auth_user";
    public static final String KEY_AUTH_PASS = "auth_pass";
    public static final String KEY_DIM_BRIGHTNESS = "dim_brightness";
    public static final String KEY_WAKE_SECONDS = "wake_seconds";
    public static final String KEY_RESOLUTION = "resolution";
    public static final String KEY_JPEG_QUALITY = "jpeg_quality";
    public static final String KEY_MJPEG_FPS = "mjpeg_fps";
    public static final String KEY_CAMERA_FACING = "camera_facing";
    public static final String KEY_CAMERA_ENABLED = "camera_enabled";
    public static final String KEY_FRONT_CAMERA_DISABLED = "front_camera_disabled";
    public static final String KEY_MCP_ENABLED = "mcp_enabled";
    public static final String KEY_ROTATION = "rotation";
    public static final String KEY_SENSOR_ROTATION = "sensor_rotation";
    public static final String KEY_MQTT_ENABLED = "mqtt_enabled";
    public static final String KEY_MQTT_HOST = "mqtt_host";
    public static final String KEY_MQTT_PORT = "mqtt_port";
    public static final String KEY_MQTT_USER = "mqtt_user";
    public static final String KEY_MQTT_PASS = "mqtt_pass";
    public static final String KEY_MQTT_PREFIX = "mqtt_prefix";
    private static final String KEY_MQTT_DEVICE_ID = "mqtt_device_id";
    private static final String KEY_MQTT_DISCOVERY_MIGRATED = "mqtt_discovery_migrated";

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_JPEG_QUALITY = 80;
    public static final int DEFAULT_MJPEG_FPS = 10;
    public static final int DEFAULT_MQTT_PORT = 1883;
    public static final String DEFAULT_MQTT_PREFIX = "camera_agent";
    public static final int DEFAULT_WAKE_SECONDS = 15;
    /** Percent of full backlight while idle. Not 0: some 4.x panels read 0 as "system default". */
    public static final int DEFAULT_DIM_PERCENT = 2;
    public static final String DEFAULT_RESOLUTION = "640x480";
    /** Matches Camera.CameraInfo.CAMERA_FACING_BACK. */
    public static final int DEFAULT_FACING = 0;

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static Settings of(Context context) {
        return new Settings(context);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public int httpPort() {
        return clamp(getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT), 1024, 65535);
    }

    public String httpPathPrefix() {
        return io.github.ctxinf.cameraagent.legacy.util.HttpPathPrefix.normalize(
                prefs.getString(KEY_HTTP_PATH_PREFIX, ""));
    }

    public boolean authEnabled() {
        return prefs.getBoolean(KEY_AUTH_ENABLED, false);
    }

    public String authUser() {
        return prefs.getString(KEY_AUTH_USER, "");
    }

    public String authPass() {
        return prefs.getString(KEY_AUTH_PASS, "");
    }

    /** Backlight level while idle, as a 0..1 window brightness. */
    public float dimBrightness() {
        return clamp(getInt(KEY_DIM_BRIGHTNESS, DEFAULT_DIM_PERCENT), 0, 100) / 100f;
    }

    public long wakeDurationMs() {
        return clamp(getInt(KEY_WAKE_SECONDS, DEFAULT_WAKE_SECONDS), 3, 600) * 1000L;
    }

    /** Requested preview size as "WxH"; the camera picks the closest supported one. */
    public String resolution() {
        String v = prefs.getString(KEY_RESOLUTION, DEFAULT_RESOLUTION);
        return (v == null || v.indexOf('x') < 0) ? DEFAULT_RESOLUTION : v;
    }

    public int resolutionWidth() {
        return parseDim(resolution(), 0, 640);
    }

    public int resolutionHeight() {
        return parseDim(resolution(), 1, 480);
    }

    public int jpegQuality() {
        return clamp(getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY), 10, 100);
    }

    public int mjpegFps() {
        return clamp(getInt(KEY_MJPEG_FPS, DEFAULT_MJPEG_FPS), 1, 30);
    }

    /**
     * Whether the MCP endpoint is served. Off by default: it is an additional
     * remote-control surface, so it should be opted into rather than exposed to
     * the LAN the moment the app is installed.
     */
    /**
     * Extra clockwise rotation applied on top of the sensor-derived angle, in
     * degrees (0/90/180/270). The automatic value is right on most hardware, but
     * a phone mounted sideways in a window bracket needs a manual correction.
     */
    public int rotationOffset() {
        int v = getInt(KEY_ROTATION, 0);
        // Snap to a quarter turn and normalise; parenthesised because * and %
        // share a precedence level and evaluate left to right.
        return ((((v / 90) * 90) % 360) + 360) % 360;
    }

    public void setRotationOffset(int degrees) {
        int v = ((degrees % 360) + 360) % 360;
        prefs.edit().putString(KEY_ROTATION, String.valueOf(v)).commit();
    }

    /**
     * Last sensor mounting angle observed at camera open, cached so the reported
     * rotation is right immediately after a restart. Querying CameraInfo before
     * the camera has ever been opened returns nothing useful on some 4.x ROMs,
     * and opening the camera just to answer a status query would defeat the
     * whole point of keeping it closed.
     */
    public int sensorRotation() {
        return getInt(KEY_SENSOR_ROTATION, 90);
    }

    public void setSensorRotation(int degrees) {
        int v = ((degrees % 360) + 360) % 360;
        if (v == sensorRotation()) {
            return;
        }
        prefs.edit().putString(KEY_SENSOR_ROTATION, String.valueOf(v)).commit();
    }

    public boolean mcpEnabled() {
        return prefs.getBoolean(KEY_MCP_ENABLED, false);
    }

    public boolean mqttEnabled() {
        return prefs.getBoolean(KEY_MQTT_ENABLED, false);
    }

    public String mqttHost() {
        return prefs.getString(KEY_MQTT_HOST, "");
    }

    public int mqttPort() {
        return clamp(getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT), 1, 65535);
    }

    public String mqttUser() {
        return prefs.getString(KEY_MQTT_USER, "");
    }

    public String mqttPass() {
        return prefs.getString(KEY_MQTT_PASS, "");
    }

    public String mqttPrefix() {
        String prefix = prefs.getString(KEY_MQTT_PREFIX, DEFAULT_MQTT_PREFIX);
        if (prefix == null || prefix.trim().length() == 0) {
            return DEFAULT_MQTT_PREFIX;
        }
        return prefix.trim().replace('/', '_').replace('\\', '_');
    }

    /**
     * Per-install identity used by MQTT discovery. ANDROID_ID alone is not safe
     * here: cloned/older ROM images can return the same value on several phones,
     * causing Home Assistant to treat them as one device.
     */
    public synchronized String mqttDeviceId() {
        String id = prefs.getString(KEY_MQTT_DEVICE_ID, "");
        if (id != null && id.length() > 0) {
            return id;
        }
        id = "camera_" + java.util.UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_MQTT_DEVICE_ID, id).commit();
        return id;
    }

    public boolean mqttDiscoveryMigrated() {
        return prefs.getBoolean(KEY_MQTT_DISCOVERY_MIGRATED, false);
    }

    public void setMqttDiscoveryMigrated() {
        prefs.edit().putBoolean(KEY_MQTT_DISCOVERY_MIGRATED, true).commit();
    }

    /** Camera.CameraInfo facing constant; 0 = back, 1 = front. */
    public int cameraFacing() {
        return getInt(KEY_CAMERA_FACING, DEFAULT_FACING) == 1 ? 1 : 0;
    }

    /** Master security gate. Network services remain available when it is off. */
    public boolean cameraEnabled() {
        return prefs.getBoolean(KEY_CAMERA_ENABLED, true);
    }

    public void setCameraEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CAMERA_ENABLED, enabled).commit();
    }

    /** Highest-priority policy: the front lens may never be opened when true. */
    public boolean frontCameraDisabled() {
        return prefs.getBoolean(KEY_FRONT_CAMERA_DISABLED, false);
    }

    public void setCameraFacing(int facing) {
        prefs.edit().putString(KEY_CAMERA_FACING, facing == 1 ? "1" : "0").commit();
    }

    /**
     * Preferences write ints as strings, but an older build (or a programmatic
     * put) may have stored a real int. Tolerate both rather than crashing.
     */
    private int getInt(String key, int def) {
        try {
            String s = prefs.getString(key, null);
            if (s == null || s.trim().length() == 0) {
                return def;
            }
            return Integer.parseInt(s.trim());
        } catch (ClassCastException e) {
            return prefs.getInt(key, def);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseDim(String wxh, int index, int def) {
        try {
            String[] p = wxh.split("x");
            return Integer.parseInt(p[index].trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
