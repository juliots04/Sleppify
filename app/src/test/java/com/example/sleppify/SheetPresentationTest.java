package com.example.sleppify;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SheetPresentationTest {
    @Test public void playerRemainsUndimmedUntilSheetNearsTop() {
        for (float progress : new float[]{0f, .25f, .5f, .72f}) {
            assertEquals(0f, SheetPresentation.backgroundAlpha(progress), .0001f);
        }
    }

    @Test public void finalTravelFadesContinuouslyAndReversesWithFinger() {
        float previous = 0f;
        for (int percent = 73; percent <= 100; percent++) {
            float alpha = SheetPresentation.backgroundAlpha(percent / 100f);
            assertTrue(alpha >= previous && alpha <= 1f);
            assertTrue(alpha - previous < .06f);
            previous = alpha;
        }
        assertEquals(.5f, SheetPresentation.backgroundAlpha(.86f), .0001f);
        assertEquals(0f, SheetPresentation.backgroundAlpha(-.1f), .0001f);
        assertEquals(1f, SheetPresentation.backgroundAlpha(1.1f), .0001f);
    }
}
