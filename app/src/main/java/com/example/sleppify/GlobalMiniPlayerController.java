package com.example.sleppify;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.sleppify.utils.YouTubeCropTransformation;

/**
 * Centralised mini-player that lives in {@code activity_main.xml}.
 * Replaces the per-fragment duplicates in MusicPlayerFragment,
 * PlaylistDetailFragment and SearchFragment.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class GlobalMiniPlayerController implements PlaybackEventBus.Listener, PlaybackLoadingBus.Listener {

    private static final long PROGRESS_TICK_MS = 200L;
    private static final PathInterpolator MATERIAL_EASE =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final YouTubeCropTransformation SHARED_YT_CROP =
            new YouTubeCropTransformation();

    // Views (from activity_main.xml include)
    private final View llMiniPlayer;
    private final ImageView ivArt;
    private final android.widget.ProgressBar pbMiniLoading;
    private final TextView tvTitle;
    private final TextView tvSubtitle;
    private final ImageButton btnPlayPause;
    private final SeekBar sbProgress;
    private boolean videoAttached = false;
    private boolean videoOverlayActive = false;

    // Owner
    private final MainActivity activity;

    // State
    private boolean miniPlaying;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable progressTicker;
    @NonNull private String lastArtVideoId = "";
    @NonNull private String lastArtUrl = "";
    private int lastProgressValue = -1;

    public GlobalMiniPlayerController(@NonNull MainActivity activity) {
        this.activity = activity;
        llMiniPlayer = activity.findViewById(R.id.llGlobalMiniPlayer);
        ivArt = activity.findViewById(R.id.ivGlobalMiniPlayerArt);
        pbMiniLoading = activity.findViewById(R.id.pbMiniPlayerLoading);
        tvTitle = activity.findViewById(R.id.tvGlobalMiniPlayerTitle);
        tvSubtitle = activity.findViewById(R.id.tvGlobalMiniPlayerSubtitle);
        btnPlayPause = activity.findViewById(R.id.btnGlobalMiniPlayPause);
        sbProgress = activity.findViewById(R.id.sbGlobalMiniPlayerProgress);

        llMiniPlayer.setOnClickListener(v -> openFullPlayer());
        btnPlayPause.setOnClickListener(v -> togglePlayback());
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    public void onResume() {
        PlaybackEventBus.addListener(this);
        PlaybackLoadingBus.addListener(this);
        syncMiniLoadingState();
        updateUi();
        startProgressTicker();
    }

    public void onPause() {
        PlaybackEventBus.removeListener(this);
        PlaybackLoadingBus.removeListener(this);
        stopProgressTicker();
    }

    // ── PlaybackEventBus.Listener ─────────────────────────────────

    @Override
    public void onPlaybackSnapshotUpdated() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(this::updateUi);
    }

    // ── Player visibility coordination ────────────────────────────

    /**
     * Called by MainActivity when the full SongPlayerFragment becomes
     * visible or hidden. Hides/shows the mini-player accordingly.
     */
    public void onPlayerVisibilityChanged(boolean fullPlayerVisible) {
        if (fullPlayerVisible) {
            hide();
        } else {
            updateUi();
        }
    }

    // ── Core UI update ────────────────────────────────────────────

    public void updateUi() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (llMiniPlayer == null) return;

        // Hide when full player is visible
        if (activity.isSongPlayerVisible()) {
            llMiniPlayer.setVisibility(View.GONE);
            return;
        }

        // Only show mini-player in allowed screens
        if (!activity.isMiniPlayerAllowedForCurrentScreen()) {
            llMiniPlayer.setVisibility(View.GONE);
            return;
        }

        SongPlayerFragment songPlayer = activity.findSongPlayerFragment();
        boolean playerAttached = songPlayer != null && songPlayer.isAdded();
        PlaybackHistoryStore.Snapshot snapshot = PlaybackHistoryStore.load(activity);
        PlaybackHistoryStore.QueueTrack snapshotTrack = snapshot.currentTrack();

        String title;
        String subtitle;
        String imageUrl;
        int totalSeconds = 1;
        int currentSeconds = 0;

        if (playerAttached) {
            totalSeconds = Math.max(1, songPlayer.externalGetTotalSeconds());
            currentSeconds = Math.max(0, songPlayer.externalGetCurrentSeconds());
            title = songPlayer.externalGetCurrentTitle();
            subtitle = songPlayer.externalGetCurrentArtist();
            imageUrl = songPlayer.externalGetCurrentImageUrl();
            miniPlaying = songPlayer.externalIsPlaying();
        } else if (snapshotTrack != null) {
            title = TextUtils.isEmpty(snapshotTrack.title) ? "Última reproducción" : snapshotTrack.title;
            subtitle = snapshotTrack.artist;
            imageUrl = snapshotTrack.imageUrl;
            miniPlaying = snapshot.isPlaying;
            totalSeconds = Math.max(1, snapshot.totalSeconds);
            currentSeconds = 0;
            // If there's no live player, treat as paused
            if (!playerAttached) miniPlaying = false;
        } else {
            if (llMiniPlayer.getVisibility() != View.GONE) {
                llMiniPlayer.setVisibility(View.GONE);
            }
            miniPlaying = false;
            return;
        }

        // Show with slide-up animation if currently hidden
        if (llMiniPlayer.getVisibility() != View.VISIBLE) {
            float distance = llMiniPlayer.getHeight() > 0 ? llMiniPlayer.getHeight() : 300f;
            llMiniPlayer.setTranslationY(distance);
            llMiniPlayer.setVisibility(View.VISIBLE);
            llMiniPlayer.animate().cancel();
            llMiniPlayer.animate()
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(MATERIAL_EASE)
                    .withEndAction(null)
                    .start();
        }

        // Title / Subtitle
        if (title == null) title = "";
        if (subtitle == null) subtitle = "";
        if (!TextUtils.equals(tvTitle.getText(), title)) {
            tvTitle.setText(title);
        }
        if (!TextUtils.equals(tvSubtitle.getText(), subtitle)) {
            tvSubtitle.setText(subtitle);
        }

        // Play/Pause icon
        btnPlayPause.setImageResource(miniPlaying
                ? R.drawable.ic_mini_pause
                : R.drawable.ic_mini_play);

        // Progress bar
        int clampedCurrent = Math.max(0, Math.min(totalSeconds, currentSeconds));
        int progress = Math.round((clampedCurrent / (float) Math.max(1, totalSeconds)) * 1000f);
        int bounded = Math.max(0, Math.min(1000, progress));
        if (lastProgressValue != bounded) {
            sbProgress.setProgress(bounded);
            lastProgressValue = bounded;
        }

        // Artwork (skip if video overlay is active or video surface is attached)
        imageUrl = imageUrl == null ? "" : imageUrl.trim();
        String videoId = playerAttached ? songPlayer.externalGetCurrentVideoId() : (snapshotTrack != null ? snapshotTrack.videoId : "");
        if (!videoAttached && !videoOverlayActive) {
            if (!TextUtils.equals(lastArtVideoId, videoId)
                    || !TextUtils.equals(lastArtUrl, imageUrl)) {
                loadArtwork(imageUrl);
                lastArtVideoId = videoId;
                lastArtUrl = imageUrl;
            }
        } else {
            lastArtVideoId = videoId;
            lastArtUrl = imageUrl;
        }
    }

    // ── Actions ───────────────────────────────────────────────────

    private void togglePlayback() {
        SongPlayerFragment songPlayer = activity.findSongPlayerFragment();
        if (songPlayer != null && songPlayer.isAdded()) {
            songPlayer.externalTogglePlayback();
            // Use intent flag for immediate icon feedback — ExoPlayer may not
            // have started yet for online streams, so externalIsPlaying() would
            // return false even though the user just pressed play.
            miniPlaying = songPlayer.externalIsPlayingIntent();
            btnPlayPause.setImageResource(miniPlaying
                    ? R.drawable.ic_mini_pause
                    : R.drawable.ic_mini_play);
            return;
        }
        // No player attached — try to restore from snapshot
        PlaybackHistoryStore.Snapshot snapshot = PlaybackHistoryStore.load(activity);
        if (snapshot.isValid()) {
            startHiddenPlayerFromSnapshot(snapshot, true);
        }
    }

    private void openFullPlayer() {
        // Animate mini-player out
        animateOut();

        SongPlayerFragment existingPlayer = activity.findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            activity.openSongPlayer();
            return;
        }

        // No player — create one from snapshot
        PlaybackHistoryStore.Snapshot snapshot = PlaybackHistoryStore.load(activity);
        if (snapshot.isValid()) {
            openPlayerFromSnapshot(snapshot, snapshot.isPlaying);
        }
    }

    // ── Show / Hide ───────────────────────────────────────────────

    public void hide() {
        if (llMiniPlayer == null) return;
        llMiniPlayer.setVisibility(View.GONE);
    }

    /**
     * Slide-down exit animation (used before opening the full player).
     */
    public void animateOut() {
        if (llMiniPlayer == null) return;
        llMiniPlayer.animate().cancel();
        float distance = llMiniPlayer.getHeight() > 0 ? llMiniPlayer.getHeight() : 300f;
        llMiniPlayer.animate()
                .translationY(distance)
                .setDuration(250)
                .setInterpolator(MATERIAL_EASE)
                .withEndAction(() -> llMiniPlayer.setVisibility(View.GONE))
                .start();
    }

    // ── Progress ticker ───────────────────────────────────────────

    private void startProgressTicker() {
        stopProgressTicker();
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                SongPlayerFragment player = activity.findSongPlayerFragment();
                if (player == null || !player.isVisible()) {
                    updateUi();
                }
                handler.postDelayed(this, PROGRESS_TICK_MS);
            }
        };
        handler.post(progressTicker);
    }

    private void stopProgressTicker() {
        if (progressTicker != null) {
            handler.removeCallbacks(progressTicker);
            progressTicker = null;
        }
    }

    // ── Snapshot helpers ──────────────────────────────────────────

    private void startHiddenPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid()) return;

        SongPlayerFragment existingPlayer = activity.findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            if (startPlaying && !existingPlayer.externalIsPlaying()) {
                existingPlayer.externalTogglePlayback();
            }
            updateUi();
            return;
        }

        if (activity.getSupportFragmentManager().isStateSaved()) return;

        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        java.util.ArrayList<String> artists = new java.util.ArrayList<>();
        java.util.ArrayList<String> durations = new java.util.ArrayList<>();
        java.util.ArrayList<String> images = new java.util.ArrayList<>();
        for (PlaybackHistoryStore.QueueTrack item : snapshot.queue) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }
        if (ids.isEmpty()) return;

        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids, titles, artists, durations, images,
                snapshotIndex, startPlaying
        );

        activity.getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .runOnCommit(this::updateUi)
                .commit();
    }

    private void openPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid()) return;

        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        java.util.ArrayList<String> artists = new java.util.ArrayList<>();
        java.util.ArrayList<String> durations = new java.util.ArrayList<>();
        java.util.ArrayList<String> images = new java.util.ArrayList<>();
        for (PlaybackHistoryStore.QueueTrack item : snapshot.queue) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }
        if (ids.isEmpty()) return;

        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));

        SongPlayerFragment existingPlayer = activity.findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, snapshotIndex, startPlaying);
            activity.openSongPlayer();
            return;
        }

        if (activity.getSupportFragmentManager().isStateSaved()) return;

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids, titles, artists, durations, images,
                snapshotIndex, startPlaying
        );

        activity.getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .runOnCommit(playerFragment::externalAnimateEnterSlide)
                .commit();
    }

    // ── PlaybackLoadingBus.Listener ─────────────────────────────────────

    @Override
    public void onPlaybackLoadingStateChanged(@NonNull String videoId, boolean loading) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (loading) {
            if (pbMiniLoading != null) pbMiniLoading.setVisibility(View.VISIBLE);
            if (ivArt != null) {
                ivArt.animate().cancel();
                ivArt.setAlpha(0f);
            }
            miniPlaying = true;
            btnPlayPause.setImageResource(R.drawable.ic_mini_pause);
        } else {
            if (pbMiniLoading != null) pbMiniLoading.setVisibility(View.GONE);
            if (ivArt != null) {
                ivArt.animate().cancel();
                ivArt.animate().alpha(1f).setDuration(300).start();
            }
        }
    }

    private void syncMiniLoadingState() {
        String loadingId = PlaybackLoadingBus.getLoadingVideoId();
        if (loadingId != null) {
            if (pbMiniLoading != null) pbMiniLoading.setVisibility(View.VISIBLE);
            if (ivArt != null) ivArt.setAlpha(0f);
        } else {
            if (pbMiniLoading != null) pbMiniLoading.setVisibility(View.GONE);
            if (ivArt != null) ivArt.setAlpha(1f);
        }
    }

    // ── Artwork ─────────────────────────────────────────────────────────

    private void loadArtwork(@Nullable String imageUrl) {
        if (TextUtils.isEmpty(imageUrl) || activity.isFinishing() || activity.isDestroyed()) {
            ivArt.setImageDrawable(null);
            return;
        }
        try {
            Glide.with(activity)
                    .load(imageUrl.trim())
                    .transform(SHARED_YT_CROP)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(320, 320)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivArt);
        } catch (Exception ignored) {
        }
    }

    // ── Video state (managed by VideoSurfaceRouter) ───────────────────────

    public boolean isVideoAttached() {
        return videoAttached;
    }

    public void setVideoAttached(boolean attached) {
        this.videoAttached = attached;
    }

    @Nullable
    public ImageView getArtView() {
        return ivArt;
    }
}
