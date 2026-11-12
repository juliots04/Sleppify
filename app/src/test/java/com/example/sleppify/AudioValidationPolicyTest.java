package com.example.sleppify;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.sleppify.AudioValidationPolicy.Result.*;

public class AudioValidationPolicyTest {
    private static final long SIZE = 1024L * 1024L;

    @Test public void rejectsVideoOnlyAndZeroChannelDownloads() {
        assertEquals(INVALID, AudioValidationPolicy.evaluate(SIZE, 180, 0, -1, 180));
        assertEquals(INVALID, AudioValidationPolicy.evaluate(SIZE, 180, 1, 0, 180));
    }
    @Test public void transientMetadataErrorsAreNotCorruption() {
        assertEquals(UNREADABLE, AudioValidationPolicy.evaluate(SIZE, -1, -1, -1, 180));
        assertEquals(UNREADABLE, AudioValidationPolicy.evaluate(SIZE, -1, 1, 2, 180));
    }
    @Test public void toleratesMissingOptionalChannelMetadata() {
        assertEquals(VALID, AudioValidationPolicy.evaluate(SIZE, 180, 1, -1, 180));
    }
    @Test public void durationCheckStillRunsWhenExpectedDurationBecomesAvailable() {
        assertEquals(VALID, AudioValidationPolicy.evaluate(SIZE, 30, 1, 2, -1));
        assertEquals(INVALID, AudioValidationPolicy.evaluate(SIZE, 30, 1, 2, 180));
    }
    @Test public void acceptsToleranceBoundaryAndRejectsPartialFiles() {
        assertEquals(VALID, AudioValidationPolicy.evaluate(SIZE, 150, 1, 2, 180));
        assertEquals(INVALID, AudioValidationPolicy.evaluate(SIZE, 149, 1, 2, 180));
        assertEquals(INVALID, AudioValidationPolicy.evaluate(1024, 180, 1, 2, 180));
    }
    @Test public void shortOrUnknownDurationRetainsMinimumSafetyCheck() {
        assertEquals(INVALID, AudioValidationPolicy.evaluate(SIZE, 5, 1, 2, -1));
        assertEquals(VALID, AudioValidationPolicy.evaluate(SIZE, 6, 1, 2, -1));
    }
}
