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
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
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

    private static final String TAG = "PlaylistDetailFragment";
    private static final String PREFS_STREAMING_CACHE = AppConstants.PREFS_STREAMING_CACHE;
    private static final long TRACKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final String PREF_TRACKS_UPDATED_AT_PREFIX = "playlist_tracks_updated_at_";
    private static final String PREF_TRACKS_DATA_PREFIX = "playlist_tracks_data_";
    private static final String PREF_TRACKS_FULL_CACHE_PREFIX = "playlist_tracks_cache_full_";
    private static final String PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX = "playlist_offline_complete_";
    private static final String PREF_CACHED_GOOGLE_PROFILE_PHOTO_URL = "cached_google_profile_photo_url";
    private static final String PREF_PLAYLIST_OFFLINE_AUTO_PREFIX = "playlist_offline_auto_";
    private static final String PREF_PLAYLIST_GRID_URLS_PREFIX = "playlist_grid_urls_";
    private static final String PREFS_PLAYER_STATE = AppConstants.PREFS_PLAYER_STATE;
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
    // Effectively "load all": the fetch pages through every YouTube continuation until the playlist
    // ends (the service loop stops when there is no nextPageToken), so a playlist with N songs shows
    // all N — no 280-song cap. INITIAL == MAX so canLoadMore stays false after the single full fetch
    // and the result is cached as complete. Any realistic playlist is far below this ceiling.
    private static final int PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT = 100000;
    private static final int PLAYLIST_TRACKS_FETCH_STEP = 220;
    private static final int PLAYLIST_TRACKS_FETCH_MAX_LIMIT = 100000;
    private static final int PLAYLIST_TRACKS_LOAD_MORE_THRESHOLD = 12;
    // Bound for the offline-state-lookup debounce map, decoupled from the fetch ceiling above so the
    // large limit never preallocates/holds a huge map.
    private static final int OFFLINE_STATE_LOOKUP_CACHE_MAX = 4096;
    private static final String OFFLINE_DOWNLOAD_UNIQUE_PREFIX = "offline_playlist_";
    private static final String OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME = "offline_playlist_queue";
    private static final String OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME = "offline_manual_track_queue";
    private static final String TAG_PLAYLIST_DETAIL = "playlist_detail";
    private static final String TAG_MODULE_MUSIC = "module_music";
    private static final String TAG_OFFLINE_DOWNLOAD = "OfflinePlaylistDl";
    private static final long OFFLINE_STATE_LOOKUP_DEBOUNCE_MS = 120L;
    private static final long PLAYLIST_INITIAL_CONTENT_FADE_MS = 220L;
    private boolean pendingEntryFade = true;

    /** Shared singleton — avoids allocation per bind and ensures consistent Glide cache keys. */
    private static final YouTubeCropTransformation SHARED_YT_CROP = new YouTubeCropTransformation();
    /** Shared crossfade — built once, reused on every bind so no transition object is allocated
     *  per row. Glide skips the animation on memory-cache hits, so it only fades on first load. */
    private static final DrawableTransitionOptions SHARED_CROSSFADE =
            DrawableTransitionOptions.withCrossFade(100);
    /** Payload marker for state-only adapter updates (skip image reload). */
    private static final String PAYLOAD_STATE_ONLY = "state_only";
    /** Material standard easing — pre-built once, never re-allocated per animation. */
    private static final android.view.animation.Interpolator MATERIAL_EASE =
            new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** Shared decelerate easing for the offline progress fill — avoids allocating a new
     *  interpolator on every downloading-row bind. */
    private static final android.view.animation.Interpolator DECELERATE_EASE =
            new android.view.animation.DecelerateInterpolator();
    /** Linear easing for the live download fill: consecutive 0→N segments must join into ONE
     *  continuous grow, so each segment eases the same way (a decelerate-per-segment stutters). */
    private static final android.view.animation.Interpolator LINEAR_EASE =
            new android.view.animation.LinearInterpolator();

    public static final String ARG_PLAYLIST_ID = "arg_playlist_id";
    public static final String ARG_PLAYLIST_TITLE = "arg_playlist_title";
    public static final String ARG_PLAYLIST_SUBTITLE = "arg_playlist_subtitle";
    public static final String ARG_PLAYLIST_THUMBNAIL = "arg_playlist_thumbnail";
    public static final String ARG_YOUTUBE_ACCESS_TOKEN = "arg_youtube_access_token";
    /** navigationEndpoint `params` token captured from the opening home card. Forwarded to the
     *  InnerTube /next (mix) or browse (recap) request so YT-generated personal mixes actually
     *  return their tracks instead of an empty panel. Empty for every non-generated entry point. */
    public static final String ARG_PLAYLIST_PARAMS = "arg_playlist_params";
    /** Art-rendering hint from the opening card. When {@link #ART_HINT_SINGLE} the header must
     *  CLONE the card's single cover (foreground + blurred backdrop) and never synthesize a 2x2
     *  mosaic or a 3-circle radio composite — used by the "recomendadas"/"Recaps" carousels and any
     *  recap in "recientes", whose cards always show ONE YT thumbnail. Empty = id-based auto art. */
    public static final String ARG_ART_HINT = "arg_art_hint";
    /** Vista Descargas: muestra SOLO las canciones descargadas a disco de esta playlist y añade
     *  "· N descargadas" al header. Lo activa PlaylistDetailLauncher.openDownloadedOnly. */
    public static final String ARG_DOWNLOADED_ONLY = "arg_downloaded_only";
    public static final String ART_HINT_SINGLE = "single";

    private RecyclerView rvPlaylistContent;
    private View playlistLoadingOverlay;
    private ProgressBar pbPlaylistLoading;
    private View flNoConnectionState;
    private View btnRetryConnection;
    private TextView tvNoConnectionMessage;

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
    // Botón aleatorio del header actualmente en pantalla (para repintar su estado activo/neutro
    // cuando el modo aleatorio o la canción en reproducción cambian fuera del bind).
    @Nullable
    private ImageButton headerShuffleButton;
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
    /** Home card's navigationEndpoint params token; "" for non-generated entry points. Forwarded to
     *  the mix (/next) and browse fetches so YT-generated mixes/recaps return their tracks. */
    @NonNull
    private String currentPlaylistParams = "";
    /** True when the opening card passed {@link #ART_HINT_SINGLE}: the header clones the card's one
     *  cover and skips the 2x2 grid + radio composite (decoupled from {@link #isRadioContext}, which
     *  still drives how the tracklist is FETCHED — a mix keeps loading via /next even when shown as a
     *  single cover). */
    private boolean forceSingleCoverArt = false;
    // Vista "Descargas": la lista se filtra a lo realmente descargado y el header suma "N descargadas".
    private boolean downloadedOnlyMode = false;
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
    private int headerBackdropTopOverlapPx;
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
    /** Bounded auto-retry counter (per playlistId) for FAILED offline downloads, so a
     *  deterministically-failing worker can't spin an unbounded re-enqueue loop. */
    @NonNull
    private final Map<String, Integer> offlineAutoRetryCountByPlaylist = new HashMap<>();
    /** WorkInfo ids seen RUNNING/ENQUEUED/BLOCKED during this observation — their terminal event
     *  is fresh. Historical SUCCEEDED infos replayed on (re)observe are not in this set and must
     *  not re-persist an old run's completion. */
    @NonNull
    private final java.util.Set<java.util.UUID> offlineWorkSeenActiveIds = new java.util.HashSet<>();
    private static final int MAX_OFFLINE_AUTO_RETRY = 3;
    private static final long OFFLINE_AUTO_RETRY_DELAY_MS = 20000L;
    @Nullable
    private Runnable offlineAutoRetryRunnable;
    /** Unique-work name of the most recently enqueued offline download (auto QUEUE vs MANUAL
     *  track queue), so lifecycle re-entry re-observes the queue that is actually active. */
    @Nullable
    private String lastActiveOfflineUniqueName;
    private boolean restoringHiddenPlayerFromSnapshot;
    private boolean awaitingInitialPlaylistRender = true;
    private boolean isScrolling = false;
    /** True only during a momentum fling (SCROLL_STATE_SETTLING). During a fling we defer
     *  the disk-based offline state lookups (MediaMetadataRetriever reads) to avoid flooding
     *  the executor; the SCROLL_STATE_IDLE handler covers the visible range once it settles.
     *  Image loads are NOT deferred — Glide decodes off the main thread and memory-cache
     *  hits bind instantly, so rows fill in while flinging instead of staying grey. */
    private boolean isFlinging = false;
    /** Cached artwork decode size in px (50dp). Resolved once to avoid a DisplayMetrics
     *  lookup on every list bind. */
    private int cachedTrackArtPx = 0;
    /** Cached surface_high color for the track art placeholder. Resolved once to avoid a
     *  Resources lookup on every list bind. */
    private int cachedTrackArtPlaceholderColor = 0;
    /** How many rows past the viewport to warm in the scroll direction. Kept small so off-screen
     *  decodes never steal CPU/decode-thread slots from the rows actually on screen during a fling
     *  — the visible rows must win the cores so the scroll stays fluid. ~a full screen of buffer, so
     *  more rows are already memory-warm before they enter the viewport (fewer rows then need the
     *  idle-batch reload, which is what makes thumbnails appear to load "in blocks"). */
    private static final int ART_PREFETCH_AHEAD = 12;
    /** Last (anchor, direction) pair the artwork prefetcher ran for; prevents re-issuing
     *  the same prefetch batch on every onScrolled frame while the anchor row is unchanged. */
    private int lastArtPrefetchKey = Integer.MIN_VALUE;
    private boolean pendingOfflineToggle = false;
    private final Map<String, Long> lastOfflineStateLookupTimeByTrack = new LinkedHashMap<String, Long>(
            256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > OFFLINE_STATE_LOOKUP_CACHE_MAX;
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
        return newInstance(playlistId, playlistTitle, playlistSubtitle, playlistThumbnail, accessToken, "");
    }

    /** Overload that also carries the home card's navigationEndpoint {@code params} token
     *  ({@link #ARG_PLAYLIST_PARAMS}) so YT-generated mixes/recaps can be loaded. */
    @NonNull
    public static PlaylistDetailFragment newInstance(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle,
            @NonNull String playlistThumbnail,
            @NonNull String accessToken,
            @NonNull String playlistParams
    ) {
        return newInstance(playlistId, playlistTitle, playlistSubtitle, playlistThumbnail,
                accessToken, playlistParams, "");
    }

    /** Overload that also carries the art hint ({@link #ARG_ART_HINT}, e.g. {@link #ART_HINT_SINGLE})
     *  so single-cover carousels (recomendadas/Recaps) force the header to clone their one cover. */
    @NonNull
    public static PlaylistDetailFragment newInstance(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle,
            @NonNull String playlistThumbnail,
            @NonNull String accessToken,
            @NonNull String playlistParams,
            @NonNull String artHint
    ) {
        return newInstance(playlistId, playlistTitle, playlistSubtitle, playlistThumbnail,
                accessToken, playlistParams, artHint, false);
    }

    /** Overload completo que además marca la vista de "solo descargadas" ({@link #ARG_DOWNLOADED_ONLY}). */
    @NonNull
    public static PlaylistDetailFragment newInstance(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle,
            @NonNull String playlistThumbnail,
            @NonNull String accessToken,
            @NonNull String playlistParams,
            @NonNull String artHint,
            boolean downloadedOnly
    ) {
        PlaylistDetailFragment fragment = new PlaylistDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLAYLIST_ID, playlistId);
        args.putString(ARG_PLAYLIST_TITLE, playlistTitle);
        args.putString(ARG_PLAYLIST_SUBTITLE, playlistSubtitle);
        args.putString(ARG_PLAYLIST_THUMBNAIL, playlistThumbnail);
        args.putString(ARG_YOUTUBE_ACCESS_TOKEN, accessToken);
        args.putString(ARG_PLAYLIST_PARAMS, playlistParams);
        args.putString(ARG_ART_HINT, artHint);
        args.putBoolean(ARG_DOWNLOADED_ONLY, downloadedOnly);
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
        // Hide the global header. Do NOT dismiss the activity loading overlay here: it stays up until
        // showInitialLoadingOverlay() (called a few lines below) shows the fragment's own overlay and
        // then hides the activity one INSTANTLY. Cross-fading it here instead showed two overlapping
        // spinners (the activity overlay + flPlaylistLoadingOverlay) during the 200ms fade window.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideTopAppBarForPlaylistDetail();
            ((MainActivity) getActivity()).setContainerOverlayMode(false);
        }
        // Resolve the fragment-scoped Glide manager once here so list binds reuse it
        // instead of paying the Glide.with(Fragment) lookup per row.
        glideManager = Glide.with(this);
        artRequestBase = null;
        rvPlaylistContent = view.findViewById(R.id.rvPlaylistContent);
        playlistLoadingOverlay = view.findViewById(R.id.flPlaylistLoadingOverlay);
        pbPlaylistLoading = view.findViewById(R.id.pbPlaylistLoading);
        flNoConnectionState = view.findViewById(R.id.flNoConnectionState);
        btnRetryConnection = view.findViewById(R.id.btnRetryConnection);
        tvNoConnectionMessage = view.findViewById(R.id.tvNoConnectionMessage);

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
            int densityPx = (int) (6 * getResources().getDisplayMetrics().density);
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
                headerBackdropTopOverlapPx = rvPlaylistContent.getPaddingTop() + densityPx;
                if (headerAdapter != null) {
                    headerAdapter.notifyItemChanged(0);
                }
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
        isRadioContext = isRadioOrMixPlaylistId(playlistId);
        if (isRadioContext && playlistTitle.startsWith("Radio: ")) {
            playlistTitle = playlistTitle.substring(7);
        }
        currentPlaylistTitle = playlistTitle;
        currentPlaylistSubtitle = playlistSubtitle;
        currentPlaylistThumbnail = playlistThumbnail;
        currentPlaylistParams = safeArg(ARG_PLAYLIST_PARAMS);
        // Single-cover carousels (recomendadas/Recaps + recaps in recientes) ask the header to clone
        // their one YT cover instead of guessing art from the id: no 2x2, no 3-circle radio composite.
        forceSingleCoverArt = ART_HINT_SINGLE.equals(safeArg(ARG_ART_HINT));
        downloadedOnlyMode = getArguments() != null && getArguments().getBoolean(ARG_DOWNLOADED_ONLY, false);
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
        // 20 off-screen rows kept bound (rows are light: ~56dp, listeners set once in the
        // holder) so short back-and-forth scrolls re-attach cached views instead of rebinding.
        rvPlaylistContent.setItemViewCacheSize(20);

        // Single unified scroll listener — reduces per-frame dispatch overhead vs. 3 separate listeners
        rvPlaylistContent.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!isAdded()) return;
                isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                isFlinging = newState == RecyclerView.SCROLL_STATE_SETTLING;

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (trackAdapter != null) {
                        trackAdapter.flushDeferredNotifications();
                        if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                            LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                            // ConcatAdapter: header at position 0, tracks start at 1. When the
                            // header is the first visible item, firstVisible maps to -1 — clamp
                            // to 0 so the rows below the header still get their idle pass.
                            int firstVisible = Math.max(0, lm.findFirstVisibleItemPosition() - 1);
                            int lastVisible  = lm.findLastVisibleItemPosition()  - 1;
                            if (lastVisible >= 0) {
                                // Download state is NO LONGER scanned per-scroll — that per-row disk I/O
                                // was the first-scroll jank. Downloaded status now lives in the
                                // "Descargas" library view; here we only settle the artwork.
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

                // Warm artwork for the rows about to enter the viewport. Keyed on the
                // (anchor row, direction) pair so it fires once per new edge row, not on
                // every scroll frame.
                if (trackAdapter != null && dy != 0 && !isFlinging) {
                    int anchor = (dy > 0 ? layoutManager.findLastVisibleItemPosition()
                                         : layoutManager.findFirstVisibleItemPosition()) - 1;
                    int prefetchKey = anchor * 2 + (dy > 0 ? 1 : 0);
                    if (anchor >= 0 && prefetchKey != lastArtPrefetchKey) {
                        lastArtPrefetchKey = prefetchKey;
                        trackAdapter.prefetchArtFrom(anchor, dy > 0 ? 1 : -1);
                    }
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
            try { glideManager().resumeRequests(); } catch (Exception e) {
                Log.w(TAG, "Unexpected error", e);
            }
        }
        if (!isHidden()) onBecameVisible(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        pendingEntryFade = true;
    }

    @Override
    public void onPlaybackSnapshotUpdated() {
        if (isAdded() && !isHidden() && getActivity() != null) {
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
            pendingEntryFade = true;
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

        // Fade-in content once per visibility change
        if (!awaitingInitialPlaylistRender && pendingEntryFade && rvPlaylistContent != null) {
            pendingEntryFade = false;
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
        clearEnterCachedFallback();
        if (offlineAutoRetryRunnable != null) {
            mainHandler.removeCallbacks(offlineAutoRetryRunnable);
            offlineAutoRetryRunnable = null;
        }
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
            } catch (Exception e) {
                Log.w(TAG, "Unexpected error", e);
            }
        }
        playlistLoadingOverlay = null;
        pbPlaylistLoading = null;
        rvPlaylistContent = null;
        // Drop the view-scoped Glide references so a destroyed-view manager is never reused.
        artRequestBase = null;
        glideManager = null;
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
        // Show the fragment's OWN overlay first, so there is always exactly one spinner on screen.
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
        // Now dismiss the activity-level overlay INSTANTLY (not a 200ms cross-fade): the fragment's
        // identical spinner is already up, so the hand-off shows no second spinner and no content flash.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideModuleLoadingOverlayImmediate();
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
            boolean loadMore,
            @NonNull String message
    ) {
        revealPlaylistContentIfNeeded(true);
        if (flNoConnectionState != null) {
            flNoConnectionState.setVisibility(View.VISIBLE);
        }
        if (tvNoConnectionMessage != null) {
            tvNoConnectionMessage.setText(message);
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

    // Silent-on-enter revalidation: throttle repeat entries and defer slightly so the cache paints
    // first (the fresh network result is folded in a moment later, only if it changed).
    private static final long ENTER_REVALIDATE_THROTTLE_MS = 60_000L;
    private static final long ENTER_REVALIDATE_DELAY_MS = 600L;
    private long lastEnterRevalidateAtMs = 0L;
    private String lastEnterRevalidatePlaylistId = "";

    // Refresh INVISIBLE al entrar: cuando la revalidación silenciosa va a disparar, la caché
    // (posiblemente obsoleta — los mixes de YT cambian por completo entre visitas) NO se pinta;
    // el skeleton sigue hasta que llegue la versión fresca y el usuario ve directamente la lista
    // final, nunca el swap. La caché queda de respaldo: si la red falla o tarda más de
    // ENTER_FRESH_WAIT_MAX_MS, se pinta para no dejar la pantalla en blanco.
    private static final long ENTER_FRESH_WAIT_MAX_MS = 4_000L;
    @Nullable
    private List<PlaylistTrack> pendingEnterCachedFallback;
    private String pendingEnterFallbackPlaylistId = "";
    private final Runnable enterFreshFallbackRunnable = this::renderEnterCachedFallback;

    /** True si la revalidación silenciosa al entrar VA a disparar para esta playlist (mismo
     *  criterio y throttle que {@link #maybeRevalidateOnEnter}). */
    private boolean enterRevalidateDue(@NonNull String playlistId) {
        if (playlistId.isEmpty()
                || isFavoritesPlaylistContext(playlistId)
                || isCustomPlaylistContext(playlistId)
                || isLocalFilesContext(playlistId)) {
            return false;
        }
        return !(TextUtils.equals(playlistId, lastEnterRevalidatePlaylistId)
                && System.currentTimeMillis() - lastEnterRevalidateAtMs < ENTER_REVALIDATE_THROTTLE_MS);
    }

    /** Respaldo del refresh invisible: la versión fresca no llegó a tiempo — pinta la caché. */
    private void renderEnterCachedFallback() {
        List<PlaylistTrack> fallback = pendingEnterCachedFallback;
        String pid = pendingEnterFallbackPlaylistId;
        pendingEnterCachedFallback = null;
        pendingEnterFallbackPlaylistId = "";
        if (fallback == null || fallback.isEmpty() || !isAdded()) return;
        if (!TextUtils.equals(currentPlaylistId, pid)) return;
        renderTracks(fallback, pid, true);
    }

    private void clearEnterCachedFallback() {
        pendingEnterCachedFallback = null;
        pendingEnterFallbackPlaylistId = "";
        mainHandler.removeCallbacks(enterFreshFallbackRunnable);
    }

    /**
     * After a playlist renders from cache on entry, silently re-fetch it from YouTube so edits made
     * in YT Music show up WITHOUT a manual pull-to-refresh. Skips locally-authoritative lists
     * (favorites/custom/local files — they have no YT source to reconcile against) and throttles
     * quick re-entries so back-and-forth navigation doesn't spam the network.
     */
    private void maybeRevalidateOnEnter(@NonNull String playlistId, @NonNull String accessToken) {
        if (!isAdded() || playlistId.isEmpty()) return;
        if (isFavoritesPlaylistContext(playlistId)
                || isCustomPlaylistContext(playlistId)
                || isLocalFilesContext(playlistId)) {
            return;
        }
        if (!enterRevalidateDue(playlistId)) {
            return;
        }
        lastEnterRevalidatePlaylistId = playlistId;
        lastEnterRevalidateAtMs = System.currentTimeMillis();
        mainHandler.postDelayed(() -> {
            if (!isAdded() || !TextUtils.equals(currentPlaylistId, playlistId)) return;
            if (playlistTracksLoadMoreInFlight) return;
            String token = TextUtils.isEmpty(accessToken) ? resolveYoutubeAccessToken("") : accessToken;
            playlistTracksRequestedLimit = PLAYLIST_TRACKS_INITIAL_FETCH_LIMIT;
            playlistTracksCanLoadMore = false;
            // forceRefresh=true re-fetches and re-renders only the final list (no skeleton/clear),
            // so a same-content result is a visual no-op and a changed one updates in place.
            bindTrackList(playlistId, token, true);
        }, ENTER_REVALIDATE_DELAY_MS);
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
        // Per-row downloaded-state is no longer scanned from disk — that "escaneo de descargas" was
        // the first-scroll jank and now lives in the "Descargas" library view. Active-download
        // progress bars are driven entirely by the WorkManager observer via setOfflineDownloadState().
        // Kept as a no-op so the existing completion callers stay valid.
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
        // Re-observe whichever queue most recently had this playlist's work. A manual per-row
        // download lives on the MANUAL queue; always defaulting to the bulk QUEUE would detach
        // the single observer slot from in-flight manual progress on every lifecycle re-entry.
        String restoreName = !TextUtils.isEmpty(lastActiveOfflineUniqueName)
                ? lastActiveOfflineUniqueName
                : OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME;
        observeOfflineDownload(restoreName, false);
    }

    /** Keeps only the WorkInfos tagged for the currently-open playlist, so another playlist's
     *  download (which shares the same global unique-work name) cannot bleed its progress,
     *  header text, or terminal state into this screen. */
    @Nullable
    private List<WorkInfo> filterWorkInfosForCurrentPlaylist(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null || workInfos.isEmpty()) {
            return workInfos;
        }
        String tag = currentPlaylistOfflineTag();
        List<WorkInfo> scoped = new ArrayList<>(workInfos.size());
        for (WorkInfo info : workInfos) {
            if (info != null && info.getTags().contains(tag)) {
                scoped.add(info);
            }
        }
        return scoped;
    }

    /** True if any job in the (unfiltered) chain is RUNNING or ENQUEUED. */
    private boolean hasAnyAliveChainWork(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null) return false;
        for (WorkInfo info : workInfos) {
            if (info == null) continue;
            WorkInfo.State s = info.getState();
            if (s == WorkInfo.State.RUNNING || s == WorkInfo.State.ENQUEUED) {
                return true;
            }
        }
        return false;
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
        // Kill the single-thumbnail → 2x2 "flash": if this playlist already has a persisted grid
        // from a previous visit, adopt it BEFORE this first header bind so the cover chain lands on
        // the grid branch straight away (PlaylistGridArtLoader then hits its disk cache and paints
        // the composite at reveal) instead of drawing the single arg thumbnail and swapping to the
        // grid a few frames later. Icon/radio headers never use a grid, so skip them — their
        // branches win the cover chain regardless and a stale persisted grid must not hijack them.
        if (isAdded()
                && !isLikedPlaylistContext(currentPlaylistId)
                && !isFavoritesPlaylistContext(currentPlaylistId)
                && !isLocalFilesContext(currentPlaylistId)
                && !isRadioContext
                && !forceSingleCoverArt
                // Auto-generated playlists (RDCLAK…) keep their single static cover, never a grid —
                // so a stale persisted grid must not hijack the single-image header either.
                && !isAutoGeneratedPlaylistId(currentPlaylistId)) {
            List<String> persistedGrid = loadPersistedGridUrls(currentPlaylistId);
            if (persistedGrid != null && persistedGrid.size() >= 4) {
                headerGridUrls = persistedGrid;
            }
        }
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

    private int trackArtSizePx(@NonNull Context ctx) {
        if (cachedTrackArtPx == 0) {
            float density = ctx.getResources().getDisplayMetrics().density;
            cachedTrackArtPx = Math.max(100, Math.round(50 * density));
        }
        return cachedTrackArtPx;
    }

    /**
     * Rewrites a googleusercontent/ggpht thumbnail URL to request the row-thumbnail size from
     * the CDN (the YT Music approach: tiny transfer + tiny disk cache entry for a 50dp cell,
     * instead of shipping the full-size cover and downsampling locally). MUST be used by every
     * row-art path (bind, fling, prefetch) so Glide cache keys stay consistent.
     */
    @Nullable
    private String sizedTrackArtUrl(@NonNull Context ctx, @Nullable String url) {
        return ThumbnailUrls.atSize(url, trackArtSizePx(ctx));
    }

    private int trackArtPlaceholderColor(@NonNull Context ctx) {
        if (cachedTrackArtPlaceholderColor == 0) {
            cachedTrackArtPlaceholderColor = ContextCompat.getColor(ctx, R.color.surface_high);
        }
        return cachedTrackArtPlaceholderColor;
    }

    /** Cached grey placeholder drawable — built once and reused on every bind instead of
     *  allocating a new ColorDrawable per row (a per-frame GC source during scroll). */
    private android.graphics.drawable.ColorDrawable cachedTrackArtPlaceholder;

    private android.graphics.drawable.ColorDrawable trackArtPlaceholder(@NonNull Context ctx) {
        if (cachedTrackArtPlaceholder == null) {
            cachedTrackArtPlaceholder = new android.graphics.drawable.ColorDrawable(trackArtPlaceholderColor(ctx));
        }
        return cachedTrackArtPlaceholder;
    }

    /** Fragment-scoped Glide manager resolved ONCE in onViewCreated. Reusing it avoids the
     *  expensive Glide.with(Fragment) FragmentManager traversal on every list bind — the
     *  dominant per-bind main-thread cost behind the scroll jank. */
    @Nullable
    private RequestManager glideManager;
    /** Base artwork request holding the static request shape (transform, decode format, disk
     *  cache, override size, placeholder). Cloned per bind so the request graph isn't rebuilt
     *  from scratch for every row; only the model/priority/transition vary per call. */
    @Nullable
    private RequestBuilder<android.graphics.drawable.Drawable> artRequestBase;

    @NonNull
    private RequestManager glideManager() {
        if (glideManager == null) {
            glideManager = Glide.with(this);
        }
        return glideManager;
    }

    @NonNull
    private RequestBuilder<android.graphics.drawable.Drawable> artRequestBase(@NonNull Context ctx) {
        if (artRequestBase == null) {
            int px = trackArtSizePx(ctx);
            artRequestBase = glideManager()
                    .asDrawable()
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(px, px)
                    .placeholder(trackArtPlaceholder(ctx));
        }
        return artRequestBase;
    }

    private void loadTrackArt(
            @NonNull ImageView target,
            @Nullable String imageUrl,
            @NonNull com.bumptech.glide.Priority priority
    ) {
        Context ctx = target.getContext();
        if (TextUtils.isEmpty(imageUrl)) {
            target.setTag(R.id.tag_artwork_signature, null);
            glideManager().clear(target);
            target.setImageDrawable(null);
            return;
        }
        // Anti-rebind guard: skip rebuilding the whole Glide request graph when this
        // ImageView already shows the same artwork. Prevents redundant work on re-binds
        // to the same track (notifyItemChanged, recycled holders) without affecting fresh
        // scroll (each new row has a different URL, so the signature differs).
        String url = sizedTrackArtUrl(ctx, imageUrl.trim());
        Object previousSignature = target.getTag(R.id.tag_artwork_signature);
        if (url.equals(previousSignature)) {
            return;
        }
        target.setTag(R.id.tag_artwork_signature, url);
        // The grey placeholder replaces a recycled holder's stale art immediately on
        // request start, so a row can never briefly show the previous track's image.
        // Memory-cache hits complete synchronously inside into() and never show it.
        boolean offlineOnly = !cachedHasValidatedInternet(ctx);
        // Clone the prebuilt base request (transform/format/diskCache/override/placeholder are
        // already baked in) so the request graph isn't reassembled per row — only the dynamic
        // bits (model, cache policy, priority, transition) are applied here.
        // The .error() fallback retries the ORIGINAL (unsized) URL from cache only: entries
        // cached before the URL-sizing update are keyed by the raw URL, so without this an
        // offline session right after updating would show placeholders for art that IS on disk.
        String rawUrl = imageUrl.trim();
        com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request =
                artRequestBase(ctx).clone()
                        .load(url)
                        .onlyRetrieveFromCache(offlineOnly)
                        .priority(priority)
                        .transition(SHARED_CROSSFADE);
        if (url != null && !url.equals(rawUrl)) {
            request = request.error(
                    artRequestBase(ctx).clone()
                            .load(rawUrl)
                            .onlyRetrieveFromCache(true));
        }
        request.into(target);
    }

    /**
     * Mid-fling artwork bind. Paints the row only if the thumbnail is already resolvable from
     * cache — a memory hit shows instantly and synchronously (no decode, no executor work, no
     * crossfade), so it never costs a frame. On a miss it leaves the grey placeholder (the flying
     * row stays grey) and never touches the network. The signature is set to the URL so that once
     * the list settles, {@link PlaylistTrackAdapter#reloadImagesForRange} skips the rows that
     * already resolved and issues the full load only for the ones still on the placeholder.
     */
    private void loadTrackArtCacheOnly(@NonNull ImageView target, @Nullable String imageUrl) {
        if (TextUtils.isEmpty(imageUrl)) {
            target.setTag(R.id.tag_artwork_signature, null);
            glideManager().clear(target);
            target.setImageDrawable(null);
            return;
        }
        String url = sizedTrackArtUrl(target.getContext(), imageUrl.trim());
        if (url != null && url.equals(target.getTag(R.id.tag_artwork_signature))) {
            return;
        }
        target.setTag(R.id.tag_artwork_signature, url);
        // No .transition() is applied (the base request bakes none) so a cache hit paints with no
        // crossfade — instant and frame-free, which is exactly what we want mid-fling.
        artRequestBase(target.getContext()).clone()
                .load(url)
                .onlyRetrieveFromCache(true)
                .priority(com.bumptech.glide.Priority.LOW)
                .into(target);
    }

    /**
     * Warms Glide's memory/disk caches for rows just beyond the viewport in the scroll
     * direction, using the exact same request shape (size, transform, decode format) as
     * {@link #loadTrackArt} so the cache keys match and the bind is a synchronous hit.
     * LOW priority keeps these behind the visible rows' HIGH priority requests.
     */
    private void prefetchTrackArt(@NonNull List<PlaylistTrack> tracks, int anchorIndex, int direction, int count) {
        if (!isAdded() || anchorIndex < 0 || anchorIndex >= tracks.size()) return;
        Context ctx = requireContext();
        boolean offlineOnly = !cachedHasValidatedInternet(ctx);
        for (int i = 1; i <= count; i++) {
            int idx = anchorIndex + direction * i;
            if (idx < 0 || idx >= tracks.size()) break;
            PlaylistTrack track = tracks.get(idx);
            if (track == null || TextUtils.isEmpty(track.imageUrl)
                    || LocalFilesStore.isLocalVideoId(track.videoId)) {
                continue;
            }
            artRequestBase(ctx).clone()
                    .load(sizedTrackArtUrl(ctx, track.imageUrl.trim()))
                    .onlyRetrieveFromCache(offlineOnly)
                    .priority(com.bumptech.glide.Priority.LOW)
                    .preload();
        }
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
                    // This will cache the URL in StreamResolver
                    StreamResolver.resolveStreamUrl(appContext, videoId);
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
            int decodeW = highQuality ? targetWidth : Math.max(targetWidth, 320);
            int decodeH = highQuality ? targetHeight : Math.max(targetHeight, 320);
            Glide.with(target)
                .load(ThumbnailUrls.atSize(safeUrl, Math.max(decodeW, decodeH)))
                .transform(SHARED_YT_CROP)
                .format(highQuality ? DecodeFormat.PREFER_ARGB_8888 : DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .priority(priority)
                .onlyRetrieveFromCache(offlineOnly)
                .override(decodeW, decodeH)
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                .into(target);
        }
    }

    private boolean isInternetAvailable() {
        return isAdded() && cachedHasValidatedInternet(requireContext());
    }

    /** True for YouTube Music radio/mix playlists. Genuine radios/mixes ("Mixes para ti", "My Mix",
     *  song radios, Replay/Archive mixes, etc.) start with "RD" and must be loaded via the InnerTube
     *  watch endpoint (cookie) — the playlist endpoint returns empty for them.
     *  <p>EXCEPTION — {@code RDCLAK5uy_…} are YT Music AUTO-GENERATED PLAYLISTS, not radios: they
     *  have a fixed tracklist and a single static cover, appear as a plain single-image card in the
     *  home/library carousels (never a 3-circle radio card), and are browse-readable. Treating them
     *  as radios forced the header into the radio composite and diverted loading to /next (the
     *  "single-image playlist opens as a 3-circle radio" bug). Excluding them here routes them down
     *  the normal playlist/browse path AND makes the header render the single rounded HD cover. */
    private static boolean isRadioOrMixPlaylistId(@Nullable String playlistId) {
        // Predicado centralizado (RD… && !RDCLAK…) para que fila, sheet y header coincidan siempre.
        return RadioArt.isRadioId(playlistId);
    }

    /** YT Music auto-generated PLAYLIST ids ({@code RDCLAK5uy_…}): static cover + fixed tracklist,
     *  rendered as single-image cards everywhere. Not a radio despite the leading "RD". */
    private static boolean isAutoGeneratedPlaylistId(@Nullable String playlistId) {
        return playlistId != null && playlistId.startsWith("RDCLAK");
    }

    /** True for an album/single opened from an artist page. These carry an album BROWSE id ("MPRE…"),
     *  which the OAuth Data API can't read (returns empty) — they must be resolved through the
     *  InnerTube browse endpoint with the web cookie instead. */
    private static boolean isAlbumBrowseId(@Nullable String playlistId) {
        return playlistId != null && playlistId.startsWith("MPRE");
    }

    private void refreshPlaylistMeta(@NonNull String playlistId, @NonNull String accessToken) {
        if (playlistId.isEmpty()
                || accessToken.isEmpty()
                || isLikedPlaylistContext(playlistId)
                || isFavoritesPlaylistContext(playlistId)
                || isAlbumBrowseId(playlistId)) {
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

        // Local files — load from device MediaStore cache. A LOCAL_ALBUM:: id shows just that album.
        if (localFilesContext) {
            playlistTracksLoadMoreInFlight = false;
            playlistTracksCanLoadMore = false;
            if (!isAdded()) return;
            boolean albumContext = LocalFilesStore.isLocalAlbumId(playlistId);
            if (LocalFilesStore.getCachedFiles(requireContext()).isEmpty()
                    || LocalFilesStore.isCacheStale(requireContext())) {
                LocalFilesStore.cacheFiles(requireContext(), LocalFilesStore.scanLocalFiles(requireContext()));
            }
            List<LocalFilesStore.LocalTrack> localTracks = albumContext
                    ? LocalFilesStore.getTracksForAlbum(requireContext(), LocalFilesStore.albumNameFromId(playlistId))
                    : LocalFilesStore.getCachedFiles(requireContext());
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

        // Offload cache read + JSON parse + sanitize to background to avoid blocking the UI.
        // The result is posted back to the main thread for rendering and network decisions.
        final Context bgCtx = requireContext().getApplicationContext();
        final String bgPlaylistId = playlistId;
        final String bgAccessToken = effectiveAccessToken;
        final int bgRequestedLimit = requestedLimit;
        final boolean bgFav = favoritesContext;
        final boolean bgCustom = customContext;
        final boolean bgForceRefresh = forceRefresh;
        final boolean bgLoadMore = loadMore;
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        trackStateLookupExecutor.execute(() -> {
            final List<PlaylistTrack> bgCachedTracks;
            try {
                bgCachedTracks = sanitizeTracksForPlaylist(bgCtx, bgPlaylistId,
                        loadCachedTracksInternal(bgCtx, bgPlaylistId, true));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    continueBindAfterCacheLoad(bgPlaylistId, bgAccessToken, bgRequestedLimit,
                            bgFav, bgCustom, bgForceRefresh, bgLoadMore, new ArrayList<>());
                });
                return;
            }
            mainHandler.post(() -> {
                if (!isAdded()) return;
                continueBindAfterCacheLoad(bgPlaylistId, bgAccessToken, bgRequestedLimit,
                        bgFav, bgCustom, bgForceRefresh, bgLoadMore, bgCachedTracks);
            });
        });
    }

    private void continueBindAfterCacheLoad(
            @NonNull String playlistId,
            @NonNull String effectiveAccessToken,
            int requestedLimit,
            boolean favoritesContext,
            boolean customContext,
            boolean forceRefresh,
            boolean loadMore,
            @NonNull List<PlaylistTrack> cachedTracks
    ) {
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
            if (!forceRefresh && !loadMore) {
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = !hasCompleteCache;
                boolean freshOnly = canRequestRemote
                        && enterRevalidateDue(playlistId)
                        && hasValidatedInternet(requireContext());
                if (freshOnly) {
                    // Refresh INVISIBLE: la revalidación va a disparar, así que NO se pinta la
                    // caché (el usuario vería cómo las canciones se reemplazan al llegar la
                    // versión fresca — los mixes de YT cambian enteros entre visitas). El
                    // skeleton sigue en pantalla y se pinta directamente la lista final; la
                    // caché queda de respaldo por si la red falla o tarda demasiado.
                    pendingEnterCachedFallback = new ArrayList<>(cachedTracks);
                    pendingEnterFallbackPlaylistId = playlistId;
                    mainHandler.removeCallbacks(enterFreshFallbackRunnable);
                    mainHandler.postDelayed(enterFreshFallbackRunnable, ENTER_FRESH_WAIT_MAX_MS);
                } else {
                    renderTracks(cachedTracks, playlistId, true);
                }
                // Silently re-fetch from YouTube so changes made in YT Music (added/removed/
                // reordered songs) appear without a manual pull-to-refresh.
                maybeRevalidateOnEnter(playlistId, effectiveAccessToken);
                return;
            }
            if (!forceRefresh) {
                renderTracks(cachedTracks, playlistId, true);
            }
        }

        // Albums (artist-page "MPRE…" browse ids) resolve through InnerTube with the web cookie, not
        // the OAuth playlist endpoint — so they load even when no OAuth token is available. Handle
        // them before the token gate below (which would otherwise leave the album page empty).
        if (isAlbumBrowseId(playlistId)) {
            if (!hasValidatedInternet(requireContext())) {
                playlistTracksLoadMoreInFlight = false;
                if (cachedTracks.isEmpty()) {
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                            "No se pudo cargar el álbum. Inténtalo más tarde.");
                }
                return;
            }
            notifyHeaderChanged();
            String albumCookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
            if (albumCookie == null) albumCookie = "";
            youTubeMusicService.fetchAlbumTracks(albumCookie.trim(), playlistId, new YouTubeMusicService.MixTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> tracks) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    playlistTracksCanLoadMore = false;
                    List<PlaylistTrack> mapped = new ArrayList<>();
                    for (YouTubeMusicService.TrackResult t : tracks) {
                        if (TextUtils.isEmpty(t.videoId)) continue;
                        // Album pages omit per-row artist/cover (they're in the header) and expose
                        // the duration in a fixedColumn. Fall back to the album's artist + cover so
                        // the row isn't blank, and use the real per-track duration.
                        // The playlist-subtitle fallback is a baked string ("Artista • Álbum • 2020"),
                        // so reduce either source to just the artist name.
                        String rowArtist = SongSubtitle.artistOnly(t.subtitle, t.title);
                        if (TextUtils.isEmpty(rowArtist)) {
                            rowArtist = SongSubtitle.artistOnly(currentPlaylistSubtitle);
                        }
                        String rowThumb = (t.thumbnailUrl == null || t.thumbnailUrl.isEmpty())
                                ? (currentPlaylistThumbnail == null ? "" : currentPlaylistThumbnail)
                                : t.thumbnailUrl;
                        String rowDuration = TextUtils.isEmpty(t.duration) ? "--:--" : t.duration;
                        mapped.add(new PlaylistTrack(
                                t.videoId,
                                t.title == null ? "" : t.title,
                                rowArtist,
                                rowDuration,
                                rowThumb
                        ));
                    }
                    if (mapped.isEmpty()) {
                        if (cachedTracks.isEmpty()) {
                            showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                                    "No se pudo cargar el álbum. Inténtalo más tarde.");
                        }
                        return;
                    }
                    hideNoConnectionState();
                    cacheTracks(playlistId, mapped, true);
                    renderTracks(mapped, playlistId, false);
                }

                @Override
                public void onError(@NonNull String error) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    if (!cachedTracks.isEmpty()) {
                        renderTracks(cachedTracks, playlistId, true);
                        return;
                    }
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                            "No se pudo cargar el álbum. Inténtalo más tarde.");
                }
            });
            return;
        }

        // YT auto-generated playlists (RDCLAK…): fixed-tracklist, single-cover lists the OAuth Data
        // API can't read. Resolve them through InnerTube browse (VL+id + the card's params) with the
        // web cookie — the same bypass MPRE albums use — so they load even without an OAuth token.
        // Handled before the token gate below (which would otherwise leave them empty). The header
        // already renders them as a single rounded HD cover (they're excluded from isRadioContext).
        if (isAutoGeneratedPlaylistId(playlistId)) {
            if (!hasValidatedInternet(requireContext())) {
                playlistTracksLoadMoreInFlight = false;
                if (cachedTracks.isEmpty()) {
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                            "No se pudo cargar la playlist. Inténtalo más tarde.");
                }
                return;
            }
            notifyHeaderChanged();
            String genCookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
            if (genCookie == null) genCookie = "";
            youTubeMusicService.fetchPlaylistTracksViaBrowse(genCookie.trim(), playlistId, currentPlaylistParams,
                    new YouTubeMusicService.PlaylistTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistTrackResult> browseTracks) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    playlistTracksCanLoadMore = false;
                    if (browseTracks.isEmpty()) {
                        if (!cachedTracks.isEmpty()) {
                            renderTracks(cachedTracks, playlistId, true);
                            return;
                        }
                        renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
                        return;
                    }
                    hideNoConnectionState();
                    List<PlaylistTrack> raw = mergeTrackMetadataFromCache(playlistId, mapTracks(browseTracks));
                    cacheTracks(playlistId, raw, true);
                    renderTracks(sanitizeTracksForPlaylist(playlistId, raw), playlistId, false);
                }

                @Override
                public void onError(@NonNull String error) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    if (!cachedTracks.isEmpty()) {
                        renderTracks(cachedTracks, playlistId, true);
                        return;
                    }
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                            "No se pudo cargar la playlist. Inténtalo más tarde.");
                }
            });
            return;
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

        // Radio/Mix playlists (any "RD" id: RDAMVM, RDEM, RDTMAK, RDCLAK "Mixes para ti", …)
        // use the InnerTube watch endpoint with the web cookie. Regular playlists (PL/VL/OLAK…)
        // never start with "RD", so this only diverts genuine radios/mixes — which otherwise hit
        // the playlist endpoint and come back EMPTY.
        if (isRadioOrMixPlaylistId(playlistId)) {
            // Check no-internet before attempting network fetch
            if (!hasValidatedInternet(requireContext())) {
                playlistTracksLoadMoreInFlight = false;
                showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore,
                        "No se pudo cargar la radio. Inténtalo más tarde.");
                return;
            }
            String cookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
            if (cookie == null) cookie = "";
            final String mixCookie = cookie.trim();
            // Forward the home card's params token: YT-generated personal mixes (Replay/Archive/Recap)
            // return an empty /next panel under the generic "wAEB" param and only fill with their own
            // token. Empty currentPlaylistParams (song radios opened from a track) keeps "wAEB".
            youTubeMusicService.fetchMixTracks(mixCookie, playlistId, currentPlaylistParams, new YouTubeMusicService.MixTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> tracks) {
                    if (!isAdded()) return;
                    playlistTracksLoadMoreInFlight = false;
                    playlistTracksCanLoadMore = false;
                    List<PlaylistTrack> mapped = new ArrayList<>();
                    for (YouTubeMusicService.TrackResult t : tracks) {
                        if (TextUtils.isEmpty(t.videoId)) continue;
                        // Legacy caches may still carry artist\tduration in the subtitle; live
                        // fetches now deliver the duration in the dedicated t.duration field.
                        String rawSub = t.subtitle == null ? "" : t.subtitle;
                        String artist = rawSub;
                        String duration = TextUtils.isEmpty(t.duration) ? "--:--" : t.duration;
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
                        // /next vino vacío: los mixes personales de YT (Replay/Archivo/Descubrir) sólo
                        // resuelven por el endpoint BROWSE con el token del card. Reintenta por ahí.
                        loadMixViaBrowseFallback(playlistId, mixCookie, effectiveAccessToken, forceRefresh,
                                loadMore, "No se pudo cargar la radio. Inténtalo más tarde.");
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
                                    SongSubtitle.artistOnly(currentPlaylistSubtitle, currentPlaylistTitle),
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
                    // /next falló: intenta el endpoint BROWSE con el token del card (mixes personales).
                    loadMixViaBrowseFallback(playlistId, mixCookie, effectiveAccessToken, forceRefresh,
                            loadMore, "No se pudo cargar la radio. Inténtalo más tarde.");
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

                // The OAuth Data API returns 0 items WITHOUT an error for YTM server-generated
                // playlists (RECAP / auto-mixes) it can't read. Before giving up (and rendering an
                // empty list), retry through the InnerTube browse endpoint with the web cookie —
                // the same bypass MPRE albums use. Only on the initial fetch of a normal playlist id.
                if (tracks.isEmpty() && !loadMore && shouldTryBrowsePlaylistFallback(playlistId)) {
                    tryBrowsePlaylistFallback(playlistId);
                    return;
                }

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
            // Local files never live in the offline store — counting them as "not downloaded"
            // kept the playlist permanently incomplete (same skip as the worker and the library).
            if (LocalFilesStore.isLocalVideoId(track.videoId)) {
                continue;
            }

            eligibleCount++;
            // hasOfflineAudio fallback: hasValidatedOfflineAudio can transiently fail
            // (MediaMetadataRetriever) on a file that IS fully downloaded. Without the fallback
            // this validator flipped the persisted complete flag back to false right after the
            // worker set it — the "check lights then goes out" bug. Mirrors the library's
            // computePlaylistOfflineProgressFromFiles.
            if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)
                    || OfflineAudioStore.hasOfflineAudio(appContext, track.videoId)) {
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

    /**
     * Records THIS playlist into the downloaded-playlists index (name + cover) so the "Descargas"
     * library view can list it later. Called whenever a download is enqueued from this screen —
     * the one moment a playlist gains downloads AND its real display name/cover are on screen. This
     * covers every source type (album MPRE…, radio RD…, YouTube playlist, custom list) uniformly.
     */
    private void recordDownloadedPlaylistMeta() {
        if (!isAdded() || TextUtils.isEmpty(currentPlaylistId)) return;
        String name = PlaylistNameOverrideStore.getDisplayName(requireContext(), currentPlaylistId);
        if (TextUtils.isEmpty(name)) name = headerPlaylistTitle;
        if (TextUtils.isEmpty(name)) name = currentPlaylistTitle;
        PlaylistMetaStore.save(requireContext().getApplicationContext(),
                currentPlaylistId, name, headerPlaylistThumbnail);
    }

    private void startOfflinePlaylistDownload(boolean userInitiated) {
        if (!isAdded()) {
            return;
        }
        // A fresh user-initiated download resets the auto-retry budget for this playlist.
        if (userInitiated && !TextUtils.isEmpty(currentPlaylistId)) {
            offlineAutoRetryCountByPlaylist.remove(currentPlaylistId);
        }
        if (currentTracks.isEmpty()) {
            if (userInitiated) {

            }
            return;
        }
        recordDownloadedPlaylistMeta();

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
                lastActiveOfflineUniqueName = uniqueName;

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

            // Unfiltered view of the whole chain: our BLOCKED jobs waiting behind ANOTHER
            // playlist's RUNNING job are healthy, not wedged — see the blocked-only reset below.
            final boolean anyChainWorkAlive = hasAnyAliveChainWork(workInfos);

            // Scope to the current playlist's work only — the unique-work LiveData is shared
            // across playlists, so without this filter another playlist's progress/terminal
            // state would bleed into this screen.
            workInfos = filterWorkInfosForCurrentPlaylist(workInfos);

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
                if (state == WorkInfo.State.RUNNING
                        || state == WorkInfo.State.ENQUEUED
                        || state == WorkInfo.State.BLOCKED) {
                    // Remember jobs seen alive during THIS observation: their later terminal
                    // event is fresh. Historical SUCCEEDED infos replayed by the LiveData on
                    // (re)observe must not re-persist an old run's completion.
                    offlineWorkSeenActiveIds.add(candidate.getId());
                }
                if (state == WorkInfo.State.SUCCEEDED
                        || state == WorkInfo.State.FAILED
                        || state == WorkInfo.State.CANCELLED) {
                    terminalInfo = candidate;
                }
            }

            if (!runningInfos.isEmpty()) {
                DownloadProgress dp = DownloadProgress.mergeRunning(runningInfos);
                int done = dp.done;
                int total = dp.total;
                int downloaded = dp.downloaded;
                String currentId = dp.currentId;
                String dlPlaylistTitle = dp.playlistTitle;
                Map<String, Float> progressByTrackId = new HashMap<>(dp.perTrackFraction);
                String[] activeIds = dp.getActiveIds();

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
                if (safeDownloaded == 0 && safeTotal > 0 && trackAdapter != null) {
                    int cachedOfflineCount = 0;
                    for (PlaylistTrack t : currentTracks) {
                        if (Boolean.TRUE.equals(trackAdapter.offlineAvailabilityCache.get(t.videoId))) {
                            cachedOfflineCount++;
                        }
                    }
                    safeDownloaded = cachedOfflineCount;
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
                    // Transient connectivity blip (isInternetAvailable is stricter than the
                    // worker's network constraint). Do NOT tear down the per-track progress we
                    // just set above — the worker hasn't stopped. Only show a waiting header;
                    // the next progress emission re-applies fresh values when the network returns.
                    headerPlaylistInfo = "Descargando playlist • esperando conexión";
                    notifyHeaderChanged();
                    return;
                }

                String status = "Descargando playlist • " + safeDownloaded + "/" + safeTotal;
                if (done > safeDownloaded) {
                    status += " • " + done + " revisadas";
                }
                headerPlaylistInfo = status;
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

                if (enqueuedCount == 0 && blockedCount > 0 && !anyChainWorkAlive) {
                    // Only when the WHOLE chain is dead: blocked-only within our playlist while
                    // another playlist's job runs is normal APPEND ordering, and resetting here
                    // used to cancel that other playlist's active download.
                    Log.w(TAG_OFFLINE_DOWNLOAD,
                            "queue:block_detected blocked=" + blockedCount + " uniqueName=" + uniqueName + " -> resetting");

                    resetBlockedOfflineQueues();

                    offlineDownloadQueued = false;
                    if (currentMeta != null) {
                        headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
                    }
                    notifyHeaderChanged();
                    return;
                }

                if (!isInternetAvailable()) {
                    notifyHeaderChanged();
                    return;
                }

                int safeTotal = Math.max(0, currentTracks.size());
                int cachedOfflineCount = 0;
                if (trackAdapter != null) {
                    for (PlaylistTrack t : currentTracks) {
                        if (Boolean.TRUE.equals(trackAdapter.offlineAvailabilityCache.get(t.videoId))) {
                            cachedOfflineCount++;
                        }
                    }
                }
                headerPlaylistInfo = "Descargando playlist (En cola) • " + cachedOfflineCount + "/" + safeTotal;
                notifyHeaderChanged();
                return;
            }

            offlineDownloadQueued = false;

            if (terminalInfo == null) {
                setOfflineDownloadVisualState(false, "");
                if (currentMeta != null) {
                    headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
                }
                notifyHeaderChanged();
                return;
            }

            if (terminalInfo.getState() == WorkInfo.State.SUCCEEDED) {
                setOfflineDownloadVisualState(false, "");
                Data output = terminalInfo.getOutputData();
                int downloadedOut = output.getInt(OfflinePlaylistDownloadWorker.OUTPUT_DOWNLOADED, 0);
                int totalOut = output.getInt(OfflinePlaylistDownloadWorker.OUTPUT_TOTAL, currentTracks.size());
                String reason = output.getString(OfflinePlaylistDownloadWorker.OUTPUT_REASON);

                // A successful run clears the auto-retry budget for this playlist.
                if (!TextUtils.isEmpty(currentPlaylistId)) {
                    offlineAutoRetryCountByPlaylist.remove(currentPlaylistId);
                }
                // Mark complete immediately from the worker's authoritative count so the filled
                // check lights without waiting for the async disk scan. maybeUpdateOfflineReadyState()
                // below still runs as a confirmation pass that can flip it back if files are bad.
                // Guards: (a) manual single-track jobs report total=1/downloaded=1 — that must not
                // mark the WHOLE playlist complete; (b) only terminals we saw alive this session
                // count — replayed historical SUCCEEDED infos would resurrect deleted downloads.
                boolean manualJob = terminalInfo.getTags()
                        .contains(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME);
                boolean freshTerminal = offlineWorkSeenActiveIds.remove(terminalInfo.getId());
                if (!manualJob && freshTerminal
                        && OfflinePlaylistDownloadWorker.OUTPUT_REASON_NONE.equals(reason)
                        && totalOut > 0 && downloadedOut >= totalOut) {
                    persistOfflineCompleteStateForCurrentPlaylist(true);
                }
                // Re-notify the library tile now that files actually landed (the enqueue-time
                // notify fired before downloads finished, so the tile read stale state).
                notifyMusicPlayerOfflineChanged();

                if (!offlineObserverNotifyTerminalToasts) {
                    stopObservingOfflineDownload();
                    refreshVisibleTrackRows();
                    maybeUpdateOfflineReadyState();
                    return;
                }

                // Notify ONCE that the download finished — the only per-track feedback now that the
                // rows no longer carry a persistent downloaded indicator. Only for user-initiated
                // downloads (offlineObserverNotifyTerminalToasts); freshTerminal blocks replayed
                // historical SUCCEEDED infos from re-toasting.
                if (freshTerminal && downloadedOut > 0
                        && OfflinePlaylistDownloadWorker.OUTPUT_REASON_NONE.equals(reason)) {
                    AppSnackbar.show(getActivity(),
                            (manualJob || downloadedOut == 1)
                                    ? "Descarga completada"
                                    : "Descarga completada • " + downloadedOut + " canciones");
                }

                if (currentMeta != null) {
                    headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
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
                    final String retryPlaylistId = currentPlaylistId;
                    int attempts = offlineAutoRetryCountByPlaylist.containsKey(retryPlaylistId)
                            ? offlineAutoRetryCountByPlaylist.get(retryPlaylistId) : 0;
                    if (attempts < MAX_OFFLINE_AUTO_RETRY) {
                        offlineAutoRetryCountByPlaylist.put(retryPlaylistId, attempts + 1);
                        Log.w(TAG_OFFLINE_DOWNLOAD, "terminal_failed: auto-retry "
                                + (attempts + 1) + "/" + MAX_OFFLINE_AUTO_RETRY
                                + " for playlist=" + retryPlaylistId);
                        // Delayed + guarded re-enqueue (no synchronous loop): a deterministically
                        // failing worker is now capped instead of spinning a battery-draining loop.
                        if (offlineAutoRetryRunnable != null) {
                            mainHandler.removeCallbacks(offlineAutoRetryRunnable);
                        }
                        offlineAutoRetryRunnable = () -> {
                            if (!isAdded() || isHidden()) return;
                            if (!TextUtils.equals(currentPlaylistId, retryPlaylistId)) return;
                            if (!isCurrentPlaylistOfflineAutoEnabled() || !isInternetAvailable()) return;
                            startOfflinePlaylistDownload(false);
                        };
                        mainHandler.postDelayed(offlineAutoRetryRunnable, OFFLINE_AUTO_RETRY_DELAY_MS);
                        return;
                    }
                    Log.w(TAG_OFFLINE_DOWNLOAD,
                            "terminal_failed: auto-retry budget exhausted for playlist=" + retryPlaylistId);
                }

                if (currentMeta != null) {
                    headerPlaylistInfo = buildPlaylistInfoLine(currentMeta, currentTracks.size());
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
        Context ctx = getContext();
        if (ctx == null) return new ArrayList<>(source);
        return sanitizeTracksForPlaylist(ctx, playlistId, source);
    }

    /** Thread-safe overload that accepts an explicit Context for use off the UI thread. */
    @NonNull
    private List<PlaylistTrack> sanitizeTracksForPlaylist(
            @NonNull Context ctx,
            @NonNull String playlistId,
            @NonNull List<PlaylistTrack> source
    ) {
        if (isFavoritesPlaylistContext(playlistId)) {
            List<FavoritesPlaylistStore.FavoriteTrack> favorites = FavoritesPlaylistStore.loadFavorites(ctx);
            List<PlaylistTrack> mapped = new ArrayList<>(favorites.size());
            for (FavoritesPlaylistStore.FavoriteTrack track : favorites) {
                mapped.add(new PlaylistTrack(track.videoId, track.title, track.artist, track.duration, track.imageUrl));
            }
            java.util.Map<String, PlaylistOverrideStore.Override> favOverrides =
                    PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId);
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

        if (isCustomPlaylistContext(playlistId)) {
            String name = playlistId.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            List<FavoritesPlaylistStore.FavoriteTrack> custom = CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            List<PlaylistTrack> mapped = new ArrayList<>(custom.size());
            for (FavoritesPlaylistStore.FavoriteTrack track : custom) {
                mapped.add(new PlaylistTrack(track.videoId, track.title, track.artist, track.duration, track.imageUrl));
            }
            java.util.Map<String, PlaylistOverrideStore.Override> customOverrides =
                    PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId);
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
        if (!playlistId.isEmpty()) {
            java.util.Map<String, PlaylistOverrideStore.Override> overridesMap =
                    PlaylistOverrideStore.INSTANCE.getOverrides(ctx, playlistId);
            
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

            // For "Música que te gustó", drop tracks the user un-liked locally (tombstoned) —
            // otherwise every server refetch would resurrect them in the list while the like
            // icon (which honors tombstones) shows them as not liked.
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
                Set<String> likedTombstones = FavoritesPlaylistStore.getLikedTombstones(ctx);
                if (!likedTombstones.isEmpty()) {
                    overridden.removeIf(t ->
                            !TextUtils.isEmpty(t.videoId) && likedTombstones.contains(t.videoId));
                }
            }

            // Merge locally-mirrored tracks that were added via the save-to-playlist sheet
            List<FavoritesPlaylistStore.FavoriteTrack> mirrorTracks =
                    CustomPlaylistsStore.INSTANCE.getYtMirrorTracks(ctx, playlistId);
            if (!mirrorTracks.isEmpty()) {
                Set<String> existingIds = new java.util.HashSet<>();
                for (PlaylistTrack t : overridden) {
                    if (!TextUtils.isEmpty(t.videoId)) existingIds.add(t.videoId);
                }
                // "Música que te gustó" es newest-first en YT: un like recién dado (que aún no
                // está en la lista del servidor) debe verse AL PRINCIPIO, no anexado al final.
                // El mirror ya guarda newest-first, así que insertarlo en orden en la cabeza
                // preserva ese orden. El resto de playlists YT anexan al final (como YT).
                boolean likedNewestFirst = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId);
                int insertAt = 0;
                for (FavoritesPlaylistStore.FavoriteTrack mt : mirrorTracks) {
                    if (!TextUtils.isEmpty(mt.videoId) && !existingIds.contains(mt.videoId)) {
                        PlaylistTrack merged = new PlaylistTrack(mt.videoId, mt.title, mt.artist, mt.duration, mt.imageUrl);
                        if (likedNewestFirst) overridden.add(insertAt++, merged);
                        else overridden.add(merged);
                    }
                }
            }

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
        return LocalFilesStore.PLAYLIST_ID.equals(playlistId)
                || LocalFilesStore.isLocalAlbumId(playlistId);
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
        } else if (isLikedPlaylistContext(currentPlaylistId)) {
            // Like/unlike from the player must add/remove rows in a visible "Música que te
            // gustó" list, not just repaint an existing row. Rebuild from the cached server
            // list + overrides + mirror − tombstones. The playback queue is deliberately left
            // untouched (un-liking the playing song must not cut playback).
            List<PlaylistTrack> cached = loadCachedTracksInternal(currentPlaylistId, true);
            List<PlaylistTrack> refreshed = sanitizeTracksForPlaylist(currentPlaylistId, cached);
            renderTracks(refreshed, currentPlaylistId, true);
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
        // Llegó un render real para esta playlist: el respaldo del refresh invisible ya no hace
        // falta (si este render ES la versión fresca, pintar la caché después lo pisaría).
        if (TextUtils.equals(playlistId, pendingEnterFallbackPlaylistId)) {
            clearEnterCachedFallback();
        }
        String selectedVideoId = getCurrentTrackVideoId();
        // Gate: when the local-music setting is OFF, local tracks ("local_*") that the user
        // mixed into OTHER playlists must not surface in the display list, queue, now-playing,
        // or counts. The dedicated Local Files playlist is exempt (it is gated by isEnabled at
        // the library level and legitimately contains only local tracks).
        boolean hideMixedLocalTracks = isAdded()
                && !isLocalFilesContext(playlistId)
                && !LocalFilesStore.isEnabled(requireContext());
        originalTracks.clear();
        for (PlaylistTrack t : tracks) {
            if (hideMixedLocalTracks && t != null && LocalFilesStore.isLocalVideoId(t.videoId)) {
                continue;
            }
            if (!isLikelyShort(t)) {
                originalTracks.add(t);
            }
        }

        currentTracks.clear();
        boolean offlineModeActive = isAdded() && requireContext()
                .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE)
                .getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false);
        if (offlineModeActive || downloadedOnlyMode) {
            // Offload disk I/O to background thread to avoid blocking the UI
            final List<PlaylistTrack> snapshot = new ArrayList<>(originalTracks);
            final Context offlineCtx = requireContext().getApplicationContext();
            // En la vista Descargas solo cuentan los archivos descargados por la app: los tracks
            // locales quedan fuera para que el número coincida con el "N descargas" de la row.
            final boolean allowLocalTracks = offlineModeActive;
            trackStateLookupExecutor.execute(() -> {
                List<PlaylistTrack> filtered = new ArrayList<>();
                for (PlaylistTrack t : snapshot) {
                    if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                    String vid = t.videoId.trim();
                    if ((allowLocalTracks && LocalFilesStore.isLocalVideoId(vid))
                            || OfflineAudioStore.hasOfflineAudio(offlineCtx, vid)) {
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

        // Auto-generated playlists (RDCLAK…) are single-static-cover lists shown as one image in
        // their home/library card — they must NOT build a 2x2 track grid (that was the "single-image
        // playlist opens looking different" half of the bug). Keep headerGridUrls empty + preserve the
        // card cover thumbnail so the header lands on the single rounded HD-cover branch.
        // forceSingleCoverArt (recomendadas/Recaps/recap-in-recientes) shares this exact treatment: no
        // synthesized grid, clone the card cover.
        // Una portada OFICIAL de YT registrada (OfficialCoverStore) manda igual que el art hint:
        // el header muestra ESA imagen y nunca sintetiza el 2x2.
        String officialCover = isAdded() ? OfficialCoverStore.get(requireContext(), playlistId) : null;
        if (!TextUtils.isEmpty(officialCover)) {
            headerPlaylistThumbnail = officialCover.trim();
        }
        boolean autoGeneratedPlaylist = isAutoGeneratedPlaylistId(playlistId) || forceSingleCoverArt
                || !TextUtils.isEmpty(officialCover);

        // For YouTube playlists, use persisted grid URLs so the 2x2 never changes
        List<String> gridUrls = null;
        if (isYouTubePlaylist && !autoGeneratedPlaylist && isAdded()) {
            gridUrls = loadPersistedGridUrls(playlistId);
        }

        if (!autoGeneratedPlaylist && (gridUrls == null || gridUrls.isEmpty())) {
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
            if (isYouTubePlaylist && !gridUrls.isEmpty() && isAdded()) {
                persistGridUrls(playlistId, gridUrls);
            }
        }
        headerGridUrls = (!autoGeneratedPlaylist && gridUrls != null && gridUrls.size() >= 4)
                ? gridUrls : new ArrayList<>();
        if (!autoGeneratedPlaylist && currentTracks.size() < 4) {
            for (PlaylistTrack track : currentTracks) {
                if (track != null && !TextUtils.isEmpty(track.imageUrl)) {
                    headerPlaylistThumbnail = track.imageUrl.trim();
                    break;
                }
            }
        }

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

        // Safety timeout: reveal after 1.5s even if preloads haven't all completed
        Handler uiHandler = new Handler(Looper.getMainLooper());
        uiHandler.postDelayed(() -> {
            if (isAdded() && awaitingInitialPlaylistRender) {
                revealPlaylistContentIfNeeded(true);
            }
        }, 1500L);

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

        // Preload track thumbnails with SHARED_YT_CROP + the SAME sized URL + size as
        // loadTrackArt so the Glide cache key matches and the row bind is a pure cache hit
        // (an unsized preload would warm entries the sized binds can never hit = double fetch).
        int trackPx = trackArtSizePx(ctx);
        for (String url : trackUrls) {
            Glide.with(ctx)
                    .load(sizedTrackArtUrl(ctx, url))
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .priority(com.bumptech.glide.Priority.HIGH)
                    .override(trackPx, trackPx)
                    .listener(revealListener)
                    .preload();
        }
    }

    /** True si el player actual está reproduciendo una canción DE ESTA playlist. */
    private boolean isPlayingFromThisPlaylist() {
        SongPlayerFragment player = findSongPlayerFragment();
        if (player == null || !player.isAdded()) return false;
        String vid = player.externalGetCurrentVideoId();
        if (TextUtils.isEmpty(vid)) return false;
        for (PlaylistTrack t : currentTracks) {
            if (vid.equals(t.videoId)) return true;
        }
        return false;
    }

    /** Estado "activo" del botón aleatorio del header: ESPEJO del aleatorio del player (fuente
     *  de verdad cuando existe) Y sonando algo de esta playlist. Con otra playlist sonando, el
     *  botón vuelve a su estado neutro. */
    private boolean isShuffleActiveForThisPlaylist() {
        SongPlayerFragment player = findSongPlayerFragment();
        boolean shuffleOn = (player != null && player.isAdded())
                ? player.externalIsShuffleEnabled()
                : shuffleModeEnabled;
        return shuffleOn && isPlayingFromThisPlaylist();
    }

    /** Pinta el botón aleatorio del header según su estado: activo = fondo blanco y glifo negro;
     *  neutro = fondo semitransparente y glifo blanco. */
    private void applyShuffleButtonStyle(@Nullable ImageButton btn) {
        if (btn == null) return;
        if (isShuffleActiveForThisPlaylist()) {
            btn.setBackgroundResource(R.drawable.bg_playlist_action_light);
            btn.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF000000));
        } else {
            btn.setBackgroundResource(R.drawable.bg_playlist_action_dark);
            btn.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
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
        // Refleja el estado en el botón aleatorio del header (activo solo si además está sonando
        // algo de ESTA playlist).
        applyShuffleButtonStyle(headerShuffleButton);
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
        injectOriginalQueueOrderIfShuffled(player);
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
            return new PlaybackHistoryStore.Snapshot(new ArrayList<>(), 0, 0, 1, false, 0L, null);
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
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
            // The like icon memoizes this key's id set — keep it coherent.
            FavoritesPlaylistStore.invalidateLikedMusicCache();
        }
    }

    @NonNull
    private List<PlaylistTrack> loadCachedTracksInternal(@NonNull String playlistId, boolean allowStale) {
        Context ctx = getContext();
        if (ctx == null || playlistId.isEmpty()) {
            return new ArrayList<>();
        }
        return loadCachedTracksInternal(ctx, playlistId, allowStale);
    }

    /** Thread-safe overload that accepts an explicit Context for use off the UI thread. */
    @NonNull
    private List<PlaylistTrack> loadCachedTracksInternal(@NonNull Context ctx, @NonNull String playlistId, boolean allowStale) {
        List<PlaylistTrack> result = new ArrayList<>();
        if (playlistId.isEmpty()) {
            return result;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE);
        long updatedAt = prefs.getLong(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId, 0L);
        if (updatedAt <= 0L && !allowStale) {
            return result;
        }
        if (!allowStale && updatedAt > 0L && (System.currentTimeMillis() - updatedAt) > TRACKS_CACHE_TTL_MS) {
            return result;
        }

        String raw = prefs.getString(PREF_TRACKS_DATA_PREFIX + playlistId, "");
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
        // NEVER persist an empty result — least of all as "complete". A YT-generated playlist the
        // OAuth Data API can't read (RECAP / auto-mixes) returns 0 items WITHOUT an error, and a
        // complete-empty cache would then serve that empty list forever. Skipping the write entirely
        // keeps any earlier good cache and lets every open re-attempt the network + browse fallback.
        if (tracks.isEmpty()) {
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
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
                // The like icon memoizes this key's id set — keep it coherent.
                FavoritesPlaylistStore.invalidateLikedMusicCache();
            }
        } catch (Exception e) {
            Log.w(TAG_OFFLINE_DOWNLOAD, "cacheTracks failed for " + playlistId, e);
        }
    }

    @NonNull
    private SharedPreferences getCachePrefs() {
        return requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE);
    }

    /** Fallback for RD-mix ids whose {@code /next} (watch) panel came back empty/errored: the YT
     *  personal mixes (Replay / Archivo / Descubrir) are readable via the BROWSE endpoint with the
     *  home card's params token (the same route recaps take). Renders the tracks, or the error state
     *  if browse also yields nothing (or there's no token / cookie). No re-loop back to /next. */
    private void loadMixViaBrowseFallback(@NonNull String playlistId, @NonNull String cookie,
            @NonNull String effectiveAccessToken, boolean forceRefresh, boolean loadMore,
            @NonNull String errorMessage) {
        if (!isAdded()) return;
        if (TextUtils.isEmpty(cookie) || TextUtils.isEmpty(currentPlaylistParams)) {
            showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore, errorMessage);
            return;
        }
        youTubeMusicService.fetchPlaylistTracksViaBrowse(cookie, playlistId, currentPlaylistParams,
                new YouTubeMusicService.PlaylistTracksCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistTrackResult> browseTracks) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = false;
                if (browseTracks.isEmpty()) {
                    showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore, errorMessage);
                    return;
                }
                hideNoConnectionState();
                List<PlaylistTrack> raw = mergeTrackMetadataFromCache(playlistId, mapTracks(browseTracks));
                cacheTracks(playlistId, raw, true);
                renderTracks(sanitizeTracksForPlaylist(playlistId, raw), playlistId, false);
            }

            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                showNoConnectionState(playlistId, effectiveAccessToken, forceRefresh, loadMore, errorMessage);
            }
        });
    }

    /** True for a plain OAuth YouTube playlist id — the only kind where an empty Data-API result
     *  warrants the InnerTube browse fallback (radios/albums/local/favorites/custom/liked have their
     *  own dedicated network paths and must not be re-routed through browse). */
    private boolean shouldTryBrowsePlaylistFallback(@NonNull String playlistId) {
        return !isRadioOrMixPlaylistId(playlistId)
                && !isAlbumBrowseId(playlistId)
                && !isFavoritesPlaylistContext(playlistId)
                && !isCustomPlaylistContext(playlistId)
                && !isLocalFilesContext(playlistId)
                && !YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId);
    }

    /** Retries a Data-API-empty playlist through the InnerTube browse endpoint (VL+id, web cookie),
     *  forwarding the home card's params token so YT-generated RECAP lists resolve. If browse still
     *  comes back empty and we have a params token, falls through to the /next (watch) endpoint —
     *  some generated recaps are ONLY readable there. On any final failure/empty it renders the empty
     *  state exactly like the plain empty case. */
    private void tryBrowsePlaylistFallback(@NonNull String playlistId) {
        if (!isAdded()) return;
        String cookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
        if (cookie == null) cookie = "";
        final String bgCookie = cookie.trim();
        if (TextUtils.isEmpty(bgCookie)) {
            renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
            return;
        }
        youTubeMusicService.fetchPlaylistTracksViaBrowse(bgCookie, playlistId, currentPlaylistParams,
                new YouTubeMusicService.PlaylistTracksCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistTrackResult> browseTracks) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = false;
                if (browseTracks.isEmpty()) {
                    // Browse returned nothing — a generated recap only /next can build. Try that with
                    // the card's params before giving up (needs a token; otherwise render empty).
                    if (!TextUtils.isEmpty(currentPlaylistParams)) {
                        tryNextMixFallback(playlistId, bgCookie);
                        return;
                    }
                    renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
                    return;
                }
                List<PlaylistTrack> raw = mergeTrackMetadataFromCache(playlistId, mapTracks(browseTracks));
                cacheTracks(playlistId, raw, true);
                renderTracks(sanitizeTracksForPlaylist(playlistId, raw), playlistId, false);
            }

            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                if (!TextUtils.isEmpty(currentPlaylistParams)) {
                    tryNextMixFallback(playlistId, bgCookie);
                    return;
                }
                renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
            }
        });
    }

    /** Last-resort loader for a YT-generated list that neither the OAuth Data API nor browse could
     *  read: the InnerTube /next (watch) endpoint with the card's params token. Maps the mix panel
     *  rows the same way the radio path does. Only invoked when a params token exists. */
    private void tryNextMixFallback(@NonNull String playlistId, @NonNull String cookie) {
        youTubeMusicService.fetchMixTracks(cookie, playlistId, currentPlaylistParams,
                new YouTubeMusicService.MixTracksCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> tracks) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                playlistTracksCanLoadMore = false;
                List<PlaylistTrack> mapped = new ArrayList<>();
                for (YouTubeMusicService.TrackResult t : tracks) {
                    if (TextUtils.isEmpty(t.videoId)) continue;
                    String rawSub = t.subtitle == null ? "" : t.subtitle;
                    String artist = rawSub;
                    String duration = TextUtils.isEmpty(t.duration) ? "--:--" : t.duration;
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
                    renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
                    return;
                }
                hideNoConnectionState();
                cacheTracks(playlistId, mapped, true);
                renderTracks(mapped, playlistId, false);
            }

            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) return;
                playlistTracksLoadMoreInFlight = false;
                renderTracks(sanitizeTracksForPlaylist(playlistId, new ArrayList<>()), playlistId, false);
            }
        });
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
            return result.isEmpty() ? null : result;
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
        Fragment fragment = getParentFragmentManager().findFragmentByTag(AppConstants.TAG_SONG_PLAYER);
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
        if (existingPlayer != null && existingPlayer.isAdded()) {
            existingPlayer.externalSetReturnTargetTag(TAG_PLAYLIST_DETAIL);
            existingPlayer.externalSetPlaylistContext(currentPlaylistId, currentPlaylistTitle);

            // Play in the mini-player — do NOT auto-open the full-screen player. If the tapped
            // track is already the loaded one, leave it playing; otherwise switch to it in place.
            if (!TextUtils.equals(selectedVideoId, existingPlayer.getLoadedVideoId())) {
                if (existingPlayer.externalMatchesQueue(ids)) {
                    existingPlayer.externalPlayTrackFromStart(queueIndex);
                } else {
                    existingPlayer.externalReplaceQueueFromStart(ids, titles, artists, durations, images, queueIndex, true);
                    injectOriginalQueueOrderIfShuffled(existingPlayer);
                }
            }

            ensureMiniPlayerShown();

            currentTrackIndex = position;
            miniPlaying = true;
            if (trackAdapter != null) {
                trackAdapter.setActiveIndex(position);
            }
            syncTrackStateFromPlayer();
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
        // Descargado-de-facto: la canción quedó COMPLETA en el exo_stream_cache al reproducirla
        // entera (cache-as-downloaded, por diseño). El sheet la muestra como "Descargado" igual
        // que una descarga explícita; el índice de SimpleCache vive en memoria, es barato.
        final boolean fullyCached = !hasOfflineAudio
            && !isLocalFilesPlaylist
            && !TextUtils.isEmpty(selectedTrack.videoId)
            && ExoMediaPlayer.isFullyCached(context, selectedTrack.videoId);
        final boolean offlineReady = hasOfflineAudio || fullyCached;

        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_track_options, null);
        dialog.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvBsTrackTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvBsTrackSubtitle);
        ImageView ivArt = view.findViewById(R.id.ivBsTrackArt);
        
        tvTitle.setText(TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title);
        tvSubtitle.setText(TextUtils.isEmpty(selectedTrack.normalizedSubtitle) ? "Artista" : selectedTrack.normalizedSubtitle);
        if (LocalFilesStore.isLocalVideoId(selectedTrack.videoId)) {
            // Show the file's own embedded cover (falls back to the music icon when absent).
            LocalArtworkResolver.loadInto(ivArt, selectedTrack.videoId);
        } else {
            loadArtworkInto(ivArt, selectedTrack.imageUrl);
        }
        ImageView ivBsOffline = view.findViewById(R.id.ivBsOfflineState);
        if (ivBsOffline != null) {
            ivBsOffline.setVisibility(offlineReady ? View.VISIBLE : View.GONE);
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
        if (offlineReady) {
            ivAddPrimary.setImageResource(R.drawable.ic_check_small);
            tvAddPrimary.setText("Descargado");
        } else {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold);
            tvAddPrimary.setText("Descargar");
        }
        final boolean sheetHasOfflineAudio = hasOfflineAudio;
        btnAddPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (sheetHasOfflineAudio) {
                removeTrackDownloadFromRow(position);
            } else if (fullyCached) {
                // Descarga-de-facto (solo caché): eliminarla = borrar sus streams cacheados.
                ExoMediaPlayer.removeCachedAudio(requireContext(), selectedTrack.videoId);
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
                if (isTrackInPlaylist(requireContext(), selectedTrack.videoId, gKey)) {
                    showAlreadyInPlaylistBar(selectedTrack, gName);
                } else {
                    addTrackToPlaylistByKey(gKey, selectedTrack);
                    showSavedInPlaylistBar(selectedTrack, gKey, gName);
                }
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

        // Row: Ir a artista
        View btnGoToArtist = view.findViewById(R.id.btnBsGoToArtist);
        boolean hasArtist = !TextUtils.isEmpty(selectedTrack.artist);
        btnGoToArtist.setVisibility(hasArtist ? View.VISIBLE : View.GONE);
        if (hasArtist) {
            btnGoToArtist.setOnClickListener(v -> {
                dialog.dismiss();
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openArtistDetailByName(selectedTrack.artist);
                }
            });
        }

        // Row: Agregar a la fila
        btnAddToQueue.setVisibility(View.VISIBLE);
        ImageView ivAddToQueue = btnAddToQueue.findViewById(R.id.ivBsAddToQueue);
        TextView tvAddToQueue = btnAddToQueue.findViewById(R.id.tvBsAddToQueue);
        ivAddToQueue.setImageResource(R.drawable.ic_bs_add_queue_yt);
        tvAddToQueue.setText("Agregar a la fila");
        btnAddToQueue.setOnClickListener(v -> {
            dialog.dismiss();
            queueTrackAtEnd(position);
            AppSnackbar.show(getActivity(), "Agregado a la fila");
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
            String addedKey = lastAddedKey[0];
            String addedName = lastAddedName[0];
            boolean removed = didRemove[0];
            saveDialog.dismiss();
            if (addedKey != null && addedName != null) {
                CustomPlaylistsStore.setLastSavedPlaylist(requireContext(), addedKey, addedName);
                showSavedInPlaylistBar(track, addedKey, addedName);
            } else if (removed) {
                String playlistName = resolveCurrentPlaylistName();
                showRemovedFromPlaylistBar(track, playlistName);
                currentTracks.removeIf(t -> TextUtils.equals(t.videoId, track.videoId));
                trackAdapter.submitTracks(currentTracks);
                rebuildPlaybackQueue();
            }
        });

        LinearLayout llList = sheet.findViewById(R.id.llSavePlaylistList);
        llList.removeAllViews();

        float density = ctx.getResources().getDisplayMetrics().density;
        int thumbSizePx = (int) (48 * density);

        // Cap scroll area so footer buttons remain visible
        View svScroll = sheet.findViewById(R.id.svSavePlaylistScroll);
        if (svScroll != null) {
            int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f);
            svScroll.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            svScroll.post(() -> {
                if (svScroll.getHeight() > maxH) {
                    android.view.ViewGroup.LayoutParams lp = svScroll.getLayoutParams();
                    lp.height = maxH;
                    svScroll.setLayoutParams(lp);
                }
            });
        }

        // Build playlist entries: liked first, then Favoritos, then custom playlists
        List<FavoritesPlaylistStore.FavoriteTrack> favs = FavoritesPlaylistStore.loadFavorites(ctx);

        // Inflate favorites row
        {
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(FavoritesPlaylistStore.PLAYLIST_TITLE);
            tvCount.setText(favs.size() + " pistas");
            FavoritesArt.bindCover(ivThumb);
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

        // "Música que te gustó" row (local mirror, insert at top)
        {
            String likedPid = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID;
            String likedMirrorKey = CustomPlaylistsStore.YT_MIRROR_PREFIX + likedPid;
            YouTubeMusicService.TrackResult likedCached = MusicPlayerFragment.getLikedPlaylistFromCache();
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText("Música que te gustó");
            tvCount.setText(likedCached != null ? likedCached.subtitle : "Playlist");
            ivThumb.setBackgroundResource(R.drawable.bg_music_liked_gradient);
            ivThumb.setImageResource(R.drawable.ic_thumb_up_liked);
            ivThumb.setScaleType(ImageView.ScaleType.CENTER);
            ivThumb.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            // Checked = union of server cache + local mirror, matching the player's like icon.
            boolean isIn = CustomPlaylistsStore.INSTANCE.isTrackInYtMirror(ctx, likedPid, track.videoId)
                    || FavoritesPlaylistStore.isInLikedMusic(ctx, track.videoId);
            if (ivCheck != null) ivCheck.setVisibility(isIn ? View.VISIBLE : View.GONE);
            final boolean[] checked = {isIn};
            row.setOnClickListener(v -> {
                if (checked[0]) {
                    // Remove from BOTH stores so server-cached likes actually un-like.
                    CustomPlaylistsStore.INSTANCE.removeTrackFromYtMirror(ctx, likedPid, track.videoId);
                    FavoritesPlaylistStore.removeFromLikedMusic(ctx, track.videoId);
                    checked[0] = false;
                    didRemove[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.GONE);
                    if (likedMirrorKey.equals(lastAddedKey[0])) {
                        lastAddedKey[0] = null;
                        lastAddedName[0] = null;
                    }
                } else {
                    String tTitle = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
                    String tArtist = track.artist == null ? "" : track.artist;
                    String tDuration = track.duration == null ? "" : track.duration;
                    String tImage = track.imageUrl == null ? "" : track.imageUrl;
                    CustomPlaylistsStore.INSTANCE.addTrackToYtMirror(ctx, likedPid, track.videoId,
                            tTitle, tArtist, tDuration, tImage, true);
                    FavoritesPlaylistStore.clearLikedTombstone(ctx, track.videoId);
                    checked[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.VISIBLE);
                    lastAddedKey[0] = likedMirrorKey;
                    lastAddedName[0] = "Música que te gustó";
                }
            });
            // "Música que te gustó" always first, Favoritos second.
            llList.addView(row, 0);
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

        // YouTube library playlists (local mirror)
        List<YouTubeMusicService.TrackResult> ytPlaylists = MusicPlayerFragment.getYouTubeLibraryPlaylists();
        for (YouTubeMusicService.TrackResult ytItem : ytPlaylists) {
            String ytPlaylistId = ytItem.contentId == null ? "" : ytItem.contentId.trim();
            if (ytPlaylistId.isEmpty()) continue;
            String ytMirrorKey = CustomPlaylistsStore.YT_MIRROR_PREFIX + ytPlaylistId;
            String ytFallbackThumb = ytItem.thumbnailUrl == null ? "" : ytItem.thumbnailUrl.trim();

            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(ytItem.title == null ? "" : ytItem.title);
            tvCount.setText(ytItem.subtitle == null ? "Playlist" : ytItem.subtitle);
            List<String> ytUrls = loadPersistedGridUrls(ctx, ytPlaylistId);
            if (ytUrls.size() < 4) {
                ytUrls = new ArrayList<>();
                List<FavoritesPlaylistStore.FavoriteTrack> ytMirrorTracks =
                        CustomPlaylistsStore.INSTANCE.getYtMirrorTracks(ctx, ytPlaylistId);
                for (FavoritesPlaylistStore.FavoriteTrack t : ytMirrorTracks) {
                    if (!TextUtils.isEmpty(t.imageUrl)) {
                        if (!ytUrls.contains(t.imageUrl)) ytUrls.add(t.imageUrl);
                        if (ytUrls.size() >= 4) break;
                    }
                }
            }
            if (ytUrls.size() >= 4) {
                PlaylistGridArtLoader.load(ivThumb, ytUrls, thumbSizePx);
            } else if (!ytUrls.isEmpty()) {
                loadArtworkInto(ivThumb, ytUrls.get(0));
            } else if (!ytFallbackThumb.isEmpty()) {
                loadArtworkInto(ivThumb, ytFallbackThumb);
            }
            boolean isIn = CustomPlaylistsStore.INSTANCE.isTrackInYtMirror(ctx, ytPlaylistId, track.videoId);
            if (ivCheck != null) ivCheck.setVisibility(isIn ? View.VISIBLE : View.GONE);
            final boolean[] checked = {isIn};
            final String ytPid = ytPlaylistId;
            final String ytPName = ytItem.title == null ? "" : ytItem.title;
            row.setOnClickListener(v -> {
                if (checked[0]) {
                    CustomPlaylistsStore.INSTANCE.removeTrackFromYtMirror(ctx, ytPid, track.videoId);
                    checked[0] = false;
                    didRemove[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.GONE);
                    if (ytMirrorKey.equals(lastAddedKey[0])) {
                        lastAddedKey[0] = null;
                        lastAddedName[0] = null;
                    }
                } else {
                    String tTitle = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
                    String tArtist = track.artist == null ? "" : track.artist;
                    String tDuration = track.duration == null ? "" : track.duration;
                    String tImage = track.imageUrl == null ? "" : track.imageUrl;
                    CustomPlaylistsStore.INSTANCE.addTrackToYtMirror(ctx, ytPid, track.videoId,
                            tTitle, tArtist, tDuration, tImage, false);
                    checked[0] = true;
                    if (ivCheck != null) ivCheck.setVisibility(View.VISIBLE);
                    lastAddedKey[0] = ytMirrorKey;
                    lastAddedName[0] = ytPName;
                }
            });
            llList.addView(row);
        }

        saveDialog.getBehavior().setSkipCollapsed(true);
        saveDialog.getBehavior().setFitToContents(true);
        try { saveDialog.getBehavior().setHideFriction(0.5f); } catch (Throwable ignored) {}
        saveDialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

        sheet.setAlpha(0f);
        saveDialog.setOnShowListener(d -> {
            View bottomSheetSave = ((com.google.android.material.bottomsheet.BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheetSave != null) {
                View sheetParent = (View) sheet.getParent();
                if (sheetParent != null) sheetParent.setBackgroundColor(Color.TRANSPARENT);
                bottomSheetSave.setBackgroundResource(android.R.color.transparent);
            }
            sheet.post(() -> sheet.animate().alpha(1f).setDuration(150L).start());
        });
        saveDialog.show();
    }

    @NonNull
    private static List<String> loadPersistedGridUrls(@NonNull Context ctx, @NonNull String playlistId) {
        try {
            String raw = ctx.getApplicationContext()
                    .getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE)
                    .getString("playlist_grid_urls_" + playlistId, "");
            if (TextUtils.isEmpty(raw)) return java.util.Collections.emptyList();
            String[] parts = raw.split("\\n");
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (!TextUtils.isEmpty(part)) result.add(part);
            }
            return result;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
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
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = playlistKey.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid)) {
                return CustomPlaylistsStore.INSTANCE.isTrackInYtMirror(ctx, pid, videoId)
                        || FavoritesPlaylistStore.isInLikedMusic(ctx, videoId);
            }
            return CustomPlaylistsStore.INSTANCE.isTrackInYtMirror(ctx, pid, videoId);
        }
        return false;
    }

    private int getPlaylistTrackCount(@NonNull Context ctx, @NonNull String playlistKey) {
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistKey)) {
            return FavoritesPlaylistStore.loadFavorites(ctx).size();
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            String name = playlistKey.substring(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX.length());
            return CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name).size();
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = playlistKey.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            return CustomPlaylistsStore.INSTANCE.getYtMirrorTracks(ctx, pid).size();
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
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = playlistKey.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            CustomPlaylistsStore.INSTANCE.addTrackToYtMirror(requireContext(), pid,
                    track.videoId, title, artist, duration, imageUrl, false);
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid)) {
                FavoritesPlaylistStore.clearLikedTombstone(requireContext(), track.videoId);
            }
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

        // If the added track belongs to the playlist that is currently open, reuse the proper
        // single-track path so the work is observable on this screen and uses the real title/tag.
        if (TextUtils.equals(playlistKey, currentPlaylistId)) {
            enqueueSingleTrackForOfflineDownload(videoId, title, artist, duration);
            return;
        }

        // Off-screen playlist: still route through the MANUAL unique-work queue and tag with the
        // target playlist's offline tag so the job is deduped and cancelable — instead of the old
        // untagged raw enqueue() that nothing could observe, dedupe, or cancel.
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
                    .addTag(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)
                    .addTag(OFFLINE_DOWNLOAD_UNIQUE_PREFIX + playlistId)
                    .build();
            enqueueOfflineDownloadUniqueWork(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME, request);
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
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = playlistKey.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            CustomPlaylistsStore.INSTANCE.removeTrackFromYtMirror(requireContext(), pid, videoId);
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid)) {
                FavoritesPlaylistStore.removeFromLikedMusic(requireContext(), videoId);
            }
        }
    }

    private void showSavedInPlaylistBar(@NonNull PlaylistTrack track, @NonNull String playlistKey, @NonNull String playlistName) {
        AppSnackbar.showAction(getActivity(), "Se guardó en " + playlistName, "Cambiar", () -> {
            CustomPlaylistsStore.clearLastSavedPlaylist(requireContext());
            showSaveToPlaylistSheet(track, playlistKey);
        });
    }

    private void showAlreadyInPlaylistBar(@NonNull PlaylistTrack track, @NonNull String playlistName) {
        AppSnackbar.showAction(getActivity(), "Ya está en " + playlistName, "Cambiar", () -> {
            CustomPlaylistsStore.clearLastSavedPlaylist(requireContext());
            showSaveToPlaylistSheet(track, null);
        });
    }

    private void showRemovedFromPlaylistBar(@NonNull PlaylistTrack track, @NonNull String playlistName) {
        AppSnackbar.showAction(getActivity(), (TextUtils.isEmpty(track.title) ? "Tema" : track.title) + " eliminado de " + playlistName, "Deshacer", () -> {
            undoRemoveTrackFromPlaylist(track);
            AppSnackbar.show(getActivity(), "Restaurado en " + playlistName);
        });
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

        AppSnackbar.show(getActivity(), "Se reproducirá a continuación");
        
    }

    private void startRadioForTrack(@NonNull PlaylistTrack track) {
        if (!isAdded() || TextUtils.isEmpty(track.videoId)) return;
        if (getParentFragmentManager().isStateSaved()) return;
        String radioPlaylistId = "RDAMVM" + track.videoId;
        // Shared launcher for the radio id/title canonicalization + overlay/top-bar chrome this path
        // used to skip. removeExisting=false: THIS detail is the caller and must stay alive — the
        // radio-save callback below still uses its context, and back should return to this live
        // screen. The new radio detail is added on top (matches the pre-refactor transaction).
        PlaylistDetailLauncher.open(
                getActivity(),
                getParentFragmentManager(),
                radioPlaylistId,
                TextUtils.isEmpty(track.title) ? "Tema" : track.title,
                track.artist == null ? "" : track.artist,
                track.imageUrl == null ? "" : track.imageUrl,
                track.videoId,
                false,
                false
        );

        // Fetch radio tracks and save to RadioHistoryStore for library display
        String cookie = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
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
                            SongSubtitle.artistOnly(t.subtitle, t.title),
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
        } else if (currentPlaylistId != null && currentPlaylistId.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = currentPlaylistId.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid)) return "Música que te gustó";
            for (YouTubeMusicService.TrackResult item : MusicPlayerFragment.getYouTubeLibraryPlaylists()) {
                if (item.contentId != null && pid.equals(item.contentId.trim())) return item.title;
            }
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
        } else if (currentPlaylistId != null && currentPlaylistId.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = currentPlaylistId.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            boolean insertTop = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid);
            CustomPlaylistsStore.INSTANCE.addTrackToYtMirror(requireContext(), pid,
                track.videoId, track.title, track.artist, track.duration, track.imageUrl, insertTop);
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

        // Record the parent playlist so a single-song download still surfaces it in "Descargas".
        recordDownloadedPlaylistMeta();

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
        lastActiveOfflineUniqueName = uniqueName;

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
                    // Re-find the row by videoId — the captured position may be stale if the
                    // list changed during the async delete.
                    int pos = -1;
                    for (int i = 0; i < currentTracks.size(); i++) {
                        if (TextUtils.equals(currentTracks.get(i).videoId, videoId)) { pos = i; break; }
                    }
                    if (pos >= 0) trackAdapter.notifyItemChanged(pos);
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

        AppSnackbar.show(getActivity(), "Reemplazo deshecho");

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
            lastActiveOfflineUniqueName = uniqueName;
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

        // Playback now starts in the mini-player (never auto-opens the full player), so we no
        // longer animate the mini-player out here — it stays/appears instead.

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
                    injectOriginalQueueOrderIfShuffled(existingPlayer);
                }

                ensureMiniPlayerShown();

                currentTrackIndex = position;
                miniPlaying = true;
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(position);
                }
            }
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
        injectOriginalQueueOrderIfShuffled(playerFragment);
        // Start hidden → plays in the mini-player instead of opening the full-screen player.
        addSongPlayerHidden(playerFragment);
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
                .add(R.id.playerContainer, playerFragment, AppConstants.TAG_SONG_PLAYER)
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

    /**
     * Adds the player attached but HIDDEN so playback starts in the mini-player instead of the
     * full-screen player (the pre-change behavior: playing a song from a list never auto-opened
     * the player). The global mini-player then appears on its own via the snapshot event; we also
     * refresh it immediately so it never lags a frame behind.
     */
    private void addSongPlayerHidden(@NonNull SongPlayerFragment player) {
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, player, AppConstants.TAG_SONG_PLAYER)
                .hide(player)
                .runOnCommit(this::ensureMiniPlayerShown)
                .commit();
    }

    /** Refresh the global mini-player so it appears/updates when playback starts hidden. */
    private void ensureMiniPlayerShown() {
        if (getActivity() instanceof MainActivity) {
            GlobalMiniPlayerController gmp = ((MainActivity) getActivity()).getGlobalMiniPlayer();
            if (gmp != null) gmp.updateUi();
        }
    }

    private void injectOriginalQueueOrderIfShuffled(@NonNull SongPlayerFragment player) {
        if (!shuffleModeEnabled) return;
        List<PlaylistTrack> base = originalTracks.isEmpty() ? currentTracks : originalTracks;
        if (base.isEmpty()) return;
        java.util.List<SongPlayerFragment.PlayerTrack> original = new java.util.ArrayList<>(base.size());
        for (PlaylistTrack item : base) {
            original.add(new SongPlayerFragment.PlayerTrack(
                    item.videoId, item.title, item.artist, item.duration, item.imageUrl
            ));
        }
        player.externalSetOriginalQueueOrder(original);
    }

    private void injectOriginalQueueFromSnapshot(
            @NonNull SongPlayerFragment player,
            @NonNull PlaybackHistoryStore.Snapshot snapshot
    ) {
        if (snapshot.originalQueue == null || snapshot.originalQueue.isEmpty()) return;
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
        injectOriginalQueueOrderIfShuffled(playerFragment);

        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, AppConstants.TAG_SONG_PLAYER)
                .hide(playerFragment)
                .runOnCommit(this::ensureMiniPlayerShown)
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
        
        PlaybackHistoryStore.Snapshot snapshot = null;
        PlaybackHistoryStore.QueueTrack snapshotTrack = null;

        if (playerAttached) {
            miniPlaying = songPlayer.externalIsPlaying();

            if (!currentTracks.isEmpty()) {
                String playerVideoId = songPlayer.externalGetCurrentVideoId();
                int mappedIndex = findTrackIndexByVideoId(currentTracks, playerVideoId);
                if (mappedIndex >= 0) {
                    currentTrackIndex = mappedIndex;
                } else {
                    snapshot = loadPlaybackSnapshot();
                    snapshotTrack = snapshot.currentTrack();
                    int snapshotMappedIndex = snapshotTrack == null
                            ? -1
                            : findTrackIndexByVideoId(currentTracks, snapshotTrack.videoId);
                    currentTrackIndex = snapshotMappedIndex >= 0 ? snapshotMappedIndex : -1;
                }
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(currentTrackIndex);
                }
            }
        } else {
            snapshot = loadPlaybackSnapshot();
            snapshotTrack = snapshot.currentTrack();
            if (snapshotTrack != null) {
                // No live player attached → nothing is actually playing right now. Do NOT read
                // the (stale) persisted snapshot.isPlaying, which made this row render/persist as
                // "playing" while the mini-bar (which forces false with no player) showed paused —
                // the two surfaces disagreed for the identical state. Mirror the mini-bar.
                miniPlaying = false;
                int mappedIndex = currentTracks.isEmpty()
                        ? -1
                        : findTrackIndexByVideoId(currentTracks, snapshotTrack.videoId);
                currentTrackIndex = mappedIndex >= 0 ? mappedIndex : -1;
                if (trackAdapter != null) {
                    trackAdapter.setActiveIndex(currentTrackIndex);
                }
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
        // Vista Descargas: la lista ya está filtrada a lo descargado, así que `songs` ES el
        // número de descargas — se muestra explícito al final ("… • 10 descargadas").
        if (downloadedOnlyMode && songs > 0) {
            parts.add(songs == 1 ? "1 descargada" : songs + " descargadas");
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

    private void applyHeaderBackdropVisualState(@NonNull ImageView backdrop, boolean flatGradient) {
        if (flatGradient) {
            // Liked/Favorites: the backdrop IS the same flat gradient as the cover, so it must render
            // at FULL opacity and UNBLURRED — one seamless gradient field. The default 0.68 alpha +
            // blur turned it into a dimmed, mismatched purple wash behind a bright cover square.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                backdrop.setRenderEffect(null);
            }
            backdrop.setTag(null);
            backdrop.setAlpha(1f);
            backdrop.clearColorFilter();
            return;
        }
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

    // ===== Header cover HD / radio helpers =====

    private static boolean isYtImgThumb(@Nullable String url) {
        return url != null && (url.contains("i.ytimg.com/vi/") || url.contains("img.youtube.com/vi/"));
    }

    /**
     * Highest-resolution variant of a header cover URL. googleusercontent/ggpht '=wW-hH' CDN thumbs
     * are rewritten to {@code sizePx} via {@link ThumbnailUrls#atSize}; i.ytimg '*default.jpg' video
     * thumbs (on which atSize is a no-op) are promoted to maxresdefault. Callers keep an .error()
     * fallback to the raw URL because maxresdefault 404s for some videos and old disk-cache entries
     * are keyed by the raw URL.
     */
    @NonNull
    private static String hdHeaderCoverUrl(@NonNull String raw, int sizePx) {
        if (raw.isEmpty()) return raw;
        if (isYtImgThumb(raw)) {
            // default.jpg / mqdefault.jpg / hqdefault.jpg / sddefault.jpg / maxresdefault.jpg → maxres
            return raw.replaceFirst("/[a-z0-9]*default\\.jpg", "/maxresdefault.jpg");
        }
        String sized = ThumbnailUrls.atSize(raw, sizePx);
        return sized == null ? raw : sized;
    }

    /**
     * Renders the radio/mix header: el MISMO composite estilo YT Music que compone el carrusel del
     * home ({@link RadioArtComposer}: degradado claro→oscuro + barras de onda + 3 círculos iguales
     * a todo color + chip de play) sobre el mismo campo degradado, para que una radio se vea
     * idéntica donde sea que aparezca. Sin insignia "RADIO" ni título sobre la portada (nuevo
     * diseño). Center = the seed cover; los círculos laterales = otros tracks de la radio.
     */
    private void bindRadioHeaderCover(@NonNull PlaylistHeaderAdapter.HeaderViewHolder holder) {
        ImageView cover = holder.ivPlaylistCover;
        ImageView backdrop = holder.ivPlaylistBackdrop;
        cover.setPadding(0, 0, 0, 0);
        cover.setColorFilter(null);
        // The composite is a full-bleed square with transparent gaps; fitXY fills the square cover
        // (matches item_radio_carousel's ivRadioComposite) and the field shows through the gaps.
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);

        String seedVideoId = currentPlaylistId.startsWith("RDAMVM") && currentPlaylistId.length() > 6
                ? currentPlaylistId.substring(6) : "";
        String seedHd = !seedVideoId.isEmpty()
                ? "https://i.ytimg.com/vi/" + android.net.Uri.encode(seedVideoId) + "/maxresdefault.jpg"
                : headerPlaylistThumbnail.trim();

        RadioHistoryStore.RadioEntry entry = findRadioEntry(currentPlaylistId);
        // Match home's cache key (it composes from entry.songThumbnail) so a radio already shown on
        // home hits RadioArtComposer's disk cache; only a player-opened radio with no saved entry
        // falls back to the HD seed image.
        String centerUrl = entry != null && entry.getSongThumbnail() != null
                && !entry.getSongThumbnail().isEmpty()
                ? entry.getSongThumbnail()
                : (!seedHd.isEmpty() ? seedHd : headerPlaylistThumbnail.trim());
        // Sides + color del campo vía RadioArt (mismas prefs sides_/color_ que compartía con el
        // home). ensureRawColor deduplica internamente los Palette pass concurrentes por radioId,
        // así el header conserva su garantía de una sola petición.
        android.content.Context ctx = cover.getContext();
        String[] sides = RadioArt.resolveSides(ctx, currentPlaylistId, centerUrl);
        int raw = RadioArt.rawColor(ctx, currentPlaylistId);
        if (raw == 0) {
            // Violeta por defecto mientras el Palette asíncrono calcula + persiste el real.
            raw = RadioArt.DEFAULT_RAW;
            final String radioId = currentPlaylistId;
            RadioArt.ensureRawColor(ctx, radioId, centerUrl, () -> {
                if (!isAdded() || !radioId.equals(currentPlaylistId)) return;
                notifyHeaderChanged();
            });
        }
        cover.setBackground(RadioArtComposer.fieldDrawable(raw));
        int coverPx = isAdded() ? ThumbnailUrls.dpToPx(requireContext(), 260) : 0;
        RadioArtComposer.INSTANCE.load(
                cover,
                currentPlaylistId,
                centerUrl == null ? "" : centerUrl,
                sides[0],
                sides[1],
                coverPx > 0 ? coverPx : 720,
                raw
        );

        // Backdrop: the SAME composed radio art as the foreground cover (was the low-res seed image
        // behind a crisp composite), over the same tinted field. Reusing coverPx hits the composer's
        // cache for the bitmap already built for the cover — no extra compose work, identical HD copy.
        backdrop.setBackground(RadioArtComposer.fieldDrawable(raw));
        RadioArtComposer.INSTANCE.load(
                backdrop,
                currentPlaylistId,
                centerUrl == null ? "" : centerUrl,
                sides[0],
                sides[1],
                coverPx > 0 ? coverPx : 720,
                raw
        );

        // Nuevo diseño: la portada de radio no lleva insignia "RADIO" ni título encima — el arte
        // (onda + círculos) se identifica solo, igual que la tarjeta del home.
        if (holder.tvRadioHeaderTitle != null) {
            holder.tvRadioHeaderTitle.setVisibility(View.GONE);
        }
        if (holder.tvRadioHeaderBadge != null) {
            holder.tvRadioHeaderBadge.setVisibility(View.GONE);
        }
    }

    /**
     * Renders a liked/favorites-style header where a single flat gradient fills BOTH the cover and
     * the full-width backdrop as one continuous field, with a centered white icon on the cover. Any
     * in-flight/stale Glide load on either view is cleared first so a recycled ViewHolder's previous
     * playlist thumbnail can't bleed through the gradient (that stale image was the "mismatched purple
     * backdrop" symptom). The backdrop's full-opacity, unblurred rendering is finished by
     * {@link #applyHeaderBackdropVisualState} on its flatGradient path.
     *
     * @param iconInsetPx padding on all sides of the cover icon — larger inset = smaller icon.
     */
    private void bindFlatGradientHeaderCover(@NonNull PlaylistHeaderAdapter.HeaderViewHolder holder,
            int gradientRes, int iconRes, int iconInsetPx) {
        try { Glide.with(holder.itemView).clear(holder.ivPlaylistCover); } catch (Exception ignored) {}
        try { Glide.with(holder.itemView).clear(holder.ivPlaylistBackdrop); } catch (Exception ignored) {}

        android.content.Context ctx = holder.itemView.getContext();
        // Cover: the gradient is the IMAGE (not a background) so the ShapeableImageView CLIPS it to the
        // same rounded 14dp corners as every other cover, and CENTER_CROP makes it fill the whole 260dp
        // square edge-to-edge (a background fill stayed rectangular AND didn't fill the container — the
        // bug the user reported). The thumb/cat icon rides on top as an inset foreground so it reads as
        // a smaller centered mark over a full gradient field, matching the home cards.
        holder.ivPlaylistCover.setTag(R.id.tag_artwork_signature, null);
        holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
        holder.ivPlaylistCover.setBackground(null);
        holder.ivPlaylistCover.setColorFilter(null);
        holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.ivPlaylistCover.setImageDrawable(ContextCompat.getDrawable(ctx, gradientRes));
        android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(ctx, iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            holder.ivPlaylistCover.setForeground(
                    new android.graphics.drawable.InsetDrawable(icon, iconInsetPx));
            holder.ivPlaylistCover.setForegroundGravity(android.view.Gravity.FILL);
        } else {
            holder.ivPlaylistCover.setForeground(null);
        }

        // Backdrop: same flat gradient field (rendered full-opacity + unblurred by
        // applyHeaderBackdropVisualState's flatGradient path) so cover + backdrop read as one gradient
        // — the "backdrop de antes" the user asked to keep.
        holder.ivPlaylistBackdrop.setTag(R.id.tag_artwork_signature, null);
        holder.ivPlaylistBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.ivPlaylistBackdrop.setImageDrawable(null);
        holder.ivPlaylistBackdrop.setColorFilter(null);
        holder.ivPlaylistBackdrop.setForeground(null);
        holder.ivPlaylistBackdrop.setBackgroundResource(gradientRes);
    }

    @Nullable
    private RadioHistoryStore.RadioEntry findRadioEntry(@NonNull String radioId) {
        if (!isAdded()) return null;
        try {
            for (RadioHistoryStore.RadioEntry e : RadioHistoryStore.INSTANCE.getRadios(requireContext())) {
                if (radioId.equals(e.getRadioPlaylistId())) return e;
            }
        } catch (Exception ignored) {
        }
        return null;
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
            headerShuffleButton = holder.btnShufflePlay;
            applyShuffleButtonStyle(holder.btnShufflePlay);
            holder.tvPlaylistName.setText(headerPlaylistTitle);
            holder.tvGoogleProfileName.setText(headerProfileName);
            holder.tvPlaylistInfo.setText(headerPlaylistInfo);
            holder.vPlaylistBackdropScrim.setVisibility(View.VISIBLE);
            holder.vPlaylistBackdropBottomFade.setVisibility(View.VISIBLE);
            holder.vPlaylistBackdropAmoledFade.setVisibility(View.GONE);

            int fallbackTopMargin = (int) (82 * holder.itemView.getResources().getDisplayMetrics().density);
            int backdropTopMargin = headerBackdropTopOverlapPx > 0 ? -headerBackdropTopOverlapPx : -fallbackTopMargin;
            setTopMargin(holder.ivPlaylistBackdrop, backdropTopMargin);
            setTopMargin(holder.vPlaylistBackdropScrim, backdropTopMargin);
            
            bindOfflineState(holder);

            // Only the liked/favorites branch draws an icon foreground over the cover; clear it here so
            // a normal cover (single/2x2/radio) never keeps a stale thumb/cat mark from a prior bind.
            holder.ivPlaylistCover.setForeground(null);

            // The "RADIO" badge + station-name overlays are radio-only; hide them for every other
            // header type. The radio branch (bindRadioHeaderCover) re-shows them with the name.
            if (holder.tvRadioHeaderTitle != null) holder.tvRadioHeaderTitle.setVisibility(View.GONE);
            if (holder.tvRadioHeaderBadge != null) holder.tvRadioHeaderBadge.setVisibility(View.GONE);

            if (isLikedPlaylistContext(currentPlaylistId)) {
                // Liked: the gradient fills the ENTIRE header — both the cover AND the full-width
                // backdrop use bg_music_liked_gradient so they read as ONE continuous field (not a
                // bright cover square floating over a dimmed/mismatched purple wash). The centered
                // thumb-up icon is a bit smaller than before (~40% of the cover). applyHeaderBackdrop-
                // VisualState() renders this backdrop flat + full-opacity (see its flatGradient path).
                float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                int padPx = Math.round(78 * density);
                bindFlatGradientHeaderCover(holder, R.drawable.bg_music_liked_gradient,
                        R.drawable.ic_thumb_up_liked, padPx);
            } else if (isFavoritesPlaylistContext(currentPlaylistId)) {
                // Favoritos: degradado cálido que llena TODO el header (portada + backdrop), gato
                // blanco centrado (un poco más pequeño). Misma receta que liked. Debe ir ANTES de la
                // rama del grid 2x2.
                float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                int padPx = Math.round(78 * density);
                bindFlatGradientHeaderCover(holder, R.drawable.bg_music_favorites_gradient,
                        R.drawable.ic_cat_white, padPx);
            } else if (isRadioContext && !forceSingleCoverArt) {
                // Radio/mix: render the SAME 3-circle Spotify-style composite the home carousel builds
                // (center seed + two grayscale "similar artists"), over the same flat fluorescent field.
                // Must sit ABOVE the grid + generic-thumbnail branches: those matched first before, so
                // the intended radio art was dead code and radios showed the low-res launching thumbnail.
                // forceSingleCoverArt skips this so a "recomendadas"/recap card that opened here clones
                // its single YT cover instead of a composite (the mix still FETCHES via /next).
                bindRadioHeaderCover(holder);
            } else if (!headerGridUrls.isEmpty() && !forceSingleCoverArt && !isLocalFilesContext(currentPlaylistId)) {
                holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistCover.setBackground(null);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistBackdrop.setBackground(null);
                PlaylistGridArtLoader.load(holder.ivPlaylistCover, headerGridUrls, 800);
                // Backdrop shares the SAME 800px composite as the cover (was 320px, so the blurred
                // backdrop read as low-res behind the crisp grid cover).
                PlaylistGridArtLoader.load(holder.ivPlaylistBackdrop, headerGridUrls, 800);
            } else if (!TextUtils.isEmpty(headerPlaylistThumbnail) && !isLocalFilesContext(currentPlaylistId)) {
                holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.ivPlaylistCover.setBackground(null);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistBackdrop.setBackground(null);
                // Force HD on low-res launch thumbnails: rewrite googleusercontent/ggpht '=wW-hH'
                // CDN URLs up to 800px (ThumbnailUrls.atSize) and i.ytimg '*default.jpg' to
                // maxresdefault, keeping an .error() fallback to the RAW url so old disk-cache
                // entries (keyed by the raw URL) and videos without a maxres variant still resolve.
                String rawCover = headerPlaylistThumbnail.trim();
                boolean ytImg = isYtImgThumb(rawCover);
                String hdCover = hdHeaderCoverUrl(rawCover, 800);
                // The blurred backdrop shares the EXACT same HD copy as the foreground cover (same
                // url + same 800px decode), so Glide serves one decoded bitmap for both and the
                // backdrop is no longer the old low-res 320px thumbnail behind a crisp cover.
                String hdBackdrop = hdCover;
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> coverReq =
                        Glide.with(holder.itemView)
                                .load(hdCover)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .priority(com.bumptech.glide.Priority.HIGH)
                                .override(800, 800)
                                .transition(DrawableTransitionOptions.withCrossFade(200));
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> backdropReq =
                        Glide.with(holder.itemView)
                                .load(hdBackdrop)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(800, 800)
                                .transition(DrawableTransitionOptions.withCrossFade(200));
                if (ytImg) {
                    // i.ytimg video thumbs carry 16:9 letterbox bars; the rows already crop them via
                    // SHARED_YT_CROP, the header did not — crop here too so covers match the rows.
                    coverReq = coverReq.transform(SHARED_YT_CROP);
                    backdropReq = backdropReq.transform(SHARED_YT_CROP);
                }
                if (!hdCover.equals(rawCover)) {
                    coverReq = coverReq.error(Glide.with(holder.itemView).load(rawCover)
                            .diskCacheStrategy(DiskCacheStrategy.ALL).override(800, 800)
                            .transition(DrawableTransitionOptions.withCrossFade(200)));
                }
                if (!hdBackdrop.equals(rawCover)) {
                    backdropReq = backdropReq.error(Glide.with(holder.itemView).load(rawCover)
                            .diskCacheStrategy(DiskCacheStrategy.ALL).override(800, 800)
                            .transition(DrawableTransitionOptions.withCrossFade(200)));
                }
                coverReq.into(holder.ivPlaylistCover);
                backdropReq.into(holder.ivPlaylistBackdrop);
            } else if (isLocalFilesContext(currentPlaylistId)) {
                boolean albumCtx = LocalFilesStore.isLocalAlbumId(currentPlaylistId);
                holder.ivPlaylistCover.setColorFilter(null);
                holder.ivPlaylistCover.setTag(R.id.tag_artwork_signature, null);
                // Pista local representativa (la 1ª del álbum) para tomar su carátula embebida.
                String repVideoId = null;
                if (albumCtx) {
                    for (PlaylistTrack t : currentTracks) {
                        if (t != null && LocalArtworkResolver.isLocal(t.videoId)) {
                            repVideoId = t.videoId;
                            break;
                        }
                    }
                }
                if (repVideoId != null) {
                    // Álbum local: carátula embebida REAL (el disco) llenando la portada, sin el
                    // fondo negro ni el icono de carpeta.
                    holder.ivPlaylistCover.setPadding(0, 0, 0, 0);
                    holder.ivPlaylistCover.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    LocalArtworkResolver.loadInto(holder.ivPlaylistCover, repVideoId);
                } else {
                    // Sin arte embebido: icono discreto centrado sobre transparente — disco para
                    // un álbum, carpeta para "todos los archivos".
                    float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                    int padPx = Math.round(64 * density);
                    holder.ivPlaylistCover.setPadding(padPx, padPx, padPx, padPx);
                    holder.ivPlaylistCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    holder.ivPlaylistCover.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    holder.ivPlaylistCover.setImageResource(
                            albumCtx ? R.drawable.ic_album_translucent : R.drawable.ic_folder_white);
                }
                // Backdrop TRANSPARENTE: sin el lavado azul/oscuro — el header se funde con la
                // página (el usuario lo pidió transparente para álbumes/archivos locales).
                holder.ivPlaylistBackdrop.setBackground(null);
                holder.ivPlaylistBackdrop.setImageDrawable(
                        new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                holder.vPlaylistBackdropScrim.setVisibility(View.GONE);
                holder.vPlaylistBackdropBottomFade.setVisibility(View.GONE);
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

            // Liked/Favoritos: el backdrop se trata IGUAL que el de cualquier playlist (atenuado +
            // degradado a negro abajo vía scrim/bottom-fade), NO como un campo plano a opacidad total
            // — ese campo plano no fundía a negro y cortaba brusco contra la lista. El degradado vivo
            // se queda en la PORTADA redondeada (la imagen), no en el backdrop.
            applyHeaderBackdropVisualState(holder.ivPlaylistBackdrop, false);

            if (headerProfilePhoto != null) {
                Glide.with(holder.itemView)
                        .load(headerProfilePhoto)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .onlyRetrieveFromCache(!isInternetAvailable())
                        .circleCrop()
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(holder.ivGoogleProfile);
            } else {
                // Sin foto: vacío, nunca un placeholder.
                holder.ivGoogleProfile.setImageDrawable(null);
            }

        }

        private void setTopMargin(@NonNull View target, int topMarginPx) {
            ViewGroup.LayoutParams params = target.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).topMargin = topMarginPx;
                target.setLayoutParams(params);
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
            final TextView tvRadioHeaderTitle;
            final TextView tvRadioHeaderBadge;
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
                tvRadioHeaderTitle = itemView.findViewById(R.id.tvRadioHeaderTitle);
                tvRadioHeaderBadge = itemView.findViewById(R.id.tvRadioHeaderBadge);
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
                    if (currentTracks.isEmpty()) return;
                    if (isPlayingFromThisPlaylist()) {
                        // YA estamos reproduciendo esta playlist: el botón es un ESPEJO del
                        // aleatorio del player — alterna el modo sin cambiar la canción.
                        SongPlayerFragment player = findSongPlayerFragment();
                        boolean enable = !(player != null && player.isAdded()
                                && player.externalIsShuffleEnabled());
                        if (player != null && player.isAdded()) {
                            if (!enable) injectOriginalQueueOrderIfShuffled(player);
                            player.externalSetShuffleEnabled(enable);
                        }
                        shuffleModeEnabled = enable;
                        persistShuffleModePreference();
                        applyShuffleButtonStyle(btnShufflePlay);
                    } else {
                        // Nada de esta playlist sonando: reproducir una canción al azar con
                        // aleatorio encendido, reflejándolo también en el player (si ya estaba
                        // en aleatorio, externalSetShuffleEnabled(true) es no-op y se mantiene).
                        shuffleModeEnabled = true;
                        int randomIndex = new Random().nextInt(currentTracks.size());
                        onTrackSelected(randomIndex);
                        SongPlayerFragment started = findSongPlayerFragment();
                        if (started != null && started.isAdded()) {
                            started.externalSetShuffleEnabled(true);
                        }
                        applyShuffleButtonStyle(btnShufflePlay);
                        // El player puede crearse async tras onTrackSelected: reintento corto
                        // para que su icono de aleatorio quede encendido también.
                        mainHandler.postDelayed(() -> {
                            SongPlayerFragment late = findSongPlayerFragment();
                            if (late != null && late.isAdded()) {
                                late.externalSetShuffleEnabled(true);
                            }
                            applyShuffleButtonStyle(headerShuffleButton);
                        }, 450L);
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
            // App-wide row standard ("artist \u2022 album \u2022 duration \u2022 views") via the shared
            // formatter, which also re-parses artist fields polluted by older persisted data.
            this.normalizedSubtitle = SongSubtitle.forRowParts(artist, normalizeDurationLabel(duration));
        }
    }

    private interface OnTrackTap {
        void onTap(int position);
        void onMoreTap(int position, @NonNull View anchor);
    }

    // Sized to hold a full max-length playlist (1800 tracks) so far scroll-returns hit the cache
    // instead of re-stat'ing on disk. Booleans in a LinkedHashMap are cheap (~a few hundred KB at
    // this size), so oversizing slightly past the max playlist length is a safe trade.
    private static final int TRACK_STATE_CACHE_MAX_SIZE = 2048;

    /** Offline indicator visual states tracked per ViewHolder to skip redundant per-bind work. */
    private static final int OFFLINE_STATE_NONE = 0;
    private static final int OFFLINE_STATE_AVAILABLE = 1;
    private static final int OFFLINE_STATE_DOWNLOADING = 2;

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

                    // Settle artwork for the initial visible range once the RV finishes its first
                    // layout pass. (Downloaded-state is no longer scanned here — see "Descargas".)
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
                                    // Header occupies position 0 — clamp so the pass still runs
                                    // when the header is the first visible item (first maps to -1).
                                    int first = Math.max(0, llm.findFirstVisibleItemPosition() - 1);
                                    int last = llm.findLastVisibleItemPosition() - 1;
                                    if (last >= 0) {
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
            } else {
                // Never give up: dropping the notify left rows visually stuck (e.g. a finished
                // download whose check-flip rebind was discarded during sustained scrolling).
                // Back off instead — the RecyclerView always goes idle eventually.
                int delay = attempt < 3 ? 16 : (attempt < 8 ? 32 : 96);
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
            offlineDownloadRunning = running;
            downloadingTrackIds.clear();
            downloadingTrackProgressById.clear();
            if (running) {
                downloadingTrackIds.addAll(normalizedIds);
                downloadingTrackProgressById.putAll(normalizedProgressById);
            }

            // Real-time row updates: a track that just LEFT the active set finished (or failed)
            // RIGHT NOW. Resolve its state immediately with the cheap cached check — the worker
            // already marked OfflineAudioStore's state cache on success — so the row's checkmark
            // appears in THIS tick's rebind instead of waiting for an async disk scan. Only a
            // failed download falls back to cache invalidation + verified disk lookup.
            // Tracks STILL downloading are deliberately NOT invalidated: their cached "false" is
            // still correct, and re-nulling them every 650ms progress tick just forced repeated
            // MediaMetadataRetriever probes (scroll jank while any download ran).
            Context stateCtx = getContext();
            for (String previousTrackId : previousIds) {
                if (downloadingTrackIds.contains(previousTrackId)) {
                    continue; // still active — cached state remains valid
                }
                if (stateCtx != null && OfflineAudioStore.hasOfflineAudio(stateCtx, previousTrackId)) {
                    offlineAvailabilityCache.put(previousTrackId, Boolean.TRUE);
                } else {
                    invalidateTrackStateCache(previousTrackId);
                }
            }

            Set<String> changedIds = new HashSet<>(previousIds);
            changedIds.addAll(downloadingTrackIds);
            if (progressChanged) {
                changedIds.addAll(downloadingTrackIds);
            }
            // Fire state-only rebinds for the rows whose downloading/available state changed.
            // immediateNotifyStateChanged runs inline when the list is idle (no frame delay) and
            // only defers while a layout pass is actually in flight — no per-row Handler churn.
            for (String trackId : changedIds) {
                int index = indexOfTrackById(trackId);
                if (index >= 0 && index < getItemCount()) {
                    immediateNotifyStateChanged(index);
                }
            }

            // Deliberately NO verified disk rescan per tick. A track that just finished was already
            // seeded TRUE in offlineAvailabilityCache above (cheap hasOfflineAudio), so its checkmark
            // lands on THIS rebind. The old 80ms loadStateForVisibleRange after every 650ms progress
            // tick re-probed the whole visible range continuously — the download-scan jank. The
            // verified pass now runs once, on terminal completion (observer → refreshVisibleTrackRows).
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
            // Only the download OBSERVER seeds this cache now (a track that just finished). Scrolling
            // never triggers a disk lookup — the "escaneo de descargas" that made the first scroll
            // lag was removed; the "Descargas" library view owns downloaded-state discovery.
            return cached != null ? cached : false;
        }

        /**
         * Re-issues artwork loads for the visible rows in a single pass. Glide treats an
         * equivalent already-complete request as a no-op (it re-delivers the resource
         * synchronously, no flicker), so this only does real work for rows whose earlier
         * load failed — e.g. it fired while the connectivity check said offline — giving
         * them a retry once scrolling settles. Images now load eagerly in onBindViewHolder,
         * so this is a safety net, not the primary load path.
         */
        void reloadImagesForRange(int firstVisible, int lastVisible) {
            if (rvPlaylistContent == null) return;
            int safeStart = Math.max(0, firstVisible);
            int safeEnd = Math.min(items.size() - 1, lastVisible);
            if (safeEnd < safeStart) return;
            // Center of the visible range: the reloads are ordered outward from it and their Glide
            // priority is tiered by distance, so the decode threads don't finish the whole visible
            // batch at the same instant (the "images load in one block" symptom). The middle of the
            // screen — where the eye is — fills first.
            final int center = (safeStart + safeEnd) / 2;

            // Pass 1: gather only the rows still showing the placeholder (a load that never landed —
            // fired while offline, or skipped mid-fling). Rows already showing the correct art are
            // left untouched: re-issuing into() rebuilds the whole Glide request graph (request
            // teardown + new SingleRequest + EngineKey hashing + memory probe), which for every
            // visible row on EVERY settle was the confirmed per-re-scroll main-thread burst.
            // Compare against the SIZED url — loadTrackArt stores the CDN-sized URL as the signature,
            // so comparing the raw imageUrl would never match and the guard would silently die.
            List<Integer> toReload = new ArrayList<>();
            for (int i = safeStart; i <= safeEnd; i++) {
                RecyclerView.ViewHolder vh = rvPlaylistContent.findViewHolderForAdapterPosition(i + 1);
                if (!(vh instanceof TrackViewHolder)) continue;
                PlaylistTrack track = items.get(i);
                if (track == null || TextUtils.isEmpty(track.imageUrl)
                        || LocalFilesStore.isLocalVideoId(track.videoId)) {
                    continue;
                }
                ImageView iv = ((TrackViewHolder) vh).ivTrackArt;
                Object sig = iv.getTag(R.id.tag_artwork_signature);
                android.graphics.drawable.Drawable current = iv.getDrawable();
                boolean placeholderShowing = current == null || current == cachedTrackArtPlaceholder;
                String expectedSig = sizedTrackArtUrl(iv.getContext(), track.imageUrl.trim());
                if (expectedSig != null && expectedSig.equals(sig) && !placeholderShowing) {
                    continue;
                }
                toReload.add(i);
            }
            if (toReload.isEmpty()) return;

            // Pass 2: issue center-first, priority tiered by distance from the viewport center.
            java.util.Collections.sort(toReload, (a, b) ->
                    Integer.compare(Math.abs(a - center), Math.abs(b - center)));
            for (int idx : toReload) {
                RecyclerView.ViewHolder vh = rvPlaylistContent.findViewHolderForAdapterPosition(idx + 1);
                if (!(vh instanceof TrackViewHolder)) continue;
                PlaylistTrack track = items.get(idx);
                if (track == null || TextUtils.isEmpty(track.imageUrl)) continue;
                ImageView iv = ((TrackViewHolder) vh).ivTrackArt;
                int dist = Math.abs(idx - center);
                com.bumptech.glide.Priority priority = dist <= 2
                        ? com.bumptech.glide.Priority.HIGH
                        : (dist <= 5 ? com.bumptech.glide.Priority.NORMAL : com.bumptech.glide.Priority.LOW);
                iv.setTag(R.id.tag_artwork_signature, null);
                loadTrackArt(iv, track.imageUrl, priority);
            }
        }

        /** Warms artwork for rows just past the viewport in the scroll direction. */
        void prefetchArtFrom(int anchorIndex, int direction) {
            prefetchTrackArt(items, anchorIndex, direction, ART_PREFETCH_AHEAD);
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
            // Do NOT clear the Glide request here. Cancelling on recycle threw away every
            // in-flight thumbnail during a fast scroll, so scrolling back re-fetched them
            // all from scratch. Letting the request finish populates the memory cache, and
            // the rebind's into() both cancels the stale request and shows the placeholder,
            // so a wrong image can never land on a reused row.
            int pos = holder.getAdapterPosition();
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
            // The views above were force-reset, so the memoized state no longer matches them.
            // Without this, a rebind that computes the SAME state as before recycling hits the
            // appliedOfflineState no-op guard and leaves the check INVISIBLE on a downloaded row.
            holder.appliedOfflineState = -1;
        }

        @Override
        public void onViewAttachedToWindow(@NonNull TrackViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            // Do NOT load image here — onBindViewHolder already issues the Glide request.
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

            // Offline indicator state is collapsed to a single int so the icon/background/
            // progress-bar mutations only run when this holder's visual state actually
            // changes — most binds (especially during scroll) are no-ops here.
            int offlineState = isOfflineAvailable ? OFFLINE_STATE_AVAILABLE
                    : (isCurrentlyDownloading ? OFFLINE_STATE_DOWNLOADING : OFFLINE_STATE_NONE);
            int prevOfflineState = holder.appliedOfflineState;
            if (prevOfflineState != offlineState) {
                holder.appliedOfflineState = offlineState;

                // No persistent per-row "downloaded" indicator anymore (no check circle). While a
                // track is actively downloading we show ONLY the thin progress bar; once it finishes
                // the bar sweeps to 100% and disappears, leaving the row clean. This keeps scrolling
                // free of any per-row download-state work — "solo la barra de progreso, después nada".

                // ── Progress bar show/hide ──────────────────────────────────────────
                if (offlineState == OFFLINE_STATE_DOWNLOADING) {
                    // Entering download: the bar ALWAYS starts empty and grows from 0. Without this
                    // reset the first real fraction — already ~20-30% by the time it arrives, since a
                    // track now downloads in ~2s — snapped the bar straight there ("empieza en 20%").
                    holder.vOfflineProgressFill.animate().cancel();
                    holder.vOfflineProgressFill.setPivotX(0f);
                    holder.vOfflineProgressFill.setScaleX(0f);
                    holder.flOfflineProgress.setVisibility(View.VISIBLE);
                    holder.vOfflineProgressFill.setVisibility(View.VISIBLE);
                } else if (prevOfflineState == OFFLINE_STATE_DOWNLOADING
                        && offlineState == OFFLINE_STATE_AVAILABLE) {
                    // Finished: sweep the fill to 100%, then retract the bar as the check appears.
                    holder.vOfflineProgressFill.animate().cancel();
                    holder.vOfflineProgressFill.setPivotX(0f);
                    holder.vOfflineProgressFill.animate().scaleX(1f).setDuration(180L)
                            .setInterpolator(DECELERATE_EASE)
                            .withEndAction(() -> {
                                holder.flOfflineProgress.setVisibility(View.GONE);
                                holder.vOfflineProgressFill.setScaleX(0f);
                            }).start();
                } else {
                    holder.vOfflineProgressFill.animate().cancel();
                    holder.vOfflineProgressFill.setScaleX(0f);
                    holder.flOfflineProgress.setVisibility(View.GONE);
                }
            }

            // Live fill while downloading: forward-only + linear so the successive ~650ms progress
            // segments join into ONE continuous grow — never a backward jump, never a per-segment
            // restart. (The show/hide guard above only handles the transition, not the live value.)
            if (offlineState == OFFLINE_STATE_DOWNLOADING) {
                float target = progressForTrack(track.videoId, downloadingTrackProgressById);
                if (target > holder.vOfflineProgressFill.getScaleX() + 0.005f) {
                    holder.vOfflineProgressFill.setPivotX(0f);
                    holder.vOfflineProgressFill.animate().cancel();
                    holder.vOfflineProgressFill.animate()
                            .scaleX(target)
                            .setDuration(650L)
                            .setInterpolator(LINEAR_EASE)
                            .start();
                }
            }

            boolean isActive = position == activeIndex;
            // setBackgroundResource re-inflates the drawable every call; only touch it when
            // the active state actually changed for this holder (it runs on every bind).
            int rowBackgroundRes = isActive ? R.drawable.bg_playlist_track_active : R.drawable.bg_playlist_track_default;
            if (holder.appliedRowBackgroundRes != rowBackgroundRes) {
                holder.appliedRowBackgroundRes = rowBackgroundRes;
                holder.rootTrackRow.setBackgroundResource(rowBackgroundRes);
            }

            // Only the active row can show the now-playing overlay, so skip the player
            // queries (getLoadedVideoId / isPlaying) on every other row — a per-bind win
            // that runs on every full and state-only bind.
            boolean shouldShowOverlay = false;
            boolean isActuallyPlaying = false;
            if (isActive) {
                SongPlayerFragment sp = cachedSongPlayer;
                if (sp != null) {
                    String currentVideoId = sp.getLoadedVideoId();
                    shouldShowOverlay = !TextUtils.isEmpty(currentVideoId)
                            && TextUtils.equals(currentVideoId, track.videoId);
                    isActuallyPlaying = sp.isPlaying();
                }
            }
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
                if (position >= 0 && position < items.size()) {
                    bindTrackState(holder, position, items.get(position));
                }
                return;
            }
            onBindViewHolder(holder, position);
        }

        @Override
        public void onBindViewHolder(@NonNull TrackViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            PlaylistTrack track = items.get(position);
            holder.tvTrackTitle.setText(track.title);
            Context context = holder.itemView.getContext();

            // Image loading: local tracks show album art if available, otherwise music note icon.
            // Remote tracks ALWAYS load through Glide, even mid-fling: decode happens off the
            // main thread, memory-cache hits bind synchronously, and the grey placeholder in
            // loadTrackArt covers the in-flight gap — so scrolling never shows blank rows.
            boolean isLocalTrack = LocalFilesStore.isLocalVideoId(track.videoId);
            if (isLocalTrack) {
                // Local tracks don't go through loadTrackArt, so clear the artwork signature
                // to prevent a stale URL match if this holder is later reused for a remote track.
                holder.ivTrackArt.setTag(R.id.tag_artwork_signature, null);
                // Resolve the file's OWN embedded picture (per-track), not the unreliable
                // album-level art that bled wrong/missing covers across tracks.
                LocalArtworkResolver.loadInto(holder.ivTrackArt, track.videoId, 160);
            } else {
                // Reused row may have shown local art — invalidate its pending resolve.
                LocalArtworkResolver.detach(holder.ivTrackArt);
                holder.ivTrackArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.ivTrackArt.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                if (isFlinging) {
                    // Mid fast-fling, dozens of rows bind per second. Firing a full HIGH-priority
                    // Glide request for each (request-graph build + executor submit + crossfade
                    // wiring) is the churn that makes fast scroll stutter. Instead paint only rows
                    // already in the memory cache (synchronous, no decode); rows not yet cached keep
                    // the grey placeholder and fly past. reloadImagesForRange() issues their real
                    // loads once the list settles (IDLE), so images stream in exactly as scrolling
                    // stops — the YT-Music feel: rows fly smoothly, artwork fills in on settle.
                    loadTrackArtCacheOnly(holder.ivTrackArt, track.imageUrl);
                } else {
                    loadTrackArt(holder.ivTrackArt, track.imageUrl, com.bumptech.glide.Priority.HIGH);
                }
            }

            // NO per-row offline-state disk lookup on bind anymore. Checking each row's downloaded
            // status against disk (even the cheap file-stat) was the source of the first-scroll lag.
            // The "Descargas" library view owns that work now; rows here only reflect an ACTIVE
            // download's progress bar, driven by the WorkManager observer (not by disk scans).

            bindTrackState(holder, position, track);
        }

        final class TrackViewHolder extends RecyclerView.ViewHolder {
            final ViewGroup rootTrackRow;
            final ImageView ivTrackArt;
            final FrameLayout llNowPlayingOverlay;
            final AnimatedEqualizerView animatedEq;
            final TextView tvTrackTitle;
            final TextView tvTrackSubtitle;
            final ImageView ivMore;
            final FrameLayout flOfflineProgress;
            final View vOfflineProgressFill;
            /** Background drawable currently applied to the row; starts as the XML default. */
            int appliedRowBackgroundRes = R.drawable.bg_playlist_track_default;
            /** Offline indicator visual state currently applied to this holder (-1 = unset).
             *  Lets bindTrackState skip the icon/background/progress mutations on no-op binds. */
            int appliedOfflineState = -1;

            TrackViewHolder(@NonNull View itemView) {
                super(itemView);
                rootTrackRow = itemView.findViewById(R.id.rootTrackRow);
                ivTrackArt = itemView.findViewById(R.id.ivTrackArt);
                llNowPlayingOverlay = itemView.findViewById(R.id.llNowPlayingOverlay);
                animatedEq = itemView.findViewById(R.id.animatedEq);
                tvTrackTitle = itemView.findViewById(R.id.tvTrackTitle);
                tvTrackSubtitle = itemView.findViewById(R.id.tvTrackSubtitle);
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

