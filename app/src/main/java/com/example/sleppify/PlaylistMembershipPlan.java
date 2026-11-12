package com.example.sleppify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Plans idempotent membership edits using the playlist's current server state. */
public final class PlaylistMembershipPlan {
    public enum Action { NONE, ADD, REMOVE }
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    public final Action action;
    public final List<String> removeEntryIds;

    private PlaylistMembershipPlan(Action action, List<String> ids) {
        this.action = action;
        this.removeEntryIds = Collections.unmodifiableList(ids);
    }

    public static boolean isYouTubeVideo(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    public static PlaylistMembershipPlan fromServerState(boolean add, List<String> existingEntryIds) {
        if (add) return new PlaylistMembershipPlan(existingEntryIds.isEmpty() ? Action.ADD : Action.NONE,
                Collections.emptyList());
        if (existingEntryIds.isEmpty()) return new PlaylistMembershipPlan(Action.NONE, Collections.emptyList());
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : existingEntryIds) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Esta playlist no permite quitar canciones desde tu cuenta.");
            }
            unique.add(id);
        }
        return new PlaylistMembershipPlan(Action.REMOVE, new ArrayList<>(unique));
    }
}
