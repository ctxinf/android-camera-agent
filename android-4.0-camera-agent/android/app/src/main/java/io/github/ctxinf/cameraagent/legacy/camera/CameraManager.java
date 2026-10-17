package io.github.ctxinf.cameraagent.legacy.camera;

import android.graphics.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.SurfaceHolder;

import io.github.ctxinf.cameraagent.legacy.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The single owner of {@link Camera}. Protocol adapters never open the camera
 * themselves; they acquire/release a lease here and the manager decides when the
 * hardware is actually opened and closed.
 *
 * <p>State: CLOSED -> OPENING -> OPEN (preview running) -> CLOSED.
 * Closing is deferred by {@link #IDLE_CLOSE_DELAY_MS} so that a burst of
 * snapshots, or an RTSP client reconnecting, does not thrash the hardware.
 *
 * <p>All camera calls are marshalled onto a single dedicated thread, because the
 * old Camera API requires open/preview/release to happen on one thread with a
 * live Looper (it delivers callbacks on the thread that called open()).
 */
public class CameraManager {

    public enum State { CLOSED, OPENING, OPEN }

    /** How long the camera stays warm after the last lease is released. */
    private static final long IDLE_CLOSE_DELAY_MS = 5000;

    private static final int DEFAULT_JPEG_QUALITY = 80;

    /**
     * Frames to discard after the camera opens, so auto-exposure and auto-white-
     * balance can converge. Straight after open() the sensor returns near-black
     * frames (observed luma 3 climbing to 50+ over the first second on QCamera
     * 4.x HALs), which would otherwise be served as a black snapshot.
     */
    private static final int AE_WARMUP_FRAMES = 8;

    /** Preview callback buffers. At 640x480 NV21 each is ~460 KB. */
    private static final int PREVIEW_BUFFER_COUNT = 5;

    /**
     * Minimum gap between releasing the camera and opening it again. The 4.x
     * QCamera HAL keeps the device busy briefly after release(), and opening
     * inside that window yields "startPreview failed".
     */
    private static final long REOPEN_COOLDOWN_MS = 500;

    /** Attempts for a failed open, since the cool-down is not always sufficient. */
    private static final int OPEN_ATTEMPTS = 3;

    /** A cached frame older than this is considered stale for snapshot reuse. */
    private static final long FRESH_FRAME_MAX_AGE_MS = 1000;

    /** Arbitrary non-zero GL texture name for the headless preview target. */
    private static final int DUMMY_TEXTURE_NAME = 1;

    /** Cap on how long {@link #AE_WARMUP_FRAMES} is allowed to delay a capture. */
    private static final long AE_WARMUP_MAX_MS = 1500;

    private final Handler handler;

    /**
     * Real preview surface, supplied by the Activity when it is up. A tiny
     * on-screen SurfaceView is far more reliable on 4.x than a headless
     * SurfaceTexture, which this HAL only tolerates for a couple of cycles
     * before refusing to start preview at all.
     */
    private volatile SurfaceHolder previewHolder;

    /** Guards state, leases and camera. Never held while blocking on the camera thread. */
    private final Object lock = new Object();

    private State state = State.CLOSED;
    private int leases;
    private Camera camera;

    /** Set while a frame is being delivered to preview callbacks. */
    private final List<FrameListener> frameListeners = new ArrayList<FrameListener>();

    private int previewWidth;
    private int previewHeight;

    /** Which camera to open; -1 means "resolve from {@link #facing}". */
    private volatile int cameraId = -1;

    /** Preferred lens when {@link #cameraId} is unset. */
    private volatile int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private volatile boolean cameraEnabled = true;
    private volatile boolean frontCameraDisabled;
    private byte[] deniedJpegCache;
    private String deniedJpegCacheKey;

    /** Requested preview size; the closest supported size is negotiated at open. */
    private volatile int requestedWidth = 640;
    private volatile int requestedHeight = 480;

    /** What the currently open camera was actually built with, for {@link #configChanged()}. */
    private volatile int activeCameraId = -1;
    private volatile SurfaceHolder activeHolder;
    private volatile int activeRotation = -1;

    /**
     * Clockwise degrees to rotate captured frames so they come out upright.
     * Derived from the sensor mounting angle at open; typically 90 on a phone
     * whose sensor is landscape-native while the app runs portrait.
     */
    private volatile int frameRotation;

    /** True when the selected lens is front-facing, which mirrors its frames. */
    private volatile boolean frontFacing;

    /** Sensor-derived angle, before {@link #rotationOffset} is applied. */
    private volatile int sensorRotation;

    /** User correction added to the sensor angle, in degrees clockwise. */
    private volatile int rotationOffset;

    /** Notified when the sensor angle is learned at open, so it can be cached. */
    public interface RotationListener {
        void onSensorRotation(int degrees);
    }

    private volatile RotationListener rotationListener;

    /** Frames delivered since the current open(); used for the AE warm-up. */
    private volatile int framesSinceOpen;

    /** Most recent frame, kept so a snapshot need not wait for the next one. */
    private byte[] lastFrame;
    private int lastFrameWidth;
    private int lastFrameHeight;
    private long lastFrameAt;

    /** elapsedRealtime when the camera finished opening. */
    private volatile long openedAt;

    /** elapsedRealtime of the last release(), for the HAL cool-down below. */
    private volatile long closedAt;

    /**
     * Dummy preview target used in headless mode. The old Camera API will not
     * deliver preview frames without a surface. It is created once and reused:
     * allocating a new SurfaceTexture per open leaks it, and the HAL then refuses
     * to start preview ("startPreview failed") on every subsequent open.
     */
    private android.graphics.SurfaceTexture dummyTexture;

    /** Callback for continuous frames (MJPEG / encoder). */
    public interface FrameListener {
        /** Called on the camera thread with a raw NV21 frame. Must return fast. */
        void onFrame(byte[] nv21, int width, int height);
    }

    public CameraManager(SurfaceHolder previewHolder) {
        this.previewHolder = previewHolder;
        HandlerThread thread = new HandlerThread("camera");
        thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** Override the camera selected by {@link #resolveCameraId()}. */
    public void setCameraId(int id) {
        this.cameraId = id;
    }

    /**
     * Select the lens by facing (Camera.CameraInfo.CAMERA_FACING_*). Takes effect
     * on the next open; call {@link #reconfigure()} to apply it immediately.
     */
    public void setFacing(int facing) {
        this.facing = facing;
        this.cameraId = -1;   // re-resolve from the facing on the next open
    }

    public int getFacing() {
        return facing;
    }

    public void setAccessPolicy(boolean enabled, boolean disableFront) {
        cameraEnabled = enabled;
        frontCameraDisabled = disableFront;
        if (isAccessDenied()) {
            // Closing on the camera thread makes this a real final gate, including
            // clients that already held a lease before the policy changed.
            handler.post(new Runnable() {
                @Override public void run() { doClose(); }
            });
        }
    }

    public boolean isAccessDenied() {
        return !cameraEnabled || (frontCameraDisabled
                && facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    public String accessDeniedMessage() {
        return !cameraEnabled ? "当前禁止访问" : "当前禁止访问前置摄像头";
    }

    /** JPEG used by every still/stream protocol while the secure gate is closed. */
    public synchronized byte[] deniedJpeg(int quality) {
        int w = Math.max(320, requestedWidth);
        int h = Math.max(240, requestedHeight);
        String key = accessDeniedMessage() + ":" + w + "x" + h + ":" + quality;
        if (key.equals(deniedJpegCacheKey) && deniedJpegCache != null) {
            return deniedJpegCache;
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(24, 24, 24));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(22, Math.min(w, h) / 12));
        canvas.drawText(accessDeniedMessage(), w / 2f, h / 2f, paint);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, Math.max(10, Math.min(100, quality)), out);
        bitmap.recycle();
        deniedJpegCache = out.toByteArray();
        deniedJpegCacheKey = key;
        return deniedJpegCache;
    }

    /** Requested preview size. The closest supported size is used. */
    public void setPreviewSize(int width, int height) {
        this.requestedWidth = width;
        this.requestedHeight = height;
    }

    /** Actual negotiated preview size, as "WxH", or null while closed. */
    public String getActiveResolution() {
        synchronized (lock) {
            if (state != State.OPEN) {
                return null;
            }
        }
        // Report what a client receives, which is the rotated frame.
        boolean swap = (frameRotation == 90 || frameRotation == 270);
        return swap ? (previewHeight + "x" + previewWidth)
                    : (previewWidth + "x" + previewHeight);
    }

    /**
     * Apply a changed facing, preview size or preview surface to a camera that is
     * already open. A no-op when closed, since the next open picks the new values
     * up anyway, and a no-op when nothing actually differs from what is live --
     * without that check a caller that reconfigures on every surface callback
     * drives an endless close/open loop, because reopening re-triggers the
     * callback that asked for it.
     *
     * <p>Leases are left untouched: the camera is torn down and rebuilt in place,
     * so an MJPEG client keeps its lease and simply sees frames resume at the new
     * size. Its cached width/height come from each onFrame() call, so it adapts.
     */
    public void reconfigure() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (state != State.OPEN || camera == null) {
                        return;
                    }
                }
                if (!configChanged()) {
                    return;
                }
                doClose();
                if (!doOpen()) {
                    Log.e("reconfigure: reopen failed");
                }
            }
        });
    }

    /** True if the live camera no longer matches the requested configuration. */
    private boolean configChanged() {
        if (activeCameraId != resolveCameraId()) {
            return true;
        }
        // setDisplayOrientation() is rejected while preview is running, so a
        // changed rotation can only reach the preview surface via a reopen.
        if (activeRotation != frameRotation) {
            return true;
        }
        if (activeHolder != previewHolder) {
            return true;
        }
        // The negotiated size, not the requested one: asking for 1280x720 on a
        // camera that only offers 640x480 must not look like a pending change.
        Camera.Size want = null;
        synchronized (lock) {
            if (camera != null) {
                try {
                    want = pickPreviewSize(camera.getParameters(), requestedWidth, requestedHeight);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        return want != null && (want.width != previewWidth || want.height != previewHeight);
    }

    /** Facings actually present on this device, for the settings UI. */
    public static boolean hasFacing(int facing) {
        int n = Camera.getNumberOfCameras();
        for (int i = 0; i < n; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            try {
                Camera.getCameraInfo(i, info);
                if (info.facing == facing) {
                    return true;
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    /**
     * Preview sizes the selected camera supports, as "WxH", largest first.
     * Opens the camera briefly if it is closed, so this must not be called on the
     * UI thread.
     */
    public List<String> getSupportedResolutions() {
        List<String> out = new ArrayList<String>();
        Camera probe = null;
        try {
            List<Camera.Size> sizes;
            synchronized (lock) {
                if (state == State.OPEN && camera != null) {
                    sizes = camera.getParameters().getSupportedPreviewSizes();
                    // Copy out under the lock; the list belongs to the instance.
                    sizes = sizes == null ? null : new ArrayList<Camera.Size>(sizes);
                } else {
                    sizes = null;
                }
            }
            if (sizes == null) {
                probe = Camera.open(resolveCameraId());
                sizes = probe.getParameters().getSupportedPreviewSizes();
            }
            if (sizes == null) {
                return out;
            }
            List<Camera.Size> sorted = new ArrayList<Camera.Size>(sizes);
            java.util.Collections.sort(sorted, new Comparator<Camera.Size>() {
                @Override
                public int compare(Camera.Size a, Camera.Size b) {
                    long aa = (long) a.width * a.height;
                    long bb = (long) b.width * b.height;
                    return aa < bb ? 1 : (aa > bb ? -1 : 0);
                }
            });
            for (int i = 0; i < sorted.size(); i++) {
                out.add(sorted.get(i).width + "x" + sorted.get(i).height);
            }
        } catch (Exception e) {
            Log.w("supported sizes query failed: " + e);
        } finally {
            if (probe != null) {
                try { probe.release(); } catch (Exception ignored) { }
                closedAt = SystemClock.elapsedRealtime();
            }
        }
        return out;
    }

    /**
     * Pick a concrete camera index. The no-argument Camera.open() resolves to id
     * -1 on some Qualcomm 4.x HALs and is rejected by CameraService, so we always
     * open an explicit index.
     */
    private int resolveCameraId() {
        int explicit = cameraId;
        if (explicit >= 0) {
            return explicit;
        }
        int n = Camera.getNumberOfCameras();
        int want = facing;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                try {
                    Camera.getCameraInfo(i, info);
                    if (info.facing == want) {
                        return i;
                    }
                } catch (Exception e) {
                    Log.w("getCameraInfo(" + i + ") failed: " + e);
                }
            }
            // Requested facing is absent on this device; fall back to the other.
            want = (want == Camera.CameraInfo.CAMERA_FACING_BACK)
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        return 0;
    }

    /**
     * Attach or clear the preview surface. Takes effect on the next open; the
     * camera is closed while idle anyway, so no reopen is forced here.
     */
    public void setPreviewHolder(SurfaceHolder holder) {
        this.previewHolder = holder;
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    /** Frames delivered since the current open(); 0 means delivery has stalled. */
    public int getFramesSinceOpen() {
        return framesSinceOpen;
    }

    public int getListenerCount() {
        synchronized (lock) {
            return frameListeners.size();
        }
    }

    public int getLeaseCount() {
        synchronized (lock) {
            return leases;
        }
    }

    /**
     * Closes the camera if it is still unused when it fires. The lease count is
     * re-checked under the lock at fire time, so an acquire() that lands after the
     * close was scheduled correctly cancels it; doClose() is idempotent, so an
     * extra scheduled close is harmless.
     */
    private final class CloseTask implements Runnable {
        @Override
        public void run() {
            synchronized (lock) {
                if (leases > 0) {
                    return;   // back in use
                }
            }
            doClose();
        }
    }

    /**
     * Take a lease on the camera, opening it if needed. Blocks until the camera
     * is ready (or failed). Every successful call must be paired with
     * {@link #release()}.
     *
     * @return true if the camera is open and previewing.
     */
    public boolean acquire(long timeoutMs) {
        if (isAccessDenied()) {
            return false;
        }
        synchronized (lock) {
            leases++;
            if (state == State.OPEN && camera != null) {
                return true;
            }
        }

        final CountDownLatch ready = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        handler.post(new Runnable() {
            @Override
            public void run() {
                ok[0] = doOpen();
                ready.countDown();
            }
        });

        try {
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w("acquire timed out after " + timeoutMs + "ms");
                abandonLease();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandonLease();
            return false;
        }

        if (!ok[0]) {
            abandonLease();
        }
        return ok[0];
    }

    /**
     * Undo the increment made by a failed {@link #acquire}. This is deliberately
     * not release(): release() is written for a *successful* lease and schedules
     * an idle close. Routing a failed acquire through it would, when another
     * client holds the only live lease, decrement that client's lease instead of
     * ours and tear the camera out from under a running stream.
     */
    private void abandonLease() {
        synchronized (lock) {
            if (leases > 0) {
                leases--;
            }
            if (leases > 0) {
                return;   // someone else is still using the camera; leave it open
            }
        }
        handler.postDelayed(new CloseTask(), IDLE_CLOSE_DELAY_MS);
    }

    /** Release a lease. The camera closes after an idle delay once all leases are gone. */
    public void release() {
        synchronized (lock) {
            if (leases == 0) {
                Log.w("release() with no outstanding lease");
                return;
            }
            leases--;
            if (leases > 0) {
                return;
            }
        }
        handler.postDelayed(new CloseTask(), IDLE_CLOSE_DELAY_MS);
    }

    /** Close the camera immediately, regardless of the idle delay. Used on shutdown. */
    public void shutdown() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                doClose();
            }
        });
    }

    // --- camera-thread work ------------------------------------------------

    /** Runs on the camera thread. */
    private boolean doOpen() {
        if (isAccessDenied()) {
            synchronized (lock) { state = State.CLOSED; }
            return false;
        }
        // Every concurrent acquire() posts its own doOpen(); they run serially on
        // this one thread, so all but the first must be no-ops. Checking only for
        // OPEN here is not enough -- a camera that is already open and previewing
        // must never be torn down by a queued duplicate.
        synchronized (lock) {
            if (camera != null && state == State.OPEN) {
                return true;
            }
            state = State.OPENING;
        }

        // Respect the HAL cool-down before touching the device again.
        long sinceClose = SystemClock.elapsedRealtime() - closedAt;
        if (closedAt != 0 && sinceClose < REOPEN_COOLDOWN_MS) {
            try {
                Thread.sleep(REOPEN_COOLDOWN_MS - sinceClose);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (lock) { state = State.CLOSED; }
                return false;
            }
        }

        for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
            if (tryOpen()) {
                return true;
            }
            if (attempt < OPEN_ATTEMPTS) {
                Log.w("open attempt " + attempt + " failed; retrying");
                try {
                    Thread.sleep(REOPEN_COOLDOWN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        synchronized (lock) {
            state = State.CLOSED;
        }
        return false;
    }

    /** One open attempt. Returns true if the camera is open and previewing. */
    private boolean tryOpen() {
        Camera c = null;
        try {
            int id = resolveCameraId();
            Log.i("opening camera id " + id + " of " + Camera.getNumberOfCameras());
            c = Camera.open(id);
            if (c == null) {
                Log.e("Camera.open(" + id + ") returned null");
                synchronized (lock) { state = State.CLOSED; }
                return false;
            }

            Camera.Parameters p = c.getParameters();
            Camera.Size size = pickPreviewSize(p, requestedWidth, requestedHeight);
            if (size != null) {
                p.setPreviewSize(size.width, size.height);
            }
            p.setPreviewFormat(ImageFormat.NV21);
            try {
                c.setParameters(p);
            } catch (RuntimeException e) {
                // Some 4.x devices reject a valid-looking combination; fall back
                // to whatever the driver defaults to rather than failing outright.
                Log.w("setParameters rejected, using driver defaults: " + e);
            }

            Camera.Size actual = c.getParameters().getPreviewSize();
            previewWidth = actual.width;
            previewHeight = actual.height;

            // The sensor is mounted at a fixed angle relative to the phone's
            // natural orientation. On this hardware it is landscape-native while
            // the app is locked to portrait, so frames arrive rotated.
            //
            // setDisplayOrientation() fixes only what is drawn on the preview
            // surface; the NV21 buffers handed to preview callbacks are always
            // in raw sensor orientation. Snapshot, MJPEG and MCP all come from
            // those buffers, so the rotation has to be applied to the pixels too
            // -- see rotateNv21(). Both are driven from this one value.
            Camera.CameraInfo info = new Camera.CameraInfo();
            int rotation = 90;
            boolean front = false;
            try {
                Camera.getCameraInfo(id, info);
                front = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
                // Portrait-locked activity, so the display is always at 0 degrees.
                rotation = front
                        ? (360 - ((info.orientation + 0) % 360)) % 360
                        : ((info.orientation - 0) + 360) % 360;
            } catch (Exception e) {
                Log.w("getCameraInfo(" + id + ") failed, assuming 90: " + e);
            }
            sensorRotation = rotation;
            frontFacing = front;
            RotationListener rl = rotationListener;
            if (rl != null) {
                try {
                    rl.onSensorRotation(rotation);
                } catch (Exception e) {
                    Log.w("rotation listener threw: " + e);
                }
            }
            frameRotation = (rotation + rotationOffset) % 360;
            rotation = frameRotation;
            try {
                c.setDisplayOrientation(rotation);
            } catch (RuntimeException e) {
                Log.w("setDisplayOrientation failed: " + e);
            }

            // Read the volatile once: the Activity can swap it mid-open, and
            // activeHolder must record the surface we actually attached.
            SurfaceHolder holder = previewHolder;
            if (holder != null) {
                c.setPreviewDisplay(holder);
            } else {
                // Android 4.x needs *some* surface or preview frames never arrive.
                android.graphics.SurfaceTexture previous = dummyTexture;
                dummyTexture = new android.graphics.SurfaceTexture(DUMMY_TEXTURE_NAME);
                c.setPreviewTexture(dummyTexture);
                if (previous != null) {
                    // Release only after the new one is attached, so the HAL is
                    // never left without a preview target.
                    try { previous.release(); } catch (Exception ignored) { }
                }
            }

            c.setPreviewCallbackWithBuffer(previewCallback);
            // More buffers than the 2-3 typically needed: a slow listener must not
            // be able to starve the queue and stall frame delivery to everyone.
            int bufSize = previewWidth * previewHeight * 3 / 2;
            for (int i = 0; i < PREVIEW_BUFFER_COUNT; i++) {
                c.addCallbackBuffer(new byte[bufSize]);
            }

            c.startPreview();

            synchronized (lock) {
                camera = c;
                state = State.OPEN;
                framesSinceOpen = 0;
                openedAt = SystemClock.elapsedRealtime();
                activeCameraId = id;
                activeHolder = holder;
                activeRotation = frameRotation;
            }
            Log.i("camera open " + previewWidth + "x" + previewHeight);
            return true;
        } catch (Exception e) {
            Log.e("failed to open camera: " + e);
            if (c != null) {
                // Release explicitly: an instance left dangling keeps the HAL
                // busy and makes the next attempt fail too.
                try { c.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) { }
                try { c.stopPreview(); } catch (Exception ignored) { }
                try { c.release(); } catch (Exception ignored) { }
            }
            synchronized (lock) {
                camera = null;
            }
            closedAt = SystemClock.elapsedRealtime();
            return false;
        }
    }

    /** Runs on the camera thread. */
    private void doClose() {
        Camera c;
        SurfaceHolder attached;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            c = camera;
            // What was actually attached at open, which is not necessarily what
            // previewHolder points at now -- the Activity may have swapped it.
            attached = activeHolder;
            camera = null;
            state = State.CLOSED;
            lastFrame = null;
            lastFrameAt = 0;
            activeCameraId = -1;
            activeHolder = null;
            activeRotation = -1;
        }
        if (c != null) {
            // Order matters on the 4.x QCamera HAL: clear every callback, stop
            // preview, then detach the surface before releasing. Releasing while
            // a buffer callback or preview surface is still attached leaves the
            // HAL wedged, and every later open() fails with "startPreview failed"
            // no matter how long we wait.
            try {
                c.setPreviewCallbackWithBuffer(null);
            } catch (Exception e) {
                Log.w("clear callback failed: " + e);
            }
            try {
                c.setPreviewCallback(null);
            } catch (Exception e) {
                Log.w("clear preview callback failed: " + e);
            }
            try {
                c.stopPreview();
            } catch (Exception e) {
                Log.w("stopPreview failed: " + e);
            }
            try {
                // Only detach what we attached; clearing the display on a
                // texture-backed preview upsets the HAL.
                if (attached != null) {
                    c.setPreviewDisplay(null);
                } else {
                    c.setPreviewTexture(null);
                }
            } catch (Exception e) {
                Log.w("detach preview target failed: " + e);
            }
            try {
                c.release();
            } catch (Exception e) {
                Log.w("release failed: " + e);
            }
        }
        closedAt = SystemClock.elapsedRealtime();
        Log.i("camera closed");
    }

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null) {
                return;
            }
            framesSinceOpen++;

            synchronized (lock) {
                if (lastFrame == null || lastFrame.length != data.length) {
                    lastFrame = new byte[data.length];
                }
                System.arraycopy(data, 0, lastFrame, 0, data.length);
                lastFrameWidth = previewWidth;
                lastFrameHeight = previewHeight;
                lastFrameAt = SystemClock.elapsedRealtime();
            }

            List<FrameListener> snapshot;
            Camera current;
            synchronized (lock) {
                current = camera;
                snapshot = frameListeners.isEmpty()
                        ? null
                        : new ArrayList<FrameListener>(frameListeners);
            }

            // Frame from a camera instance we no longer own (a close or reopen
            // raced us). Drop the frame, but still hand the buffer back to the
            // instance it came from: that instance owns the buffer, and silently
            // swallowing it drains its pool so delivery stops for good.
            if (current != cam) {
                try {
                    cam.addCallbackBuffer(data);
                } catch (RuntimeException ignored) {
                    // Instance already released; its buffers die with it.
                }
                return;
            }

            try {
                if (snapshot != null) {
                    for (int i = 0; i < snapshot.size(); i++) {
                        try {
                            snapshot.get(i).onFrame(data, previewWidth, previewHeight);
                        } catch (Exception e) {
                            Log.w("frame listener threw: " + e);
                        }
                    }
                }
            } finally {
                // Always recycle, even if a listener threw, or the buffer pool
                // drains and delivery stops for good.
                try {
                    cam.addCallbackBuffer(data);
                } catch (RuntimeException e) {
                    Log.w("addCallbackBuffer failed: " + e);
                }
            }
        }
    };

    public void addFrameListener(FrameListener l) {
        synchronized (lock) {
            frameListeners.add(l);
        }
    }

    public void removeFrameListener(FrameListener l) {
        synchronized (lock) {
            frameListeners.remove(l);
        }
    }

    /**
     * Capture a single JPEG using the preview stream. Using preview frames rather
     * than Camera.takePicture() keeps this fast and avoids the shutter sound and
     * the preview restart that takePicture() forces on many 4.x devices.
     *
     * @return JPEG bytes, or null on failure.
     */
    public byte[] snapshotJpeg(long timeoutMs) {
        return snapshotJpeg(timeoutMs, DEFAULT_JPEG_QUALITY);
    }

    /** As {@link #snapshotJpeg(long)}, with an explicit JPEG quality (10..100). */
    public byte[] snapshotJpeg(long timeoutMs, final int quality) {
        if (isAccessDenied()) {
            return deniedJpeg(quality);
        }
        if (!acquire(timeoutMs)) {
            return null;
        }
        try {
            // Fast path: reuse a frame that just arrived. This is what makes a
            // snapshot cheap while MJPEG is streaming -- otherwise the snapshot
            // waits for a frame that the stream's own FPS throttle is delaying,
            // which can exceed a client's timeout (Home Assistant gives up at 10s).
            if (isExposureReady()) {
                byte[] cached = null;
                int cachedW = 0;
                int cachedH = 0;
                synchronized (lock) {
                    if (lastFrame != null
                            && SystemClock.elapsedRealtime() - lastFrameAt <= FRESH_FRAME_MAX_AGE_MS) {
                        cached = new byte[lastFrame.length];
                        System.arraycopy(lastFrame, 0, cached, 0, lastFrame.length);
                        cachedW = lastFrameWidth;
                        cachedH = lastFrameHeight;
                    }
                }
                if (cached != null) {
                    // Encode outside the lock: JPEG compression takes ~100ms here
                    // and would otherwise block the camera thread's dispatch.
                    return encodeJpegUpright(cached, cachedW, cachedH, quality);
                }
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final byte[][] raw = new byte[1][];
            final int[] dims = new int[2];
            // The listener only copies the frame; JPEG encoding happens on the
            // caller's thread below. Encoding here would block the camera
            // thread's dispatch loop and starve every other listener.
            FrameListener l = new FrameListener() {
                @Override
                public void onFrame(byte[] nv21, int width, int height) {
                    if (latch.getCount() == 0) {
                        return;
                    }
                    if (!isExposureReady()) {
                        return;
                    }
                    byte[] copy = new byte[nv21.length];
                    System.arraycopy(nv21, 0, copy, 0, nv21.length);
                    raw[0] = copy;
                    dims[0] = width;
                    dims[1] = height;
                    latch.countDown();
                }
            };
            addFrameListener(l);
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.w("snapshot timed out");
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                removeFrameListener(l);
            }
            if (raw[0] == null) {
                return null;
            }
            return encodeJpegUpright(raw[0], dims[0], dims[1], quality);
        } finally {
            release();
        }
    }

    /**
     * True once enough frames have passed since open() for exposure to settle,
     * or once the warm-up budget has elapsed (so a dark scene still returns).
     */
    private boolean isExposureReady() {
        if (framesSinceOpen >= AE_WARMUP_FRAMES) {
            return true;
        }
        return SystemClock.elapsedRealtime() - openedAt >= AE_WARMUP_MAX_MS;
    }

    /**
     * Rotate an NV21 frame clockwise by 0/90/180/270 degrees.
     *
     * <p>Done on the raw planes rather than by decoding to a Bitmap and using a
     * Matrix: a 640x480 NV21 frame is 460 KB, while the same image as an
     * ARGB_8888 Bitmap is 1.2 MB, and the rotate would need a second one. On a
     * 1 GB device doing this per frame that difference decides whether the app
     * survives.
     *
     * <p>NV21 is a full-size Y plane followed by an interleaved VU plane at half
     * resolution in both axes, so the chroma pairs move as units.
     *
     * @return the rotated frame, or {@code nv21} itself when rotation is 0.
     */
    public static byte[] rotateNv21(byte[] nv21, int width, int height, int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        if (r == 0) {
            return nv21;
        }
        int frameSize = width * height;
        // A malformed frame would otherwise walk off the end of the array.
        if (nv21.length < frameSize * 3 / 2) {
            return nv21;
        }
        byte[] out = new byte[frameSize * 3 / 2];

        if (r == 180) {
            for (int i = 0; i < frameSize; i++) {
                out[i] = nv21[frameSize - 1 - i];
            }
            // Chroma pairs are reversed as pairs, not byte by byte.
            for (int i = 0; i < frameSize / 2; i += 2) {
                int src = nv21.length - 2 - i;
                out[frameSize + i] = nv21[src];
                out[frameSize + i + 1] = nv21[src + 1];
            }
            return out;
        }

        // 90 or 270: the output is transposed, so its width is the input height.
        boolean clockwise = (r == 90);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = clockwise ? (height - 1 - y) : y;
                int dy = clockwise ? x : (width - 1 - x);
                out[dy * height + dx] = nv21[y * width + x];
            }
        }
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int srcBase = frameSize;
        int dstBase = frameSize;
        for (int y = 0; y < chromaHeight; y++) {
            for (int x = 0; x < chromaWidth; x++) {
                int dx = clockwise ? (chromaHeight - 1 - y) : y;
                int dy = clockwise ? x : (chromaWidth - 1 - x);
                int src = srcBase + y * width + x * 2;
                int dst = dstBase + dy * height + dx * 2;
                out[dst] = nv21[src];
                out[dst + 1] = nv21[src + 1];
            }
        }
        return out;
    }

    /** Clockwise rotation currently applied to captured frames. */
    public int getFrameRotation() {
        return frameRotation;
    }

    /** The user correction currently in effect. */
    public int getRotationOffset() {
        return rotationOffset;
    }

    /** Sensor mounting angle seen at the last open, before the user correction. */
    public int getSensorRotation() {
        return sensorRotation;
    }

    public void setRotationListener(RotationListener l) {
        this.rotationListener = l;
    }

    /**
     * Seed the sensor angle from a cached value so callers get the right answer
     * before the camera has been opened in this process. A real open overwrites
     * it, so a wrong cache is self-correcting.
     */
    public void setSensorRotation(int degrees) {
        if (sensorRotation == 0) {
            sensorRotation = ((degrees % 360) + 360) % 360;
            frameRotation = (sensorRotation + rotationOffset) % 360;
        }
    }

    /** Total rotation that will be applied, whether or not the camera is open. */
    public int getEffectiveRotation() {
        synchronized (lock) {
            if (state == State.OPEN) {
                return frameRotation;
            }
        }
        return (sensorRotation + rotationOffset) % 360;
    }

    /**
     * Set the manual correction added to the sensor-derived angle. Applies to
     * frames immediately; the on-screen preview follows at the next open, since
     * setDisplayOrientation() cannot be called while preview is running.
     */
    public void setRotationOffset(int degrees) {
        int v = ((degrees % 360) + 360) % 360;
        if (v == rotationOffset) {
            return;
        }
        rotationOffset = v;
        frameRotation = (sensorRotation + v) % 360;
    }

    /**
     * Encode an NV21 frame to JPEG, rotating it upright first. This is the path
     * every consumer (snapshot, MJPEG, MCP) should use, so that what a client
     * receives matches what the preview shows.
     */
    public byte[] encodeJpegUpright(byte[] nv21, int width, int height, int quality) {
        int r = frameRotation;
        if (r == 0) {
            return encodeJpeg(nv21, width, height, quality);
        }
        byte[] rotated = rotateNv21(nv21, width, height, r);
        // A quarter turn swaps the dimensions.
        boolean swap = (r == 90 || r == 270);
        return encodeJpeg(rotated, swap ? height : width, swap ? width : height, quality);
    }

    public static byte[] encodeJpeg(byte[] nv21, int width, int height, int quality) {
        try {
            android.graphics.YuvImage yuv =
                    new android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, width, height), quality, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e("jpeg encode failed: " + e);
            return null;
        }
    }

    /** Pick the supported preview size closest to the requested one, preferring <= target. */
    private static Camera.Size pickPreviewSize(Camera.Parameters p, int targetW, int targetH) {
        List<Camera.Size> sizes = p.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        List<Camera.Size> sorted = new ArrayList<Camera.Size>(sizes);
        final long targetArea = (long) targetW * targetH;
        java.util.Collections.sort(sorted, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size a, Camera.Size b) {
                long da = Math.abs((long) a.width * a.height - targetArea);
                long db = Math.abs((long) b.width * b.height - targetArea);
                return da < db ? -1 : (da > db ? 1 : 0);
            }
        });
        return sorted.get(0);
    }
}
