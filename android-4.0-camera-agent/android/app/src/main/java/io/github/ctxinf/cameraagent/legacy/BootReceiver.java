package io.github.ctxinf.cameraagent.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the agent back up after a reboot so the device self-heals. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Settings.of(context).backgroundEnabled() && !Settings.of(context).manuallyExited()) {
            context.startService(new Intent(context, AgentService.class));
        }
    }
}
