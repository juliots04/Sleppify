package com.example.sleppify;

import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
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
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
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
    private final android.widget.ProgressBar pbSeekBarLoading;
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
    private boolean playbackLoading;
    private boolean artworkLoading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable progressTicker;
    @NonNull private String lastArtVideoId = "";
    @NonNull private String lastArtUrl = "";
    private int lastProgressValue = -1;
    private boolean animatingOut = false;
    private boolean coldStart = true;

    public GlobalMiniPlayerController(@NonNull MainActivity activity) {
        this.activity = activity;
        llMiniPlayer = activity.findViewById(R.id.llGlobalMiniPlayer);
        ivArt = activity.findViewById(R.id.ivGlobalMiniPlayerArt);
        pbMiniLoading = activity.findViewById(R.id.pbMiniPlayerLoading);
        pbSeekBarLoading = activity.findViewById(R.id.pbGlobalMiniPlayerSeekBarLoading);
        tvTitle = activity.findViewById(R.id.tvGlobalMiniPlayerTitle);
        tvTitle.setSelected(true);
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
        if (activity.isSongPlayerVisible()) return;
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

        // Don't update while exit animation is playing (prevents artwork flash)
        if (animatingOut) return;

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
            coldStart = false;
            totalSeconds = Math.max(1, songPlayer.externalGetTotalSeconds());
            currentSeconds = Math.max(0, songPlayer.externalGetCurrentSeconds());
            title = songPlayer.externalGetCurrentTitle();
            subtitle = songPlayer.externalGetCurrentArtist();
            imageUrl = songPlayer.externalGetCurrentImageUrl();
            miniPlaying = songPlayer.externalIsPlaying();
            playbackLoading = songPlayer.externalIsLoading();
        } else if (snapshotTrack != null) {
            // On cold start, don't show mini-player from snapshot alone;
            // wait until the player is actually attached and ready.
            if (coldStart) {
                llMiniPlayer.setVisibility(View.GONE);
                return;
            }
            title = TextUtils.isEmpty(snapshotTrack.title) ? "Última reproducción" : snapshotTrack.title;
            subtitle = snapshotTrack.artist;
            imageUrl = snapshotTrack.imageUrl;
            miniPlaying = snapshot.isPlaying;
            totalSeconds = Math.max(1, snapshot.totalSeconds);
            currentSeconds = 0;
            // If there's no live player, treat as paused
            if (!playerAttached) miniPlaying = false;
            playbackLoading = false;
        } else {
            if (llMiniPlayer.getVisibility() != View.GONE) {
                llMiniPlayer.setVisibility(View.GONE);
            }
            miniPlaying = false;
            playbackLoading = false;
            return;
        }

        // Show with slide-up animation if currently hidden
        if (llMiniPlayer.getVisibility() != View.VISIBLE) {
            float distance = llMiniPlayer.getHeight() > 0 ? llMiniPlayer.getHeight() : 300f;
            llMiniPlayer.setTranslationY(distance);
            llMiniPlayer.setVisibility(View.VISIBLE);
            llMiniPlayer.animate().cancel();
            ensureNavAboveMiniPlayer();
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

        updateMiniLoadingVisibility();
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
        animatingOut = true;
        llMiniPlayer.animate().cancel();
        ensureNavAboveMiniPlayer();
        float distance = llMiniPlayer.getHeight() > 0 ? llMiniPlayer.getHeight() : 300f;
        llMiniPlayer.animate()
                .translationY(distance)
                .setDuration(250)
                .setInterpolator(MATERIAL_EASE)
                .withEndAction(() -> {
                    llMiniPlayer.setVisibility(View.GONE);
                    animatingOut = false;
                })
                .start();
    }

    public boolean isAnimatingOut() {
        return animatingOut;
    }

    /**
     * Ensures the bottom navigation bar draws above the mini-player
     * during translationY animations (some GPUs ignore static elevation
     * for views being animated).
     */
    private void ensureNavAboveMiniPlayer() {
        View nav = activity.findViewById(R.id.bottomNavigation);
        if (nav != null) {
            nav.bringToFront();
            nav.setTranslationZ(50f);
            if (nav.getParent() instanceof View) {
                ((View) nav.getParent()).requestLayout();
            }
        }
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

        injectOriginalQueueFromSnapshot(playerFragment, snapshot);

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
            injectOriginalQueueFromSnapshot(existingPlayer, snapshot);
            activity.openSongPlayer();
            return;
        }

        if (activity.getSupportFragmentManager().isStateSaved()) return;

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids, titles, artists, durations, images,
                snapshotIndex, startPlaying
        );

        injectOriginalQueueFromSnapshot(playerFragment, snapshot);

        activity.getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .runOnCommit(playerFragment::externalAnimateEnterSlide)
                .commit();
    }

    private void injectOriginalQueueFromSnapshot(
            @NonNull SongPlayerFragment player,
            @NonNull PlaybackHistoryStore.Snapshot snapshot
    ) {
        if (snapshot.originalQueue == null || snapshot.originalQueue.isEmpty()) return;
        // Only inject if originalQueue differs from queue (shuffle was active when persisted)
        if (snapshot.originalQueue.size() == snapshot.queue.size()) {
            boolean same = true;
            for (int i = 0; i < snapshot.queue.size(); i++) {
                if (!android.text.TextUtils.equals(snapshot.queue.get(i).videoId, snapshot.originalQueue.get(i).videoId)) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }
        java.util.List<SongPlayerFragment.PlayerTrack> original = new java.util.ArrayList<>(snapshot.originalQueue.size());
        for (PlaybackHistoryStore.QueueTrack item : snapshot.originalQueue) {
            original.add(new SongPlayerFragment.PlayerTrack(
                    item.videoId, item.title, item.artist, item.duration, item.imageUrl
            ));
        }
        player.externalSetOriginalQueueOrder(original);
    }

    // ── PlaybackLoadingBus.Listener ─────────────────────────────────────

    @Override
    public void onPlaybackLoadingStateChanged(@NonNull String videoId, boolean loading) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (loading) {
            playbackLoading = true;
            miniPlaying = true;
            btnPlayPause.setImageResource(R.drawable.ic_mini_pause);
        } else {
            playbackLoading = false;
        }
        updateMiniLoadingVisibility();
    }

    private void syncMiniLoadingState() {
        String loadingId = PlaybackLoadingBus.getLoadingVideoId();
        playbackLoading = loadingId != null;
        updateMiniLoadingVisibility();
    }

    private void updateMiniLoadingVisibility() {
        boolean show = playbackLoading || artworkLoading;
        if (pbMiniLoading != null) pbMiniLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (pbSeekBarLoading != null) pbSeekBarLoading.setVisibility(playbackLoading ? View.VISIBLE : View.GONE);
        if (ivArt != null) {
            ivArt.animate().cancel();
            if (show) {
                ivArt.setAlpha(0f);
            } else {
                ivArt.animate().alpha(1f).setDuration(250).start();
            }
        }
    }

    // ── Artwork ─────────────────────────────────────────────────────────

    private void loadArtwork(@Nullable String imageUrl) {
        if (TextUtils.isEmpty(imageUrl) || activity.isFinishing() || activity.isDestroyed()) {
            if (ivArt != null) {
                ivArt.setImageDrawable(null);
            }
            artworkLoading = false;
            updateMiniLoadingVisibility();
            return;
        }
        try {
            artworkLoading = true;
            updateMiniLoadingVisibility();
            Glide.with(activity)
                    .load(imageUrl.trim())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            artworkLoading = false;
                            updateMiniLoadingVisibility();
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                            artworkLoading = false;
                            updateMiniLoadingVisibility();
                            return false;
                        }
                    })
                    .transform(SHARED_YT_CROP)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(320, 320)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivArt);
        } catch (Exception e) {
            android.util.Log.w("MiniPlayer", "Failed to load artwork", e);
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
