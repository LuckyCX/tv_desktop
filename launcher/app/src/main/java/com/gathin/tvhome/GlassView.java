package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 科技感选中框：青色霓虹描边 + 外发光，替代原先白色玻璃。
 * 位置由外部弹簧动画驱动。
 */
public class GlassView extends View {

    /** 默认科技青。 */
    private static final int TECH_RGB = 0x00E5FF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    private int glowRgb = TECH_RGB;
    private int glowAlpha = 0x58;
    private int rimColor = 0xE000E5FF;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.2f * density);
        rim.setColor(rimColor);
        glow.setStyle(Paint.Style.STROKE);
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(2.6f * density);
        corner.setColor(0xFF7AFFFF);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        applyTechFill(1);
    }

    /** 0 = 科技青默认；其它 = 自定义强调色（节日等）。 */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = TECH_RGB;
            glowAlpha = 0x58;
            rimColor = 0xE000E5FF;
            corner.setColor(0xFF7AFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            int r = (((glowRgb >> 16) & 0xFF) + 80) / 2;
            int g = (((glowRgb >> 8) & 0xFF) + 200) / 2;
            int b = ((glowRgb & 0xFF) + 255) / 2;
            rimColor = 0xE0000000 | (r << 16) | (g << 8) | b;
            corner.setColor(0xFF000000 | glowRgb);
        }
        rim.setColor(rimColor);
        applyTechFill(getHeight() > 0 ? getHeight() : 1);
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    private void applyTechFill(int h) {
        int top = 0x2800E5FF;
        int bot = 0x10002840;
        fill.setShader(new LinearGradient(0, 0, 0, Math.max(1, h), top, bot, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, Math.max(1, h) * 0.4f,
                0x5500E5FF, 0x0000E5FF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTechFill(h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // 外圈霓虹辉光
        for (int i = 1; i <= 4; i++) {
            glow.setStrokeWidth(i * 1.8f * density);
            glow.setColor((glowAlpha / i) << 24 | glowRgb);
            float o = i * 1.2f * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o,
                    radius + o, radius + o, glow);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.4f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        // 主描边
        canvas.drawRoundRect(rect, radius, radius, rim);

        // 四角短线，增强科技感
        float len = 10f * density;
        float t = rim.getStrokeWidth() * 0.5f;
        float l = rect.left + t, tp = rect.top + t, r = rect.right - t, b = rect.bottom - t;
        // 左上
        canvas.drawLine(l, tp, l + len, tp, corner);
        canvas.drawLine(l, tp, l, tp + len, corner);
        // 右上
        canvas.drawLine(r - len, tp, r, tp, corner);
        canvas.drawLine(r, tp, r, tp + len, corner);
        // 左下
        canvas.drawLine(l, b - len, l, b, corner);
        canvas.drawLine(l, b, l + len, b, corner);
        // 右下
        canvas.drawLine(r, b - len, r, b, corner);
        canvas.drawLine(r - len, b, r, b, corner);
    }
}
