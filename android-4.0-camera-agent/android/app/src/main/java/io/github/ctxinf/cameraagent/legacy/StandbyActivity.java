package io.github.ctxinf.cameraagent.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.util.NetworkUtil;

/** Home page: service gate, endpoints and lens selection. */
public class StandbyActivity extends Activity {
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

    private static final long STATUS_POLL_MS = 1000;
    private final Handler handler = new Handler();
    private Settings settings;
    private TextView infoView;
    private TextView statusText;
    private View statusLed;
    private ToggleButton serviceToggle;
    private Button frontButton;
    private Button backButton;
    private Button disabledButton;
    private boolean binding;

    private final Runnable dimTask = new Runnable() {
        @Override public void run() { setDimmed(true); }
    };
    private final Runnable statusTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, STATUS_POLL_MS); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_standby);
        settings = new Settings(this);
        infoView = (TextView) findViewById(R.id.info);
        infoView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        statusText = (TextView) findViewById(R.id.status_text);
        statusLed = findViewById(R.id.status_led);
        serviceToggle = (ToggleButton) findViewById(R.id.camera_service_toggle);
        frontButton = (Button) findViewById(R.id.facing_front);
        backButton = (Button) findViewById(R.id.facing_back);
        disabledButton = (Button) findViewById(R.id.facing_disabled);
        serviceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean enabled) {
                if (binding) return;
                settings.setBackgroundEnabled(enabled);
                ((CameraAgentApp) getApplication()).refreshBackgroundPolicy();
                applySettings();
            }
        });
        LinearLayout switches = new LinearLayout(this);
        String[] protocols = { "HTTP", "MCP", "MQTT" };
        final String[] keys = { Settings.KEY_HTTP_ENABLED, Settings.KEY_MCP_ENABLED, Settings.KEY_MQTT_ENABLED };
        for (int i = 0; i < protocols.length; i++) {
            final String key = keys[i];
            android.widget.CheckBox box = new android.widget.CheckBox(this);
            box.setText(protocols[i]);
            box.setTag(key);
            box.setChecked(settings.raw().getBoolean(key, i == 0));
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean enabled) {
                    if (binding) return;
                    settings.raw().edit().putBoolean(key, enabled).commit();
                    AgentService svc = AgentService.get();
                    if (svc != null) {
                        if (Settings.KEY_MQTT_ENABLED.equals(key)) svc.restartMqtt();
                        else svc.restartHttpServer();
                    }
                    refresh();
                }
            });
            switches.addView(box, new LinearLayout.LayoutParams(0, (int) (48 * getResources().getDisplayMetrics().density), 1));
        }
        ((LinearLayout) findViewById(R.id.service_bar)).addView(switches, 0);
        disabledButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settings.setCameraEnabled(false);
                applySettings();
            }
        });
        frontButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectFacing(1); }
        });
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectFacing(0); }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(StandbyActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.rotation).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settings.setRotationOffset((settings.rotationOffset() + 90) % 360);
                applySettings();
            }
        });
        startService(new Intent(this, AgentService.class));
    }

    private void selectFacing(int facing) {
        if (facing == 1 && (!CameraManager.hasFacing(1) || settings.frontCameraDisabled())) {
            android.widget.Toast.makeText(this, settings.frontCameraDisabled()
                    ? getString(R.string.ui_the_front_camera_is_disabled_in_settings) : getString(R.string.ui_device_has_no_front_camera), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        settings.setCameraEnabled(true);
        settings.setCameraFacing(facing);
        applySettings();
    }

    private void applySettings() {
        AgentService service = AgentService.get();
        if (service != null) service.applyCameraSettings();
        refresh();
    }

    private void refresh() {
        binding = true;
        serviceToggle.setChecked(settings.backgroundEnabled());
        String[] keys = { Settings.KEY_HTTP_ENABLED, Settings.KEY_MCP_ENABLED, Settings.KEY_MQTT_ENABLED };
        for (int i = 0; i < keys.length; i++) {
            android.widget.CheckBox box = (android.widget.CheckBox) findViewById(R.id.service_bar).findViewWithTag(keys[i]);
            box.setChecked(settings.raw().getBoolean(keys[i], i == 0));
        }
        binding = false;
        statusLed.setBackgroundResource(settings.backgroundEnabled()
                ? R.drawable.led_green : R.drawable.led_grey);
        boolean front = settings.cameraFacing() == 1;
        frontButton.setSelected(settings.cameraEnabled() && front);
        backButton.setSelected(settings.cameraEnabled() && !front);
        disabledButton.setSelected(!settings.cameraEnabled());
        disabledButton.setCompoundDrawablesWithIntrinsicBounds(
                new BanDrawable(getResources().getDisplayMetrics().density,
                        settings.cameraEnabled() ? 0xffe0e0e0 : 0xff111318), null, null, null);
        frontButton.setAlpha(CameraManager.hasFacing(1) && !settings.frontCameraDisabled() ? 1f : 0.4f);
        statusText.setText(settings.backgroundEnabled() ? getString(R.string.ui_background_service_enable) : getString(R.string.ui_background_service_close));
        statusText.setTextColor(getResources().getColor(settings.backgroundEnabled()
                ? R.color.ui_success : R.color.ui_text_secondary));
        AgentService svc = AgentService.get();
        boolean running = svc != null && svc.isHttpRunning();
        setStatusIndicator(R.id.http_status, serviceLine("HTTP", settings.httpEnabled(), running && settings.httpEnabled()));
        setStatusIndicator(R.id.mcp_status, serviceLine("MCP", settings.mcpEnabled(), running && settings.mcpEnabled()));
        ((TextView) findViewById(R.id.http_connections)).setText((svc == null ? 0 : svc.httpConnections()) + getString(R.string.ui_connections));
        ((TextView) findViewById(R.id.mcp_connections)).setText((svc == null ? 0 : svc.mcpConnections()) + getString(R.string.ui_connections));
        setStatusIndicator(R.id.mqtt_status,
                (svc != null && svc.isMqttConnected() ? "🟢" : settings.mqttEnabled() ? "🟠" : "⚪")
                + " MQTT · " + (!settings.mqttEnabled() ? getString(R.string.ui_deactivated) : svc != null && svc.isMqttConnected() ? getString(R.string.ui_connected) : getString(R.string.ui_not_connected)));
        CameraManager.State state = svc == null ? CameraManager.State.CLOSED : svc.getCameraManager().getState();
        setStatusIndicator(R.id.camera_status, (state == CameraManager.State.OPEN ? "🟢" : state == CameraManager.State.OPENING ? "🟠" : "⚪")
                + getString(R.string.ui_camera_2) + (!settings.cameraEnabled() ? getString(R.string.ui_disabled) : front ? getString(R.string.ui_prefix) : getString(R.string.ui_rear)));
        ((TextView) findViewById(R.id.camera_status)).setTextColor(settings.cameraEnabled() ? getResources().getColor(R.color.ui_text) : 0xffe6a5a5);
        ((TextView) findViewById(R.id.cameraRuntime)).setText(state == CameraManager.State.OPEN ? "Active"
                : state == CameraManager.State.OPENING ? "Opening" : "Standby");
        Button rotation = (Button) findViewById(R.id.rotation);
        rotation.setSelected(settings.rotationOffset() != 0);
        rotation.setText(settings.rotationOffset() + "°");
        rotation.setCompoundDrawablesWithIntrinsicBounds(getResources().getIdentifier(
                "ic_rotation_" + settings.rotationOffset(), "drawable", getPackageName()), 0, 0, 0);
        rotation.setContentDescription(getString(R.string.ui_rotate_clockwise_current) + settings.rotationOffset() + getString(R.string.ui_degree));
        refreshInfo();
    }

    // Shape drawables are independent of the device's emoji font support.
    private void setStatusIndicator(int viewId, String line) {
        TextView view = (TextView) findViewById(viewId);
        int resource = line.startsWith("🟢") ? R.drawable.led_green
                : line.startsWith("🟠") ? R.drawable.led_amber : R.drawable.led_grey;
        android.graphics.drawable.Drawable light = getResources().getDrawable(resource).mutate();
        int size = (int) (10 * getResources().getDisplayMetrics().density);
        light.setBounds(0, 0, size, size);
        view.setCompoundDrawables(light, null, null, null);
        view.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
        view.setText(line.substring(line.indexOf(' ') + 1));
    }

    private String serviceLine(String name, boolean enabled, boolean running) {
        return (running ? "🟢" : enabled ? "🟠" : "⚪")
                + " " + name + " · " + (!enabled ? getString(R.string.ui_deactivated) : running ? getString(R.string.ui_running) : getString(R.string.ui_not_running));
    }

    private void refreshInfo() {
        String ip = settings.httpBindAddress();
        if ("0.0.0.0".equals(ip)) ip = NetworkUtil.getLocalIpAddress();
        AgentService service = AgentService.get();
        int port = service == null ? settings.httpPort() : service.getHttpPort();
        if (ip == null) { infoView.setText(R.string.notif_no_network); return; }
        final String base = "http://" + ip + ":" + port + settings.httpPathPrefix();
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(base + "\n");
        addEndpoint(text, base, "/snapshot.jpg", getString(R.string.ui_jpeg_real_time_capture), "GET, HEAD", "image/jpeg", getString(R.string.ui_get_a_single_live_photo_when_connecting_to_home_assistant_it_is_r));
        addEndpoint(text, base, "/video.mjpeg", getString(R.string.ui_mjpeg_video_stream), "GET, HEAD", "multipart/x-mixed-replace", getString(R.string.ui_continuous_jpeg_video_streaming_browser_and_vlc_supported_when_co));
        addEndpoint(text, base, "/api/status", getString(R.string.ui_device_status), "GET, HEAD", "application/json", getString(R.string.ui_query_the_camera_resolution_number_of_connections_and_device_runn));
        addEndpoint(text, base, "/api/camera", getString(R.string.ui_camera_configuration), "GET, POST, HEAD", "application/json", getString(R.string.ui_read_or_modify_lens_resolution_fps_jpeg_quality_and_keepalive_set));
        if (settings.mcpEnabled()) addEndpoint(text, base, "/mcp", "MCP", "POST", "application/json", getString(R.string.ui_call_the_camera_tool_via_json_rpc_http_and_mcp_share_the_bind_add));
        // Avoid replacing spans during polling while the user is pressing a link.
        if (!text.toString().equals(infoView.getText().toString())) infoView.setText(text);
    }

    private void addEndpoint(android.text.SpannableStringBuilder text, final String base,
            final String path, final String title, final String method, final String type, final String description) {
        text.append("\n");
        int start = text.length();
        text.append(path);
        text.setSpan(new android.text.style.ClickableSpan() {
            @Override public void onClick(View view) {
                final String url = base + path;
                new android.app.AlertDialog.Builder(StandbyActivity.this).setTitle(title)
                        .setMessage(url + getString(R.string.ui_n_n_method) + method + getString(R.string.ui_n_type) + type
                                + getString(R.string.ui_n_certification) + (settings.authEnabled() ? getString(R.string.ui_basic_auth_required) : getString(R.string.ui_password_free_access))
                                + "\n\n" + description)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(getString(R.string.ui_copy_url), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, url));
                                android.widget.Toast.makeText(StandbyActivity.this, getString(R.string.ui_url_copied), android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }).show();
            }
        }, start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Observe touches before ScrollView, buttons or selectable text consume them.
        if (event.getAction() == MotionEvent.ACTION_DOWN) wake();
        return super.dispatchTouchEvent(event);
    }

    private void wake() {
        setDimmed(false);
        handler.removeCallbacks(dimTask);
        handler.postDelayed(dimTask, settings.wakeDurationMs());
    }

    private void setDimmed(boolean dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = ((CameraAgentApp) getApplication()).screenBrightnessOverride(dim ? settings.dimBrightness() : -1f);
        ((CameraAgentApp) getApplication()).reportWindowBrightness(lp.screenBrightness);
        getWindow().setAttributes(lp);
        // Idle mode changes brightness only; keep the home page and status polling intact.
    }

    @Override protected void onResume() {
        super.onResume();
        refreshLanguage();
        if (!settings.manuallyExited()) startService(new Intent(this, AgentService.class));
        wake();
        handler.removeCallbacks(statusTask);
        handler.post(statusTask);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(dimTask);
        handler.removeCallbacks(statusTask);
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(dimTask);
        handler.removeCallbacks(statusTask);
        super.onDestroy();
    }
}
