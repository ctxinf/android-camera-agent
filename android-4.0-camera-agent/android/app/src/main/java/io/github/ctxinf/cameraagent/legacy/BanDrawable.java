package io.github.ctxinf.cameraagent.legacy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Font-independent ban icon, including pre-vector Android releases. */
public class BanDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    public BanDrawable(float density, int color) {
        size = (int) (16 * density);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float x = bounds.exactCenterX(), y = bounds.exactCenterY();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f - paint.getStrokeWidth();
        canvas.drawCircle(x, y, radius, paint);
        float d = radius * 0.7071f;
        canvas.drawLine(x - d, y + d, x + d, y - d, paint);
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
