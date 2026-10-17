package io.github.ctxinf.cameraagent.legacy.util;

/** Thin wrapper so the tag is consistent and logging can be centrally muted. */
public final class Log {
    public static final String TAG = "CameraAgent";

    private Log() { }

    public static void i(String msg) { android.util.Log.i(TAG, msg); }
    public static void w(String msg) { android.util.Log.w(TAG, msg); }
    public static void e(String msg) { android.util.Log.e(TAG, msg); }
    public static void d(String msg) { android.util.Log.d(TAG, msg); }
}
