package io.github.ctxinf.cameraagent.legacy;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.gateway.GatewayClient;
import io.github.ctxinf.cameraagent.legacy.http.HttpServer;
import io.github.ctxinf.cameraagent.legacy.mqtt.MqttStatusPublisher;
import io.github.ctxinf.cameraagent.legacy.util.Log;

/**
 * The long-lived part of the app. What stays resident is this service and its
 * network listeners -- never the camera, which {@link CameraManager} opens only
 * for the duration of a request.
 *
 * <p>The UI activity may come and go; the service survives it. START_STICKY asks
 * the platform to restart us if we are killed.
 */
public class AgentService extends Service {

    public static final String PREF_GATEWAY_HOST = "gateway_host";
    public static final String PREF_GATEWAY_PORT = "gateway_port";

    private static final int DEFAULT_GATEWAY_PORT = 9000;

    private static AgentService instance;

    private Settings settings;
    private CameraManager cameraManager;
    private DeviceStatus deviceStatus;
    private volatile HttpServer httpServer;
    private final Object listenerLock = new Object();
    private volatile boolean destroyed;
    private GatewayClient gatewayClient;
    private MqttStatusPublisher mqttPublisher;

    /** How often the notification is refreshed. Cheap: it no-ops when unchanged. */
    private static final long STATUS_REFRESH_MS = 2000;

    private StatusNotifier notifier;
    private android.os.Handler statusHandler;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Exposed so the standby activity can hand the camera a preview surface. */
    public static AgentService get() {
        return instance;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    public Settings getSettings() {
        return settings;
    }

    /** Port the HTTP server is actually listening on, for the standby screen. */
    public int getHttpPort() {
        HttpServer h = httpServer;
        return h == null ? settings.httpPort() : h.getPort();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        if (settings.manuallyExited() || (!settings.backgroundEnabled()
                && !((CameraAgentApp) getApplication()).hasVisibleActivities())) {
            stopSelf();
            return;
        }
        instance = this;

        // A partial wake lock keeps the CPU available for the network listeners
        // while the screen is off. The screen itself is not held on here.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraAgent:service");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // Without a wifi lock, 4.x aggressively parks the radio when the screen
        // is off and the gateway connection dies silently.
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CameraAgent:wifi");
            wifiLock.setReferenceCounted(false);
            try {
                wifiLock.acquire();
            } catch (Exception e) {
                Log.w("wifi lock failed: " + e);
            }
        }

        settings = new Settings(this);
        cameraManager = new CameraManager(null);
        cameraManager.setAccessSettings(settings);
        applyCameraSettings();
        deviceStatus = new DeviceStatus(this, cameraManager, settings);

        httpServer = new HttpServer(settings.httpPort(), cameraManager, settings, deviceStatus);
        if ((settings.httpEnabled() || settings.mcpEnabled())) httpServer.start();

        mqttPublisher = new MqttStatusPublisher(this, settings, deviceStatus, cameraManager);
        mqttPublisher.start();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String gwHost = prefs.getString(PREF_GATEWAY_HOST, null);
        if (gwHost != null && gwHost.length() > 0) {
            int gwPort = prefs.getInt(PREF_GATEWAY_PORT, DEFAULT_GATEWAY_PORT);
            gatewayClient = new GatewayClient(gwHost, gwPort, deviceId(), cameraManager);
            gatewayClient.start();
        } else {
            Log.i("no gateway configured; LAN-only mode");
        }

        notifier = new StatusNotifier(this);
        statusHandler = new android.os.Handler();
        statusHandler.post(statusTask);

        Log.i("agent service started");
    }

