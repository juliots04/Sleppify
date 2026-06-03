package com.example.sleppify;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class TransientBottomBarAnimator {

    private static final long ENTER_DURATION_MS = 240L;
    private static final long EXIT_DURATION_MS = 180L;
    private static final float ENTER_TRANSLATION_DP = 24f;
    private static final float EXIT_TRANSLATION_DP = 16f;

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
        rootView.addView(bar, layoutParams);

        bar.post(() -> {
            if (bar.getParent() == null || getState(bar) != STATE_VISIBLE) return;
            bar.animate().cancel();
            bar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ENTER_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
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
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator())
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
