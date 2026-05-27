package com.example.sleppify;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Centralized crossfade manager that handles audio crossfade transitions between tracks.
 * Applies to all source types: offline files, local device files, content URIs, network streams,
 * prefetched URLs, and gapless pre-buffered players.
 *
 * For video (mp4) sources, only the audio crossfades — the video surface stays on the
 * outgoing track until the transition completes.
 */
public final class CrossfadeManager {

    private static final String TAG = "CrossfadeManager";

    private static final int CROSSFADE_MAX_SECONDS = 12;
    private static final int CROSSFADE_DEFAULT_SECONDS = 6;
    private static final int CROSSFADE_FIRST_STEP_MS = 500;
    private static final int CROSSFADE_STEP_MS = 40;

    private static final String STREAM_HTTP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public interface Callback {
        /** Called on main thread when crossfade finishes and the incoming player is the new active player. */
        void onCrossfadeFinished(@NonNull ExoMediaPlayer incomingPlayer, int nextIndex, boolean wasNetwork);

        /** Called when a fade-out-only transition ends (no incoming track). */
        void onFadeOutFinished();

        /** Called when crossfade fails to prepare the incoming player. */
        void onCrossfadeFailed(int nextIndex);
    }

    public interface NextTrackSourceResolver {
        /**
         * Resolves a playback source URL for the given track on a background thread.
         * Returns null or empty string on failure.
         */
        @Nullable
        String resolveStreamUrl(@NonNull Context context, @NonNull String videoId);
    }

    // --- State ---
    @Nullable
    private Context appContext;
    @Nullable
    private SharedPreferences settingsPrefs;
    @Nullable
    private Callback callback;
    @Nullable
    private NextTrackSourceResolver sourceResolver;
    @Nullable
    private ExecutorService resolverExecutor;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean inProgress = false;
    @Nullable
    private ExoMediaPlayer incomingPlayer;
    private int targetIndex = -1;
    private boolean isNetwork = false;
    private long startedAtMs = 0L;
    @Nullable
    private Runnable ticker;

    private int cachedDurationMs = -1;

    // --- Setup ---

    public void attach(
            @NonNull Context appContext,
            @Nullable SharedPreferences settingsPrefs,
            @NonNull Callback callback,
            @NonNull NextTrackSourceResolver sourceResolver,
            @NonNull ExecutorService resolverExecutor
    ) {
        this.appContext = appContext.getApplicationContext();
        this.settingsPrefs = settingsPrefs;
        this.callback = callback;
        this.sourceResolver = sourceResolver;
        this.resolverExecutor = resolverExecutor;
    }

    public void updateSettingsPrefs(@Nullable SharedPreferences prefs) {
        this.settingsPrefs = prefs;
    }

    // --- Queries ---

    public boolean isInProgress() {
        return inProgress;
    }

    public int getCrossfadeDurationMs() {
        if (cachedDurationMs >= 0) {
            return cachedDurationMs;
        }
        int seconds = CROSSFADE_DEFAULT_SECONDS;
        if (settingsPrefs != null) {
            seconds = settingsPrefs.getInt(
                    CloudSyncManager.KEY_OFFLINE_CROSSFADE_SECONDS,
                    CROSSFADE_DEFAULT_SECONDS
            );
        }
        seconds = Math.max(0, Math.min(CROSSFADE_MAX_SECONDS, seconds));
        int result;
        if (seconds <= 0) {
            result = 0;
        } else if (seconds == 1) {
            result = CROSSFADE_FIRST_STEP_MS;
        } else {
            result = seconds * 1000;
        }
        cachedDurationMs = result;
        return result;
    }

    public void invalidateDurationCache() {
        cachedDurationMs = -1;
    }

    // --- Core: called from progress ticker ---

    /**
     * Called from the progress ticker every ~250ms. Checks if crossfade should start
     * based on remaining playback time.
     */
    public void onProgressTick(
            int positionMs,
            int durationMs,
            @Nullable ExoMediaPlayer currentPlayer,
            boolean isPlaying,
            boolean localSourcePreparing,
            boolean userSeeking,
            @NonNull List<SongPlayerFragment.PlayerTrack> tracks,
            int currentIndex,
            int repeatMode,
            boolean networkAvailable,
            @Nullable ExoMediaPlayer gaplessPreBufferedPlayer,
            @NonNull String gaplessPreBufferedVideoId,
            @Nullable String prefetchedNextVideoId,
            @Nullable String prefetchedNextUrl
    ) {
        int crossfadeDurationMs = getCrossfadeDurationMs();
        if (currentPlayer == null
                || localSourcePreparing
                || inProgress
                || userSeeking
                || !isPlaying
                || durationMs <= 0
                || crossfadeDurationMs <= 0) {
            return;
        }

        int remainingMs = Math.max(0, durationMs - Math.max(0, positionMs));
        if (remainingMs <= crossfadeDurationMs * 2) {
            Log.d(TAG, "[CROSSFADE_DBG] tick remaining=" + remainingMs + " cfDur=" + crossfadeDurationMs + " pos=" + positionMs + " dur=" + durationMs + " inProgress=" + inProgress);
        }
        if (remainingMs > crossfadeDurationMs) {
            return;
        }

        startTransition(
                currentPlayer,
                crossfadeDurationMs,
                tracks,
                currentIndex,
                repeatMode,
                networkAvailable,
                gaplessPreBufferedPlayer,
                gaplessPreBufferedVideoId,
                prefetchedNextVideoId,
                prefetchedNextUrl
        );
    }

