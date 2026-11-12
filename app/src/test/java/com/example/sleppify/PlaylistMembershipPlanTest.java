package com.example.sleppify;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class PlaylistMembershipPlanTest {
    @Test public void retryAfterSuccessfulAddDoesNotDuplicateTheSong() {
        assertEquals(PlaylistMembershipPlan.Action.ADD,
                PlaylistMembershipPlan.fromServerState(true, Collections.emptyList()).action);
        assertEquals(PlaylistMembershipPlan.Action.NONE,
                PlaylistMembershipPlan.fromServerState(true, Collections.singletonList("server-entry")).action);
    }

    @Test public void retryAfterSuccessfulRemovalDoesNotWriteAgain() {
        assertEquals(PlaylistMembershipPlan.Action.NONE,
                PlaylistMembershipPlan.fromServerState(false, Collections.emptyList()).action);
    }

    @Test public void removeUsesEachServerEntryOnceEvenAcrossOverlappingPages() {
        PlaylistMembershipPlan plan = PlaylistMembershipPlan.fromServerState(false, Arrays.asList("first", "second", "first"));
        assertEquals(PlaylistMembershipPlan.Action.REMOVE, plan.action);
        assertEquals(Arrays.asList("first", "second"), plan.removeEntryIds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectIncompleteRemovalBeforeSendingAnyEdit() {
        PlaylistMembershipPlan.fromServerState(false, Arrays.asList("valid-entry", ""));
    }

    @Test public void localFilesAndOtherProvidersAreNeverUploadedAsYoutubeSongs() {
        assertTrue(PlaylistMembershipPlan.isYouTubeVideo("Abc_123-xy9"));
        assertFalse(PlaylistMembershipPlan.isYouTubeVideo("local_123"));
        assertFalse(PlaylistMembershipPlan.isYouTubeVideo("content://media/external/audio/media/1"));
        assertFalse(PlaylistMembershipPlan.isYouTubeVideo(null));
    }
}
