package jp.tonekeep.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small vector icons, with consistent strokes at any screen density. */
final class UiIcon extends Drawable {
    enum Kind { SEARCH, LIBRARY, KEY, DOWNLOAD, FOLDER, LIST, MORE, CLOSE, PLAY, PAUSE, RETRY, INFO, LINK, MINUS, PLUS, CHECK, MUSIC }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    UiIcon(Kind kind, int color) { this.kind = kind; paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE); }
    @Override public void setTint(int color) { paint.setColor(color); invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); canvas.translate(getBounds().left, getBounds().top); canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (kind) {
            case SEARCH -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(canvas, 15.5f, 15.5f, 21, 21); }
            case LIBRARY -> { line(canvas, 4, 4, 4, 20); line(canvas, 9, 4, 9, 20); line(canvas, 14, 5, 20, 20); }
            case KEY -> { line(canvas, 5, 7, 19, 7); line(canvas, 5, 17, 19, 17); canvas.drawCircle(9, 7, 2, paint); canvas.drawCircle(15, 17, 2, paint); }
            case DOWNLOAD -> { line(canvas, 12, 3, 12, 16); path(canvas, 7, 11, 12, 16, 17, 11); path(canvas, 4, 17, 4, 21, 20, 21, 20, 17); }
            case FOLDER -> path(canvas, 3, 6, 3, 20, 21, 20, 21, 8, 12, 8, 10, 5, 3, 5, 3, 6);
            case LIST -> { for (int y = 6; y <= 18; y += 6) { line(canvas, 8, y, 21, y); canvas.drawCircle(3, y, .6f, paint); } }
            case MORE -> { for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, .9f, paint); }
            case CLOSE -> { line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); }
            case PLAY -> path(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
            case PAUSE -> { line(canvas, 8, 5, 8, 19); line(canvas, 16, 5, 16, 19); }
            case RETRY -> { canvas.drawArc(4, 4, 20, 20, 35, 285, false, paint); path(canvas, 20, 4, 20, 10, 14, 10); }
            case INFO -> { canvas.drawCircle(12, 12, 9, paint); line(canvas, 12, 11, 12, 17); canvas.drawCircle(12, 7, .6f, paint); }
            case LINK -> { path(canvas, 14, 3, 21, 3, 21, 10); line(canvas, 21, 3, 10, 14); path(canvas, 11, 5, 4, 5, 4, 21, 20, 21, 20, 13); }
            case MINUS -> line(canvas, 5, 12, 19, 12);
            case PLUS -> { line(canvas, 5, 12, 19, 12); line(canvas, 12, 5, 12, 19); }
            case CHECK -> path(canvas, 4, 12, 9, 17, 20, 6);
            case MUSIC -> { path(canvas, 10, 17, 10, 5, 20, 3, 20, 15); canvas.drawOval(4, 15, 10, 20, paint); canvas.drawOval(14, 13, 20, 18, paint); }
        }
        canvas.restoreToCount(save);
    }
    private void line(Canvas canvas, float x, float y, float a, float b) { canvas.drawLine(x, y, a, b, paint); }
    private void path(Canvas canvas, float... points) { Path path = new Path(); path.moveTo(points[0], points[1]); for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]); canvas.drawPath(path, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
