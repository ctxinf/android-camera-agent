package io.github.ctxinf.cameraagent.legacy;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.SystemClock;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.json.Json;
import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;

/**
 * Reads inexpensive device metrics without opening the camera. The result is
 * shared by HTTP and MQTT so both protocols expose the same schema.
 */
public final class DeviceStatus {
    private static final long CACHE_MS = 5000;

    private final Context context;
    private final CameraManager cameraManager;
    private final Settings settings;

    private long previousCpuTotal;
    private long previousCpuIdle;
    private boolean haveCpuSample;
    private long lastSampleAt;
    private Map<String, Object> cached;

    public DeviceStatus(Context context, CameraManager cameraManager, Settings settings) {
        this.context = context.getApplicationContext();
        this.cameraManager = cameraManager;
        this.settings = settings;
    }

    /** Returns a JSON-ready snapshot. Calling this never opens the camera. */
    public synchronized Map<String, Object> snapshot() {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - lastSampleAt < CACHE_MS) {
            return cached;
        }

        Map<String, Object> out = Json.map();
        out.put("state", cameraManager.getState().toString());
        out.put("leases", Integer.valueOf(cameraManager.getLeaseCount()));
        out.put("frames", Integer.valueOf(cameraManager.getFramesSinceOpen()));
        out.put("listeners", Integer.valueOf(cameraManager.getListenerCount()));
        String active = cameraManager.getActiveResolution();
        out.put("resolution", active == null ? "unknown" : active);
        out.put("rotation", Integer.valueOf(cameraManager.getFrameRotation()));
        out.put("facing", settingsFacing());
        out.put("http_enabled", Boolean.valueOf(settings.httpEnabled()));
        out.put("mcp_enabled", Boolean.valueOf(settings.mcpEnabled()));
        out.put("camera_mode", !settings.cameraEnabled() ? "disabled" : settingsFacing());
        out.put("rotation", Integer.valueOf(settings.rotationOffset()));
        out.put("screen_brightness", Integer.valueOf(((CameraAgentApp) context).currentScreenBrightness()));
        out.put("camera_enabled", Boolean.valueOf(settings.cameraEnabled()));
        out.put("front_camera_disabled", Boolean.valueOf(settings.frontCameraDisabled()));
        out.put("access_denied", Boolean.valueOf(cameraManager.isAccessDenied()));
        out.put("android_sdk", Integer.valueOf(android.os.Build.VERSION.SDK_INT));
        out.put("model", android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);

        readBattery(out);
        readMemory(out);
        readStorage(out);
        out.put("cpu_percent", readCpuPercent());

        cached = out;
        lastSampleAt = now;
        return out;
    }

    public synchronized String snapshotJson() {
        return Json.write(snapshot());
    }

    public synchronized void invalidate() {
        cached = null;
        lastSampleAt = 0;
    }

    private String settingsFacing() {
        return settings.cameraFacing() == 1 ? "front" : "back";
    }

    private void readBattery(Map<String, Object> out) {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            out.put("battery_percent", null);
            out.put("battery_status", "unknown");
            out.put("battery_temperature_c", null);
            return;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            out.put("battery_percent", Integer.valueOf((level * 100) / scale));
        } else {
            out.put("battery_percent", null);
        }

        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                out.put("battery_status", "charging");
                break;
            case BatteryManager.BATTERY_STATUS_FULL:
                out.put("battery_status", "full");
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                out.put("battery_status", "discharging");
                break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                out.put("battery_status", "not_charging");
                break;
            default:
                out.put("battery_status", "unknown");
                break;
        }

        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        out.put("battery_temperature_c", tenths == Integer.MIN_VALUE
                ? null : Double.valueOf(tenths / 10.0));
    }

    private void readMemory(Map<String, Object> out) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            out.put("memory_available_mb", null);
            out.put("memory_low", null);
            return;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        out.put("memory_available_mb", Long.valueOf(info.availMem / (1024L * 1024L)));
        out.put("memory_low", Boolean.valueOf(info.lowMemory));
    }

    private void readStorage(Map<String, Object> out) {
        File data = Environment.getDataDirectory();
        out.put("storage_available_mb", Long.valueOf(data.getFreeSpace() / (1024L * 1024L)));
    }

    /** Overall CPU usage from /proc/stat, not this process's own CPU share. */
    private Integer readCpuPercent() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return null;
            }
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 5) {
                return null;
            }
            long total = 0;
            for (int i = 1; i < fields.length; i++) {
                total += Long.parseLong(fields[i]);
            }
            long idle = Long.parseLong(fields[4]);
            if (fields.length > 5) {
                idle += Long.parseLong(fields[5]); // iowait
            }
            Integer result = null;
            if (haveCpuSample && total > previousCpuTotal) {
                long totalDelta = total - previousCpuTotal;
                long idleDelta = idle - previousCpuIdle;
                double percent = Math.max(0.0,
                        Math.min(100.0, (totalDelta - idleDelta) * 100.0 / totalDelta));
                result = Integer.valueOf((int) Math.round(percent));
            }
            previousCpuTotal = total;
            previousCpuIdle = idle;
            haveCpuSample = true;
            return result;
        } catch (Exception e) {
            Log.d("cpu sample unavailable: " + e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }
}
