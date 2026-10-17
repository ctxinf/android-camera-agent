package io.github.ctxinf.cameraagent.legacy;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;
import io.github.ctxinf.cameraagent.legacy.util.Log;
import io.github.ctxinf.cameraagent.legacy.util.NetworkUtil;

/**
 * Ongoing status notification: the endpoint address and whether the camera is
 * currently open, visible without unlocking or returning to the app.
 *
 * <p>It doubles as the foreground-service notification. Running the agent in the
 * foreground is what keeps a 1 GB 4.x device from reclaiming it under memory
 * pressure, and the platform requires a visible notification in exchange.
 */
public class StatusNotifier {

    private static final int NOTIFICATION_ID = 1;

    /** Channel id is only read on API 26+; harmless to define unconditionally. */
    private static final String CHANNEL_ID = "camera_agent_status";

    private final Service service;
    private final android.app.NotificationManager manager;

    /** Last rendered text, so an unchanged poll does not re-post the notification. */
    private String lastText;
    private boolean lastOpen;
    private boolean started;

    public StatusNotifier(Service service) {
        this.service = service;
        this.manager = (android.app.NotificationManager)
                service.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        // API 26+ refuses to post without a channel. Reflection-free but
        // version-guarded, since the class does not exist on 4.x.
        if (android.os.Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.notif_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            manager.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w("notification channel failed: " + t);
        }
    }

    /** Post or refresh the notification to match the current camera state. */
    public void update(CameraManager cm, Settings settings) {
        boolean open = cm != null && cm.getState() == CameraManager.State.OPEN;

        String ip = NetworkUtil.getLocalIpAddress();
        int port = settings.httpPort();
        AgentService svc = AgentService.get();
        if (svc != null) {
            port = svc.getHttpPort();
        }

        String text = (ip == null)
                ? service.getString(R.string.notif_no_network)
                : (ip + ":" + port + (settings.authEnabled() ? "  ·  auth" : ""));

        String title = service.getString(open
                ? R.string.notif_title_open
                : R.string.notif_title_idle);
        if (open && cm != null) {
            String res = cm.getActiveResolution();
            int clients = cm.getLeaseCount();
            title = title + "  " + (res == null ? "" : res) + "  x" + clients;
        }

        String composed = title + "\n" + text;
        if (started && composed.equals(lastText) && open == lastOpen) {
            return;   // nothing changed; avoid the redraw and the wakeup
        }
        lastText = composed;
        lastOpen = open;

        Notification n = build(title, text, open);
        if (n == null) {
            return;
        }
        if (!started) {
            // startForeground both posts the notification and pins the service.
            service.startForeground(NOTIFICATION_ID, n);
            started = true;
        } else {
            manager.notify(NOTIFICATION_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification build(String title, String text, boolean open) {
        Intent open_ = new Intent(service, StandbyActivity.class);
        open_.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= 0x02000000;   // FLAG_IMMUTABLE, required on API 31+
        }
        PendingIntent pi = PendingIntent.getActivity(service, 0, open_, flags);

        try {
            Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(service, CHANNEL_ID)
                    : new Notification.Builder(service);
            b.setContentTitle(title);
            b.setContentText(text);
            b.setSmallIcon(open ? R.drawable.ic_stat_open : R.drawable.ic_stat_idle);
            b.setContentIntent(pi);
            b.setOngoing(true);
            b.setWhen(0);
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                b.setPriority(Notification.PRIORITY_MIN);   // no sound, bottom of the shade
            }
            return (android.os.Build.VERSION.SDK_INT >= 16)
                    ? b.build()
                    : b.getNotification();
        } catch (Throwable t) {
            Log.e("notification build failed: " + t);
            return null;
        }
    }

    public void cancel() {
        try {
            service.stopForeground(true);
        } catch (Throwable ignored) { }
        try {
            manager.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) { }
        started = false;
    }
}
