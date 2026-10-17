package io.github.ctxinf.cameraagent.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


/** A short launcher-only animation: distinguishes a resident service from a cold launch. */
public class LaunchActivity extends Activity {
    private static boolean hasLaunched;
    private boolean showAnimation;
    private final Handler handler = new Handler();
    private final Runnable open = new Runnable() {
        @Override public void run() {
            startActivity(new Intent(LaunchActivity.this, StandbyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Settings settings = Settings.of(this);
        showAnimation = settings.manuallyExited() || (!hasLaunched && AgentService.get() == null);
        hasLaunched = true;
        settings.setManuallyExited(false);
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this,
                BootReceiver.class),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP);
        if (!showAnimation) {
            open.run();
            return;
        }
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
        setContentView(panel);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start();
    }
    @Override public void onResume() { super.onResume(); if (showAnimation) handler.postDelayed(open, 900); }
    @Override public void onPause() { handler.removeCallbacks(open); super.onPause(); }
}
