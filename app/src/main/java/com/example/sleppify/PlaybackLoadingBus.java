package com.example.sleppify;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static event bus that broadcasts playback loading state for online tracks.
 * Components (player cover, mini-player, adapter rows) subscribe to show/hide
 * a spinner overlay while audio is buffering.
 */
public final class PlaybackLoadingBus {

    public interface Listener {
        void onPlaybackLoadingStateChanged(@NonNull String videoId, boolean loading);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private static volatile String loadingVideoId = null;

    public static void addListener(@NonNull Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    /** Called when an online track starts loading (resolve + buffer). */
    public static void notifyLoadingStarted(@NonNull String videoId) {
        loadingVideoId = videoId;
        dispatch(videoId, true);
    }

    /** Called when audio output is confirmed (mp.start() succeeded). */
    public static void notifyAudioConfirmed(@NonNull String videoId) {
        if (videoId.equals(loadingVideoId)) {
            loadingVideoId = null;
        }
        dispatch(videoId, false);
    }

    /** Clear loading state without notifying (e.g. when track is offline/instant). */
    public static void clearLoading() {
        loadingVideoId = null;
    }

    /** Returns the videoId currently in loading state, or null if none. */
    @Nullable
    public static String getLoadingVideoId() {
        return loadingVideoId;
    }

    /** Check if a specific videoId is currently loading. */
    public static boolean isLoading(@Nullable String videoId) {
        if (videoId == null || videoId.isEmpty()) return false;
        return videoId.equals(loadingVideoId);
    }

    private static void dispatch(@NonNull String videoId, boolean loading) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (Listener l : listeners) {
                try {
                    l.onPlaybackLoadingStateChanged(videoId, loading);
                } catch (Exception ignored) {
                }
            }
        } else {
            mainHandler.post(() -> {
                for (Listener l : listeners) {
                    try {
                        l.onPlaybackLoadingStateChanged(videoId, loading);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }
}
