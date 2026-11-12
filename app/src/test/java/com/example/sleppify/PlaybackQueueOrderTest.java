package com.example.sleppify;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackQueueOrderTest {
    @Test public void limitedSheetKeepsUndisplayedTracksInPlaybackOrder() {
        List<String> queue = Arrays.asList("A", "B", "C", "D", "E", "F");
        assertEquals(Arrays.asList("C", "E", "D", "F", "A", "B"),
                PlaybackQueueOrder.apply(queue, 2, Arrays.asList("C", "E", "D")));
        assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), queue);
    }

    @Test public void differentQueueEntriesOfSameSongArePreserved() {
        String first = new String("same-video-id"), second = new String("same-video-id");
        String current = "current";
        List<String> result = PlaybackQueueOrder.apply(Arrays.asList(current, first, second), 0,
                Arrays.asList(current, second, first));
        assertEquals(3, result.size());
        assertSame(second, result.get(1));
        assertSame(first, result.get(2));
    }

    @Test public void staleRowsCannotReplaceCurrentTrackOrInjectRecommendations() {
        List<String> result = PlaybackQueueOrder.apply(Arrays.asList("A", "B", "C"), 1,
                Arrays.asList("unknown", "A", "A"));
        assertEquals(Arrays.asList("B", "A", "C"), result);
    }

    @Test public void emptyAndInvalidQueuesAreSafe() {
        assertTrue(PlaybackQueueOrder.apply(Collections.emptyList(), 0, Arrays.asList("A")).isEmpty());
        assertEquals(Arrays.asList("A"), PlaybackQueueOrder.apply(Arrays.asList("A"), -1, Arrays.asList("B")));
    }
}
