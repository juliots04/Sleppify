package com.example.sleppify;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

/** Footer opening has one position owner. Native nested dragging starts only once fully open. */
final class PlayerQueueSheet {
    private final CoordinatorLayout host;
    private final View content;
    private final View miniPlayer;
    private final View statusBarScrim;
    private final WindowInsetsControllerCompat systemBars;
    private boolean originalLightStatusBar;
    private boolean appliedLightStatusBar;
    private BottomSheetBehavior<View> behavior;
    private boolean showing;
    private boolean dragging;
    private float dragDistance;
    private int generation;

    PlayerQueueSheet(ViewGroup parent, View content, Window window) {
        this.content = content;
        miniPlayer = content.findViewById(R.id.llSheetMiniPlayer);
        systemBars = new WindowInsetsControllerCompat(window, window.getDecorView());
        host = new CoordinatorLayout(parent.getContext());
        ConstraintLayout.LayoutParams hostParams = new ConstraintLayout.LayoutParams(0, 0);
        hostParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        hostParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        hostParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        hostParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        host.setVisibility(View.GONE);
        parent.addView(host, hostParams);
        statusBarScrim = new View(parent.getContext());
        statusBarScrim.setBackgroundColor(android.graphics.Color.BLACK);
        statusBarScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusBarScrim.setVisibility(View.GONE);
        ConstraintLayout.LayoutParams scrimParams = new ConstraintLayout.LayoutParams(0, 0);
        scrimParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        scrimParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        scrimParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        parent.addView(statusBarScrim, scrimParams);
        ViewCompat.setOnApplyWindowInsetsListener(host, (view, insets) -> {
            Insets bars = applyInsets(insets);
            // The host already avoids these bars. Passing them to the behavior again pushes
            // the expanded sheet down a second time, leaving the player's header exposed.
            return insets.inset(bars.left, bars.top, bars.right, bars.bottom);
        });
        WindowInsetsCompat currentInsets = ViewCompat.getRootWindowInsets(parent);
        if (currentInsets != null) applyInsets(currentInsets);
        ViewCompat.requestApplyInsets(host);
        CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        host.addView(content, params);
        setPresentationProgress(0f);
    }

    private Insets applyInsets(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        ConstraintLayout.LayoutParams layout = (ConstraintLayout.LayoutParams) host.getLayoutParams();
        if (layout.topMargin != bars.top || layout.bottomMargin != bars.bottom
                || layout.leftMargin != bars.left || layout.rightMargin != bars.right) {
            layout.setMargins(bars.left, bars.top, bars.right, bars.bottom);
            host.setLayoutParams(layout);
        }
        ViewGroup.LayoutParams scrim = statusBarScrim.getLayoutParams();
        if (scrim.height != bars.top) {
            scrim.height = bars.top;
            statusBarScrim.setLayoutParams(scrim);
        }
        return bars;
    }

    private void updatePresentation() {
        float progress = 1f - (content.getTop() + content.getTranslationY()) / height();
        setPresentationProgress(Math.max(0f, Math.min(1f, progress)));
    }

    private void setPresentationProgress(float progress) {
        if (miniPlayer != null) miniPlayer.setAlpha(progress);
        statusBarScrim.setAlpha(progress);
        if (showing) setLightStatusBar(progress < 0.25f && originalLightStatusBar);
    }

    private void setLightStatusBar(boolean light) {
        if (appliedLightStatusBar == light) return;
        appliedLightStatusBar = light;
        systemBars.setAppearanceLightStatusBars(light);
    }

    boolean isShowing() { return showing; }

    void show() {
        if (showing) return;
        int token = beginOpening(false);
        host.post(() -> {
            if (generation != token || !showing || dragging) return;
            content.setTranslationY(height());
            settle(true);
        });
    }

    void beginDrag() {
        int token = beginOpening(true);
        host.post(() -> {
            if (generation == token && dragging) dragTo(dragDistance);
        });
    }

    private int beginOpening(boolean followFinger) {
        int token = ++generation;
        content.animate().cancel();
        if (!showing) {
            originalLightStatusBar = systemBars.isAppearanceLightStatusBars();
            appliedLightStatusBar = originalLightStatusBar;
        }
        showing = true;
        dragging = followFinger;
        dragDistance = 0f;
        detachBehavior();
        content.setTranslationY(height());
        androidx.core.view.ViewCompat.offsetTopAndBottom(content, -content.getTop());
        setPresentationProgress(0f);
        statusBarScrim.setVisibility(View.VISIBLE);
        host.setVisibility(View.VISIBLE);
        return token;
    }

    void dragTo(float distance) {
        if (!dragging) return;
        dragDistance = Math.max(0f, distance);
        content.setTranslationY(Math.max(0f, height() - dragDistance));
        updatePresentation();
    }

    void endDrag(float upwardVelocity, boolean cancelled) {
        if (!dragging) return;
        boolean expand = !cancelled && (upwardVelocity > 600f
                || (upwardVelocity > -600f && dragDistance > height() * 0.25f));
        dragging = false;
        settle(expand);
    }

    private void settle(boolean expand) {
        int token = generation;
        content.animate().translationY(expand ? 0f : height()).setDuration(220L)
                .setUpdateListener(animation -> updatePresentation())
                .withEndAction(() -> {
                    if (generation != token) return;
                    if (expand) attachExpandedBehavior(); else dismiss();
                }).start();
    }

    private void attachExpandedBehavior() {
        BottomSheetBehavior<View> nativeDrag = new BottomSheetBehavior<>(host.getContext(), null);
        nativeDrag.setHideable(true);
        nativeDrag.setSkipCollapsed(true);
        nativeDrag.setPeekHeight(0);
        // Setting the initial state before attachment avoids a second opening animation.
        nativeDrag.setState(BottomSheetBehavior.STATE_EXPANDED);
        nativeDrag.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(View sheet, int state) {
                if (behavior == nativeDrag && state == BottomSheetBehavior.STATE_HIDDEN) dismiss();
            }
            @Override public void onSlide(View sheet, float offset) {
                if (behavior == nativeDrag) updatePresentation();
            }
        });
        behavior = nativeDrag;
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) content.getLayoutParams();
        params.setBehavior(nativeDrag);
        content.setLayoutParams(params);
    }

    private void detachBehavior() {
        behavior = null;
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) content.getLayoutParams();
        params.setBehavior(null);
        content.setLayoutParams(params);
    }

    private int height() {
        View parent = (View) host.getParent();
        ConstraintLayout.LayoutParams layout = (ConstraintLayout.LayoutParams) host.getLayoutParams();
        return Math.max(1, host.getHeight() > 0 ? host.getHeight()
                : parent.getHeight() - layout.topMargin - layout.bottomMargin);
    }

    void dismiss() {
        if (showing) setLightStatusBar(originalLightStatusBar);
        ++generation;
        dragging = false;
        showing = false;
        content.animate().cancel();
        host.setVisibility(View.GONE);
        statusBarScrim.setVisibility(View.GONE);
        setPresentationProgress(0f);
        content.setTranslationY(0f);
        detachBehavior();
    }
}
