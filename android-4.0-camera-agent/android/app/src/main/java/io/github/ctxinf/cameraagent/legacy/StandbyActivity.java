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
    private LinearLayout controls;
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
        controls = (LinearLayout) findViewById(R.id.controls);
        serviceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean enabled) {
                if (binding) return;
                settings.setCameraEnabled(enabled);
                applySettings();
            }
        });
        frontButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectFacing(1); }
        });
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectFacing(0); }
        });
        findViewById(R.id.preview_page).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(StandbyActivity.this, PreviewActivity.class));
            }
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
        serviceToggle.setChecked(settings.cameraEnabled());
        binding = false;
        statusLed.setBackgroundResource(settings.cameraEnabled()
                ? R.drawable.led_green : R.drawable.led_grey);
        boolean front = settings.cameraFacing() == 1;
        frontButton.setSelected(front);
        backButton.setSelected(!front);
        frontButton.setEnabled(CameraManager.hasFacing(1) && !settings.frontCameraDisabled());
        refreshInfo();
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
        infoView.setVisibility(visibility);
        facingRow.setVisibility(visibility);
        controls.setVisibility(visibility);
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
