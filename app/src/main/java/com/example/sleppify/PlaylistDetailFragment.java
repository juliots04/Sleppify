package com.example.sleppify;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import com.example.sleppify.utils.YouTubeCropTransformation;
import com.example.sleppify.utils.YouTubeImageProcessor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PlaylistDetailFragment extends Fragment
        implements PlaybackEventBus.Listener,
                   TrackReplacementSheet.OnReplacementConfirmedListener,
                   TrackReplacementSheet.OnReplacementUndoneListener {

    private static final String PREFS_STREAMING_CACHE = "streaming_cache";
    private static final long TRACKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final String PREF_TRACKS_UPDATED_AT_PREFIX = "playlist_tracks_updated_at_";
    private static final String PREF_TRACKS_DATA_PREFIX = "playlist_tracks_data_";
    private static final String PREF_TRACKS_FULL_CACHE_PREFIX = "playlist_tracks_cache_full_";
    private static final String PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX = "playlist_offline_complete_";
    private static final String PREF_CACHED_GOOGLE_PROFILE_PHOTO_URL = "cached_google_profile_photo_url";
    private static final String PREF_PLAYLIST_OFFLINE_AUTO_PREFIX = "playlist_offline_auto_";
    private static final String PREF_PLAYLIST_GRID_URLS_PREFIX = "playlist_grid_urls_";
    private static final String PREFS_PLAYER_STATE = "player_state";
    private static final String PREF_LAST_PLAYLIST_ID = "stream_last_playlist_id";
    private static final String PREF_LAST_PLAYLIST_TITLE = "stream_last_playlist_title";
    private static final String PREF_LAST_PLAYLIST_SUBTITLE = "stream_last_playlist_subtitle";
    private static final String PREF_LAST_PLAYLIST_THUMBNAIL = "stream_last_playlist_thumbnail";
    private static final String PREF_LAST_VIDEO_ID = "stream_last_video_id";
    private static final String PREF_LAST_TRACK_TITLE = "stream_last_track_title";
    private static final String PREF_LAST_TRACK_ARTIST = "stream_last_track_artist";
    private static final String PREF_LAST_TRACK_IMAGE = "stream_last_track_image";
    private static final String PREF_LAST_TRACK_DURATION = "stream_last_track_duration";
    private static final String PREF_LAST_IS_PLAYING = "stream_last_is_playing";
    private static final String PREF_LAST_STREAM_SCREEN = "stream_last_screen";
    private static final String PREF_LAST_YOUTUBE_ACCESS_TOKEN = "stream_last_youtube_access_token";
    private static final String STREAM_SCREEN_LIBRARY = "library";
    private static final String STREAM_SCREEN_PLAYLIST_DETAIL = "playlist_detail";
    private static final long TRACKS_TOKEN_RETRY_DELAY_MS = 1200L;
    private static final int MAX_TRACKS_TOKEN_RETRY = 3;
    private static final int PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT = 280;
    private static final int PLAYLIST_TRACKS_FETCH_STEP = 220;
    private static final int PLAYLIST_TRACKS_FETCH_MAX_LIMIT = 1800;
    private static final int PLAYLIST_TRACKS_LOAD_MORE_THRESHOLD = 12;
    private static final String OFFLINE_DOWNLOAD_UNIQUE_PREFIX = "offline_playlist_";
    private static final String OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME = "offline_playlist_queue";
    private static final String OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME = "offline_manual_track_queue";
    private static final String TAG_PLAYLIST_DETAIL = "playlist_detail";
    private static final String TAG_MODULE_MUSIC = "module_music";
    private static final String TAG_OFFLINE_DOWNLOAD = "OfflinePlaylistDl";
    private static final long OFFLINE_STATE_LOOKUP_DEBOUNCE_MS = 120L;
    private static final long PLAYLIST_INITIAL_CONTENT_FADE_MS = 220L;

    /** Shared singleton — avoids allocation per bind and ensures consistent Glide cache keys. */
    private static final YouTubeCropTransformation SHARED_YT_CROP = new YouTubeCropTransformation();
    /** Payload marker for state-only adapter updates (skip image reload). */
    private static final String PAYLOAD_STATE_ONLY = "state_only";
    /** Material standard easing — pre-built once, never re-allocated per animation. */
    private static final android.view.animation.Interpolator MATERIAL_EASE =
            new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);

    public static final String ARG_PLAYLIST_ID = "arg_playlist_id";
    public static final String ARG_PLAYLIST_TITLE = "arg_playlist_title";
    public static final String ARG_PLAYLIST_SUBTITLE = "arg_playlist_subtitle";
    public static final String ARG_PLAYLIST_THUMBNAIL = "arg_playlist_thumbnail";
    public static final String ARG_YOUTUBE_ACCESS_TOKEN = "arg_youtube_access_token";

    private RecyclerView rvPlaylistContent;
    private View playlistLoadingOverlay;
    private ProgressBar pbPlaylistLoading;
    private View flNoConnectionState;
    private View btnRetryConnection;

    // Playlist toolbar members (back + search + scroll-aware title)
    private View llPlaylistToolbar;
    private ImageView btnPlaylistBack;
    private ImageView btnPlaylistSearch;
    private TextView tvToolbarPlaylistTitle;
    private android.graphics.drawable.ColorDrawable toolbarBgDrawable;
    private long lastToolbarScrollUpdateMs = 0L;

    // lastSavedPlaylistKey/Name now read from CustomPlaylistsStore (global persistent)
    private final YouTubeMusicService youTubeMusicService = new YouTubeMusicService();
    private final ExecutorService urlPrefetchExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService trackStateLookupExecutor = Executors.newFixedThreadPool(2);
    private final List<PlaylistTrack> originalTracks = new ArrayList<>();
    private final List<PlaylistTrack> currentTracks = new ArrayList<>();
    private final List<PlaylistTrack> playbackQueueTracks = new ArrayList<>();
    private PlaylistHeaderAdapter headerAdapter;
    private PlaylistTrackAdapter trackAdapter;
    private int currentTrackIndex = -1;
    private boolean miniPlaying;
    private boolean shuffleModeEnabled;
    private final Random random = new Random();
    private int lastPlaybackQueueSize = -1;
    private boolean lastPlaybackQueueShuffleState = false;
    private PlaylistMeta currentMeta = new PlaylistMeta("", 0, "", "", "");
    @NonNull
    private String currentPlaylistId = "";
    @NonNull
    private String currentPlaylistTitle = "";
    @NonNull
    private String currentPlaylistSubtitle = "";
    @NonNull
    private String currentPlaylistThumbnail = "";
    @NonNull
    private String lastPersistedVideoId = "";
    private String lastPersistedPlaylistId = "";
    private boolean lastPersistedPlaying = false;
    @NonNull
    private String headerPlaylistTitle = "Lista";
    @NonNull
    private String headerPlaylistInfo = "Lista";
    @NonNull
    private String headerProfileName = "Tu cuenta";
    @Nullable
    private Uri headerProfilePhoto;
    @NonNull
    private String headerPlaylistThumbnail = "";
    @NonNull
    private List<String> headerGridUrls = new ArrayList<>();
    private boolean isRadioContext;
    private int pendingTracksTokenRetry;
    private int playlistTracksRequestedLimit = PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT;
    private boolean playlistTracksLoadMoreInFlight;
    private boolean playlistTracksCanLoadMore;
    @Nullable
    private Runnable pendingTracksTokenRetryRunnable;
    @Nullable
    private Observer<List<WorkInfo>> offlineDownloadObserver;
    @Nullable
    private String observingOfflineUniqueName;
    private boolean offlineObserverNotifyTerminalToasts;
    @Nullable
    private ActivityResultLauncher<String[]> offlineImportLauncher;
    @NonNull
    private final Set<String> pendingImportTrackIds = new HashSet<>();
    private boolean offlineDownloadRunning;
    private boolean offlineDownloadQueued;
    @NonNull
    private String offlineDownloadingTrackId = "";
    @NonNull
    private final Set<String> offlineDownloadingTrackIds = new HashSet<>();
    @NonNull
    private final Map<String, Float> offlineTrackProgressFractions = new HashMap<>();
    private boolean restoringHiddenPlayerFromSnapshot;
    private boolean awaitingInitialPlaylistRender = true;
    private boolean isScrolling = false;
    private boolean pendingOfflineToggle = false;
    private final Map<String, Long> lastOfflineStateLookupTimeByTrack = new LinkedHashMap<String, Long>(
            PLAYLIST_TRACKS_FETCH_MAX_LIMIT / 4 + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > PLAYLIST_TRACKS_FETCH_MAX_LIMIT / 2;
        }
    };
    private SongPlayerFragment cachedSongPlayer = null;
    private long lastCachedSongPlayerTime = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService offlineReadyStateExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong offlineReadyStateGeneration = new AtomicLong(0L);

    @NonNull
    public static PlaylistDetailFragment newInstance(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle,
            @NonNull String playlistThumbnail,
            @NonNull String accessToken
    ) {
        PlaylistDetailFragment fragment = new PlaylistDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLAYLIST_ID, playlistId);
        args.putString(ARG_PLAYLIST_TITLE, playlistTitle);
        args.putString(ARG_PLAYLIST_SUBTITLE, playlistSubtitle);
        args.putString(ARG_PLAYLIST_THUMBNAIL, playlistThumbnail);
        args.putString(ARG_YOUTUBE_ACCESS_TOKEN, accessToken);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        offlineImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::handleOfflineImportSelection
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Hide global header and dismiss global loading overlay immediately —
        // the fragment's own internal overlay (flPlaylistLoadingOverlay) takes over from here.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideTopAppBarForPlaylistDetail();
            ((MainActivity) getActivity()).setContainerOverlayMode(false);
            ((MainActivity) getActivity()).revealModuleContent();
        }
        rvPlaylistContent = view.findViewById(R.id.rvPlaylistContent);
        playlistLoadingOverlay = view.findViewById(R.id.flPlaylistLoadingOverlay);
        pbPlaylistLoading = view.findViewById(R.id.pbPlaylistLoading);
        flNoConnectionState = view.findViewById(R.id.flNoConnectionState);
        btnRetryConnection = view.findViewById(R.id.btnRetryConnection);

        // Playlist toolbar initialization (back + search + scroll title)
        llPlaylistToolbar = view.findViewById(R.id.llPlaylistToolbar);
        btnPlaylistBack = view.findViewById(R.id.btnPlaylistBack);
        btnPlaylistSearch = view.findViewById(R.id.btnPlaylistSearch);
        tvToolbarPlaylistTitle = view.findViewById(R.id.tvToolbarPlaylistTitle);

        // Transparent background controlled via alpha for scroll effect
        int surfaceColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.surface_dark);
        toolbarBgDrawable = new android.graphics.drawable.ColorDrawable(surfaceColor);
        toolbarBgDrawable.setAlpha(0);
        llPlaylistToolbar.setBackground(toolbarBgDrawable);

        // Apply status bar inset to toolbar and RecyclerView top padding
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            if (llPlaylistToolbar != null) {
                llPlaylistToolbar.setPadding(
                    llPlaylistToolbar.getPaddingLeft(),
                    statusBarTop,
                    llPlaylistToolbar.getPaddingRight(),
                    llPlaylistToolbar.getPaddingBottom()
                );
            }
            if (rvPlaylistContent != null) {
                rvPlaylistContent.setPadding(
                    rvPlaylistContent.getPaddingLeft(),
                    statusBarTop + (int)(56 * getResources().getDisplayMetrics().density),
                    rvPlaylistContent.getPaddingRight(),
                    rvPlaylistContent.getPaddingBottom()
                );
            }
            return insets;
        });

        if (btnPlaylistBack != null) {
            btnPlaylistBack.setOnClickListener(v -> {
                if (getActivity() != null) getActivity().onBackPressed();
            });
        }
        if (btnPlaylistSearch != null) {
            btnPlaylistSearch.setOnClickListener(v -> launchSearchActivity());
        }

        String playlistId = safeArg(ARG_PLAYLIST_ID);
        String playlistTitle = safeArg(ARG_PLAYLIST_TITLE);
        String playlistSubtitle = safeArg(ARG_PLAYLIST_SUBTITLE);
        String playlistThumbnail = safeArg(ARG_PLAYLIST_THUMBNAIL);
        String youtubeAccessToken = resolveYoutubeAccessToken(safeArg(ARG_YOUTUBE_ACCESS_TOKEN));
        playlistId = normalizeLikedPlaylistId(playlistId, playlistTitle, playlistSubtitle);
        if (isFavoritesPlaylistContext(playlistId)) {
            playlistTitle = FavoritesPlaylistStore.PLAYLIST_TITLE;
            playlistSubtitle = FavoritesPlaylistStore.buildSubtitle(
                FavoritesPlaylistStore.getFavoritesCount(requireContext())
            );
        }
        currentPlaylistId = playlistId;
        isRadioContext = playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDEM") || playlistId.startsWith("RDTMAK");
        if (isRadioContext && playlistTitle.startsWith("Radio: ")) {
            playlistTitle = playlistTitle.substring(7);
        }
        currentPlaylistTitle = playlistTitle;
        currentPlaylistSubtitle = playlistSubtitle;
        currentPlaylistThumbnail = playlistThumbnail;
        persistStreamingScreen(STREAM_SCREEN_PLAYLIST_DETAIL);

        if (playlistTitle.isEmpty()) {
            playlistTitle = "Lista";
            currentPlaylistTitle = playlistTitle;
        }

        PlaylistMeta meta = parseMeta(playlistSubtitle);
        currentMeta = meta;
        bindHeader(playlistTitle, meta, playlistThumbnail);
        showInitialLoadingOverlay();

        headerAdapter = new PlaylistHeaderAdapter();
        trackAdapter = new PlaylistTrackAdapter(new ArrayList<>(), new OnTrackTap() {
            @Override
            public void onTap(int position) {
                onTrackSelected(position);
            }

            @Override
            public void onMoreTap(int position, @NonNull View anchor) {
                onTrackMorePressed(position, anchor);
            }
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        rvPlaylistContent.setLayoutManager(layoutManager);
        rvPlaylistContent.setHasFixedSize(true);
        rvPlaylistContent.setItemAnimator(null);
        rvPlaylistContent.setItemViewCacheSize(5);

        // Single unified scroll listener — reduces per-frame dispatch overhead vs. 3 separate listeners
        rvPlaylistContent.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!isAdded()) return;
                isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (trackAdapter != null) {
                        trackAdapter.flushDeferredNotifications();
                        if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                            LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                            // ConcatAdapter: header at position 0, tracks start at 1
                            int firstVisible = lm.findFirstVisibleItemPosition() - 1;
                            int lastVisible  = lm.findLastVisibleItemPosition()  - 1;
                            if (firstVisible >= 0) {
                                trackAdapter.loadStateForVisibleRange(firstVisible, lastVisible);
                                trackAdapter.reloadImagesForRange(firstVisible, lastVisible);
                            }
                        }
                    }
                    if (!isScrolling) refreshActiveEqualizerState();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // State lookups are deferred to SCROLL_STATE_IDLE to avoid mid-scroll rebinds.

                // Toolbar scroll transition: throttled to ~30fps to avoid per-frame
                // findViewHolderForAdapterPosition + view-tree traversal + setAlpha invalidations.
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastToolbarScrollUpdateMs >= 32L) {
                    lastToolbarScrollUpdateMs = nowMs;
                    updateToolbarScrollState(recyclerView);
                }

                if (dy <= 0 || !playlistTracksCanLoadMore || playlistTracksLoadMoreInFlight) return;
                LinearLayoutManager lm = layoutManager;
                int totalItems = lm.getItemCount();
                int lastVisible = lm.findLastVisibleItemPosition();
                if (totalItems <= 0 || lastVisible < 0) return;
                if (totalItems - lastVisible <= PLAYLIST_TRACKS_LOAD_MORE_THRESHOLD) {
                    rvPlaylistContent.post(() -> requestMorePlaylistTracksIfNeeded());
                }
            }
        });

        rvPlaylistContent.setAdapter(new ConcatAdapter(
            headerAdapter,
            trackAdapter
        ));

        playlistTracksRequestedLimit = PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT;
        playlistTracksLoadMoreInFlight = false;
        playlistTracksCanLoadMore = false;

        shuffleModeEnabled = loadPersistedShuffleMode();
        syncShuffleModeFromPlayer();

        final String requestPlaylistId = playlistId;
        final String requestToken = youtubeAccessToken;
        view.post(() -> {
            if (!isAdded() || getView() == null) {
                return;
            }
            // Deferred: these were blocking fragment entry with snapshot loads,
            // fragment transactions, and Glide calls before the overlay was visible
            restoreOfflineDownloadObservation();
            maybeRestoreHiddenPlayerFromSnapshot();
            syncTrackStateFromPlayer();
            refreshPlaylistMeta(requestPlaylistId, requestToken);
            bindTrackList(requestPlaylistId, requestToken);
        });
        PlaybackEventBus.addListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideTopAppBarForPlaylistDetail();
        }
        // Ensure Glide is not paused if the fragment resumes after a fling was interrupted
        if (isAdded()) {
            try { Glide.with(this).resumeRequests(); } catch (Exception ignored) {}
        }
        onBecameVisible(false);
    }

    @Override
    public void onPlaybackSnapshotUpdated() {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(this::syncTrackStateFromPlayer);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setContainerOverlayMode(false);
            if (!hidden) {
                ((MainActivity) getActivity()).hideTopAppBarForPlaylistDetail();
            }
        }
        if (hidden) {
            return;
        }
        if (rvPlaylistContent != null) {
            rvPlaylistContent.post(() -> {
                if (rvPlaylistContent != null) rvPlaylistContent.scrollToPosition(0);
            });
        }

        onBecameVisible(true);
    }

    private void onBecameVisible(boolean deferHeavyWork) {
        persistStreamingScreen(STREAM_SCREEN_PLAYLIST_DETAIL);
        syncTrackStateFromPlayer();

        // Always fade-in the content when the fragment becomes visible
        if (!awaitingInitialPlaylistRender && rvPlaylistContent != null) {
            rvPlaylistContent.animate().cancel();
            rvPlaylistContent.setAlpha(0f);
            rvPlaylistContent.animate().alpha(1f).setDuration(PLAYLIST_INITIAL_CONTENT_FADE_MS).start();
        }

        if (deferHeavyWork) {
            View v = getView();
            if (v != null) {
                v.postDelayed(() -> {
                    if (!isAdded() || isHidden()) return;
                    restoreOfflineDownloadObservation();
                    maybeRestoreHiddenPlayerFromSnapshot();
                    syncShuffleModeFromPlayer();
                    if (rvPlaylistContent != null) {
                        rvPlaylistContent.post(() -> {
                            if (!isAdded()) return;
                            refreshVisibleTrackRows();
                            maybeUpdateOfflineReadyState();
                        });
                    }
                }, 140);
            } else {
                restoreOfflineDownloadObservation();
                maybeRestoreHiddenPlayerFromSnapshot();
                syncShuffleModeFromPlayer();
            }
        } else {
            restoreOfflineDownloadObservation();
            maybeRestoreHiddenPlayerFromSnapshot();
            syncShuffleModeFromPlayer();
            if (rvPlaylistContent != null) {
                rvPlaylistContent.post(() -> {
                    if (!isAdded()) return;
                    refreshVisibleTrackRows();
                    maybeUpdateOfflineReadyState();
                });
            }
        }
    }

    private void persistStreamingScreen(@NonNull String screen) {
        if (!isAdded()) {
            return;
        }
        requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_STREAM_SCREEN, screen)
                .apply();
    }

    @Override
    public void onDestroyView() {
        stopObservingOfflineDownload();
        setOfflineDownloadVisualState(false, "");
        pendingImportTrackIds.clear();
        cancelPendingTracksTokenRetry();
        persistLibraryScreenIfReturningToMusic();
        offlineReadyStateGeneration.incrementAndGet();
        restoringHiddenPlayerFromSnapshot = false;
        cachedSongPlayer = null;
        lastCachedSongPlayerTime = 0;
        if (getActivity() instanceof MainActivity) {
            MainActivity main = (MainActivity) getActivity();
            main.setContainerOverlayMode(false);
            // Safety net: restore main shell when this fragment is popped off the back stack
            if (isRemoving() && !main.isFinishing() && !main.isDestroyed()) {
                main.ensureHeaderVisibleForMusic();
            }
        }
        if (rvPlaylistContent != null) {
            try {
                rvPlaylistContent.clearOnScrollListeners();
            } catch (Exception ignored) {}
        }
        playlistLoadingOverlay = null;
        pbPlaylistLoading = null;
        rvPlaylistContent = null;
        // Cleanup toolbar views
        llPlaylistToolbar = null;
        btnPlaylistBack = null;
        btnPlaylistSearch = null;
        tvToolbarPlaylistTitle = null;
        toolbarBgDrawable = null;
        PlaybackEventBus.removeListener(this);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        offlineReadyStateExecutor.shutdownNow();
        trackStateLookupExecutor.shutdownNow();
        urlPrefetchExecutor.shutdownNow();
        super.onDestroy();
    }

    private void updateToolbarScrollState(@NonNull RecyclerView recyclerView) {
        if (llPlaylistToolbar == null || toolbarBgDrawable == null) return;

        float fraction;
        // Header is at adapter position 0 in ConcatAdapter.
        // headerView.getBottom() returns the header's bottom edge in RecyclerView-local
        // coordinates — the same space as toolbarHeight, so math is correct.
        RecyclerView.ViewHolder headerVH = recyclerView.findViewHolderForAdapterPosition(0);
        if (headerVH != null) {
            int toolbarHeight = llPlaylistToolbar.getHeight();
            if (toolbarHeight <= 0) toolbarHeight = llPlaylistToolbar.getMeasuredHeight();
            if (toolbarHeight <= 0) {
                toolbarBgDrawable.setAlpha(0);
                if (tvToolbarPlaylistTitle != null) tvToolbarPlaylistTitle.setAlpha(0f);
                return;
            }
            int headerBottom = headerVH.itemView.getBottom(); // RV-local coords
            float transitionZone = toolbarHeight * 3f;
            if (headerBottom <= toolbarHeight) {
                fraction = 1f;
            } else if (headerBottom >= toolbarHeight + transitionZone) {
                fraction = 0f;
            } else {
                fraction = 1f - (float) (headerBottom - toolbarHeight) / transitionZone;
            }
        } else {
            // Header scrolled completely off-screen — fully solid
            fraction = 1f;
        }

        toolbarBgDrawable.setAlpha(Math.round(fraction * 255f));
        if (tvToolbarPlaylistTitle != null) {
            tvToolbarPlaylistTitle.setAlpha(fraction);
        }
    }

    private void showInitialLoadingOverlay() {
        awaitingInitialPlaylistRender = true;
        if (rvPlaylistContent != null) {
            rvPlaylistContent.animate().cancel();
            rvPlaylistContent.setAlpha(0f);
        }
        // Hide the activity-level loading overlay — PlaylistDetail has its own
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).revealModuleContent();
        }
        // No afectar el minireproductor durante el loading state
        if (playlistLoadingOverlay != null) {
            playlistLoadingOverlay.animate().cancel();
            playlistLoadingOverlay.setAlpha(1f);
            playlistLoadingOverlay.setVisibility(View.VISIBLE);
            // No usar bringToFront() para no afectar al minireproductor
        }
        if (pbPlaylistLoading != null) {
            pbPlaylistLoading.setVisibility(View.VISIBLE);
        }
    }

    private void revealPlaylistContentIfNeeded(boolean animated) {
        if (!awaitingInitialPlaylistRender) {
            return;
        }
        awaitingInitialPlaylistRender = false;

        if (rvPlaylistContent != null) {
            rvPlaylistContent.animate().cancel();
            if (animated) {
                rvPlaylistContent.animate().alpha(1f).setDuration(PLAYLIST_INITIAL_CONTENT_FADE_MS).start();
            } else {
                rvPlaylistContent.setAlpha(1f);
            }
        }

        if (playlistLoadingOverlay != null) {
            playlistLoadingOverlay.animate().cancel();
            if (animated) {
                playlistLoadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(PLAYLIST_INITIAL_CONTENT_FADE_MS)
                        .withEndAction(() -> {
                            if (playlistLoadingOverlay != null) {
                                playlistLoadingOverlay.setVisibility(View.GONE);
                            }
                        })
                        .start();
            } else {
                playlistLoadingOverlay.setAlpha(0f);
                playlistLoadingOverlay.setVisibility(View.GONE);
            }
        }

        if (pbPlaylistLoading != null) {
            pbPlaylistLoading.setVisibility(View.GONE);
        }
    }

    private void showNoConnectionState(
            @NonNull String playlistId,
            @NonNull String accessToken,
            boolean forceRefresh,
            boolean loadMore
    ) {
        revealPlaylistContentIfNeeded(true);
        if (flNoConnectionState != null) {
            flNoConnectionState.setVisibility(View.VISIBLE);
        }
        if (btnRetryConnection != null) {
            btnRetryConnection.setOnClickListener(v -> {
                hideNoConnectionState();
                showInitialLoadingOverlay();
                bindTrackList(playlistId, accessToken, forceRefresh, loadMore);
            });
        }
    }

    private void hideNoConnectionState() {
        if (flNoConnectionState != null) {
            flNoConnectionState.setVisibility(View.GONE);
        }
    }

    public void externalRefreshOfflineState() {
        if (!isAdded() || getView() == null) return;
        refreshVisibleTrackRows();
    }

    public void externalForceRefresh() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) return;
        playlistTracksRequestedLimit = PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT;
        playlistTracksCanLoadMore = false;
        playlistTracksLoadMoreInFlight = false;
        String token = resolveYoutubeAccessToken("");
        if (!TextUtils.isEmpty(token)) {
            refreshPlaylistMeta(currentPlaylistId, token);
        }
        bindTrackList(currentPlaylistId, token, true);
    }

    private void requestMorePlaylistTracksIfNeeded() {
        if (!isAdded()
                || TextUtils.isEmpty(currentPlaylistId)
                || playlistTracksLoadMoreInFlight
                || !playlistTracksCanLoadMore) {
            return;
        }

        String token = resolveYoutubeAccessToken("");
        if (TextUtils.isEmpty(token)) {
            return;
        }

        playlistTracksLoadMoreInFlight = true;
        bindTrackList(currentPlaylistId, token, false, true);
    }

    private void persistLibraryScreenIfReturningToMusic() {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        Fragment music = getParentFragmentManager().findFragmentByTag(TAG_MODULE_MUSIC);
        if (music == null || !music.isAdded() || music.isHidden()) {
            return;
        }

        persistStreamingScreen(STREAM_SCREEN_LIBRARY);
    }

    private void notifyHeaderChanged() {
        if (headerAdapter != null) {
            headerAdapter.notifyItemChanged(0);
        }
    }

    private void notifyHeaderStateChanged() {
        if (headerAdapter != null) {
            headerAdapter.notifyItemChanged(0, PAYLOAD_STATE_ONLY);
        }
    }

    private void refreshActiveEqualizerState() {
        if (trackAdapter == null || rvPlaylistContent == null || currentTrackIndex < 0) {
            return;
        }
        // The active track's position in the ConcatAdapter = headerAdapter(1) + currentTrackIndex
        int globalPosition = 1 + currentTrackIndex;
        RecyclerView.ViewHolder vh = rvPlaylistContent.findViewHolderForAdapterPosition(globalPosition);
        if (vh == null) {
            return;
        }
        AnimatedEqualizerView eq = vh.itemView.findViewById(R.id.animatedEq);
        if (eq == null) {
            return;
        }
        SongPlayerFragment songPlayer = findSongPlayerFragment();
        boolean isActuallyPlaying = songPlayer != null && songPlayer.isPlaying();
        eq.setAnimating(isActuallyPlaying);
    }

    private void refreshVisibleTrackRows() {
        if (trackAdapter == null) {
            return;
        }

        // Only invalidate cache for visible tracks, not all tracks — avoids re-triggering
        // disk I/O lookups for hundreds of off-screen items.
        int totalTracks = trackAdapter.getItemCount();
        if (totalTracks <= 0) {
            return;
        }

        int start = 0;
        int end = totalTracks - 1;

        if (rvPlaylistContent != null && rvPlaylistContent.getLayoutManager() instanceof LinearLayoutManager) {
            LinearLayoutManager layoutManager = (LinearLayoutManager) rvPlaylistContent.getLayoutManager();
            int firstVisibleGlobal = layoutManager.findFirstVisibleItemPosition();
            int lastVisibleGlobal = layoutManager.findLastVisibleItemPosition();
            if (firstVisibleGlobal >= 0 && lastVisibleGlobal >= firstVisibleGlobal) {
                start = Math.max(0, firstVisibleGlobal - 1);
                end = Math.max(start, Math.min(totalTracks - 1, lastVisibleGlobal - 1));
            } else {
                return;
            }
        } else {
            return;
        }

        // Invalidate cache for visible range so lookups re-run after app return.
        // Do NOT call notifyItemRangeChanged here — that would immediately rebind
        // with an empty cache (showing icons as not-downloaded) before the async
        // disk check completes. The lookup callbacks call immediateNotifyStateChanged
        // themselves once the real result is available.
        trackAdapter.invalidateVisibleTrackStateCache(currentTracks, start, end);
        trackAdapter.loadStateForVisibleRange(start, end);
    }

    private void setOfflineDownloadVisualState(boolean running, @Nullable String currentTrackId) {
        setOfflineDownloadVisualState(running, currentTrackId, null, null);
    }

    private void setOfflineDownloadVisualState(
            boolean running,
            @Nullable String currentTrackId,
            @Nullable String[] activeTrackIds,
            @Nullable Map<String, Float> progressByTrackId
    ) {
        offlineDownloadRunning = running;
        offlineDownloadingTrackId = currentTrackId == null ? "" : currentTrackId.trim();
        offlineDownloadingTrackIds.clear();
        offlineTrackProgressFractions.clear();

        if (running && activeTrackIds != null && activeTrackIds.length > 0) {
            for (String trackId : activeTrackIds) {
                if (TextUtils.isEmpty(trackId)) {
                    continue;
                }
                offlineDownloadingTrackIds.add(trackId.trim());
            }
        }

        if (running
                && offlineDownloadingTrackIds.isEmpty()
                && !TextUtils.isEmpty(offlineDownloadingTrackId)) {
            offlineDownloadingTrackIds.add(offlineDownloadingTrackId);
        }

        if (running && progressByTrackId != null && !progressByTrackId.isEmpty()) {
            for (Map.Entry<String, Float> entry : progressByTrackId.entrySet()) {
                if (entry == null || TextUtils.isEmpty(entry.getKey())) {
                    continue;
                }
                float value = entry.getValue() == null ? 0f : entry.getValue();
                offlineTrackProgressFractions.put(entry.getKey().trim(), Math.max(0f, Math.min(1f, value)));
            }
        }

        if (trackAdapter != null) {
            trackAdapter.setOfflineDownloadState(offlineDownloadRunning, offlineDownloadingTrackIds, offlineTrackProgressFractions);
        }
    }

    private boolean isOfflineStatusPinned() {
        return offlineDownloadRunning || offlineDownloadQueued;
    }

    private void restoreOfflineDownloadObservation() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return;
        }
        observeOfflineDownload(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME, false);
    }

    @NonNull
    private String currentPlaylistOfflineTag() {
        return OFFLINE_DOWNLOAD_UNIQUE_PREFIX + (TextUtils.isEmpty(currentPlaylistId) ? "current" : currentPlaylistId);
    }

    private boolean isCurrentPlaylistOfflineAutoEnabled() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return false;
        }
        return getCachePrefs().getBoolean(PREF_PLAYLIST_OFFLINE_AUTO_PREFIX + currentPlaylistId, false);
    }

    private void setCurrentPlaylistOfflineAutoEnabled(boolean enabled) {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return;
        }
        getCachePrefs().edit()
                .putBoolean(PREF_PLAYLIST_OFFLINE_AUTO_PREFIX + currentPlaylistId, enabled)
                .apply();
    }

    private void onOfflineTogglePressed() {
        if (!isAdded()) {
            return;
        }

        if (currentTracks.isEmpty()) {
            pendingOfflineToggle = true;
            return;
        }

        if (!isCurrentPlaylistOfflineAutoEnabled()) {
            setCurrentPlaylistOfflineAutoEnabled(true);
            notifyHeaderChanged();
            startOfflinePlaylistDownload(true);
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Desactivar descargas offline")
                .setMessage("Se eliminaran todas las canciones descargadas de esta playlist. ¿Deseas continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aceptar", (dialog, which) -> disableOfflineForCurrentPlaylist())
                .show();
    }

    private void disableOfflineForCurrentPlaylist() {
        if (!isAdded()) {
            return;
        }

        setCurrentPlaylistOfflineAutoEnabled(false);
        WorkManager.getInstance(requireContext().getApplicationContext())
                .cancelAllWorkByTag(currentPlaylistOfflineTag());

        setOfflineDownloadVisualState(false, "");
        offlineDownloadQueued = false;
        notifyHeaderChanged();

        ArrayList<String> idsToDelete = buildCurrentVideoIds();
        if (idsToDelete.isEmpty()) {
            notifyHeaderChanged();
            
            return;
        }

        final Activity hostActivity = getActivity();
        final android.content.Context appContext = requireContext().getApplicationContext();

        trackStateLookupExecutor.execute(() -> {
            int removed = OfflineAudioStore.deleteOfflineAudio(appContext, idsToDelete);
            if (hostActivity == null || hostActivity.isFinishing()) {
                return;
            }
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                notifyHeaderChanged();
                if (trackAdapter != null) {
                    trackAdapter.invalidateTrackStateCache();
                    trackAdapter.submitTracks(currentTracks);
                }
                maybeUpdateOfflineReadyState();

                // Notify MusicPlayerFragment to update its offline state for this playlist
                notifyMusicPlayerOfflineChanged();
            });
        });
    }

    private void maybeAutoDownloadForCurrentPlaylist() {
        if (!isAdded() || !isCurrentPlaylistOfflineAutoEnabled() || currentTracks.isEmpty()) {
            return;
        }
        if (offlineDownloadRunning || offlineDownloadQueued) {
            return;
        }
        startOfflinePlaylistDownload(false);
    }

    @NonNull
    private String safeArg(@NonNull String key) {
        Bundle args = getArguments();
        if (args == null) {
            return "";
        }
        String value = args.getString(key);
        return value == null ? "" : value.trim();
    }

    private void bindHeader(@NonNull String playlistTitle, @NonNull PlaylistMeta meta, @NonNull String playlistThumbnail) {
        String override = isAdded() ? PlaylistNameOverrideStore.getDisplayName(requireContext(), currentPlaylistId) : null;
        headerPlaylistTitle = (override != null && !override.isEmpty()) ? override : playlistTitle;
        if (override != null && !override.isEmpty()) currentPlaylistTitle = override;
        headerPlaylistInfo = buildPlaylistInfoLine(meta, currentTracks.isEmpty() ? 0 : currentTracks.size());
        headerPlaylistThumbnail = playlistThumbnail;
        headerGridUrls = new ArrayList<>();
        bindGoogleProfile(meta.ownerLabel);
        if (tvToolbarPlaylistTitle != null) {
            tvToolbarPlaylistTitle.setText(headerPlaylistTitle);
        }
        notifyHeaderChanged();
    }

    private void bindGoogleProfile(@NonNull String fallbackName) {
        String profileName = resolvePrimaryUserName();
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        Uri profilePhoto = null;
        String cachedPhotoUrl = getCachePrefs().getString(PREF_CACHED_GOOGLE_PROFILE_PHOTO_URL, "");

        if (firebaseUser != null) {
            if (TextUtils.isEmpty(profileName) && !TextUtils.isEmpty(firebaseUser.getDisplayName())) {
                profileName = firebaseUser.getDisplayName();
            }
            profilePhoto = firebaseUser.getPhotoUrl();
            if (profilePhoto != null && !TextUtils.isEmpty(profilePhoto.toString())) {
                getCachePrefs().edit().putString(PREF_CACHED_GOOGLE_PROFILE_PHOTO_URL, profilePhoto.toString()).apply();
            }
        }

        if (profilePhoto == null && !TextUtils.isEmpty(cachedPhotoUrl)) {
            profilePhoto = Uri.parse(cachedPhotoUrl);
        }

        if (TextUtils.isEmpty(profileName)) {
            profileName = fallbackName;
        }

        headerProfileName = extractFirstName(profileName);
        headerProfilePhoto = profilePhoto;
        notifyHeaderChanged();
    }

    private void loadTrackArt(@NonNull ImageView target, @Nullable String imageUrl) {
        loadTrackArt(target, imageUrl, com.bumptech.glide.Priority.HIGH);
    }

    private void loadTrackArt(
            @NonNull ImageView target,
            @Nullable String imageUrl,
            @NonNull com.bumptech.glide.Priority priority
    ) {
        Context ctx = target.getContext();
        if (TextUtils.isEmpty(imageUrl)) {
            Glide.with(ctx).clear(target);
            target.setImageDrawable(null);
            return;
        }
        // DO NOT call target.setImageDrawable(null) here — it causes a visible
        // white flash before the new image loads. Glide's crossFade transition
        // handles the swap smoothly from old→new image.
        boolean offlineOnly = !cachedHasValidatedInternet(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        int px = Math.max(100, Math.round(50 * density));
        Glide.with(target)
                .load(imageUrl.trim())
                .transform(SHARED_YT_CROP)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(offlineOnly)
                .priority(priority)
                .override(px, px)
                .transition(DrawableTransitionOptions.withCrossFade(100))
                .into(target);
    }

    private void prefetchStreamUrlsForTracks(@NonNull List<PlaylistTrack> tracks, int limit) {
        if (!isAdded() || !isInternetAvailable()) {
            return;
        }

        Context appContext = requireContext().getApplicationContext();
        int count = 0;
        for (PlaylistTrack track : tracks) {
            if (track == null || TextUtils.isEmpty(track.videoId)) {
                continue;
            }
            
            final String videoId = track.videoId;
            final String duration = track.duration;
            urlPrefetchExecutor.submit(() -> {
                try {
                    // Skip if already has offline audio (disk I/O — must be off main thread)
                    if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, videoId, duration)) {
                        return;
                    }
                    // This will cache the URL in InnertubeResolver
                    InnertubeResolver.resolveStreamUrl(appContext, videoId);
                } catch (Exception e) {
                    Log.w(TAG_OFFLINE_DOWNLOAD, "prefetchStreamUrl failed for " + videoId, e);
                }
            });
            
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private static int bucketArtworkDimension(int value) {
        int safe = Math.max(1, value);
        return Math.max(64, ((safe + 63) / 64) * 64);
    }

    private static int resolveTargetDimension(int measured, int layoutValue) {
        if (measured > 0) {
            return measured;
        }
        if (layoutValue > 0) {
            return layoutValue;
        }
        return 0;
    }

    private static long sInternetCheckMs = 0L;
    private static boolean sInternetCheckResult = true;
    private static final long INTERNET_CHECK_TTL_MS = 5_000L;

    private static boolean cachedHasValidatedInternet(@Nullable Context context) {
        long now = System.currentTimeMillis();
        if (now - sInternetCheckMs < INTERNET_CHECK_TTL_MS) {
            return sInternetCheckResult;
        }
        sInternetCheckMs = now;
        sInternetCheckResult = hasValidatedInternet(context);
        return sInternetCheckResult;
    }

    private static boolean hasValidatedInternet(@Nullable Context context) {
        if (context == null) {
            return false;
        }

        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }

        android.net.Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null) {
            return false;
        }

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static void loadArtworkInto(@NonNull ImageView target, @Nullable String imageUrl) {
        loadArtworkInto(target, imageUrl, 0, false);
    }

    public static void loadArtworkIntoStatic(@NonNull ImageView target, @Nullable String imageUrl, int fixedSizeDp) {
        loadArtworkInto(target, imageUrl, fixedSizeDp, false);
    }

    private static void loadArtworkInto(@NonNull ImageView target, @Nullable String imageUrl, int fixedSizeDp, boolean highQuality) {
        if (TextUtils.isEmpty(imageUrl)) {
            target.setTag(R.id.tag_artwork_signature, null);
            target.setImageDrawable(null);
            return;
        }

        String safeUrl = imageUrl.trim();
        Context context = target.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        
        int targetWidth;
        int targetHeight;

        if (fixedSizeDp > 0) {
            targetWidth = Math.round(fixedSizeDp * density);
            targetHeight = targetWidth;
        } else {
            ViewGroup.LayoutParams params = target.getLayoutParams();
            int rawWidth = resolveTargetDimension(target.getWidth(), params == null ? 0 : params.width);
            int rawHeight = resolveTargetDimension(target.getHeight(), params == null ? 0 : params.height);
            boolean hasTargetSize = rawWidth > 0 && rawHeight > 0;

            if (hasTargetSize) {
                targetWidth = bucketArtworkDimension(rawWidth);
                targetHeight = bucketArtworkDimension(rawHeight);
            } else {
                targetWidth = Math.round(160 * density); 
                targetHeight = targetWidth;
            }
        }

        // Use a fixed decode size for smart crop so the crop result is always
        // identical between thumbnail and full load — preventing the visible
        // "re-crop flash" that happens when Glide thumbnail loads a smaller
        // bitmap that produces different crop margins.
        // Exception: for small list thumbnails (<=64dp) the smartCrop benefit is negligible
        // and forcing MIN_DECODE_PX_FOR_SMART_CROP (320px) would decode 16x more pixels
        // than needed, causing severe lag on first scroll of large playlists.
        boolean applySmartCropDecode = fixedSizeDp <= 0 || fixedSizeDp > 64;
        if (applySmartCropDecode && YouTubeImageProcessor.shouldProcess(safeUrl)) {
            int side = YouTubeImageProcessor.decodeDimensionForSmartCrop(
                    fixedSizeDp > 0 ? Math.round(fixedSizeDp * density) : targetWidth);
            targetWidth = side;
            targetHeight = side;
        }

        // For small thumbnails, apply the same 160px minimum here so the signature
        // matches the actual Glide .override() used below — preventing duplicate loads.
        if (!highQuality && fixedSizeDp > 0 && fixedSizeDp <= 64) {
            targetWidth = Math.max(targetWidth, 160);
            targetHeight = Math.max(targetHeight, 160);
        }

        String signature = safeUrl + "|" + targetWidth + "x" + targetHeight;
        Object previousSignature = target.getTag(R.id.tag_artwork_signature);
        if (previousSignature instanceof String && signature.equals(previousSignature)) {
            return;
        }
        target.setTag(R.id.tag_artwork_signature, signature);

        boolean offlineOnly = !cachedHasValidatedInternet(context);

        // DO NOT call target.setImageDrawable(null) here — it causes a visible
        // white flash before the new image loads. Glide's crossFade transition
        // handles the swap smoothly from old→new image.
        
        com.bumptech.glide.Priority priority = com.bumptech.glide.Priority.NORMAL;
        if (fixedSizeDp > 0 && fixedSizeDp <= 64) {
            priority = com.bumptech.glide.Priority.HIGH;
        }
        
        // For small list thumbnails (<=64dp), use a lightweight load path.
        // Override minimum 200px so images stay sharp on high-density screens.
        if (!highQuality && fixedSizeDp > 0 && fixedSizeDp <= 64) {
            Glide.with(target)
                .load(safeUrl)
                .transform(SHARED_YT_CROP)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(offlineOnly)
                .override(targetWidth, targetHeight)
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(100))
                .into(target);
        } else {
            Glide.with(target)
                .load(safeUrl)
                .transform(SHARED_YT_CROP)
                .format(highQuality ? DecodeFormat.PREFER_ARGB_8888 : DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .priority(priority)
                .onlyRetrieveFromCache(offlineOnly)
                .override(highQuality ? targetWidth : Math.max(targetWidth, 320), 
                         highQuality ? targetHeight : Math.max(targetHeight, 320))
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                .into(target);
        }
    }

    private boolean isInternetAvailable() {
        return isAdded() && cachedHasValidatedInternet(requireContext());
    }

    private void refreshPlaylistMeta(@NonNull String playlistId, @NonNull String accessToken) {
        if (playlistId.isEmpty()
                || accessToken.isEmpty()
                || isLikedPlaylistContext(playlistId)
                || isFavoritesPlaylistContext(playlistId)) {
            return;
        }

        youTubeMusicService.fetchPlaylistMeta(accessToken, playlistId, new YouTubeMusicService.PlaylistMetaCallback() {
            @Override
            public void onSuccess(@NonNull YouTubeMusicService.PlaylistResult playlist) {
                if (!isAdded()) {
                    return;
                }
                currentMeta = new PlaylistMeta(
                        currentMeta.ownerLabel,
                        Math.max(currentMeta.songsCount, playlist.itemCount),
                        currentMeta.estimatedDuration,
                        buildVisibilityLabel(playlist.privacyStatus),
                        buildRelativeDateLabel(playlist.publishedAt)
                );
                headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.isEmpty() ? 0 : currentTracks.size());
                notifyHeaderChanged();
            }

            @Override
            public void onError(@NonNull String error) {
                // Keep existing fallback metadata when API does not provide optional fields.
            }
        });
    }

    @NonNull
    private String resolvePrimaryUserName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return "";
        }

        String displayName = user.getDisplayName();
        if (!TextUtils.isEmpty(displayName)) {
            return displayName.trim();
        }

        String email = user.getEmail();
        if (!TextUtils.isEmpty(email)) {
            int atIndex = email.indexOf('@');
            if (atIndex > 0) {
                return email.substring(0, atIndex).trim();
            }
            return email.trim();
        }

        return "";
    }

    @NonNull
    private String extractFirstName(@NonNull String fullName) {
        String normalized = fullName.trim();
        if (normalized.isEmpty()) {
            return "Tu cuenta";
        }

        String[] bySpace = normalized.split("\\s+");
        if (bySpace.length > 0 && !TextUtils.isEmpty(bySpace[0])) {
            return capitalizeToken(bySpace[0]);
        }

        String[] byDot = normalized.split("[._-]");
        if (byDot.length > 0 && !TextUtils.isEmpty(byDot[0])) {
            return capitalizeToken(byDot[0]);
        }

        return capitalizeToken(normalized);
    }

    @NonNull
    private String capitalizeToken(@NonNull String token) {
        String value = token.trim();
        if (value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void bindTrackList(@NonNull String playlistId, @NonNull String accessToken) {
        bindTrackList(playlistId, accessToken, false, false);
    }

    private void bindTrackList(@NonNull String playlistId, @NonNull String accessToken, boolean forceRefresh) {
        bindTrackList(playlistId, accessToken, forceRefresh, false);
    }

    private void bindTrackList(
            @NonNull String playlistId,
            @NonNull String accessToken,
            boolean forceRefresh,
            boolean loadMore
    ) {
        if (playlistId.isEmpty()) {
            playlistTracksLoadMoreInFlight = false;
            playlistTracksCanLoadMore = false;
            return;
        }

        cancelPendingTracksTokenRetry();
        playlistTracksLoadMoreInFlight = true;

        String effectiveAccessToken = resolveYoutubeAccessToken(accessToken);
        int requestedLimit = resolveTrackFetchLimit(forceRefresh, loadMore);

        boolean localFilesContext = isLocalFilesContext(playlistId);
        boolean favoritesContext = isFavoritesPlaylistContext(playlistId);
        boolean customContext = isCustomPlaylistContext(playlistId);

        // Local files — load from device MediaStore cache
        if (localFilesContext) {
            playlistTracksLoadMoreInFlight = false;
            playlistTracksCanLoadMore = false;
            if (!isAdded()) return;
            List<LocalFilesStore.LocalTrack> localTracks = LocalFilesStore.getCachedFiles(requireContext());
            boolean needsRescan = localTracks.isEmpty()
                    || (!localTracks.isEmpty() && TextUtils.isEmpty(localTracks.get(0).getAlbumArtUri()));
            if (needsRescan) {
                localTracks = LocalFilesStore.scanLocalFiles(requireContext());
                LocalFilesStore.cacheFiles(requireContext(), localTracks);
            }
            List<PlaylistTrack> mapped = new ArrayList<>(localTracks.size());
            for (LocalFilesStore.LocalTrack t : localTracks) {
                mapped.add(new PlaylistTrack(
                        t.getVideoId(),
                        t.getTitle(),
                        t.getArtist(),
                        t.getDuration(),
                        t.getAlbumArtUri()
                ));
            }
            renderTracks(mapped, playlistId, false);
            return;
        }

        List<PlaylistTrack> cachedTracks = sanitizeTracksForPlaylist(
                playlistId,
                loadCachedTracks(playlistId)
        );

        if (favoritesContext || customContext) {
            // Clean HTML entities from stored titles/artists
            List<PlaylistTrack> cleaned = new ArrayList<>(cachedTracks.size());
            for (PlaylistTrack track : cachedTracks) {
                String cleanTitle = decodeHtmlEntities(track.title);
                String cleanArtist = decodeHtmlEntities(track.artist);
                cleaned.add(new PlaylistTrack(track.videoId, cleanTitle, cleanArtist, track.duration, track.imageUrl));
            }
            List<PlaylistTrack> cleanedCachedTracks = cleaned;

            renderTracks(cleanedCachedTracks, playlistId, true);
            if (cleanedCachedTracks.isEmpty() && !isOfflineStatusPinned()) {
                notifyHeaderChanged();
            }

            // On pull-to-refresh, try to enrich tracks missing durations
            if (forceRefresh && !cleanedCachedTracks.isEmpty() && !effectiveAccessToken.isEmpty()) {
                List<String> missingDurationIds = new ArrayList<>();
                for (PlaylistTrack track : cleanedCachedTracks) {
                    if (TextUtils.isEmpty(track.duration) || "--:--".equals(track.duration)) {
                        if (!TextUtils.isEmpty(track.videoId)) {
                            missingDurationIds.add(track.videoId);
                        }
                    }
                }

                final List<PlaylistTrack> tracksToEnrich = new ArrayList<>(cleanedCachedTracks);
                final String enrichPlaylistId = playlistId;
                final boolean isFavContext = favoritesContext;

                if (!missingDurationIds.isEmpty()) {
                    youTubeMusicService.fetchVideoDurations(effectiveAccessToken, missingDurationIds, new YouTubeMusicService.VideoDurationCallback() {
                        @Override
                        public void onSuccess(@NonNull Map<String, String> durations) {
                            if (!isAdded()) return;
                            if (!durations.isEmpty()) {
                                List<PlaylistTrack> enriched = new ArrayList<>(tracksToEnrich.size());
                                boolean changed = false;
                                for (PlaylistTrack track : tracksToEnrich) {
                                    String newDuration = durations.get(track.videoId);
                                    if (newDuration != null && !newDuration.isEmpty()) {
                                        enriched.add(new PlaylistTrack(track.videoId, track.title, track.artist, newDuration, track.imageUrl));
                                        changed = true;
                                    } else {
                                        enriched.add(track);
                                    }
                                }
                                if (changed) {
                                    persistEnrichedLocalTracks(enrichPlaylistId, enriched, isFavContext);
                                    renderTracks(enriched, enrichPlaylistId, false);
                                }
                            }
                        }

                        @Override
                        public void onError(@NonNull String error) {
                            Log.w(TAG_OFFLINE_DOWNLOAD, "fetchVideoDurations failed: " + error);
                        }
                    });
                } else {
                    persistEnrichedLocalTracks(enrichPlaylistId, tracksToEnrich, isFavContext);
                }
            }

            playlistTracksLoadMoreInFlight = false;
            playlistTracksCanLoadMore = false;
            return;
        }

        boolean canRequestRemote = !playlistId.isEmpty() && !effectiveAccessToken.isEmpty();
        boolean hasCompleteCache = hasCompleteTracksCache(playlistId, cachedTracks);

        if (!cachedTracks.isEmpty()) {
            if (!forceRefresh) {
                renderTracks(cachedTracks, playlistId, true);
            }
            if (!forceRefresh && !loadMore) {
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = !hasCompleteCache;
                return;
            }
        }

        if (!canRequestRemote) {
            if (cachedTracks.isEmpty()) {
                List<PlaylistTrack> staleTracks = sanitizeTracksForPlaylist(
                        playlistId,
                        loadCachedTracksInternal(playlistId, true)
                );
                if (!staleTracks.isEmpty()) {
                    renderTracks(staleTracks, playlistId, true);
                    return;
                }

                if (!playlistId.isEmpty() && TextUtils.isEmpty(effectiveAccessToken)) {
                    scheduleTracksTokenRetry(playlistId);
                }
                notifyHeaderChanged();
                revealPlaylistContentIfNeeded(true);
            }
            playlistTracksLoadMoreInFlight = false;
            return;
        }

        notifyHeaderChanged();

        // Radio/Mix playlists (RDAMVM, RDEM, RDTMAK) use InnerTube API with cookie
        if (playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDEM") || playlistId.startsWith("RDTMAK")) {
            // Check no-internet before attempting network fetch
            if (!hasValidatedInternet(requireContext())) {
                List<PlaylistTrack> cached = sanitizeTracksForPlaylist(
                        playlistId, loadCachedTracks(playlistId));
                if (!cached.isEmpty()) {
                    playlistTracksLoadMoreInFlight = false;
                    renderTracks(cached, playlistId, true);
                    return;
                }
                playlistTracksLoadMoreInFlight = false;
                showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore);
                return;
            }
            String cookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .getString("stream_last_youtube_web_cookie", "");
            if (cookie == null) cookie = "";
            youTubeMusicService.fetchMixTracks(cookie.trim(), playlistId, new YouTubeMusicService.MixTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> tracks) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    playlistTracksCanLoadMore = false;
                    List<PlaylistTrack> mapped = new ArrayList<>();
                    for (YouTubeMusicService.TrackResult t : tracks) {
                        if (TextUtils.isEmpty(t.videoId)) continue;
                        // Subtitle may contain artist\tduration from parseMixTracks
                        String rawSub = t.subtitle == null ? "" : t.subtitle;
                        String artist = rawSub;
                        String duration = "--:--";
                        int tabIdx = rawSub.indexOf('\t');
                        if (tabIdx >= 0) {
                            artist = rawSub.substring(0, tabIdx);
                            duration = rawSub.substring(tabIdx + 1);
                        }
                        mapped.add(new PlaylistTrack(
                                t.videoId,
                                t.title == null ? "" : t.title,
                                artist,
                                duration,
                                t.thumbnailUrl == null ? "" : t.thumbnailUrl
                        ));
                    }
                    if (mapped.isEmpty()) {
                        revealPlaylistContentIfNeeded(true);
                        return;
                    }
                    // Prepend the source track that generated the radio at position 0
                    if (currentPlaylistId.startsWith("RDAMVM") && currentPlaylistId.length() > 6) {
                        String sourceVideoId = currentPlaylistId.substring(6);
                        boolean alreadyPresent = false;
                        for (PlaylistTrack pt : mapped) {
                            if (sourceVideoId.equals(pt.videoId)) { alreadyPresent = true; break; }
                        }
                        if (!alreadyPresent) {
                            mapped.add(0, new PlaylistTrack(
                                    sourceVideoId,
                                    currentPlaylistTitle != null ? currentPlaylistTitle : "",
                                    currentPlaylistSubtitle != null ? currentPlaylistSubtitle : "",
                                    "--:--",
                                    currentPlaylistThumbnail != null ? currentPlaylistThumbnail : ""
                            ));
                        }
                    }
                    hideNoConnectionState();
                    cacheTracks(playlistId, mapped, true);
                    renderTracks(mapped, playlistId, false);
                }

                @Override
                public void onError(@NonNull String error) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    // Try loading from cache if available
                    List<PlaylistTrack> cached = sanitizeTracksForPlaylist(
                            playlistId, loadCachedTracks(playlistId));
                    if (!cached.isEmpty()) {
                        renderTracks(cached, playlistId, true);
                        return;
                    }
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore);
                }
            });
            return;
        }

        youTubeMusicService.fetchPlaylistTracks(effectiveAccessToken, playlistId, requestedLimit, new YouTubeMusicService.PlaylistTracksCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistTrackResult> tracks) {
                if (!isAdded()) {
                    return;
                }

                playlistTracksRequestedLimit = requestedLimit;
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = tracks.size() >= requestedLimit
                        && requestedLimit < PLAYLIST_TRACKS_FETCH_MAX_LIMIT;

                List<PlaylistTrack> raw = mergeTrackMetadataFromCache(playlistId, mapTracks(tracks));
                cacheTracks(playlistId, raw, isFetchResultComplete(tracks.size(), requestedLimit));
                List<PlaylistTrack> mapped = sanitizeTracksForPlaylist(playlistId, raw);
                renderTracks(mapped, playlistId, false);
            }

            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) {
                    return;
                }

                playlistTracksLoadMoreInFlight = false;

                if (!cachedTracks.isEmpty()) {
                    renderTracks(cachedTracks, playlistId, true);
                    return;
                }

                List<PlaylistTrack> staleTracks = sanitizeTracksForPlaylist(
                        playlistId,
                        loadCachedTracksInternal(playlistId, true)
                );
                if (!staleTracks.isEmpty()) {
                    renderTracks(staleTracks, playlistId, true);
                    return;
                }

                String refreshedToken = resolveYoutubeAccessToken("");
                if (!TextUtils.isEmpty(refreshedToken)
                        && !TextUtils.equals(refreshedToken, effectiveAccessToken)) {
                    bindTrackList(playlistId, refreshedToken, forceRefresh, loadMore);
                    return;
                }

                notifyHeaderChanged();
                currentTracks.clear();
                trackAdapter.submitTracks(currentTracks);
                currentTrackIndex = -1;
                miniPlaying = false;
                syncTrackStateFromPlayer();
                revealPlaylistContentIfNeeded(true);
                playlistTracksCanLoadMore = false;
            }
        });
    }

    private int resolveTrackFetchLimit(boolean forceRefresh, boolean loadMore) {
        if (forceRefresh) {
            playlistTracksRequestedLimit = PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT;
            playlistTracksCanLoadMore = false;
            return playlistTracksRequestedLimit;
        }

        if (loadMore) {
            int next = Math.min(
                    PLAYLIST_TRACKS_FETCH_MAX_LIMIT,
                    Math.max(PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT, playlistTracksRequestedLimit) + PLAYLIST_TRACKS_FETCH_STEP
            );
            return Math.max(PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT, next);
        }

        return Math.max(PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT, playlistTracksRequestedLimit);
    }

    private void maybeUpdateOfflineReadyState() {
        if (!isAdded()) {
            return;
        }

        final String playlistIdSnapshot = currentPlaylistId;
        final List<PlaylistTrack> tracksSnapshot = new ArrayList<>(currentTracks);
        // Skip computation entirely if tracks haven't loaded yet — avoids
        // persisting a false negative (or positive) based on empty data.
        if (tracksSnapshot.isEmpty()) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final long generation = offlineReadyStateGeneration.incrementAndGet();

        offlineReadyStateExecutor.execute(() -> {
            boolean complete = computeOfflineReadyState(appContext, tracksSnapshot);
            mainHandler.post(() -> {
                if (!isAdded()
                        || generation != offlineReadyStateGeneration.get()
                        || !TextUtils.equals(playlistIdSnapshot, currentPlaylistId)) {
                    return;
                }

                persistOfflineCompleteStateForCurrentPlaylist(complete);
                notifyHeaderStateChanged();
            });
        });
    }

    private boolean computeOfflineReadyState(
            @NonNull Context appContext,
            @NonNull List<PlaylistTrack> tracksSnapshot
    ) {
        if (tracksSnapshot.isEmpty()) {
            // Track list not loaded yet — do NOT claim complete.
            // Return false so we don't persist a false positive.
            return false;
        }

        int eligibleCount = 0;
        int offlineCount = 0;

        for (PlaylistTrack track : tracksSnapshot) {
            if (track == null || TextUtils.isEmpty(track.videoId)) {
                continue;
            }

            eligibleCount++;
            if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)) {
                offlineCount++;
            }
        }

        return eligibleCount <= 0 || offlineCount >= eligibleCount;
    }

    private boolean isPlaylistCacheReadyForOffline(@NonNull String playlistId, int expectedTracks) {
        if (!isAdded() || TextUtils.isEmpty(playlistId) || expectedTracks <= 0) {
            return false;
        }

        List<PlaylistTrack> cachedTracks = sanitizeTracksForPlaylist(
                playlistId,
                loadCachedTracksInternal(playlistId, true)
        );
        return cachedTracks.size() >= expectedTracks;
    }

    private void persistOfflineCompleteStateForCurrentPlaylist(boolean complete) {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return;
        }
        getCachePrefs().edit()
                .putBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + currentPlaylistId, complete)
                .apply();
    }

    private boolean isPersistedOfflineCompleteStateForCurrentPlaylist() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return false;
        }

        return getCachePrefs().getBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + currentPlaylistId, false);
    }

    private void startOfflinePlaylistDownload(boolean userInitiated) {
        if (!isAdded()) {
            return;
        }
        if (currentTracks.isEmpty()) {
            if (userInitiated) {
                
            }
            return;
        }

        // Snapshot track data on main thread, then offload disk I/O to background
        final List<PlaylistTrack> tracksSnapshot = new ArrayList<>(currentTracks);
        final Context appContext = requireContext().getApplicationContext();
        final String playlistId = currentPlaylistId;
        final String playlistTitle = currentPlaylistTitle;
        final String offlineTag = currentPlaylistOfflineTag();
        SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        final boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);

        // Optimistic UI: show queued state immediately
        offlineDownloadQueued = true;
        notifyHeaderChanged();

        trackStateLookupExecutor.execute(() -> {
            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<String> artists = new ArrayList<>();
            ArrayList<String> durations = new ArrayList<>();
            int skippedAlreadyOffline = 0;
            int totalWithVideoId = 0;
            for (PlaylistTrack track : tracksSnapshot) {
                if (!TextUtils.isEmpty(track.videoId)) {
                    totalWithVideoId++;
                    boolean alreadyOffline = OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration);
                    if (alreadyOffline) {
                        skippedAlreadyOffline++;
                        continue;
                    }
                    ids.add(track.videoId);
                    titles.add(track.title);
                    artists.add(track.artist);
                    durations.add(track.duration);
                }
            }
            if (ids.isEmpty()) {
                mainHandler.post(() -> {
                    offlineDownloadQueued = false;
                    notifyHeaderChanged();
                });
                return;
            }

            final int finalSkipped = skippedAlreadyOffline;
            final int finalTotal = totalWithVideoId;
            mainHandler.post(() -> {
                if (!isAdded()) return;

                String uniqueName = OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME;
                Data input = new Data.Builder()
                        .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, playlistId)
                        .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, playlistTitle)
                        .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, ids.toArray(new String[0]))
                        .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, titles.toArray(new String[0]))
                        .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, artists.toArray(new String[0]))
                        .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, durations.toArray(new String[0]))
                        .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, finalSkipped)
                        .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, finalTotal)
                        .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, userInitiated)
                        .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, false)
                        .build();

                Constraints constraints = new Constraints.Builder()
                        .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                        .build();

                OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                        .setInputData(input)
                        .setConstraints(constraints)
                        .addTag(uniqueName)
                        .addTag(offlineTag)
                        .build();

                enqueueOfflineDownloadUniqueWork(uniqueName, request);

                offlineDownloadQueued = true;
                notifyHeaderChanged();
                observeOfflineDownload(uniqueName);

                notifyMusicPlayerOfflineChanged();
            });
        });
    }

    private void launchOfflineImportPicker(@NonNull List<String> videoIds) {
        if (!isAdded() || offlineImportLauncher == null) {
            return;
        }

        pendingImportTrackIds.clear();
        pendingImportTrackIds.addAll(videoIds);
        notifyHeaderChanged();

        try {
            offlineImportLauncher.launch(new String[] {
                    "audio/mpeg",
                    "audio/mp4",
                    "audio/*",
                    "application/octet-stream"
            });
        } catch (Exception e) {
            pendingImportTrackIds.clear();
            
        }
    }

    private void handleOfflineImportSelection(@Nullable List<Uri> uris) {
        if (!isAdded()) {
            return;
        }

        if (uris == null || uris.isEmpty()) {
            pendingImportTrackIds.clear();
            
            maybeUpdateOfflineReadyState();
            return;
        }

        if (pendingImportTrackIds.isEmpty()) {
            
            return;
        }

        int imported = 0;
        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }

            String trackId = resolveTrackIdFromUri(uri);
            if (TextUtils.isEmpty(trackId) || !pendingImportTrackIds.contains(trackId)) {
                continue;
            }

            if (copyUriToOfflineFile(uri, trackId)) {
                imported++;
            }
        }

        pendingImportTrackIds.clear();
        maybeUpdateOfflineReadyState();
        if (trackAdapter != null) {
            trackAdapter.invalidateTrackStateCache();
            trackAdapter.submitTracks(currentTracks);
        }

        if (imported > 0) {
            
        } else {
            
        }
    }

    @NonNull
    private ArrayList<String> buildCurrentVideoIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (PlaylistTrack track : currentTracks) {
            if (!TextUtils.isEmpty(track.videoId)) {
                ids.add(track.videoId);
            }
        }
        return ids;
    }

    @NonNull
    private String resolvePlaylistType(@NonNull String playlistId) {
        if (isLocalFilesContext(playlistId)) return "local_files";
        if (isFavoritesPlaylistContext(playlistId)) return "favorites";
        if (isCustomPlaylistContext(playlistId)) return "custom";
        return "youtube";
    }

    private boolean copyUriToOfflineFile(@NonNull Uri uri, @NonNull String trackId) {
        if (!isAdded()) {
            return false;
        }

        File target = OfflineAudioStore.getOfflineAudioFile(requireContext(), trackId);
        File parent = target.getParentFile();
        if (parent == null) {
            return false;
        }
        if (!parent.exists() && !parent.mkdirs()) {
            return false;
        }

        File temp = new File(target.getAbsolutePath() + ".tmp");
        ContentResolver resolver = requireContext().getContentResolver();
        boolean success = false;

        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            if (in == null) {
                return false;
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();

            if (!temp.isFile() || temp.length() <= 0L) {
                return false;
            }

            if (target.exists() && !target.delete()) {
                return false;
            }

            success = temp.renameTo(target);
            if (success) {
                OfflineAudioStore.markOfflineAudioState(trackId, true);
            }
            return success;
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "handleOfflineImport copy failed", e);
            return false;
        } finally {
            if (!success && temp.exists()) {
                temp.delete();
            }
        }
    }

    @NonNull
    private String resolveTrackIdFromUri(@NonNull Uri uri) {
        String displayName = resolveDisplayName(uri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = uri.getLastPathSegment();
        }

        if (TextUtils.isEmpty(displayName)) {
            return "";
        }

        String decoded = Uri.decode(displayName).trim();
        if (decoded.isEmpty()) {
            return "";
        }

        int lastDot = decoded.lastIndexOf('.');
        if (lastDot > 0) {
            decoded = decoded.substring(0, lastDot).trim();
        }

        return decoded;
    }

    @NonNull
    private String resolveDisplayName(@NonNull Uri uri) {
        ContentResolver resolver = requireContext().getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    return name == null ? "" : name;
                }
            }
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "resolveDisplayName failed", e);
        }
        return "";
    }

    private void observeOfflineDownload(@NonNull String uniqueName) {
        observeOfflineDownload(uniqueName, true);
    }

    private void observeOfflineDownload(@NonNull String uniqueName, boolean notifyTerminalToasts) {
        if (!isAdded()) {
            return;
        }

        String observeTag = uniqueName;

        if (TextUtils.equals(observingOfflineUniqueName, observeTag) && offlineDownloadObserver != null) {
            if (notifyTerminalToasts) {
                offlineObserverNotifyTerminalToasts = true;
            }
            return;
        }

        stopObservingOfflineDownload();
        observingOfflineUniqueName = observeTag;
        offlineObserverNotifyTerminalToasts = notifyTerminalToasts;

        offlineDownloadObserver = workInfos -> {
            if (!isAdded()) {
                return;
            }

            if (workInfos == null || workInfos.isEmpty()) {
                if (!offlineDownloadQueued) {
                    setOfflineDownloadVisualState(false, "");
                    notifyHeaderChanged();
                    maybeUpdateOfflineReadyState();
                }
                return;
            }

            List<WorkInfo> runningInfos = new ArrayList<>();
            WorkInfo terminalInfo = null;
            int queuedCount = 0;
            int enqueuedCount = 0;
            int blockedCount = 0;

            for (WorkInfo candidate : workInfos) {
                WorkInfo.State state = candidate.getState();
                if (state == WorkInfo.State.RUNNING) {
                    runningInfos.add(candidate);
                }
                if (state == WorkInfo.State.ENQUEUED) {
                    enqueuedCount++;
                    queuedCount++;
                }
                if (state == WorkInfo.State.BLOCKED) {
                    blockedCount++;
                    queuedCount++;
                }
                if (state == WorkInfo.State.SUCCEEDED
                        || state == WorkInfo.State.FAILED
                        || state == WorkInfo.State.CANCELLED) {
                    terminalInfo = candidate;
                }
            }

            if (!runningInfos.isEmpty()) {
                int done = 0;
                int total = 0;
                int downloaded = 0;
                String currentId = "";
                String dlPlaylistTitle = "";
                Map<String, Float> progressByTrackId = new HashMap<>();

                for (WorkInfo runningInfo : runningInfos) {
                    Data progress = runningInfo.getProgress();
                    done = Math.max(done, progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DONE, 0));
                    total = Math.max(total, progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_TOTAL, 0));
                    downloaded = Math.max(downloaded, progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DOWNLOADED, 0));

                    String candidateCurrentId = progress.getString(OfflinePlaylistDownloadWorker.PROGRESS_CURRENT_ID);
                    if (TextUtils.isEmpty(currentId) && !TextUtils.isEmpty(candidateCurrentId)) {
                        currentId = candidateCurrentId.trim();
                    }

                    String candidatePlaylistTitle = progress.getString(OfflinePlaylistDownloadWorker.PROGRESS_PLAYLIST_TITLE);
                    if (TextUtils.isEmpty(dlPlaylistTitle) && !TextUtils.isEmpty(candidatePlaylistTitle)) {
                        dlPlaylistTitle = candidatePlaylistTitle.trim();
                    }

                    String[] activeIds = progress.getStringArray(OfflinePlaylistDownloadWorker.PROGRESS_ACTIVE_IDS);
                    float[] activeFractions = progress.getFloatArray(OfflinePlaylistDownloadWorker.PROGRESS_ACTIVE_FRACTIONS);
                    if (activeIds == null || activeIds.length == 0) {
                        continue;
                    }

                    for (int idx = 0; idx < activeIds.length; idx++) {
                        String activeId = activeIds[idx];
                        if (TextUtils.isEmpty(activeId)) {
                            continue;
                        }
                        String normalizedId = activeId.trim();
                        float value = (activeFractions != null && idx < activeFractions.length)
                                ? activeFractions[idx]
                                : 0f;
                        float normalizedValue = Math.max(0f, Math.min(1f, value));
                        Float previous = progressByTrackId.get(normalizedId);
                        if (previous == null || normalizedValue > previous) {
                            progressByTrackId.put(normalizedId, normalizedValue);
                        }
                    }
                }

                String[] activeIds = progressByTrackId.keySet().toArray(new String[0]);
                if (TextUtils.isEmpty(currentId) && activeIds.length > 0) {
                    currentId = activeIds[0];
                }

                String effectivePlaylistTitle = dlPlaylistTitle == null ? "" : dlPlaylistTitle.trim();
                if (TextUtils.isEmpty(effectivePlaylistTitle)) {
                    effectivePlaylistTitle = currentPlaylistTitle == null ? "" : currentPlaylistTitle.trim();
                }
                if (TextUtils.isEmpty(effectivePlaylistTitle)) {
                    effectivePlaylistTitle = "Playlist";
                }

                int safeTotal = total > 0 ? total : Math.max(0, currentTracks.size());
                int safeDownloaded = Math.max(0, downloaded);
                if (safeTotal > 0) {
                    safeDownloaded = Math.min(safeDownloaded, safeTotal);
                }

                // Only update track-level visual state if worker has emitted real progress
                if (total > 0 || activeIds.length > 0) {
                    setOfflineDownloadVisualState(true, currentId, activeIds, progressByTrackId);
                } else if (!offlineDownloadRunning) {
                    // First observer fire before worker emits — mark running without resetting tracks
                    setOfflineDownloadVisualState(true, currentId,
                            offlineDownloadingTrackIds.toArray(new String[0]), offlineTrackProgressFractions);
                }
                offlineDownloadQueued = queuedCount > 0;

                if (!isInternetAvailable()) {
                    setOfflineDownloadVisualState(false, "");
                    notifyHeaderChanged();
                    return;
                }

                String playlistNameMarkup = " <b>" + TextUtils.htmlEncode(effectivePlaylistTitle) + "</b>";
                String status = "Descargando playlist" + playlistNameMarkup + " " + safeDownloaded + "/" + safeTotal;
                if (done > safeDownloaded) {
                    status += " • " + done + " revisadas";
                }
                notifyHeaderChanged();
                return;
            }

            if (queuedCount > 0) {
                if (!offlineDownloadRunning) {
                    String optimisticCurrentId = offlineDownloadingTrackId;
                    if (TextUtils.isEmpty(optimisticCurrentId) && !offlineDownloadingTrackIds.isEmpty()) {
                        optimisticCurrentId = offlineDownloadingTrackIds.iterator().next();
                    }

                    String[] optimisticIds = offlineDownloadingTrackIds.isEmpty()
                            ? (TextUtils.isEmpty(optimisticCurrentId)
                            ? new String[0]
                            : new String[] { optimisticCurrentId })
                            : offlineDownloadingTrackIds.toArray(new String[0]);

                    setOfflineDownloadVisualState(true, optimisticCurrentId, optimisticIds, null);
                }
                offlineDownloadQueued = true;

                if (enqueuedCount == 0 && blockedCount > 0) {
                    Log.w(TAG_OFFLINE_DOWNLOAD,
                            "queue:block_detected blocked=" + blockedCount + " uniqueName=" + uniqueName + " -> resetting");

                    resetBlockedOfflineQueues();

                    offlineDownloadQueued = false;
                    notifyHeaderChanged();
                    return;
                }

                if (!isInternetAvailable()) {
                    notifyHeaderChanged();
                    return;
                }

                notifyHeaderChanged();
                return;
            }

            offlineDownloadQueued = false;

            if (terminalInfo == null) {
                setOfflineDownloadVisualState(false, "");
                notifyHeaderChanged();
                return;
            }

            if (terminalInfo.getState() == WorkInfo.State.SUCCEEDED) {
                setOfflineDownloadVisualState(false, "");
                Data output = terminalInfo.getOutputData();
                int downloadedOut = output.getInt(OfflinePlaylistDownloadWorker.OUTPUT_DOWNLOADED, 0);
                int totalOut = output.getInt(OfflinePlaylistDownloadWorker.OUTPUT_TOTAL, currentTracks.size());
                String reason = output.getString(OfflinePlaylistDownloadWorker.OUTPUT_REASON);

                if (!offlineObserverNotifyTerminalToasts) {
                    stopObservingOfflineDownload();
                    refreshVisibleTrackRows();
                    maybeUpdateOfflineReadyState();
                    return;
                }

                notifyHeaderChanged();
                stopObservingOfflineDownload();
                refreshVisibleTrackRows();
                maybeUpdateOfflineReadyState();
                return;
            }

            if (terminalInfo.getState() == WorkInfo.State.FAILED || terminalInfo.getState() == WorkInfo.State.CANCELLED) {
                Log.e(TAG_OFFLINE_DOWNLOAD, "terminal_state=" + terminalInfo.getState());
                setOfflineDownloadVisualState(false, "");

                stopObservingOfflineDownload();
                maybeUpdateOfflineReadyState();

                if (terminalInfo.getState() == WorkInfo.State.FAILED
                        && isCurrentPlaylistOfflineAutoEnabled()
                        && isInternetAvailable()) {
                    Log.w(TAG_OFFLINE_DOWNLOAD, "terminal_failed: auto-retrying download for playlist=" + currentPlaylistId);
                    startOfflinePlaylistDownload(false);
                    return;
                }

                if (offlineObserverNotifyTerminalToasts) {
                    notifyHeaderChanged();
                }
            }
        };

        WorkManager.getInstance(requireContext().getApplicationContext())
            .getWorkInfosForUniqueWorkLiveData(observeTag)
                .observe(getViewLifecycleOwner(), offlineDownloadObserver);
    }

    private void stopObservingOfflineDownload() {
        if (!isAdded() || offlineDownloadObserver == null || TextUtils.isEmpty(observingOfflineUniqueName)) {
            offlineDownloadObserver = null;
            observingOfflineUniqueName = null;
            offlineObserverNotifyTerminalToasts = false;
            return;
        }

        WorkManager.getInstance(requireContext().getApplicationContext())
            .getWorkInfosForUniqueWorkLiveData(observingOfflineUniqueName)
                .removeObserver(offlineDownloadObserver);

        offlineDownloadObserver = null;
        observingOfflineUniqueName = null;
        offlineObserverNotifyTerminalToasts = false;
    }

    private void enqueueOfflineDownloadUniqueWork(
            @NonNull String uniqueName,
            @NonNull OneTimeWorkRequest request
    ) {
        WorkManager manager = WorkManager.getInstance(requireContext().getApplicationContext());
        manager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    private void resetBlockedOfflineQueues() {
        WorkManager manager = WorkManager.getInstance(requireContext().getApplicationContext());
        manager.cancelUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME);
        manager.cancelUniqueWork(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME);
        manager.cancelAllWorkByTag(currentPlaylistOfflineTag());
        manager.pruneWork();
    }

    @NonNull
    private String resolveYoutubeAccessToken(@Nullable String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (!token.isEmpty()) {
            return token;
        }
        if (!isAdded()) {
            return "";
        }

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String persisted = prefs.getString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, "");
        return persisted == null ? "" : persisted.trim();
    }

    private void scheduleTracksTokenRetry(@NonNull String playlistId) {
        if (!isAdded() || pendingTracksTokenRetryRunnable != null) {
            return;
        }

        if (pendingTracksTokenRetry >= MAX_TRACKS_TOKEN_RETRY) {
            return;
        }

        pendingTracksTokenRetry++;
        pendingTracksTokenRetryRunnable = new Runnable() {
            @Override
            public void run() {
                pendingTracksTokenRetryRunnable = null;
                if (!isAdded()) {
                    return;
                }

                String token = resolveYoutubeAccessToken("");
                if (!token.isEmpty()) {
                    pendingTracksTokenRetry = 0;
                    refreshPlaylistMeta(playlistId, token);
                    bindTrackList(playlistId, token);
                    return;
                }

                if (pendingTracksTokenRetry < MAX_TRACKS_TOKEN_RETRY) {
                    scheduleTracksTokenRetry(playlistId);
                    return;
                }

                notifyHeaderChanged();
                revealPlaylistContentIfNeeded(true);
            }
        };

        mainHandler.postDelayed(pendingTracksTokenRetryRunnable, TRACKS_TOKEN_RETRY_DELAY_MS);
    }

    private void cancelPendingTracksTokenRetry() {
        if (pendingTracksTokenRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingTracksTokenRetryRunnable);
            pendingTracksTokenRetryRunnable = null;
        }
        pendingTracksTokenRetry = 0;
    }

    @NonNull
    private List<PlaylistTrack> mapTracks(@NonNull List<YouTubeMusicService.PlaylistTrackResult> tracks) {
        List<PlaylistTrack> mapped = new ArrayList<>();
        for (YouTubeMusicService.PlaylistTrackResult track : tracks) {
            String duration = normalizeDurationLabel(track.duration);
            mapped.add(new PlaylistTrack(
                    track.videoId,
                    track.title,
                    track.artist,
                    duration,
                    track.thumbnailUrl
            ));
        }
        return mapped;
    }

    @NonNull
    private List<PlaylistTrack> mergeTrackMetadataFromCache(
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> source
    ) {
        if (!isAdded() || source.isEmpty() || playlistId.isEmpty()) {
            return source;
        }

        List<PlaylistTrack> cached = loadCachedTracksInternal(playlistId, true);
        if (cached.isEmpty()) {
            return source;
        }

        Map<String, PlaylistTrack> cachedById = new HashMap<>();
        for (PlaylistTrack track : cached) {
            if (track == null || TextUtils.isEmpty(track.videoId)) {
                continue;
            }
            cachedById.put(track.videoId, track);
        }

        if (cachedById.isEmpty()) {
            return source;
        }

        List<PlaylistTrack> merged = new ArrayList<>(source.size());
        for (PlaylistTrack track : source) {
            if (track == null || TextUtils.isEmpty(track.videoId)) {
                continue;
            }

            PlaylistTrack cachedTrack = cachedById.get(track.videoId);
            String mergedDuration = normalizeDurationLabel(track.duration);
            if (mergedDuration.isEmpty() && cachedTrack != null) {
                mergedDuration = normalizeDurationLabel(cachedTrack.duration);
            }

            String mergedImageUrl = track.imageUrl == null ? "" : track.imageUrl.trim();
            if (mergedImageUrl.isEmpty() && cachedTrack != null && !TextUtils.isEmpty(cachedTrack.imageUrl)) {
                mergedImageUrl = cachedTrack.imageUrl.trim();
            }

            merged.add(new PlaylistTrack(
                    track.videoId,
                    track.title,
                    track.artist,
                    mergedDuration,
                    mergedImageUrl
            ));
        }

        return merged;
    }

    @NonNull
    private List<PlaylistTrack> sanitizeTracksForPlaylist(
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> source
    ) {
        if (isFavoritesPlaylistContext(playlistId) && isAdded()) {
            List<FavoritesPlaylistStore.FavoriteTrack> favorites = FavoritesPlaylistStore.loadFavorites(requireContext());
            List<PlaylistTrack> mapped = new ArrayList<>(favorites.size());
            for (FavoritesPlaylistStore.FavoriteTrack track : favorites) {
                mapped.add(new PlaylistTrack(track.videoId, track.title, track.artist, track.duration, track.imageUrl));
            }
            java.util.Map<String, PlaylistOverrideStore.Override> favOverrides =
                    PlaylistOverrideStore.INSTANCE.getOverrides(requireContext(), playlistId);
            if (!favOverrides.isEmpty()) {
                mapped = PlaylistOverrideStore.INSTANCE.applyOverridesTo(
                        mapped, favOverrides,
                        track -> track.videoId,
                        ovr -> new PlaylistTrack(
                                ovr.getReplacementVideoId(), ovr.getTitle(),
                                ovr.getArtist(), ovr.getDuration(), ovr.getImageUrl()
                        )
                );
            }
            return mapped;
        }

        if (isCustomPlaylistContext(playlistId) && isAdded()) {
            String name = playlistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            List<FavoritesPlaylistStore.FavoriteTrack> custom = CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(requireContext(), name);
            List<PlaylistTrack> mapped = new ArrayList<>(custom.size());
            for (FavoritesPlaylistStore.FavoriteTrack track : custom) {
                mapped.add(new PlaylistTrack(track.videoId, track.title, track.artist, track.duration, track.imageUrl));
            }
            java.util.Map<String, PlaylistOverrideStore.Override> customOverrides =
                    PlaylistOverrideStore.INSTANCE.getOverrides(requireContext(), playlistId);
            if (!customOverrides.isEmpty()) {
                mapped = PlaylistOverrideStore.INSTANCE.applyOverridesTo(
                        mapped, customOverrides,
                        track -> track.videoId,
                        ovr -> new PlaylistTrack(
                                ovr.getReplacementVideoId(), ovr.getTitle(),
                                ovr.getArtist(), ovr.getDuration(), ovr.getImageUrl()
                        )
                );
            }
            return mapped;
        }

        // For YouTube (non-local) playlists, apply persisted overrides
        if (isAdded() && !playlistId.isEmpty()) {
            java.util.Map<String, PlaylistOverrideStore.Override> overridesMap =
                    PlaylistOverrideStore.INSTANCE.getOverrides(requireContext(), playlistId);
            
            List<PlaylistTrack> overridden = PlaylistOverrideStore.INSTANCE.applyOverridesTo(
                    new ArrayList<>(source),
                    overridesMap,
                    track -> track.videoId,
                    ovr -> new PlaylistTrack(
                            ovr.getReplacementVideoId(),
                            ovr.getTitle(),
                            ovr.getArtist(),
                            ovr.getDuration(),
                            ovr.getImageUrl()
                    )
            );
            return overridden;
        }
        return new ArrayList<>(source);
    }

    @NonNull
    private static String decodeHtmlEntities(@Nullable String text) {
        if (TextUtils.isEmpty(text)) return "";
        if (!text.contains("&")) return text;
        return androidx.core.text.HtmlCompat.fromHtml(text, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private void persistEnrichedLocalTracks(
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> tracks,
            boolean isFavorites
    ) {
        if (!isAdded() || tracks.isEmpty()) return;
        try {
            if (isFavorites) {
                List<FavoritesPlaylistStore.FavoriteTrack> updated = new ArrayList<>(tracks.size());
                for (PlaylistTrack track : tracks) {
                    updated.add(new FavoritesPlaylistStore.FavoriteTrack(
                            track.videoId, track.title, track.artist, track.duration, track.imageUrl
                    ));
                }
                FavoritesPlaylistStore.storeFavorites(requireContext(), updated);
            } else if (isCustomPlaylistContext(playlistId)) {
                String name = playlistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
                List<FavoritesPlaylistStore.FavoriteTrack> updated = new ArrayList<>(tracks.size());
                for (PlaylistTrack track : tracks) {
                    updated.add(new FavoritesPlaylistStore.FavoriteTrack(
                            track.videoId, track.title, track.artist, track.duration, track.imageUrl
                    ));
                }
                CustomPlaylistsStore.INSTANCE.savePlaylist(requireContext(), name, updated);
            }
        } catch (Exception e) {
            Log.e("PlaylistDetailFragment", "Error persisting enriched tracks", e);
        }
    }

    @NonNull
    private String normalizeLikedPlaylistId(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle
    ) {
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistId)) {
            return playlistId;
        }

        String normalizedTitle = playlistTitle.trim().toLowerCase(Locale.US);
        String normalizedSubtitle = playlistSubtitle.trim().toLowerCase(Locale.US);
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
            return playlistId;
        }

        String title = normalizedTitle;
        String subtitle = normalizedSubtitle;
        if (title.contains("gusta")
                || title.contains("liked")
                || title.contains("musica que te gusto")
                || subtitle.contains("gusta")
                || subtitle.contains("liked")
                || subtitle.contains("autogenerada")) {
            return YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID;
        }

        return playlistId;
    }

    private boolean isLikedPlaylistContext(@NonNull String playlistId) {
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
            return true;
        }

        String title = currentPlaylistTitle == null ? "" : currentPlaylistTitle.toLowerCase(Locale.US);
        String subtitle = currentPlaylistSubtitle == null ? "" : currentPlaylistSubtitle.toLowerCase(Locale.US);
        return title.contains("gusta")
                || title.contains("liked")
                || title.contains("musica que te gusto")
                || subtitle.contains("gusta")
                || subtitle.contains("liked")
                || subtitle.contains("autogenerada");
    }

    private boolean isFavoritesPlaylistContext(@NonNull String playlistId) {
        return FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistId);
    }

    private boolean isCustomPlaylistContext(@NonNull String playlistId) {
        return playlistId.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX);
    }

    private boolean isLocalFilesContext(@NonNull String playlistId) {
        return LocalFilesStore.PLAYLIST_ID.equals(playlistId);
    }

    public void externalRefreshFavoritesIfActive() {
        externalRefreshFavoritesIfActive(null);
    }

    public void externalRefreshFavoritesIfActive(@Nullable String videoId) {
        if (!isAdded()) return;

        if (isFavoritesPlaylistContext(currentPlaylistId) || isCustomPlaylistContext(currentPlaylistId)) {
            List<PlaylistTrack> refreshed = sanitizeTracksForPlaylist(currentPlaylistId, Collections.emptyList());
            renderTracks(refreshed, currentPlaylistId, true);
            replacePlayerQueueWithCurrentOrder();
        } else if (videoId != null && currentTracks != null) {
            for (int i = 0; i < currentTracks.size(); i++) {
                if (TextUtils.equals(currentTracks.get(i).videoId, videoId)) {
                    if (trackAdapter != null) {
                        trackAdapter.invalidateTrackStateCache(videoId);
                        trackAdapter.notifyItemChanged(i);
                    }
                    break;
                }
            }
        }
    }

    public void refreshForOfflineModeChange() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) return;
        renderTracks(new ArrayList<>(originalTracks), currentPlaylistId, true);
    }

    private void renderTracks(
            @NonNull List<PlaylistTrack> tracks,
            @NonNull String playlistId,
            boolean fromCache
    ) {
        String selectedVideoId = getCurrentTrackVideoId();
        originalTracks.clear();
        for (PlaylistTrack t : tracks) {
            if (!isLikelyShort(t)) {
                originalTracks.add(t);
            }
        }

        currentTracks.clear();
        boolean offlineModeActive = isAdded() && requireContext()
                .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE)
                .getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false);
        if (offlineModeActive) {
            // Offload disk I/O to background thread to avoid blocking the UI
            final List<PlaylistTrack> snapshot = new ArrayList<>(originalTracks);
            final Context offlineCtx = requireContext().getApplicationContext();
            trackStateLookupExecutor.execute(() -> {
                List<PlaylistTrack> filtered = new ArrayList<>();
                for (PlaylistTrack t : snapshot) {
                    if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                    String vid = t.videoId.trim();
                    if (OfflineAudioStore.hasOfflineAudio(offlineCtx, vid)) {
                        filtered.add(t);
                    }
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    currentTracks.clear();
                    currentTracks.addAll(filtered);
                    finalizeRenderTracks(playlistId, fromCache, selectedVideoId);
                });
            });
            return;
        } else {
            currentTracks.addAll(originalTracks);
        }

        finalizeRenderTracks(playlistId, fromCache, selectedVideoId);
    }

    private void finalizeRenderTracks(
            @NonNull String playlistId,
            boolean fromCache,
            @Nullable String selectedVideoId
    ) {
        if (pendingOfflineToggle && !currentTracks.isEmpty()) {
            pendingOfflineToggle = false;
            onOfflineTogglePressed();
        }

        boolean isYouTubePlaylist = !isFavoritesPlaylistContext(playlistId)
                && !isCustomPlaylistContext(playlistId)
                && !isLikedPlaylistContext(playlistId);

        // Radio playlists: skip 2x2 grid, use single thumbnail image
        if (isRadioContext) {
            headerGridUrls = new ArrayList<>();
            trackAdapter.submitTracks(currentTracks);
            rebuildPlaybackQueue();
            prefetchStreamUrlsForTracks(currentTracks, 5);
            int totalSeconds = 0;
            for (PlaylistTrack track : currentTracks) {
                totalSeconds += parseDurationSeconds(track.duration);
            }
            currentMeta = new PlaylistMeta(
                currentMeta.ownerLabel,
                currentTracks.size(),
                formatTotalDuration(totalSeconds),
                currentMeta.visibilityLabel,
                currentMeta.ageLabel
            );
            headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
            notifyHeaderChanged();
            maybeUpdateOfflineReadyState();
            if (currentTracks.isEmpty()) {
                currentTrackIndex = -1;
                miniPlaying = false;
            }
            revealPlaylistContentIfNeeded(true);
            return;
        }

        // For YouTube playlists, use persisted grid URLs so the 2x2 never changes
        List<String> gridUrls = null;
        if (isYouTubePlaylist && isAdded()) {
            gridUrls = loadPersistedGridUrls(playlistId);
        }

        if (gridUrls == null || gridUrls.size() < 4) {
            gridUrls = new ArrayList<>(4);
            Set<String> seenUrls = new HashSet<>();
            for (PlaylistTrack track : currentTracks) {
                if (track == null || TextUtils.isEmpty(track.imageUrl)) {
                    continue;
                }
                String url = track.imageUrl.trim();
                if (!url.isEmpty() && seenUrls.add(url) && gridUrls.size() < 4) {
                    gridUrls.add(url);
                }
            }
            // Persist for YouTube playlists so grid survives track reordering/removal
            if (isYouTubePlaylist && gridUrls.size() >= 4 && isAdded()) {
                persistGridUrls(playlistId, gridUrls);
            }
        }
        headerGridUrls = gridUrls;

        trackAdapter.submitTracks(currentTracks);
        rebuildPlaybackQueue();

        // Prefetch stream URLs for first few tracks to reduce playback start delay
        prefetchStreamUrlsForTracks(currentTracks, 5);

        int totalSeconds = 0;
        for (PlaylistTrack track : currentTracks) {
            totalSeconds += parseDurationSeconds(track.duration);
        }
        currentMeta = new PlaylistMeta(
            currentMeta.ownerLabel,
            currentTracks.size(),
            formatTotalDuration(totalSeconds),
            currentMeta.visibilityLabel,
            currentMeta.ageLabel
        );
        headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
        notifyHeaderChanged();
        maybeUpdateOfflineReadyState();

        if (currentTracks.isEmpty()) {
            currentTrackIndex = -1;
            miniPlaying = false;
        } else {
            int mappedIndex = findTrackIndexByVideoId(currentTracks, selectedVideoId);
            if (mappedIndex >= 0) {
                currentTrackIndex = mappedIndex;
            } else {
                int persistedIndex = findTrackIndexByVideoId(currentTracks, loadPersistedVideoIdForCurrentPlaylist());
                if (persistedIndex >= 0) {
                    currentTrackIndex = persistedIndex;
                    miniPlaying = false;
                } else {
                    PlaybackHistoryStore.Snapshot snapshot = loadPlaybackSnapshot();
                    int historyIndex = findTrackIndexFromSnapshot(currentTracks, snapshot);
                    if (historyIndex >= 0) {
                        currentTrackIndex = historyIndex;
                        miniPlaying = false;
                    } else if (currentTrackIndex < 0 || currentTrackIndex >= currentTracks.size()) {
                        currentTrackIndex = -1;
                        miniPlaying = false;
                    }
                }
            }
        }

        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(currentTrackIndex);
        }

        maybeAutoDownloadForCurrentPlaylist();
        maybeRestoreHiddenPlayerFromSnapshot();
        syncTrackStateFromPlayer();
        preloadImagesAndReveal();
    }

    private void preloadImagesAndReveal() {
        if (!isAdded() || !awaitingInitialPlaylistRender) {
            revealPlaylistContentIfNeeded(true);
            return;
        }

        // Collect URLs to preload: grid URLs + first 12 track thumbnails
        List<String> gridUrls = new ArrayList<>();
        for (String gridUrl : headerGridUrls) {
            if (!TextUtils.isEmpty(gridUrl)) gridUrls.add(gridUrl.trim());
        }
        List<String> trackUrls = new ArrayList<>();
        int trackLimit = Math.min(currentTracks.size(), 8);
        for (int i = 0; i < trackLimit; i++) {
            PlaylistTrack t = currentTracks.get(i);
            if (t != null && !TextUtils.isEmpty(t.imageUrl)) {
                String url = t.imageUrl.trim();
                if (!url.isEmpty()) trackUrls.add(url);
            }
        }

        int totalPreloads = gridUrls.size() + trackUrls.size();
        if (totalPreloads == 0) {
            revealPlaylistContentIfNeeded(true);
            return;
        }

        // Safety timeout: reveal after 3s even if preloads haven't all completed
        Handler uiHandler = new Handler(Looper.getMainLooper());
        uiHandler.postDelayed(() -> {
            if (isAdded() && awaitingInitialPlaylistRender) {
                revealPlaylistContentIfNeeded(true);
            }
        }, 3000L);

        Context ctx = requireContext();
        final java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(totalPreloads);
        com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> revealListener =
                new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                    Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    boolean isFirstResource) {
                if (remaining.decrementAndGet() <= 0) {
                    uiHandler.post(() -> {
                        if (isAdded() && awaitingInitialPlaylistRender)
                            revealPlaylistContentIfNeeded(true);
                    });
                }
                return false;
            }
            @Override
            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                    Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                if (remaining.decrementAndGet() <= 0) {
                    uiHandler.post(() -> {
                        if (isAdded() && awaitingInitialPlaylistRender)
                            revealPlaylistContentIfNeeded(true);
                    });
                }
                return false;
            }
        };

        // Preload grid URLs at 320px (for header)
        for (String url : gridUrls) {
            Glide.with(ctx)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(320, 320)
                    .listener(revealListener)
                    .preload();
        }

        // Preload track thumbnails with SHARED_YT_CROP + same size as loadTrackArt
        // so the cache key matches and the first scroll doesn't re-decode
        float density = ctx.getResources().getDisplayMetrics().density;
        int trackPx = Math.max(100, Math.round(50 * density));
        for (String url : trackUrls) {
            Glide.with(ctx)
                    .load(url)
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .priority(com.bumptech.glide.Priority.HIGH)
                    .override(trackPx, trackPx)
                    .listener(revealListener)
                    .preload();
        }
    }

    private void syncShuffleModeFromPlayer() {
        SongPlayerFragment player = findSongPlayerFragment();
        if (player == null || !player.isAdded()) {
            return;
        }

        boolean playerShuffleEnabled = player.externalIsShuffleEnabled();
        boolean changed = shuffleModeEnabled != playerShuffleEnabled;
        shuffleModeEnabled = playerShuffleEnabled;
        if (changed) {
            syncPlaybackQueueOrderFromPlayer(player);
        }
        persistShuffleModePreference();
    }

    private boolean loadPersistedShuffleMode() {
        if (!isAdded()) {
            return false;
        }
        SharedPreferences settings = requireContext()
                .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE);
        return settings.getBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, false);
    }

    private void persistShuffleModePreference() {
        if (!isAdded()) {
            return;
        }

        SharedPreferences settings = requireContext()
                .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE);
        boolean currentValue = settings.getBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, false);
        if (currentValue == shuffleModeEnabled) {
            return;
        }

        settings.edit()
                .putBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, shuffleModeEnabled)
                .apply();
    }

    private void syncPlaybackQueueOrderFromPlayer(@NonNull SongPlayerFragment player) {
        List<String> queueVideoIds = player.externalGetQueueVideoIds();
        if (queueVideoIds.isEmpty()) {
            return;
        }

        List<PlaylistTrack> base = new ArrayList<>(originalTracks.isEmpty() ? currentTracks : originalTracks);
        if (base.isEmpty()) {
            return;
        }

        List<PlaylistTrack> remaining = new ArrayList<>(base);
        List<PlaylistTrack> ordered = new ArrayList<>();
        for (String videoId : queueVideoIds) {
            if (videoId == null) continue;
            int index = findTrackIndexByVideoId(remaining, videoId);
            if (index >= 0) {
                ordered.add(remaining.remove(index));
            } else {
                // If not in remaining anymore (duplicate), find in base to get metadata
                int baseIdx = findTrackIndexByVideoId(base, videoId);
                if (baseIdx >= 0) {
                    ordered.add(base.get(baseIdx));
                }
            }
        }

        if (!remaining.isEmpty()) {
            ordered.addAll(remaining);
        }

        if (ordered.isEmpty()) {
            return;
        }

        playbackQueueTracks.clear();
        playbackQueueTracks.addAll(ordered);
    }

    private void replacePlayerQueueWithCurrentOrder() {
        SongPlayerFragment player = findSongPlayerFragment();
        if (player == null || !player.isAdded()) {
            return;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            return;
        }

        boolean keepPlaying = player.externalIsPlaying() || miniPlaying;
        String selectedVideoId = getCurrentTrackVideoId();
        int startIndex = findTrackIndexByVideoId(playbackQueueTracks, selectedVideoId);
        if (startIndex < 0) {
            startIndex = 0;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaylistTrack item : playbackQueueTracks) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }

        player.externalReplaceQueue(ids, titles, artists, durations, images, startIndex, keepPlaying);
        miniPlaying = keepPlaying;
    }

    private void rebuildPlaybackQueue() {
        List<PlaylistTrack> base = new ArrayList<>(originalTracks.isEmpty() ? currentTracks : originalTracks);
        if (shuffleModeEnabled && base.size() > 1) {
            Collections.shuffle(base, random);
        }
        playbackQueueTracks.clear();
        playbackQueueTracks.addAll(base);
        lastPlaybackQueueSize = currentTracks.size();
        lastPlaybackQueueShuffleState = shuffleModeEnabled;
    }

    private void ensurePlaybackQueue() {
        boolean needsRebuild = false;
        
        // Rebuild if queue is empty
        if (playbackQueueTracks.isEmpty()) {
            needsRebuild = true;
        }
        // Rebuild only if the actual size of tracks changed (not on every call)
        else if (lastPlaybackQueueSize != currentTracks.size()) {
            needsRebuild = true;
        }
        // Rebuild if shuffle mode was toggled
        else if (lastPlaybackQueueShuffleState != shuffleModeEnabled) {
            needsRebuild = true;
        }
        
        if (needsRebuild) {
            rebuildPlaybackQueue();
        }
    }

    @NonNull
    private String getCurrentTrackVideoId() {
        if (currentTrackIndex < 0 || currentTrackIndex >= currentTracks.size()) {
            return "";
        }
        String value = currentTracks.get(currentTrackIndex).videoId;
        return value == null ? "" : value;
    }

    private int findTrackIndexByVideoId(@NonNull List<PlaylistTrack> source, @NonNull String videoId) {
        if (videoId.trim().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < source.size(); i++) {
            if (videoId.equals(source.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private PlaybackHistoryStore.Snapshot loadPlaybackSnapshot() {
        if (!isAdded()) {
            return new PlaybackHistoryStore.Snapshot(new ArrayList<>(), 0, 0, 1, false, 0L);
        }
        return PlaybackHistoryStore.load(requireContext());
    }

    private int findTrackIndexFromSnapshot(
            @NonNull List<PlaylistTrack> source,
            @NonNull PlaybackHistoryStore.Snapshot snapshot
    ) {
        PlaybackHistoryStore.QueueTrack track = snapshot.currentTrack();
        if (track == null || TextUtils.isEmpty(track.videoId)) {
            return -1;
        }
        return findTrackIndexByVideoId(source, track.videoId);
    }

    @NonNull
    private List<PlaylistTrack> loadCachedTracks(@NonNull String playlistId) {
        return loadCachedTracksInternal(playlistId, true);
    }

    private void invalidateTracksCache(@NonNull String playlistId) {
        if (!isAdded() || playlistId.isEmpty()) return;
        getCachePrefs().edit()
                .remove(PREF_TRACKS_DATA_PREFIX + playlistId)
                .remove(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId)
                .remove(PREF_TRACKS_FULL_CACHE_PREFIX + playlistId)
                .commit();
    }

    @NonNull
    private List<PlaylistTrack> loadCachedTracksInternal(@NonNull String playlistId, boolean allowStale) {
        List<PlaylistTrack> result = new ArrayList<>();
        if (!isAdded() || playlistId.isEmpty()) {
            return result;
        }

        long updatedAt = getCachePrefs().getLong(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId, 0L);
        if (updatedAt <= 0L && !allowStale) {
            return result;
        }
        if (!allowStale && updatedAt > 0L && (System.currentTimeMillis() - updatedAt) > TRACKS_CACHE_TTL_MS) {
            return result;
        }

        String raw = getCachePrefs().getString(PREF_TRACKS_DATA_PREFIX + playlistId, "");
        if (TextUtils.isEmpty(raw)) {
            return result;
        }

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String videoId = obj.optString("videoId", "").trim();
                String title = obj.optString("title", "").trim();
                String artist = obj.optString("artist", "").trim();
                String duration = normalizeDurationLabel(obj.optString("duration", ""));
                String imageUrl = obj.optString("imageUrl", "").trim();
                if (videoId.isEmpty() || title.isEmpty()) {
                    continue;
                }
                result.add(new PlaylistTrack(videoId, title, artist, duration, imageUrl));
            }
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "loadCachedTracks parse failed", e);
            return new ArrayList<>();
        }

        return result;
    }

    private boolean hasCompleteTracksCache(
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> cachedTracks
    ) {
        if (!isAdded() || playlistId.isEmpty() || cachedTracks.isEmpty()) {
            return false;
        }
        return getCachePrefs().getBoolean(PREF_TRACKS_FULL_CACHE_PREFIX + playlistId, false);
    }

    private boolean isFetchResultComplete(int fetchedCount, int requestedLimit) {
        return fetchedCount >= 0 && fetchedCount < Math.max(1, requestedLimit);
    }

    private void cacheTracks(
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> tracks,
            boolean cacheComplete
    ) {
        if (!isAdded() || playlistId.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (PlaylistTrack track : tracks) {
                JSONObject obj = new JSONObject();
                obj.put("videoId", track.videoId);
                obj.put("title", track.title);
                obj.put("artist", track.artist);
                obj.put("duration", normalizeDurationLabel(track.duration));
                obj.put("imageUrl", track.imageUrl);
                array.put(obj);
            }

            getCachePrefs().edit()
                    .putLong(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId, System.currentTimeMillis())
                    .putBoolean(PREF_TRACKS_FULL_CACHE_PREFIX + playlistId, cacheComplete)
                    .putString(PREF_TRACKS_DATA_PREFIX + playlistId, array.toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "cacheTracks failed for " + playlistId, e);
        }
    }

    @NonNull
    private SharedPreferences getCachePrefs() {
        return requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE);
    }

    private void persistGridUrls(@NonNull String playlistId, @NonNull List<String> urls) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(urls.get(i));
            }
            getCachePrefs().edit()
                    .putString(PREF_PLAYLIST_GRID_URLS_PREFIX + playlistId, sb.toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "persistGridUrls failed", e);
        }
    }

    @Nullable
    private List<String> loadPersistedGridUrls(@NonNull String playlistId) {
        try {
            String raw = getCachePrefs().getString(PREF_PLAYLIST_GRID_URLS_PREFIX + playlistId, "");
            if (TextUtils.isEmpty(raw)) return null;
            String[] parts = raw.split("\\n");
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result.size() >= 4 ? result : null;
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "loadCachedGridUrls parse failed", e);
            return null;
        }
    }

    @Nullable
    private SongPlayerFragment findSongPlayerFragment() {
        long now = System.currentTimeMillis();
        if (lastCachedSongPlayerTime > 0 && now - lastCachedSongPlayerTime < 500L) {
            return cachedSongPlayer;
        }
        Fragment fragment = getParentFragmentManager().findFragmentByTag("song_player");
        cachedSongPlayer = fragment instanceof SongPlayerFragment ? (SongPlayerFragment) fragment : null;
        lastCachedSongPlayerTime = now;
        return cachedSongPlayer;
    }

    public void syncMiniStateFromPlayer(int trackIndex, boolean playing) {
        int safeIndex = -1;
        SongPlayerFragment songPlayer = findSongPlayerFragment();
        if (!currentTracks.isEmpty() && songPlayer != null && songPlayer.isAdded()) {
            String playerVideoId = songPlayer.externalGetCurrentVideoId();
            safeIndex = findTrackIndexByVideoId(currentTracks, playerVideoId);
        }
        if (safeIndex < 0 && trackIndex >= 0 && trackIndex < currentTracks.size()) {
            safeIndex = trackIndex;
        }

        currentTrackIndex = safeIndex;
        miniPlaying = playing;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(safeIndex);
        }

        if (getView() == null) {
            return;
        }
        syncTrackStateFromPlayer();
    }

    private void onTrackSelected(int position) {
        if (position < 0 || position >= currentTracks.size()) {
            return;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            return;
        }

        String selectedVideoId = currentTracks.get(position).videoId;
        int queueIndex = findTrackIndexByVideoId(playbackQueueTracks, selectedVideoId);
        if (queueIndex < 0) {
            queueIndex = 0;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaylistTrack item : playbackQueueTracks) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
                existingPlayer.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);
                
                // If the selected track is already playing, just open the player
                if (TextUtils.equals(selectedVideoId, existingPlayer.getLoadedVideoId())) {
                    showSongPlayerWithEnterAnimation(existingPlayer);
                    return;
                }

                if (existingPlayer.externalMatchesQueue(ids)) {
                    existingPlayer.externalPlayTrackFromStart(queueIndex);
                } else {
                    existingPlayer.externalReplaceQueueFromStart(ids, titles, artists, durations, images, queueIndex, true);
                }

                currentTrackIndex = position;
                miniPlaying = true;
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(position);
                }
                syncTrackStateFromPlayer();
            }
            return;
        }

        startHiddenIntegratedPlayerAt(position, true);
    }

    private void onTrackMorePressed(int position, @NonNull View anchor) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showTrackActionPopup(anchor, position);
    }

    private void showTrackActionPopup(@NonNull View anchor, int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) return;

        Context context = requireContext();
        PlaylistTrack selectedTrack = currentTracks.get(position);
        boolean isLocalFilesPlaylist = isLocalFilesContext(currentPlaylistId);
        // Use adapter's in-memory cache to avoid main-thread disk I/O
        boolean hasOfflineAudio = !TextUtils.isEmpty(selectedTrack.videoId)
            && !isLocalFilesPlaylist
            && trackAdapter != null
            && trackAdapter.isOfflineAvailable(context, selectedTrack.videoId, selectedTrack.duration, position);

        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_track_options, null);
        dialog.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvBsTrackTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvBsTrackSubtitle);
        ImageView ivArt = view.findViewById(R.id.ivBsTrackArt);
        
        tvTitle.setText(TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title);
        tvSubtitle.setText(TextUtils.isEmpty(selectedTrack.artist) ? "Artista" : selectedTrack.artist);
        if (isLocalFilesPlaylist && LocalFilesStore.isLocalVideoId(selectedTrack.videoId)) {
            ivArt.setScaleType(ImageView.ScaleType.CENTER);
            ivArt.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_high));
            ivArt.setImageResource(R.drawable.ic_music);
        } else {
            loadArtworkInto(ivArt, selectedTrack.imageUrl);
        }
        ImageView ivBsOffline = view.findViewById(R.id.ivBsOfflineState);
        if (ivBsOffline != null) {
            ivBsOffline.setVisibility(hasOfflineAudio ? View.VISIBLE : View.GONE);
        }

        View btnPlayNext = view.findViewById(R.id.btnBsPlayNext);
        View btnAddPrimary = view.findViewById(R.id.btnBsAddPrimary);
        View btnShare = view.findViewById(R.id.btnBsShare);

        ImageView ivPlayNext = view.findViewById(R.id.ivBsPlayNextIcon);
        TextView tvPlayNext = view.findViewById(R.id.tvBsPlayNextLabel);
        ImageView ivAddPrimary = view.findViewById(R.id.ivBsAddPrimary);
        TextView tvAddPrimary = view.findViewById(R.id.tvBsAddPrimary);
        ImageView ivShare = view.findViewById(R.id.ivBsShareIcon);
        TextView tvShare = view.findViewById(R.id.tvBsShareLabel);

        // Slot 1 (top): Reproducir
        btnPlayNext.setVisibility(View.VISIBLE);
        ivPlayNext.setImageResource(R.drawable.ic_player_play);
        tvPlayNext.setText("Reproducir");
        btnPlayNext.setOnClickListener(v -> {
            dialog.dismiss();
            onTrackSelected(position);
        });

        // Slot 2 (top): Descargar / Eliminar descarga (hidden for local files)
        btnAddPrimary.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        if (hasOfflineAudio) {
            ivAddPrimary.setImageResource(R.drawable.ic_check_small);
            tvAddPrimary.setText("Descargado");
        } else {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold);
            tvAddPrimary.setText("Descargar");
        }
        btnAddPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (hasOfflineAudio) {
                removeTrackDownloadFromRow(position);
            } else {
                downloadTrackFromRow(position);
            }
        });

        // Slot 3 (top): Compartir (hidden for local files)
        btnShare.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        ivShare.setImageResource(R.drawable.ic_playlist_share);
        tvShare.setText("Compartir");
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareTrack(selectedTrack);
        });

        View btnPlayPlaylist = view.findViewById(R.id.btnBsPlayPlaylist);
        View btnPlay = view.findViewById(R.id.btnBsPlay);
        View btnFavorite = view.findViewById(R.id.btnBsFavorite);
        View btnDownload = view.findViewById(R.id.btnBsDownload);
        View btnAddToQueue = view.findViewById(R.id.btnBsAddToQueue);

        // Row: Reproducir a continuación
        btnPlayPlaylist.setVisibility(View.VISIBLE);
        ImageView ivPlayNextRow = btnPlayPlaylist.findViewById(R.id.ivBsPlayPlaylist);
        TextView tvPlayNextRow = btnPlayPlaylist.findViewById(R.id.tvBsPlayPlaylist);
        ivPlayNextRow.setImageResource(R.drawable.ic_bs_play_next_yt);
        tvPlayNextRow.setText("Reproducir a continuación");
        btnPlayPlaylist.setOnClickListener(v -> {
            dialog.dismiss();
            queueTrackAsNext(position);
        });

        btnDownload.setVisibility(View.GONE);

        // Row: Añadir a playlist
        btnFavorite.setVisibility(View.VISIBLE);
        ImageView ivFav = btnFavorite.findViewById(R.id.ivBsFavorite);
        TextView tvFav = btnFavorite.findViewById(R.id.tvBsFavorite);
        ivFav.setImageResource(R.drawable.ic_stream_queue_add);
        tvFav.setText("Añadir a playlist");
        btnFavorite.setOnClickListener(v -> {
            dialog.dismiss();
            String gKey = CustomPlaylistsStore.getLastSavedPlaylistKey(requireContext());
            String gName = CustomPlaylistsStore.getLastSavedPlaylistName(requireContext());
            if (gKey != null && gName != null) {
                addTrackToPlaylistByKey(gKey, selectedTrack);
                showSavedInPlaylistBar(selectedTrack, gKey, gName);
            } else {
                showSaveToPlaylistSheet(selectedTrack, null);
            }
        });

        // Row: Iniciar radio (hidden for local files)
        btnPlay.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        ImageView ivRadio = btnPlay.findViewById(R.id.ivBsPlay);
        TextView tvRadio = btnPlay.findViewById(R.id.tvBsPlayLabel);
        ivRadio.setImageResource(R.drawable.ic_bs_radio);
        tvRadio.setText("Iniciar radio");
        btnPlay.setOnClickListener(v -> {
            dialog.dismiss();
            startRadioForTrack(selectedTrack);
        });

        // Row: Agregar a la fila
        btnAddToQueue.setVisibility(View.VISIBLE);
        ImageView ivAddToQueue = btnAddToQueue.findViewById(R.id.ivBsAddToQueue);
        TextView tvAddToQueue = btnAddToQueue.findViewById(R.id.tvBsAddToQueue);
        ivAddToQueue.setImageResource(R.drawable.ic_bs_add_queue_yt);
        tvAddToQueue.setText("Agregar a la fila");
        btnAddToQueue.setOnClickListener(v -> {
            dialog.dismiss();
            queueTrackAtEnd(position);
        });

        // Row: Reemplazar (hidden for local files)
        View btnReplace = view.findViewById(R.id.btnBsReplace);
        btnReplace.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        ImageView ivReplace = view.findViewById(R.id.ivBsReplace);
        TextView tvReplace = view.findViewById(R.id.tvBsReplace);
        ivReplace.setImageResource(R.drawable.ic_player_repeat);
        tvReplace.setText("Reemplazar");
        btnReplace.setOnClickListener(v -> {
            dialog.dismiss();
            String playlistType = resolvePlaylistType(currentPlaylistId);
            boolean offlineSub = isCurrentPlaylistOfflineAutoEnabled();
            String resolvedId = resolveOriginalVideoId(selectedTrack.videoId);
            boolean hasOverride = PlaylistOverrideStore.INSTANCE.getOverrides(requireContext(), currentPlaylistId)
                    .containsKey(resolvedId);
            TrackReplacementSheet.show(
                    getChildFragmentManager(),
                    currentPlaylistId,
                    playlistType,
                    resolvedId,
                    selectedTrack.title,
                    selectedTrack.artist,
                    selectedTrack.duration,
                    selectedTrack.imageUrl,
                    offlineSub,
                    hasOverride
            );
        });

        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setFitToContents(true);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                View sheetParent = (View) view.getParent();
                if (sheetParent != null) sheetParent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                bottomSheet.setBackgroundResource(android.R.color.transparent);
            }
        });
        dialog.show();
    }

    /**
     * Shows a BottomSheet listing Favoritos + custom playlists.
     * When a playlist is selected, the track is saved there (moved if previously saved elsewhere).
     * A snackbar-like bar appears with "Se guardó en X" and a "Cambiar" button.
     *
     * @param track               the track to save
     * @param previousPlaylistKey if non-null, the track will be removed from this playlist first (move semantics)
     */
    private void showSaveToPlaylistSheet(@NonNull PlaylistTrack track, @Nullable String previousPlaylistKey) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        com.google.android.material.bottomsheet.BottomSheetDialog saveDialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);
        View sheet = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_save_to_playlist, null);
        saveDialog.setContentView(sheet);

        ImageView ivClose = sheet.findViewById(R.id.ivSaveClose);
        ivClose.setOnClickListener(v -> saveDialog.dismiss());

        final String[] lastAddedKey = {null};
        final String[] lastAddedName = {null};
        final boolean[] didRemove = {false};

        sheet.findViewById(R.id.btnSaveCancel).setOnClickListener(v -> saveDialog.dismiss());
        sheet.findViewById(R.id.btnSaveConfirm).setOnClickListener(v -> {
            saveDialog.dismiss();
            if (lastAddedKey[0] != null && lastAddedName[0] != null) {
                CustomPlaylistsStore.setLastSavedPlaylist(requireContext(), lastAddedKey[0], lastAddedName[0]);
                showSavedInPlaylistBar(track, lastAddedKey[0], lastAddedName[0]);
            } else if (didRemove[0]) {
                String playlistName = resolveCurrentPlaylistName();
                showRemovedFromPlaylistBar(track, playlistName);
            }
        });

        LinearLayout llList = sheet.findViewById(R.id.llSavePlaylistList);
        llList.removeAllViews();

        float density = ctx.getResources().getDisplayMetrics().density;
        int thumbSizePx = (int) (48 * density);

        // Build playlist entries: Favoritos first, then custom playlists
        List<FavoritesPlaylistStore.FavoriteTrack> favs = FavoritesPlaylistStore.loadFavorites(ctx);
        List<String> favUrls = new ArrayList<>();
        for (FavoritesPlaylistStore.FavoriteTrack f : favs) {
            if (f != null && !TextUtils.isEmpty(f.imageUrl)) {
                if (!favUrls.contains(f.imageUrl)) favUrls.add(f.imageUrl);
                if (favUrls.size() >= 4) break;
            }
        }

        // Inflate favorites row
        {
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(FavoritesPlaylistStore.PLAYLIST_TITLE);
            tvCount.setText(favs.size() + " pistas");
            if (favUrls.size() >= 4) {
                PlaylistGridArtLoader.load(ivThumb, favUrls, thumbSizePx);
            } else if (!favUrls.isEmpty()) {
                loadArtworkInto(ivThumb, favUrls.get(0));
            }
            boolean isIn = isTrackInPlaylist(ctx, track.videoId, FavoritesPlaylistStore.PLAYLIST_ID);
            if (ivCheck != null) ivCheck.setVisibility(isIn ? View.VISIBLE : View.GONE);
            final boolean[] checked = {isIn};
            row.setOnClickListener(v -> {
                if (checked[0]) {
                    removeTrackFromPlaylistByKey(FavoritesPlaylistStore.PLAYLIST_ID, track.videoId);
                    checked[0] = false;
                    didRemove[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.GONE);
                    if (FavoritesPlaylistStore.PLAYLIST_ID.equals(lastAddedKey[0])) {
                        lastAddedKey[0] = null;
                        lastAddedName[0] = null;
                    }
                } else {
                    addTrackToPlaylistByKey(FavoritesPlaylistStore.PLAYLIST_ID, track);
                    checked[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.VISIBLE);
                    lastAddedKey[0] = FavoritesPlaylistStore.PLAYLIST_ID;
                    lastAddedName[0] = FavoritesPlaylistStore.PLAYLIST_TITLE;
                }
                int count = getPlaylistTrackCount(ctx, FavoritesPlaylistStore.PLAYLIST_ID);
                tvCount.setText(count + " pistas");
            });
            llList.addView(row);
        }

        // Custom playlists
        List<String> customNames = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(ctx);
        for (String name : customNames) {
            String playlistKey = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name;
            List<FavoritesPlaylistStore.FavoriteTrack> customTracks =
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            List<String> urls = new ArrayList<>();
            for (FavoritesPlaylistStore.FavoriteTrack t : customTracks) {
                if (!TextUtils.isEmpty(t.imageUrl)) {
                    if (!urls.contains(t.imageUrl)) urls.add(t.imageUrl);
                    if (urls.size() >= 4) break;
                }
            }

            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(name);
            tvCount.setText(customTracks.size() + " pistas");
            if (urls.size() >= 4) {
                PlaylistGridArtLoader.load(ivThumb, urls, thumbSizePx);
            } else if (!urls.isEmpty()) {
                loadArtworkInto(ivThumb, urls.get(0));
            }
            boolean isIn = isTrackInPlaylist(ctx, track.videoId, playlistKey);
            if (ivCheck != null) ivCheck.setVisibility(isIn ? View.VISIBLE : View.GONE);
            final boolean[] checked = {isIn};
            final String pName = name;
            row.setOnClickListener(v -> {
                if (checked[0]) {
                    removeTrackFromPlaylistByKey(playlistKey, track.videoId);
                    checked[0] = false;
                    didRemove[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.GONE);
                    if (playlistKey.equals(lastAddedKey[0])) {
                        lastAddedKey[0] = null;
                        lastAddedName[0] = null;
                    }
                } else {
                    addTrackToPlaylistByKey(playlistKey, track);
                    checked[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.VISIBLE);
                    lastAddedKey[0] = playlistKey;
                    lastAddedName[0] = pName;
                }
                int count = getPlaylistTrackCount(ctx, playlistKey);
                tvCount.setText(count + " pistas");
            });
            llList.addView(row);
        }

        saveDialog.getBehavior().setSkipCollapsed(true);
        saveDialog.getBehavior().setFitToContents(true);
        saveDialog.setOnShowListener(d -> {
            View bottomSheetSave = ((com.google.android.material.bottomsheet.BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheetSave != null) {
                View sheetParent = (View) sheet.getParent();
                if (sheetParent != null) sheetParent.setBackgroundColor(Color.TRANSPARENT);
                bottomSheetSave.setBackgroundResource(android.R.color.transparent);
            }
        });
        saveDialog.show();
    }

    private boolean isTrackInPlaylist(@NonNull Context ctx, @NonNull String videoId, @NonNull String playlistKey) {
        if (TextUtils.isEmpty(videoId)) return false;
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistKey)) {
            return FavoritesPlaylistStore.isFavorite(ctx, videoId);
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = playlistKey.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            List<FavoritesPlaylistStore.FavoriteTrack> tracks =
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            for (FavoritesPlaylistStore.FavoriteTrack t : tracks) {
                if (videoId.equals(t.videoId)) return true;
            }
        }
        return false;
    }

    private int getPlaylistTrackCount(@NonNull Context ctx, @NonNull String playlistKey) {
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistKey)) {
            return FavoritesPlaylistStore.loadFavorites(ctx).size();
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = playlistKey.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            return CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name).size();
        }
        return 0;
    }

    private void addTrackToPlaylistByKey(@NonNull String playlistKey, @NonNull PlaylistTrack track) {
        if (!isAdded()) return;
        String title = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
        String artist = track.artist == null ? "" : track.artist;
        String duration = track.duration == null ? "" : track.duration;
        String imageUrl = track.imageUrl == null ? "" : track.imageUrl;

        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistKey)) {
            FavoritesPlaylistStore.upsertFavorite(requireContext(), track.videoId, title, artist, duration, imageUrl);
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = playlistKey.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            CustomPlaylistsStore.INSTANCE.addTrackToPlaylist(requireContext(), name,
                    track.videoId, title, artist, duration, imageUrl);
        }
        maybeEnqueueOfflineDownloadForAddedTrack(playlistKey, track.videoId, title, artist, duration);
    }

    private void maybeEnqueueOfflineDownloadForAddedTrack(@NonNull String playlistKey, @NonNull String videoId,
                                                           @NonNull String title, @NonNull String artist, @NonNull String duration) {
        if (!isAdded() || TextUtils.isEmpty(videoId)) return;
        String playlistId = playlistKey;
        android.content.SharedPreferences cachePrefs = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE);
        boolean offlineAuto = cachePrefs.getBoolean(PREF_PLAYLIST_OFFLINE_AUTO_PREFIX + playlistId, false);
        if (!offlineAuto) return;
        if (OfflineAudioStore.hasOfflineAudio(requireContext(), videoId)) return;
        try {
            Data inputData = new Data.Builder()
                    .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, playlistId)
                    .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, playlistId)
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, new String[]{videoId})
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, new String[]{title})
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, new String[]{artist})
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, new String[]{duration})
                    .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                    .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
                    .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
                    .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
                    .build();
            SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
            boolean allowMobile = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(allowMobile ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    .addTag("offline_add_track_" + videoId)
                    .build();
            WorkManager.getInstance(requireContext()).enqueue(request);
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "maybeEnqueueOfflineDownloadForAddedTrack failed", e);
        }
    }

    private void removeTrackFromPlaylistByKey(@NonNull String playlistKey, @NonNull String videoId) {
        if (!isAdded() || TextUtils.isEmpty(videoId)) return;
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistKey)) {
            FavoritesPlaylistStore.removeFavorite(requireContext(), videoId);
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = playlistKey.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            CustomPlaylistsStore.INSTANCE.removeTrackFromPlaylist(requireContext(), name, videoId);
        }
    }

    private int computeSnackbarBottomMargin(@NonNull Activity activity, float density) {
        int margin = (int) (8 * density);
        View bottomNav = activity.findViewById(R.id.bottomNavigation);
        if (bottomNav != null && bottomNav.getVisibility() == View.VISIBLE) {
            margin += bottomNav.getHeight();
        }
        View miniPlayer = activity.findViewById(R.id.llGlobalMiniPlayer);
        if (miniPlayer != null && miniPlayer.getVisibility() == View.VISIBLE) {
            margin += miniPlayer.getHeight();
        }
        return margin;
    }

    private void showSavedInPlaylistBar(@NonNull PlaylistTrack track, @NonNull String playlistKey, @NonNull String playlistName) {
        if (!isAdded()) return;
        MainActivity activity = (MainActivity) requireActivity();
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        View existing = rootView.findViewWithTag("saved_bar");
        if (existing != null) rootView.removeView(existing);

        float density = getResources().getDisplayMetrics().density;

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setTag("saved_bar");
        bar.setId(View.generateViewId());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#FF1E1E1E"));
        int hPad = (int) (16 * density);
        int vPad = (int) (12 * density);
        bar.setPadding(hPad, vPad, hPad, vPad);
        bar.setElevation(8 * density);

        TextView tvSaved = new TextView(requireContext());
        tvSaved.setText("Se guardó en " + playlistName);
        tvSaved.setTextColor(Color.WHITE);
        tvSaved.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvSaved.setTypeface(null, android.graphics.Typeface.NORMAL);
        tvSaved.setMaxLines(1);
        tvSaved.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvSaved.setLayoutParams(tvParams);
        bar.addView(tvSaved);

        TextView btnChange = new TextView(requireContext());
        btnChange.setText("Cambiar");
        btnChange.setTextColor(Color.parseColor("#8AB4F8"));
        btnChange.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        btnChange.setTypeface(null, android.graphics.Typeface.BOLD);
        btnChange.setPadding((int) (16 * density), 0, 0, 0);
        btnChange.setOnClickListener(v -> {
            rootView.removeView(bar);
            CustomPlaylistsStore.clearLastSavedPlaylist(requireContext());
            showSaveToPlaylistSheet(track, playlistKey);
        });
        bar.addView(btnChange);

        int barBottomMargin = computeSnackbarBottomMargin(activity, density);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = android.view.Gravity.BOTTOM;
        flp.bottomMargin = barBottomMargin;
        rootView.addView(bar, flp);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (bar.getParent() != null) {
                ((ViewGroup) bar.getParent()).removeView(bar);
            }
        }, 4000L);
    }

    private void showRemovedFromPlaylistBar(@NonNull PlaylistTrack track, @NonNull String playlistName) {
        if (!isAdded()) return;
        MainActivity activity = (MainActivity) requireActivity();
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        View existing = rootView.findViewWithTag("saved_bar");
        if (existing != null) rootView.removeView(existing);

        float density = getResources().getDisplayMetrics().density;

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setTag("saved_bar");
        bar.setId(View.generateViewId());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#FF1E1E1E"));
        int hPad = (int) (16 * density);
        int vPad = (int) (12 * density);
        bar.setPadding(hPad, vPad, hPad, vPad);
        bar.setElevation(8 * density);

        String title = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
        TextView tvMsg = new TextView(requireContext());
        tvMsg.setText(title + " eliminado de " + playlistName);
        tvMsg.setTextColor(Color.WHITE);
        tvMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvMsg.setTypeface(null, android.graphics.Typeface.NORMAL);
        tvMsg.setMaxLines(1);
        tvMsg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvMsg.setLayoutParams(tvParams);
        bar.addView(tvMsg);

        TextView btnUndo = new TextView(requireContext());
        btnUndo.setText("Deshacer");
        btnUndo.setTextColor(Color.parseColor("#8AB4F8"));
        btnUndo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        btnUndo.setTypeface(null, android.graphics.Typeface.BOLD);
        btnUndo.setPadding((int) (16 * density), 0, 0, 0);
        btnUndo.setOnClickListener(v -> {
            rootView.removeView(bar);
            undoRemoveTrackFromPlaylist(track);
            Toast.makeText(requireContext(), "Restaurado en " + playlistName, Toast.LENGTH_SHORT).show();
        });
        bar.addView(btnUndo);

        int barBottomMargin = computeSnackbarBottomMargin(activity, density);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = android.view.Gravity.BOTTOM;
        flp.bottomMargin = barBottomMargin;
        rootView.addView(bar, flp);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (bar.getParent() != null) {
                ((ViewGroup) bar.getParent()).removeView(bar);
            }
        }, 4000L);
    }

    private void shareTrack(PlaylistTrack track) {
        if (track == null) return;
        String shareText = "https://music.youtube.com/watch?v=" + track.videoId;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(intent, "Compartir canción"));
    }

    private void queueTrackAsNext(int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            
            return;
        }

        PlaylistTrack selected = currentTracks.get(position);
        if (TextUtils.isEmpty(selected.videoId)) {
            
            return;
        }

        int currentQueueIndex = resolveCurrentQueueIndex();
        PlaylistTrack movingTrack = selected;

        int insertIndex = Math.max(0, Math.min(currentQueueIndex + 1, playbackQueueTracks.size()));
        playbackQueueTracks.add(insertIndex, movingTrack);

        SongPlayerFragment player = findSongPlayerFragment();
        if (player != null && player.isAdded()) {
            player.externalInsertNext(movingTrack.videoId, movingTrack.title, movingTrack.artist, movingTrack.duration, movingTrack.imageUrl);
        }
        
    }

    private void startRadioForTrack(@NonNull PlaylistTrack track) {
        if (!isAdded() || TextUtils.isEmpty(track.videoId)) return;
        if (getParentFragmentManager().isStateSaved()) return;
        String radioPlaylistId = "RDAMVM" + track.videoId;
        String radioTitle = "Radio: " + (TextUtils.isEmpty(track.title) ? "Tema" : track.title);
        String accessToken = resolveYoutubeAccessToken("");
        PlaylistDetailFragment detailFragment = PlaylistDetailFragment.newInstance(
                radioPlaylistId,
                radioTitle,
                track.artist == null ? "" : track.artist,
                track.imageUrl == null ? "" : track.imageUrl,
                accessToken
        );
        androidx.fragment.app.Fragment existingDetail = getParentFragmentManager().findFragmentByTag("playlist_detail");
        androidx.fragment.app.FragmentTransaction transaction = getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true);
        if (existingDetail != null && existingDetail.isAdded() && existingDetail != this) {
            transaction.remove(existingDetail);
        }
        transaction
                .add(R.id.fragmentContainer, detailFragment, "playlist_detail")
                .addToBackStack("playlist_detail")
                .commit();

        // Fetch radio tracks and save to RadioHistoryStore for library display
        String cookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .getString("stream_last_youtube_web_cookie", "");
        if (cookie == null) cookie = "";
        final String selectedVideoId = track.videoId;
        final String selectedTitle = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
        final String selectedArtist = track.artist == null ? "" : track.artist;
        final String selectedThumb = track.imageUrl == null ? "" : track.imageUrl;
        final String finalRadioPlaylistId = radioPlaylistId;
        youTubeMusicService.fetchMixTracks(cookie.trim(), radioPlaylistId, new YouTubeMusicService.MixTracksCallback() {
            @Override
            public void onSuccess(@NonNull java.util.List<YouTubeMusicService.TrackResult> radioTracks) {
                if (radioTracks.isEmpty()) return;
                java.util.List<RadioHistoryStore.RadioTrack> radioStoreTracks = new java.util.ArrayList<>();
                radioStoreTracks.add(new RadioHistoryStore.RadioTrack(
                        selectedVideoId, selectedTitle, selectedArtist, selectedThumb));
                for (YouTubeMusicService.TrackResult t : radioTracks) {
                    if (TextUtils.isEmpty(t.videoId) || TextUtils.equals(t.videoId, selectedVideoId)) continue;
                    radioStoreTracks.add(new RadioHistoryStore.RadioTrack(
                            t.videoId,
                            TextUtils.isEmpty(t.title) ? "" : t.title,
                            t.subtitle == null ? "" : t.subtitle,
                            t.thumbnailUrl == null ? "" : t.thumbnailUrl));
                }
                Context ctx = getContext();
                if (ctx == null) ctx = requireActivity().getApplicationContext();
                RadioHistoryStore.INSTANCE.saveRadio(ctx, finalRadioPlaylistId, selectedTitle, selectedThumb, radioStoreTracks);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).refreshMusicLibrary();
                }
            }

            @Override
            public void onError(@NonNull String error) {
                // Radio fetch failed — no action needed
            }
        });
    }

    private void queueTrackAtEnd(int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            
            return;
        }

        PlaylistTrack selected = currentTracks.get(position);
        if (TextUtils.isEmpty(selected.videoId)) {
            
            return;
        }

        PlaylistTrack movingTrack = selected;
        playbackQueueTracks.add(movingTrack);

        SongPlayerFragment player = findSongPlayerFragment();
        if (player != null && player.isAdded()) {
            player.externalEnqueue(movingTrack.videoId, movingTrack.title, movingTrack.artist, movingTrack.duration, movingTrack.imageUrl);
        }
        
    }

    private void addTrackToFavoritesFromRow(int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        PlaylistTrack selected = currentTracks.get(position);
        if (TextUtils.isEmpty(selected.videoId)) {
            return;
        }

        FavoritesPlaylistStore.upsertFavorite(
                requireContext(),
                selected.videoId,
                selected.title,
                selected.artist,
                selected.duration,
                selected.imageUrl
        );
    }

    private String resolveCurrentPlaylistName() {
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(currentPlaylistId)) {
            return FavoritesPlaylistStore.PLAYLIST_TITLE;
        } else if (currentPlaylistId != null && currentPlaylistId.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            return currentPlaylistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
        }
        return "playlist";
    }

    private void undoRemoveTrackFromPlaylist(PlaylistTrack track) {
        if (!isAdded()) return;
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(currentPlaylistId)) {
            FavoritesPlaylistStore.upsertFavorite(requireContext(), track.videoId, 
                track.title, track.artist, track.duration, track.imageUrl);
        } else if (currentPlaylistId != null && currentPlaylistId.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = currentPlaylistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            CustomPlaylistsStore.INSTANCE.addTrackToPlaylist(requireContext(), name, 
                track.videoId, track.title, track.artist, track.duration, track.imageUrl);
        }
        // Refresh the track list
        currentTracks.add(track);
        trackAdapter.submitTracks(currentTracks);
        rebuildPlaybackQueue();
    }

    private int resolveCurrentQueueIndex() {
        ensurePlaybackQueue();
        SongPlayerFragment player = findSongPlayerFragment();
        if (player != null && player.isAdded()) {
            return player.externalGetCurrentIndex();
        }

        int localIndex = findTrackIndexByVideoId(playbackQueueTracks, getCurrentTrackVideoId());
        if (localIndex >= 0) {
            return localIndex;
        }

        return -1;
    }

    private void syncPlayerQueueWithPlaybackOrder() {
        SongPlayerFragment player = findSongPlayerFragment();
        if (player == null || !player.isAdded()) {
            return;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            return;
        }

        String selectedVideoId = player.externalGetCurrentVideoId();
        if (TextUtils.isEmpty(selectedVideoId)) {
            selectedVideoId = getCurrentTrackVideoId();
        }

        int selectedIndex = findTrackIndexByVideoId(playbackQueueTracks, selectedVideoId);
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaylistTrack item : playbackQueueTracks) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }

        boolean keepPlaying = player.externalIsPlaying();
        player.externalReplaceQueue(ids, titles, artists, durations, images, selectedIndex, keepPlaying);
        miniPlaying = keepPlaying;
        syncTrackStateFromPlayer();
    }

    private void downloadTrackFromRow(int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        PlaylistTrack track = currentTracks.get(position);
        if (TextUtils.isEmpty(track.videoId)) {
            
            return;
        }

        // Local device files don't need downloading
        if (LocalFilesStore.isLocalVideoId(track.videoId)) return;

        // Use adapter's in-memory cache to avoid main-thread disk I/O
        if (trackAdapter != null && trackAdapter.isOfflineAvailable(requireContext(), track.videoId, track.duration, position)) {
            
            return;
        }

        String uniqueName = OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME;
        Data input = new Data.Builder()
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, currentPlaylistId)
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, currentPlaylistTitle)
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, new String[] { track.videoId })
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, new String[] { track.title })
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, new String[] { track.artist })
            .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, new String[] { track.duration })
                .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
            .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
            .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
                .build();

        SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(uniqueName)
                .addTag(currentPlaylistOfflineTag())
                .build();

        enqueueOfflineDownloadUniqueWork(uniqueName, request);

        setOfflineDownloadVisualState(
            true,
            track.videoId,
            new String[] { track.videoId },
            null
        );
        offlineDownloadQueued = true;
        notifyHeaderChanged();
        observeOfflineDownload(uniqueName);

        // Notify MusicPlayerFragment to update its offline state for this playlist
        notifyMusicPlayerOfflineChanged();
    }

    private void removeTrackDownloadFromRow(int position) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return;
        }

        PlaylistTrack track = currentTracks.get(position);
        if (TextUtils.isEmpty(track.videoId)) {
            return;
        }

        final Context appContext = requireContext().getApplicationContext();
        final String videoId = track.videoId;
        final ArrayList<String> idsToDelete = new ArrayList<>(1);
        idsToDelete.add(videoId);

        // Optimistically update UI before background deletion completes
        if (trackAdapter != null) {
            trackAdapter.invalidateTrackStateCache(videoId);
            trackAdapter.notifyItemChanged(position);
        }

        trackStateLookupExecutor.execute(() -> {
            OfflineAudioStore.deleteOfflineAudio(appContext, idsToDelete);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (trackAdapter != null) {
                    trackAdapter.invalidateTrackStateCache(videoId);
                    trackAdapter.notifyItemChanged(position);
                }
                maybeUpdateOfflineReadyState();
                maybeAutoDownloadForCurrentPlaylist();
                notifyMusicPlayerOfflineChanged();
            });
        });
    }

    /**
     * Notifies MusicPlayerFragment that the offline state of the current playlist has changed.
     * This ensures the library view shows the correct offline indicator.
     */
    private void notifyMusicPlayerOfflineChanged() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return;
        }
        Fragment music = getParentFragmentManager().findFragmentByTag(TAG_MODULE_MUSIC);
        if (music instanceof MusicPlayerFragment) {
            ((MusicPlayerFragment) music).notifyPlaylistOfflineChanged(currentPlaylistId);
        }
    }

    private String resolveOriginalVideoId(@NonNull String videoId) {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId) || TextUtils.isEmpty(videoId))
            return videoId;
        java.util.Map<String, PlaylistOverrideStore.Override> overrides =
                PlaylistOverrideStore.INSTANCE.getOverrides(requireContext(), currentPlaylistId);
        for (java.util.Map.Entry<String, PlaylistOverrideStore.Override> entry : overrides.entrySet()) {
            if (videoId.equals(entry.getValue().getReplacementVideoId())) {
                return entry.getKey();
            }
        }
        return videoId;
    }

    @Override
    public void onReplacementUndone(@NonNull String playlistId, @NonNull String originalVideoId) {
        if (!isAdded()) return;
        Context ctx = requireContext();
        final Context appContext = ctx.getApplicationContext();

        PlaylistOverrideStore.Override existing =
                PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId).get(originalVideoId);
        String replacementVideoId = existing != null ? existing.getReplacementVideoId() : "";
        if (!TextUtils.isEmpty(replacementVideoId)) {
            final String deleteId = replacementVideoId;
            trackStateLookupExecutor.execute(() -> OfflineAudioStore.deleteOfflineAudio(appContext, deleteId));
        }

        PlaylistOverrideStore.INSTANCE.removeOverride(ctx, playlistId, originalVideoId);

        List<PlaylistTrack> updatedTracks = sanitizeTracksForPlaylist(playlistId, loadCachedTracks(playlistId));
        renderTracks(updatedTracks, playlistId, false);

        if (trackAdapter != null) {
            trackAdapter.invalidateTrackStateCache(replacementVideoId);
            trackAdapter.invalidateTrackStateCache(originalVideoId);
            trackAdapter.submitTracks(updatedTracks);
        }

        android.widget.Toast.makeText(ctx, "Reemplazo deshecho", android.widget.Toast.LENGTH_SHORT).show();

        if (AuthManager.getInstance(ctx).isSignedIn()) {
            CloudSyncManager.getInstance(ctx).syncPlaylistOverridesToCloud(
                    playlistId,
                    new ArrayList<>(PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId).values())
            );
        }

        notifyMusicPlayerOfflineChanged();
    }

    @Override
    public void onReplacementConfirmed(
            @NonNull String playlistId,
            @NonNull String playlistType,
            @NonNull String originalVideoId,
            @NonNull YouTubeMusicService.ReplacementCandidate candidate
    ) {
        if (!isAdded()) return;

        Context ctx = requireContext();

        final Context appContext = ctx.getApplicationContext();
        PlaylistOverrideStore.Override previousOverride =
                PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId).get(originalVideoId);
        String previousVideoId = previousOverride != null
                ? previousOverride.getReplacementVideoId()
                : originalVideoId;
        if (!TextUtils.isEmpty(previousVideoId)) {
            final String deleteId = previousVideoId;
            trackStateLookupExecutor.execute(() -> OfflineAudioStore.deleteOfflineAudio(appContext, deleteId));
        }

        PlaylistOverrideStore.Override override = new PlaylistOverrideStore.Override(
                originalVideoId,
                candidate.videoId,
                candidate.title,
                candidate.artist,
                candidate.duration,
                candidate.thumbnailUrl,
                System.currentTimeMillis()
        );
        PlaylistOverrideStore.INSTANCE.putOverride(ctx, playlistId, override);

        List<PlaylistTrack> updatedTracks = sanitizeTracksForPlaylist(playlistId, loadCachedTracks(playlistId));
        renderTracks(updatedTracks, playlistId, false);

        if (trackAdapter != null) {
            trackAdapter.invalidateTrackStateCache(previousVideoId);
            trackAdapter.invalidateTrackStateCache(candidate.videoId);
            trackAdapter.submitTracks(updatedTracks);
        }

        if (isCurrentPlaylistOfflineAutoEnabled()) {
            enqueueSingleTrackForOfflineDownload(candidate.videoId, candidate.title, candidate.artist, candidate.duration);
        }

        if (AuthManager.getInstance(ctx).isSignedIn()) {
            CloudSyncManager.getInstance(ctx).syncPlaylistOverridesToCloud(
                    playlistId,
                    new ArrayList<>(PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId).values())
            );
        }

        notifyMusicPlayerOfflineChanged();
    }

    private void enqueueSingleTrackForOfflineDownload(
            @NonNull String videoId,
            @NonNull String title,
            @NonNull String artist,
            @NonNull String duration
    ) {
        if (!isAdded() || TextUtils.isEmpty(videoId)) return;
        try {
            Data inputData = new Data.Builder()
                    .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, currentPlaylistId)
                    .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, currentPlaylistTitle)
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, new String[] { videoId })
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, new String[] { title })
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, new String[] { artist })
                    .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, new String[] { duration })
                    .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                    .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
                    .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
                    .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
                    .build();

            SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
            boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                    .build();

            String uniqueName = OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME;
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    .addTag(uniqueName)
                    .addTag(currentPlaylistOfflineTag())
                    .build();

            enqueueOfflineDownloadUniqueWork(uniqueName, request);
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "enqueueSingleTrackForOfflineDownload failed", e);
        }
    }

    private void playAllInOrder() {
        if (currentTracks.isEmpty()) {
            
            return;
        }

        if (shuffleModeEnabled) {
            ensurePlaybackQueue();
            if (!playbackQueueTracks.isEmpty()) {
                String firstQueueVideoId = playbackQueueTracks.get(0).videoId;
                int displayIndex = findTrackIndexByVideoId(currentTracks, firstQueueVideoId);
                openIntegratedPlayerAt(displayIndex >= 0 ? displayIndex : 0, true);
                return;
            }
        }

        openIntegratedPlayerAt(0, true);
    }

    private void openIntegratedPlayerAt(int position, boolean startFromBeginning) {
        if (position < 0 || position >= currentTracks.size()) {
            return;
        }

        if (getActivity() instanceof MainActivity) {
            GlobalMiniPlayerController gmp = ((MainActivity) getActivity()).getGlobalMiniPlayer();
            if (gmp != null) gmp.animateOut();
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            return;
        }

        String selectedVideoId = currentTracks.get(position).videoId;
        int queueIndex = findTrackIndexByVideoId(playbackQueueTracks, selectedVideoId);
        if (queueIndex < 0) {
            queueIndex = 0;
        }
        final int effectiveQueueIndex = queueIndex;

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaylistTrack item : playbackQueueTracks) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
                existingPlayer.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);
                if (existingPlayer.externalMatchesQueue(ids)) {
                    if (startFromBeginning) {
                        existingPlayer.externalPlayTrackFromStart(queueIndex);
                    } else {
                        existingPlayer.externalPlayTrack(queueIndex);
                    }
                } else {
                    if (startFromBeginning) {
                        existingPlayer.externalReplaceQueueFromStart(ids, titles, artists, durations, images, queueIndex, true);
                    } else {
                        existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, queueIndex, true);
                    }
                }

                    showSongPlayerWithEnterAnimation(existingPlayer);

                currentTrackIndex = position;
                miniPlaying = true;
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(position);
                }
            }
            // Don't update mini-player UI - keep it hidden while full player is visible
            return;
        }

        currentTrackIndex = position;
        miniPlaying = true;
        trackAdapter.setActiveIndex(position);

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                queueIndex,
                true
        );
        playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
        playerFragment.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);
        addSongPlayerWithEnterAnimation(playerFragment);
        // Don't update mini-player UI - keep it hidden while full player is visible
    }

    private boolean openPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid()) {
            return false;
        }

        // Hide global mini-player immediately to prevent UI overlap
        if (getActivity() instanceof MainActivity) {
            GlobalMiniPlayerController gmp = ((MainActivity) getActivity()).getGlobalMiniPlayer();
            if (gmp != null) gmp.hide();
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaybackHistoryStore.QueueTrack item : snapshot.queue) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }
        if (ids.isEmpty()) {
            return false;
        }

        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
                existingPlayer.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);
                existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, snapshotIndex, startPlaying);
                showSongPlayerWithEnterAnimation(existingPlayer);
            }
        } else {
            SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                    ids,
                    titles,
                    artists,
                    durations,
                    images,
                    snapshotIndex,
                    startPlaying
            );
            playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
            playerFragment.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);
                addSongPlayerWithEnterAnimation(playerFragment);
        }

        int displayIndex = findTrackIndexFromSnapshot(currentTracks, snapshot);
        currentTrackIndex = displayIndex;
        miniPlaying = startPlaying;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(displayIndex);
        }
        syncTrackStateFromPlayer();
        return true;
    }

    private void maybeRestoreHiddenPlayerFromSnapshot() {
        if (!isAdded() || isHidden() || restoringHiddenPlayerFromSnapshot) {
            return;
        }
        if (currentTracks.isEmpty()) {
            return;
        }

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            return;
        }

        PlaybackHistoryStore.Snapshot snapshot = loadPlaybackSnapshot();
        if (!snapshot.isValid()) {
            return;
        }

        int displayIndex = findTrackIndexFromSnapshot(currentTracks, snapshot);
        if (displayIndex < 0) {
            return;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaybackHistoryStore.QueueTrack item : snapshot.queue) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }
        if (ids.isEmpty()) {
            return;
        }

        if (getParentFragmentManager().isStateSaved()) {
            return;
        }

        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                snapshotIndex,
            snapshot.isPlaying
        );
        playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);

        restoringHiddenPlayerFromSnapshot = true;
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .runOnCommit(() -> {
                    restoringHiddenPlayerFromSnapshot = false;
                    syncTrackStateFromPlayer();
                })
                .commit();

        currentTrackIndex = displayIndex;
        miniPlaying = snapshot.isPlaying;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(displayIndex);
        }
    }

    private boolean launchPlayerFromLastTrackPrefs(boolean startPlaying, boolean showPlayer) {
        if (!isAdded()) {
            return false;
        }

        SharedPreferences prefs = getPlayerStatePrefs();
        String persistedPlaylistId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        if (!TextUtils.isEmpty(currentPlaylistId)
                && !TextUtils.equals(currentPlaylistId, persistedPlaylistId)) {
            return false;
        }

        String videoId = prefs.getString(PREF_LAST_VIDEO_ID, "");
        if (TextUtils.isEmpty(videoId)) {
            return false;
        }

        String title = prefs.getString(PREF_LAST_TRACK_TITLE, "");
        String artist = prefs.getString(PREF_LAST_TRACK_ARTIST, "");
        String duration = prefs.getString(PREF_LAST_TRACK_DURATION, "");
        String image = prefs.getString(PREF_LAST_TRACK_IMAGE, "");

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        ids.add(videoId);
        titles.add(TextUtils.isEmpty(title) ? "Ultima reproduccion" : title);
        artists.add(artist == null ? "" : artist);
        durations.add(duration == null ? "" : duration);
        images.add(image == null ? "" : image);

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
                existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, 0, startPlaying);

                if (showPlayer) {
                    showSongPlayerWithEnterAnimation(existingPlayer);
                }

                currentTrackIndex = findTrackIndexByVideoId(currentTracks, videoId);
                miniPlaying = startPlaying;
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(currentTrackIndex);
                }
                syncTrackStateFromPlayer();
            }
            return true;
        }

        if (getParentFragmentManager().isStateSaved()) {
            return false;
        }

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                0,
                startPlaying
        );
        playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
            if (showPlayer) {
                addSongPlayerWithEnterAnimation(playerFragment);
            } else {
                getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.playerContainer, playerFragment, "song_player")
                    .hide(playerFragment)
                    .commit();
            }

        currentTrackIndex = findTrackIndexByVideoId(currentTracks, videoId);
        miniPlaying = startPlaying;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(currentTrackIndex);
        }
        syncTrackStateFromPlayer();
        return true;
    }

    private void showSongPlayerWithEnterAnimation(@NonNull SongPlayerFragment player) {
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .show(player)
                .runOnCommit(player::externalAnimateEnterSlide)
                .commit();
    }

    private void addSongPlayerWithEnterAnimation(@NonNull SongPlayerFragment player) {
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, player, "song_player")
                .runOnCommit(player::externalAnimateEnterSlide)
                .commit();
    }

    private boolean startHiddenPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid() || !isAdded()) {
            return false;
        }

        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
                if (startPlaying && !existingPlayer.externalIsPlaying()) {
                    existingPlayer.externalTogglePlayback();
                }
                syncTrackStateFromPlayer();
            }
            return true;
        }

        if (getParentFragmentManager().isStateSaved()) {
            return false;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaybackHistoryStore.QueueTrack item : snapshot.queue) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }
        if (ids.isEmpty()) {
            return false;
        }

        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                snapshotIndex,
            false
        );
        playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);

        final ArrayList<String> replayIds = ids;
        final ArrayList<String> replayTitles = titles;
        final ArrayList<String> replayArtists = artists;
        final ArrayList<String> replayDurations = durations;
        final ArrayList<String> replayImages = images;
        final int replaySnapshotIndex = snapshotIndex;
        final boolean replayStartPlaying = startPlaying;
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .runOnCommit(() -> {
                    playerFragment.externalReplaceQueue(
                            replayIds,
                            replayTitles,
                            replayArtists,
                            replayDurations,
                            replayImages,
                            replaySnapshotIndex,
                            replayStartPlaying
                    );
                    syncTrackStateFromPlayer();
                })
                .commit();

        int displayIndex = findTrackIndexFromSnapshot(currentTracks, snapshot);
        currentTrackIndex = displayIndex;
        miniPlaying = startPlaying;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(displayIndex);
        }
        syncTrackStateFromPlayer();
        return true;
    }

    private boolean startHiddenIntegratedPlayerAt(int position, boolean startFromBeginning) {
        if (!isAdded() || position < 0 || position >= currentTracks.size()) {
            return false;
        }

        if (getParentFragmentManager().isStateSaved()) {
            return false;
        }

        ensurePlaybackQueue();
        if (playbackQueueTracks.isEmpty()) {
            return false;
        }

        String selectedVideoId = currentTracks.get(position).videoId;
        int queueIndex = findTrackIndexByVideoId(playbackQueueTracks, selectedVideoId);
        if (queueIndex < 0) {
            queueIndex = 0;
        }
        final int effectiveQueueIndex = queueIndex;

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        for (PlaylistTrack item : playbackQueueTracks) {
            ids.add(item.videoId);
            titles.add(item.title);
            artists.add(item.artist);
            durations.add(item.duration);
            images.add(item.imageUrl);
        }

        if (ids.isEmpty()) {
            return false;
        }

        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                effectiveQueueIndex,
                true
        );
        playerFragment.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);

        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .commit();

        currentTrackIndex = position;
        miniPlaying = true;
        if (trackAdapter != null) {
            trackAdapter.setActiveIndex(position);
        }
        syncTrackStateFromPlayer();
        return true;
    }

    private void syncTrackStateFromPlayer() {
        if (!isAdded() || getView() == null) {
            return;
        }

        SongPlayerFragment songPlayer = findSongPlayerFragment();
        boolean playerAttached = songPlayer != null && songPlayer.isAdded();
        PlaybackHistoryStore.Snapshot snapshot = loadPlaybackSnapshot();
        PlaybackHistoryStore.QueueTrack snapshotTrack = snapshot.currentTrack();

        if (playerAttached) {
            miniPlaying = songPlayer.externalIsPlaying();

            if (!currentTracks.isEmpty()) {
                String playerVideoId = songPlayer.externalGetCurrentVideoId();
                int mappedIndex = findTrackIndexByVideoId(currentTracks, playerVideoId);
                if (mappedIndex >= 0) {
                    currentTrackIndex = mappedIndex;
                } else {
                    int snapshotMappedIndex = snapshotTrack == null
                            ? -1
                            : findTrackIndexByVideoId(currentTracks, snapshotTrack.videoId);
                    currentTrackIndex = snapshotMappedIndex >= 0 ? snapshotMappedIndex : -1;
                }
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(currentTrackIndex);
                }
            }
        } else if (snapshotTrack != null) {
            miniPlaying = snapshot.isPlaying;
            int mappedIndex = currentTracks.isEmpty()
                    ? -1
                    : findTrackIndexByVideoId(currentTracks, snapshotTrack.videoId);
            currentTrackIndex = mappedIndex >= 0 ? mappedIndex : -1;
            if (trackAdapter != null) {
                trackAdapter.setActiveIndex(currentTrackIndex);
            }
        }

        PlaylistTrack currentListTrack = null;
        if (currentTrackIndex >= 0 && currentTrackIndex < currentTracks.size()) {
            currentListTrack = currentTracks.get(currentTrackIndex);
        }
        if (currentListTrack == null && snapshotTrack == null && !playerAttached) {
            miniPlaying = false;
        }

        if (currentListTrack != null) {
            persistCurrentPlaybackState(currentListTrack, miniPlaying);
        }
    }

    @NonNull
    private SharedPreferences getPlayerStatePrefs() {
        return requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
    }

    @NonNull
    private String loadPersistedVideoIdForCurrentPlaylist() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) {
            return "";
        }
        SharedPreferences prefs = getPlayerStatePrefs();
        String playlistId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        if (!TextUtils.equals(currentPlaylistId, playlistId)) {
            return "";
        }
        String videoId = prefs.getString(PREF_LAST_VIDEO_ID, "");
        return videoId == null ? "" : videoId.trim();
    }

    private void persistCurrentPlaybackState(
            @NonNull PlaylistTrack current,
            boolean playing
    ) {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId) || TextUtils.isEmpty(current.videoId)) {
            return;
        }

        if (TextUtils.equals(lastPersistedPlaylistId, currentPlaylistId)
                && TextUtils.equals(lastPersistedVideoId, current.videoId)
                && lastPersistedPlaying == playing) {
            return;
        }

        lastPersistedPlaylistId = currentPlaylistId;
        lastPersistedVideoId = current.videoId;
        lastPersistedPlaying = playing;

        getPlayerStatePrefs().edit()
                .putString(PREF_LAST_PLAYLIST_ID, currentPlaylistId)
                .putString(PREF_LAST_PLAYLIST_TITLE, currentPlaylistTitle)
                .putString(PREF_LAST_PLAYLIST_SUBTITLE, currentPlaylistSubtitle)
                .putString(PREF_LAST_PLAYLIST_THUMBNAIL, currentPlaylistThumbnail)
                .putString(PREF_LAST_VIDEO_ID, current.videoId)
                .putString(PREF_LAST_TRACK_TITLE, current.title)
                .putString(PREF_LAST_TRACK_ARTIST, current.artist)
                .putString(PREF_LAST_TRACK_IMAGE, current.imageUrl)
                .putString(PREF_LAST_TRACK_DURATION, current.duration)
                .putBoolean(PREF_LAST_IS_PLAYING, playing)
                .apply();
    }

    @NonNull
    private PlaylistMeta parseMeta(@NonNull String subtitle) {
        String owner = "Nexus";
        int songs = 0;

        if (!subtitle.isEmpty()) {
            String[] tokens = subtitle.split("•");
            for (String token : tokens) {
                String value = token == null ? "" : token.trim();
                if (value.isEmpty()) {
                    continue;
                }

                String lower = value.toLowerCase(Locale.US);
                if (lower.contains("playlist")) {
                    continue;
                }
                if (lower.contains("song") || lower.contains("cancion")) {
                    songs = parseFirstNumber(value, songs);
                } else {
                    owner = value;
                }
            }
        }

        int totalMinutes = Math.max(0, songs * 3);
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        String duration = String.format(Locale.US, "%dh %02dm", hours, minutes);

        String visibility = "";
        String age = "";
        String lowerSubtitle = subtitle.toLowerCase(Locale.US);
        if (lowerSubtitle.contains("no listada") || lowerSubtitle.contains("unlisted")) {
            visibility = "No listada";
        } else if (lowerSubtitle.contains("listada") || lowerSubtitle.contains("public")) {
            visibility = "Listada";
        } else if (lowerSubtitle.contains("privada") || lowerSubtitle.contains("private")) {
            visibility = "Privada";
        }

        return new PlaylistMeta(owner, songs, duration, visibility, age);
    }

    @NonNull
    private String buildVisibilityLabel(@NonNull String privacyStatus) {
        String normalized = privacyStatus.trim().toLowerCase(Locale.US);
        if ("public".equals(normalized)) {
            return "Listada";
        }
        if ("unlisted".equals(normalized)) {
            return "No listada";
        }
        if ("private".equals(normalized)) {
            return "Privada";
        }
        return "";
    }

    @NonNull
    private String buildRelativeDateLabel(@NonNull String publishedAtIso) {
        long publishedAt = parseIsoDateMillis(publishedAtIso);
        if (publishedAt <= 0L) {
            return "";
        }

        long diffMs = Math.max(0L, System.currentTimeMillis() - publishedAt);
        long days = TimeUnit.MILLISECONDS.toDays(diffMs);
        if (days < 1) {
            return "hace hoy";
        }
        if (days < 7) {
            return "hace " + days + " dias";
        }
        long weeks = Math.max(1, days / 7);
        if (weeks < 5) {
            return "hace " + weeks + " sem.";
        }
        long months = Math.max(1, days / 30);
        if (months < 12) {
            return "hace " + months + " mes" + (months == 1 ? "" : "es");
        }
        long years = Math.max(1, days / 365);
        return "hace " + years + " a" + (years == 1 ? "n" : "nos");
    }

    private long parseIsoDateMillis(@NonNull String iso) {
        if (iso.trim().isEmpty()) {
            return -1L;
        }

        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(true);
                Date date = format.parse(iso);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return -1L;
    }

    @NonNull
    private String buildPlaylistInfoLine(@NonNull PlaylistMeta meta, int actualTrackCount) {
        List<String> parts = new ArrayList<>();

        int songs = actualTrackCount >= 0 ? actualTrackCount : meta.songsCount;
        if (songs > 0) {
            parts.add(songs + " canciones");
        }
        if (!TextUtils.isEmpty(meta.estimatedDuration)) {
            parts.add(meta.estimatedDuration);
        }
        if (!TextUtils.isEmpty(meta.ageLabel)) {
            parts.add(meta.ageLabel);
        }
        if (!TextUtils.isEmpty(meta.visibilityLabel)) {
            parts.add(meta.visibilityLabel);
        }

        if (parts.isEmpty()) {
            if (isFavoritesPlaylistContext(currentPlaylistId)) {
                return "0 canciones";
            }
            return "Lista";
        }
        return TextUtils.join(" • ", parts);
    }

    private int parseFirstNumber(@NonNull String text, int fallback) {
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseDurationSeconds(@NonNull String duration) {
        if (duration.isEmpty() || duration.contains("--")) {
            return 0;
        }

        String[] parts = duration.split(":");
        try {
            if (parts.length == 2) {
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                return (minutes * 60) + seconds;
            }
            if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                return (hours * 3600) + (minutes * 60) + seconds;
            }
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return 0;
    }

    @NonNull
    private static String normalizeDurationLabel(@Nullable String rawDuration) {
        if (rawDuration == null) {
            return "";
        }

        String normalized = rawDuration.trim();
        if (normalized.isEmpty() || normalized.contains("--")) {
            return "";
        }

        return parseDurationSeconds(normalized) > 0 ? normalized : "";
    }

    private boolean isLikelyShort(@Nullable PlaylistTrack track) {
        if (track == null) return false;
        String lowerTitle = track.title.toLowerCase(Locale.US);
        if (lowerTitle.contains("#shorts") || lowerTitle.contains(" shorts") || lowerTitle.contains("shorts ")) {
            return true;
        }
        int seconds = parseDurationSeconds(track.duration);
        return seconds > 0 && seconds < 70;
    }

    private void applyHeaderBackdropVisualState(@NonNull ImageView backdrop) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String blurTag = "header-blur-44";
            Object currentTag = backdrop.getTag();
            if (!(currentTag instanceof String) || !TextUtils.equals((String) currentTag, blurTag)) {
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(44f, 44f, Shader.TileMode.CLAMP));
                backdrop.setTag(blurTag);
            }
            backdrop.setAlpha(0.68f);
            backdrop.clearColorFilter();
            return;
        }

        backdrop.setTag(null);
        backdrop.setAlpha(0.68f);
        backdrop.clearColorFilter();
    }

    @NonNull
    private String formatTotalDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return String.format(Locale.US, "%dh %02dm", hours, minutes);
        }
        return String.format(Locale.US, "%dm", minutes);
    }

    private final class PlaylistHeaderAdapter extends RecyclerView.Adapter<PlaylistHeaderAdapter.HeaderViewHolder> {

        @NonNull
        @Override
        public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist_detail_header, parent, false);
            return new HeaderViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty()) {
                for (Object payload : payloads) {
                    if (PAYLOAD_STATE_ONLY.equals(payload)) {
                        bindOfflineState(holder);
                    }
                }
                return;
            }
            onBindViewHolder(holder, position);
        }

        @Override
        public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
            holder.tvPlaylistName.setText(headerPlaylistTitle);
            holder.tvGoogleProfileName.setText(headerProfileName);
            holder.tvPlaylistInfo.setText(headerPlaylistInfo);
            holder.vPlaylistBackdropScrim.setVisibility(View.VISIBLE);
            holder.vPlaylistBackdropBottomFade.setVisibility(View.VISIBLE);
            holder.vPlaylistBackdropAmoledFade.setVisibility(View.GONE);
            
            bindOfflineState(holder);

            if (isLikedPlaylistContext(currentPlaylistId)) {
                // Liked playlist: gradient background + thumb-up icon scaled to ~50% of cover
                float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                int padPx = Math.round(65 * density);
                holder.ivPlaylistCover.setPadding(padPx, padPx, padPx, padPx);
                holder.ivPlaylistCover.setTag(R.id.tag_artwork_signature, null);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.ivPlaylistCover.setBackgroundResource(R.drawable.bg_music_liked_gradient);
                holder.ivPlaylistCover.setImageResource(R.drawable.ic_thumb_up_liked);
                holder.ivPlaylistCover.setColorFilter(Color.WHITE);
                // Backdrop: same gradient so header looks cohesive
                holder.ivPlaylistBackdrop.setTag(R.id.tag_artwork_signature, null);
                holder.ivPlaylistBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistBackdrop.setBackgroundResource(R.drawable.bg_music_liked_gradient);
                holder.ivPlaylistBackdrop.setImageDrawable(null);
            } else if (headerGridUrls.size() >= 4) {
                holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistCover.setBackground(null);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistBackdrop.setBackground(null);
                PlaylistGridArtLoader.load(holder.ivPlaylistCover, headerGridUrls, 800);
                PlaylistGridArtLoader.load(holder.ivPlaylistBackdrop, headerGridUrls, 320);
            } else if (isLocalFilesContext(currentPlaylistId)) {
                // Local files: white folder icon on black background, scaled to ~50% of cover
                float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                int padPx = Math.round(50 * density);
                holder.ivPlaylistCover.setPadding(padPx, padPx, padPx, padPx);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.ivPlaylistCover.setBackgroundColor(android.graphics.Color.BLACK);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistCover.setImageResource(R.drawable.ic_folder_white);
                holder.ivPlaylistBackdrop.setBackground(null);
                holder.ivPlaylistBackdrop.setImageDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            } else if (isRadioContext && !TextUtils.isEmpty(headerPlaylistThumbnail)) {
                // Radio context: HD image from the originating track's videoId
                holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistCover.setBackground(null);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistBackdrop.setBackground(null);
                String radioVideoId = currentPlaylistId.startsWith("RDAMVM") ? currentPlaylistId.substring(6) : "";
                String hdUrl = !radioVideoId.isEmpty()
                        ? "https://i.ytimg.com/vi/" + android.net.Uri.encode(radioVideoId) + "/maxresdefault.jpg"
                        : headerPlaylistThumbnail.trim();
                Glide.with(holder.itemView)
                        .load(hdUrl)
                        .error(Glide.with(holder.itemView).load(headerPlaylistThumbnail.trim()))
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(com.bumptech.glide.Priority.HIGH)
                        .override(800, 800)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(holder.ivPlaylistCover);
                Glide.with(holder.itemView)
                        .load(hdUrl)
                        .error(Glide.with(holder.itemView).load(headerPlaylistThumbnail.trim()))
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(320, 320)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(holder.ivPlaylistBackdrop);
            } else {
                // Grey placeholder until track data arrives and grid can be built
                holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistCover.setBackground(null);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistBackdrop.setBackground(null);
                int placeholderColor = ContextCompat.getColor(requireContext(), R.color.surface_high);
                holder.ivPlaylistCover.setTag(R.id.tag_artwork_signature, null);
                holder.ivPlaylistCover.setImageDrawable(new android.graphics.drawable.ColorDrawable(placeholderColor));
                holder.ivPlaylistBackdrop.setTag(R.id.tag_artwork_signature, null);
                holder.ivPlaylistBackdrop.setImageDrawable(new android.graphics.drawable.ColorDrawable(placeholderColor));
            }

            applyHeaderBackdropVisualState(holder.ivPlaylistBackdrop);

            if (headerProfilePhoto != null) {
                Glide.with(holder.itemView)
                        .load(headerProfilePhoto)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .onlyRetrieveFromCache(!isInternetAvailable())
                        .circleCrop()
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .placeholder(android.R.drawable.ic_menu_myplaces)
                        .error(android.R.drawable.ic_menu_myplaces)
                        .into(holder.ivGoogleProfile);
            } else {
                holder.ivGoogleProfile.setImageResource(android.R.drawable.ic_menu_myplaces);
            }

        }

        private void bindOfflineState(@NonNull HeaderViewHolder holder) {
            if (isLocalFilesContext(currentPlaylistId)) {
                holder.btnDownload.setVisibility(View.GONE);
                holder.btnEditPlaylist.setVisibility(View.GONE);
                holder.btnSharePlaylist.setVisibility(View.GONE);
                return;
            }
            boolean offlineAutoEnabled = isCurrentPlaylistOfflineAutoEnabled();
            boolean completeOffline = isPersistedOfflineCompleteStateForCurrentPlaylist();
            holder.btnEditPlaylist.setVisibility(View.VISIBLE);

            if (offlineAutoEnabled) {
                holder.btnDownload.setImageResource(R.drawable.ic_check_small);
                holder.btnDownload.setBackgroundResource(completeOffline
                        ? R.drawable.bg_offline_state_filled_primary
                        : R.drawable.bg_playlist_action_dark);
                holder.btnDownload.setColorFilter(completeOffline
                        ? ContextCompat.getColor(requireContext(), R.color.surface_dark)
                        : 0xFFFFFFFF);
            } else {
                holder.btnDownload.setImageResource(R.drawable.ic_download_bold);
                holder.btnDownload.setBackgroundResource(R.drawable.bg_playlist_action_dark);
                holder.btnDownload.setColorFilter(0xFFFFFFFF);
            }
        }

        @Override
        public int getItemCount() {
            return 1;
        }

        final class HeaderViewHolder extends RecyclerView.ViewHolder {
            final LinearLayout llPlaylistHeaderRoot;
            final ConstraintLayout flPlaylistHeaderContainer;
            final ImageView ivPlaylistCover;
            final ImageView ivPlaylistBackdrop;
            final View vPlaylistBackdropScrim;
            final View vPlaylistBackdropBottomFade;
            final View vPlaylistBackdropAmoledFade;
            final ShapeableImageView ivGoogleProfile;
            final TextView tvPlaylistName;
            final TextView tvGoogleProfileName;
            final TextView tvPlaylistInfo;
            final MaterialButton btnListenNow;
            final ImageButton btnDownload;
            final ImageButton btnEditPlaylist;
            final ImageButton btnSharePlaylist;
            final ImageButton btnShufflePlay;

            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                llPlaylistHeaderRoot = itemView.findViewById(R.id.llPlaylistHeaderRoot);
                flPlaylistHeaderContainer = itemView.findViewById(R.id.flPlaylistHeaderContainer);
                ivPlaylistCover = itemView.findViewById(R.id.ivPlaylistCover);
                ivPlaylistBackdrop = itemView.findViewById(R.id.ivPlaylistBackdrop);
                vPlaylistBackdropScrim = itemView.findViewById(R.id.vPlaylistBackdropScrim);
                vPlaylistBackdropBottomFade = itemView.findViewById(R.id.vPlaylistBackdropBottomFade);
                vPlaylistBackdropAmoledFade = itemView.findViewById(R.id.vPlaylistBackdropAmoledFade);
                ivGoogleProfile = itemView.findViewById(R.id.ivGoogleProfile);
                tvPlaylistName = itemView.findViewById(R.id.tvPlaylistName);
                tvGoogleProfileName = itemView.findViewById(R.id.tvGoogleProfileName);
                tvPlaylistInfo = itemView.findViewById(R.id.tvPlaylistInfo);
                btnListenNow = itemView.findViewById(R.id.btnListenNow);
                btnDownload = itemView.findViewById(R.id.btnDownload);
                btnEditPlaylist = itemView.findViewById(R.id.btnEditPlaylist);
                btnSharePlaylist = itemView.findViewById(R.id.btnSharePlaylist);
                btnShufflePlay = itemView.findViewById(R.id.btnShufflePlay);

                btnDownload.setOnClickListener(v -> onOfflineTogglePressed());
                btnListenNow.setOnClickListener(v -> {
                    if (!currentTracks.isEmpty()) {
                        onTrackSelected(0);
                    }
                });
                btnEditPlaylist.setOnClickListener(v -> showRenamePlaylistDialog());
                btnSharePlaylist.setOnClickListener(v -> shareCurrentPlaylist());
                btnShufflePlay.setOnClickListener(v -> {
                    if (!currentTracks.isEmpty()) {
                        shuffleModeEnabled = true;
                        int randomIndex = new Random().nextInt(currentTracks.size());
                        onTrackSelected(randomIndex);
                    }
                });
            }
        }
    }

    private void openPlaylistExternal(@NonNull String playlistId) {
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
            
            return;
        }

        if (playlistId.isEmpty()) {
            
            return;
        }

        try {
            String url = "https://music.youtube.com/playlist?list=" + Uri.encode(playlistId);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "openPlaylistExternal failed", e);
        }
    }

    private void showRenamePlaylistDialog() {
        if (!isAdded()) return;
        String currentDisplayName = PlaylistNameOverrideStore.getDisplayName(requireContext(), currentPlaylistId);
        if (currentDisplayName == null || currentDisplayName.isEmpty()) {
            currentDisplayName = currentPlaylistTitle;
        }
        final String finalCurrentDisplay = currentDisplayName;

        EditText input = new EditText(requireContext());
        input.setText(finalCurrentDisplay);
        input.setSelection(input.getText().length());
        int padPx = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(padPx, padPx / 2, padPx, padPx / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cambiar nombre")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (TextUtils.isEmpty(newName) || newName.equals(finalCurrentDisplay)) return;

                    if (isCustomPlaylistContext(currentPlaylistId)) {
                        String oldName = currentPlaylistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
                        boolean ok = CustomPlaylistsStore.INSTANCE.renamePlaylist(requireContext(), oldName, newName);
                        if (ok) {
                            currentPlaylistId = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + newName;
                            currentPlaylistTitle = newName;
                        } else {
                            return;
                        }
                    } else {
                        PlaylistNameOverrideStore.setDisplayName(requireContext(), currentPlaylistId, newName);
                        currentPlaylistTitle = newName;
                    }

                    headerPlaylistTitle = newName;
                    notifyHeaderChanged();
                    MusicPlayerFragment music = getMusicPlayerFragment();
                    if (music != null) music.refreshLibraryUi();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void shareCurrentPlaylist() {
        if (!isAdded()) return;
        String pid = currentPlaylistId;
        String shareText;
        if (isCustomPlaylistContext(pid) || FavoritesPlaylistStore.PLAYLIST_ID.equals(pid)) {
            shareText = currentPlaylistTitle;
        } else {
            shareText = "https://music.youtube.com/playlist?list=" + Uri.encode(pid);
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareText);
        try {
            startActivity(Intent.createChooser(intent, "Compartir playlist"));
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "shareCurrentPlaylist failed", e);
        }
    }

    @Nullable
    private MusicPlayerFragment getMusicPlayerFragment() {
        Activity a = getActivity();
        if (!(a instanceof MainActivity)) return null;
        androidx.fragment.app.Fragment music = ((MainActivity) a).getSupportFragmentManager()
                .findFragmentByTag("module_music");
        return (music instanceof MusicPlayerFragment) ? (MusicPlayerFragment) music : null;
    }

    private static final class PlaylistMeta {
        final String ownerLabel;
        final int songsCount;
        final String estimatedDuration;
        final String visibilityLabel;
        final String ageLabel;

        PlaylistMeta(
                @NonNull String ownerLabel,
                int songsCount,
                @NonNull String estimatedDuration,
                @NonNull String visibilityLabel,
                @NonNull String ageLabel
        ) {
            this.ownerLabel = ownerLabel;
            this.songsCount = songsCount;
            this.estimatedDuration = estimatedDuration;
            this.visibilityLabel = visibilityLabel;
            this.ageLabel = ageLabel;
        }
    }

    private static final class PlaylistTrack {
        final String videoId;
        final String title;
        final String artist;
        final String duration;
        final String imageUrl;
        final String normalizedSubtitle;

        PlaylistTrack(
                @NonNull String videoId,
                @NonNull String title,
                @NonNull String artist,
                @NonNull String duration,
                @NonNull String imageUrl
        ) {
            this.videoId = videoId;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.imageUrl = imageUrl;
            String sub = artist.trim();
            String dur = normalizeDurationLabel(duration);
            if (!dur.isEmpty()) {
                sub = sub.isEmpty() ? dur : sub + " \u2022 " + dur;
            }
            this.normalizedSubtitle = sub;
        }
    }

    private interface OnTrackTap {
        void onTap(int position);
        void onMoreTap(int position, @NonNull View anchor);
    }

    private static final int TRACK_STATE_CACHE_MAX_SIZE = 200;

    private final class PlaylistTrackAdapter extends RecyclerView.Adapter<PlaylistTrackAdapter.TrackViewHolder> {
        private final List<PlaylistTrack> items;
        private final OnTrackTap onTrackTap;
        private final Map<String, Boolean> offlineAvailabilityCache = new LinkedHashMap<String, Boolean>(
                TRACK_STATE_CACHE_MAX_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > TRACK_STATE_CACHE_MAX_SIZE;
            }
        };
        private int activeIndex = -1;
        private int submitGeneration = 0;
        private boolean offlineDownloadRunning;
        @NonNull
        private final Set<String> downloadingTrackIds = new HashSet<>();
        @NonNull
        private final Map<String, Float> downloadingTrackProgressById = new HashMap<>();
        private final Set<Integer> pendingNotifyPositions = new HashSet<>();
        // Track which items have pending state lookups to avoid duplicate requests
        private final Set<String> pendingOfflineLookups = new HashSet<>();
        // Cached colors — resolved once to avoid Resources lock on every bind
        private final int colorPrimary;
        private final int colorSurface;
        // Fixed handler — avoids allocating a new Handler object on every coalesced notify
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        PlaylistTrackAdapter(@NonNull List<PlaylistTrack> items, @NonNull OnTrackTap onTrackTap) {
            this.items = new ArrayList<>(items);
            this.onTrackTap = onTrackTap;
            Context ctx = requireContext();
            colorPrimary = ContextCompat.getColor(ctx, R.color.stitch_blue);
            colorSurface = ContextCompat.getColor(ctx, R.color.surface_dark);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /** Fires a state-only notification immediately, regardless of scroll state.
         *  Used for download status / offline indicators which must always be live. */
        private void immediateNotifyStateChanged(int position) {
            if (position < 0 || position >= getItemCount()) return;
            dispatchWhenIdle(() -> {
                if (position >= 0 && position < getItemCount()) {
                    notifyItemChanged(position, PAYLOAD_STATE_ONLY);
                }
            }, 0);
        }

        void flushDeferredNotifications() {
            if (pendingNotifyPositions.isEmpty()) return;
            final Set<Integer> snapshot = new HashSet<>(pendingNotifyPositions);
            pendingNotifyPositions.clear();
            dispatchWhenIdle(() -> {
                int size = getItemCount();
                for (int pos : snapshot) {
                    if (pos >= 0 && pos < size) {
                        notifyItemChanged(pos, PAYLOAD_STATE_ONLY);
                    }
                }
            }, 0);
        }

        void submitTracks(@NonNull List<PlaylistTrack> newItems) {
            final List<PlaylistTrack> previous = new ArrayList<>(items);
            final List<PlaylistTrack> incoming = new ArrayList<>(newItems);
            final int generation = ++submitGeneration;

            // Offload DiffUtil to background thread to avoid UI stutter on large lists during playback
            trackStateLookupExecutor.execute(() -> {
                final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                    @Override
                    public int getOldListSize() {
                        return previous.size();
                    }

                    @Override
                    public int getNewListSize() {
                        return incoming.size();
                    }

                    @Override
                    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                        PlaylistTrack oldItem = previous.get(oldItemPosition);
                        PlaylistTrack newItem = incoming.get(newItemPosition);
                        return TextUtils.equals(oldItem.videoId, newItem.videoId);
                    }

                    @Override
                    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                        PlaylistTrack oldItem = previous.get(oldItemPosition);
                        PlaylistTrack newItem = incoming.get(newItemPosition);
                        return TextUtils.equals(oldItem.videoId, newItem.videoId)
                                && TextUtils.equals(oldItem.title, newItem.title)
                                && TextUtils.equals(oldItem.artist, newItem.artist)
                                && TextUtils.equals(oldItem.duration, newItem.duration)
                                && TextUtils.equals(oldItem.imageUrl, newItem.imageUrl);
                    }
                });

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    if (generation != submitGeneration) return;

                    items.clear();
                    items.addAll(incoming);

                    if (activeIndex >= items.size()) {
                        activeIndex = -1;
                    }

                    diffResult.dispatchUpdatesTo(this);

                    // Trigger offline state lookup for the initial visible range.
                    // Use a one-shot OnLayoutChangeListener: fires exactly after the RV
                    // completes its first layout pass, guaranteeing valid item positions.
                    if (rvPlaylistContent != null) {
                        rvPlaylistContent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(View v, int l, int t, int r, int b,
                                    int ol, int ot, int or, int ob) {
                                rvPlaylistContent.removeOnLayoutChangeListener(this);
                                if (!isAdded() || trackAdapter == null) return;
                                RecyclerView.LayoutManager lm = rvPlaylistContent.getLayoutManager();
                                if (lm instanceof LinearLayoutManager) {
                                    LinearLayoutManager llm = (LinearLayoutManager) lm;
                                    int first = llm.findFirstVisibleItemPosition() - 1;
                                    int last = llm.findLastVisibleItemPosition() - 1;
                                    if (first >= 0) {
                                        trackAdapter.loadStateForVisibleRange(first, last);
                                        trackAdapter.reloadImagesForRange(first, last);
                                    }
                                }
                            }
                        });
                    }
                });
            });
        }

        void setActiveIndex(int activeIndex) {
            int previous = this.activeIndex;
            this.activeIndex = activeIndex;

            // Use PAYLOAD_STATE_ONLY so neither the previously-active nor the newly-active
            // row triggers a full rebind (which would reload the image via Glide).
            // The partial-bind path in onBindViewHolder(holder, position, payloads) handles
            // the overlay / equalizer / subtitle update without touching ivTrackArt.
            if (previous >= 0 && previous < getItemCount() && previous != activeIndex) {
                dispatchWhenIdle(() -> { if (previous < getItemCount()) notifyItemChanged(previous, PAYLOAD_STATE_ONLY); }, 0);
            }
            if (activeIndex >= 0 && activeIndex < getItemCount()) {
                dispatchWhenIdle(() -> { if (activeIndex < getItemCount()) notifyItemChanged(activeIndex, PAYLOAD_STATE_ONLY); }, 0);
            }
        }

        private void dispatchWhenIdle(@NonNull Runnable action, int attempt) {
            if (rvPlaylistContent == null) { action.run(); return; }
            if (!rvPlaylistContent.isComputingLayout() && !rvPlaylistContent.isLayoutRequested()) {
                action.run();
            } else if (attempt < 5) {
                int delay = attempt < 3 ? 16 : 32;
                mainHandler.postDelayed(() -> dispatchWhenIdle(action, attempt + 1), delay);
            }
        }

        void setOfflineDownloadState(
                boolean running,
                @NonNull Set<String> currentTrackIds,
                @NonNull Map<String, Float> progressByTrackId
        ) {
            Set<String> normalizedIds = new HashSet<>();
            for (String rawId : currentTrackIds) {
                if (TextUtils.isEmpty(rawId)) {
                    continue;
                }
                normalizedIds.add(rawId.trim());
            }

            Map<String, Float> normalizedProgressById = new HashMap<>();
            if (running) {
                for (Map.Entry<String, Float> entry : progressByTrackId.entrySet()) {
                    if (entry == null || TextUtils.isEmpty(entry.getKey())) {
                        continue;
                    }
                    String id = entry.getKey().trim();
                    float value = entry.getValue() == null ? 0f : entry.getValue();
                    normalizedProgressById.put(id, Math.max(0f, Math.min(1f, value)));
                }
            }

            boolean progressChanged = hasProgressChanged(normalizedIds, normalizedProgressById);

            if (offlineDownloadRunning == running
                    && downloadingTrackIds.equals(normalizedIds)
                    && !progressChanged) {
                return;
            }

            Set<String> previousIds = new HashSet<>(downloadingTrackIds);
            boolean wasRunning = offlineDownloadRunning;
            offlineDownloadRunning = running;
            downloadingTrackIds.clear();
            downloadingTrackProgressById.clear();
            if (running) {
                downloadingTrackIds.addAll(normalizedIds);
                downloadingTrackProgressById.putAll(normalizedProgressById);
            }

            // Invalidate cache for all tracks that changed state so the next bind
            // triggers a real disk lookup via triggerOfflineStateLookup, avoiding
            // false positives when a download failed internally.
            for (String previousTrackId : previousIds) {
                invalidateTrackStateCache(previousTrackId);
            }
            for (String currentTrackId : downloadingTrackIds) {
                invalidateTrackStateCache(currentTrackId);
            }

            Set<String> changedIds = new HashSet<>(previousIds);
            changedIds.addAll(downloadingTrackIds);
            if (progressChanged) {
                changedIds.addAll(downloadingTrackIds);
            }
            // Use direct post (no dispatchWhenIdle retry loop) — PAYLOAD_STATE_ONLY binds
            // don't affect layout so there's no risk of crashing during layout computation.
            for (String trackId : changedIds) {
                int index = indexOfTrackById(trackId);
                if (index >= 0 && index < getItemCount()) {
                    final int pos = index;
                    mainHandler.post(() -> {
                        if (pos >= 0 && pos < getItemCount()) {
                            notifyItemChanged(pos, PAYLOAD_STATE_ONLY);
                        }
                    });
                }
            }

            // After batch completes or tracks leave active set, schedule a verified
            // disk lookup so the UI reflects the real file state (not cached assumptions).
            if (wasRunning && rvPlaylistContent != null) {
                mainHandler.postDelayed(() -> {
                    if (rvPlaylistContent == null) return;
                    RecyclerView.LayoutManager lm = rvPlaylistContent.getLayoutManager();
                    if (lm instanceof LinearLayoutManager) {
                        int first = ((LinearLayoutManager) lm).findFirstVisibleItemPosition() - 1;
                        int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition() - 1;
                        if (first >= 0) {
                            loadStateForVisibleRange(first, last);
                        }
                    }
                }, 80L);
            }
        }

        private boolean hasProgressChanged(
                @NonNull Set<String> normalizedIds,
                @NonNull Map<String, Float> normalizedProgressById
        ) {
            for (String id : normalizedIds) {
                float oldValue = progressForTrack(id, downloadingTrackProgressById);
                float newValue = progressForTrack(id, normalizedProgressById);
                if (Math.abs(oldValue - newValue) > 0.001f) {
                    return true;
                }
            }
            return false;
        }

        private float progressForTrack(@Nullable String id, @NonNull Map<String, Float> source) {
            if (TextUtils.isEmpty(id)) {
                return 0f;
            }
            Float value = source.get(id);
            if (value == null) {
                return 0f;
            }
            return Math.max(0f, Math.min(1f, value));
        }

        private int indexOfTrackById(@Nullable String trackId) {
            if (TextUtils.isEmpty(trackId)) {
                return -1;
            }

            for (int i = 0; i < items.size(); i++) {
                if (TextUtils.equals(trackId, items.get(i).videoId)) {
                    return i;
                }
            }
            return -1;
        }

        void invalidateTrackStateCache() {
            offlineAvailabilityCache.clear();
            lastOfflineStateLookupTimeByTrack.clear();
        }

        void invalidateTrackStateCache(@Nullable String trackId) {
            if (TextUtils.isEmpty(trackId)) {
                return;
            }
            String normalized = trackId.trim();
            offlineAvailabilityCache.remove(normalized);
            // Clear debounce timestamp so re-lookup is not blocked after invalidation
            lastOfflineStateLookupTimeByTrack.remove(normalized);
        }

        /**
         * Invalidates track state cache only for tracks in the visible range.
         * This avoids clearing the entire cache (hundreds of tracks) when only
         * 10-15 visible items need refreshing.
         */
        void invalidateVisibleTrackStateCache(
                @NonNull List<PlaylistTrack> allTracks, int startIndex, int endIndex
        ) {
            int safeStart = Math.max(0, startIndex);
            int safeEnd = Math.min(allTracks.size() - 1, endIndex);
            for (int i = safeStart; i <= safeEnd; i++) {
                PlaylistTrack track = allTracks.get(i);
                if (track != null && !TextUtils.isEmpty(track.videoId)) {
                    String normalized = track.videoId.trim();
                    offlineAvailabilityCache.remove(normalized);
                    lastOfflineStateLookupTimeByTrack.remove(normalized);
                }
            }
        }

        private boolean isOfflineAvailable(
                @NonNull Context context,
                @Nullable String trackId,
                @Nullable String expectedDuration,
                int position
        ) {
            if (TextUtils.isEmpty(trackId)) {
                return false;
            }
            String normalized = trackId.trim();
            Boolean cached = offlineAvailabilityCache.get(normalized);
            // Solo devolver valor cacheado; NO iniciar lookup aquí
            // Los lookups se inician desde loadStateForVisibleRange() en el scroll listener
            return cached != null ? cached : false;
        }

        /**
         * Inicia lookup de estado offline para items visibles.
         * Llamado desde scroll listener para cargar estado solo de items en pantalla.
         */
        void triggerOfflineStateLookup(int position, @NonNull PlaylistTrack track) {
            if (position < 0 || position >= items.size()) return;
            String normalized = track.videoId == null ? "" : track.videoId.trim();
            if (normalized.isEmpty()) return;
            
            Boolean cached = offlineAvailabilityCache.get(normalized);
            if (cached != null) return; // Ya está en cache
            if (pendingOfflineLookups.contains(normalized)) return; // Ya hay lookup en progreso
            
            long now = System.currentTimeMillis();
            Long lastLookup = lastOfflineStateLookupTimeByTrack.get(normalized);
            if (lastLookup != null && now - lastLookup < OFFLINE_STATE_LOOKUP_DEBOUNCE_MS) return;
            
            lastOfflineStateLookupTimeByTrack.put(normalized, now);
            pendingOfflineLookups.add(normalized);
            
            Context context = requireContext();
            trackStateLookupExecutor.execute(() -> {
                boolean available = OfflineAudioStore.hasValidatedOfflineAudio(context, normalized, track.duration);
                mainHandler.post(() -> {
                    pendingOfflineLookups.remove(normalized);
                    Boolean current = offlineAvailabilityCache.get(normalized);
                    if (current == null || current != available) {
                        offlineAvailabilityCache.put(normalized, available);
                        // Only notify if this position still shows the same track
                        // (ViewHolder may have been recycled and rebound to a different track)
                        if (position >= 0 && position < items.size()
                                && TextUtils.equals(items.get(position).videoId, track.videoId)) {
                            immediateNotifyStateChanged(position);
                        }
                    }
                });
            });
        }

        /**
         * Carga el estado offline solo para items visibles en pantalla.
         * Llamado desde el scroll listener del RecyclerView.
         */
        void loadStateForVisibleRange(int firstVisible, int lastVisible) {
            if (items.isEmpty()) return;
            int safeStart = Math.max(0, firstVisible);
            int safeEnd = Math.min(items.size() - 1, lastVisible);
            for (int i = safeStart; i <= safeEnd; i++) {
                PlaylistTrack track = items.get(i);
                if (track != null && !TextUtils.isEmpty(track.videoId)) {
                    triggerOfflineStateLookup(i, track);
                }
            }
        }

        /**
         * Reloads track artwork for the visible range using a viewport-first wave:
         * starts at the first visible item and loads outward one frame at a time
         * so the user sees the tracks they are actually looking at fill in first,
         * rather than loading top-to-bottom from off-screen items.
         * Uses Priority.HIGH so these requests beat any queued background work.
         */
        void reloadImagesForRange(int firstVisible, int lastVisible) {
            if (rvPlaylistContent == null) return;
            int safeStart = Math.max(0, firstVisible);
            int safeEnd = Math.min(items.size() - 1, lastVisible);
            int count = safeEnd - safeStart + 1;
            if (count <= 0) return;
            // Build a viewport-first order: safeStart, safeStart+1, ..., safeEnd
            // (already viewport-order since the user landed here after a fling;
            // we just load one per frame to create the wave effect instead of
            // issuing all requests in the same frame).
            int[] order = new int[count];
            for (int i = 0; i < count; i++) {
                order[i] = safeStart + i;
            }
            scheduleWaveImageLoad(order, 0);
        }

        /** Loads artwork in batches of WAVE_BATCH_SIZE per frame so decode work is
         *  spread across multiple frames instead of blocking a single one. */
        private static final int WAVE_BATCH_SIZE = 4;
        private void scheduleWaveImageLoad(int[] order, int index) {
            if (order.length == 0 || rvPlaylistContent == null || index >= order.length) return;
            mainHandler.post(() -> {
                if (rvPlaylistContent == null) return;
                int end = Math.min(index + WAVE_BATCH_SIZE, order.length);
                for (int i = index; i < end; i++) {
                    int adapterIdx = order[i];
                    if (adapterIdx < 0 || adapterIdx >= items.size()) continue;
                    RecyclerView.ViewHolder vh = rvPlaylistContent.findViewHolderForAdapterPosition(adapterIdx + 1);
                    if (vh instanceof TrackViewHolder) {
                        PlaylistTrack track = items.get(adapterIdx);
                        if (track != null) {
                            loadTrackArt(((TrackViewHolder) vh).ivTrackArt, track.imageUrl, com.bumptech.glide.Priority.HIGH);
                        }
                    }
                }
                if (end < order.length) {
                    scheduleWaveImageLoad(order, end);
                }
            });
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= items.size()) {
                return RecyclerView.NO_ID;
            }
            return items.get(position).videoId.hashCode();
        }

        @NonNull
        @Override
        public TrackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_track, parent, false);
            return new TrackViewHolder(view);
        }

        @Override
        public void onViewRecycled(@NonNull TrackViewHolder holder) {
            super.onViewRecycled(holder);
            // Cancel any in-flight Glide request so it can't land on a rebound holder
            // showing a different track — this is the root cause of wrong-image-on-wrong-row.
            // Skip clearing for local tracks — they always show a static icon.
            int pos = holder.getAdapterPosition();
            boolean isLocal = pos >= 0 && pos < items.size() && LocalFilesStore.isLocalVideoId(items.get(pos).videoId);
            if (!isLocal) {
                Glide.with(holder.ivTrackArt.getContext()).clear(holder.ivTrackArt);
                holder.ivTrackArt.setImageDrawable(null);
            }
            if (pos >= 0 && pos < items.size()) {
                String videoId = items.get(pos).videoId;
                if (!TextUtils.isEmpty(videoId)) {
                    String normalized = videoId.trim();
                    pendingOfflineLookups.remove(normalized);
                    lastOfflineStateLookupTimeByTrack.remove(normalized);
                }
            }
            if (holder.vOfflineProgressFill != null) {
                holder.vOfflineProgressFill.animate().cancel();
                holder.vOfflineProgressFill.setScaleX(0f);
            }
            if (holder.flOfflineProgress != null) {
                holder.flOfflineProgress.setVisibility(View.GONE);
            }
            if (holder.ivOfflineState != null) {
                holder.ivOfflineState.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull TrackViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            // Do NOT load image here — onBindViewHolder already handles it with fling awareness.
            // Loading here too causes duplicate Glide requests and re-queues during fast scroll.
        }

        /** Binds all state-driven UI (offline icon, progress bar, subtitle, active row, overlay).
         *  Called from both the full bind path and the state-only partial bind path. */
        private void bindTrackState(@NonNull TrackViewHolder holder, int position, @NonNull PlaylistTrack track) {
            Context context = holder.itemView.getContext();
            boolean isOfflineAvailable = isOfflineAvailable(context, track.videoId, track.duration, position);
            boolean isCurrentlyDownloading = offlineDownloadRunning
                    && !TextUtils.isEmpty(track.videoId)
                    && downloadingTrackIds.contains(track.videoId)
                    && !isOfflineAvailable;

            holder.tvTrackSubtitle.setText(track.normalizedSubtitle);

            boolean showOfflineState = isOfflineAvailable || isCurrentlyDownloading;
            holder.ivOfflineState.setVisibility(showOfflineState ? View.VISIBLE : View.INVISIBLE);

            if (isOfflineAvailable) {
                holder.ivOfflineState.setImageResource(R.drawable.ic_check_small);
                holder.ivOfflineState.setBackgroundResource(R.drawable.bg_offline_state_filled_primary);
                holder.ivOfflineState.setColorFilter(colorSurface);
            } else if (isCurrentlyDownloading) {
                holder.ivOfflineState.setImageResource(R.drawable.ic_check_small);
                holder.ivOfflineState.setBackgroundResource(R.drawable.bg_offline_state_outline_primary);
                holder.ivOfflineState.setColorFilter(colorPrimary);
            }

            if (isCurrentlyDownloading) {
                holder.flOfflineProgress.setVisibility(View.VISIBLE);
                holder.vOfflineProgressFill.setVisibility(View.VISIBLE);
                float target = progressForTrack(track.videoId, downloadingTrackProgressById);
                holder.vOfflineProgressFill.setPivotX(0f);
                holder.vOfflineProgressFill.animate().cancel();
                holder.vOfflineProgressFill.animate().scaleX(target).setDuration(180L).start();
            } else {
                holder.vOfflineProgressFill.animate().cancel();
                holder.vOfflineProgressFill.setScaleX(0f);
                holder.flOfflineProgress.setVisibility(View.GONE);
            }

            boolean isActive = position == activeIndex;
            holder.rootTrackRow.setBackgroundResource(
                    isActive ? R.drawable.bg_playlist_track_active : R.drawable.bg_playlist_track_default);

            SongPlayerFragment sp = cachedSongPlayer;
            boolean isActuallyPlaying = sp != null && sp.isPlaying();
            String currentVideoId = sp != null ? sp.getLoadedVideoId() : "";
            boolean shouldShowOverlay = isActive && !TextUtils.isEmpty(currentVideoId) && TextUtils.equals(currentVideoId, track.videoId);
            holder.llNowPlayingOverlay.setVisibility(shouldShowOverlay ? View.VISIBLE : View.GONE);
            if (holder.animatedEq != null) {
                holder.animatedEq.setAnimating(shouldShowOverlay && isActuallyPlaying);
            }

        }

        @Override
        public void onBindViewHolder(@NonNull TrackViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_STATE_ONLY)) {
                // State-only partial update — only refresh offline indicators
                // WITHOUT reloading the image, re-creating click listeners, or re-resolving colors.
                bindTrackState(holder, position, items.get(position));
                return;
            }
            onBindViewHolder(holder, position);
        }

        @Override
        public void onBindViewHolder(@NonNull TrackViewHolder holder, int position) {
            PlaylistTrack track = items.get(position);
            holder.tvTrackTitle.setText(track.title);
            Context context = holder.itemView.getContext();

            // Image loading: local tracks show album art if available, otherwise music note icon.
            // For remote tracks, during a fast fling show a grey placeholder only.
            boolean isLocalTrack = LocalFilesStore.isLocalVideoId(track.videoId);
            if (isLocalTrack) {
                if (!TextUtils.isEmpty(track.imageUrl)) {
                    holder.ivTrackArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    holder.ivTrackArt.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    Glide.with(context)
                        .load(android.net.Uri.parse(track.imageUrl))
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_music)
                        .error(R.drawable.ic_music)
                        .override(160, 160)
                        .centerCrop()
                        .into(holder.ivTrackArt);
                } else {
                    Glide.with(context).clear(holder.ivTrackArt);
                    holder.ivTrackArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    holder.ivTrackArt.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_high));
                    holder.ivTrackArt.setImageResource(R.drawable.ic_music);
                }
            } else {
                holder.ivTrackArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivTrackArt.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                loadTrackArt(holder.ivTrackArt, track.imageUrl, com.bumptech.glide.Priority.HIGH);
            }

            // Eagerly trigger state lookup on full bind so first-visible items
            // don't have to wait for the scroll listener / layout listener.
            if (!TextUtils.isEmpty(track.videoId)) {
                triggerOfflineStateLookup(position, track);
            }

            bindTrackState(holder, position, track);
        }

        final class TrackViewHolder extends RecyclerView.ViewHolder {
            final ViewGroup rootTrackRow;
            final ImageView ivTrackArt;
            final FrameLayout llNowPlayingOverlay;
            final AnimatedEqualizerView animatedEq;
            final TextView tvTrackTitle;
            final TextView tvTrackSubtitle;
            final ImageView ivOfflineState;
            final ImageView ivMore;
            final FrameLayout flOfflineProgress;
            final View vOfflineProgressFill;

            TrackViewHolder(@NonNull View itemView) {
                super(itemView);
                rootTrackRow = itemView.findViewById(R.id.rootTrackRow);
                ivTrackArt = itemView.findViewById(R.id.ivTrackArt);
                llNowPlayingOverlay = itemView.findViewById(R.id.llNowPlayingOverlay);
                animatedEq = itemView.findViewById(R.id.animatedEq);
                tvTrackTitle = itemView.findViewById(R.id.tvTrackTitle);
                tvTrackSubtitle = itemView.findViewById(R.id.tvTrackSubtitle);
                ivOfflineState = itemView.findViewById(R.id.ivOfflineState);
                ivMore = itemView.findViewById(R.id.ivMore);
                flOfflineProgress = itemView.findViewById(R.id.flOfflineProgress);
                vOfflineProgressFill = itemView.findViewById(R.id.vOfflineProgressFill);

                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onTrackTap.onTap(pos);
                });
                ivMore.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onTrackTap.onMoreTap(pos, ivMore);
                });
                itemView.setOnLongClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return false;
                    onTrackTap.onMoreTap(pos, itemView);
                    return true;
                });
            }
        }
    } // end PlaylistTrackAdapter

    private void launchSearchActivity() {
        if (!isAdded()) return;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openSearchFragment();
        }
    }
}