    // --- Transition logic ---

    private void startTransition(
            @NonNull ExoMediaPlayer outgoing,
            int crossfadeDurationMs,
            @NonNull List<SongPlayerFragment.PlayerTrack> tracks,
            int currentIndex,
            int repeatMode,
            boolean networkAvailable,
            @Nullable ExoMediaPlayer gaplessPreBufferedPlayer,
            @NonNull String gaplessPreBufferedVideoId,
            @Nullable String prefetchedNextVideoId,
            @Nullable String prefetchedNextUrl
    ) {
        if (tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        int nextIndex = resolveNextIndex(tracks, currentIndex, repeatMode);
        if (nextIndex < 0 || nextIndex >= tracks.size()) {
            fadeOutOnly(outgoing, crossfadeDurationMs);
            return;
        }

        SongPlayerFragment.PlayerTrack nextTrack = tracks.get(nextIndex);
        if (nextTrack == null || TextUtils.isEmpty(nextTrack.videoId)) {
            fadeOutOnly(outgoing, crossfadeDurationMs);
            return;
        }

        // 1. Use gapless pre-buffered player if available
        if (gaplessPreBufferedPlayer != null
                && TextUtils.equals(gaplessPreBufferedVideoId, nextTrack.videoId)) {
            crossfadeWithPlayer(outgoing, nextIndex, gaplessPreBufferedPlayer, crossfadeDurationMs);
            return;
        }

        // 2. Local device files
        if (appContext != null && LocalFilesStore.isLocalVideoId(nextTrack.videoId)) {
            String contentUri = LocalFilesStore.getContentUriForVideoId(appContext, nextTrack.videoId);
            if (contentUri != null && !contentUri.isEmpty()) {
                crossfadeWithSource(outgoing, nextIndex, contentUri, false, crossfadeDurationMs);
                return;
            }
        }

        // 3. Offline files
        if (appContext != null && OfflineAudioStore.hasValidatedOfflineAudio(appContext, nextTrack.videoId, nextTrack.duration)) {
            File nextFile = OfflineAudioStore.getExistingOfflineAudioFile(appContext, nextTrack.videoId);
            if (nextFile != null && nextFile.isFile() && nextFile.length() > 0L) {
                crossfadeWithSource(outgoing, nextIndex, nextFile.getAbsolutePath(), false, crossfadeDurationMs);
                return;
            }
        }

        // 4. Prefetched URL
        if (TextUtils.equals(nextTrack.videoId, prefetchedNextVideoId) && !TextUtils.isEmpty(prefetchedNextUrl)) {
            crossfadeWithSource(outgoing, nextIndex, prefetchedNextUrl, true, crossfadeDurationMs);
            return;
        }

        // 5. Resolve online URL on background thread
        if (networkAvailable && sourceResolver != null && resolverExecutor != null && appContext != null) {
            final ExoMediaPlayer fadeOutgoing = outgoing;
            final int fadeNextIndex = nextIndex;
            final int fadeDuration = crossfadeDurationMs;
            final String videoId = nextTrack.videoId;
            resolverExecutor.submit(() -> {
                String resolved = sourceResolver.resolveStreamUrl(appContext, videoId);
                handler.post(() -> {
                    if (inProgress) return;
                    if (!TextUtils.isEmpty(resolved)) {
                        crossfadeWithSource(fadeOutgoing, fadeNextIndex, resolved, true, fadeDuration);
                    } else {
                        fadeOutOnly(fadeOutgoing, fadeDuration);
                    }
                });
            });
            return;
        }

        fadeOutOnly(outgoing, crossfadeDurationMs);
    }

    private void crossfadeWithPlayer(
            @NonNull ExoMediaPlayer outgoing,
            int nextIndex,
            @NonNull ExoMediaPlayer preBuffered,
            int crossfadeDurationMs
    ) {
        preBuffered.isCrossfadeComponent = true;
        try {
            preBuffered.setVolume(0f, 0f);
            preBuffered.start();
        } catch (Exception e) {
            Log.e(TAG, "crossfadeWithPlayer: start failed", e);
            releaseSingle(preBuffered);
            fadeOutOnly(outgoing, crossfadeDurationMs);
            return;
        }

        beginFade(outgoing, preBuffered, nextIndex, false, crossfadeDurationMs);
    }

    private void crossfadeWithSource(
            @NonNull ExoMediaPlayer outgoing,
            int nextIndex,
            @NonNull String source,
            boolean isNetworkSource,
            int crossfadeDurationMs
    ) {
        if (appContext == null) {
            fadeOutOnly(outgoing, crossfadeDurationMs);
            return;
        }

        ExoMediaPlayer incoming = new ExoMediaPlayer(appContext);
        incoming.isCrossfadeComponent = true;
        try {
            incoming.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (isNetworkSource) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", STREAM_HTTP_USER_AGENT);
                headers.put("Accept", "*/*");
                incoming.setDataSource(appContext, Uri.parse(source), headers);
            } else if (source.startsWith("content://")) {
                incoming.setDataSource(appContext, Uri.parse(source), null);
            } else {
                incoming.setDataSource(source);
            }
            incoming.prepare();
            incoming.setVolume(0f, 0f);
            incoming.start();
        } catch (Exception e) {
            Log.e(TAG, "crossfadeWithSource: failed to prepare incoming player", e);
            releaseSingle(incoming);
            if (callback != null) {
                callback.onCrossfadeFailed(nextIndex);
            }
            return;
        }

        beginFade(outgoing, incoming, nextIndex, isNetworkSource, crossfadeDurationMs);
    }

