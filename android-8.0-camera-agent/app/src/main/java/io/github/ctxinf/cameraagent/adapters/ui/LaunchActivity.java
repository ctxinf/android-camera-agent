package io.github.ctxinf.cameraagent.adapters.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.ctxinf.cameraagent.R;
import io.github.ctxinf.cameraagent.bootstrap.AgentService;
import io.github.ctxinf.cameraagent.bootstrap.CameraAgentApp;

/** A short launcher-only animation: distinguishes a resident service from a cold launch. */
public class LaunchActivity extends Activity {
    private final Handler handler = new Handler();
    private final Runnable open = new Runnable() {
        @Override public void run() {
            startActivity(new Intent(LaunchActivity.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean resident = AgentService.Companion.isRunning();
        ((CameraAgentApp) getApplication()).getAppGraph().getSettingsRepository().setManuallyExited(false);
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this,
                io.github.ctxinf.cameraagent.bootstrap.BootReceiver.class),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(0xff111318);
        int size = (int) (88 * getResources().getDisplayMetrics().density);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        panel.addView(icon, new LinearLayout.LayoutParams(size, size));
        TextView title = new TextView(this);
        title.setText("Camera Agent");
        title.setTextSize(28);
        title.setTextColor(0xffe0e0e0);
        title.setPadding(0, 28, 0, 12);
        panel.addView(title);
        TextView status = new TextView(this);
        status.setText(resident ? "后台服务运行中 · 正在返回" : "重新启动 · 正在初始化");
        status.setTextColor(resident ? 0xffa8c7ba : 0xffbac6ea);
        status.setTextSize(16);
        panel.addView(status);
        setContentView(panel);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start();
    }
    @Override public void onResume() { super.onResume(); handler.postDelayed(open, 900); }
    @Override public void onPause() { handler.removeCallbacks(open); super.onPause(); }
}
