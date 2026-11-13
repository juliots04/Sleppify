package com.example.sleppify;

final class SheetPresentation {
    private SheetPresentation() { }

    /** Darken the player and its status bar together, only near the top of the gesture. */
    static float backgroundAlpha(float progress) {
        float t = Math.max(0f, Math.min(1f, (progress - 0.72f) / 0.28f));
        return t * t * (3f - 2f * t);
    }
}