    private void beginFade(
            @NonNull ExoMediaPlayer outgoing,
            @NonNull ExoMediaPlayer incoming,
            int nextIndex,
            boolean networkSource,
            int crossfadeDurationMs
    ) {
        this.incomingPlayer = incoming;
        this.inProgress = true;
        this.isNetwork = networkSource;
        this.targetIndex = nextIndex;
        this.startedAtMs = SystemClock.elapsedRealtime();

        outgoing.setOnCompletionListener(null);
        this.ticker = buildTicker(outgoing, crossfadeDurationMs);
        handler.post(this.ticker);
    }

    private void fadeOutOnly(@NonNull ExoMediaPlayer outgoing, int fadeDurationMs) {
        if (inProgress) {
            return;
        }
        this.incomingPlayer = null;
        this.inProgress = true;
        this.targetIndex = -1;
        this.startedAtMs = SystemClock.elapsedRealtime();
        this.ticker = buildTicker(outgoing, Math.max(1, fadeDurationMs));
        handler.post(this.ticker);
    }

    @NonNull
    private Runnable buildTicker(@NonNull ExoMediaPlayer outgoing, int crossfadeDurationMs) {
        return new Runnable() {
            @Override
            public void run() {
                if (!inProgress) {
                    return;
                }
                float progress = Math.min(1f, Math.max(0f,
                        (SystemClock.elapsedRealtime() - startedAtMs) / (float) crossfadeDurationMs));
                try {
                    outgoing.setVolume(1f - progress, 1f - progress);
                } catch (Exception ignored) {
                }
                if (incomingPlayer != null) {
                    try {
                        incomingPlayer.setVolume(progress, progress);
                    } catch (Exception ignored) {
                    }
                }
                if (progress >= 1f) {
                    completeCrossfade(outgoing);
                    return;
                }
                handler.postDelayed(this, CROSSFADE_STEP_MS);
            }
        };
    }

    private void completeCrossfade(@NonNull ExoMediaPlayer outgoing) {
        ExoMediaPlayer incoming = this.incomingPlayer;
        int nextIdx = this.targetIndex;
        boolean wasNet = this.isNetwork;

        resetState();
        releaseSingle(outgoing);

        if (incoming == null || nextIdx < 0) {
            if (callback != null) {
                callback.onFadeOutFinished();
            }
            return;
        }

        incoming.isCrossfadeComponent = false;
        if (callback != null) {
            callback.onCrossfadeFinished(incoming, nextIdx, wasNet);
        }
    }

    // --- Cancel ---

    public void cancel() {
        boolean wasInProgress = inProgress;

        if (ticker != null) {
            handler.removeCallbacks(ticker);
        }

        if (incomingPlayer != null) {
            releaseSingle(incomingPlayer);
        }

        resetState();
    }

    /**
     * Cancels crossfade and restores the current player's volume to full.
     */
    public void cancelAndRestoreVolume(@Nullable ExoMediaPlayer currentPlayer) {
        cancel();
        if (currentPlayer != null) {
            try {
                currentPlayer.setVolume(1f, 1f);
            } catch (Exception ignored) {
            }
        }
    }

    // --- Helpers ---

    private void resetState() {
        inProgress = false;
        isNetwork = false;
        startedAtMs = 0L;
        targetIndex = -1;
        ticker = null;
        incomingPlayer = null;
    }

    private int resolveNextIndex(
            @NonNull List<SongPlayerFragment.PlayerTrack> tracks,
            int currentIndex,
            int repeatMode
    ) {
        if (tracks.isEmpty()) {
            return -1;
        }
        if (repeatMode == SongPlayerFragment.REPEAT_MODE_ONE) {
            return -1;
        }
        if (currentIndex < tracks.size() - 1) {
            return currentIndex + 1;
        }
        if (repeatMode == SongPlayerFragment.REPEAT_MODE_ALL) {
            return 0;
        }
        return -1;
    }

    private void releaseSingle(@Nullable ExoMediaPlayer player) {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        cancel();
        appContext = null;
        settingsPrefs = null;
        callback = null;
        sourceResolver = null;
        resolverExecutor = null;
    }
}
