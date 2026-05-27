package com.example.sleppify;

import java.util.concurrent.CopyOnWriteArrayList;

public class PlaybackEventBus {

    public interface Listener {
        void onPlaybackSnapshotUpdated();
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) {
        if (l == null) return;
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        listeners.remove(l);
    }

    public static void notifyPlaybackSnapshotUpdated() {
        for (Listener l : listeners) {
            try {
                l.onPlaybackSnapshotUpdated();
            } catch (Exception ignored) {
            }
        }
    }
}
