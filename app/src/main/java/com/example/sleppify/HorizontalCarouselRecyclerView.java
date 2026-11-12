package com.example.sleppify;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/** Locks each gesture to one axis before a vertical scroll view or sheet can steal it. */
public final class HorizontalCarouselRecyclerView extends RecyclerView {
    private static final int UNDECIDED = 0, HORIZONTAL = 1, VERTICAL = 2;
    private final int touchSlop;
    private int direction = UNDECIDED;
    private float startX, startY;

    public HorizontalCarouselRecyclerView(@NonNull Context context) { this(context, null); }
    public HorizontalCarouselRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }
    public HorizontalCarouselRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int style) {
        super(context, attrs, style);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setNestedScrollingEnabled(false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = event.getX();
            startY = event.getY();
            direction = UNDECIDED;
            allowParentIntercept(false);
        } else if (action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == 0
                && event.getPointerCount() > 1) {
            startX += event.getX(1) - event.getX(0);
            startY += event.getY(1) - event.getY(0);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (direction == UNDECIDED) {
                float dx = Math.abs(event.getX() - startX);
                float dy = Math.abs(event.getY() - startY);
                if (Math.max(dx, dy) > touchSlop) direction = dx >= dy ? HORIZONTAL : VERTICAL;
            }
            allowParentIntercept(direction == VERTICAL);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            allowParentIntercept(true);
            direction = UNDECIDED;
        }
        return handled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (direction == VERTICAL && event.getActionMasked() == MotionEvent.ACTION_MOVE) return false;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (direction == VERTICAL && event.getActionMasked() == MotionEvent.ACTION_MOVE) return false;
        return super.onTouchEvent(event);
    }

    private void allowParentIntercept(boolean allow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(!allow);
    }
}
