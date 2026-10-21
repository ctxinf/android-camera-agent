package io.github.ctxinf.cameraagent.legacy;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import java.util.HashSet;

/** Process-wide visibility and explicit-exit policy, including Settings and Preview. API 14+. */
public class CameraAgentApp extends Application implements Application.ActivityLifecycleCallbacks {
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LanguageSettings.wrap(base));
    }

    private final Handler handler = new Handler();
    private final HashSet<Activity> activities = new HashSet<Activity>();
    private int started;
    public boolean hasVisibleActivities() { return started > 0; }
    @Override public void onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); }

    private final Runnable backgroundCheck = new Runnable() {
        @Override public void run() {
            if (!hasVisibleActivities() && !Settings.of(CameraAgentApp.this).backgroundEnabled())
                stopService(new Intent(CameraAgentApp.this, AgentService.class));
        }
    };
    public void refreshBackgroundPolicy() {
        if (AgentService.get() != null) startService(new Intent(this, AgentService.class));
        handler.removeCallbacks(backgroundCheck);
        if (!hasVisibleActivities()) handler.post(backgroundCheck);
    }
    public void forceExit() {
        Settings.of(this).setManuallyExited(true);
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, BootReceiver.class),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        handler.removeCallbacks(backgroundCheck);
        stopService(new Intent(this, AgentService.class));
        for (Activity activity : new HashSet<Activity>(activities)) activity.finish();
        handler.postDelayed(new Runnable() {
            @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 600);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { activities.add(activity); }
    @Override public void onActivityStarted(Activity activity) {
        started++;
        handler.removeCallbacks(backgroundCheck);
        if (!(activity instanceof LaunchActivity) && !Settings.of(this).manuallyExited())
            startService(new Intent(this, AgentService.class));
    }
    @Override public void onActivityStopped(Activity activity) {
        started--;
        handler.postDelayed(backgroundCheck, 500);
    }
    @Override public void onActivityDestroyed(Activity activity) { activities.remove(activity); }
    private Activity resumedActivity;
    private volatile Integer brightnessOverride;
    private volatile int currentBrightness = -1;
    public float screenBrightnessOverride(float fallback) {
        Integer value = brightnessOverride;
        return value == null ? fallback : value.intValue() / 100f;
    }
    public int currentScreenBrightness() {
        int value = currentBrightness;
        return value >= 0 ? value : android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255;
    }
    public void setScreenBrightness(final int percent) {
        handler.post(new Runnable() {
            @Override public void run() {
                brightnessOverride = Integer.valueOf(percent);
                for (Activity activity : activities) applyBrightness(activity);
                if (resumedActivity != null) currentBrightness = percent;
            }
        });
    }
    private void applyBrightness(Activity activity) {
        if (brightnessOverride == null) return;
        android.view.WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        params.screenBrightness = screenBrightnessOverride(-1f);
        activity.getWindow().setAttributes(params);
    }
    @Override public void onActivityResumed(Activity activity) {
        resumedActivity = activity;
        applyBrightness(activity);
        float value = activity.getWindow().getAttributes().screenBrightness;
        currentBrightness = value < 0 ? -1 : Math.round(value * 100);
    }
    public void reportWindowBrightness(float value) { currentBrightness = value < 0 ? -1 : Math.round(value * 100); }
    @Override public void onActivityPaused(Activity activity) { if (resumedActivity == activity) { resumedActivity = null; currentBrightness = -1; } }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
