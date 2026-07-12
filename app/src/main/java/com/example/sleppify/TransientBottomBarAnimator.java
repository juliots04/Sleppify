package com.example.sleppify;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.animation.PathInterpolatorCompat;

final class TransientBottomBarAnimator {

    private static final long ENTER_DURATION_MS = 380L;
    private static final long EXIT_DURATION_MS = 240L;
    private static final float ENTER_TRANSLATION_DP = 56f;
    private static final float EXIT_TRANSLATION_DP = 28f;
    private static final float ENTER_SCALE = 0.92f;
    private static final float EXIT_SCALE = 0.95f;

    // Fluid "liquid" easing: fast rise with a soft overshoot settle (easeOutBack-ish).
    private static final Interpolator ENTER_INTERPOLATOR =
            PathInterpolatorCompat.create(0.22f, 1.18f, 0.36f, 1f);
    private static final Interpolator EXIT_INTERPOLATOR =
            PathInterpolatorCompat.create(0.4f, 0f, 1f, 1f);

    private static final int STATE_HIDDEN = 0;
    private static final int STATE_VISIBLE = 1;
    private static final int STATE_DISMISSING = 2;

    private TransientBottomBarAnimator() {
    }

    static void show(
            @NonNull ViewGroup rootView,
            @NonNull View bar,
            @NonNull ViewGroup.LayoutParams layoutParams,
            @Nullable Object existingTag,
            long autoDismissMs
    ) {
        if (existingTag != null) {
            View existing = rootView.findViewWithTag(existingTag);
            if (existing != null && existing != bar) {
                dismiss(existing, null);
            }
        }

        bar.setTag(R.id.tag_transient_bar_state, STATE_VISIBLE);
        bar.setAlpha(0f);
        bar.setTranslationY(dp(bar.getContext(), ENTER_TRANSLATION_DP));
        bar.setScaleX(ENTER_SCALE);
        bar.setScaleY(ENTER_SCALE);
        rootView.addView(bar, layoutParams);

        bar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                v.removeOnLayoutChangeListener(this);
                if (v.getParent() == null || getState(v) != STATE_VISIBLE) return;
                v.setPivotX(v.getWidth() / 2f);
                v.setPivotY(v.getHeight());
                v.animate().cancel();
                v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(ENTER_DURATION_MS)
                        .setInterpolator(ENTER_INTERPOLATOR)
                        .start();
            }
        });

        bar.postDelayed(() -> dismiss(bar, null), autoDismissMs);
    }

    static void dismiss(@NonNull View bar) {
        dismiss(bar, null);
    }

    static void dismiss(@NonNull View bar, @Nullable Runnable endAction) {
        int state = getState(bar);
        if (state == STATE_DISMISSING || state == STATE_HIDDEN) {
            if (endAction != null) endAction.run();
            return;
        }

        bar.setTag(R.id.tag_transient_bar_state, STATE_DISMISSING);
        // Clear the lookup tag so findViewWithTag (show() dedupe, MainActivity's saved-bar
        // transfer) can never grab a bar that is already on its way out — re-hosting or
        // "dismissing" a DISMISSING bar would otherwise leave it stuck on screen forever.
        bar.setTag(null);
        if (bar.getParent() == null) {
            bar.setTag(R.id.tag_transient_bar_state, STATE_HIDDEN);
            if (endAction != null) endAction.run();
            return;
        }

        bar.animate().cancel();
        float exitTranslation = Math.max(dp(bar.getContext(), EXIT_TRANSLATION_DP), bar.getHeight() * 0.15f);
        bar.animate()
                .alpha(0f)
                .translationY(exitTranslation)
                .scaleX(EXIT_SCALE)
                .scaleY(EXIT_SCALE)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(EXIT_INTERPOLATOR)
                .withEndAction(() -> {
                    ViewParent parent = bar.getParent();
                    if (parent instanceof ViewGroup) {
                        ((ViewGroup) parent).removeView(bar);
                    }
                    bar.setTag(R.id.tag_transient_bar_state, STATE_HIDDEN);
                    if (endAction != null) endAction.run();
                })
                .start();
    }

    private static int getState(@NonNull View bar) {
        Object tag = bar.getTag(R.id.tag_transient_bar_state);
        return tag instanceof Integer ? (Integer) tag : STATE_HIDDEN;
    }

    private static float dp(@NonNull Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}
