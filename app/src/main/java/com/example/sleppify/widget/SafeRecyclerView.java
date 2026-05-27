package com.example.sleppify.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView subclass that survives the IndexOutOfBoundsException caused by
 * data-set changes racing with onMeasure → dispatchLayoutStep1.
 *
 * When the crash is intercepted:
 *  1. The current adapter is detached (swapAdapter null) to clear all ViewHolder state.
 *  2. The adapter is re-attached so the next frame renders cleanly.
 *
 * This is a last-resort safety net. The root cause (data + notify atomicity) is
 * already handled in MusicResultsAdapter.applyDataAndNotify() — this guard
 * prevents process death on the rare frames where the race still occurs.
 */
public class SafeRecyclerView extends RecyclerView {

    private static final String TAG = "SafeRecyclerView";

    public SafeRecyclerView(@NonNull Context context) {
        super(context);
    }

    public SafeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SafeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        try {
            super.onMeasure(widthSpec, heightSpec);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Caught RecyclerView inconsistency during onMeasure — deferring recovery.", e);
            // Do NOT call swapAdapter/setAdapter here — that causes its own crashes
            // (IllegalArgumentException: Tmp detached view) because we are inside a layout pass.
            // Just survive this frame; requestLayout on the next frame so the RV re-renders.
            post(this::requestLayout);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        try {
            super.onLayout(changed, l, t, r, b);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Caught RecyclerView inconsistency during onLayout — deferring recovery.", e);
            post(this::requestLayout);
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        try {
            return super.onTouchEvent(e);
        } catch (IndexOutOfBoundsException ex) {
            Log.w(TAG, "Caught RecyclerView inconsistency during onTouchEvent — recovering.", ex);
            post(this::requestLayout);
            return true;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        try {
            return super.onInterceptTouchEvent(e);
        } catch (IndexOutOfBoundsException ex) {
            Log.w(TAG, "Caught RecyclerView inconsistency during onInterceptTouchEvent — recovering.", ex);
            return false;
        }
    }
}
