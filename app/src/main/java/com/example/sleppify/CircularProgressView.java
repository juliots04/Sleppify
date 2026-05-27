package com.example.sleppify;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Lightweight circular progress view that draws an arc from 0° to 360°.
 * Call {@link #setProgress(float)} with a value between 0 and 1.
 */
public class CircularProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float progress = 0f;          // 0..1
    private float displayProgress = 0f;   // animated value

    @Nullable
    private ValueAnimator animator;

    private static final float STROKE_WIDTH_DP = 2f;
    private static final long ANIM_DURATION_MS = 320L;
    private static final float START_ANGLE = -90f; // 12 o'clock

    public CircularProgressView(@NonNull Context context) {
        this(context, null);
    }

    public CircularProgressView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircularProgressView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        float strokePx = STROKE_WIDTH_DP * density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokePx);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        trackPaint.setAlpha(60);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokePx);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(ContextCompat.getColor(context, R.color.stitch_blue));
    }

    public void setProgressColor(@ColorInt int color) {
        progressPaint.setColor(color);
        invalidate();
    }

    public void setTrackColor(@ColorInt int color) {
        trackPaint.setColor(color);
        invalidate();
    }

    /**
     * Set progress (0..1). Animates smoothly from current displayed value.
     */
    public void setProgress(float target) {
        target = Math.max(0f, Math.min(1f, target));
        if (Float.compare(this.progress, target) == 0) {
            return;
        }
        this.progress = target;
        animateTo(target);
    }

    /**
     * Immediately jump to the given progress without animation.
     */
    public void setProgressImmediate(float target) {
        target = Math.max(0f, Math.min(1f, target));
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        this.progress = target;
        this.displayProgress = target;
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(displayProgress, target);
        animator.setDuration(ANIM_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            displayProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float half = trackPaint.getStrokeWidth() / 2f;
        arcRect.set(half, half, getWidth() - half, getHeight() - half);

        // Background track circle
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        // Progress arc
        if (displayProgress > 0.001f) {
            float sweep = displayProgress * 360f;
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, progressPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
