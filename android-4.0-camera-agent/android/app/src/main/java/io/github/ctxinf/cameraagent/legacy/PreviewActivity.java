package io.github.ctxinf.cameraagent.legacy;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import io.github.ctxinf.cameraagent.legacy.camera.CameraManager;

/** Dedicated local preview, rotation and image-configuration page. */
public class PreviewActivity extends Activity {
    private static final long ACQUIRE_TIMEOUT_MS = 8000;
    private final Handler handler = new Handler();
    private Settings settings;
    private ToggleButton previewToggle;
    private Button rotateButton;
    private TextView infoView;
    private TextView deniedView;
    private SurfaceHolder holder;
    private SurfaceView previewSurface;
    private boolean leaseHeld;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_preview);
        settings = new Settings(this);
        previewToggle = (ToggleButton) findViewById(R.id.preview_toggle);
        rotateButton = (Button) findViewById(R.id.rotate);
        infoView = (TextView) findViewById(R.id.preview_info);
        deniedView = (TextView) findViewById(R.id.preview_denied);
        SurfaceView surface = (SurfaceView) findViewById(R.id.preview_surface);
        previewSurface = surface;
        ((View) surface.getParent()).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int a, int b, int c, int d, int e, int f, int g, int h) { fitPreview(); }
        });
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                holder = h; CameraManager camera = camera();
                if (camera != null) camera.setPreviewHolder(h);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {
                holder = h; CameraManager camera = camera();
                if (camera != null && leaseHeld) { camera.setPreviewHolder(h); camera.reconfigure(); }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                holder = null; CameraManager camera = camera();
                if (camera != null) camera.setPreviewHolder(null);
            }
        });
        previewToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) startPreview(); else stopPreview();
            }
        });
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = (settings.rotationOffset() + 90) % 360;
                settings.setRotationOffset(next);
                CameraManager camera = camera();
                if (camera != null) { camera.setRotationOffset(next); camera.reconfigure(); }
                refreshInfo();
            }
        });
        refreshInfo();
    }

    private CameraManager camera() {
        AgentService service = AgentService.get();
        return service == null ? null : service.getCameraManager();
    }

    private void startPreview() {
        final CameraManager camera = camera();
        if (camera == null) { previewToggle.setChecked(false); return; }
        if (camera.isAccessDenied()) {
            deniedView.setText(camera.accessDeniedMessage());
            deniedView.setVisibility(View.VISIBLE);
            refreshInfo();
            return;
        }
        deniedView.setVisibility(View.GONE);
        previewToggle.setEnabled(false);
        final int requestedGeneration = ++generation;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean opened = camera.acquire(ACQUIRE_TIMEOUT_MS);
                handler.post(new Runnable() {
                    @Override public void run() {
                        previewToggle.setEnabled(true);
                        if (requestedGeneration != generation) { if (opened) camera.release(); return; }
                        if (!opened) { previewToggle.setChecked(false); return; }
                        leaseHeld = true;
                        if (holder != null) camera.setPreviewHolder(holder);
                        camera.reconfigure(); refreshInfo();
                    }
                });
            }
        }, "local-preview").start();
    }

    private void stopPreview() {
        generation++;
        deniedView.setVisibility(View.GONE);
        if (leaseHeld) { leaseHeld = false; CameraManager camera = camera(); if (camera != null) camera.release(); }
        refreshInfo();
    }

    private void refreshInfo() {
        CameraManager camera = camera();
        String resolution = camera == null ? null : camera.getActiveResolution();
        String info = (resolution == null ? settings.resolution() : resolution)
                + "  ·  JPEG q" + settings.jpegQuality() + "  ·  " + settings.mjpegFps() + " fps\n"
                + "旋转 " + settings.rotationOffset() + "°";
        if (!info.contentEquals(infoView.getText())) infoView.setText(info);
        String label = getString(R.string.rotate_button_fmt, Integer.valueOf(settings.rotationOffset()));
        if (!label.contentEquals(rotateButton.getText())) rotateButton.setText(label);
        fitPreview();
    }

    private void fitPreview() {
        CameraManager camera = camera();
        String resolution = camera == null ? null : camera.getActiveResolution();
        int w, h;
        if (resolution != null) {
            String[] size = resolution.split("x");
            w = Integer.parseInt(size[0]); h = Integer.parseInt(size[1]);
        } else {
            w = settings.resolutionWidth(); h = settings.resolutionHeight();
            int rotation = (settings.sensorRotation() + settings.rotationOffset()) % 360;
            if (rotation % 180 != 0) { int tmp = w; w = h; h = tmp; }
        }
        View parent = (View) previewSurface.getParent();
        if (parent.getWidth() == 0 || parent.getHeight() == 0) return;
        float scale = Math.min((float) parent.getWidth() / w, (float) parent.getHeight() / h);
        int width = Math.max(1, (int) (w * scale)), height = Math.max(1, (int) (h * scale));
        android.widget.FrameLayout.LayoutParams old = (android.widget.FrameLayout.LayoutParams) previewSurface.getLayoutParams();
        if (old.width != width || old.height != height) {
            previewSurface.setLayoutParams(new android.widget.FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER));
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refreshInfo(); handler.postDelayed(this, 500); }
    };
    @Override protected void onResume() { super.onResume(); handler.post(refreshTask); }
    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        if (previewToggle.isChecked()) previewToggle.setChecked(false);
        super.onPause();
    }
    @Override protected void onDestroy() {
        generation++; if (leaseHeld) { CameraManager camera = camera(); if (camera != null) camera.release(); }
        handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
