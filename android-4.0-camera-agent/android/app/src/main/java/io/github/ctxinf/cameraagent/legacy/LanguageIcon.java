package io.github.ctxinf.cameraagent.legacy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** A theme-colored 文/A language symbol drawn natively on Android 4.0+. */
public final class LanguageIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    public LanguageIcon(Context context) {
        size = Math.round(32 * context.getResources().getDisplayMetrics().density);
        android.content.res.TypedArray colors = context.obtainStyledAttributes(
                new int[] { android.R.attr.textColorPrimary });
        paint.setColor(colors.getColor(0, android.graphics.Color.DKGRAY));
        colors.recycle();
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    @Override public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 32f, getBounds().height() / 32f);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(17);
        canvas.drawText("文", 0, 20, paint);
        paint.setTextSize(18);
        canvas.drawText("A", 20, 24, paint);
        paint.setStrokeWidth(1.5f);
        canvas.drawLine(17, 24, 21, 9, paint);
        canvas.restoreToCount(saved);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
