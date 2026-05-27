package com.example.sleppify;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * ConstraintLayout that can intercept vertical swipe gestures before any child
 * (SeekBar, buttons, etc.) consumes them. Used by SongPlayerFragment to implement
 * reliable swipe-to-dismiss regardless of which child is touched.
 */
public class SwipeInterceptLayout extends ConstraintLayout {

    public interface SwipeInterceptCallback {
        /** Return true to start intercepting (steal subsequent MOVE/UP from children). */
        boolean onInterceptSwipe(MotionEvent e);
        boolean onSwipeTouch(MotionEvent e);
    }

    private SwipeInterceptCallback callback;
    private float downX, downY;
    private boolean intercepting = false;

    public SwipeInterceptLayout(Context context) { super(context); }
    public SwipeInterceptLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public SwipeInterceptLayout(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    public void setSwipeInterceptCallback(SwipeInterceptCallback cb) { this.callback = cb; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (callback == null) return super.onInterceptTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                intercepting = false;
                // Forward DOWN to reset gesture state; never intercept DOWN so children get clicks
                callback.onInterceptSwipe(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!intercepting) {
                    float dx = Math.abs(ev.getRawX() - downX);
                    float dy = ev.getRawY() - downY;
                    // Only intercept clear downward vertical swipes
                    if (dy > 10 && dy > dx * 1.5f) {
                        intercepting = callback.onInterceptSwipe(ev);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!intercepting) {
                    // gesture never started — reset
                    callback.onInterceptSwipe(ev);
                }
                intercepting = false;
                break;
        }
        return intercepting;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (callback != null) {
            boolean handled = callback.onSwipeTouch(ev);
            if (handled) return true;
        }
        return super.onTouchEvent(ev);
    }
}
