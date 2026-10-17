package io.github.ctxinf.cameraagent.legacy;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import java.util.HashSet;

/** Process-wide visibility and explicit-exit policy, including Settings and Preview. API 14+. */
public class CameraAgentApp extends Application implements Application.ActivityLifecycleCallbacks {
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
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
