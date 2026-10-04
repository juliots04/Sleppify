package com.example.sleppify;

import java.util.concurrent.CopyOnWriteArrayList;

public class PlaybackEventBus {

    public interface Listener {
        void onPlaybackSnapshotUpdated();
        default void onShuffleModeChanged(boolean enabled) {}
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
            } catch (Exception e) {
                android.util.Log.w("PlaybackEventBus", "Listener error", e);
            }
        }
    }

    public static void notifyShuffleModeChanged(boolean enabled) {
        for (Listener l : listeners) {
            try {
                l.onShuffleModeChanged(enabled);
            } catch (Exception e) {
                android.util.Log.w("PlaybackEventBus", "Listener error", e);
            }
        }
    }
}
