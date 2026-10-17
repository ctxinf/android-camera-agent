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
    private static final long STATUS_POLL_MS = 1000;
    private final Handler handler = new Handler();
    private Settings settings;
    private TextView infoView;
    private TextView statusText;
    private View statusLed;
    private ToggleButton serviceToggle;
    private Button frontButton;
    private Button backButton;
    private LinearLayout facingRow;
    private View settingsButton;
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
        statusText = (TextView) findViewById(R.id.status_text);
        statusLed = findViewById(R.id.status_led);
        serviceToggle = (ToggleButton) findViewById(R.id.camera_service_toggle);
        frontButton = (Button) findViewById(R.id.facing_front);
        backButton = (Button) findViewById(R.id.facing_back);
        facingRow = (LinearLayout) findViewById(R.id.facing_row);
        settingsButton = findViewById(R.id.settings);
        serviceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean enabled) {
                if (binding) return;
                settings.setServiceEnabled(enabled);
                AgentService svc = AgentService.get();
                if (svc != null) { svc.restartHttpServer(); svc.restartMqtt(); }
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
        ((LinearLayout) findViewById(R.id.service_bar)).addView(switches, 1);
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
        startService(new Intent(this, AgentService.class));
        wake();
    }

    private void selectFacing(int facing) {
        if (facing == 1 && settings.frontCameraDisabled()) return;
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
        serviceToggle.setChecked(settings.serviceEnabled());
        String[] keys = { Settings.KEY_HTTP_ENABLED, Settings.KEY_MCP_ENABLED, Settings.KEY_MQTT_ENABLED };
        for (int i = 0; i < keys.length; i++) {
            android.widget.CheckBox box = (android.widget.CheckBox) findViewById(R.id.service_bar).findViewWithTag(keys[i]);
            box.setChecked(settings.raw().getBoolean(keys[i], i == 0));
        }
        binding = false;
        statusLed.setBackgroundResource(settings.serviceEnabled()
                ? R.drawable.led_green : R.drawable.led_grey);
        boolean front = settings.cameraFacing() == 1;
        frontButton.setSelected(front);
        backButton.setSelected(!front);
        frontButton.setEnabled(CameraManager.hasFacing(1) && !settings.frontCameraDisabled());
        statusText.setText(settings.serviceEnabled() ? "整体服务 · 已开启" : "整体服务 · 已关闭");
        statusText.setTextColor(getResources().getColor(settings.serviceEnabled()
                ? R.color.ui_success : R.color.ui_text_secondary));
        AgentService svc = AgentService.get();
        boolean running = settings.serviceEnabled() && svc != null && svc.isHttpRunning();
        setStatusIndicator(R.id.http_status, serviceLine("HTTP", settings.httpEnabled(), running && settings.httpEnabled()));
        setStatusIndicator(R.id.mcp_status, serviceLine("MCP", settings.mcpEnabled(), running && settings.mcpEnabled()));
        ((TextView) findViewById(R.id.http_connections)).setText((svc == null ? 0 : svc.httpConnections()) + " 个连接");
        ((TextView) findViewById(R.id.mcp_connections)).setText((svc == null ? 0 : svc.mcpConnections()) + " 个连接");
        setStatusIndicator(R.id.mqtt_status,
                (svc != null && svc.isMqttConnected() ? "🟢" : settings.mqttEnabled() && settings.serviceEnabled() ? "🟠" : "⚪")
                + " MQTT · " + (!settings.mqttEnabled() ? "已停用" : !settings.serviceEnabled() ? "整体服务已关闭" : svc != null && svc.isMqttConnected() ? "已连接" : "未连接"));
        setStatusIndicator(R.id.camera_status,
                svc != null && svc.getCameraManager().getState() != CameraManager.State.CLOSED ? "🟢 相机使用中" : "⚪ 相机空闲");
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
        return (running ? "🟢" : enabled && settings.serviceEnabled() ? "🟠" : "⚪")
                + " " + name + " · " + (!enabled ? "已停用" : running ? "运行中" : "未运行");
    }

    private void refreshInfo() {
        String ip = NetworkUtil.getLocalIpAddress();
        AgentService service = AgentService.get();
        int port = service == null ? settings.httpPort() : service.getHttpPort();
        if (ip == null) { infoView.setText("no network"); return; }
        String base = "http://" + ip + ":" + port + settings.httpPathPrefix();
        StringBuilder text = new StringBuilder();
        text.append(base).append("\n\n/snapshot.jpg\n")
                .append("/video.mjpeg\n")
                .append("/api/status\n")
                .append("/api/camera");
        if (settings.mcpEnabled()) text.append("\n/mcp  (MCP)");
        infoView.setText(text.toString());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { wake(); return true; }
        return super.onTouchEvent(event);
    }

    private void wake() {
        setDimmed(false);
        handler.removeCallbacks(dimTask);
        handler.postDelayed(dimTask, settings.wakeDurationMs());
    }

    private void setDimmed(boolean dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dim ? settings.dimBrightness() : -1f;
        getWindow().setAttributes(lp);
        int visibility = dim ? View.INVISIBLE : View.VISIBLE;
        findViewById(R.id.service_bar).setVisibility(visibility);
        infoView.setVisibility(visibility);
        facingRow.setVisibility(visibility);
        settingsButton.setVisibility(visibility);
        serviceToggle.setVisibility(visibility);
        statusText.setVisibility(visibility);
        handler.removeCallbacks(statusTask);
        if (!dim) { refresh(); handler.post(statusTask); }
    }

    @Override protected void onResume() { super.onResume(); wake(); }
    @Override protected void onPause() { handler.removeCallbacks(statusTask); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacks(dimTask);
        handler.removeCallbacks(statusTask);
        super.onDestroy();
    }
}
