package io.github.ctxinf.cameraagent.legacy;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.app.AlertDialog;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.mqtt.MqttVerifier;
import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.util.List;

/**
 * Settings UI. Deliberately the old PreferenceActivity API rather than
 * PreferenceFragment: this must run on API 14, and the app carries no AndroidX.
 *
 * <p>Changes apply as they are made -- the camera is reconfigured in place and
 * the standby screen re-reads its brightness on resume. Only the HTTP port needs
 * the service restarted, which this does itself.
 */
@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final Handler handler = new Handler();
    private Settings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        getListView().setBackgroundColor(android.graphics.Color.rgb(17, 19, 24));
        getListView().setPadding(16, 0, 16, 0);
        settings = new Settings(this);

        // Only offer lenses the device actually has, so picking "front" on a
        // single-camera phone is not silently ignored.
        ListPreference facing = (ListPreference) findPreference(Settings.KEY_CAMERA_FACING);
        if (facing != null && !CameraManager.hasFacing(1)) {
            facing.setEnabled(false);
            facing.setSummary(getString(R.string.pref_facing_single));
        }

        populateResolutions();
        setupMqttVerifier();
        refreshAllSummaries();
    }

    private void setupMqttVerifier() {
        final Preference verify = findPreference("mqtt_verify");
        if (verify == null) {
            return;
        }
        verify.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                verify.setEnabled(false);
                verify.setSummary(getString(R.string.pref_mqtt_verify_running));
                MqttVerifier.verify(settings, new MqttVerifier.Callback() {
                    @Override
                    public void onResult(final boolean success, final String message) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }
                                verify.setEnabled(true);
                                verify.setSummary(message);
                                new AlertDialog.Builder(SettingsActivity.this)
                                        .setTitle(success ? R.string.mqtt_verify_success
                                                : R.string.mqtt_verify_failed)
                                        .setMessage(message)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                            }
                        });
                    }
                });
                return true;
            }
        });
    }

    /**
     * Fill the resolution list from the camera's own supported sizes. Querying
     * can open the camera, so it runs off the UI thread.
     */
    private void populateResolutions() {
        final ListPreference pref = (ListPreference) findPreference(Settings.KEY_RESOLUTION);
        if (pref == null) {
            return;
        }
        // Placeholder until the real list arrives, so the entry is never an
        // empty dialog if the user taps it immediately.
        setResolutionEntries(pref, new String[] { settings.resolution() });

        new Thread(new Runnable() {
            @Override
            public void run() {
                AgentService svc = AgentService.get();
                List<String> sizes = (svc != null && svc.getCameraManager() != null)
                        ? svc.getCameraManager().getSupportedResolutions()
                        : null;
                if (sizes == null || sizes.isEmpty()) {
                    return;
                }
                final String[] arr = sizes.toArray(new String[sizes.size()]);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setResolutionEntries(pref, arr);
                        updateSummary(pref);
                    }
                });
            }
        }, "resolution-probe").start();
    }

    private void setResolutionEntries(ListPreference pref, String[] values) {
        pref.setEntries(values);
        pref.setEntryValues(values);
        if (pref.getValue() == null) {
            pref.setValue(settings.resolution());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Preference p = findPreference(key);
        if (p != null) {
            updateSummary(p);
        }

        AgentService svc = AgentService.get();
        if (svc == null) {
            return;
        }

        if (Settings.KEY_HTTP_PORT.equals(key)) {
            // The listening socket is bound at startup, so a new port needs the
            // server rebuilt. Done here rather than silently deferring to the
            // next boot, which would leave the UI lying about the endpoint.
            svc.restartHttpServer();
        } else if (Settings.KEY_CAMERA_FACING.equals(key)
                || Settings.KEY_RESOLUTION.equals(key)
                || Settings.KEY_FRONT_CAMERA_DISABLED.equals(key)) {
            svc.applyCameraSettings();
            if (Settings.KEY_CAMERA_FACING.equals(key)) {
                // The lens changed, so any list built for the old one is wrong.
                populateResolutions();
            }
        } else if (Settings.KEY_MQTT_ENABLED.equals(key)
                || Settings.KEY_MQTT_HOST.equals(key)
                || Settings.KEY_MQTT_PORT.equals(key)
                || Settings.KEY_MQTT_USER.equals(key)
                || Settings.KEY_MQTT_PASS.equals(key)
                || Settings.KEY_MQTT_PREFIX.equals(key)) {
            svc.restartMqtt();
        }
        Log.i("setting changed: " + key);
    }

    private void refreshAllSummaries() {
        String[] keys = {
                Settings.KEY_DIM_BRIGHTNESS, Settings.KEY_WAKE_SECONDS,
                Settings.KEY_CAMERA_FACING, Settings.KEY_RESOLUTION,
                Settings.KEY_JPEG_QUALITY, Settings.KEY_MJPEG_FPS,
                Settings.KEY_HTTP_PORT, Settings.KEY_AUTH_ENABLED,
                Settings.KEY_AUTH_USER, Settings.KEY_MCP_ENABLED,
                Settings.KEY_MQTT_ENABLED, Settings.KEY_MQTT_HOST,
                Settings.KEY_MQTT_PORT, Settings.KEY_MQTT_USER,
                Settings.KEY_MQTT_PASS, Settings.KEY_MQTT_PREFIX,
        };
        for (int i = 0; i < keys.length; i++) {
            Preference p = findPreference(keys[i]);
            if (p != null) {
                updateSummary(p);
            }
        }
    }

    /** Show the current value in the summary; the stock widgets do not. */
    private void updateSummary(Preference p) {
        String key = p.getKey();
        if (p instanceof ListPreference) {
            ListPreference lp = (ListPreference) p;
            CharSequence label = lp.getEntry();
            p.setSummary(label == null ? lp.getValue() : label);
            return;
        }
        if (p instanceof CheckBoxPreference) {
            return;   // the checkbox itself already shows the state
        }
        if (p instanceof EditTextPreference) {
            String v = ((EditTextPreference) p).getText();
            if (v == null || v.length() == 0) {
                p.setSummary(getString(R.string.pref_unset));
                return;
            }
            if (Settings.KEY_DIM_BRIGHTNESS.equals(key)) {
                p.setSummary(v + "%");
            } else if (Settings.KEY_WAKE_SECONDS.equals(key)) {
                p.setSummary(v + "s");
            } else if (Settings.KEY_MJPEG_FPS.equals(key)) {
                p.setSummary(v + " fps");
            } else if (Settings.KEY_MQTT_PASS.equals(key) && v.length() > 0) {
                p.setSummary("••••••");
            } else {
                p.setSummary(v);
            }
        }
    }
}
