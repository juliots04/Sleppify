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
    /** True while the incoming source is being resolved/prepared, BEFORE the fade starts.
     *  Blocks onProgressTick from re-arming a transition every ~250ms (which used to spam
     *  the resolver executor with duplicate NewPipe resolutions of the same video). */
    private boolean armed = false;
    /** True after a transition was aborted (resolution/prepare failed, or no playable next
     *  source). Blocks re-arming until {@link #cancel()} — which fires on every track change,
     *  pause and seek — so the outgoing track simply finishes at full volume and the
     *  fragment's natural-completion path advances the queue (including offline skipping). */
    private boolean suppressed = false;
    /** Bumped on every cancel/reset. Async resolution/prepare callbacks capture the value at
     *  submit time and abort if it changed — otherwise a resolution finishing after the user
     *  paused or skipped would start a crossfade on a player that is no longer active. */
    private long generation = 0L;
    @Nullable
    private ExoMediaPlayer incomingPlayer;
    /** Incoming player while it is buffering, before the fade begins. */
    @Nullable
    private ExoMediaPlayer preparingPlayer;
    @Nullable
    private Runnable prepareTimeoutRunnable;
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

    /** True if the running crossfade is consuming the given player as its incoming side. */
    public boolean isUsingPlayer(@Nullable ExoMediaPlayer player) {
        return player != null && inProgress && incomingPlayer == player;
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
                || armed
                || suppressed
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
            // Genuine end of playback (repeat-one park or end of queue): fade out gracefully.
            fadeOutOnly(outgoing, crossfadeDurationMs);
            return;
        }

        SongPlayerFragment.PlayerTrack nextTrack = tracks.get(nextIndex);
        if (nextTrack == null || TextUtils.isEmpty(nextTrack.videoId)) {
            // Unplayable queue entry: do NOT fade out (that used to release the player and
            // leave the UI stuck on "playing"). Let the track end naturally so the
            // fragment's completion path advances and skips it.
            abortTransitionUntilCancel();
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
                crossfadeWithSource(outgoing, nextIndex, nextTrack.videoId, contentUri, false, crossfadeDurationMs);
                return;
            }
        }

        // 3. Offline files
        if (appContext != null && OfflineAudioStore.hasValidatedOfflineAudio(appContext, nextTrack.videoId, nextTrack.duration)) {
            File nextFile = OfflineAudioStore.getExistingOfflineAudioFile(appContext, nextTrack.videoId);
            if (nextFile != null && nextFile.isFile() && nextFile.length() > 0L) {
                crossfadeWithSource(outgoing, nextIndex, nextTrack.videoId, nextFile.getAbsolutePath(), false, crossfadeDurationMs);
                return;
            }
        }

        // 4. Prefetched URL
        if (TextUtils.equals(nextTrack.videoId, prefetchedNextVideoId) && !TextUtils.isEmpty(prefetchedNextUrl)) {
            crossfadeWithSource(outgoing, nextIndex, nextTrack.videoId, prefetchedNextUrl, true, crossfadeDurationMs);
            return;
        }

        // 5. Resolve online URL on background thread. `armed` blocks the ~250ms ticker from
        // re-submitting this resolution while it is in flight (it used to queue dozens of
        // duplicate NewPipe extractions, starving the shared executor that prebuffer uses).
        if (networkAvailable && sourceResolver != null && resolverExecutor != null && appContext != null) {
            final ExoMediaPlayer fadeOutgoing = outgoing;
            final int fadeNextIndex = nextIndex;
            final int fadeDuration = crossfadeDurationMs;
            final String videoId = nextTrack.videoId;
            final long gen = generation;
            armed = true;
            resolverExecutor.submit(() -> {
                String resolved = sourceResolver.resolveStreamUrl(appContext, videoId);
                handler.post(() -> {
                    // The transition was cancelled (track change / pause / seek) while
                    // resolving — do not start a crossfade on a player that moved on.
                    if (gen != generation || inProgress) return;
                    if (!TextUtils.isEmpty(resolved)) {
                        crossfadeWithSource(fadeOutgoing, fadeNextIndex, videoId, resolved, true, fadeDuration);
                    } else {
                        // Resolution failed: keep playing at full volume; natural completion
                        // will advance the queue without crossfade.
                        abortTransitionUntilCancel();
                    }
                });
            });
            return;
        }

        // No network and the next track has no local/offline source: let the track finish
        // naturally — the fragment's completion path skips to the next offline-available
        // track. Fading out here used to kill playback entirely in offline mode.
        abortTransitionUntilCancel();
    }

    /** Aborts the pending transition without fading. The outgoing track keeps playing at
     *  full volume to its natural end; re-arming stays blocked until {@link #cancel()}. */
    private void abortTransitionUntilCancel() {
        armed = false;
        suppressed = true;
        if (prepareTimeoutRunnable != null) {
            handler.removeCallbacks(prepareTimeoutRunnable);
            prepareTimeoutRunnable = null;
        }
        if (preparingPlayer != null) {
            releaseSingle(preparingPlayer);
            preparingPlayer = null;
        }
    }

    private void crossfadeWithPlayer(
            @NonNull ExoMediaPlayer outgoing,
            int nextIndex,
            @NonNull ExoMediaPlayer preBuffered,
            int crossfadeDurationMs
    ) {
        preBuffered.isCrossfadeComponent = true;

        // Replace the listeners the fragment's prebuffer phase installed: its error listener
        // released the player unconditionally, which mid-fade would have destroyed the
        // manager's incoming side without the manager noticing — completeCrossfade would
        // then hand a RELEASED player to the fragment (silence, UI stuck on "playing").
        final long gen = generation;
        preBuffered.setOnPreparedListener(null);
        preBuffered.setOnErrorListener((mp, what, extra) -> {
            if (gen != generation) {
                releaseSingle(mp);
                return true;
            }
            Log.w(TAG, "crossfadeWithPlayer: incoming player error what=" + what);
            if (inProgress && incomingPlayer == mp) {
                // Error mid-fade: cancel the fade and restore the outgoing track so the
                // music does not die at reduced volume.
                cancelAndRestoreVolume(outgoing);
                suppressed = true;
            } else {
                abortTransitionUntilCancel();
            }
            if (callback != null) {
                callback.onCrossfadeFailed(nextIndex);
            }
            return true;
        });

        try {
            preBuffered.setVolume(0f, 0f);
            preBuffered.start();
        } catch (Exception e) {
            Log.e(TAG, "crossfadeWithPlayer: start failed", e);
            releaseSingle(preBuffered);
            // Keep playing at full volume; natural completion advances the queue.
            abortTransitionUntilCancel();
            return;
        }

        beginFade(outgoing, preBuffered, nextIndex, false, crossfadeDurationMs);
    }

    private void crossfadeWithSource(
            @NonNull ExoMediaPlayer outgoing,
            int nextIndex,
            @NonNull String videoId,
            @NonNull String source,
            boolean isNetworkSource,
            int crossfadeDurationMs
    ) {
        if (appContext == null) {
            abortTransitionUntilCancel();
            return;
        }

        final ExoMediaPlayer incoming = new ExoMediaPlayer(appContext);
        incoming.isCrossfadeComponent = true;
        final long gen = generation;
        try {
            incoming.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (isNetworkSource) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", STREAM_HTTP_USER_AGENT);
                headers.put("Accept", "*/*");
                // Inject exact headers resolved by StreamResolver for this videoId
                Map<String, String> streamHeaders = StreamResolver.getHeadersFor(videoId);
                if (streamHeaders != null) {
                    headers.putAll(streamHeaders);
                }
                incoming.setDataSource(appContext, Uri.parse(source), headers);
            } else if (source.startsWith("content://")) {
                incoming.setDataSource(appContext, Uri.parse(source), null);
            } else {
                incoming.setDataSource(source);
            }

            // Do NOT begin the fade yet: prepare() is asynchronous, and ramping the outgoing
            // volume down while the incoming player is still buffering produced an audible
            // fade-to-silence with the next track popping in late ("crossfade missing").
            // The fade starts in onPrepared, when the incoming player can actually play.
            armed = true;
            preparingPlayer = incoming;

            incoming.setOnPreparedListener(mp -> {
                if (gen != generation || preparingPlayer != mp) {
                    // Cancelled while buffering (track change / pause / seek).
                    releaseSingle(mp);
                    return;
                }
                if (prepareTimeoutRunnable != null) {
                    handler.removeCallbacks(prepareTimeoutRunnable);
                    prepareTimeoutRunnable = null;
                }
                preparingPlayer = null;
                armed = false;
                beginFade(outgoing, mp, nextIndex, isNetworkSource, crossfadeDurationMs);
            });

            incoming.setOnErrorListener((mp, what, extra) -> {
                if (gen != generation) {
                    releaseSingle(mp);
                    return true;
                }
                Log.w(TAG, "crossfadeWithSource: incoming player error what=" + what);
                if (inProgress && incomingPlayer == mp) {
                    // Error mid-fade: cancel the fade and restore the outgoing track so the
                    // music does not die at reduced volume.
                    cancelAndRestoreVolume(outgoing);
                    suppressed = true;
                } else {
                    abortTransitionUntilCancel();
                }
                if (callback != null) {
                    callback.onCrossfadeFailed(nextIndex);
                }
                return true;
            });

            incoming.prepare();
            incoming.setVolume(0f, 0f);
            incoming.start();

            // Buffering watchdog: if the incoming source is not ready in time, give up on
            // the crossfade (natural completion advances the queue without one).
            prepareTimeoutRunnable = () -> {
                prepareTimeoutRunnable = null;
                if (gen != generation || preparingPlayer != incoming) return;
                Log.w(TAG, "crossfadeWithSource: incoming player not ready in time — aborting fade");
                abortTransitionUntilCancel();
            };
            handler.postDelayed(prepareTimeoutRunnable, Math.min(crossfadeDurationMs, 5_000));
        } catch (Exception e) {
            Log.e(TAG, "crossfadeWithSource: failed to prepare incoming player", e);
            preparingPlayer = null;
            releaseSingle(incoming);
            abortTransitionUntilCancel();
            if (callback != null) {
                callback.onCrossfadeFailed(nextIndex);
            }
        }
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

        // Keep the outgoing player's completion listener attached: the fragment's completion
        // handler already ignores completions while isInProgress(). Nulling it here meant
        // that after a cancelled crossfade (seek back, pause) the track could reach its end
        // and never advance because nobody restored the listener.
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
                } catch (Exception e) {
                    android.util.Log.w("CrossfadeManager", "Failed to set outgoing volume", e);
                }
                if (incomingPlayer != null) {
                    try {
                        incomingPlayer.setVolume(progress, progress);
                    } catch (Exception e) {
                        android.util.Log.w("CrossfadeManager", "Failed to set incoming volume", e);
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
        if (ticker != null) {
            handler.removeCallbacks(ticker);
        }
        if (prepareTimeoutRunnable != null) {
            handler.removeCallbacks(prepareTimeoutRunnable);
        }

        if (incomingPlayer != null) {
            releaseSingle(incomingPlayer);
        }
        if (preparingPlayer != null) {
            releaseSingle(preparingPlayer);
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
            } catch (Exception e) {
                android.util.Log.w("CrossfadeManager", "Failed to restore volume", e);
            }
        }
    }

    // --- Helpers ---

    private void resetState() {
        generation++;
        inProgress = false;
        armed = false;
        suppressed = false;
        isNetwork = false;
        startedAtMs = 0L;
        targetIndex = -1;
        ticker = null;
        incomingPlayer = null;
        preparingPlayer = null;
        prepareTimeoutRunnable = null;
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
        } catch (Exception e) {
            android.util.Log.w("CrossfadeManager", "Failed to stop player", e);
        }
        try {
            player.release();
        } catch (Exception e) {
            android.util.Log.w("CrossfadeManager", "Failed to release player", e);
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
