package com.example.sleppify;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackDurationTest {
    @Test public void parsesSongsLongMixesAndHours() {
        assertEquals(272, TrackDuration.parseSeconds("4:32", -1));
        assertEquals(6000, TrackDuration.parseSeconds("100:00", -1));
        assertEquals(3723, TrackDuration.parseSeconds(" 1:02:03 ", -1));
        assertEquals(0, TrackDuration.parseSeconds("0:00", -1));
    }

    @Test public void preservesCallerUnknownValue() {
        assertEquals(1, TrackDuration.parseSeconds("--:--", 1));
        assertEquals(0, TrackDuration.parseSeconds("En directo", 0));
        assertEquals(-1, TrackDuration.parseSeconds(null, -1));
    }

    @Test public void malformedValuesNeverBecomeValidOfflineDurations() {
        for (String value : new String[]{"-1:30", "3:99", "1::30", "3:", "1:60:00", "999999999999999:00", "2147483647:00"}) {
            assertEquals(value, -1, TrackDuration.parseSeconds(value, -1));
        }
    }
}