    /**
     * Keeps the shade in step with the camera state. Polling rather than
     * observing: the camera opens and closes from several threads, and a 2 s
     * refresh that skips unchanged text is far simpler than a callback fan-out.
     */
    private final Runnable statusTask = new Runnable() {
        @Override
        public void run() {
            if (notifier != null) {
                notifier.update(cameraManager, settings);
            }
            statusHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    /** Refresh the notification now, without waiting for the next poll. */
    public void refreshStatusNotification() {
        if (statusHandler != null) {
            statusHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (notifier != null) {
                        notifier.update(cameraManager, settings);
                    }
                }
            });
        }
    }

    /**
     * Push the camera-related settings into the manager. Safe to call at any
     * time: a camera that is currently open is rebuilt in place, and one that is
     * closed simply picks the new values up at its next open.
     */
    public void applyCameraSettings() {
        if (cameraManager == null) {
            return;
        }
        cameraManager.setFacing(settings.cameraFacing());
        cameraManager.setAccessPolicy(settings.cameraEnabled(), settings.frontCameraDisabled());
        cameraManager.setPreviewSize(settings.resolutionWidth(), settings.resolutionHeight());
        cameraManager.setRotationOffset(settings.rotationOffset());
        // Seed from the cached angle so status is right before the first open,
        // and keep the cache fresh once the camera reports the real value.
        cameraManager.setSensorRotation(settings.sensorRotation());
        cameraManager.setRotationListener(new CameraManager.RotationListener() {
            @Override
            public void onSensorRotation(int degrees) {
                settings.setSensorRotation(degrees);
            }
        });
        // reconfigure() is a no-op unless the camera is open *and* the live
        // configuration actually differs, so this is safe to call at startup.
        cameraManager.reconfigure();
    }

    /**
     * Rebind the HTTP server, for a port change. Runs off the caller's thread
     * because stop() joins on socket teardown, and this is called from the UI.
     */
    public void restartHttpServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (listenerLock) {
                if (destroyed) return;
                HttpServer old = httpServer;
                boolean enabled = (settings.httpEnabled() || settings.mcpEnabled());
                if (old != null && enabled && old.isRunning() && old.getPort() == settings.httpPort()) {
                    old.closeDisabledConnections();
                    return;
                }
                if (old != null) {
                    old.stop();
                }
                // The old listener needs a moment to actually release the port,
                // or the rebind fails with "Address already in use".
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                HttpServer fresh = new HttpServer(settings.httpPort(), cameraManager, settings, deviceStatus);
                if ((settings.httpEnabled() || settings.mcpEnabled())) fresh.start();
                httpServer = fresh;
                Log.i("http server restarted on :" + settings.httpPort());
                refreshStatusNotification();
                }
            }
        }, "http-restart").start();
    }

    /** Reconnect MQTT immediately after broker settings change. */
    public boolean isMqttConnected() { return mqttPublisher != null && mqttPublisher.isConnected(); }
    public boolean isHttpRunning() { return httpServer != null && httpServer.isRunning(); }
    public int httpConnections() { return httpServer == null ? 0 : httpServer.connectionCount(false); }
    public int mcpConnections() { return httpServer == null ? 0 : httpServer.connectionCount(true); }

    public void restartMqtt() {
        if (mqttPublisher != null) {
            mqttPublisher.stop();
            mqttPublisher.start();
        }
    }

    /** Stable per-install identifier for the gateway to key on. */
    private String deviceId() {
        String id = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (settings.manuallyExited()) { stopSelf(); return START_NOT_STICKY; }
        return settings.backgroundEnabled() ? START_STICKY : START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent intent) {
        if (!settings.backgroundEnabled() || settings.manuallyExited()) stopSelf();
        super.onTaskRemoved(intent);
    }

    @Override
    public void onDestroy() {
        Log.i("agent service stopping");
        if (statusHandler != null) {
            statusHandler.removeCallbacks(statusTask);
        }
        if (notifier != null) {
            notifier.cancel();
        }
        if (gatewayClient != null) {
            gatewayClient.stop();
        }
        if (mqttPublisher != null) {
            mqttPublisher.stop();
        }
        synchronized (listenerLock) {
            destroyed = true;
            if (httpServer != null) httpServer.stop();
        }
        if (cameraManager != null) {
            cameraManager.shutdown();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
