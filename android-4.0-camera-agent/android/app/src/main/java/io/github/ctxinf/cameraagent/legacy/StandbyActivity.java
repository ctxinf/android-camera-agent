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
                    ? "前置摄像头已在设置中禁用" : "设备没有前置摄像头", android.widget.Toast.LENGTH_SHORT).show();
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
        statusText.setText(settings.backgroundEnabled() ? "后台服务 · 开启" : "后台服务 · 关闭");
        statusText.setTextColor(getResources().getColor(settings.backgroundEnabled()
                ? R.color.ui_success : R.color.ui_text_secondary));
        AgentService svc = AgentService.get();
        boolean running = svc != null && svc.isHttpRunning();
        setStatusIndicator(R.id.http_status, serviceLine("HTTP", settings.httpEnabled(), running && settings.httpEnabled()));
        setStatusIndicator(R.id.mcp_status, serviceLine("MCP", settings.mcpEnabled(), running && settings.mcpEnabled()));
        ((TextView) findViewById(R.id.http_connections)).setText((svc == null ? 0 : svc.httpConnections()) + " 个连接");
        ((TextView) findViewById(R.id.mcp_connections)).setText((svc == null ? 0 : svc.mcpConnections()) + " 个连接");
        setStatusIndicator(R.id.mqtt_status,
                (svc != null && svc.isMqttConnected() ? "🟢" : settings.mqttEnabled() ? "🟠" : "⚪")
                + " MQTT · " + (!settings.mqttEnabled() ? "已停用" : svc != null && svc.isMqttConnected() ? "已连接" : "未连接"));
        CameraManager.State state = svc == null ? CameraManager.State.CLOSED : svc.getCameraManager().getState();
        setStatusIndicator(R.id.camera_status, (state == CameraManager.State.OPEN ? "🟢" : state == CameraManager.State.OPENING ? "🟠" : "⚪")
                + " 摄像头 · " + (!settings.cameraEnabled() ? "已禁用" : front ? "前置" : "后置"));
        ((TextView) findViewById(R.id.camera_status)).setTextColor(settings.cameraEnabled() ? getResources().getColor(R.color.ui_text) : 0xffe6a5a5);
        ((TextView) findViewById(R.id.cameraRuntime)).setText(state == CameraManager.State.OPEN ? "Active"
                : state == CameraManager.State.OPENING ? "Opening" : "Standby");
        Button rotation = (Button) findViewById(R.id.rotation);
        rotation.setSelected(settings.rotationOffset() != 0);
        rotation.setText(settings.rotationOffset() + "°");
        rotation.setCompoundDrawablesWithIntrinsicBounds(getResources().getIdentifier(
                "ic_rotation_" + settings.rotationOffset(), "drawable", getPackageName()), 0, 0, 0);
        rotation.setContentDescription("顺时针旋转，当前 " + settings.rotationOffset() + " 度");
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
                + " " + name + " · " + (!enabled ? "已停用" : running ? "运行中" : "未运行");
    }

    private void refreshInfo() {
        String ip = settings.httpBindAddress();
        if ("0.0.0.0".equals(ip)) ip = NetworkUtil.getLocalIpAddress();
        AgentService service = AgentService.get();
        int port = service == null ? settings.httpPort() : service.getHttpPort();
        if (ip == null) { infoView.setText("no network"); return; }
        final String base = "http://" + ip + ":" + port + settings.httpPathPrefix();
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(base + "\n");
        addEndpoint(text, base, "/snapshot.jpg", "JPEG 实时抓图", "GET, HEAD", "image/jpeg", "获取单张实时照片。接入 Home Assistant 时，建议使用“MJPEG IP Camera”集成，而不是“通用摄像头（Generic Camera）”，将此地址填入 Still Image URL。");
        addEndpoint(text, base, "/video.mjpeg", "MJPEG 视频流", "GET, HEAD", "multipart/x-mixed-replace", "连续 JPEG 视频流，支持浏览器和 VLC。接入 Home Assistant 时，建议使用“MJPEG IP Camera”集成，而不是“通用摄像头（Generic Camera）”，将此地址填入 MJPEG URL。");
        addEndpoint(text, base, "/api/status", "设备状态", "GET, HEAD", "application/json", "查询摄像头、分辨率、连接数及设备运行状态。");
        addEndpoint(text, base, "/api/camera", "相机配置", "GET, POST, HEAD", "application/json", "读取或修改镜头、分辨率、FPS、JPEG 质量与保活设置。");
        if (settings.mcpEnabled()) addEndpoint(text, base, "/mcp", "MCP", "POST", "application/json", "通过 JSON-RPC 调用相机工具。HTTP 与 MCP 共用绑定地址及 Basic Auth 设置。");
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
                        .setMessage(url + "\n\n方法：" + method + "\n类型：" + type
                                + "\n认证：" + (settings.authEnabled() ? "需要 Basic Auth" : "免密访问")
                                + "\n\n" + description)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton("复制 URL", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, url));
                                android.widget.Toast.makeText(StandbyActivity.this, "已复制 URL", android.widget.Toast.LENGTH_SHORT).show();
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
