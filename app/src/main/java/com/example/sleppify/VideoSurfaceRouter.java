package com.example.sleppify;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

/**
 * Centralised video surface manager.
 * <p>
 * Owns a <b>single</b> {@link PlayerView} (SurfaceView) and physically reparents it
 * between the hero (full-player) and mini-player containers.  Because the same
 * SurfaceView is reused, ExoPlayer never rebuilds its video decoder pipeline —
 * eliminating all lag when opening/closing the full player.
 * <p>
 * All playback is video (proxy stream or offline mp4). No static detection needed.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoSurfaceRouter {

    private static final String TAG = "VideoSurfaceRouter";

    @Nullable private FrameLayout heroContainer;
    @Nullable private FrameLayout miniContainer;
    @Nullable private Context appContext;

    @Nullable private PlayerView playerView;

    private boolean videoActive = false;
    private boolean playerVisible = true;
    @Nullable private String activeVideoId;
    @Nullable private ExoMediaPlayer activePlayer;

    @Nullable private Callback callback;

    public interface Callback {
        void onVideoConfirmed();
    }

    // ── Init ───────────────────────────────────────────────────────────

    public void init(
            @NonNull Context context,
            @Nullable FrameLayout heroContainer,
            @Nullable FrameLayout miniContainer
    ) {
        this.appContext = context.getApplicationContext();
        this.heroContainer = heroContainer;
        this.miniContainer = miniContainer;

        if (playerView == null && appContext != null) {
            playerView = new PlayerView(appContext);
            playerView.setUseController(false);
            playerView.setBackgroundColor(Color.BLACK);
            playerView.setVisibility(View.GONE);
            playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    // ── Track lifecycle ────────────────────────────────────────────────

    /**
     * Called when a new track starts playback. Always attaches video surface.
     */
    public void onTrackStarted(
            @NonNull ExoMediaPlayer player,
            @NonNull String videoId
    ) {
        // If the new player wraps the same underlying ExoPlayer that is already
        // attached to the PlayerView, skip detach+reattach.  Re-assigning the
        // same ExoPlayer to its PlayerView forces a surface reconfiguration which
        // flushes decoder buffers and triggers a second STATE_READY → onPrepared
        // → mp.start() cycle (the "double start" bug).
        boolean sameUnderlying = activePlayer != null
                && player.getExoPlayer() != null
                && player.getExoPlayer() == activePlayer.getExoPlayer();

        if (sameUnderlying) {
            activePlayer = player;
            activeVideoId = videoId;
            videoActive = true;
            if (callback != null) callback.onVideoConfirmed();
            return;
        }

        detachAndHide();

        activePlayer = player;
        activeVideoId = videoId;
        videoActive = true;
        attachToCurrentContainer(player);
        if (callback != null) callback.onVideoConfirmed();
    }

    /**
     * Called when the ExoMediaPlayer is being released.
     */
    public void onPlayerReleased() {
        detachAndHide();
        videoActive = false;
        activeVideoId = null;
        activePlayer = null;
    }

    // ── Player visibility ──────────────────────────────────────────────

    public void onPlayerVisible() {
        playerVisible = true;
        if (!videoActive || playerView == null) return;

        removeFromParent(playerView);
        if (heroContainer != null) {
            heroContainer.addView(playerView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            playerView.setVisibility(View.VISIBLE);
        }
    }

    public void onPlayerHidden() {
        playerVisible = false;
        if (!videoActive || playerView == null) return;

        removeFromParent(playerView);
        if (miniContainer != null) {
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            miniContainer.addView(playerView);
            playerView.setVisibility(View.VISIBLE);
        }
    }

    // ── State queries ──────────────────────────────────────────────────

    public boolean isVideoActive() {
        return videoActive;
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void attachToCurrentContainer(@NonNull ExoMediaPlayer player) {
        if (playerView == null) return;

        removeFromParent(playerView);

        FrameLayout target = playerVisible ? heroContainer : miniContainer;
        if (target != null) {
            target.addView(playerView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }

        player.attachPlayerView(playerView);
        playerView.setVisibility(View.VISIBLE);
    }

    private void detachAndHide() {
        if (playerView == null) return;

        if (activePlayer != null) {
            activePlayer.detachPlayerView(playerView);
        }
        playerView.setVisibility(View.GONE);
        removeFromParent(playerView);
    }

    private void removeFromParent(@NonNull View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
    }
}
