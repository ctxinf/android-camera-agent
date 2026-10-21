package io.github.ctxinf.cameraagent.legacy.mqtt;

import io.github.ctxinf.cameraagent.legacy.Settings;
import io.github.ctxinf.cameraagent.legacy.R;

/** One-shot broker login check used by the settings screen. */
public final class MqttVerifier {
    private MqttVerifier() { }

    public interface Callback {
        void onResult(boolean success, String message);
    }

    public static void verify(final android.content.Context context, final Settings settings, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String host = settings.mqttHost() == null ? "" : settings.mqttHost().trim();
                if (host.length() == 0) {
                    callback.onResult(false, context.getString(R.string.mqtt_host_unset));
                    return;
                }

                MqttConnection connection = new MqttConnection(
                        host, settings.mqttPort(),
                        "camera_verify_" + Long.toHexString(System.currentTimeMillis()),
                        settings.mqttPrefix() + "/verify/availability",
                        settings.mqttUser(), settings.mqttPass());
                try {
                    connection.connect();
                    callback.onResult(true, context.getString(R.string.mqtt_verify_success) + ": " + host + ":" + settings.mqttPort());
                } catch (Exception e) {
                    String detail = e.getMessage();
                    if (detail == null || detail.length() == 0) {
                        detail = e.getClass().getSimpleName();
                    }
                    callback.onResult(false, context.getString(R.string.mqtt_verify_failed) + ": " + detail);
                } finally {
                    connection.close();
                }
            }
        }, "mqtt-verify").start();
    }
}
