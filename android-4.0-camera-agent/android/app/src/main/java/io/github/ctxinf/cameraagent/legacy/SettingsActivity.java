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
    private String appliedLanguage;

    @Override protected void attachBaseContext(android.content.Context base) {
        appliedLanguage = LanguageSettings.selected(base);
        super.attachBaseContext(LanguageSettings.wrap(base));
    }

    private void refreshLanguage() {
        if (!LanguageSettings.selected(this).equals(appliedLanguage)) {
            appliedLanguage = LanguageSettings.selected(this);
            recreate();
        }
    }


    private final Handler handler = new Handler();
    private Settings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.widget.Button exit = new android.widget.Button(this);
        exit.setText(getString(R.string.ui_force_quit));
        exit.setTextColor(android.graphics.Color.WHITE);
        exit.setBackgroundColor(0xffb3261e);
        exit.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_lock_power_off, 0, 0, 0);
        exit.setPadding(24, 16, 24, 16);
        exit.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View view) {
                new AlertDialog.Builder(SettingsActivity.this).setTitle(getString(R.string.ui_force_quit))
                        .setMessage(getString(R.string.ui_stops_all_connections_and_cameras_exits_the_process_and_disables_))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(getString(R.string.ui_exit), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                ((CameraAgentApp) getApplication()).forceExit();
                            }
                        }).show();
            }
        });
        // Footer must be registered before the preference adapter on older Android releases.
        getListView().addFooterView(exit, null, false);

        android.view.View about = getLayoutInflater().inflate(R.layout.settings_about, getListView(), false);
        ((android.widget.TextView) about.findViewById(R.id.aboutVersion)).setText(
                getString(R.string.about_version, appVersion()));
        String project = "https://github.com/ctxinf/android-camera-agent";
        setupAboutLink(about, R.id.aboutGithub, project);
        getListView().addFooterView(about, null, false);

        addPreferencesFromResource(R.xml.preferences);
        ListPreference languagePreference = (ListPreference) findPreference(LanguageSettings.KEY);
        languagePreference.setIcon(new LanguageIcon(this));
        languagePreference.setDialogIcon(new LanguageIcon(this));
        getListView().setBackgroundColor(android.graphics.Color.rgb(17, 19, 24));
        getListView().setPadding(16, 0, 16, 0);
        settings = new Settings(this);
        findPreference(Settings.KEY_HTTP_PATH_PREFIX).setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
                    @Override public boolean onPreferenceChange(Preference preference, Object value) {
                        try {
                            String normalized = io.github.ctxinf.cameraagent.legacy.util.HttpPathPrefix.normalize(value.toString());
                            ((EditTextPreference) preference).setText(normalized);
                            preference.setSummary(normalized.length() == 0 ? getString(R.string.http_path_prefix_empty) : normalized);
                            return false;
                        } catch (IllegalArgumentException e) {
                            android.widget.Toast.makeText(SettingsActivity.this, e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                            return false;
                        }
                    }
                });

        // Only offer lenses the device actually has, so picking "front" on a
        // single-camera phone is not silently ignored.
        ListPreference facing = (ListPreference) findPreference(Settings.KEY_CAMERA_FACING);
        if (facing != null && !CameraManager.hasFacing(1)) {
            facing.setEnabled(false);
            facing.setSummary(getString(R.string.pref_facing_single));
        }

        if (facing != null) facing.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference preference, Object value) {
                if ("1".equals(value.toString()) && settings.frontCameraDisabled()) {
                    android.widget.Toast.makeText(SettingsActivity.this, getString(R.string.ui_the_front_camera_is_disabled_in_settings), android.widget.Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
        findPreference(Settings.KEY_FRONT_CAMERA_DISABLED).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference preference, Object value) {
                if (Boolean.TRUE.equals(value)) {
                    if (settings.cameraFacing() == 1) settings.setCameraFacing(0);
                    android.widget.Toast.makeText(SettingsActivity.this, getString(R.string.ui_front_camera_disabled), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        findPreference("preview").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference p) {
                startActivity(new android.content.Intent(SettingsActivity.this, PreviewActivity.class));
                return true;
            }
        });
        setupHttpDialogs();
        populateResolutions();
        setupMqttVerifier();
        refreshAllSummaries();
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setupAboutLink(android.view.View footer, int id, final String url) {
        footer.findViewById(id).setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View view) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (android.content.ActivityNotFoundException e) {
                    android.widget.Toast.makeText(SettingsActivity.this, R.string.about_no_browser,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private android.widget.EditText field(android.widget.LinearLayout layout, String hint, String value, int type) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setInputType(type);
        input.setText(value);
        layout.addView(input);
        return input;
    }

    private android.widget.LinearLayout dialogLayout() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        return layout;
    }

    private void refreshHttpSummaries() {
        findPreference(Settings.KEY_HTTP_BIND_ADDRESS).setSummary(settings.httpBindAddress());
        findPreference("http_auth").setSummary(settings.authEnabled() ? getString(R.string.ui_enabled) + settings.authUser() : getString(R.string.ui_not_enabled));
    }

    private void setupHttpDialogs() {
        refreshHttpSummaries();
        findPreference(Settings.KEY_HTTP_BIND_ADDRESS).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference preference) {
                android.widget.LinearLayout layout = dialogLayout();
                final android.widget.EditText input = field(layout, getString(R.string.ui_bind_ipv4_address), settings.httpBindAddress(), android.text.InputType.TYPE_CLASS_TEXT);
                android.widget.Button choose = new android.widget.Button(SettingsActivity.this);
                choose.setText(getString(R.string.ui_select_local_ip));
                layout.addView(choose);
                choose.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        final java.util.ArrayList<String> addresses = new java.util.ArrayList<String>();
                        addresses.add("0.0.0.0");
                        addresses.add("127.0.0.1");
                        try {
                            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                            while (interfaces != null && interfaces.hasMoreElements()) {
                                java.util.Enumeration<java.net.InetAddress> values = interfaces.nextElement().getInetAddresses();
                                while (values.hasMoreElements()) {
                                    java.net.InetAddress address = values.nextElement();
                                    if (address instanceof java.net.Inet4Address && !addresses.contains(address.getHostAddress())) addresses.add(address.getHostAddress());
                                }
                            }
                        } catch (java.net.SocketException e) { Log.w("local address lookup failed: " + e); }
                        new AlertDialog.Builder(SettingsActivity.this).setTitle(getString(R.string.ui_select_local_ip_2))
                                .setItems(addresses.toArray(new String[addresses.size()]), new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) { input.setText(addresses.get(which)); }
                                }).show();
                    }
                });
                final AlertDialog dialog = new AlertDialog.Builder(SettingsActivity.this).setTitle(getString(R.string.ui_http_binding_ip))
                        .setMessage(getString(R.string.ui_0_0_0_0_listens_on_all_interfaces_http_and_mcp_share_this_address))
                        .setView(layout).setNegativeButton(android.R.string.cancel, null).setPositiveButton(getString(R.string.ui_save), null).create();
                dialog.show();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        String value = input.getText().toString().trim();
                        String[] parts = value.split("\\.", -1);
                        boolean valid = parts.length == 4;
                        for (String part : parts) {
                            try { if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) valid = false; }
                            catch (NumberFormatException e) { valid = false; }
                        }
                        if (!valid) { input.setError(getString(R.string.ui_please_enter_a_valid_ipv4_address)); return; }
                        value = Integer.parseInt(parts[0]) + "." + Integer.parseInt(parts[1]) + "." + Integer.parseInt(parts[2]) + "." + Integer.parseInt(parts[3]);
                        settings.raw().edit().putString(Settings.KEY_HTTP_BIND_ADDRESS, value).commit();
                        refreshHttpSummaries();
                        dialog.dismiss();
                    }
                });
                return true;
            }
        });
        findPreference("http_auth").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference preference) {
                android.widget.LinearLayout layout = dialogLayout();
                final android.widget.CheckBox enabled = new android.widget.CheckBox(SettingsActivity.this);
                enabled.setText(getString(R.string.ui_enable_basic_auth));
                enabled.setChecked(settings.authEnabled());
                layout.addView(enabled);
                final android.widget.EditText user = field(layout, getString(R.string.pref_auth_user), settings.authUser(), android.text.InputType.TYPE_CLASS_TEXT);
                final android.widget.EditText pass = field(layout, getString(R.string.pref_auth_pass), settings.authPass(), android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                final AlertDialog dialog = new AlertDialog.Builder(SettingsActivity.this).setTitle("HTTP Basic Auth")
                        .setView(layout).setNegativeButton(android.R.string.cancel, null).setPositiveButton(getString(R.string.ui_save), null).create();
                dialog.show();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        String name = user.getText().toString();
                        if (enabled.isChecked() && (name.trim().length() == 0 || name.indexOf(':') >= 0)) {
                            user.setError(getString(R.string.ui_please_enter_a_non_empty_username_without_colons)); return;
                        }
                        settings.raw().edit().putBoolean(Settings.KEY_AUTH_ENABLED, enabled.isChecked())
                                .putString(Settings.KEY_AUTH_USER, name).putString(Settings.KEY_AUTH_PASS, pass.getText().toString()).commit();
                        refreshHttpSummaries();
                        dialog.dismiss();
                    }
                });
                return true;
            }
        });
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
                MqttVerifier.verify(getApplicationContext(), settings, new MqttVerifier.Callback() {
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
        android.preference.ListPreference language = (android.preference.ListPreference) findPreference(LanguageSettings.KEY);
        language.setSummary(language.getEntry());
        refreshLanguage();
        String[] toggles = { Settings.KEY_HTTP_ENABLED, Settings.KEY_MCP_ENABLED, Settings.KEY_MQTT_ENABLED };
        for (String key : toggles) {
            android.preference.CheckBoxPreference toggle = (android.preference.CheckBoxPreference) findPreference(key);
            if (toggle != null) toggle.setChecked(settings.raw().getBoolean(key, Settings.KEY_HTTP_ENABLED.equals(key)));
        }

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
        if (LanguageSettings.KEY.equals(key)) {
            // Persisted by ListPreference before this listener runs.
            getApplication().getResources().updateConfiguration(
                    LanguageSettings.wrap(this).getResources().getConfiguration(),
                    getResources().getDisplayMetrics());
            recreate();
            return;
        }
        refreshHttpSummaries();
        Preference p = findPreference(key);
        if (p != null) {
            updateSummary(p);
        }

        AgentService svc = AgentService.get();
        if (svc == null) {
            return;
        }

        if (Settings.KEY_HTTP_BIND_ADDRESS.equals(key) || Settings.KEY_HTTP_PORT.equals(key) || Settings.KEY_HTTP_ENABLED.equals(key) || Settings.KEY_MCP_ENABLED.equals(key)) {
            // The listening socket is bound at startup, so a new port needs the
            // server rebuilt. Done here rather than silently deferring to the
            // next boot, which would leave the UI lying about the endpoint.
            svc.restartHttpServer();
        } else if (Settings.KEY_CAMERA_ENABLED.equals(key)
                || Settings.KEY_CAMERA_FACING.equals(key)
                || Settings.KEY_RESOLUTION.equals(key)
                || Settings.KEY_FRONT_CAMERA_DISABLED.equals(key)) {
            svc.applyCameraSettings();
            if (Settings.KEY_CAMERA_FACING.equals(key)) {
                // The lens changed, so any list built for the old one is wrong.
                populateResolutions();
            }
        } else if (Settings.KEY_HTTP_PATH_PREFIX.equals(key)
                || Settings.KEY_MQTT_ENABLED.equals(key)
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
                Settings.KEY_HTTP_PORT, Settings.KEY_HTTP_PATH_PREFIX, Settings.KEY_AUTH_ENABLED,
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
        if (Settings.KEY_HTTP_PATH_PREFIX.equals(key)) {
            String prefix = settings.httpPathPrefix();
            p.setSummary(prefix.length() == 0 ? getString(R.string.http_path_prefix_empty) : prefix);
            return;
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
