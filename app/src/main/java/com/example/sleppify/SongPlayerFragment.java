package com.example.sleppify;

import com.example.sleppify.BuildConfig;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import android.graphics.drawable.GradientDrawable;
import androidx.palette.graphics.Palette;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.sleppify.utils.YouTubeCropTransformation;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class SongPlayerFragment extends Fragment {

    private static final String TAG = "SongPlayerFragment";
    private static final YouTubeCropTransformation SHARED_YT_CROP = new YouTubeCropTransformation();

    public static final String ARG_VIDEO_IDS = "arg_video_ids";
    public static final String ARG_TITLES = "arg_titles";
    public static final String ARG_ARTISTS = "arg_artists";
    public static final String ARG_DURATIONS = "arg_durations";
    public static final String ARG_IMAGES = "arg_images";
    public static final String ARG_SELECTED_INDEX = "arg_selected_index";
    public static final String ARG_START_PLAYING = "arg_start_playing";
    public static final String ARG_IS_TEMPORARY_PLAYER = "arg_is_temporary_player";

    private static final long PROGRESS_TICK_MS = 500L;
    private static final String PREFS_PLAYER_STATE = AppConstants.PREFS_PLAYER_STATE;
    private static final String PREF_SOCIAL_STATS_PREFIX = "yt_social_stats_";
    private static final String PREF_LAST_PLAYLIST_ID = "stream_last_playlist_id";
    private static final String PREF_LAST_PLAYLIST_TITLE = "stream_last_playlist_title";
    private static final String PREF_LAST_PLAYLIST_SUBTITLE = "stream_last_playlist_subtitle";
    private static final String PREF_LAST_PLAYLIST_THUMBNAIL = "stream_last_playlist_thumbnail";
    private static final String PREF_LAST_YOUTUBE_ACCESS_TOKEN = "stream_last_youtube_access_token";
    private static final String MEDIA_NOTIFICATION_CHANNEL_ID = "sleppify_media_playback";
    private static final int MEDIA_NOTIFICATION_ID = AppConstants.MEDIA_NOTIFICATION_ID;
    private static final String TAG_PLAYLIST_DETAIL = "playlist_detail";
    private static final String TAG_MODULE_MUSIC = "module_music";
    private static final int MEDIA_SESSION_ARTWORK_MAX_SIZE_PX = 1400;
    static final int REPEAT_MODE_OFF = 0;
    static final int REPEAT_MODE_ALL = 1;
    static final int REPEAT_MODE_ONE = 2;
    private static final long AUTOPLAY_RECOVERY_DELAY_MS = 1400L;
    private static final long TRACK_ERROR_RETRY_DELAY_MS = 750L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    // 15s (antes 30s): un stream directo sano alcanza STATE_READY en pocos segundos; 30s dejaba al
    // usuario 30s en silencio ante una URL colgada antes de reaccionar. Al vencer, el reintento
    // fuerza el cliente ANDROID_VR (ver forceAltClientVideoIds), que suele reproducir donde la URL
    // de NewPipe se colgaba.
    private static final long SOURCE_PREPARE_TIMEOUT_MS = 15000L;
    private static final long SOCIAL_STATS_FETCH_DEFER_MS = 1800L;
    private static final long COMMENTS_PREFETCH_DEFER_MS = 2200L;
    private static final long PLAYBACK_BOOTSTRAP_GRACE_MS = 1800L;
    private static final int MAX_PLAYBACK_SOURCE_RETRY = 2;
    private static final long PLAYBACK_SOURCE_RETRY_DELAY_MS = 350L;
    private static final String STREAM_HTTP_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String AUDIUS_API_BASE_URL = "https://discoveryprovider.audius.co/v1";
    private static final String AUDIUS_APP_NAME = "sleppify";
    private static final int AUDIUS_SEARCH_LIMIT = 6;
    private static final int PLAYER_HERO_DEFAULT_HEIGHT_DP = 370;

    private final List<PlayerTrack> tracks = new ArrayList<>();
    private static final int MAX_NEXT_UP = 50;
    /** Filas de la hoja «A continuación» bindeadas ANTES de show(); el resto (hasta
     *  {@link #MAX_NEXT_UP}) se agrega cuando la animación de apertura ya asentó. */
    private static final int QUEUE_SHEET_INITIAL_ROWS = 12;
    private final List<PlayerTrack> nextUpTracks = new ArrayList<>();
    private final List<PlayerTrack> originalQueueOrder = new ArrayList<>();
    @Nullable
    private List<PlayerTrack> pendingOriginalQueueOrder;
    private static final int MAX_GLOBAL_HISTORY = 50;
    private final java.util.ArrayDeque<PlayerTrack> globalPlaybackHistory = new java.util.ArrayDeque<>();

    private ImageView ivPlayerCover;
    private View ivPlayerBackdrop;
    private AnimatedEqualizerView animatedEqPlayer;
    private FrameLayout flPlayerHero;
    @Nullable
    private android.widget.ProgressBar pbVideoLoading;
    // Authoritative "the CURRENTLY loaded player is presenting video" flag. Captured at each
    // player-commit point from isVideoTrack(track) — NOT re-read live from the global, videoId-keyed
    // StreamResolver.isVideoSource() cache, which can flip (mode toggle / next-track prefetch / a
    // reverted swap) while a different source is actually loaded. isVideoPresentation() reads THIS
    // for network sources so the cover/surface never disagree with what is really playing.
    private boolean currentSourceIsVideo = false;
    // True while flPlayerHero is shaped for VIDEO (full-bleed 16:9, no side margins). The song-cover
    // shaping (applyHeroShapeForAspect) insets the hero 20dp per side for square art; that inset is
    // what left the video narrow after a swap. Tracks which shape is applied so a Video→Canción swap
    // can restore the cover shape without a full artwork re-bind.
    private boolean heroShapedForVideo = false;
    // Deterministic source of truth for restoring the SONG presentation after a Video→Canción swap
    // (which does NOT re-bind the artwork): the last song cover bitmap + its dominant color, cached
    // every time a music cover/palette is computed. Lets return-to-song restore cover+color instantly
    // with no Glide/Palette recompute and no dependency on the live ImageView drawable (which a
    // runaway fade-out animator could otherwise have left blank).
    @Nullable
    private Bitmap lastSongCoverBitmap = null;
    private int lastSongDominantColor = 0xFF121212;
    private boolean lastSongColorValid = false;
    // videoId al que pertenecen lastSongCoverBitmap/lastSongDominantColor. El restore de
    // Video→Canción solo puede aplicar el cache si sigue siendo la pista cargada — sin esta
    // marca, un cambio de pista ocurrido EN modo video restauraba la carátula/color del track
    // anterior al volver a Canción.
    @Nullable
    private String lastSongArtVideoId = null;
    // True while a switch-to-video swap has committed but the first video frame has not rendered yet.
    // Keeps the song cover up as a poster over the (still-black) PlayerView so there is no black flash;
    // the swap player's first-frame listener clears it and fades the cover out.
    private boolean swapAwaitingFirstFrame = false;
    @Nullable
    private String currentVideoFilePath = null;
    @NonNull
    private final VideoSurfaceRouter videoRouter = new VideoSurfaceRouter();
    private TextView tvPlayerTitle;
    private TextView tvPlayerArtist;
    private TextView tvActionLikeCount;
    private TextView tvActionCommentCount;
    private TextView tvActionFavoriteLabel;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private View llSimilarTrigger;
    private View llQueueTrigger;
    private View llPlayerNavBar;
    private ImageButton btnPlayerClose;
    private SeekBar sbPlaybackProgress;
    @Nullable
    private android.widget.ProgressBar pbSeekBarLoading;
    @Nullable
    private android.graphics.drawable.Drawable seekBarOriginalThumb;
    private boolean seekBarThumbVisible = false;
    private static final long SEEK_THUMB_HIDE_DELAY_MS = 2000L;
    @Nullable
    private android.animation.ValueAnimator seekThumbAnimator;
    private ImageButton btnShuffle;
    private ImageButton btnRepeat;
    private View vPlayerShuffleIndicator;
    private View vPlayerRepeatIndicator;
    private ImageButton btnPlayPause;
    private View actionLike;
    private View actionDislike;
    private View actionComments;
    private View actionFavorite;
    private ImageView ivActionFavoriteIcon;
    private ImageView ivActionLikeIcon;
    private ImageView ivActionDislikeIcon;
    // Prefs key (in PREFS_PLAYER_STATE) for the set of videoIds the user has disliked. LIKE state
    // comes from "Música que te gustó" membership; DISLIKE from this local set — both reliable and
    // read synchronously, so a liked song never shows un-liked on re-entry.
    private static final String PREF_DISLIKED_VIDEO_IDS = "player_disliked_video_ids";
    private final YouTubeMusicService likeMusicService = new YouTubeMusicService();
    // Probe de disponibilidad de video musical en vuelo (una sola petición por canción).
    private String pendingCounterpartVideoId;
    private View actionRadio;
    private View actionShare;
    private View actionDownloadTrack;
    private View actionGoToArtist;
    // lastSavedPlaylistKey/Name now read from CustomPlaylistsStore (global persistent)
    private ImageView ivActionDownloadIcon;
    private TextView tvActionDownloadLabel;

    // Pastilla Audio|Video: switch del modo de reproducción (default AUDIO por sesión).
    private TextView tvModeAudio;
    private TextView tvModeVideo;
    private boolean playerVideoMode = false;
    /** Un hot-swap de modo a la vez; el toggle se ignora mientras uno está en vuelo. */
    private boolean modeSwapInProgress = false;
    @Nullable
    private ExoMediaPlayer pendingModeSwapPlayer;
    /** Adelanto de seek al preparar el player del swap: compensa el tiempo de commit. */
    private static final int HOT_SWAP_SEEK_LEAD_MS = 450;
    /** TECHO del commit del swap: el intercambio se comete apenas el player nuevo tiene
     *  {@link #HOT_SWAP_COMMIT_BUFFER_MS} de buffer por delante del objetivo (poll de 50ms);
     *  este delay solo aplica si una fuente lenta nunca llega a ese buffer. */
    private static final long HOT_SWAP_COMMIT_DELAY_MS = 400L;
    /** Buffer mínimo por delante del objetivo para considerar el player del swap listo. */
    private static final int HOT_SWAP_COMMIT_BUFFER_MS = 300;
    private static final long HOT_SWAP_TIMEOUT_MS = 12000L;
    /** Fallback: if the first video frame never renders after a switch-to-video commit (a stalled
     *  stream), stop holding the song cover as a poster after this and fade to the video anyway. A
     *  pre-buffered committed video renders its first frame well within this window. */
    private static final long SWAP_VIDEO_POSTER_TIMEOUT_MS = 3000L;
    /** Ruta rápida (C1+C2): URL de video ya conocida y cabecera precalentada → el player del
     *  swap prepara casi desde disco, así que basta con un adelanto/commit mucho menores. */
    private static final int HOT_SWAP_SEEK_LEAD_FAST_MS = 200;
    private static final long HOT_SWAP_COMMIT_DELAY_FAST_MS = 150L;
    /** ~1.5MB de cabecera del stream de video a precalentar en el exo_stream_cache compartido. */
    private static final long VIDEO_WARM_HEAD_BYTES = 1_500_000L;
    /** Tarea de precalentado (C2) de la cabecera del video en curso; cancelable. */
    @Nullable
    private ExoMediaPlayer.WarmHandle videoWarmHandle;
    /** videoId cuya cabecera de video se precalentó (C2). Informativo: la ruta rápida del
     *  hot-swap ya no depende de este flag — consulta los bytes REALES en exo_stream_cache,
     *  que cubre tanto el warm C2 como los bytes cacheados de una reproducción previa. */
    @Nullable
    private String warmedVideoId;

    private NextUpAdapter nextUpAdapter;
    @Nullable
    private ItemTouchHelper nextUpItemTouchHelper;
    // Hoja «A continuación» REUTILIZADA entre aperturas: construir el BottomSheetDialog + inflar
    // su layout en cada tap era el grueso de la latencia de apertura. Se descarta en
    // onDestroyView porque referencia el contexto/inflater de la vista vieja.
    @Nullable
    private BottomSheetDialog queueSheetDialog;
    @Nullable
    private RecyclerView rvQueueSheet;
    @Nullable
    private TextView tvEmptyQueueSheet;
    /** Precalentado (debounced) de carátulas de la cola para la hoja «A continuación». */
    @Nullable
    private Runnable nextUpPrewarmRunnable;

    @Nullable
    private OnBackPressedCallback backPressedCallback;
    @Nullable
    private ExoMediaPlayer localExoMediaPlayer;
    @Nullable
    private ExoMediaPlayer localCrossfadeIncomingPlayer;
    @Nullable
    private MediaSessionCompat mediaSession;
    @Nullable
    private SharedPreferences playerStatePrefs;
    @Nullable
    private SharedPreferences settingsPrefs;

    private boolean userSeeking = false;
    private boolean isTemporaryPlayer = false;

    private int currentIndex = 0;
    private boolean isPlaying = true;

    public boolean isPlaying() {
        return isPlaying;
    }
    private int currentSeconds = 0;
    private int totalSeconds = 1;
    private boolean isRestoringPosition = false;
    private int lastSeekTargetSeconds = -1;
    private boolean pauseRequestedByUser = false;
    private boolean appInBackground = false;
    private boolean swipeDismissGestureActive = false;
    private boolean swipeDismissAnimationRunning = false;
    private boolean playerEnterAnimationRunning = false;
    private int swipeDismissTouchSlopPx = 12;
    private int swipeDismissMinDistancePx = 120;
    private int consecutiveStreamFailures = 0;
    @Nullable
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean shuffleEnabled = false;
    private int repeatMode = REPEAT_MODE_ALL;
    private boolean collapsingToMiniMode;
    private long lastHandledEndedAtMs = 0L;
    @NonNull
    private String lastHandledEndedVideoId = "";
    private boolean playCountRecordedForCurrentTrack = false;
    @NonNull
    private String currentPlaylistContextId = "";
    @NonNull
    private String currentPlaylistContextName = "";
    @NonNull
    private String pendingDownloadVideoId = "";
    /**
     * Tracks the videoId for which we already consumed a one-shot re-resolution
     * retry after an ExoPlayer failure. If another error occurs for the same videoId,
     * we skip to the next track instead of retrying indefinitely.
     */
    @Nullable
    private String lastReresolveVideoId;
    /**
     * Tracks whether we already attempted an ExoPlayer AudioTrack reinit for the
     * current playback request token. Prevents infinite reinit loops on low-memory
     * devices where AudioFlinger cannot reclaim resources quickly enough.
     */
    private long audioTrackReinitToken = -1;
    @NonNull
    private final Random random = new Random();
    @NonNull
    private String loadedVideoId = "";
    /** Whether the actively loaded track was classified as VIDEO when its playback started.
     *  Presentation must use this pinned value for the loaded track: the stream-as-download
     *  worker can drop an .mp4 on disk mid-song, and re-evaluating disk state on a later
     *  rebind would flip the UI to video mode while the playing source has no video frames. */
    private boolean loadedTrackIsVideo = false;
    /** Cache of "does the offline file for this videoId contain a real video track" so the
     *  per-bind classification probe (MediaExtractor) runs at most once per id. Only positive/
     *  negative results for files that EXIST are cached; absent files are never cached so a later
     *  download is picked up. */
    @NonNull
    private final java.util.Map<String, Boolean> offlineVideoProbeCache = new java.util.concurrent.ConcurrentHashMap<>();

    @NonNull
    public String getLoadedVideoId() {
        return loadedVideoId;
    }
    public void externalSetPlaylistContext(@NonNull String playlistId, @NonNull String playlistName) {
        currentPlaylistContextId = playlistId;
        currentPlaylistContextName = playlistName;
    }
    @NonNull
    private String returnTargetTag = TAG_PLAYLIST_DETAIL;
    private boolean usingOfflineSource = false;
    private final Handler localProgressHandler = new Handler(Looper.getMainLooper());
    private final YouTubeMusicService radioMusicService = new YouTubeMusicService();
    private final ExecutorService streamResolverExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService backgroundDownloadExecutor = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, SocialStats> socialStatsCache = new android.util.LruCache<>(50);

    // Direct streaming & pre-fetching
    private volatile String prefetchedNextVideoId = null;
    private volatile String prefetchedNextUrl = null;
    // Mode the prefetched/pre-buffered next-track source was resolved FOR (video vs canción). A
    // pre-resolved source is only valid to adopt while it matches the CURRENT preferVideoMode —
    // otherwise switching Canción<->Video mid-track would carry the stale mode into the next track.
    private volatile boolean prefetchedNextIsVideo = false;

    // Stream-as-download: background-save the currently streaming track for offline
    private volatile String streamDownloadingVideoId = null;

    // Gapless pre-buffer: an ExoMediaPlayer prepared silently for the next track
    private static final long GAPLESS_PRE_BUFFER_LISTEN_THRESHOLD_MS = 2_000L;
    private long accumulatedListenMs = 0;
    /** Last whole second at which the periodic snapshot persist ran (see ticker). */
    private int lastSnapshotPersistSecond = -1;
    @Nullable
    private ExoMediaPlayer gaplessPreBufferedPlayer = null;
    @NonNull
    private String gaplessPreBufferedVideoId = "";
    // Mode the ready/in-flight gapless pre-buffer was resolved FOR (see prefetchedNextIsVideo).
    private boolean gaplessPreBufferedIsVideo = false;
    private boolean gaplessPreBufferingIsVideo = false;
    private boolean gaplessPreBufferTriggered = false;
    @NonNull
    private String gaplessPreBufferingVideoId = "";
    @Nullable
    private ExoMediaPlayer gaplessPreBufferingPlayer = null;

    @Nullable
    private Future<?> pendingStreamResolverFuture;
    // videoId the in-flight online stream resolution (pendingStreamResolverFuture) is for, or "".
    // Used so a rapid skip to a DIFFERENT track does not get falsely short-circuited by the
    // pending-resolution guard in playCurrentTrack().
    @NonNull
    private String pendingResolutionVideoId = "";
    @Nullable
    private Runnable sourcePrepareTimeoutRunnable;
    private boolean localSourcePreparing = false;
    private boolean localCrossfadeInProgress = false;
    private boolean localCrossfadeIsNetwork = false;
    private Context persistentAppContext;
    private int localCrossfadeTargetIndex = -1;
    private long localCrossfadeStartedAtMs = 0L;
    @Nullable
    private Runnable localCrossfadeTicker;
    @NonNull
    private final CrossfadeManager crossfadeManager = new CrossfadeManager();

    private final CrossfadeManager.Callback crossfadeCallback = new CrossfadeManager.Callback() {
        @Override
        public void onCrossfadeFinished(@NonNull ExoMediaPlayer incomingPlayer, int nextIndex, boolean wasNetwork) {
            handleCrossfadeFinished(incomingPlayer, nextIndex, wasNetwork);
        }
        @Override
        public void onFadeOutFinished() {
            // The manager already released the outgoing player — which was our
            // localExoMediaPlayer. Drop the stale reference and run the normal
            // end-of-track path so the UI leaves the "playing" state (it used to stay
            // stuck on a released player with the pause icon showing).
            stopLocalProgressTicker();
            releaseLocalExoMediaPlayer();
            handleTrackEnded();
        }
        @Override
        public void onCrossfadeFailed(int nextIndex) {
            // The failed crossfade did not commit any state: the current track keeps
            // playing at full volume and natural completion advances the queue (the old
            // behavior restarted the current song from zero near its end). If the track
            // already ended while the failed fade ran (its completion event was swallowed
            // by the isInProgress guard), advance now.
            Log.w(TAG, "Crossfade failed for nextIndex=" + nextIndex + " — continuing without crossfade");
            ExoMediaPlayer p = localExoMediaPlayer;
            boolean ended = p == null;
            if (p != null) {
                try {
                    int dur = p.getDuration();
                    ended = !p.isPlaying() && dur > 0 && p.getCurrentPosition() >= dur - 250;
                } catch (Exception ignored) { }
            }
            if (ended) {
                // Consume this player's end before advancing: ExoMediaPlayer re-reads the
                // listener field at dispatch time, so nulling it here also kills the
                // pending ENDED post for the same end event — otherwise it would land
                // after playCurrentTrack switched tracks and advance a second time,
                // skipping the just-loaded track.
                if (p != null) {
                    p.setOnCompletionListener(null);
                }
                stopLocalProgressTicker();
                handleTrackEnded();
            }
        }
    };
    private long lastPlaybackStartRequestAtMs = 0L;
    private long lastSnapshotDispatchedAtMs = 0L;
    private static final long SNAPSHOT_DEBOUNCE_MS = 300L;
    private long lastPrevPressAtMs = 0L;
    private static final long PREV_DOUBLE_TAP_WINDOW_MS = 3000L;
    private static final int PREV_RESTART_THRESHOLD_SECONDS = 3;
    private long activePlaybackRequestToken = 0L;
    @Nullable
    private Runnable autoplayRecoveryRunnable;
    @NonNull
    private String autoplayRecoveryVideoId = "";
    @Nullable
    private Runnable playbackErrorRetryRunnable;
    @NonNull
    private String playbackErrorRetryVideoId = "";
    @NonNull
    private String lastErroredVideoId = "";
    private int sameTrackErrorCount = 0;
    private int playerEngineRecoveryAttempts = 0;
    private final Set<String> audiusFallbackAttemptedVideoIds = new HashSet<>();
    @NonNull
    private String pendingSocialStatsVideoId = "";
    @Nullable
    private Runnable pendingSocialStatsFetchRunnable;
    @Nullable
    private Runnable pendingCommentsPrefetchRunnable;

    private View playerBackgroundContainer;
    /** Bumped on every cover bind. Async artwork deliveries (Glide CustomTarget, Palette)
     *  capture the value at submit time and no-op if it changed — without this, a late
     *  result from a previous track repainted the cover OVER a playing video, applied the
     *  wrong hero aspect ratio, or recolored the backdrop with another song's palette. */
    private int playerArtworkGeneration = 0;
    /** Generation whose artwork-derived hero ratio is currently applied (see
     *  updatePlayerSurfaceForSource — it must not stomp the artwork ratio). */
    private int heroRatioAppliedGeneration = -1;
    /** In-flight cover target so a new bind can cancel the previous load. */
    @Nullable
    private com.bumptech.glide.request.target.CustomTarget<Bitmap> playerCoverTarget;
    @Nullable
    private Runnable nextUpRevealRunnable;
    private int nextUpRevealCursor = 0;
    private boolean playerArtworkBootstrapPending = true;
    @Nullable
    private Bitmap mediaSessionArtwork;
    @NonNull
    private String mediaSessionArtworkVideoId = "";
    
    private final PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO);

    // Last values actually rendered into the time labels / seekbar, so the 2Hz ticker skips the
    // String.format + setText + layout when the displayed value hasn't changed (the current-time
    // label only changes once per second, the total-time label almost never). Reset to -1 on bind
    // so a new track always re-renders. -1 = "nothing rendered yet".
    private int lastRenderedCurrentSeconds = -1;
    private int lastRenderedTotalSeconds = -1;
    private int lastRenderedProgress = -1;
    private int lastRenderedBuffered = -1;

    private final Runnable localProgressTicker = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || localExoMediaPlayer == null || userSeeking) {
                localProgressHandler.removeCallbacks(this);
                return;
            }

            try {
                int positionMs = Math.max(0, localExoMediaPlayer.getCurrentPosition());
                int durationMs = Math.max(1, localExoMediaPlayer.getDuration());

                accumulatedListenMs += PROGRESS_TICK_MS;
                maybeStartGaplessPreBuffer(positionMs, durationMs);
                // Crossfade only in Canción mode: it overlaps two players, but the video surface can
                // attach to only ONE, so a crossfade in Video mode would drop the picture on the
                // incoming track. In Video mode the gapless/completion path does a clean video->video
                // cut instead (the pre-buffer is already resolved for video). Keep ticking an
                // ALREADY-running crossfade (e.g. mode flipped mid-fade) so it finishes instead of
                // freezing at partial volume; only STARTING a new crossfade is gated.
                if (!StreamResolver.isPreferVideoMode() || crossfadeManager.isInProgress()) {
                    crossfadeManager.onProgressTick(
                            positionMs, durationMs,
                            localExoMediaPlayer,
                            isPlaying,
                            localSourcePreparing,
                            userSeeking,
                            tracks,
                            currentIndex,
                            repeatMode,
                            isNetworkAvailable(),
                            gaplessPreBufferedPlayer,
                            gaplessPreBufferedVideoId,
                            prefetchedNextVideoId,
                            prefetchedNextUrl
                    );
                }
                // If a crossfade started, resolve the fate of the pre-buffered player.
                if (crossfadeManager.isInProgress() && gaplessPreBufferedPlayer != null) {
                    if (!crossfadeManager.isUsingPlayer(gaplessPreBufferedPlayer)) {
                        // The crossfade went with a different source — release the stale
                        // pre-buffered player. Just dropping the reference (old behavior)
                        // leaked a live, buffering ExoPlayer instance.
                        releaseSingleExoMediaPlayer(gaplessPreBufferedPlayer);
                    }
                    gaplessPreBufferedPlayer = null;
                    gaplessPreBufferedVideoId = "";
                    gaplessPreBufferTriggered = false;
                }

                int playerSeconds = positionMs / 1000;

                // Position Guard: If we are restoring a position, don't let a "0" from the player
                // (which happens during prep/buffer) overwrite our target until the player actually advances.
                if (isRestoringPosition) {
                    if (playerSeconds > 0 || (lastSeekTargetSeconds <= 0)) {
                        // Player has advanced or we didn't have a target anyway
                        currentSeconds = playerSeconds;
                        isRestoringPosition = false;
                    } else {
                        // Keep our target currentSeconds for now, don't update from player
                    }
                } else {
                    currentSeconds = playerSeconds;
                }

                totalSeconds = Math.max(1, durationMs / 1000);

                // Only update UI if player is visible - reduces GPU/CPU load when hidden.
                // Skip the format+setText+layout when the rendered value is unchanged: the ticker
                // fires 2x/second but the second value changes only 1x/second, and the total-time
                // label is effectively constant per track.
                // appInBackground: isHidden() only covers mini-player mode — when the app is
                // backgrounded with the full player on screen, these writes hit views nobody
                // can see, twice a second, for hours.
                if (!isHidden() && !appInBackground) {
                    if (currentSeconds != lastRenderedCurrentSeconds) {
                        lastRenderedCurrentSeconds = currentSeconds;
                        tvCurrentTime.setText(formatSeconds(currentSeconds));
                    }
                    if (totalSeconds != lastRenderedTotalSeconds) {
                        lastRenderedTotalSeconds = totalSeconds;
                        tvTotalTime.setText(formatSeconds(totalSeconds));
                    }
                    int progress = Math.round((Math.max(0, currentSeconds) / (float) Math.max(1, totalSeconds)) * 1000f);
                    progress = Math.max(0, Math.min(1000, progress));
                    if (progress != lastRenderedProgress) {
                        lastRenderedProgress = progress;
                        sbPlaybackProgress.setProgress(progress);
                    }
                    // Buffered line (secondaryProgress), YT-style. Offline/local sources are
                    // fully on disk, so the line goes straight to the end.
                    int buffered = usingOfflineSource ? 1000
                            : Math.round(localExoMediaPlayer.getBufferedPosition() / (float) durationMs * 1000f);
                    buffered = Math.max(progress, Math.min(1000, buffered));
                    if (buffered != lastRenderedBuffered) {
                        lastRenderedBuffered = buffered;
                        sbPlaybackProgress.setSecondaryProgress(buffered);
                    }
                }

                if (!playCountRecordedForCurrentTrack && totalSeconds > 0
                        && currentSeconds >= Math.min(30, Math.max(1, totalSeconds / 2))) {
                    playCountRecordedForCurrentTrack = true;
                    if (isAdded() && currentIndex >= 0 && currentIndex < tracks.size()) {
                        PlayerTrack _t = tracks.get(currentIndex);
                        PlayCountStore.incrementPlayCount(
                                requireContext(),
                                _t.videoId, _t.title, _t.artist, _t.imageUrl,
                                currentPlaylistContextId.isEmpty() ? null : currentPlaylistContextId,
                                currentPlaylistContextName.isEmpty() ? null : currentPlaylistContextName
                        );
                        CloudSyncManager.getInstance(requireContext()).syncPlayCountsToCloud(requireContext());
                        ListenHistoryStore.record(requireContext(), _t.videoId, _t.title, _t.artist, _t.imageUrl);
                    }
                }
                // Track the last persisted second so the snapshot fires once per matching second
                // (the ticker runs 2x/second). In background stretch the cadence: the MediaSession
                // extrapolates position from speed on its own, and persisting every 5s all night
                // is pure churn.
                int persistEverySeconds = appInBackground ? 30 : 5;
                if (currentSeconds % persistEverySeconds == 0 && currentSeconds != lastSnapshotPersistSecond) {
                    lastSnapshotPersistSecond = currentSeconds;
                    persistPlaybackSnapshot(false);
                    updateMediaSessionState();
                }
            } catch (Exception e) {
                Log.w(TAG, "Progress ticker error", e);
            }

            localProgressHandler.postDelayed(this, 500L);
        }
    };


    @NonNull
    public static SongPlayerFragment newInstance(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex
    ) {
        return newInstance(videoIds, titles, artists, durations, images, selectedIndex, true, false);
    }

    @NonNull
    public static SongPlayerFragment newInstance(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex,
            boolean startPlaying
    ) {
        return newInstance(videoIds, titles, artists, durations, images, selectedIndex, startPlaying, false);
    }

    @NonNull
    public static SongPlayerFragment newInstance(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex,
            boolean startPlaying,
            boolean isTemporaryPlayer
    ) {
        SongPlayerFragment fragment = new SongPlayerFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_VIDEO_IDS, videoIds);
        args.putStringArrayList(ARG_TITLES, titles);
        args.putStringArrayList(ARG_ARTISTS, artists);
        args.putStringArrayList(ARG_DURATIONS, durations);
        args.putStringArrayList(ARG_IMAGES, images);
        args.putInt(ARG_SELECTED_INDEX, selectedIndex);
        args.putBoolean(ARG_START_PLAYING, startPlaying);
        args.putBoolean(ARG_IS_TEMPORARY_PLAYER, isTemporaryPlayer);
        fragment.setArguments(args);
        return fragment;
    }



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        persistentAppContext = requireContext().getApplicationContext();
        if (savedInstanceState != null) {
            // Prevent auto-playback when restoring from a crash/process death
            isPlaying = false;
        }
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // Notification removed
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_song_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.setTranslationY(0f);
        view.setAlpha(1f);

        persistentAppContext = requireContext().getApplicationContext();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setContainerOverlayMode(true);
        }

        // ✅ CRITICAL: Only findViewById here (fast path)
        ivPlayerCover = view.findViewById(R.id.ivPlayerCover);
        // ivPlayerBackdrop now only used as container for background color
        ivPlayerBackdrop = view.findViewById(R.id.ivPlayerBackdrop);
        playerBackgroundContainer = ivPlayerBackdrop;
        animatedEqPlayer = view.findViewById(R.id.animatedEqPlayer);
        flPlayerHero = view.findViewById(R.id.flPlayerHero);
        pbVideoLoading = view.findViewById(R.id.pbVideoLoading);
        // Init video router with hero + mini containers
        {
            FrameLayout miniContainer = null;
            if (getActivity() instanceof MainActivity) {
                View miniRoot = ((MainActivity) getActivity()).findViewById(R.id.llGlobalMiniPlayer);
                if (miniRoot != null) {
                    miniContainer = miniRoot.getRootView().findViewById(R.id.flMiniPlayerVideoContainer);
                }
            }
            videoRouter.init(requireContext(), flPlayerHero, miniContainer);
            videoRouter.setCallback(() -> updatePlayerSurfaceForSource());
        }
        playerArtworkBootstrapPending = true;
        tvPlayerTitle = view.findViewById(R.id.tvPlayerTitle);
        tvPlayerTitle.setSelected(true);
        tvPlayerArtist = view.findViewById(R.id.tvPlayerArtist);
        actionLike = view.findViewById(R.id.actionLike);
        actionDislike = view.findViewById(R.id.actionDislike);
        actionComments = view.findViewById(R.id.actionComments);
        actionFavorite = view.findViewById(R.id.actionFavorite);
        actionRadio = view.findViewById(R.id.actionRadio);
        actionShare = view.findViewById(R.id.actionShare);
        actionDownloadTrack = view.findViewById(R.id.actionDownloadTrack);
        actionGoToArtist = view.findViewById(R.id.actionGoToArtist);
        ivActionDownloadIcon = view.findViewById(R.id.ivActionDownloadIcon);
        tvActionDownloadLabel = view.findViewById(R.id.tvActionDownloadLabel);
        tvActionLikeCount = view.findViewById(R.id.tvActionLikeCount);
        tvActionCommentCount = view.findViewById(R.id.tvActionCommentCount);
        ivActionLikeIcon = view.findViewById(R.id.ivActionLikeIcon);
        ivActionDislikeIcon = view.findViewById(R.id.ivActionDislikeIcon);
        tvActionFavoriteLabel = view.findViewById(R.id.tvActionFavoriteLabel);
        ivActionFavoriteIcon = view.findViewById(R.id.ivActionFavoriteIcon);
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime);
        tvTotalTime = view.findViewById(R.id.tvTotalTime);
        sbPlaybackProgress = view.findViewById(R.id.sbPlaybackProgress);
        pbSeekBarLoading = view.findViewById(R.id.pbSeekBarLoading);
        seekBarOriginalThumb = null; // unused — thumb visibility controlled via tint alpha
        btnShuffle = view.findViewById(R.id.btnShuffle);
        btnRepeat = view.findViewById(R.id.btnRepeat);
        vPlayerShuffleIndicator = view.findViewById(R.id.vPlayerShuffleIndicator);
        vPlayerRepeatIndicator = view.findViewById(R.id.vPlayerRepeatIndicator);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        llQueueTrigger = view.findViewById(R.id.llQueueTrigger);
        llQueueTrigger.setOnClickListener(v -> showQueueBottomSheet());
        llPlayerNavBar = view.findViewById(R.id.llPlayerNavBar);
        btnPlayerClose = view.findViewById(R.id.btnPlayerClose);
        if (btnPlayerClose != null) {
            btnPlayerClose.setOnClickListener(v -> collapseToMiniMode(true));
        }

        // Pastilla Audio|Video. El modo vive en StreamResolver (sesión completa), así una
        // recreación del fragment (rotación, restauración) no lo desincroniza del stream real.
        tvModeAudio = view.findViewById(R.id.tvModeAudio);
        tvModeVideo = view.findViewById(R.id.tvModeVideo);
        if (tvModeAudio != null) tvModeAudio.setOnClickListener(v -> onPlaybackModeSelected(false));
        if (tvModeVideo != null) tvModeVideo.setOnClickListener(v -> onPlaybackModeSelected(true));
        playerVideoMode = StreamResolver.isPreferVideoMode();
        // Candado "No reproducir videos": oculta la pastilla y desarma el modo video si el
        // ajuste está activo (p.ej. quedó armado de una sesión anterior o de otro dispositivo).
        applyNoMusicVideosSetting();
        updatePlaybackModePillUi();

        // Apply status-bar inset to the internal nav bar so buttons sit below the status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            if (llPlayerNavBar != null) {
                llPlayerNavBar.setPadding(
                    llPlayerNavBar.getPaddingLeft(),
                    statusBarHeight,
                    llPlayerNavBar.getPaddingRight(),
                    llPlayerNavBar.getPaddingBottom()
                );
            }
            return insets;
        });
        llSimilarTrigger = view.findViewById(R.id.llSimilarTrigger);
        if (llSimilarTrigger != null) {
            llSimilarTrigger.setOnClickListener(v -> openRadioForCurrentTrack());
        }

        // Wire the swipe-to-dismiss gesture SYNCHRONOUSLY (not deferred to phase1 below). It only
        // reads ViewConfiguration + display metrics, so it's cheap. Deferring it via view.post()
        // left a window — especially after a process-death restore triggered by backgrounding the
        // app — where the SwipeInterceptLayout callback was still null and the swipe "no agarraba".
        setupSwipeToDismiss(view);

        // When created hidden (mini-player background restore), skip the entry animation
        // delay and run both phases faster to start playback sooner.
        final boolean createdHidden = isHidden();

        // ✅ PHASE 1: Lightweight UI wiring only — runs during first frame, no heavy I/O
        Runnable phase1 = () -> {
            if (!isAdded()) return;

            setupBackPressToMiniMode();
            view.findViewById(R.id.btnPrev).setOnClickListener(v -> moveTrack(-1));
            view.findViewById(R.id.btnNext).setOnClickListener(v -> moveTrack(1));
            btnShuffle.setOnClickListener(v -> toggleShuffleMode());
            btnRepeat.setOnClickListener(v -> cycleRepeatMode());
            btnPlayPause.setOnClickListener(v -> togglePlayback());

            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    if (seekBarThumbVisible && sbPlaybackProgress != null) {
                        int[] loc = new int[2];
                        sbPlaybackProgress.getLocationOnScreen(loc);
                        float x = event.getRawX(), y = event.getRawY();
                        boolean overSeekBar = x >= loc[0] && x <= loc[0] + sbPlaybackProgress.getWidth()
                                && y >= loc[1] && y <= loc[1] + sbPlaybackProgress.getHeight();
                        if (!overSeekBar) {
                            hideSeekBarThumb();
                        }
                    }
                }
                return false;
            });

            sbPlaybackProgress.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    showSeekBarThumb();
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    scheduleSeekBarThumbHide();
                }
                return false;
            });

            sbPlaybackProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || totalSeconds <= 0) {
                        return;
                    }
                    currentSeconds = Math.max(0, Math.min(totalSeconds, Math.round((progress / 1000f) * totalSeconds)));
                    tvCurrentTime.setText(formatSeconds(currentSeconds));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    userSeeking = true;
                    showSeekBarThumb();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    userSeeking = false;
                    scheduleSeekBarThumbHide();
                    lastSnapshotPersistSecond = -1;
                    // Solo el crossfade: un seek dentro de la pista actual NO invalida el
                    // pre-buffer gapless del siguiente track. Liberarlo aquí (release de Media3,
                    // hasta ~500ms en main) congelaba el frame del gesto, y el ticker lo
                    // recreaba medio segundo después — thrash en cada seek.
                    cancelCrossfadeOnly();
                    if (localExoMediaPlayer != null) {
                        try {
                            if (pbVideoLoading != null && !usingOfflineSource && isVideoTrackId(loadedVideoId)) {
                                pbVideoLoading.setVisibility(View.VISIBLE);
                            }
                            if (!usingOfflineSource && !loadedVideoId.isEmpty()) {
                                PlaybackLoadingBus.notifyLoadingStarted(loadedVideoId);
                            }
                            localExoMediaPlayer.seekTo(currentSeconds * 1000);
                        } catch (Exception e) {
                            Log.w(TAG, "Seek failed", e);
                        }
                    }
                    if (isPlaying) {
                        ensureActivePlaybackIfExpected("seek-bar-seekTo");
                    }
                }
            });
        };

        // ✅ PHASE 2a: Data/I-O only — prefs, playback modes, queue hydration and the stream/
        // offline pre-resolución. Sin mutaciones de vista, así puede correr DURANTE la animación
        // de entrada: la resolución NewPipe (~0.5-3s) y los parseos del archivo offline se
        // solapan con la animación en vez de arrancar recién cuando termina.
        Runnable phase2a = () -> {
            if (!isAdded()) return;

            playerStatePrefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
            settingsPrefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE);
            loadPlaybackModesFromSettings();
            hydrateTracksFromArgs();
            currentIndex = Math.max(0, Math.min(currentIndex, tracks.size() - 1));
            preResolveCurrentTrackSource();
            // Pinta título/artista/tiempos del hero YA (mientras el reproductor se desliza), en vez
            // de esperar a phase2 (+320ms) y verse en blanco durante toda la animación de entrada.
            bindHeroPresentationEarly();
        };

        // ✅ PHASE 2b: Heavy/view work — crossfade attach, MediaSession, bind, playback. On the
        // visible path this stays behind the 320ms entry-animation guard (low-end devices jank
        // if the bind/Glide burst lands mid-animation), but by then phase2a already has the
        // source resolution in flight or cached.
        Runnable phase2 = () -> {
            if (!isAdded()) return;

            crossfadeManager.attach(
                    requireContext().getApplicationContext(),
                    settingsPrefs,
                    crossfadeCallback,
                    (ctx, videoId) -> StreamResolver.resolveStreamUrl(ctx, videoId),
                    streamResolverExecutor
            );
            crossfadeManager.invalidateDurationCache();
            setupSocialActions();
            updatePlaybackModeButtons();

            setupMediaSession();

            bindCurrentTrack(true);
            playCurrentTrack();
        };

        if (createdHidden) {
            // Hidden path (mini-player restore): no entry animation, run immediately
            phase1.run();
            view.post(() -> {
                phase2a.run();
                phase2.run();
            });
        } else {
            // Visible path: view wiring + data/pre-resolve immediately; heavy view work deferred
            // so the entry animation stays smooth while network/disk already run underneath.
            view.post(phase1);
            view.post(phase2a);
            view.postDelayed(phase2, 320L);
        }
    }

    @Override
    public void onPause() {
        appInBackground = true;
        updateBackPressedCallbackEnabled(true);
        stopLocalProgressTicker();
        persistPlaybackSnapshot(false);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        updateBackPressedCallbackEnabled(hidden);
        if (hidden) {
            swipeDismissGestureActive = false;
            swipeDismissAnimationRunning = false;
            // Ticker must keep running in mini mode so crossfade can trigger.
            // UI updates inside the ticker are already guarded by isHidden().
            // Reparent video surface to mini-player
            videoRouter.onPlayerHidden();
        } else {
            if (getView() != null) {
                if (!playerEnterAnimationRunning) {
                    getView().animate().cancel();
                    getView().setTranslationY(0f);
                }
                getView().setVisibility(View.VISIBLE);
            }
            // Reparent video surface back to hero (delay if mini-player is still animating out)
            MainActivity ma = (getActivity() instanceof MainActivity) ? (MainActivity) getActivity() : null;
            GlobalMiniPlayerController miniCtrl = (ma != null) ? ma.getGlobalMiniPlayer() : null;
            if (miniCtrl != null && miniCtrl.isAnimatingOut()) {
                View root = getView();
                if (root != null) root.postDelayed(() -> videoRouter.onPlayerVisible(), 260);
            } else {
                videoRouter.onPlayerVisible();
            }
            // ✅ Restart ticker when visible if playing
            if (isPlaying) {
                startLocalProgressTicker();
            }
            ensureActivePlaybackIfExpected("onHiddenChanged-visible");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean returningFromBackground = appInBackground;
        swipeDismissGestureActive = false;
        swipeDismissAnimationRunning = false;
        if (getView() != null && !isHidden()) {
            // A legitimate entry animation only runs on a fresh open, never on a background return.
            // So when returning from background, force the player back to rest even if
            // playerEnterAnimationRunning got stuck true (its withEndAction doesn't fire if the
            // animation was cancelled by backgrounding) — otherwise the view could stay translated
            // off-rest and the swipe-to-dismiss target lands in the wrong place ("no agarra").
            if (returningFromBackground || !playerEnterAnimationRunning) {
                playerEnterAnimationRunning = false;
                getView().animate().cancel();
                getView().setTranslationY(0f);
            }
            getView().setVisibility(View.VISIBLE);
        }
        appInBackground = false;
        if (crossfadeManager != null) crossfadeManager.invalidateDurationCache();
        updateBackPressedCallbackEnabled(isHidden());
        ensureActivePlaybackIfExpected("onResume");
        persistPlaybackSnapshot(false);
    }



    private void ensureActivePlaybackIfExpected(@NonNull String reason) {
        if (!isPlaying) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        boolean bootstrapWindow = (now - lastPlaybackStartRequestAtMs) < PLAYBACK_BOOTSTRAP_GRACE_MS;
        Log.d(TAG, "[PLAYBACK_DBG] ensureActive reason=" + reason + " localPlayer=" + (localExoMediaPlayer != null) + " preparing=" + localSourcePreparing + " bootstrap=" + bootstrapWindow + " pending=" + hasPendingStreamResolution());

        if (localExoMediaPlayer != null) {
            if (localSourcePreparing) {
                // Player is still preparing — onPrepared will call start() when ready.
                // Don't call start() prematurely as it sets playWhenReady without syncing isPlaying.
                return;
            }

            boolean alreadyPlaying = false;
            try {
                alreadyPlaying = localExoMediaPlayer.isPlaying();
            } catch (Exception e) {
                Log.w(TAG, "isPlaying() check failed", e);
            }

            if (alreadyPlaying) {
                startLocalProgressTicker();
                return;
            }

            try {
                localExoMediaPlayer.start();
                startLocalProgressTicker();
            } catch (Exception e) {
                if (localSourcePreparing || bootstrapWindow) {
                    return;
                }
                // If the player instance still exists, it is in a transient state (e.g. MIUI
                // paused the fragment briefly). Calling playCurrentTrack() here would reset
                // currentSeconds to 0 and restart the song from scratch — that is the bug.
                // Instead, schedule a short retry; if the player truly died the retry will
                // find localExoMediaPlayer == null and reload cleanly.
                if (localExoMediaPlayer != null) {
                    Log.w(TAG, "ensureActivePlaybackIfExpected: start() failed but player alive, scheduling retry. reason=" + reason, e);
                    final String retryReason = reason;
                    localProgressHandler.postDelayed(() -> ensureActivePlaybackIfExpected("retry-" + retryReason), 500L);
                    return;
                }
                Log.w(TAG, "ensureActivePlaybackIfExpected: player lost, not restarting automatically. reason=" + reason, e);
            }
            return;
        }

        if (!hasPendingStreamResolution()) {
            if (bootstrapWindow || localSourcePreparing) {
                return;
            }
            Log.d(TAG, "ensureActivePlaybackIfExpected: player missing, waiting for user action. reason=" + reason);
        }
    }

    private boolean isEffectivePlaying() {
        if (!isPlaying) {
            return false;
        }
        if (localExoMediaPlayer != null) {
            try {
                return localExoMediaPlayer.isPlaying();
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        if (!isTemporaryPlayer) {
            persistPlaybackSnapshot(true);
        }
        cancelAutoplayRecovery();
        cancelPlaybackErrorRetry();
        cancelNextUpReveal();
        cancelPendingSocialStatsFetch();
        cancelPendingStreamResolver();
        // La hoja de cola reutilizada referencia el contexto/inflater de ESTA vista — se
        // descarta entera; la próxima vista la reconstruye en su primera apertura.
        if (nextUpPrewarmRunnable != null) {
            localProgressHandler.removeCallbacks(nextUpPrewarmRunnable);
            nextUpPrewarmRunnable = null;
        }
        if (queueSheetDialog != null) {
            try { queueSheetDialog.dismiss(); } catch (Exception ignored) { }
            queueSheetDialog = null;
        }
        rvQueueSheet = null;
        tvEmptyQueueSheet = null;
        nextUpAdapter = null;
        nextUpItemTouchHelper = null;
        // Un hot-swap de modo Audio/Video a medio preparar no debe filtrar su player.
        if (pendingModeSwapPlayer != null) {
            try { pendingModeSwapPlayer.release(); } catch (Exception ignored) { }
            pendingModeSwapPlayer = null;
            modeSwapInProgress = false;
        }
        // Abortar cualquier precalentado de cabecera de video en vuelo (C2).
        cancelVideoStreamWarm();
        // Release video surface before destroying view
        videoRouter.onPlayerReleased();
        stopLocalProgressTicker();
        
        // Si es un reproductor temporal (e.g., en SearchActivity), NO liberar el player
        // Mantenerlo en su estado actual para que la reproducción continúe
        if (!isTemporaryPlayer) {
            releaseLocalExoMediaPlayer();
        } else {
            // Temporary players keep localExoMediaPlayer alive (handed off to keep playback
            // going), so releaseLocalExoMediaPlayer() — and thus cancelOfflineCrossfade() ->
            // cancelGaplessPreBuffer() — is skipped here, leaking any fragment-scoped gapless
            // pre-buffer player. Release it explicitly; it never touches the visible player.
            cancelGaplessPreBuffer();
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setContainerOverlayMode(false);
        }
        releaseMediaSession();
        clearMediaNotificationArtwork();
        if (backPressedCallback != null) {
            backPressedCallback.remove();
            backPressedCallback = null;
        }
        crossfadeManager.destroy();
        settingsPrefs = null;
        // Release the cached song cover reference (Glide owns the bitmap; do NOT recycle it). The
        // view is recreated on the same retained instance, so a fresh bind repopulates the cache.
        lastSongCoverBitmap = null;
        lastSongArtVideoId = null;
        lastSongColorValid = false;
        swapAwaitingFirstFrame = false;
        flPlayerHero = null;
        if (seekThumbAnimator != null) { seekThumbAnimator.cancel(); seekThumbAnimator = null; }
        sbPlaybackProgress = null;
        pbSeekBarLoading = null;
        // NOTE: the stream/background executors are final, instance-scoped fields shut down in
        // onDestroy() — NOT here. This fragment is retained and its view is add/hide/show'd, so a
        // config change (rotation, resize, font/locale change) runs onDestroyView then recreates
        // the view on the SAME instance; shutting the executors down here left them permanently
        // dead and the next skip/seek/resolve threw RejectedExecutionException.
        super.onDestroyView();
    }


    private void setupMediaSession() {
        if (!isAdded()) {
            return;
        }

        if (mediaSession != null) {
            mediaSession.release();
        }

        mediaSession = new MediaSessionCompat(requireContext().getApplicationContext(), "SleppifySongSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (tracks.isEmpty()) {
                    return;
                }

                pauseRequestedByUser = false;
                isPlaying = true;
                ensureActivePlaybackIfExpected("media-session-play");
                updatePlayPauseIcon();
                updateMediaSessionState();
                updateMediaNotification();
                persistPlaybackSnapshot(false);
            }

            @Override
            public void onPause() {
                pauseRequestedByUser = true;
                // Cancel any armed/in-flight crossfade: without this, a transition resolving
                // in the background would begin (or finish) the fade against the paused
                // player and force playback back on.
                cancelOfflineCrossfade();
                if (localExoMediaPlayer != null) {
                    try {
                        localExoMediaPlayer.pause();
                        stopLocalProgressTicker();
                    } catch (Exception e) {
                        Log.w(TAG, "Media session pause failed", e);
                    }
                }
                isPlaying = false;
                updatePlayPauseIcon();
                updateMediaSessionState();
                updateMediaNotification();
                persistPlaybackSnapshot(false);
            }

            @Override
            public void onSkipToNext() {
                moveTrack(1);
            }

            @Override
            public void onSkipToPrevious() {
                moveTrack(-1);
            }

            @Override
            public void onSetShuffleMode(int shuffleMode) {
                // SET to the requested state (idempotent) — do NOT toggle. System controllers
                // (Bluetooth/AVRCP, Android Auto, the media notification, media-resumption) call
                // this to SYNC shuffle state; toggling here flipped shuffle "by itself" on every
                // such sync. setShuffleEnabled already no-ops when the state is unchanged.
                setShuffleEnabled(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE);
            }

            @Override
            public void onSeekTo(long pos) {
                currentSeconds = Math.max(0, (int) (pos / 1000L));
                lastSnapshotPersistSecond = -1;
                // Igual que el seek de la seekbar: preservar el pre-buffer gapless (ver
                // cancelCrossfadeOnly) — un seek no cambia cuál es el siguiente track.
                cancelCrossfadeOnly();
                if (localExoMediaPlayer != null) {
                    try {
                        if (!usingOfflineSource && pbVideoLoading != null && isVideoTrackId(loadedVideoId)) {
                            pbVideoLoading.setVisibility(View.VISIBLE);
                        }
                        if (!usingOfflineSource && !loadedVideoId.isEmpty()) {
                            PlaybackLoadingBus.notifyLoadingStarted(loadedVideoId);
                        }
                        localExoMediaPlayer.seekTo(currentSeconds * 1000);
                    } catch (Exception e) {
                        Log.w(TAG, "Media session seek failed", e);
                    }
                }
                if (isPlaying) {
                    ensureActivePlaybackIfExpected("media-session-seek");
                }
                tvCurrentTime.setText(formatSeconds(currentSeconds));
                int progress = Math.round((Math.max(0, currentSeconds) / (float) Math.max(1, totalSeconds)) * 1000f);
                sbPlaybackProgress.setProgress(Math.max(0, Math.min(1000, progress)));
                updateMediaSessionState();
            }
        });
        // Advertise the loaded shuffle state so controllers start in sync.
        publishShuffleModeToSession(shuffleEnabled);
        // Initialize the playback state immediately so the system controller (notification,
        // lock screen, Android Auto) knows whether we are playing or paused before the user
        // taps any button; otherwise a stale/undefined state can make the system log/send
        // the opposite action (e.g., MediaSessionRecord:pause) when the user pressed play.
        updateMediaSessionState();
        ensureMediaNotificationChannel();
    }

    private void releaseMediaSession() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    private void moveTrack(int delta) {
        moveTrack(delta, false, false);
    }

    private void moveTrack(int delta, boolean fromCompletion) {
        moveTrack(delta, fromCompletion, false);
    }

    private void moveTrack(int delta, boolean fromCompletion, boolean skipRestartCheck) {
        if (tracks.isEmpty()) {
            return;
        }

        if (!fromCompletion) {
            consecutiveStreamFailures = 0; // Manual track change by user resets the loop protector
            cancelOfflineCrossfade();

            // Spotify/YT Music behavior for "previous" button:
            // - If current position > threshold OR it's not a double-tap: restart current song
            // - If pressed again within PREV_DOUBLE_TAP_WINDOW_MS: go to previous track
            if (delta == -1 && !skipRestartCheck) {
                long now = SystemClock.elapsedRealtime();
                boolean isDoubleTap = (now - lastPrevPressAtMs) < PREV_DOUBLE_TAP_WINDOW_MS;
                lastPrevPressAtMs = now;
                if (!isDoubleTap || currentSeconds > PREV_RESTART_THRESHOLD_SECONDS) {
                    // Restart current track
                    currentSeconds = 0;
                    isPlaying = true;
                    if (localExoMediaPlayer != null) {
                        try {
                            localExoMediaPlayer.seekTo(0);
                        } catch (Exception ignored) {
                            playCurrentTrack();
                        }
                    } else {
                        playCurrentTrack();
                    }
                    syncMiniStateWithPlaylist();
                    return;
                }
                // Double-tap within window: fall through to go to previous track
                lastPrevPressAtMs = 0L; // reset so a third press restarts again
            }

            // Going back from first track: pop global history
            if (delta == -1 && currentIndex == 0 && !globalPlaybackHistory.isEmpty()) {
                PlayerTrack histTrack = globalPlaybackHistory.pollFirst();
                if (histTrack != null && !TextUtils.isEmpty(histTrack.videoId)) {
                    tracks.clear();
                    tracks.add(histTrack);
                    currentIndex = 0;
                    isPlaying = true;
                    currentSeconds = 0;
                    playCurrentTrack();
                    syncMiniStateWithPlaylist();
                    return;
                }
            }
        }

        if (fromCompletion && repeatMode == REPEAT_MODE_ONE) {
            // Repeat current track from the beginning
            currentSeconds = 0;
            isPlaying = true;
            if (localExoMediaPlayer != null) {
                try {
                    localExoMediaPlayer.seekTo(0);
                    if (!localExoMediaPlayer.isPlaying()) localExoMediaPlayer.start();
                } catch (Exception e) {
                    playCurrentTrack();
                }
            } else {
                playCurrentTrack();
            }
            updatePlayPauseIcon();
            updateMediaSessionState();
            syncMiniStateWithPlaylist();
            return;
        }

        int targetIndex;
        if (fromCompletion) {
            if (currentIndex < tracks.size() - 1) {
                targetIndex = currentIndex + 1;
            } else if (repeatMode == REPEAT_MODE_ALL) {
                // Re-shuffle on wrap-around so each cycle plays in a different order
                if (shuffleEnabled && tracks.size() > 1) {
                    String lastVideoId = tracks.get(currentIndex).videoId;
                    randomizeQueueFromCurrentTrack(lastVideoId);
                }
                targetIndex = 0;
            } else {
                isPlaying = false;
                currentSeconds = Math.max(0, totalSeconds);
                updatePlayPauseIcon();
                updateMediaSessionState();
                syncMiniStateWithPlaylist();
                return;
            }
        } else {
            targetIndex = (currentIndex + delta + tracks.size()) % tracks.size();
        }

        // When offline, skip to the next track that has offline audio available
        if (!isNetworkAvailable() && isAdded()) {
            int scanned = 0;
            int step = delta >= 0 ? 1 : -1;
            while (scanned < tracks.size()) {
                PlayerTrack candidate = tracks.get(targetIndex);
                if (candidate != null && !TextUtils.isEmpty(candidate.videoId)
                        && (LocalFilesStore.isLocalVideoId(candidate.videoId)
                            || OfflineAudioStore.hasOfflineAudio(requireContext(), candidate.videoId))) {
                    break;
                }
                targetIndex = (targetIndex + step + tracks.size()) % tracks.size();
                scanned++;
            }
            if (scanned >= tracks.size()) {
                // No offline track found in the entire queue
                isPlaying = false;
                updatePlayPauseIcon();
                updateMediaSessionState();
                syncMiniStateWithPlaylist();
                return;
            }
        }

        currentIndex = targetIndex;
        isPlaying = true;
        currentSeconds = 0;
        playCurrentTrack();
        if (fromCompletion) {
            scheduleAutoplayRecoveryForCurrentTrack();
        } else {
            cancelAutoplayRecovery();
        }
        syncMiniStateWithPlaylist();
    }

    private void advanceToNextTrackAfterFailure() {
        if (!isAdded()) {
            return;
        }

        cancelAutoplayRecovery();
        cancelPlaybackErrorRetry();
        cancelSourcePrepareTimeout();
        cancelPendingStreamResolver();

        if (tracks.isEmpty()) {
            isPlaying = false;
            updatePlayPauseIcon();
            updateMediaSessionState();
            syncMiniStateWithPlaylist();
            return;
        }

        if (currentIndex + 1 < tracks.size()) {
            currentIndex++;
            currentSeconds = 0;
            isPlaying = true;
            playCurrentTrack();
        } else {
            isPlaying = false;
            updatePlayPauseIcon();
            updateMediaSessionState();
            syncMiniStateWithPlaylist();
        }
    }


    private void handleTrackEnded() {
        if (TextUtils.isEmpty(loadedVideoId)) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (TextUtils.equals(lastHandledEndedVideoId, loadedVideoId)
                && (now - lastHandledEndedAtMs) < 1200L) {
            return;
        }

        lastHandledEndedVideoId = loadedVideoId;
        lastHandledEndedAtMs = now;

        if (!playCountRecordedForCurrentTrack
                && isAdded() && currentIndex >= 0 && currentIndex < tracks.size()) {
            playCountRecordedForCurrentTrack = true;
            PlayerTrack finished = tracks.get(currentIndex);
            PlayCountStore.incrementPlayCount(
                    requireContext(),
                    finished.videoId,
                    finished.title,
                    finished.artist,
                    finished.imageUrl,
                    null, null
            );
            CloudSyncManager.getInstance(requireContext()).syncPlayCountsToCloud(requireContext());
            ListenHistoryStore.record(requireContext(), finished.videoId, finished.title, finished.artist, finished.imageUrl);
        }

        moveTrack(1, true);
    }

    private void scheduleAutoplayRecoveryForCurrentTrack() {
        // Disabled: never auto-restart the same track after completion.
        cancelAutoplayRecovery();
    }

    private void cancelAutoplayRecovery() {
        if (autoplayRecoveryRunnable != null) {
            localProgressHandler.removeCallbacks(autoplayRecoveryRunnable);
            autoplayRecoveryRunnable = null;
        }
        autoplayRecoveryVideoId = "";
    }


    private void schedulePlaybackRetry(@NonNull String videoId) {
        cancelPlaybackErrorRetry();
        if (TextUtils.isEmpty(videoId) || !isAdded()) return;
        // Without network, retrying the same track will always fail — skip instead.
        if (!isNetworkAvailable()) return;

        // Exponential backoff: 3s, 6s, 12s, 24s, capped at 30s
        long delayMs = Math.min(3000L * (1L << Math.min(consecutiveStreamFailures, 4)), 30000L);
        Log.d(TAG, "[PLAYBACK_RETRY] scheduling retry for videoId=" + videoId + " delay=" + delayMs + "ms attempt=" + consecutiveStreamFailures);

        playbackErrorRetryVideoId = videoId;
        playbackErrorRetryRunnable = () -> {
            if (!isAdded()) return;
            if (!TextUtils.equals(loadedVideoId, videoId)) return;
            Log.d(TAG, "[PLAYBACK_RETRY] retrying videoId=" + videoId);
            StreamResolver.invalidate(videoId);
            playCurrentTrack();
        };
        localProgressHandler.postDelayed(playbackErrorRetryRunnable, delayMs);
    }

    private void cancelPlaybackErrorRetry() {
        if (playbackErrorRetryRunnable != null) {
            localProgressHandler.removeCallbacks(playbackErrorRetryRunnable);
            playbackErrorRetryRunnable = null;
        }
        playbackErrorRetryVideoId = "";
    }

    private void resetPlaybackErrorState() {
        cancelPlaybackErrorRetry();
        lastErroredVideoId = "";
        sameTrackErrorCount = 0;
        lastReresolveVideoId = null;
    }

    private void stopPlaybackAfterErrors(@NonNull String message) {
        cancelPlaybackErrorRetry();
        isPlaying = false;
        pauseRequestedByUser = false;
        updatePlayerSurfaceForSource();
        Log.e(TAG, "Playback stopped after errors. videoId=" + loadedVideoId + " message=" + message);
        updatePlayPauseIcon();
        updateMediaSessionState();
        persistPlaybackSnapshot(false);
        
    }

    private void togglePlayback() {
        if (usingOfflineSource && localExoMediaPlayer != null) {
            if (isPlaying) {
                cancelOfflineCrossfade();
                pauseRequestedByUser = true;
                try {
                    localExoMediaPlayer.pause();
                } catch (Exception e) {
                    Log.w(TAG, "Offline pause failed", e);
                }
                stopLocalProgressTicker();
                isPlaying = false;
            } else {
                pauseRequestedByUser = false;
                isPlaying = true;
                try {
                    localExoMediaPlayer.start();
                    startLocalProgressTicker();
                } catch (Exception ignored) {
                    playCurrentTrack();
                }
            }

            updatePlayPauseIcon();
            updateMediaSessionState();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        // While the current online track is still resolving/preparing (the restore/cold-start "still
        // loading" window), treat every play tap as an idempotent "start when it's ready" instead of
        // toggling isPlaying's parity. Previously, taps during this window flipped isPlaying on/off
        // and onPrepared honored whatever parity it landed on — which is exactly why the last-played
        // track needed several taps to actually start and appeared to "reload" on each one. Keeping
        // isPlaying=true here lets onPrepared (shouldStart = isPlaying || playWhenReady) auto-start
        // once, from a single tap, without re-triggering a resolve.
        if (!usingOfflineSource) {
            String curVideoId = tracks.isEmpty() ? "" : tracks.get(currentIndex).videoId;
            boolean currentTrackLoading =
                    (localSourcePreparing && localExoMediaPlayer != null)
                    || (hasPendingStreamResolution() && TextUtils.equals(pendingResolutionVideoId, curVideoId));
            if (currentTrackLoading) {
                Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: track still loading, arming play-when-ready (idempotent)");
                pauseRequestedByUser = false;
                isPlaying = true;
                updatePlayPauseIcon();
                updateMediaSessionState();
                updateMediaNotification();
                syncMiniStateWithPlaylist();
                persistPlaybackSnapshot(false);
                return;
            }
        }

        if (isPlaying) {
            Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: PAUSING (isPlaying was true)");
            pauseRequestedByUser = true;
            isPlaying = false;
            // Cancel any armed/in-flight crossfade (mirrors the offline pause branch):
            // otherwise a transition resolving in the background would begin the fade
            // against the paused player and force playback back on.
            cancelOfflineCrossfade();
            if (localExoMediaPlayer != null) {
                try {
                    localExoMediaPlayer.pause();
                } catch (Exception e) {
                    Log.w(TAG, "Online pause failed", e);
                }
            }
            stopLocalProgressTicker();
            updatePlayPauseIcon();
            updateMediaSessionState();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        pauseRequestedByUser = false;
        isPlaying = true;

        // If the player is already preparing the current track, don't destroy and recreate it.
        // Just let the existing preparation finish — it will call start() in onPrepared.
        if (localSourcePreparing && localExoMediaPlayer != null) {
            Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: already preparing, skipping playCurrentTrack");
            updatePlayPauseIcon();
            updateMediaSessionState();
            updateMediaNotification();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        // If the player already exists and is actively playing, just resume the ticker
        // instead of destroying and recreating (handles state restore where isPlaying was false
        // but the player was already started).
        if (localExoMediaPlayer != null && localExoMediaPlayer.isPlaying()) {
            Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: player already playing, resuming ticker");
            startLocalProgressTicker();
            updatePlayPauseIcon();
            updateMediaSessionState();
            updateMediaNotification();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        // If the player exists, is prepared, is truly paused (not just buffering after a seek),
        // just call start() instead of destroying and recreating everything.
        if (localExoMediaPlayer != null && !localSourcePreparing
                && !localExoMediaPlayer.getPlayWhenReady()) {
            Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: player prepared but paused, calling start()");
                try {
                    localExoMediaPlayer.setVolume(1f, 1f);
                    localExoMediaPlayer.start();
                    startLocalProgressTicker();
                } catch (Exception e) {
                    Log.e(TAG, "togglePlayback: start() failed, falling through to playCurrentTrack", e);
                    playCurrentTrack();
                }
            updatePlayPauseIcon();
            updateMediaSessionState();
            updateMediaNotification();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        // If the player exists with playWhenReady=true, it's already started but buffering
        // (e.g. after a seek). Don't destroy it — just ensure ticker is running.
        if (localExoMediaPlayer != null && localExoMediaPlayer.getPlayWhenReady()) {
            Log.d(TAG, "[PLAYBACK_DBG] togglePlayback: player started but buffering, waiting");
            startLocalProgressTicker();
            updatePlayPauseIcon();
            updateMediaSessionState();
            updateMediaNotification();
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        playCurrentTrack();
        updatePlayPauseIcon();
        updateMediaSessionState();
        updateMediaNotification();
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(false);
    }

    private void toggleShuffleMode() {
        setShuffleEnabled(!shuffleEnabled);
    }

    private void publishShuffleModeToSession(boolean enabled) {
        if (mediaSession == null) return;
        try {
            mediaSession.setShuffleMode(enabled
                    ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                    : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        } catch (Exception e) {
            Log.w(TAG, "setShuffleMode failed", e);
        }
    }

    private void cycleRepeatMode() {
        if (repeatMode == REPEAT_MODE_OFF) {
            repeatMode = REPEAT_MODE_ALL;
        } else if (repeatMode == REPEAT_MODE_ALL) {
            repeatMode = REPEAT_MODE_ONE;
        } else {
            repeatMode = REPEAT_MODE_OFF;
        }
        persistPlaybackModePreferences();
        updatePlaybackModeButtons();
        updateMediaNotification();
        syncMiniStateWithPlaylist();
    }

    private void setShuffleEnabled(boolean enabled) {
        // Keep the MediaSession's advertised shuffle mode in sync so remote controllers
        // (Bluetooth/Android Auto/notification) display the real state and don't push back a
        // contradicting value on connect.
        publishShuffleModeToSession(enabled);
        if (shuffleEnabled == enabled) {
            updatePlaybackModeButtons();
            return;
        }

        shuffleEnabled = enabled;
        if (shuffleEnabled) {
            randomizeQueueFromCurrentTrack();
        } else {
            restoreOriginalQueueOrder();
        }

        refreshNextUp();
        invalidateNextTrackPreparations();
        persistPlaybackModePreferences();
        persistPlaybackSnapshot(false);
        updatePlaybackModeButtons();
        updateMediaNotification();
        syncMiniStateWithPlaylist();
    }

    private int normalizeRepeatMode(int mode) {
        if (mode == REPEAT_MODE_ALL || mode == REPEAT_MODE_ONE || mode == REPEAT_MODE_OFF) {
            return mode;
        }
        return REPEAT_MODE_ALL;
    }

    private void loadPlaybackModesFromSettings() {
        boolean persistNormalized = false;

        if (settingsPrefs != null) {
            boolean hasShuffle = settingsPrefs.contains(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED);
            boolean hasRepeat = settingsPrefs.contains(CloudSyncManager.KEY_PLAYER_REPEAT_MODE);

            shuffleEnabled = settingsPrefs.getBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, false);
            int storedRepeatMode = settingsPrefs.getInt(CloudSyncManager.KEY_PLAYER_REPEAT_MODE, REPEAT_MODE_ALL);
            repeatMode = normalizeRepeatMode(storedRepeatMode);

            persistNormalized = !hasShuffle || !hasRepeat || storedRepeatMode != repeatMode;
        } else {
            shuffleEnabled = false;
            repeatMode = REPEAT_MODE_ALL;
        }

        if (persistNormalized) {
            persistPlaybackModePreferences();
        }
    }

    private void persistPlaybackModePreferences() {
        if (settingsPrefs == null) {
            return;
        }

        int safeRepeatMode = normalizeRepeatMode(repeatMode);
        if (repeatMode != safeRepeatMode) {
            repeatMode = safeRepeatMode;
        }

        boolean storedShuffle = settingsPrefs.getBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, false);
        int storedRepeat = normalizeRepeatMode(settingsPrefs.getInt(CloudSyncManager.KEY_PLAYER_REPEAT_MODE, REPEAT_MODE_ALL));
        if (storedShuffle == shuffleEnabled && storedRepeat == safeRepeatMode) {
            return;
        }

        settingsPrefs.edit()
                .putBoolean(CloudSyncManager.KEY_PLAYER_SHUFFLE_ENABLED, shuffleEnabled)
                .putInt(CloudSyncManager.KEY_PLAYER_REPEAT_MODE, safeRepeatMode)
                .apply();
    }

    private void updatePlaybackModeButtons() {
        if (!isAdded()) {
            return;
        }

        int iconColor = shuffleEnabled
            ? ContextCompat.getColor(requireContext(), R.color.stitch_blue)
            : ContextCompat.getColor(requireContext(), android.R.color.white);
        int white = ContextCompat.getColor(requireContext(), android.R.color.white);

        if (btnShuffle != null) {
            btnShuffle.setImageTintList(ColorStateList.valueOf(iconColor));
            btnShuffle.setAlpha(1f);
        }
        if (vPlayerShuffleIndicator != null) {
            vPlayerShuffleIndicator.setVisibility(shuffleEnabled ? View.VISIBLE : View.INVISIBLE);
        }

        if (btnRepeat != null) {
            btnRepeat.setImageTintList(ColorStateList.valueOf(white));
            btnRepeat.setAlpha(repeatMode == REPEAT_MODE_OFF ? 0.4f : 1f);
        }
        if (vPlayerRepeatIndicator != null) {
            vPlayerRepeatIndicator.setVisibility(repeatMode == REPEAT_MODE_ONE ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Fire-and-forget: adelanta el trabajo de I/O de la pista seleccionada ANTES de que phase2
     *  llame a playCurrentTrack. Online: deja la resolución NewPipe corriendo con dedup
     *  (preResolveQueue registra el in-flight que resolveStreamUrl esperará). Offline: paga la
     *  validación (MediaMetadataRetriever) y el probe de video en background, así el arranque
     *  encuentra ambos caches calientes en vez de parsear el archivo en el main thread. */
    private void preResolveCurrentTrackSource() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }
        final String videoId = tracks.get(currentIndex).videoId;
        final String durationLabel = tracks.get(currentIndex).duration;
        if (TextUtils.isEmpty(videoId) || LocalFilesStore.isLocalVideoId(videoId)) return;
        final Context appCtx = requireContext().getApplicationContext();
        backgroundExecutor.execute(() -> {
            try {
                if (!StreamResolver.isPreferVideoMode()
                        && OfflineAudioStore.hasOfflineAudio(appCtx, videoId)) {
                    OfflineAudioStore.hasValidatedOfflineAudio(appCtx, videoId, durationLabel);
                    File offline = OfflineAudioStore.getExistingOfflineAudioFile(appCtx, videoId);
                    if (offline.isFile() && offline.length() > 0L) {
                        ensureOfflineVideoProbeCached(videoId, offline);
                    }
                    return;
                }
                StreamResolver.preResolveQueue(appCtx, Collections.singletonList(videoId));
            } catch (Exception e) {
                Log.w(TAG, "preResolveCurrentTrackSource failed", e);
            }
        });
    }

    private void playCurrentTrack() {
        if (!isAdded() || tracks.isEmpty()) {
            return;
        }

        final PlayerTrack track = tracks.get(currentIndex);
        Log.d(TAG, "[PLAYBACK_DBG] playCurrentTrack videoId=" + track.videoId + " idx=" + currentIndex, new Throwable("caller"));

        // Detach the pre-buffered player for THIS track before cancelOfflineCrossfade —
        // its cancelGaplessPreBuffer() releases gaplessPreBufferedPlayer, which made the
        // gapless fast-path below dead code: every non-crossfade advance threw away the
        // fully buffered next track and re-resolved it from scratch.
        // Only adopt the pre-buffered player if it was resolved for the CURRENT mode: a swap to
        // Video (or back to Canción) mid-track invalidates a stale-mode pre-buffer, otherwise the
        // next track would keep playing in the previous mode.
        final boolean wantVideoNow = StreamResolver.isPreferVideoMode();
        ExoMediaPlayer adoptablePreBuffered = null;
        if (gaplessPreBufferedPlayer != null && TextUtils.equals(gaplessPreBufferedVideoId, track.videoId)
                && gaplessPreBufferedIsVideo == wantVideoNow) {
            adoptablePreBuffered = gaplessPreBufferedPlayer;
            gaplessPreBufferedPlayer = null;
            gaplessPreBufferedVideoId = "";
            gaplessPreBufferTriggered = false;
        } else if (gaplessPreBufferedPlayer != null && TextUtils.equals(gaplessPreBufferedVideoId, track.videoId)) {
            // Same track but wrong mode: drop the stale pre-buffer so it is re-resolved below.
            releaseSingleExoMediaPlayer(gaplessPreBufferedPlayer);
            gaplessPreBufferedPlayer = null;
            gaplessPreBufferedVideoId = "";
            gaplessPreBufferTriggered = false;
        }

        // Safety: ensure no crossfade is active and no duplicate players exist
        cancelOfflineCrossfade();
        // Also ensure any lingering incoming player from a failed crossfade is released
        if (localCrossfadeIncomingPlayer != null) {
            releaseSingleExoMediaPlayer(localCrossfadeIncomingPlayer);
            localCrossfadeIncomingPlayer = null;
        }
        loadedVideoId = track.videoId;
        loadedTrackIsVideo = isVideoTrackId(track.videoId);
        // New track: default the committed-source flag to music until a real source commits
        // (startMediaPlaybackFromSource / adoptGaplessPlayer / commitHotSwap set the true value).
        // Without this reset the just-bound new track would inherit the previous track's video/music
        // presentation for the brief window before its own source resolves.
        currentSourceIsVideo = false;
        playCountRecordedForCurrentTrack = false;
        currentSeconds = 0;
        lastSeekTargetSeconds = -1;
        lastSnapshotPersistSecond = -1;
        isRestoringPosition = false;

        long requestToken = ++activePlaybackRequestToken;

        PlaybackHistoryStore.Snapshot snapshot = PlaybackHistoryStore.load(requireContext());
        androidx.media3.exoplayer.ExoPlayer sharedExoPlayer = ExoPlayerManager.INSTANCE.getSharedExoPlayer();
        boolean isSharedPlayerActive = sharedExoPlayer != null 
                && (sharedExoPlayer.getPlaybackState() == androidx.media3.exoplayer.ExoPlayer.STATE_READY 
                    || sharedExoPlayer.getPlaybackState() == androidx.media3.exoplayer.ExoPlayer.STATE_BUFFERING)
                && snapshot.currentTrack() != null 
                && snapshot.currentTrack().videoId.equals(track.videoId);

        if (isSharedPlayerActive && localExoMediaPlayer == null) {
            if (adoptablePreBuffered != null) {
                releaseSingleExoMediaPlayer(adoptablePreBuffered);
                adoptablePreBuffered = null;
            }
            PlaybackLoadingBus.notifyAudioConfirmed(track.videoId);
            bindCurrentTrackInternal(true, false); // Keep current time and UI intact
            usingOfflineSource = true;
            localSourcePreparing = false;
            localExoMediaPlayer = new ExoMediaPlayer(requireContext().getApplicationContext(), sharedExoPlayer);
            // Player audible: reporta SU sesión al EQ solo-app (el shared nunca dispara
            // onAudioSessionIdChanged porque su id se generó una sola vez en initialize()).
            localExoMediaPlayer.markAsActiveForEq();
            localExoMediaPlayer.setOnPreparedListener(mp -> {
                localSourcePreparing = false;
            });
            localExoMediaPlayer.setOnCompletionListener(mp -> handleLocalPlaybackCompletion());
            localExoMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Local ExoMediaPlayer error (seamless): " + what + ", " + extra);
                handlePlaybackError();
                return true;
            });
            updatePlayPauseIcon();
            startLocalProgressTicker();
            lastPlaybackStartRequestAtMs = android.os.SystemClock.elapsedRealtime();
            return;
        }

        // Bind metadata. forceZero=true resets UI to 0, but we restore resume position after.
        bindCurrentTrackInternal(false, true);
        // Reset state BEFORE restoring resumeSeconds
        cancelOfflineCrossfade();
        resetPlaybackStateForNewTrack();
        
        lastPlaybackStartRequestAtMs = SystemClock.elapsedRealtime();

        cancelPlaybackErrorRetry();
        if (hasPendingStreamResolution() && TextUtils.equals(pendingResolutionVideoId, track.videoId)) {
            if (adoptablePreBuffered != null) {
                releaseSingleExoMediaPlayer(adoptablePreBuffered);
            }
            return;
        }
        cancelPendingStreamResolver();

        // requestToken already incremented above
        final long token = activePlaybackRequestToken;

        // ✅ FAST-PATH: check offline on the main thread immediately — no executor queue wait.
        // OfflineAudioStore.hasOfflineAudio is a simple SharedPrefs/file existence check.
        // En modo VIDEO (pastilla Audio|Video) los tracks descargados van igual a red: el
        // offline es audio y el usuario pidió ver el video musical.
        boolean hasOfflineLocal = LocalFilesStore.isLocalVideoId(track.videoId)
                || (!StreamResolver.isPreferVideoMode()
                        && OfflineAudioStore.hasOfflineAudio(requireContext(), track.videoId));
        if (hasOfflineLocal) {
            // Offline/local sources start instantly anyway; the pre-buffered network player
            // (if any) is not needed. adoptGaplessPlayer assumes a network source, so do
            // not adopt it for offline tracks.
            if (adoptablePreBuffered != null) {
                releaseSingleExoMediaPlayer(adoptablePreBuffered);
            }
            PlaybackLoadingBus.clearLoading();
            List<String> directSources = buildDirectSourceCandidates(track);
            attemptPlaybackFromSources(track, directSources, 0, requestToken, 0);
            return;
        }

        // GAPLESS: if pre-buffered player is ready for this track, use it instantly
        if (adoptablePreBuffered != null) {
            adoptGaplessPlayer(track, adoptablePreBuffered, requestToken);
            return;
        }

        // Not offline — check prefetch (main-thread fields), then resolve online via executor.
        // Only reuse the prefetched URL when it was resolved for the current mode.
        if (TextUtils.equals(track.videoId, prefetchedNextVideoId) && !TextUtils.isEmpty(prefetchedNextUrl)
                && prefetchedNextIsVideo == wantVideoNow) {
            String url = prefetchedNextUrl;
            prefetchedNextVideoId = null;
            prefetchedNextUrl = null;
            startMediaPlaybackFromSource(track, url, requestToken, () -> {
                resolveAndPlayOnlineTrack(track, requestToken);
            });
            return;
        }
        resolveAndPlayOnlineTrack(track, requestToken);
    }

    private void adoptGaplessPlayer(@NonNull PlayerTrack track, @NonNull ExoMediaPlayer preBuffered, long requestToken) {
        // Release current player
        releaseLocalExoMediaPlayer();
        usingOfflineSource = false;
        localSourcePreparing = false;

        localExoMediaPlayer = preBuffered;
        // Promoción del pre-buffer gapless a player audible: ahora SU sesión es la del EQ.
        preBuffered.markAsActiveForEq();
        preBuffered.isCrossfadeComponent = false;
        preBuffered.setVolume(1f, 1f);
        preBuffered.setOnCompletionListener(mp -> handleLocalPlaybackCompletion());
        preBuffered.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "adoptGaplessPlayer: error what=" + what + " extra=" + extra);
            handlePlaybackError();
            return true;
        });

        try {
            preBuffered.start();
        } catch (Exception e) {
            Log.e(TAG, "adoptGaplessPlayer: start failed", e);
            releaseLocalExoMediaPlayer();
            resolveAndPlayOnlineTrack(track, requestToken);
            return;
        }

        int durationMs;
        try {
            durationMs = Math.max(0, preBuffered.getDuration());
        } catch (Exception ignored) {
            durationMs = 0;
        }
        totalSeconds = durationMs > 0 ? Math.max(1, durationMs / 1000) : Math.max(1, parseDurationSeconds(track.duration));

        isPlaying = true;

        // Attach video surface and update UI for the adopted gapless track. Capture the committed
        // source type with the SAME isVideoTrack(track) the surface attach uses, so presentation and
        // surface never diverge.
        currentSourceIsVideo = isVideoTrack(track);
        videoRouter.onTrackStarted(preBuffered, track.videoId, currentSourceIsVideo);
        updatePlayerSurfaceForSource();

        updatePlayPauseIcon();
        startLocalProgressTicker();
        updateMediaSessionMetadata();
        updateMediaSessionState();
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(false);
        prefetchNextTrackStream();
        // C2: precalienta la cabecera del video de la pista adoptada (modo audio) si ya se conoce.
        maybeWarmVideoStreamHead(track.videoId);

        // Stream-as-download: gapless players are always network sources
        maybeSaveStreamedTrackOffline(track.videoId);
    }

    private void handleLocalPlaybackCompletion() {
        if (!isAdded()) {
            return;
        }
        if (crossfadeManager.isInProgress()) {
            return;
        }
        stopLocalProgressTicker();
        handleTrackEnded();
    }

    private void handlePlaybackError() {
        if (!isAdded()) {
            return;
        }
        // Error de fuente en el player compartido: el próximo intento de esta pista fuerza ANDROID_VR.
        markForceAltClient(loadedVideoId);
        tryReresolveOrSkipCurrentTrack("Error en reproductor compartido. Reintentando.", false);
    }

    // videoIds cuya URL primaria (NewPipe) resolvió pero FALLÓ al reproducirse (prepare colgado,
    // error de fuente). El próximo resolve de estos fuerza el cliente ANDROID_VR. Se limpia al
    // reproducir con éxito. Acotado para no crecer sin límite.
    private final java.util.LinkedHashSet<String> forceAltClientVideoIds = new java.util.LinkedHashSet<>();

    /** Marca un videoId para resolverse por el cliente alternativo (ANDROID_VR) en el próximo intento. */
    private void markForceAltClient(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId)) return;
        forceAltClientVideoIds.add(videoId);
        if (forceAltClientVideoIds.size() > 50) {
            java.util.Iterator<String> it = forceAltClientVideoIds.iterator();
            it.next();
            it.remove();
        }
    }

    private void resolveAndPlayOnlineTrack(@NonNull PlayerTrack track, long requestToken) {
        // Si la reproducción de la URL primaria de esta pista ya falló antes, forzar ANDROID_VR.
        resolveAndPlayOnlineTrack(track, requestToken, forceAltClientVideoIds.contains(track.videoId));
    }

    private void resolveAndPlayOnlineTrack(@NonNull PlayerTrack track, long requestToken, boolean forceAlternativeClient) {
        if (!isNetworkAvailable()) {
            // No network and no offline audio for this track: skip to the next available offline track.
            Log.w(TAG, "resolveAndPlayOnlineTrack: offline, no download for videoId=" + track.videoId + " — skipping.");
            moveTrack(1, false);
            return;
        }

        PlaybackLoadingBus.notifyLoadingStarted(track.videoId);
        updateSeekBarLoadingState();
        // Stop the previous track's audio/ticker immediately so a rapid skip does not keep the
        // OLD song playing (and the seekbar tracking it) during the async resolution window.
        // Pause rather than release: re-resolve/failure callers may still reference the player,
        // and startMediaPlaybackFromSource will release+replace it once the new source is ready.
        stopLocalProgressTicker();
        if (localExoMediaPlayer != null) {
            try {
                localExoMediaPlayer.pause();
            } catch (Exception ignored) {
            }
        }
        // Record which track this in-flight resolution is for so playCurrentTrack's
        // pending-resolution guard can tell a re-entrant call for the SAME track from a skip
        // to a DIFFERENT track.
        pendingResolutionVideoId = track.videoId;
        // Capture the app context now: requireContext() inside the executor lambda throws
        // if the fragment detaches mid-flight, silently aborting the resolution.
        final Context resolveCtx = requireContext().getApplicationContext();
        pendingStreamResolverFuture = streamResolverExecutor.submit(() -> {
            String resolvedUrl = StreamResolver.resolveStreamUrl(resolveCtx, track.videoId, forceAlternativeClient);

            localProgressHandler.post(() -> {
                if (requestToken != activePlaybackRequestToken || !isAdded()) return;
                pendingStreamResolverFuture = null;

                if (!TextUtils.isEmpty(resolvedUrl)) {
                    startMediaPlaybackFromSource(track, resolvedUrl, requestToken, () -> {
                        StreamResolver.invalidate(track.videoId);
                        // La URL resolvió pero su reproducción falló/colgó: el próximo intento de
                        // ESTA pista debe usar el cliente ANDROID_VR (URL directa más reproducible)
                        // en vez de re-pedir la misma URL de NewPipe que se colgó.
                        markForceAltClient(track.videoId);
                        tryReresolveOrSkipCurrentTrack("Fallo de reproducción directa: re-resolviendo.", false);
                    });
                } else {
                    if (!tryReresolveOrSkipCurrentTrack("No se pudo resolver el stream directo. Reintentando.", false)
                            && !isNetworkAvailable()) {
                        // Offline dead-end: tryReresolveOrSkipCurrentTrack returns false without
                        // advancing or clearing the spinner. Notify confirmed to dismiss spinner.
                        PlaybackLoadingBus.notifyAudioConfirmed(track.videoId);
                    }
                }
            });
        });
        updateSeekBarLoadingState();
    }

    private void prefetchNextTrackStream() {
        if (tracks.size() <= 1 || !isAdded()) return;

        // Capture the app context now: requireContext() inside the executor lambda throws
        // if the fragment detaches mid-flight, silently killing the prefetch.
        final Context appCtx = requireContext().getApplicationContext();
        // Prefetch FOR the current mode; a source resolved for the wrong mode is useless (and
        // adopting it would break Canción<->Video stickiness).
        final boolean wantVideo = StreamResolver.isPreferVideoMode();

        // Prefetch next 2 tracks in parallel for instant transitions
        for (int offset = 1; offset <= Math.min(2, tracks.size() - 1); offset++) {
            int idx = (currentIndex + offset) % tracks.size();
            PlayerTrack track = tracks.get(idx);
            if (track == null || TextUtils.isEmpty(track.videoId)) continue;

            // Skip local files always. Skip DOWNLOADED tracks only in Canción mode — in Video mode a
            // downloaded track still streams its music video, so it must be prefetched from network.
            if (LocalFilesStore.isLocalVideoId(track.videoId)) continue;
            if (!wantVideo && OfflineAudioStore.hasOfflineAudio(appCtx, track.videoId)) continue;

            // Only resolve the immediate next into prefetchedNext fields
            final boolean isImmediate = (offset == 1);
            if (isImmediate && TextUtils.equals(track.videoId, prefetchedNextVideoId)
                    && prefetchedNextIsVideo == wantVideo) continue;

            final String videoId = track.videoId;
            streamResolverExecutor.submit(() -> {
                String url = StreamResolver.resolveStreamUrl(appCtx, videoId);
                if (TextUtils.isEmpty(url) || !isImmediate) return;
                // resolveStreamUrl reads the LIVE mode on this bg thread, so the source it produced
                // may not match the mode captured at submit time (user toggled and toggled back
                // during the resolve). Tag from the ACTUAL resolved source type, not the captured
                // intent, and commit only if that actual type matches the current live mode.
                final boolean resolvedIsVideo = StreamResolver.isVideoSource(videoId);
                localProgressHandler.post(() -> {
                    if (!isAdded() || tracks.size() <= 1) return;
                    if (StreamResolver.isPreferVideoMode() != resolvedIsVideo) return;
                    int immediateIdx = (currentIndex + 1) % tracks.size();
                    PlayerTrack immediate = tracks.get(immediateIdx);
                    if (immediate == null || !TextUtils.equals(immediate.videoId, videoId)) return;
                    prefetchedNextVideoId = videoId;
                    prefetchedNextUrl = url;
                    prefetchedNextIsVideo = resolvedIsVideo;
                });
            });
        }
    }

    /**
     * After a network track starts playing, download its audio in the background to the offline
     * directory straight from the googlevideo CDN (NewPipe-resolved URL, no proxy), so a streamed
     * track quietly becomes a light offline file that plays as music.
     */
    private void maybeSaveStreamedTrackOffline(@NonNull String videoId) {
        if (TextUtils.isEmpty(videoId)) return;
        if (LocalFilesStore.isLocalVideoId(videoId)) return;
        if (!isAdded()) return;

        final String normalized = videoId.trim();
        if (normalized.equals(streamDownloadingVideoId)) return;

        final Context appContext = requireContext().getApplicationContext();
        if (OfflineAudioStore.hasOfflineAudio(appContext, normalized)) return;

        // Respect the user's download-over-mobile-data setting, like every WorkManager download
        // path does (UNMETERED constraint). This passive save pulls the audio from the CDN —
        // without this gate, every track played on mobile data would silently burn data.
        android.content.SharedPreferences settingsPrefsForSave =
                appContext.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean allowMobileForSave =
                settingsPrefsForSave.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
        if (!allowMobileForSave) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.isActiveNetworkMetered()) return;
        }

        streamDownloadingVideoId = normalized;
        backgroundDownloadExecutor.execute(() -> {
            try {
                // Re-check on background thread
                if (OfflineAudioStore.hasOfflineAudio(appContext, normalized)) {
                    streamDownloadingVideoId = null;
                    return;
                }

                // Direct CDN audio download (NewPipe-resolved googlevideo URL). The resolver owns
                // the offline file path/extension (.m4a/.webm) and cleans up its own temp on failure.
                boolean ok = SleppifyDownloaderResolver.downloadTrackAudio(appContext, normalized);

                if (ok) {
                    OfflineAudioStore.markOfflineAudioState(normalized, true);
                    Log.d(TAG, "stream-as-download: saved " + normalized);
                    localProgressHandler.post(() -> {
                        if (!isAdded()) return;
                        try {
                            Fragment playlist = getParentFragmentManager().findFragmentByTag("playlist_detail");
                            if (playlist instanceof PlaylistDetailFragment) {
                                ((PlaylistDetailFragment) playlist).externalRefreshOfflineState();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to refresh playlist offline state", e);
                        }
                    });
                } else {
                    OfflineAudioStore.markOfflineAudioState(normalized, false);
                }
            } catch (Exception e) {
                Log.w(TAG, "stream-as-download: failed " + normalized + " " + e.getMessage());
            } finally {
                if (normalized.equals(streamDownloadingVideoId)) {
                    streamDownloadingVideoId = null;
                }
            }
        });
    }


    private boolean isGaplessPlaybackEnabled() {
        return settingsPrefs != null
                && settingsPrefs.getBoolean(CloudSyncManager.KEY_GAPLESS_PLAYBACK, true);
    }

    private void maybeStartGaplessPreBuffer(int positionMs, int durationMs) {
        if (!isAdded()
                || !isGaplessPlaybackEnabled()
                || localExoMediaPlayer == null
                || localSourcePreparing
                || crossfadeManager.isInProgress()
                || !isPlaying) {
            return;
        }

        // Trigger after 2s of cumulative listening
        if (accumulatedListenMs < GAPLESS_PRE_BUFFER_LISTEN_THRESHOLD_MS) {
            return;
        }

        // Determine next track
        int nextIndex = resolveNextIndexForCompletionCrossfade();
        if (nextIndex < 0 || nextIndex >= tracks.size()) {
            return;
        }

        PlayerTrack nextTrack = tracks.get(nextIndex);
        if (nextTrack == null || TextUtils.isEmpty(nextTrack.videoId)) {
            return;
        }

        // Skip if already preloaded/preloading the exact same track
        if (TextUtils.equals(gaplessPreBufferingVideoId, nextTrack.videoId)) {
            return;
        }

        // If we are currently pre-buffering/pre-buffered for a different track, cancel it first
        if (!TextUtils.isEmpty(gaplessPreBufferingVideoId)) {
            cancelGaplessPreBuffer();
        }

        gaplessPreBufferingVideoId = nextTrack.videoId;
        gaplessPreBufferTriggered = true;
        // Pre-buffer FOR the current mode. In Video mode a downloaded track streams its music
        // video (never the offline audio file), mirroring playCurrentTrack's offline gate.
        final boolean wantVideo = StreamResolver.isPreferVideoMode();
        gaplessPreBufferingIsVideo = wantVideo;

        String url = null;
        boolean isLocalOrOffline = false;

        if (LocalFilesStore.isLocalVideoId(nextTrack.videoId)) {
            url = LocalFilesStore.getContentUriForVideoId(requireContext(), nextTrack.videoId);
            isLocalOrOffline = true;
        } else if (!wantVideo && OfflineAudioStore.hasOfflineAudio(requireContext(), nextTrack.videoId)) {
            java.io.File offlineFile = OfflineAudioStore.getExistingOfflineAudioFile(requireContext(), nextTrack.videoId);
            if (offlineFile != null && offlineFile.isFile() && offlineFile.length() > 0L) {
                url = offlineFile.getAbsolutePath();
                isLocalOrOffline = true;
            }
        }

        if (!isLocalOrOffline) {
            // Find a URL: use prefetched if available AND resolved for this mode, otherwise resolve.
            if (TextUtils.equals(nextTrack.videoId, prefetchedNextVideoId) && !TextUtils.isEmpty(prefetchedNextUrl)
                    && prefetchedNextIsVideo == wantVideo) {
                url = prefetchedNextUrl;
            }
        }

        if (!TextUtils.isEmpty(url)) {
            prepareGaplessPlayer(nextTrack, url, wantVideo);
        } else {
            // Capture the app context now: requireContext() inside the executor lambda
            // throws if the fragment detaches mid-flight, silently killing the prebuffer.
            final Context appCtx = requireContext().getApplicationContext();
            streamResolverExecutor.submit(() -> {
                String resolved = StreamResolver.resolveStreamUrl(appCtx, nextTrack.videoId);
                // Tag from the ACTUAL resolved source type (resolveStreamUrl read the live mode on
                // this bg thread), not the mode captured at submit time.
                final boolean resolvedIsVideo = StreamResolver.isVideoSource(nextTrack.videoId);
                localProgressHandler.post(() -> {
                    if (!isAdded()) return;
                    // Cancelled (track change / pause) or mode no longer matches the resolved source.
                    if (!TextUtils.equals(gaplessPreBufferingVideoId, nextTrack.videoId)) return;
                    if (StreamResolver.isPreferVideoMode() != resolvedIsVideo) {
                        gaplessPreBufferingVideoId = "";
                        return;
                    }
                    if (TextUtils.isEmpty(resolved)) {
                        gaplessPreBufferingVideoId = "";
                        return;
                    }
                    // Re-validate that this is still the upcoming track: the user may have
                    // skipped or toggled shuffle while the resolution was in flight, and
                    // prebuffering the wrong track wastes the player and the bandwidth.
                    int upcoming = resolveNextIndexForCompletionCrossfade();
                    if (upcoming < 0 || upcoming >= tracks.size()
                            || !TextUtils.equals(tracks.get(upcoming).videoId, nextTrack.videoId)) {
                        gaplessPreBufferingVideoId = "";
                        return;
                    }
                    prepareGaplessPlayer(nextTrack, resolved, resolvedIsVideo);
                });
            });
        }
    }

    private void prepareGaplessPlayer(@NonNull PlayerTrack nextTrack, @NonNull String url, boolean isVideo) {
        if (!isAdded()) return;

        // Release any existing pre-buffer player
        if (gaplessPreBufferedPlayer != null) {
            releaseSingleExoMediaPlayer(gaplessPreBufferedPlayer);
            gaplessPreBufferedPlayer = null;
            gaplessPreBufferedVideoId = "";
        }
        if (gaplessPreBufferingPlayer != null) {
            releaseSingleExoMediaPlayer(gaplessPreBufferingPlayer);
            gaplessPreBufferingPlayer = null;
        }

        Context appCtx = requireContext().getApplicationContext();
        ExoMediaPlayer player = new ExoMediaPlayer(appCtx, false, true); // backgroundBuffer: progressive download
        gaplessPreBufferingPlayer = player;
        player.isCrossfadeComponent = true; // Mark so it doesn't interfere with media session

        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            if (url.startsWith("http://") || url.startsWith("https://")) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", STREAM_HTTP_USER_AGENT);
                headers.put("Accept", "*/*");
                // Inject InnerTube-specific headers for direct googlevideo.com URLs
                Map<String, String> innertubeHeaders = StreamResolver.getHeadersFor(nextTrack.videoId);
                headers.putAll(innertubeHeaders);
                player.setDataSource(appCtx, Uri.parse(url), headers);
            } else if (url.startsWith("content://")) {
                player.setDataSource(appCtx, Uri.parse(url), null);
            } else {
                player.setDataSource(url);
            }
            player.setVolume(0f, 0f);

            player.setOnPreparedListener(mp -> {
                if (gaplessPreBufferingPlayer == mp) {
                    gaplessPreBufferingPlayer = null;
                }
                gaplessPreBufferedPlayer = mp;
                gaplessPreBufferedVideoId = nextTrack.videoId;
                gaplessPreBufferedIsVideo = isVideo;
            });

            player.setOnErrorListener((mp, what, extra) -> {
                if (gaplessPreBufferingPlayer == mp) {
                    gaplessPreBufferingPlayer = null;
                }
                if (gaplessPreBufferedPlayer == mp) {
                    gaplessPreBufferedPlayer = null;
                    gaplessPreBufferedVideoId = "";
                }
                if (TextUtils.equals(gaplessPreBufferingVideoId, nextTrack.videoId)) {
                    gaplessPreBufferingVideoId = "";
                }
                releaseSingleExoMediaPlayer(mp);
                return true;
            });

            player.prepareAsync();
        } catch (Exception e) {
            if (gaplessPreBufferingPlayer == player) {
                gaplessPreBufferingPlayer = null;
            }
            if (TextUtils.equals(gaplessPreBufferingVideoId, nextTrack.videoId)) {
                gaplessPreBufferingVideoId = "";
            }
            releaseSingleExoMediaPlayer(player);
        }
    }

    private void cancelGaplessPreBuffer() {
        gaplessPreBufferTriggered = false;
        gaplessPreBufferingVideoId = "";
        gaplessPreBufferingIsVideo = false;
        if (gaplessPreBufferingPlayer != null) {
            releaseSingleExoMediaPlayer(gaplessPreBufferingPlayer);
            gaplessPreBufferingPlayer = null;
        }
        if (gaplessPreBufferedPlayer != null) {
            releaseSingleExoMediaPlayer(gaplessPreBufferedPlayer);
            gaplessPreBufferedPlayer = null;
            gaplessPreBufferedVideoId = "";
            gaplessPreBufferedIsVideo = false;
        }
    }

    private void invalidateNextTrackPreparations() {
        invalidateNextTrackPreparations(true);
    }

    /** @param reprefetchNow false difiere el re-prefetch del siguiente track ~2.5s (misma pauta
     *  que el arranque de pista). El hot-swap de modo lo usa: prefetchNextTrackStream lanza 2
     *  resoluciones al MISMO pool de 3 hilos que el resolve del swap está por usar, y hacerlas
     *  en el tap le robaba el hilo al intercambio. */
    private void invalidateNextTrackPreparations(boolean reprefetchNow) {
        cancelGaplessPreBuffer();
        cancelVideoStreamWarm();
        prefetchedNextVideoId = null;
        prefetchedNextUrl = null;
        if (reprefetchNow) {
            prefetchNextTrackStream();
        } else {
            localProgressHandler.postDelayed(() -> {
                if (isAdded()) {
                    prefetchNextTrackStream();
                }
            }, 2500L);
        }
    }

    private void attemptPlaybackFromSources(
            @NonNull PlayerTrack track,
            @NonNull List<String> sources,
            int sourceIndex,
            long requestToken,
            int sourceRetryCount
    ) {
        if (!isAdded()) {
            return;
        }

        if (requestToken != activePlaybackRequestToken
                || !TextUtils.equals(track.videoId, loadedVideoId)) {
            return;
        }

        if (sourceIndex >= sources.size()) {
            if (isNetworkAvailable() && tryReresolveOrSkipCurrentTrack("No se encontro audio directo.", false)) {
                return;
            }

            if (!isNetworkAvailable()
                    && !OfflineAudioStore.hasValidatedOfflineAudio(requireContext(), track.videoId, track.duration)) {
                markPlaybackUnavailable("Sin internet y sin descarga offline para esta canción.");
            } else {
                markPlaybackUnavailable("No se encontro una fuente de audio para esta canción.");
            }
            return;
        }

        String source = sources.get(sourceIndex);
        startMediaPlaybackFromSource(
                track,
                source,
                requestToken,
            () -> {
                if (sourceRetryCount < MAX_PLAYBACK_SOURCE_RETRY) {
                Log.w(TAG,
                    "attemptPlaybackFromSources: retrying same source sourceIndex=" + sourceIndex
                        + " source=" + maskUrlForLog(source)
                        + " nextRetry=" + (sourceRetryCount + 1)
                        + " requestToken=" + requestToken);

                localProgressHandler.postDelayed(
                    () -> attemptPlaybackFromSources(
                        track,
                        sources,
                        sourceIndex,
                        requestToken,
                        sourceRetryCount + 1
                    ),
                    PLAYBACK_SOURCE_RETRY_DELAY_MS
                );
                return;
                }

                attemptPlaybackFromSources(track, sources, sourceIndex + 1, requestToken, 0);
            }
        );
    }

    private void startMediaPlaybackFromSource(
            @NonNull PlayerTrack track,
            @NonNull String source,
            long requestToken,
            @NonNull Runnable onFailure
    ) {
        final boolean networkSource = isHttpStreamSource(source);
        stopLocalProgressTicker();
        releaseLocalExoMediaPlayer();
        usingOfflineSource = !networkSource;
        // Committed-source flag captured with the SAME predicate the surface attach (below) uses,
        // so the cover/surface presentation follows what is really loaded — not the mutable global
        // StreamResolver cache.
        currentSourceIsVideo = isVideoTrack(track);
        currentVideoFilePath = (!networkSource) ? source : null;
        localSourcePreparing = true;
        updateSeekBarLoadingState();
        updatePlayerSurfaceForSource();

        // Show player loading spinner only for local video tracks
        if (pbVideoLoading != null) {
            if (isVideoTrack(track)) {
                pbVideoLoading.setVisibility(View.VISIBLE);
            } else {
                pbVideoLoading.setVisibility(View.GONE);
            }
        }

        Context playbackAppContext = getPlaybackAppContext();
        if (playbackAppContext == null) {
            Log.w(TAG, "startMediaPlaybackFromSource: missing app context, aborting playback start");
            localSourcePreparing = false;
            usingOfflineSource = false;
            onFailure.run();
            return;
        }

        // Try to use shared ExoPlayer to reduce startup latency
        ExoPlayer sharedExoPlayer = ExoPlayerManager.INSTANCE.getSharedExoPlayer();
        ExoMediaPlayer player;
        if (sharedExoPlayer != null) {
            try {
                player = new ExoMediaPlayer(playbackAppContext, sharedExoPlayer);
                Log.d(TAG, "[PLAYBACK_DBG] using SHARED ExoPlayer for videoId=" + track.videoId + " token=" + requestToken);
            } catch (Exception e) {
                Log.w(TAG, "Failed to use shared ExoPlayer, falling back", e);
                player = new ExoMediaPlayer(playbackAppContext);
                Log.d(TAG, "[PLAYBACK_DBG] using OWN ExoPlayer (shared failed) for videoId=" + track.videoId);
            }
        } else {
            player = new ExoMediaPlayer(playbackAppContext);
            Log.d(TAG, "[PLAYBACK_DBG] using OWN ExoPlayer (no shared) for videoId=" + track.videoId);
        }
        localExoMediaPlayer = player;
        // Player audible del start normal (shared u own): reporta su sesión al EQ solo-app.
        player.markAsActiveForEq();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());

        // Re-affirm EQ on the global session (session 0) — no per-player sessionId needed.
        try {
            AudioEffectsService.sendApply(playbackAppContext);
        } catch (Exception e) {
            Log.w(TAG, "Failed to re-affirm EQ", e);
        }

        // Attach video surface
        videoRouter.onTrackStarted(player, track.videoId, isVideoTrack(track));

        // Show/hide loading spinner based on buffering state, but only hide when
        // audio is actually playing (not just when prepare finishes with isPlaying=false).
        player.setOnBufferingListener((mp, isBuffering) -> {
            if (!isAdded()) return;
            if (mp == localExoMediaPlayer) {
                if (pbVideoLoading != null) {
                    if (isBuffering && isVideoTrack(track)) {
                        pbVideoLoading.setVisibility(View.VISIBLE);
                    } else if (!isBuffering) {
                        pbVideoLoading.setVisibility(View.GONE);
                    }
                }
                if (!usingOfflineSource && !loadedVideoId.isEmpty()) {
                    if (isBuffering) {
                        PlaybackLoadingBus.notifyLoadingStarted(loadedVideoId);
                    } else {
                        PlaybackLoadingBus.notifyAudioConfirmed(loadedVideoId);
                    }
                }
            }
        });

        player.setOnRenderedFirstFrameListener(mp -> {
            if (!isAdded() || requestToken != activePlaybackRequestToken) return;
            if (localExoMediaPlayer != mp || !TextUtils.equals(track.videoId, loadedVideoId)) return;
            PlaybackLoadingBus.notifyAudioConfirmed(track.videoId);
            if (pbVideoLoading != null) pbVideoLoading.setVisibility(View.GONE);
        });

        player.setOnPreparedListener(mp -> {
            cancelSourcePrepareTimeout();
            localSourcePreparing = false;
            updateSeekBarLoadingState();

            Log.d(TAG, "[PLAYBACK_DBG] onPrepared fired for videoId=" + track.videoId
                    + " isAdded=" + isAdded()
                    + " samePlayer=" + (localExoMediaPlayer == mp)
                    + " tokenMatch=" + (requestToken == activePlaybackRequestToken)
                    + " videoIdMatch=" + TextUtils.equals(track.videoId, loadedVideoId)
                    + " isPlaying=" + isPlaying
                    + " requestToken=" + requestToken
                    + " activeToken=" + activePlaybackRequestToken
                    + " loadedVideoId=" + loadedVideoId);

            if (!isAdded()
                    || localExoMediaPlayer != mp
                    || requestToken != activePlaybackRequestToken
                    || !TextUtils.equals(track.videoId, loadedVideoId)) {
                Log.w(TAG, "[PLAYBACK_DBG] onPrepared REJECTED — releasing mp for videoId=" + track.videoId);
                releaseSingleExoMediaPlayer(mp);
                return;
            }

            int durationMs;
            try {
                durationMs = Math.max(0, mp.getDuration());
            } catch (Exception ignored) {
                durationMs = 0;
            }

            int resolvedTotal = durationMs > 0
                    ? Math.max(1, durationMs / 1000)
                    : Math.max(1, parseDurationSeconds(track.duration));

            totalSeconds = resolvedTotal;

            // Also start if ExoPlayer's playWhenReady was already set (e.g. by ensureActive)
            boolean shouldStart = isPlaying || mp.getPlayWhenReady();
            if (shouldStart) {
                if (!isPlaying) {
                    Log.d(TAG, "[PLAYBACK_DBG] onPrepared: isPlaying=false but playWhenReady=true, correcting");
                    isPlaying = true;
                    pauseRequestedByUser = false;
                }
                try {
                    mp.setVolume(1f, 1f);
                    mp.start();
                    Log.d(TAG, "[PLAYBACK_FLOW] mp.start() called, AUDIO PLAYING for videoId=" + track.videoId);
                    if (!LocalFilesStore.isLocalVideoId(track.videoId)) {
                        PlaybackLoadingBus.notifyAudioConfirmed(track.videoId);
                        if (pbVideoLoading != null) {
                            pbVideoLoading.setVisibility(View.GONE);
                        }
                    }
                    if (networkSource) StreamResolver.markSuccess(track.videoId);
                    // Reprodujo bien: si estaba marcada para ANDROID_VR forzado, quitarla (el
                    // camino rápido de NewPipe vuelve a valer para futuras reproducciones).
                    forceAltClientVideoIds.remove(track.videoId);
                    consecutiveStreamFailures = 0; // Reset counter on successful playback
                    audioTrackReinitToken = -1;
                    startLocalProgressTicker();

                    // Defer the two background network jobs so they don't fight the just-started
                    // stream's buffering (which is what janked the UI the instant playback began).
                    // Prefetch the next track a couple seconds in; save-offline (a full server-side
                    // mp4 fetch) waits longer and only if this track is still the current one.
                    final String startedVideoId = track.videoId;
                    final boolean saveOffline = networkSource;
                    localProgressHandler.postDelayed(() -> {
                        if (!isAdded()) return;
                        prefetchNextTrackStream();
                    }, 2500L);
                    if (saveOffline) {
                        localProgressHandler.postDelayed(() -> {
                            if (!isAdded() || startedVideoId == null) return;
                            // Only save if the user is still on this track (skipped tracks don't
                            // deserve a full mp4 fetch — that was the data/CPU burn at play-start).
                            if (currentIndex >= 0 && currentIndex < tracks.size()
                                    && startedVideoId.equals(tracks.get(currentIndex).videoId)) {
                                maybeSaveStreamedTrackOffline(startedVideoId);
                            }
                        }, 8000L);
                    }
                } catch (Exception startError) {
                    Log.e(TAG, "onPrepared: start failed for videoId=" + track.videoId, startError);
                    if (localExoMediaPlayer == mp) {
                        releaseLocalExoMediaPlayer();
                        usingOfflineSource = false;
                    } else {
                        releaseSingleExoMediaPlayer(mp);
                    }
                    onFailure.run();
                    return;
                }
            }

            // Always clear loading state once prepared, even if not auto-starting.
            // This ensures the mini-player spinner is dismissed.
            if (!LocalFilesStore.isLocalVideoId(track.videoId)) {
                PlaybackLoadingBus.notifyAudioConfirmed(track.videoId);
            }

            // C2: en modo AUDIO, precalienta la cabecera del video de esta pista si ya se conoce
            // su URL (side-cache C1) — deja el swap a «Video» casi instantáneo.
            maybeWarmVideoStreamHead(track.videoId);

            setPlaybackUiForPreparedSource();
        });

        player.setOnCompletionListener(mp -> {
            if (!isAdded()) {
                return;
            }
            if (requestToken != activePlaybackRequestToken) {
                return;
            }
            if (crossfadeManager.isInProgress()) {
                return;
            }
            stopLocalProgressTicker();
            handleTrackEnded();
        });

        player.setOnErrorListener((mp, what, extra) -> {
            cancelSourcePrepareTimeout();
            localSourcePreparing = false;
            Log.e(TAG, "onError: what=" + what
                    + " extra=" + extra
                    + " source=" + maskUrlForLog(source)
                    + " videoId=" + track.videoId
                    + " token=" + requestToken);

            // Codec/renderer errors (not network issues) — device codec crashed
            boolean isCodecError = (what == 4003 || what == 4006);

            // Codec crash (DEAD_OBJECT / MediaCodecRenderer error): error codes 4003, 4006.
            // The device codec process died — NOT a network issue. Don't destroy the
            // player; just call start() which triggers ExoPlayer's internal prepare() from
            // STATE_IDLE, reinitializing codecs without losing position or media source.
            if (isCodecError && localExoMediaPlayer == mp && isAdded()) {
                Log.w(TAG, "Codec error (" + what + ") — retrying start() to reinit codecs for videoId=" + track.videoId);
                localProgressHandler.postDelayed(() -> {
                    if (!isAdded() || requestToken != activePlaybackRequestToken) return;
                    if (localExoMediaPlayer == mp) {
                        try {
                            mp.start();
                            startLocalProgressTicker();
                            Log.d(TAG, "Codec recovery: start() succeeded for videoId=" + track.videoId);
                        } catch (Exception e) {
                            Log.e(TAG, "Codec recovery: start() failed, skipping track", e);
                            releaseLocalExoMediaPlayer();
                            usingOfflineSource = false;
                            advanceToNextTrackAfterFailure();
                        }
                    }
                }, 500L);
                return true;
            }

            // Drop the cached stream URL on network/source errors (forces a fresh NewPipe resolve
            // on the next attempt), but not on codec crashes — those keep the same valid URL.
            if (networkSource && !isCodecError) {
                StreamResolver.markFailed(track.videoId);
            }

            if (localExoMediaPlayer == mp) {
                stopLocalProgressTicker();
                releaseLocalExoMediaPlayer();
                usingOfflineSource = false;
            } else {
                releaseSingleExoMediaPlayer(mp);
            }

            // AudioTrack init failure (status -12 / ENOMEM): reinitialize shared player
            // to force release stale AudioTrack resources held by the OS.
            // Only attempt reinit ONCE per playback token to prevent infinite loops on
            // low-memory devices where AudioFlinger cannot reclaim resources quickly.
            if (what == 5001 && isAdded()) {
                if (audioTrackReinitToken == requestToken) {
                    Log.w(TAG, "AudioTrack init failed again after reinit — falling through to next source/track");
                    // Fall through to normal onFailure handling below
                } else {
                    audioTrackReinitToken = requestToken;
                    Log.w(TAG, "AudioTrack init failed — reinitializing shared ExoPlayer");
                    ExoPlayerManager.INSTANCE.reinitialize(requireContext().getApplicationContext());
                    // Give the OS time to reclaim AudioTrack resources before retrying
                    // (3s is needed on low-end devices like SM-A032M with 2GB RAM)
                    if (requestToken == activePlaybackRequestToken) {
                        localProgressHandler.postDelayed(() -> {
                            if (!isAdded() || requestToken != activePlaybackRequestToken) return;
                            onFailure.run();
                        }, 3000L);
                    }
                    return true;
                }
            }

            if (requestToken != activePlaybackRequestToken || !isAdded()) {
                return true;
            }

            onFailure.run();
            return true;
        });

        try {
            if (isHttpStreamSource(source) && isAdded()) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", STREAM_HTTP_USER_AGENT);
                headers.put("Accept", "*/*");
                // Inject InnerTube-specific headers for direct googlevideo.com URLs
                Map<String, String> innertubeHeaders = StreamResolver.getHeadersFor(track.videoId);
                headers.putAll(innertubeHeaders);
                player.setDataSource(playbackAppContext, Uri.parse(source), headers);
            } else if (source.startsWith("content://") && isAdded()) {
                player.setDataSource(playbackAppContext, Uri.parse(source), null);
            } else {
                player.setDataSource(source);
            }
            player.prepareAsync();
            scheduleSourcePrepareTimeout(track, requestToken, player, onFailure);
        } catch (IllegalStateException ise) {
            cancelSourcePrepareTimeout();
            localSourcePreparing = false;
            Log.e(TAG, "startMediaPlaybackFromSource: ExoPlayer thread is dead, reinitializing manager", ise);
            ExoPlayerManager.INSTANCE.reinitialize(playbackAppContext);
            onFailure.run();
        } catch (Exception e) {
            cancelSourcePrepareTimeout();
            localSourcePreparing = false;
            Log.e(TAG, "startMediaPlaybackFromSource: setDataSource/prepareAsync failed for source="
                    + maskUrlForLog(source), e);
            if (localExoMediaPlayer == player) {
                releaseLocalExoMediaPlayer();
                usingOfflineSource = false;
            } else {
                releaseSingleExoMediaPlayer(player);
            }
            onFailure.run();
            return;
        }

        notifyPlaybackStateChanged();
    }

    /** One-frame coalescer flag for {@link #notifyPlaybackStateChanged()}. */
    private boolean playbackStateNotifyScheduled = false;

    private void notifyPlaybackStateChanged() {
        // Starting a track fires this ≥4 times in the same main-thread burst
        // (resetPlaybackStateForNewTrack, bindCurrentTrack, startMediaPlaybackFromSource,
        // setPlaybackUiForPreparedSource) — each pass rebuilding the media notification,
        // session metadata and cross-fragment sync. Coalesce the burst into ONE pass on the
        // next main-loop message; it reads current state at execution time so nothing is lost.
        if (playbackStateNotifyScheduled) return;
        playbackStateNotifyScheduled = true;
        localProgressHandler.post(() -> {
            playbackStateNotifyScheduled = false;
            if (!isAdded()) return;
            notifyPlaybackStateChangedNow();
        });
    }

    private void notifyPlaybackStateChangedNow() {
        updatePlayPauseIcon();
        updateMediaSessionMetadata();
        updateMediaSessionState();
        updateMediaNotification();
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(false);
    }

    private void setPlaybackUiForPreparedSource() {
        tvCurrentTime.setText(formatSeconds(currentSeconds));
        tvTotalTime.setText(formatSeconds(totalSeconds));
        int progress = Math.round((Math.max(0, currentSeconds) / (float) Math.max(1, totalSeconds)) * 1000f);
        sbPlaybackProgress.setProgress(Math.max(0, Math.min(1000, progress)));
        notifyPlaybackStateChanged();
        // Fire event bus directly on main thread so GlobalMiniPlayerController picks up
        // the prepared state immediately (bypasses persistPlaybackSnapshot debounce).
        PlaybackEventBus.notifyPlaybackSnapshotUpdated();
    }

    private void resetPlaybackStateForNewTrack() {
        currentSeconds = 0;
        accumulatedListenMs = 0;
        if (tvCurrentTime != null) tvCurrentTime.setText("00:00");
        if (tvTotalTime != null) tvTotalTime.setText("--:--");
        if (sbPlaybackProgress != null) {
            sbPlaybackProgress.setProgress(0);
            sbPlaybackProgress.setSecondaryProgress(0);
        }
        lastRenderedBuffered = -1;

        notifyPlaybackStateChanged();
    }

    private void releaseSingleExoMediaPlayer(@Nullable ExoMediaPlayer player) {
        if (player == null) {
            return;
        }
        try {
            player.stop();
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop player", e);
        }
        try {
            // releaseAsync: el stop/detach corre ya; el ExoPlayer.release() pesado (hasta ~500ms
            // en main) se difiere a otro mensaje del looper, fuera del frame del caller (gesto
            // de seek, commit de swap, cambio de pista).
            player.releaseAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to release player", e);
        }
    }

    @NonNull
    private List<String> buildDirectSourceCandidates(@NonNull PlayerTrack track) {
        LinkedHashSet<String> orderedSources = new LinkedHashSet<>();

        // Local device files — use content URI directly
        if (isAdded() && LocalFilesStore.isLocalVideoId(track.videoId)) {
            String contentUri = LocalFilesStore.getContentUriForVideoId(requireContext(), track.videoId);
            if (contentUri != null && !contentUri.isEmpty()) {
                orderedSources.add(contentUri);
            }
            return new ArrayList<>(orderedSources);
        }

        if (isAdded() && OfflineAudioStore.hasOfflineAudio(requireContext(), track.videoId)) {
            File file = OfflineAudioStore.getExistingOfflineAudioFile(requireContext(), track.videoId);
            if (file.isFile() && file.length() > 0L) {
                orderedSources.add(file.getAbsolutePath());
                // Decisión de arranque solo con el chequeo BARATO de existencia: la validación
                // completa (MediaMetadataRetriever) y el probe de video (MediaExtractor) eran dos
                // parseos síncronos del archivo entero EN EL MAIN THREAD antes del primer play —
                // ExoPlayer lo parsea igual en prepare() y un archivo corrupto cae por el camino
                // normal de error → re-resuelve online, así que no se pierde robustez.
                //
                // Probe de video en background: la superficie se adjunta con el valor cacheado
                // (false en el primer play); si el probe async descubre que el .mp4 SÍ trae video,
                // su callback re-anuncia el track al router, cuya rama sameUnderlying ahora
                // permite el attach tardío (el bug histórico del video descargado que mostraba
                // su carátula para siempre ya no puede volver por esta vía).
                if (!offlineVideoProbeCache.containsKey(track.videoId)) {
                    offlineFileHasVideoTrack(track.videoId);
                }
                final Context appCtx = requireContext().getApplicationContext();
                final String validateId = track.videoId;
                final String validateDuration = track.duration;
                backgroundExecutor.execute(() ->
                        OfflineAudioStore.hasValidatedOfflineAudio(appCtx, validateId, validateDuration));
            }
        }
        return new ArrayList<>(orderedSources);
    }

    /** Synchronous, cached MediaExtractor probe for a real video track in an offline file. Runs once
     *  per videoId (guarded by {@link #offlineVideoProbeCache}) on a local file we're about to open
     *  anyway, so the cost is negligible and only paid on the first play of each downloaded track. */
    private void ensureOfflineVideoProbeCached(@Nullable String videoId, @NonNull File file) {
        if (TextUtils.isEmpty(videoId) || offlineVideoProbeCache.containsKey(videoId)) return;
        boolean hasVideo = false;
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { hasVideo = true; break; }
            }
        } catch (Exception e) {
            hasVideo = false;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        putOfflineVideoProbe(videoId, hasVideo);
    }

    private static final int OFFLINE_PROBE_CACHE_MAX = 256;

    /** Store a probe result, bounding the cache so a very long listening session can't grow it
     *  without limit. Thread-safe: clear()/put() on the ConcurrentHashMap are atomic and a
     *  double-clear race is harmless. */
    private void putOfflineVideoProbe(@NonNull String videoId, boolean hasVideo) {
        if (offlineVideoProbeCache.size() >= OFFLINE_PROBE_CACHE_MAX) {
            offlineVideoProbeCache.clear();
        }
        offlineVideoProbeCache.put(videoId, hasVideo);
    }

    private void cancelPendingStreamResolver() {
        pendingResolutionVideoId = "";
        if (pendingStreamResolverFuture != null) {
            pendingStreamResolverFuture.cancel(true);
            pendingStreamResolverFuture = null;
        }
    }

    private boolean hasPendingStreamResolution() {
        return pendingStreamResolverFuture != null && !pendingStreamResolverFuture.isDone();
    }

    private boolean tryReresolveOrSkipCurrentTrack(@NonNull String reason, boolean forceAttempt) {
        if (!isAdded()) {
            return false;
        }

        if (tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return false;
        }

        PlayerTrack track = tracks.get(currentIndex);
        if (track == null || TextUtils.isEmpty(track.videoId)) {
            return false;
        }

        if (LocalFilesStore.isLocalVideoId(track.videoId)) {
            Log.w(TAG, "Local file playback failed: videoId=" + track.videoId + " — skipping to next.");
            moveTrack(1, false);
            return true;
        }

        if (!isNetworkAvailable()) {
            return false;
        }
        
        consecutiveStreamFailures++;

        Log.w(TAG, "[PLAYBACK_RETRY] resolution failed, scheduling retry. reason="
                + reason + " videoId=" + track.videoId + " attempt=" + consecutiveStreamFailures);
        stopLocalProgressTicker();
        releaseLocalExoMediaPlayer();
        usingOfflineSource = false;
        schedulePlaybackRetry(track.videoId);
        return true;
    }

    private boolean tryResolveAudiusAndReplay(@NonNull PlayerTrack track, long requestToken) {
        if (!isAdded() || !isNetworkAvailable()) {
            return false;
        }
        if (audiusFallbackAttemptedVideoIds.contains(track.videoId)) {
            return false;
        }

        audiusFallbackAttemptedVideoIds.add(track.videoId);
        if (audiusFallbackAttemptedVideoIds.size() > 50) {
            audiusFallbackAttemptedVideoIds.remove(audiusFallbackAttemptedVideoIds.iterator().next());
        }
        
        Log.w(TAG, "tryResolveAudiusAndReplay: start videoId=" + track.videoId + " requestToken=" + requestToken);

        cancelPendingStreamResolver();
        pendingStreamResolverFuture = streamResolverExecutor.submit(() -> {
            String resolved = fetchAudiusStreamUrl(track);

            localProgressHandler.post(() -> {
                pendingStreamResolverFuture = null;
                if (!isAdded()) {
                    return;
                }
                if (requestToken != activePlaybackRequestToken
                        || !TextUtils.equals(track.videoId, loadedVideoId)) {
                    return;
                }

                if (TextUtils.isEmpty(resolved)) {
                    stopPlaybackAfterErrors("No se pudo reproducir en YouTube ni encontrar audio alternativo gratuito.");
                    return;
                }

                resetPlaybackErrorState();
                playerEngineRecoveryAttempts = 0;
                startMediaPlaybackFromSource(
                        track,
                        resolved,
                        requestToken,
                        () -> stopPlaybackAfterErrors("No se pudo abrir la fuente de audio alternativa.")
                );
            });
        });
        return true;
    }

    @NonNull
    private String fetchAudiusStreamUrl(@NonNull PlayerTrack track) {
        try {
            String query = buildAudiusSearchQuery(track);
            if (TextUtils.isEmpty(query)) {
                Log.w(TAG, "fetchAudiusStreamUrl: empty query for videoId=" + track.videoId);
                return "";
            }
            Uri searchUri = Uri.parse(AUDIUS_API_BASE_URL)
                    .buildUpon()
                    .appendPath("tracks")
                    .appendPath("search")
                    .appendQueryParameter("query", query)
                    .appendQueryParameter("limit", String.valueOf(AUDIUS_SEARCH_LIMIT))
                    .appendQueryParameter("app_name", AUDIUS_APP_NAME)
                    .build();

            String searchBody = readTextResponse(searchUri.toString(), "application/json");
            if (TextUtils.isEmpty(searchBody)) {
                return "";
            }

            JSONObject root = new JSONObject(searchBody);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                Log.w(TAG, "fetchAudiusStreamUrl: no results for query='" + query + "'");
                return "";
            }
            String selectedTrackId = "";
            int selectedScore = Integer.MIN_VALUE;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                String candidateId = item.optString("id", "").trim();
                if (TextUtils.isEmpty(candidateId)) {
                    continue;
                }

                String candidateTitle = item.optString("title", "");
                JSONObject userObj = item.optJSONObject("user");
                String candidateArtist = userObj == null ? "" : userObj.optString("name", "");

                int score = scoreAudiusCandidate(track, candidateTitle, candidateArtist);
                if (score > selectedScore) {
                    selectedScore = score;
                    selectedTrackId = candidateId;
                }
            }

            if (TextUtils.isEmpty(selectedTrackId)) {
                Log.w(TAG, "fetchAudiusStreamUrl: no candidate selected for query='" + query + "'");
                return "";
            }
            Uri streamUri = Uri.parse(AUDIUS_API_BASE_URL)
                    .buildUpon()
                    .appendPath("tracks")
                    .appendPath(selectedTrackId)
                    .appendPath("stream")
                    .appendQueryParameter("app_name", AUDIUS_APP_NAME)
                    .build();
            return streamUri.toString();
        } catch (Exception e) {
            Log.e(TAG, "fetchAudiusStreamUrl: failed for videoId=" + track.videoId, e);
            return "";
        }
    }

    @NonNull
    private String buildAudiusSearchQuery(@NonNull PlayerTrack track) {
        String title = track.title == null ? "" : track.title.trim();
        String artist = track.artist == null ? "" : track.artist.trim();
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(artist)) {
            return "";
        }
        if (TextUtils.isEmpty(artist)) {
            return title;
        }
        if (TextUtils.isEmpty(title)) {
            return artist;
        }
        return title + " " + artist;
    }

    private int scoreAudiusCandidate(
            @NonNull PlayerTrack track,
            @NonNull String candidateTitle,
            @NonNull String candidateArtist
    ) {
        String wantedTitle = normalizeForMatch(track.title);
        String wantedArtist = normalizeForMatch(track.artist);
        String foundTitle = normalizeForMatch(candidateTitle);
        String foundArtist = normalizeForMatch(candidateArtist);

        int score = 0;
        if (!TextUtils.isEmpty(wantedTitle) && !TextUtils.isEmpty(foundTitle)) {
            if (TextUtils.equals(wantedTitle, foundTitle)) {
                score += 120;
            } else if (foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle)) {
                score += 70;
            }
        }

        if (!TextUtils.isEmpty(wantedArtist) && !TextUtils.isEmpty(foundArtist)) {
            if (TextUtils.equals(wantedArtist, foundArtist)) {
                score += 70;
            } else if (foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist)) {
                score += 45;
            }
        }

        if (score == 0) {
            score = Math.max(0, foundTitle.length() - 2);
        }
        return score;
    }

    @NonNull
    private String normalizeForMatch(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private boolean isHttpStreamSource(@NonNull String source) {
        return source.startsWith("https://") || source.startsWith("http://");
    }

    private void scheduleSourcePrepareTimeout(
            @NonNull PlayerTrack track,
            long requestToken,
            @NonNull ExoMediaPlayer player,
            @NonNull Runnable onFailure
    ) {
        cancelSourcePrepareTimeout();
        sourcePrepareTimeoutRunnable = () -> {
            if (!isAdded()) {
                return;
            }
            if (localExoMediaPlayer != player
                    || requestToken != activePlaybackRequestToken
                    || !TextUtils.equals(track.videoId, loadedVideoId)) {
                return;
            }

            Log.e(TAG, "scheduleSourcePrepareTimeout: prepare timeout reached for videoId=" + track.videoId
                    + " token=" + requestToken
                    + " timeoutMs=" + SOURCE_PREPARE_TIMEOUT_MS);
        localSourcePreparing = false;
            stopLocalProgressTicker();
            releaseLocalExoMediaPlayer();
            onFailure.run();
        };
        localProgressHandler.postDelayed(sourcePrepareTimeoutRunnable, SOURCE_PREPARE_TIMEOUT_MS);
    }

    private void cancelSourcePrepareTimeout() {
        if (sourcePrepareTimeoutRunnable != null) {
            localProgressHandler.removeCallbacks(sourcePrepareTimeoutRunnable);
            sourcePrepareTimeoutRunnable = null;
        }
    }

    private void markPlaybackUnavailable(@NonNull String message) {
        Log.e(TAG, "markPlaybackUnavailable: " + message
            + " loadedVideoId=" + loadedVideoId
            + " activeToken=" + activePlaybackRequestToken);
        cancelSourcePrepareTimeout();
        stopLocalProgressTicker();
        releaseLocalExoMediaPlayer();
        usingOfflineSource = false;

        updatePlayerSurfaceForSource();
        updatePlayPauseIcon();
        updateMediaSessionMetadata();
        updateMediaSessionState();
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(false);

        if (!isNetworkAvailable()) {
            // Offline and this track has no usable source. Skip forward to the nearest
            // downloaded track rather than scheduling a retry that can never succeed.
            boolean hasAnyOffline = false;
            if (isAdded()) {
                for (PlayerTrack t : tracks) {
                    if (t != null && !TextUtils.isEmpty(t.videoId)
                            && (LocalFilesStore.isLocalVideoId(t.videoId)
                                || OfflineAudioStore.hasOfflineAudio(requireContext(), t.videoId))) {
                        hasAnyOffline = true;
                        break;
                    }
                }
            }
            if (hasAnyOffline) {
                moveTrack(1, false);
            }
            // else: nothing to play offline — stay stopped, no retry.
            return;
        }

        // Stay on current track and keep retrying (network available)
        if (!TextUtils.isEmpty(loadedVideoId)) {
            schedulePlaybackRetry(loadedVideoId);
        }
    }

    @NonNull
    private String readTextResponse(@NonNull String urlValue, @NonNull String accept) {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(urlValue);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Sleppify-Stream/1.0");
            connection.setRequestProperty("Accept", accept);
            connection.setUseCaches(false);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.e(TAG, "readTextResponse: non-2xx code=" + code
                        + " url=" + maskUrlForLog(urlValue)
                        + " body=" + safeTrim(readErrorResponse(connection), 320));
                return "";
            }

            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            Log.e(TAG, "readTextResponse: exception url=" + maskUrlForLog(urlValue), e);
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @NonNull
    private String readErrorResponse(@NonNull HttpURLConnection connection) {
        InputStream errorStream = null;
        try {
            errorStream = connection.getErrorStream();
            if (errorStream == null) {
                return "";
            }
            try (BufferedInputStream in = new BufferedInputStream(errorStream);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    @NonNull
    private String safeTrim(@Nullable String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLen)) + "...";
    }

    @NonNull
    private String maskUrlForLog(@Nullable String value) {
        if (value == null) {
            return "";
        }
        int tokenIndex = value.indexOf("token=");
        if (tokenIndex < 0) {
            return value;
        }
        int amp = value.indexOf('&', tokenIndex);
        if (amp < 0) {
            return value.substring(0, tokenIndex) + "token=***";
        }
        return value.substring(0, tokenIndex) + "token=***" + value.substring(amp);
    }

    private boolean cachedNetworkAvailable = false;
    private long cachedNetworkCheckedAtMs = 0L;
    private static final long NETWORK_CACHE_TTL_MS = 2000L;

    /** Cached network reachability. The raw check does ConnectivityManager Binder calls
     *  (getActiveNetwork + getNetworkCapabilities), and this is polled on every 500ms progress
     *  tick plus many playback paths — querying the system service that often is needless IPC.
     *  A ~2s TTL keeps it effectively real-time for playback decisions while cutting the churn. */
    private boolean isNetworkAvailable() {
        if (!isAdded()) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (cachedNetworkCheckedAtMs != 0L && now - cachedNetworkCheckedAtMs < NETWORK_CACHE_TTL_MS) {
            return cachedNetworkAvailable;
        }
        boolean available = queryNetworkAvailable();
        cachedNetworkAvailable = available;
        cachedNetworkCheckedAtMs = now;
        return available;
    }

    private boolean queryNetworkAvailable() {
        ConnectivityManager cm = ContextCompat.getSystemService(requireContext(), ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        if (capabilities == null) {
            return false;
        }
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private int resolveNextIndexForCompletionCrossfade() {
        if (tracks.isEmpty()) {
            return -1;
        }

        if (repeatMode == REPEAT_MODE_ONE) {
            return -1;
        }

        if (currentIndex < tracks.size() - 1) {
            return currentIndex + 1;
        }

        if (repeatMode == REPEAT_MODE_ALL) {
            return 0;
        }

        return -1;
    }

    private void cancelOfflineCrossfade() {
        cancelCrossfadeOnly();
        cancelGaplessPreBuffer();
    }

    /** Cancela el crossfade en curso (volumen, ticker, player entrante) SIN tocar el pre-buffer
     *  gapless. Los seeks dentro de la pista actual usan esta variante: el siguiente track sigue
     *  siendo el mismo, así que su player pre-buffereado sigue siendo válido — y liberarlo
     *  implicaba un ExoPlayer.release() bloqueante en pleno gesto. */
    private void cancelCrossfadeOnly() {
        crossfadeManager.cancelAndRestoreVolume(localExoMediaPlayer);
        localCrossfadeInProgress = false;
        localCrossfadeIsNetwork = false;
        localCrossfadeStartedAtMs = 0L;
        localCrossfadeTargetIndex = -1;
        if (localCrossfadeTicker != null) {
            localProgressHandler.removeCallbacks(localCrossfadeTicker);
            localCrossfadeTicker = null;
        }
        if (localCrossfadeIncomingPlayer != null) {
            releaseSingleExoMediaPlayer(localCrossfadeIncomingPlayer);
            localCrossfadeIncomingPlayer = null;
        }
    }

    private void handleCrossfadeFinished(@NonNull ExoMediaPlayer incoming, int nextIndex, boolean wasNetwork) {
        if (!isAdded() || nextIndex < 0 || nextIndex >= tracks.size()) {
            releaseSingleExoMediaPlayer(incoming);
            return;
        }

        localExoMediaPlayer = incoming;
        // Promoción del crossfade: el entrante pasa a ser el audible — mueve el EQ a su sesión.
        localExoMediaPlayer.markAsActiveForEq();
        localExoMediaPlayer.isCrossfadeComponent = false;
        usingOfflineSource = !wasNetwork;
        accumulatedListenMs = 0;
        gaplessPreBufferTriggered = false;
        gaplessPreBufferingVideoId = "";
        currentIndex = nextIndex;
        pauseRequestedByUser = false;
        isPlaying = true;
        currentSeconds = 0;
        // Per-track state that playCurrentTrack resets but this commit path used to miss:
        // without these, every crossfade-adopted track lost its play count (the flag was
        // still true from the previous track) and skipped its first snapshot persist.
        playCountRecordedForCurrentTrack = false;
        lastSnapshotPersistSecond = -1;

        PlayerTrack track = tracks.get(currentIndex);
        loadedVideoId = track.videoId;
        if (TextUtils.isEmpty(loadedVideoId)) {
            loadedVideoId = "";
        }
        loadedTrackIsVideo = isVideoTrackId(loadedVideoId);

        // Resolve the offline .mp4 across BOTH volumes (internal + SD): building the path with the
        // write-dir API pointed at a nonexistent file whenever the download lived on the other
        // volume (e.g. after flipping "Usar tarjeta SD"). Committed-source flag captured with the
        // same isVideoTrack(track) the surface attach uses (usingOfflineSource already set above).
        currentSourceIsVideo = isVideoTrack(track);
        java.io.File existingVideo = (!wasNetwork && isAdded())
                ? OfflineAudioStore.findExistingOfflineVideoFile(requireContext(), track.videoId)
                : null;
        currentVideoFilePath = existingVideo != null ? existingVideo.getAbsolutePath() : null;
        updatePlayerSurfaceForSource();

        incoming.setOnCompletionListener(mp -> {
            if (localExoMediaPlayer != mp) {
                return;
            }
            // Route through handleLocalPlaybackCompletion for its isInProgress() guard.
            // Without it, when THIS track later crossfades into the next one and reaches
            // its natural end mid-fade, this listener fired handleTrackEnded, which
            // cancelled the in-flight fade and restarted the incoming track from zero —
            // breaking every second crossfade in a continuously crossfading queue.
            handleLocalPlaybackCompletion();
        });
        incoming.setOnErrorListener((mp, what, extra) -> {
            if (localExoMediaPlayer == mp) {
                stopLocalProgressTicker();
                releaseLocalExoMediaPlayer();
                usingOfflineSource = false;
            } else {
                releaseSingleExoMediaPlayer(mp);
            }
            advanceToNextTrackAfterFailure();
            return true;
        });

        try {
            totalSeconds = Math.max(1, incoming.getDuration() / 1000);
        } catch (Exception ignored) {
            totalSeconds = 1;
        }

        bindCurrentTrackInternal(false, true);
        startLocalProgressTicker();
        updatePlayPauseIcon();
        updateMediaSessionState();

        // Attach video surface for crossfaded track
        if (localExoMediaPlayer != null) {
            videoRouter.onTrackStarted(localExoMediaPlayer, track.videoId, isVideoTrack(track));
        }

        prefetchedNextVideoId = null;
        prefetchNextTrackStream();

        // Stream-as-download: save crossfaded network track offline
        if (wasNetwork) {
            maybeSaveStreamedTrackOffline(track.videoId);
        }
    }

    private void startLocalProgressTicker() {
        localProgressHandler.removeCallbacks(localProgressTicker);
        localProgressHandler.post(localProgressTicker);
    }

    private void stopLocalProgressTicker() {
        localProgressHandler.removeCallbacks(localProgressTicker);
    }

    private void loadNotificationArtworkOnly(@NonNull PlayerTrack track) {
        if (!isAdded() || getContext() == null) return;
        String videoId = track.videoId == null ? "" : track.videoId.trim();
        String imageUrl = track.imageUrl == null ? "" : track.imageUrl.trim();
        if (TextUtils.isEmpty(videoId) && TextUtils.isEmpty(imageUrl)) return;

        // Skip if already cached for this exact track — disk cache will serve it instantly anyway.
        if (TextUtils.equals(videoId, mediaSessionArtworkVideoId)
                && mediaSessionArtwork != null
                && !mediaSessionArtwork.isRecycled()) {
            return;
        }

        final String notifVideoId = videoId;

        // Use applicationContext so this request outlives fragment hide/show.
        Context appCtx = getContext() != null ? getContext().getApplicationContext() : null;
        if (appCtx == null) return;

        // Local files: resolve the file's OWN embedded picture. Never fall back to a YouTube
        // thumbnail (a "local_<id>" videoId would build a URL that always 404s).
        if (LocalFilesStore.isLocalVideoId(videoId)) {
            LocalArtworkResolver.loadBytes(appCtx, videoId, bytes -> {
                if (!isAdded() || bytes == null) return; // no embedded art -> launcher-icon fallback
                Glide.with(appCtx).asBitmap()
                    .load(bytes)
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .signature(new com.bumptech.glide.signature.ObjectKey("localart:" + notifVideoId))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .override(256, 256)
                    .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource,
                                @Nullable Transition<? super Bitmap> transition) {
                            if (!isAdded()) return;
                            cacheMediaNotificationArtwork(notifVideoId, resource);
                            updateMediaSessionMetadata();
                            updateMediaNotification();
                        }
                        @Override
                        public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        @Override
                        public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                            Log.w(TAG, "loadNotificationArtworkOnly: local decode failed for " + notifVideoId);
                        }
                    });
            });
            return;
        }

        // Primary URL: track's own imageUrl. Fallback: YouTube hqdefault thumbnail.
        final String primaryUrl = !TextUtils.isEmpty(imageUrl) ? imageUrl
                : "https://i.ytimg.com/vi/" + Uri.encode(videoId) + "/hqdefault.jpg";
        final String fallbackUrl = "https://i.ytimg.com/vi/" + Uri.encode(videoId) + "/hqdefault.jpg";

        com.bumptech.glide.RequestManager rm = Glide.with(appCtx);
        rm.asBitmap()
            .load(primaryUrl)
            .transform(SHARED_YT_CROP)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(256, 256)
            .error(
                rm.asBitmap()
                    .load(fallbackUrl)
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(256, 256)
            )
            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource,
                        @Nullable Transition<? super Bitmap> transition) {
                    if (!isAdded()) return;
                    cacheMediaNotificationArtwork(notifVideoId, resource);
                    updateMediaSessionMetadata();
                    updateMediaNotification();
                }
                @Override
                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                @Override
                public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                    Log.w(TAG, "loadNotificationArtworkOnly: failed for videoId=" + notifVideoId);
                }
            });
    }



    @Override
    public void onDestroy() {
        cancelSourcePrepareTimeout();
        cancelAutoplayRecovery();
        cancelPlaybackErrorRetry();
        stopLocalProgressTicker();
        releaseLocalExoMediaPlayer();
        cancelPendingStreamResolver();
        streamResolverExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();
        backgroundDownloadExecutor.shutdownNow();
        super.onDestroy();
    }

    private void animateBackgroundTransition(android.graphics.drawable.Drawable newDrawable) {
        if (playerBackgroundContainer == null) return;
        android.graphics.drawable.Drawable oldDrawable = playerBackgroundContainer.getBackground();
        if (oldDrawable == null) {
            oldDrawable = new android.graphics.drawable.ColorDrawable(Color.BLACK);
        } else if (oldDrawable instanceof android.graphics.drawable.TransitionDrawable) {
            android.graphics.drawable.TransitionDrawable td = (android.graphics.drawable.TransitionDrawable) oldDrawable;
            if (td.getNumberOfLayers() > 1) {
                oldDrawable = td.getDrawable(1);
            } else if (td.getNumberOfLayers() > 0) {
                oldDrawable = td.getDrawable(0);
            }
        }
        
        android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[]{oldDrawable, newDrawable};
        android.graphics.drawable.TransitionDrawable transition = new android.graphics.drawable.TransitionDrawable(layers);
        // crossFade(true) fades the OLD layer out while the new fades in, so at mid-transition
        // both are translucent and the black window background bleeds through — every track
        // change flashed dark instead of fading color→color. With crossFade off the old color
        // stays fully opaque underneath while the new one fades in over it.
        transition.setCrossFadeEnabled(false);
        playerBackgroundContainer.setBackground(transition);
        transition.startTransition(400); // 400ms fade transition
    }

    /** Builds the player's vertical background gradient from a dominant color: the color at the top
     *  fading to a soft dark end (22% brightness) at the bottom. Single source for every backdrop so
     *  the artwork bind, the applyDominantColorBackdrop path, and the return-to-song restore all match. */
    private GradientDrawable buildDominantGradient(int dominantColor) {
        int r = Color.red(dominantColor);
        int g = Color.green(dominantColor);
        int b = Color.blue(dominantColor);
        int colorEnd = Color.rgb((int) (r * 0.22f), (int) (g * 0.22f), (int) (b * 0.22f));
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { dominantColor, colorEnd });
    }

    /** Extrae el color dominante de {@code bitmap} y aplica el gradiente vertical de fondo, igual
     *  que el onResourceReady de la carátula. Se salta por completo si la presentación actual es
     *  VIDEO (BUG 1: el fondo del video debe ser negro puro, nunca el color dominante), tanto antes
     *  de lanzar Palette como dentro de su callback async por si el modo cambia entre medias. */
    private void applyDominantColorBackdrop(@Nullable android.graphics.Bitmap bitmap) {
        if (bitmap == null || playerBackgroundContainer == null) return;
        if (currentSourceIsVideo) return;
        Palette.from(bitmap).generate(palette -> {
            if (palette == null || !isAdded() || playerBackgroundContainer == null) return;
            if (currentSourceIsVideo) return; // se entró a video mientras se generaba → dejar negro
            int dominantColor = palette.getDominantColor(0xFF121212);
            // Cache so a later Video→Canción swap can restore this exact color instantly (no recompute).
            lastSongDominantColor = dominantColor;
            lastSongColorValid = true;
            animateBackgroundTransition(buildDominantGradient(dominantColor));
        });
    }

    private void bindCurrentTrack(boolean allowResume) {
        bindCurrentTrackInternal(allowResume, false);
    }

    /**
     * Bind LIGERO del hero (título/artista/tiempos) para que el reproductor muestre su metadata al
     * instante mientras se desliza, en vez de quedar en blanco los ~320ms hasta phase2. SOLO texto +
     * seekbar; carátula/Palette/MediaSession/artwork siguen en phase2 tras la animación de entrada.
     * bindCurrentTrack los reescribe luego de forma idempotente.
     */
    private void bindHeroPresentationEarly() {
        if (tracks.isEmpty()) return;
        if (currentIndex < 0 || currentIndex >= tracks.size()) currentIndex = 0;
        PlayerTrack track = tracks.get(currentIndex);
        if (tvPlayerTitle != null) {
            tvPlayerTitle.setText(track.title);
            tvPlayerTitle.setSelected(true);
        }
        if (tvPlayerArtist != null) tvPlayerArtist.setText(track.artist);
        int total = Math.max(1, parseDurationSeconds(track.duration));
        if (tvCurrentTime != null) tvCurrentTime.setText(formatSeconds(0));
        if (tvTotalTime != null) {
            tvTotalTime.setText(TextUtils.isEmpty(track.duration) ? formatSeconds(total) : track.duration);
        }
        if (sbPlaybackProgress != null) {
            sbPlaybackProgress.setProgress(0);
            sbPlaybackProgress.setSecondaryProgress(0);
        }
    }

    private void bindCurrentTrackInternal(boolean allowResume, boolean forceZero) {
        if (tracks.isEmpty()) {
            return;
        }
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            currentIndex = 0;
        }

        PlayerTrack track = tracks.get(currentIndex);
        tvPlayerTitle.setText(track.title);
        tvPlayerTitle.setSelected(true);
        tvPlayerArtist.setText(track.artist);
        boolean isLocalFile = LocalFilesStore.isLocalVideoId(track.videoId);
        // Hide irrelevant chips for local files
        View likeDislikeChip = (actionLike != null) ? (View) actionLike.getParent() : null;
        if (likeDislikeChip != null) likeDislikeChip.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);
        if (actionComments != null) actionComments.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);
        if (actionRadio != null) actionRadio.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);
        if (actionShare != null) actionShare.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);
        if (actionDownloadTrack != null) actionDownloadTrack.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);
        if (actionGoToArtist != null) actionGoToArtist.setVisibility(!TextUtils.isEmpty(track.artist) ? View.VISIBLE : View.GONE);
        if (llSimilarTrigger != null) llSimilarTrigger.setVisibility(isLocalFile ? View.GONE : View.VISIBLE);

        refreshSocialActionsForCurrentTrack(track);
        refreshFavoriteActionForCurrentTrack();
        refreshVideoPillAvailability(track);
        if (!isLocalFile) refreshDownloadChipState();
        scheduleCommentsPrefetch(track, isLocalFile);

        totalSeconds = Math.max(1, parseDurationSeconds(track.duration));
        currentSeconds = 0;

        // New track: invalidate the ticker's render cache so the labels/seekbar re-sync even if
        // the new second/duration numerically matches the previous track's last rendered value.
        lastRenderedCurrentSeconds = -1;
        lastRenderedTotalSeconds = -1;
        lastRenderedProgress = -1;
        lastRenderedBuffered = -1;
        tvCurrentTime.setText(formatSeconds(currentSeconds));
        tvTotalTime.setText(TextUtils.isEmpty(track.duration) ? formatSeconds(totalSeconds) : track.duration);

        int progress = Math.round((currentSeconds / (float) Math.max(1, totalSeconds)) * 1000f);
        sbPlaybackProgress.setProgress(Math.max(0, Math.min(1000, progress)));
        sbPlaybackProgress.setSecondaryProgress(0);
        updatePlayPauseIcon();

        boolean bootstrapArtwork = playerArtworkBootstrapPending;
        if (bootstrapArtwork) {
            playerArtworkBootstrapPending = false;
        }

        loadArtworkForCurrentTrack(track);

        // Load notification/MediaSession artwork only
        loadNotificationArtworkOnly(track);
        if (bootstrapArtwork) {
            refreshNextUp();
        }

        updateMediaSessionMetadata();
        updateMediaSessionState();
        if (bootstrapArtwork) {
            cancelNextUpReveal();
            nextUpTracks.clear();
            if (nextUpAdapter != null) {
                nextUpAdapter.setItems(nextUpTracks);
            }
        } else {
            refreshNextUp();
        }
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(false);
    }

    /** Carga la carátula + color dominante de la pista en el hero (rama música) o aplica la
     *  presentación de video (rama video). Extraído de bindCurrentTrackInternal para que el
     *  restore de Video→Canción pueda relanzar SOLO la carga de arte cuando el cover quedó sin
     *  drawable y sin cache válido — sin re-bindear tiempos/metadata de la pista en curso. */
    private void loadArtworkForCurrentTrack(@NonNull PlayerTrack track) {
        if (!isAdded()) {
            return;
        }
        boolean isLocalVideo = isVideoPresentation(track);

        if (ivPlayerCover != null) {
            // Invalidate any in-flight artwork delivery from a previous bind and cancel its
            // Glide request. CustomTargets are not view-bound, so without this a late result
            // from the PREVIOUS track repainted the cover over a playing video, applied the
            // wrong hero ratio, or recolored the backdrop with another song's palette.
            final int artworkGen = ++playerArtworkGeneration;
            if (playerCoverTarget != null) {
                Glide.with(this).clear(playerCoverTarget);
                playerCoverTarget = null;
            }

            if (isLocalVideo) {
                // VIDEO: no backdrop, no cover — just the video surface on black. Shape the hero
                // full-width (16:9) so the surface is not boxed into the song cover's inset square.
                applyHeroShapeForVideo();
                if (ivPlayerCover.getVisibility() == View.VISIBLE) {
                    ivPlayerCover.animate().cancel();
                    ivPlayerCover.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                        ivPlayerCover.setVisibility(View.GONE);
                    }).start();
                } else {
                    ivPlayerCover.setVisibility(View.GONE);
                }
                if (playerBackgroundContainer != null) {
                    animateBackgroundTransition(new android.graphics.drawable.ColorDrawable(Color.BLACK));
                }
            } else {
                // MUSIC (NewPipe stream / download / local file): smart-cropped cover with
                // a dominant-color gradient backdrop.
                boolean coverWasHidden = ivPlayerCover.getVisibility() != View.VISIBLE;
                ivPlayerCover.animate().cancel();
                ivPlayerCover.setVisibility(View.VISIBLE);
                if (coverWasHidden) {
                    // Coming from video mode: the ImageView still holds artwork from an
                    // older track. Keep it transparent until the new artwork lands so the
                    // stale art never flashes; onResourceReady fades the new one in.
                    ivPlayerCover.setImageDrawable(null);
                    ivPlayerCover.setAlpha(0f);
                } else {
                    ivPlayerCover.setAlpha(1f);
                }

                // Convert low-res image URL to HD
                String hdUrl = getHdImageUrl(track.imageUrl, track.videoId);

                playerCoverTarget = new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            if (!isAdded() || artworkGen != playerArtworkGeneration) return;

                            // BUG 1 + BUG 2: si para cuando la carátula llegó (Glide es async) la
                            // presentación YA se comprometió como VIDEO —el commit de la fuente ocurrió
                            // mientras la imagen cargaba, típico al avanzar de un video al siguiente— NO
                            // aplicar la presentación de música. Reformaría el hero a cuadrado sobre el
                            // video (se ve como «canción»/audio, BUG 2) y el Palette de abajo repintaría
                            // el fondo con el color dominante en vez de negro (BUG 1).
                            // updatePlayerSurfaceForSource ya dejó el video (16:9, fondo negro, carátula
                            // oculta); una entrega tardía de carátula NO debe corromperlo. PERO la
                            // entrega igual se CACHEA (solo se saltan las mutaciones de vista): el
                            // restore de Video→Canción depende de este cache, y descartar el bitmap
                            // aquí era lo que dejaba el hero negro sin carátula ni color al volver.
                            if (currentSourceIsVideo) {
                                lastSongCoverBitmap = resource;
                                lastSongArtVideoId = track.videoId;
                                Palette.from(resource).generate(palette -> {
                                    if (palette == null || artworkGen != playerArtworkGeneration) return;
                                    lastSongDominantColor = palette.getDominantColor(0xFF121212);
                                    lastSongColorValid = true;
                                });
                                return;
                            }

                            // Shape the hero to match THIS artwork during the alpha-0 swap below.
                            // Reshaping while the previous bitmap is still visible re-crops that
                            // bitmap (centerCrop) into the new aspect — that re-crop is the
                            // "distortion" and the jump from full-bleed 16:9 to a bordered square
                            // the user saw on every track change.
                            final float aspect = (float) resource.getWidth() / Math.max(1, resource.getHeight());
                            final int srcW = resource.getWidth();
                            final int srcH = resource.getHeight();

                            // Apply the artwork SYNCHRONOUSLY here — never deferred into an
                            // animation's withEndAction. That end-action only runs if the fade
                            // completes naturally; a competing ivPlayerCover.animate().cancel()
                            // from a rapid follow-up bind (or updatePlayerSurfaceForSource)
                            // dropped it, leaving the cover stuck (blank / previous art) while
                            // the Palette color below — applied directly — still landed. That is
                            // the "dominant color changes but the next song's image never
                            // appears" bug when songs auto-advance one after another. Hiding the
                            // cover first (alpha 0) still prevents the hero reshape from
                            // re-cropping the outgoing bitmap on screen.
                            boolean coverHasContent = ivPlayerCover.getDrawable() != null
                                    && ivPlayerCover.getAlpha() > 0.01f;
                            ivPlayerCover.animate().cancel();
                            ivPlayerCover.setAlpha(0f);
                            applyHeroShapeForAspect(aspect, srcW, srcH, artworkGen);
                            ivPlayerCover.setImageBitmap(resource);
                            // Cache the song cover so a later Video→Canción swap (which does not
                            // re-bind artwork) can restore it instantly and deterministically.
                            lastSongCoverBitmap = resource;
                            lastSongArtVideoId = track.videoId;
                            ivPlayerCover.animate().alpha(1f).setDuration(coverHasContent ? 240 : 260).start();
                            if (pbVideoLoading != null) {
                                pbVideoLoading.setVisibility(View.GONE);
                            }

                            // Extract dominant color and set vertical gradient (no boost — the raw
                            // dominant swatch is used as-is per design preference).
                            Palette.from(resource).generate(palette -> {
                                if (palette == null || !isAdded() || artworkGen != playerArtworkGeneration) return;
                                int dominantColor = palette.getDominantColor(0xFF121212);
                                // Cache so a later Video→Canción swap restores this exact color instantly.
                                // El cache corre ANTES del guard de video: aunque el fondo deba seguir
                                // negro ahora, el color queda listo para el restore.
                                lastSongDominantColor = dominantColor;
                                lastSongColorValid = true;
                                // BUG 1: Palette.generate es async; si entre tanto se entró a VIDEO
                                // (swap de modo), el fondo debe quedar negro puro — no repintar con el
                                // color dominante.
                                if (currentSourceIsVideo) return;

                                if (playerBackgroundContainer != null) {
                                    animateBackgroundTransition(buildDominantGradient(dominantColor));
                                }
                            });
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            if (!isAdded() || artworkGen != playerArtworkGeneration) return;
                            // BUG 1 + BUG 2: mismo motivo que onResourceReady — si la presentación ya
                            // se comprometió como VIDEO, no aplicar el hero cuadrado + icono + fondo
                            // gris de música sobre el video.
                            if (currentSourceIsVideo) return;
                            // No artwork available (e.g. local file without album art): neutral
                            // music icon on a square hero + dark backdrop, instead of inheriting
                            // the previous track's shape or leaving the cover stuck at alpha 0.
                            ivPlayerCover.animate().cancel();
                            applyHeroShapeForAspect(1f, 1, 1, artworkGen);
                            ivPlayerCover.setImageResource(R.drawable.ic_music);
                            ivPlayerCover.setAlpha(1f);
                            // No artwork for this track: drop any cached song bitmap/color so a later
                            // return-to-song restore never resurrects the PREVIOUS track's cover/color.
                            lastSongCoverBitmap = null;
                            lastSongArtVideoId = null;
                            lastSongColorValid = false;
                            if (playerBackgroundContainer != null) {
                                animateBackgroundTransition(new android.graphics.drawable.ColorDrawable(0xFF161616));
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}
                    };

                if (LocalFilesStore.isLocalVideoId(track.videoId)) {
                    // Local track: resolve the file's own embedded picture off the main thread,
                    // then feed the bytes into the same cover target (Palette + hero shaping).
                    final CustomTarget<Bitmap> coverTarget = playerCoverTarget;
                    final String localVideoId = track.videoId;
                    LocalArtworkResolver.loadBytes(requireContext(), localVideoId, bytes -> {
                        if (!isAdded() || artworkGen != playerArtworkGeneration || coverTarget == null) return;
                        if (bytes == null) {
                            coverTarget.onLoadFailed(null);
                            return;
                        }
                        Glide.with(this)
                            .asBitmap()
                            .load(bytes)
                            .transform(SHARED_YT_CROP)
                            .signature(new com.bumptech.glide.signature.ObjectKey("localart:" + localVideoId))
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(coverTarget);
                    });
                } else {
                    Glide.with(this)
                        .asBitmap()
                        .load(hdUrl)
                        .transform(SHARED_YT_CROP)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .error(
                            Glide.with(this)
                                .asBitmap()
                                .load(track.imageUrl)
                                .transform(SHARED_YT_CROP)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                        )
                        .into(playerCoverTarget);
                }
            }
        }
    }

    private String getHdImageUrl(String url, String videoId) {
        // Local files have no remote thumbnail — art is resolved per-file from the embedded
        // picture by LocalArtworkResolver. Never synthesize a (bogus) YouTube URL for them.
        if (LocalFilesStore.isLocalVideoId(videoId)) {
            return "";
        }
        if (TextUtils.isEmpty(url)) {
            if (!TextUtils.isEmpty(videoId)) {
                return "https://i.ytimg.com/vi/" + videoId + "/hq720.jpg";
            }
            return "";
        }
        // If it is a Google user content URL (YouTube Music thumbnail)
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            String hdUrl = url.replaceAll("=[ws]\\d+(-[ws]\\d+)*.*", "=w720-h720-l90-rj");
            if (!hdUrl.contains("=w720")) {
                hdUrl = url + "=w720-h720-l90-rj";
            }
            return hdUrl;
        }
        // If it is a standard YouTube thumbnail URL
        if (url.contains("i.ytimg.com") || url.contains("img.youtube.com")) {
            if (!TextUtils.isEmpty(videoId)) {
                return "https://i.ytimg.com/vi/" + videoId + "/hq720.jpg";
            }
            return url.replace("default.jpg", "hq720.jpg")
                      .replace("mqdefault.jpg", "hq720.jpg")
                      .replace("hqdefault.jpg", "hq720.jpg");
        }
        return url;
    }

    /** Applies the hero's aspect ratio + chrome for a given cover aspect, in one shot. Wide art
     *  (aspect > 1.2) goes full-bleed and flat; everything else is a rounded square with side
     *  margins. Centralised so the success and failure artwork paths shape the hero identically,
     *  and so callers can apply it atomically with the bitmap swap (no mid-swap re-crop). */
    private void applyHeroShapeForAspect(float aspect, int srcW, int srcH, int gen) {
        if (flPlayerHero == null || getContext() == null) return;
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams p =
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) flPlayerHero.getLayoutParams();
        if (p == null) return;
        float density = getResources().getDisplayMetrics().density;
        p.height = 0; // MATCH_CONSTRAINT
        if (aspect > 1.2f) {
            p.leftMargin = 0;
            p.rightMargin = 0;
            p.dimensionRatio = "H," + Math.max(1, srcW) + ":" + Math.max(1, srcH);
            flPlayerHero.setBackground(androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.bg_player_cover_flat));
        } else {
            p.leftMargin = (int) (20 * density);
            p.rightMargin = (int) (20 * density);
            p.dimensionRatio = "H,1:1";
            flPlayerHero.setBackground(androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.bg_player_cover_rounded));
        }
        flPlayerHero.setLayoutParams(p);
        heroRatioAppliedGeneration = gen;
        heroShapedForVideo = false; // this is a music (cover) shape
    }

    /** Shapes the hero as a FULL-WIDTH video box: no side margins and a landscape 16:9 ratio, flat
     *  chrome. The song-cover shaping insets square art by 20dp per side and forces a 1:1 box — left
     *  in place across a swap, that inset is exactly why the video rendered narrow and cropped. The
     *  single reused PlayerView (RESIZE_MODE_FIXED_WIDTH) then fills the full width regardless of the
     *  clip's own aspect. Analogous to the wide-art (aspect>1.2) branch of applyHeroShapeForAspect. */
    private void applyHeroShapeForVideo() {
        if (flPlayerHero == null || getContext() == null) return;
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams p =
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) flPlayerHero.getLayoutParams();
        if (p == null) return;
        if (heroShapedForVideo && "H,16:9".equals(p.dimensionRatio)
                && p.leftMargin == 0 && p.rightMargin == 0) {
            return; // ya está en forma de video: no re-disparar layout
        }
        p.height = 0; // MATCH_CONSTRAINT — el ancho lo llena start↔end, alto por el ratio
        p.leftMargin = 0;
        p.rightMargin = 0;
        p.dimensionRatio = "H,16:9";
        flPlayerHero.setBackground(androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.bg_player_cover_flat));
        flPlayerHero.setLayoutParams(p);
        heroShapedForVideo = true;
    }

    /** Restores the song-cover hero shape after a Video→Canción swap (which does NOT re-bind the
     *  artwork, so the cover shaping never re-runs on its own). Derives the aspect from the cover
     *  bitmap still held by ivPlayerCover; defaults to a 1:1 square when none is available. */
    private void restoreMusicHeroShape() {
        if (!heroShapedForVideo) return; // ya tiene forma de canción
        // Solo restaurar cuando la pastilla está en Canción. Al AVANZAR entre videos (modo Video) la
        // presentación pasa un instante por «música» antes de resolver el siguiente video; reformar a
        // cuadrado ahí haría parpadear el hero. En ese caso bindCurrentTrack ya reajusta la forma.
        if (StreamResolver.isPreferVideoMode()) return;
        // Prefer the live cover bitmap; fall back to the cached song bitmap so the shape still
        // restores if the live drawable was cleared/rejected during video mode; else a 1:1 square.
        Bitmap b = null;
        if (ivPlayerCover != null
                && ivPlayerCover.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            b = ((android.graphics.drawable.BitmapDrawable) ivPlayerCover.getDrawable()).getBitmap();
        }
        if ((b == null || b.isRecycled())
                && lastSongCoverBitmap != null && !lastSongCoverBitmap.isRecycled()) {
            b = lastSongCoverBitmap;
        }
        if (b != null && !b.isRecycled()) {
            float aspect = (float) b.getWidth() / Math.max(1, b.getHeight());
            applyHeroShapeForAspect(aspect, b.getWidth(), b.getHeight(), heroRatioAppliedGeneration);
        } else {
            applyHeroShapeForAspect(1f, 1, 1, heroRatioAppliedGeneration);
        }
    }

    private void setupSocialActions() {
        applySocialStatsToUi(SocialStats.unavailable());

        if (actionLike != null) {
            actionLike.setOnClickListener(v -> onLikeTapped());
        }
        if (actionDislike != null) {
            actionDislike.setOnClickListener(v -> onDislikeTapped());
        }
        if (actionComments != null) {
            actionComments.setOnClickListener(v -> showCommentsSheet());
        }
        if (actionFavorite != null) {
            actionFavorite.setOnClickListener(v -> showSaveToPlaylistSheetFromPlayer());
        }
        if (actionRadio != null) {
            actionRadio.setOnClickListener(v -> openRadioForCurrentTrack());
        }
        if (actionShare != null) {
            actionShare.setOnClickListener(v -> shareCurrentTrack());
        }
        if (actionDownloadTrack != null) {
            actionDownloadTrack.setOnClickListener(v -> toggleOfflineDownloadForCurrentTrack());
        }
        if (actionGoToArtist != null) {
            actionGoToArtist.setOnClickListener(v -> goToArtistForCurrentTrack());
        }
        refreshFavoriteActionForCurrentTrack();
        refreshDownloadChipState();
        observeManualDownloadWork();
    }

    private void goToArtistForCurrentTrack() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.artist)) return;
        collapseToMiniMode(true);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openArtistDetailByName(track.artist);
        }
    }

    private void observeManualDownloadWork() {
        if (!isAdded()) return;
        try {
            androidx.work.WorkManager.getInstance(requireContext().getApplicationContext())
                    .getWorkInfosForUniqueWorkLiveData("offline_manual_track_queue")
                    .observe(getViewLifecycleOwner(), workInfos -> {
                        if (workInfos == null || workInfos.isEmpty()) return;
                        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
                        String currentVideoId = tracks.get(currentIndex).videoId;
                        if (TextUtils.isEmpty(currentVideoId) || !currentVideoId.equals(pendingDownloadVideoId)) return;
                        for (androidx.work.WorkInfo info : workInfos) {
                            if (info.getState() == androidx.work.WorkInfo.State.RUNNING) {
                                String[] activeIds = info.getProgress().getStringArray(
                                        OfflinePlaylistDownloadWorker.PROGRESS_ACTIVE_IDS);
                                if (activeIds != null) {
                                    for (String id : activeIds) {
                                        if (currentVideoId.equals(id)) {
                                            return;
                                        }
                                    }
                                }
                            }
                            if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED
                                    || info.getState() == androidx.work.WorkInfo.State.FAILED
                                    || info.getState() == androidx.work.WorkInfo.State.CANCELLED) {
                                boolean nowOffline = OfflineAudioStore.hasValidatedOfflineAudio(
                                        requireContext(), currentVideoId, null);
                                if (nowOffline || info.getState() != androidx.work.WorkInfo.State.SUCCEEDED) {
                                    pendingDownloadVideoId = "";
                                }
                                refreshDownloadChipState();
                                return;
                            }
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "Failed to observe download work", e);
        }
    }

    private void toggleCurrentTrackFavorite() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        PlayerTrack current = tracks.get(currentIndex);
        if (TextUtils.isEmpty(current.videoId)) {
            return;
        }

        boolean isFavorite = FavoritesPlaylistStore.isFavorite(requireContext(), current.videoId);
        if (isFavorite) {
            FavoritesPlaylistStore.removeFavorite(requireContext(), current.videoId);
        } else {
            FavoritesPlaylistStore.upsertFavorite(
                    requireContext(),
                    current.videoId,
                    current.title,
                    current.artist,
                    resolveDurationLabelForFavorite(current),
                    current.imageUrl
            );
        }

        refreshFavoriteActionForCurrentTrack();
        notifyFavoritesPlaylistIfVisible();
    }

    private void notifyFavoritesPlaylistIfVisible() {
        if (!isAdded()) {
            return;
        }

        String videoId = null;
        if (!tracks.isEmpty() && currentIndex >= 0 && currentIndex < tracks.size()) {
            videoId = tracks.get(currentIndex).videoId;
        }

        Fragment playlist = getParentFragmentManager().findFragmentByTag("playlist_detail");
        if (playlist instanceof PlaylistDetailFragment) {
            ((PlaylistDetailFragment) playlist).externalRefreshFavoritesIfActive(videoId);
        }
    }

    @NonNull
    private String resolveDurationLabelForFavorite(@NonNull PlayerTrack track) {
        String baseDuration = track.duration == null ? "" : track.duration.trim();
        if (parseDurationSeconds(baseDuration) > 0) {
            return baseDuration;
        }

        if (totalSeconds > 1) {
            return formatSeconds(totalSeconds);
        }

        if (tvTotalTime != null && tvTotalTime.getText() != null) {
            String fromUi = tvTotalTime.getText().toString().trim();
            if (parseDurationSeconds(fromUi) > 0) {
                return fromUi;
            }
        }

        return "--:--";
    }

    private void persistResolvedDurationForCurrentFavorite() {
        if (!isAdded() || totalSeconds <= 1 || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        PlayerTrack current = tracks.get(currentIndex);
        if (TextUtils.isEmpty(current.videoId)
                || !FavoritesPlaylistStore.isFavorite(requireContext(), current.videoId)
                || parseDurationSeconds(current.duration) > 0) {
            return;
        }

        FavoritesPlaylistStore.upsertFavorite(
                requireContext(),
                current.videoId,
                current.title,
                current.artist,
                formatSeconds(totalSeconds),
                current.imageUrl
        );
    }

    @NonNull
    private java.util.List<String[]> getPlaylistsContainingTrack(@NonNull String videoId) {
        java.util.List<String[]> result = new ArrayList<>();
        if (!isAdded()) return result;
        Context ctx = requireContext();
        if (FavoritesPlaylistStore.isFavorite(ctx, videoId)) {
            result.add(new String[]{FavoritesPlaylistStore.PLAYLIST_ID, FavoritesPlaylistStore.PLAYLIST_TITLE});
        }
        java.util.List<String> customNames = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(ctx);
        for (String name : customNames) {
            java.util.List<FavoritesPlaylistStore.FavoriteTrack> playlistTracks =
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            for (FavoritesPlaylistStore.FavoriteTrack t : playlistTracks) {
                if (videoId.equals(t.videoId)) {
                    result.add(new String[]{CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name, name});
                    break;
                }
            }
        }
        return result;
    }

    private void refreshFavoriteActionForCurrentTrack() {
        if (!isAdded()) {
            return;
        }

        if (tvActionFavoriteLabel != null) {
            tvActionFavoriteLabel.setText("Añadir a playlist");
        }

        if (ivActionFavoriteIcon != null) {
            ivActionFavoriteIcon.setImageResource(R.drawable.ic_stream_queue_add);
            int tint = ContextCompat.getColor(requireContext(), R.color.text_primary);
            ivActionFavoriteIcon.setColorFilter(tint);
        }

        if (actionFavorite != null) {
            actionFavorite.setAlpha(1f);
        }
    }

    private void showCommentsSheet() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.videoId)) return;
        String commentCountText = (tvActionCommentCount != null) ? tvActionCommentCount.getText().toString() : "0";
        new CommentsBottomSheet(requireContext(), track.videoId, commentCountText).show();
    }

    private void shareCurrentTrack() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.videoId)) return;
        String shareText = "https://youtu.be/" + track.videoId;
        android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareText);
        startActivity(android.content.Intent.createChooser(shareIntent, "Compartir"));
    }

    private void openRadioForCurrentTrack() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        if (getParentFragmentManager().isStateSaved()) return;
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.videoId)) return;
        String radioPlaylistId = "RDAMVM" + track.videoId;
        String radioTitle = TextUtils.isEmpty(track.title) ? "Radio" : track.title;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);

        // Close player with animation
        collapseToMiniMode(true);

        // Open radio PlaylistDetail after a short delay for animation. The launcher derives the
        // RDAMVM id + canonical title from the seed videoId and owns the transaction/chrome.
        final String seedVideoId = track.videoId;
        final String seedArtist = TextUtils.isEmpty(track.artist) ? "" : track.artist;
        final String seedThumb = TextUtils.isEmpty(track.imageUrl) ? "" : track.imageUrl;
        View view = getView();
        if (view != null) {
            view.postDelayed(() -> {
                if (getActivity() == null) return;
                androidx.fragment.app.FragmentManager fm;
                try { fm = getParentFragmentManager(); } catch (Exception e) { return; }
                if (fm.isStateSaved()) return;
                PlaylistDetailLauncher.open(
                        getActivity(), fm,
                        /* id */ null, radioTitle, seedArtist, seedThumb,
                        /* seedVideoId */ seedVideoId);
            }, 350L);
        }

        // Fetch radio tracks and save to RadioHistoryStore for library display
        String cookie = safeValue(prefs.getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, ""));
        final String selectedVideoId = track.videoId;
        final String selectedTitle = TextUtils.isEmpty(track.title) ? "Tema" : track.title;
        final String selectedArtist = TextUtils.isEmpty(track.artist) ? "" : track.artist;
        final String selectedThumb = TextUtils.isEmpty(track.imageUrl) ? "" : track.imageUrl;
        radioMusicService.fetchMixTracks(cookie, radioPlaylistId, new YouTubeMusicService.MixTracksCallback() {
            @Override
            public void onSuccess(@NonNull java.util.List<YouTubeMusicService.TrackResult> radioTracks) {
                if (radioTracks.isEmpty()) return;
                java.util.List<RadioHistoryStore.RadioTrack> radioStoreTracks = new ArrayList<>();
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
                Context ctx = persistentAppContext;
                if (ctx != null) {
                    RadioHistoryStore.INSTANCE.saveRadio(ctx, radioPlaylistId, selectedTitle, selectedThumb, radioStoreTracks);
                }
                localProgressHandler.post(() -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).refreshMusicLibrary();
                    }
                });
            }

            @Override
            public void onError(@NonNull String error) {
                // Radio fetch failed — no action needed
            }
        });
    }

    private void refreshDownloadChipState() {
        if (actionDownloadTrack == null || ivActionDownloadIcon == null || tvActionDownloadLabel == null) return;
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            actionDownloadTrack.setVisibility(View.GONE);
            return;
        }
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.videoId)) {
            actionDownloadTrack.setVisibility(View.GONE);
            return;
        }

        actionDownloadTrack.setVisibility(View.VISIBLE);
        boolean isPending = track.videoId.equals(pendingDownloadVideoId);
        if (isPending) {
            tvActionDownloadLabel.setText("Descargando...");
            ivActionDownloadIcon.setImageResource(R.drawable.ic_download_bold);
            actionDownloadTrack.setAlpha(0.6f);
            actionDownloadTrack.setClickable(false);
            return;
        }

        // Default state while checking
        tvActionDownloadLabel.setText("Descargar");
        ivActionDownloadIcon.setImageResource(R.drawable.ic_download_bold);
        actionDownloadTrack.setAlpha(1f);
        actionDownloadTrack.setClickable(true);

        final String videoId = track.videoId;
        final Context appCtx = requireContext().getApplicationContext();
        streamResolverExecutor.execute(() -> {
            boolean isDownloaded = OfflineAudioStore.hasValidatedOfflineAudio(appCtx, videoId, null);
            localProgressHandler.post(() -> {
                if (!isAdded() || actionDownloadTrack == null) return;
                if (currentIndex < 0 || currentIndex >= tracks.size()) return;
                if (!TextUtils.equals(tracks.get(currentIndex).videoId, videoId)) return;
                applyDownloadChipVisual(isDownloaded, videoId.equals(pendingDownloadVideoId));
            });
        });
    }

    private void applyDownloadChipVisual(boolean isDownloaded, boolean isPending) {
        if (actionDownloadTrack == null || ivActionDownloadIcon == null || tvActionDownloadLabel == null) return;
        if (isDownloaded) {
            pendingDownloadVideoId = "";
            tvActionDownloadLabel.setText("Descargado");
            ivActionDownloadIcon.setImageResource(R.drawable.ic_download_bold);
            actionDownloadTrack.setAlpha(1f);
            actionDownloadTrack.setClickable(true);
        } else if (isPending) {
            tvActionDownloadLabel.setText("Descargando...");
            ivActionDownloadIcon.setImageResource(R.drawable.ic_download_bold);
            actionDownloadTrack.setAlpha(0.6f);
            actionDownloadTrack.setClickable(false);
        } else {
            tvActionDownloadLabel.setText("Descargar");
            ivActionDownloadIcon.setImageResource(R.drawable.ic_download_bold);
            actionDownloadTrack.setAlpha(1f);
            actionDownloadTrack.setClickable(true);
        }
    }

    private void toggleOfflineDownloadForCurrentTrack() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        PlayerTrack track = tracks.get(currentIndex);
        if (TextUtils.isEmpty(track.videoId)) return;

        boolean isDownloaded = OfflineAudioStore.hasValidatedOfflineAudio(requireContext(), track.videoId, null);
        if (isDownloaded) {
            pendingDownloadVideoId = "";
            OfflineAudioStore.deleteOfflineAudio(requireContext(), track.videoId);
            AppSnackbar.showInView(getPlayerToastRoot(), "Descarga eliminada",
                    null, null, playerToastBottomMarginPx());
            refreshDownloadChipState();
        } else {
            pendingDownloadVideoId = track.videoId;
            enqueueOfflineDownloadForTrack(track);
            AppSnackbar.showInView(getPlayerToastRoot(), "Descarga en cola",
                    null, null, playerToastBottomMarginPx());
            refreshDownloadChipState();
        }
    }

    private void enqueueOfflineDownloadForTrack(PlayerTrack track) {
        androidx.work.Data input = new androidx.work.Data.Builder()
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, "")
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, "Descargas Manuales")
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, new String[]{track.videoId})
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, new String[]{track.title})
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, new String[]{track.artist})
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, new String[]{track.duration})
                .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
                .build();
        SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
        androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(allowMobileData ? androidx.work.NetworkType.CONNECTED : androidx.work.NetworkType.UNMETERED)
                .build();
        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                // Short linear backoff: manual jobs share one APPEND chain — a retrying head
                // must resolve fast or it blocks every download appended behind it.
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                .addTag(AppConstants.OFFLINE_MANUAL_TRACK_QUEUE)
                .build();
        androidx.work.WorkManager.getInstance(requireContext().getApplicationContext())
                .enqueueUniqueWork(AppConstants.OFFLINE_MANUAL_TRACK_QUEUE, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    private void refreshSocialActionsForCurrentTrack(@Nullable PlayerTrack track) {
        if (track == null || TextUtils.isEmpty(track.videoId)) {
            cancelPendingSocialStatsFetch();
            pendingSocialStatsVideoId = "";
            applySocialStatsToUi(SocialStats.unavailable());
            return;
        }

        // Reflect the like/dislike state from local stores (synchronous, instant).
        refreshLikeIconState();

        pendingSocialStatsVideoId = track.videoId;
        SocialStats cached = socialStatsCache.get(track.videoId);
        if (cached != null) {
            applySocialStatsToUi(cached);
            continueSocialStatsFetch(track.videoId, cached);
            return;
        }

        // In-memory miss: read the persisted stats (SharedPreferences + JSON parse) OFF the UI
        // thread. This used to run on the main thread on every track change; the network fetch it
        // feeds into is already async, so deferring the disk read is behaviour-preserving.
        applySocialStatsToUi(SocialStats.loading());
        final String requestVideoId = track.videoId;
        backgroundExecutor.execute(() -> {
            final SocialStats prefStats = readSocialStatsFromCache(requestVideoId);
            localProgressHandler.post(() -> {
                if (!isAdded() || !TextUtils.equals(pendingSocialStatsVideoId, requestVideoId)) {
                    return;
                }
                if (prefStats != null) {
                    socialStatsCache.put(requestVideoId, prefStats);
                    applySocialStatsToUi(prefStats);
                }
                continueSocialStatsFetch(requestVideoId, prefStats);
            });
        });
    }

    private void continueSocialStatsFetch(@NonNull String requestVideoId, @Nullable SocialStats cached) {
        // Las estadísticas salen ahora del /next de InnerTube (sin la Data API v3), así que ya NO
        // se requiere una API key — antes, sin key, no se mostraban stats en absoluto.
        boolean deferFetch = cached == null && isPlaying && !usingOfflineSource;
        scheduleSocialStatsFetch(requestVideoId, "", cached, deferFetch);
    }

    private void scheduleSocialStatsFetch(
            @NonNull String requestVideoId,
            @NonNull String apiKey,
            @Nullable SocialStats cachedSnapshot,
            boolean deferFetch
    ) {
        cancelPendingSocialStatsFetch();

        pendingSocialStatsFetchRunnable = () -> {
            pendingSocialStatsFetchRunnable = null;
            if (!isAdded() || !TextUtils.equals(pendingSocialStatsVideoId, requestVideoId)) {
                return;
            }

            backgroundExecutor.execute(() -> {
                SocialStats stats = fetchSocialStats(requestVideoId, apiKey);
                localProgressHandler.post(() -> {
                    if (!isAdded() || !TextUtils.equals(pendingSocialStatsVideoId, requestVideoId)) {
                        return;
                    }

                    if (stats.unavailable && cachedSnapshot != null) {
                        applySocialStatsToUi(cachedSnapshot);
                        return;
                    }

                    socialStatsCache.put(requestVideoId, stats);
                    persistSocialStatsToCache(requestVideoId, stats);
                    applySocialStatsToUi(stats);
                });
            });
        };

        if (deferFetch) {
            localProgressHandler.postDelayed(pendingSocialStatsFetchRunnable, SOCIAL_STATS_FETCH_DEFER_MS);
        } else {
            localProgressHandler.post(pendingSocialStatsFetchRunnable);
        }
    }

    private void cancelPendingSocialStatsFetch() {
        if (pendingSocialStatsFetchRunnable != null) {
            localProgressHandler.removeCallbacks(pendingSocialStatsFetchRunnable);
            pendingSocialStatsFetchRunnable = null;
        }
    }

    /**
     * Warms the comments first-page cache shortly after a track binds, so opening the
     * comments sheet later hits the instant cache path. Deferred + cancelled on track change
     * so rapid skipping never fires one request per skip (mirrors the social-stats pattern).
     */
    private void scheduleCommentsPrefetch(@NonNull PlayerTrack track, boolean isLocalFile) {
        cancelPendingCommentsPrefetch();
        if (isLocalFile || TextUtils.isEmpty(track.videoId)) return;
        final String requestVideoId = track.videoId;
        pendingCommentsPrefetchRunnable = () -> {
            pendingCommentsPrefetchRunnable = null;
            if (!isAdded() || currentIndex < 0 || currentIndex >= tracks.size()) return;
            if (!TextUtils.equals(tracks.get(currentIndex).videoId, requestVideoId)) return;
            CommentsBottomSheet.prefetch(requireContext(), requestVideoId);
        };
        localProgressHandler.postDelayed(pendingCommentsPrefetchRunnable, COMMENTS_PREFETCH_DEFER_MS);
    }

    private void cancelPendingCommentsPrefetch() {
        if (pendingCommentsPrefetchRunnable != null) {
            localProgressHandler.removeCallbacks(pendingCommentsPrefetchRunnable);
            pendingCommentsPrefetchRunnable = null;
        }
    }

    @Nullable
    private SocialStats readSocialStatsFromCache(@NonNull String videoId) {
        if (playerStatePrefs == null || TextUtils.isEmpty(videoId)) {
            return null;
        }

        String raw = playerStatePrefs.getString(PREF_SOCIAL_STATS_PREFIX + videoId, "");
        if (TextUtils.isEmpty(raw)) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(raw);
            String like = json.optString("like", "");
            String dislike = json.optString("dislike", "");
            String comments = json.optString("comments", "");
            if (TextUtils.isEmpty(like) || TextUtils.isEmpty(dislike) || TextUtils.isEmpty(comments)) {
                return null;
            }
            return new SocialStats(like, dislike, comments, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistSocialStatsToCache(@NonNull String videoId, @NonNull SocialStats stats) {
        if (playerStatePrefs == null || TextUtils.isEmpty(videoId) || stats.unavailable) {
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("like", stats.likeCount);
            json.put("dislike", stats.dislikeCount);
            json.put("comments", stats.commentCount);
            playerStatePrefs.edit()
                    .putString(PREF_SOCIAL_STATS_PREFIX + videoId, json.toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist social stats", e);
        }
    }

    /**
     * Estadísticas sociales (like + comentarios) vía el /next de InnerTube (cliente WEB) — SIN la
     * YouTube Data API v3, cuya cuota diaria se agotaba (403) y rompía las stats para todos. Datos
     * públicos: no requiere OAuth (la cookie/SAPISIDHASH solo mejora la respuesta si hay sesión).
     * El param apiKey queda ignorado (compat de firma).
     */
    @NonNull
    private SocialStats fetchSocialStats(@NonNull String videoId, @NonNull String apiKeyIgnored) {
        String body = postInnertubeNext(videoId);
        if (body.isEmpty()) {
            return SocialStats.unavailable();
        }
        try {
            JSONObject root = new JSONObject(body);
            long likeCount = extractLikeCountFromNext(root);
            long commentCount = extractCommentCountFromNext(root);
            if (likeCount < 0 && commentCount < 0) {
                return SocialStats.unavailable();
            }
            return new SocialStats(
                    likeCount >= 0 ? formatCompactCount(likeCount) : "0",
                    "0",
                    commentCount >= 0 ? formatCompactCount(commentCount) : "0",
                    false
            );
        } catch (Exception ignored) {
            return SocialStats.unavailable();
        }
    }

    /** POST al /next de InnerTube (cliente WEB). Devuelve el body o "" en error. */
    @NonNull
    private String postInnertubeNext(@NonNull String videoId) {
        HttpURLConnection connection = null;
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20241111.01.00");
            client.put("hl", "es");
            client.put("gl", "US");
            JSONObject payload = new JSONObject();
            payload.put("context", new JSONObject().put("client", client));
            payload.put("videoId", videoId);
            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://www.youtube.com/youtubei/v1/next?prettyPrint=false");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Origin", "https://www.youtube.com");
            connection.setRequestProperty("Referer", "https://www.youtube.com/");
            String cookie = StreamResolver.getAuthCookieHeader();
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
                String sapisid = StreamResolver.buildSapisidHash("https://www.youtube.com");
                if (sapisid != null && !sapisid.isEmpty()) {
                    connection.setRequestProperty("Authorization", sapisid);
                }
            }
            connection.setUseCaches(false);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "postInnertubeNext non-2xx code=" + code);
                return "";
            }
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            Log.w(TAG, "postInnertubeNext exception", e);
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Conteo de comentarios del /next (varias rutas: engagementPanels header, o el entry point). */
    private long extractCommentCountFromNext(@NonNull JSONObject root) {
        // Ruta 1: engagementPanels[…comments].header.engagementPanelTitleHeaderRenderer.contextualInfo
        try {
            JSONArray panels = root.optJSONArray("engagementPanels");
            if (panels != null) {
                for (int i = 0; i < panels.length(); i++) {
                    JSONObject r = panels.optJSONObject(i) == null ? null
                            : panels.optJSONObject(i).optJSONObject("engagementPanelSectionListRenderer");
                    if (r == null) continue;
                    String id = r.optString("panelIdentifier", "") + r.optString("targetId", "");
                    if (!id.toLowerCase(Locale.US).contains("comment")) continue;
                    JSONObject title = r.optJSONObject("header") == null ? null
                            : r.optJSONObject("header").optJSONObject("engagementPanelTitleHeaderRenderer");
                    if (title == null) continue;
                    long n = parseCompactCountText(firstRunText(title.optJSONObject("contextualInfo")));
                    if (n >= 0) return n;
                }
            }
        } catch (Exception ignored) {
        }
        // Ruta 2: itemSectionRenderer → commentsEntryPointHeaderRenderer.commentCount
        try {
            JSONArray contents = watchNextContents(root);
            if (contents != null) {
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject sec = contents.optJSONObject(i) == null ? null
                            : contents.optJSONObject(i).optJSONObject("itemSectionRenderer");
                    JSONArray inner = sec == null ? null : sec.optJSONArray("contents");
                    if (inner == null) continue;
                    for (int j = 0; j < inner.length(); j++) {
                        JSONObject h = inner.optJSONObject(j) == null ? null
                                : inner.optJSONObject(j).optJSONObject("commentsEntryPointHeaderRenderer");
                        if (h == null) continue;
                        JSONObject cc = h.optJSONObject("commentCount");
                        String text = cc == null ? "" : (cc.optString("simpleText", "").isEmpty()
                                ? firstRunText(cc) : cc.optString("simpleText", ""));
                        long n = parseCompactCountText(text);
                        if (n >= 0) return n;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Conteo de "me gusta" del /next: botón like del videoPrimaryInfoRenderer (mejor esfuerzo). */
    private long extractLikeCountFromNext(@NonNull JSONObject root) {
        try {
            JSONArray contents = watchNextContents(root);
            if (contents == null) return -1;
            for (int i = 0; i < contents.length(); i++) {
                JSONObject vpi = contents.optJSONObject(i) == null ? null
                        : contents.optJSONObject(i).optJSONObject("videoPrimaryInfoRenderer");
                if (vpi == null) continue;
                JSONObject menu = vpi.optJSONObject("videoActions") == null ? null
                        : vpi.optJSONObject("videoActions").optJSONObject("menuRenderer");
                JSONArray top = menu == null ? null : menu.optJSONArray("topLevelButtons");
                if (top == null) continue;
                // El botón de like es el primer subárbol; busca el primer texto que parsee a número.
                long n = findFirstCountInTree(top.optJSONObject(0), 0);
                if (n >= 0) return n;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    @Nullable
    private JSONArray watchNextContents(@NonNull JSONObject root) {
        JSONObject c = root.optJSONObject("contents");
        JSONObject two = c == null ? null : c.optJSONObject("twoColumnWatchNextResults");
        JSONObject res = two == null ? null : two.optJSONObject("results");
        JSONObject res2 = res == null ? null : res.optJSONObject("results");
        return res2 == null ? null : res2.optJSONArray("contents");
    }

    @NonNull
    private String firstRunText(@Nullable JSONObject textObj) {
        if (textObj == null) return "";
        String simple = textObj.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = textObj.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            JSONObject r0 = runs.optJSONObject(0);
            if (r0 != null) return r0.optString("text", "");
        }
        return "";
    }

    /** Busca recursivamente (acotado) el primer valor de texto que parsee a un conteo compacto,
     *  dentro del subárbol del botón de like (title/accessibilityText/label). */
    private long findFirstCountInTree(@Nullable JSONObject node, int depth) {
        if (node == null || depth > 8) return -1;
        java.util.Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = node.opt(k);
            if (v instanceof String) {
                if (k.equals("title") || k.equals("accessibilityText") || k.equals("label")
                        || k.equals("simpleText") || k.equals("text")) {
                    long n = parseCompactCountText((String) v);
                    if (n >= 0) return n;
                }
            } else if (v instanceof JSONObject) {
                long n = findFirstCountInTree((JSONObject) v, depth + 1);
                if (n >= 0) return n;
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    long n = findFirstCountInTree(arr.optJSONObject(i), depth + 1);
                    if (n >= 0) return n;
                }
            }
        }
        return -1;
    }

    /** Parsea "1,234" / "1.2K" / "1.2 M" / "3,4 mil" a un long. -1 si no es un conteo. */
    private long parseCompactCountText(@Nullable String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return -1;
        // Extraer el primer número (con , . como separadores) y un posible sufijo de magnitud.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9][0-9.,]*)\\s*(mil|k|m|b|mm)?").matcher(s);
        if (!m.find()) return -1;
        String numPart = m.group(1);
        String suffix = m.group(2) == null ? "" : m.group(2);
        // Normalizar separadores: quitar los de miles, dejar el decimal. Heurística: si hay coma y
        // punto, el último es el decimal; si solo uno y separa <=2 dígitos al final con sufijo, es decimal.
        double base;
        try {
            String cleaned = numPart;
            if (suffix.isEmpty()) {
                // Conteo entero: quitar todos los separadores.
                cleaned = cleaned.replace(".", "").replace(",", "");
                return Long.parseLong(cleaned);
            } else {
                // Con sufijo (1.2K): el separador es decimal.
                cleaned = cleaned.replace(",", ".");
                base = Double.parseDouble(cleaned);
            }
        } catch (Exception e) {
            return -1;
        }
        double mult = 1;
        switch (suffix) {
            case "mil": case "k": mult = 1_000d; break;
            case "m": mult = 1_000_000d; break;
            case "b": case "mm": mult = 1_000_000_000d; break;
            default: mult = 1; break;
        }
        return (long) (base * mult);
    }

    private void applySocialStatsToUi(@NonNull SocialStats stats) {
        if (tvActionLikeCount != null) {
            tvActionLikeCount.setText(stats.likeCount);
        }
        if (tvActionCommentCount != null) {
            tvActionCommentCount.setText(stats.commentCount);
        }
        refreshLikeIconState();
    }

    private void refreshLikeIconState() {
        if (ivActionLikeIcon == null || !isAdded()) return;
        if (tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            paintLikeDislikeIcons(YouTubeMusicService.LikeStatus.INDIFFERENT);
            return;
        }
        paintLikeDislikeIcons(effectiveLikeStatus(tracks.get(currentIndex).videoId));
    }

    /**
     * Like/dislike state derived from LOCAL stores — the reliable, synchronous source. LIKE comes
     * from "Música que te gustó" membership (synced from YouTube's liked playlist); DISLIKE from a
     * local set. We intentionally do NOT read it back from a per-play network call: that was slow
     * and unreliable and would momentarily show a liked song as un-liked on re-entry.
     */
    @NonNull
    private YouTubeMusicService.LikeStatus effectiveLikeStatus(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId)) return YouTubeMusicService.LikeStatus.INDIFFERENT;
        if (isTrackInLikedMusic(videoId)) return YouTubeMusicService.LikeStatus.LIKE;
        if (isTrackDisliked(videoId)) return YouTubeMusicService.LikeStatus.DISLIKE;
        return YouTubeMusicService.LikeStatus.INDIFFERENT;
    }

    private boolean isTrackDisliked(@NonNull String videoId) {
        if (!isAdded()) return false;
        Set<String> set = requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .getStringSet(PREF_DISLIKED_VIDEO_IDS, null);
        return set != null && set.contains(videoId);
    }

    private void setTrackDisliked(@NonNull String videoId, boolean disliked) {
        if (!isAdded() || TextUtils.isEmpty(videoId)) return;
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        Set<String> current = prefs.getStringSet(PREF_DISLIKED_VIDEO_IDS, null);
        // Never mutate the set returned by getStringSet — copy it (Android reuses the instance).
        Set<String> updated = new HashSet<>(current == null ? java.util.Collections.emptySet() : current);
        boolean changed = disliked ? updated.add(videoId) : updated.remove(videoId);
        if (changed) prefs.edit().putStringSet(PREF_DISLIKED_VIDEO_IDS, updated).apply();
    }

    private void paintLikeDislikeIcons(@NonNull YouTubeMusicService.LikeStatus status) {
        int neutral = ContextCompat.getColor(requireContext(), R.color.text_primary);
        if (ivActionLikeIcon != null) {
            if (status == YouTubeMusicService.LikeStatus.LIKE) {
                ivActionLikeIcon.setImageResource(R.drawable.ic_thumb_up_liked);
                ivActionLikeIcon.clearColorFilter();
            } else {
                ivActionLikeIcon.setImageResource(R.drawable.ic_social_thumb_up);
                ivActionLikeIcon.setColorFilter(neutral);
            }
        }
        if (ivActionDislikeIcon != null) {
            if (status == YouTubeMusicService.LikeStatus.DISLIKE) {
                ivActionDislikeIcon.setImageResource(R.drawable.ic_social_thumb_down);
                ivActionDislikeIcon.setColorFilter(neutral);
            } else {
                ivActionDislikeIcon.setImageResource(R.drawable.ic_social_thumb_down_outline);
                ivActionDislikeIcon.setColorFilter(neutral);
            }
        }
    }

    /** YT Music web cookie for authenticated InnerTube calls (like/dislike). */
    @NonNull
    private String getWebCookie() {
        if (!isAdded()) return "";
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        return safeValue(prefs.getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, ""));
    }


    /**
     * "Música que te gustó" membership is the union of the server-synced cache and the local
     * yt_mirror (the same union PlaylistDetailFragment renders), so the like icon fills for a
     * liked song no matter where it was liked from or which playlist it is playing from.
     */
    private boolean isTrackInLikedMusic(@NonNull String videoId) {
        if (!isAdded()) return false;
        Context ctx = requireContext();
        return FavoritesPlaylistStore.isInLikedMusic(ctx, videoId)
                || CustomPlaylistsStore.INSTANCE.isTrackInYtMirror(
                        ctx, YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID, videoId);
    }

    /**
     * Like tap = REAL YouTube like. Toggles LIKE<->INDIFFERENT on YouTube via InnerTube (counts like
     * tapping like on youtube.com) and keeps the local "Música que te gustó" playlist + icon in sync
     * INSTANTLY from local stores; the network rate runs in the background and only reverts on error.
     */
    private void onLikeTapped() {
        PlayerTrack current = currentTrackForRating();
        if (current == null) return;
        YouTubeMusicService.LikeStatus previous = effectiveLikeStatus(current.videoId);
        YouTubeMusicService.LikeStatus target = (previous == YouTubeMusicService.LikeStatus.LIKE)
                ? YouTubeMusicService.LikeStatus.INDIFFERENT
                : YouTubeMusicService.LikeStatus.LIKE;
        applyLikeStatusLocally(current, target);
        refreshLikeIconState();
        animateLikeIconPop();
        if (target == YouTubeMusicService.LikeStatus.LIKE) {
            AppSnackbar.showInView(getPlayerToastRoot(), "Agregado a Música que te gustó",
                    null, null, playerToastBottomMarginPx());
        }
        sendRealRating(current, target, previous);
    }

    /**
     * Dislike tap = REAL YouTube dislike. Toggles DISLIKE<->INDIFFERENT on YouTube; a dislike also
     * clears any prior like (mutually exclusive), matching YouTube's own behavior.
     */
    private void onDislikeTapped() {
        PlayerTrack current = currentTrackForRating();
        if (current == null) return;
        YouTubeMusicService.LikeStatus previous = effectiveLikeStatus(current.videoId);
        YouTubeMusicService.LikeStatus target = (previous == YouTubeMusicService.LikeStatus.DISLIKE)
                ? YouTubeMusicService.LikeStatus.INDIFFERENT
                : YouTubeMusicService.LikeStatus.DISLIKE;
        applyLikeStatusLocally(current, target);
        refreshLikeIconState();
        sendRealRating(current, target, previous);
    }

    @Nullable
    private PlayerTrack currentTrackForRating() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return null;
        PlayerTrack current = tracks.get(currentIndex);
        if (current == null || TextUtils.isEmpty(current.videoId)) return null;
        return current;
    }

    /** Writes the like/dislike state into the local stores (like and dislike are mutually exclusive). */
    private void applyLikeStatusLocally(@NonNull PlayerTrack current, @NonNull YouTubeMusicService.LikeStatus status) {
        if (!isAdded() || TextUtils.isEmpty(current.videoId)) return;
        applyLocalLikedMirror(current, status == YouTubeMusicService.LikeStatus.LIKE);
        setTrackDisliked(current.videoId, status == YouTubeMusicService.LikeStatus.DISLIKE);
    }

    /** Fires the real InnerTube rate in the background; reverts the local stores on failure. */
    private void sendRealRating(@NonNull PlayerTrack current,
                                @NonNull YouTubeMusicService.LikeStatus target,
                                @NonNull YouTubeMusicService.LikeStatus previous) {
        String cookie = getWebCookie();
        if (cookie.isEmpty()) {
            // No YT session: undo the optimistic local change and ask the user to sign in.
            applyLikeStatusLocally(current, previous);
            refreshLikeIconState();
            AppSnackbar.showInView(getPlayerToastRoot(),
                    "Inicia sesión en YouTube Music para dar me gusta",
                    null, null, playerToastBottomMarginPx());
            return;
        }
        likeMusicService.rateSong(cookie, current.videoId, target, new YouTubeMusicService.RateCallback() {
            @Override
            public void onSuccess(@NonNull YouTubeMusicService.LikeStatus status) {
                // Local stores already reflect the choice; nothing more to do.
            }

            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) return;
                // Stores are keyed by videoId (independent of the displayed track): always undo.
                applyLikeStatusLocally(current, previous);
                refreshLikeIconState();
                AppSnackbar.showInView(getPlayerToastRoot(),
                        "No se pudo actualizar en YouTube",
                        null, null, playerToastBottomMarginPx());
            }
        });
    }

    /**
     * Keeps the local "Música que te gustó" playlist mirror consistent with the like state, so a
     * liked song shows there and reads instantly/offline. Adding mirrors the save-to-playlist sheet's
     * liked row; removing clears BOTH stores (the fill state is a union of server cache + mirror).
     */
    private void applyLocalLikedMirror(@NonNull PlayerTrack current, boolean liked) {
        if (!isAdded() || TextUtils.isEmpty(current.videoId)) return;
        Context ctx = requireContext();
        String likedPid = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID;
        if (liked) {
            String tTitle = TextUtils.isEmpty(current.title) ? "Tema" : current.title;
            String tArtist = current.artist == null ? "" : current.artist;
            String tDuration = current.duration == null ? "" : current.duration;
            String tImage = current.imageUrl == null ? "" : current.imageUrl;
            CustomPlaylistsStore.INSTANCE.addTrackToYtMirror(
                    ctx, likedPid, current.videoId, tTitle, tArtist, tDuration, tImage, true);
            FavoritesPlaylistStore.clearLikedTombstone(ctx, current.videoId);
        } else {
            CustomPlaylistsStore.INSTANCE.removeTrackFromYtMirror(ctx, likedPid, current.videoId);
            FavoritesPlaylistStore.removeFromLikedMusic(ctx, current.videoId);
        }
        FavoritesPlaylistStore.invalidateLikedMusicCache();
        notifyFavoritesPlaylistIfVisible();
    }

    private void animateLikeIconPop() {
        if (ivActionLikeIcon == null) return;
        ivActionLikeIcon.animate().cancel();
        ivActionLikeIcon.setScaleX(0.7f);
        ivActionLikeIcon.setScaleY(0.7f);
        ivActionLikeIcon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();
    }

    @NonNull
    private String formatCompactCount(long value) {
        if (value <= 0) {
            return "0";
        }
        if (value < 1000L) {
            return String.valueOf(value);
        }

        double compact = value;
        String suffix = "";
        if (value >= 1_000_000_000L) {
            compact = value / 1_000_000_000d;
            suffix = "B";
        } else if (value >= 1_000_000L) {
            compact = value / 1_000_000d;
            suffix = "M";
        } else {
            compact = value / 1000d;
            suffix = "K";
        }

        if (compact >= 100d) {
            return String.format(Locale.US, "%.0f%s", compact, suffix);
        }
        if (compact >= 10d) {
            return String.format(Locale.US, "%.1f%s", compact, suffix);
        }
        return String.format(Locale.US, "%.2f%s", compact, suffix);
    }

    private long parseSafeLong(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignored) {
            return -1L;
        }
    }

    public void externalPlayTrack(int index) {
        externalPlayTrackInternal(index, false);
    }

    public void externalPlayTrackFromStart(int index) {
        externalPlayTrackInternal(index, true);
    }

    private void externalPlayTrackInternal(int index, boolean startFromBeginning) {
        if (index < 0 || index >= tracks.size()) {
            return;
        }

        consecutiveStreamFailures = 0; // Reset failures on explicit user action

        String targetVideoId = tracks.get(index).videoId;
        boolean sameAsLoaded = !TextUtils.isEmpty(targetVideoId)
                && TextUtils.equals(targetVideoId, loadedVideoId);

        if (!startFromBeginning
                && sameAsLoaded
                && currentIndex == index
                && isEffectivePlaying()) {
            syncMiniStateWithPlaylist();
            persistPlaybackSnapshot(false);
            return;
        }

        currentIndex = index;
        isPlaying = true;
        bindCurrentTrack(!startFromBeginning);
        playCurrentTrack();
        persistPlaybackSnapshot(false);
    }

    public void externalSkipNext() {
        moveTrack(1);
    }

    public void externalSkipPrevious() {
        moveTrack(-1);
    }

    public void externalTogglePlayback() {
        togglePlayback();
    }

    public void externalPause() {
        if (isPlaying) {
            togglePlayback();
        }
    }

    public void externalPauseForSessionExit() {
        pauseRequestedByUser = true;
        cancelAutoplayRecovery();
        cancelPendingStreamResolver();
        stopLocalProgressTicker();
        releaseLocalExoMediaPlayer();
        isPlaying = false;
        updatePlayPauseIcon();
        updateMediaSessionState();
        updateMediaNotification();
        syncMiniStateWithPlaylist();
        persistPlaybackSnapshot(true);
    }

    public void externalAnimateEnterSlide() {
        View root = getView();
        if (root == null) {
            return;
        }

        HorizontalScrollView hsActions = root.findViewById(R.id.hsSocialActions);
        if (hsActions != null) hsActions.scrollTo(0, 0);

        playerEnterAnimationRunning = true;

        final int fallbackDistance = root.getResources().getDisplayMetrics().heightPixels;

        // Put the player off-screen synchronously, BEFORE its first draw, so it never flashes at
        // rest. Then defer the actual slide to the first pre-draw: the heavy initial measure/layout
        // of this large layout runs first, so it can't drop the animation's opening frames — which
        // is exactly what made the player look like it appeared instantly with no animation.
        root.animate().cancel();
        root.setVisibility(View.VISIBLE);
        root.setAlpha(1f);
        root.setTranslationY(root.getHeight() > 0 ? root.getHeight() : fallbackDistance);

        final View slideRoot = root;
        ViewTreeObserver vto = root.getViewTreeObserver();
        if (vto.isAlive()) {
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    slideRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                    startEnterSlide(slideRoot, fallbackDistance);
                    return true; // allow this (off-screen) frame to draw; slide starts next frame
                }
            });
        } else {
            // View not ready for a pre-draw hook — start on the next loop instead.
            root.post(() -> startEnterSlide(slideRoot, fallbackDistance));
        }
    }

    /** Runs the actual slide-to-rest. Split out so it can start either from the first pre-draw
     *  (after layout, so the opening frames aren't dropped) or from a posted fallback. */
    private void startEnterSlide(@NonNull View root, int fallbackDistance) {
        if (!isAdded() || getView() != root) {
            playerEnterAnimationRunning = false;
            return;
        }
        int distance = root.getHeight() > 0 ? root.getHeight() : fallbackDistance;
        root.setTranslationY(distance);
        root.animate()
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> {
                    playerEnterAnimationRunning = false;
                    View currentView = getView();
                    if (currentView != null) {
                        currentView.setTranslationY(0f);
                    }
                })
                .start();
    }

    public void externalSetReturnTargetTag(@NonNull String targetTag) {
        String safeTarget = targetTag == null ? "" : targetTag.trim();
        if (TextUtils.isEmpty(safeTarget)) {
            return;
        }
        returnTargetTag = safeTarget;
    }

    @NonNull
    public String externalGetReturnTargetTag() {
        return returnTargetTag;
    }


    public void externalSnapshotForNavigation() {
        persistPlaybackSnapshot(false);
    }

    public int externalGetCurrentIndex() {
        return currentIndex;
    }

    public boolean externalIsPlaying() {
        return isEffectivePlaying();
    }

    public boolean externalIsPlayingIntent() {
        return isPlaying;
    }

    public boolean externalIsShuffleEnabled() {
        return shuffleEnabled;
    }

    public int externalGetRepeatMode() {
        return repeatMode;
    }

    public void externalSetShuffleEnabled(boolean enabled) {
        setShuffleEnabled(enabled);
    }

    public int externalGetCurrentSeconds() {
        // When hidden, localProgressTicker is stopped so currentSeconds is stale.
        // Read live position from ExoPlayer if it's still active.
        if (isHidden() && localExoMediaPlayer != null) {
            try {
                return Math.max(0, localExoMediaPlayer.getCurrentPosition() / 1000);
            } catch (Exception e) {
                Log.w(TAG, "getCurrentPosition failed", e);
            }
        }
        return Math.max(0, currentSeconds);
    }

    public int externalGetTotalSeconds() {
        if (isHidden() && localExoMediaPlayer != null) {
            try {
                int durationMs = localExoMediaPlayer.getDuration();
                if (durationMs > 0) return Math.max(1, durationMs / 1000);
            } catch (Exception e) {
                Log.w(TAG, "getDuration failed", e);
            }
        }
        return Math.max(1, totalSeconds);
    }

    public boolean externalIsLoading() {
        return localSourcePreparing || hasPendingStreamResolution();
    }

    @NonNull
    public String externalGetCurrentVideoId() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return "";
        }
        String value = tracks.get(currentIndex).videoId;
        return value == null ? "" : value;
    }

    public String externalGetCurrentTitle() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return "";
        }
        String value = tracks.get(currentIndex).title;
        return value == null ? "" : value;
    }

    public String externalGetCurrentArtist() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return "";
        }
        String value = tracks.get(currentIndex).artist;
        return value == null ? "" : value;
    }

    public String externalGetCurrentImageUrl() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return "";
        }
        String value = tracks.get(currentIndex).imageUrl;
        return value == null ? "" : value;
    }

    @NonNull
    public List<String> externalGetQueueVideoIds() {
        List<String> queueVideoIds = new ArrayList<>(tracks.size());
        for (PlayerTrack track : tracks) {
            queueVideoIds.add(track.videoId == null ? "" : track.videoId);
        }
        return queueVideoIds;
    }

    public boolean externalMatchesQueue(@Nullable List<String> videoIds) {
        if (videoIds == null || videoIds.size() != tracks.size()) {
            return false;
        }

        for (int i = 0; i < tracks.size(); i++) {
            if (!TextUtils.equals(tracks.get(i).videoId, videoIds.get(i))) {
                return false;
            }
        }
        return true;
    }

    public void externalInsertNext(

            @NonNull String videoId,
            @NonNull String title,
            @NonNull String artist,
            @NonNull String duration,
            @NonNull String imageUrl
    ) {
        if (!isAdded()) return;
        PlayerTrack track = new PlayerTrack(
                safeValue(videoId),
                safeValue(title),
                safeValue(artist),
                safeValue(duration),
                safeValue(imageUrl)
        );

        int insertIndex = Math.max(0, Math.min(currentIndex + 1, tracks.size()));
        tracks.add(insertIndex, track);

        if (shuffleEnabled && originalQueueOrder.size() > 0) {
            String currentOriginalVideoId = externalGetCurrentVideoId();
            int origIndex = -1;
            for (int i = 0; i < originalQueueOrder.size(); i++) {
                if (TextUtils.equals(originalQueueOrder.get(i).videoId, currentOriginalVideoId)) {
                    origIndex = i;
                    break;
                }
            }
            int origInsertIndex = origIndex >= 0 ? Math.min(origIndex + 1, originalQueueOrder.size()) : originalQueueOrder.size();
            originalQueueOrder.add(origInsertIndex, track);
        } else {
            cacheOriginalQueueOrder();
        }

        refreshNextUp();
        invalidateNextTrackPreparations();
        persistPlaybackSnapshot(false);
        syncMiniStateWithPlaylist();
    }

    public void externalEnqueue(
            @NonNull String videoId,
            @NonNull String title,
            @NonNull String artist,
            @NonNull String duration,
            @NonNull String imageUrl
    ) {
        if (!isAdded()) return;
        PlayerTrack track = new PlayerTrack(
                safeValue(videoId),
                safeValue(title),
                safeValue(artist),
                safeValue(duration),
                safeValue(imageUrl)
        );

        tracks.add(track);

        if (shuffleEnabled && originalQueueOrder.size() > 0) {
            originalQueueOrder.add(track);
        } else {
            cacheOriginalQueueOrder();
        }

        refreshNextUp();
        invalidateNextTrackPreparations();
        persistPlaybackSnapshot(false);
        syncMiniStateWithPlaylist();
    }

    public void externalReplaceQueue(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex,
            boolean keepPlaying
    ) {
        externalReplaceQueueInternal(
                videoIds,
                titles,
                artists,
                durations,
                images,
                selectedIndex,
                keepPlaying,
                false
        );
    }

    public void externalReplaceQueueFromStart(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex,
            boolean keepPlaying
    ) {
        externalReplaceQueueInternal(
                videoIds,
                titles,
                artists,
                durations,
                images,
                selectedIndex,
                keepPlaying,
                true
        );
    }

    private void externalReplaceQueueInternal(
            @NonNull ArrayList<String> videoIds,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex,
            boolean keepPlaying,
            boolean startFromBeginning
    ) {
        if (!isAdded()) {
            return;
        }

        consecutiveStreamFailures = 0; // Reset failures on external queue replacement

        int targetIndexFromArgs = Math.max(0, selectedIndex);
        String targetVideoId = targetIndexFromArgs < videoIds.size()
                ? safeValue(videoIds.get(targetIndexFromArgs))
                : "";

        // Important: check if the track we ARE playing is the same as the one we WILL play
        boolean sameAsLoaded = !TextUtils.isEmpty(targetVideoId)
                && TextUtils.equals(targetVideoId, loadedVideoId);
        Log.d(TAG, "[PLAYBACK_DBG] replaceQueue sameAsLoaded=" + sameAsLoaded + " startFromBeginning=" + startFromBeginning + " videoId=" + targetVideoId + " loadedVideoId=" + loadedVideoId);

        // Push current track to global history before replacing queue
        if (!sameAsLoaded && currentIndex >= 0 && currentIndex < tracks.size()) {
            PlayerTrack prev = tracks.get(currentIndex);
            if (prev != null && !TextUtils.isEmpty(prev.videoId)) {
                if (globalPlaybackHistory.size() >= MAX_GLOBAL_HISTORY) {
                    globalPlaybackHistory.pollLast();
                }
                globalPlaybackHistory.addFirst(prev);
            }
        }

        // ALWAYS stop previous audio before starting new playback to prevent overlap
        if (!sameAsLoaded) {
            // Stop any playing audio immediately to prevent mixing
            stopLocalProgressTicker();
            releaseLocalExoMediaPlayer();
            usingOfflineSource = false;
        }

        tracks.clear();

        int count = Math.min(videoIds.size(), Math.min(titles.size(), Math.min(artists.size(), Math.min(durations.size(), images.size()))));
        for (int i = 0; i < count; i++) {
            tracks.add(new PlayerTrack(
                    safeValue(videoIds.get(i)),
                    safeValue(titles.get(i)),
                    safeValue(artists.get(i)),
                    safeValue(durations.get(i)),
                    safeValue(images.get(i))
            ));
        }

        if (tracks.isEmpty()) {
            tracks.add(new PlayerTrack("", "Track", "Artist", "0:00", ""));
            currentIndex = 0;
            isPlaying = false;
            loadedVideoId = "";
            bindCurrentTrack(true);
            return;
        }

        currentIndex = Math.max(0, Math.min(selectedIndex, tracks.size() - 1));
        isPlaying = keepPlaying;
        
        // Only clear loadedVideoId if we are actually switching tracks OR starting from beginning.
        // This prevents the current song from RESTARTING when only the rest of the queue changed.
        if (!sameAsLoaded || startFromBeginning) {
            loadedVideoId = "";
        }

        cacheOriginalQueueOrder();
        if (shuffleEnabled) {
            randomizeQueueFromCurrentTrack();
        }

        if (startFromBeginning) {
            currentSeconds = 0;
        }

        if (!sameAsLoaded || startFromBeginning) {
            bindCurrentTrack(!startFromBeginning);
            if (isPlaying) {
                playCurrentTrack();
            }
        } else {
            // Queue changed but current song is the same: lightweight sync only.
            // Do NOT call bindCurrentTrack or playCurrentTrack to avoid any lag/pause.
            refreshNextUp();
            invalidateNextTrackPreparations();
        }
        
        persistPlaybackSnapshot(false);
    }

    public void enterMiniMode() {
        collapseToMiniMode(true);
    }

    public boolean externalTryEnterMiniMode() {
        if (!isHidden()) {
            return collapseToMiniMode(true);
        }
        return false;
    }

    private void setupBackPressToMiniMode() {
        if (!isAdded()) {
            return;
        }
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                collapseToMiniMode(true);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);
        updateBackPressedCallbackEnabled(isHidden());
    }

    private void updateBackPressedCallbackEnabled(boolean hidden) {
        if (backPressedCallback == null) {
            return;
        }
        backPressedCallback.setEnabled(!hidden && isResumed());
    }

    private boolean isVideoTrackId(String videoId) {
        // Static/no-source classification: never video. Video is decided ONLY from the active
        // source (see isVideoTrack / isVideoPresentation): online (NewPipe audio) is always music,
        // and a track merely HAVING an offline .mp4 does not make it video.
        return false;
    }

    /** True when the current source carries video: an OFFLINE .mp4 with a real video track, or a
     *  NETWORK muxed mp4-360 stream (music videos enabled → StreamResolver resolved a VIDEO source).
     *  Gating the offline case on {@link #usingOfflineSource} still prevents the old bug where a
     *  download landing mid-song flipped an online audio stream into black video mode; the network
     *  case is driven by the resolved source type, which is audio unless music videos are enabled. */
    private boolean isVideoTrack(PlayerTrack track) {
        if (track == null) return false;
        if (usingOfflineSource) return offlineFileHasVideoTrack(track.videoId);
        return StreamResolver.isVideoSource(track.videoId);
    }

    /** Video/music classification for PRESENTATION (cover, backdrop, hero, surface). Only the
     *  actively-loaded track can be video, whether from an offline .mp4 or a network mp4-360 stream. */
    private boolean isVideoPresentation(@Nullable PlayerTrack track) {
        if (track == null) return false;
        if (TextUtils.isEmpty(loadedVideoId) || !TextUtils.equals(loadedVideoId, track.videoId)) {
            return false;
        }
        // Offline: probe the actual file (also triggers the async probe + a re-sync on completion).
        if (usingOfflineSource) return offlineFileHasVideoTrack(track.videoId);
        // Network: follow the CURRENTLY-loaded player's committed source (captured at the last
        // commit), NOT the live StreamResolver.isVideoSource() cache. That global cache is keyed only
        // by videoId and flips on a mode toggle, a next-track prefetch, or a reverted swap — reading
        // it here is exactly what made the cover/surface disagree with what was really playing.
        return currentSourceIsVideo;
    }

    /** Probes the offline file for a real video track (cached). Returns false for online-only or
     *  audio-only (plain song) offline files, so those present as music.
     *  If the result is not yet cached, the probe is dispatched to backgroundExecutor and
     *  false is returned immediately so the main thread is never blocked on disk I/O. */
    private boolean offlineFileHasVideoTrack(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId) || !isAdded()) return false;
        Boolean cached = offlineVideoProbeCache.get(videoId);
        if (cached != null) return cached;

        java.io.File file = OfflineAudioStore.getExistingOfflineAudioFile(requireContext(), videoId);
        if (file == null || !file.isFile() || file.length() <= 0L) {
            // Not downloaded yet — do not cache, so a later download is detected.
            return false;
        }

        final String probeVideoId = videoId;
        final String filePath = file.getAbsolutePath();
        backgroundExecutor.execute(() -> {
            boolean hasVideo = false;
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            try {
                extractor.setDataSource(filePath);
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { hasVideo = true; break; }
                }
            } catch (Exception e) {
                hasVideo = false;
            } finally {
                try { extractor.release(); } catch (Exception ignored) {}
            }
            final boolean result = hasVideo;
            putOfflineVideoProbe(probeVideoId, result);
            localProgressHandler.post(() -> {
                if (!isAdded()) return;
                if (currentIndex >= 0 && currentIndex < tracks.size()
                        && TextUtils.equals(tracks.get(currentIndex).videoId, probeVideoId)
                        && TextUtils.equals(loadedVideoId, probeVideoId)) {
                    // El attach original corrió con isVideo=false (cache miss del probe). Si el
                    // archivo SÍ trae video, re-anunciar el track al router ANTES de refrescar la
                    // presentación: su rama sameUnderlying permite el attach tardío cuando
                    // videoActive pasa de false a true para el mismo player, así el hero nunca
                    // queda negro (superficie sin adjuntar) mientras suena el audio.
                    if (result && usingOfflineSource && localExoMediaPlayer != null) {
                        currentSourceIsVideo = true;
                        videoRouter.onTrackStarted(localExoMediaPlayer, probeVideoId, true);
                    }
                    updatePlayerSurfaceForSource();
                }
            });
        });
        return false;
    }

    // ─── Pastilla Audio|Video: cambio de modo EN CALIENTE (sin pausar) ──────────────

    private void onPlaybackModeSelected(boolean videoMode) {
        if (videoMode == playerVideoMode || modeSwapInProgress) return;
        // Candado duro "No reproducir videos musicales" (default ON, sincronizado en Firebase):
        // bloquea el cambio a video en su origen. StreamResolver tiene el mismo gate, así que
        // ningún camino (restauración, prefetch, swap) puede servir video con el ajuste activo.
        if (videoMode && noMusicVideosEnabled()) {
            if (isAdded()) {
                AppSnackbar.showInView(getPlayerToastRoot(),
                        "Los videos musicales están desactivados en Ajustes",
                        null, null, playerToastBottomMarginPx());
            }
            return;
        }

        PlayerTrack track = (currentIndex >= 0 && currentIndex < tracks.size())
                ? tracks.get(currentIndex) : null;
        if (videoMode && track != null && LocalFilesStore.isLocalVideoId(track.videoId)) {
            if (isAdded()) {
                AppSnackbar.showInView(getPlayerToastRoot(), "No disponible para archivos locales",
                        null, null, playerToastBottomMarginPx());
            }
            return;
        }
        // Bloqueo por DATOS (counterpart del /next): la canción no tiene video musical — sin esto
        // el swap "triunfaba" reproduciendo el mp4 de portada estática del upload Topic.
        if (videoMode && track != null
                && MusicVideoAvailability.get(track.videoId) == MusicVideoAvailability.State.NO) {
            if (isAdded()) {
                AppSnackbar.showInView(getPlayerToastRoot(),
                        "Video no disponible para esta canción",
                        null, null, playerToastBottomMarginPx());
            }
            return;
        }

        playerVideoMode = videoMode;
        StreamResolver.setPreferVideoMode(videoMode);
        updatePlaybackModePillUi();
        // Los prefetch/pre-buffers del siguiente track se hicieron con el modo anterior —
        // invalidarlos para que se re-preparen con la fuente correcta. El re-prefetch se
        // difiere para no competir con el resolve del hot-swap en el pool de 3 hilos.
        invalidateNextTrackPreparations(false);

        if (track == null || TextUtils.isEmpty(track.videoId) || localExoMediaPlayer == null) {
            return; // nada sonando: la próxima reproducción ya usará el modo nuevo
        }
        if (LocalFilesStore.isLocalVideoId(track.videoId)) {
            return; // archivo local: ya suena como audio, no hay nada que intercambiar
        }
        if (crossfadeManager.isInProgress()) {
            return; // transición en curso: el track entrante ya resolverá con el modo nuevo
        }
        hotSwapPlaybackMode(track, videoMode);
    }

    /** Lee el ajuste "No reproducir videos musicales" (default ON — ver CloudSyncManager). */
    private boolean noMusicVideosEnabled() {
        try {
            return requireContext().getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean(CloudSyncManager.KEY_NO_MUSIC_VIDEOS, true);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Aplica el candado "No reproducir videos musicales": oculta/muestra la pastilla
     * Canción|Video y, si un video está sonando con el candado activo, vuelve a canción EN
     * CALIENTE (sin pausar). Lo llaman Ajustes al togglear el switch y onViewCreated al montar.
     * Seguro con el player vacío o el fragment despegado.
     */
    public void applyNoMusicVideosSetting() {
        boolean blocked = noMusicVideosEnabled();
        View root = getView();
        View pill = root != null ? root.findViewById(R.id.pillPlaybackMode) : null;
        if (pill != null) pill.setVisibility(blocked ? View.GONE : View.VISIBLE);
        if (!blocked) return;
        if (playerVideoMode && localExoMediaPlayer != null && !modeSwapInProgress) {
            onPlaybackModeSelected(false); // swap en caliente Video→Canción
        } else if (playerVideoMode) {
            // Nada intercambiable sonando (o swap en curso): al menos desarma el modo para la
            // próxima resolución; el gate del resolver cubre cualquier carrera restante.
            playerVideoMode = false;
            StreamResolver.setPreferVideoMode(false);
            updatePlaybackModePillUi();
        } else {
            StreamResolver.setPreferVideoMode(false);
        }
    }

    private void updatePlaybackModePillUi() {
        if (tvModeAudio == null || tvModeVideo == null) return;
        // El lado activo va en blanco puro; el inactivo, blanco al 70% (0xB3) para atenuarlo
        // ligeramente. El segmento activo llena TODA su mitad (drawable con la esquina exterior
        // redondeada según el lado).
        if (playerVideoMode) {
            tvModeVideo.setTextColor(0xFFFFFFFF);
            tvModeAudio.setTextColor(0xB3FFFFFF);
            tvModeVideo.setBackgroundResource(R.drawable.bg_player_mode_segment_active_right);
            tvModeAudio.setBackground(null);
        } else {
            tvModeAudio.setTextColor(0xFFFFFFFF);
            tvModeVideo.setTextColor(0xB3FFFFFF);
            tvModeAudio.setBackgroundResource(R.drawable.bg_player_mode_segment_active_left);
            tvModeVideo.setBackground(null);
        }
    }

    /**
     * Pastilla Canción|Video según disponibilidad REAL de video (counterpart del /next de YT
     * Music, ver MusicVideoAvailability): sin video → «Video» atenuado y el tap avisa en vez de
     * reproducir el mp4 de portada estática. Si aún no se sabe (UNKNOWN), se dispara UN probe por
     * canción y la pastilla queda habilitada mientras tanto (el fallo de swap sigue siendo la red
     * de seguridad).
     */
    private void refreshVideoPillAvailability(@Nullable PlayerTrack track) {
        if (tvModeVideo == null) return;
        if (track == null || TextUtils.isEmpty(track.videoId)
                || LocalFilesStore.isLocalVideoId(track.videoId) || noMusicVideosEnabled()) {
            applyVideoAvailabilityToPill(MusicVideoAvailability.State.UNKNOWN);
            return;
        }
        MusicVideoAvailability.State state = MusicVideoAvailability.get(track.videoId);
        applyVideoAvailabilityToPill(state);
        if (state != MusicVideoAvailability.State.UNKNOWN) return;
        if (TextUtils.equals(pendingCounterpartVideoId, track.videoId)) return;
        pendingCounterpartVideoId = track.videoId;
        final String probeId = track.videoId;
        radioMusicService.fetchVideoCounterpart(getWebCookie(), probeId, resolved -> {
            if (TextUtils.equals(pendingCounterpartVideoId, probeId)) pendingCounterpartVideoId = null;
            if (!isAdded()) return;
            PlayerTrack current = (currentIndex >= 0 && currentIndex < tracks.size())
                    ? tracks.get(currentIndex) : null;
            if (current != null && TextUtils.equals(current.videoId, probeId)) {
                applyVideoAvailabilityToPill(resolved);
            }
        });
    }

    /** NO hay video → «Video» atenuado (el tap lo intercepta onPlaybackModeSelected). */
    private void applyVideoAvailabilityToPill(MusicVideoAvailability.State state) {
        if (tvModeVideo == null) return;
        tvModeVideo.setAlpha(state == MusicVideoAvailability.State.NO ? 0.35f : 1f);
    }

    /** Vuelve la pastilla al modo contrario tras un fallo de swap y avisa. */
    private void revertPlaybackModeAfterFailure(boolean attemptedVideo) {
        playerVideoMode = !attemptedVideo;
        StreamResolver.setPreferVideoMode(playerVideoMode);
        updatePlaybackModePillUi();
        invalidateNextTrackPreparations();
        if (isAdded()) {
            AppSnackbar.showInView(getPlayerToastRoot(), attemptedVideo
                    ? "Video no disponible para esta canción"
                    : "Audio no disponible", null, null, playerToastBottomMarginPx());
        }
    }

    /**
     * Deja listo el swap a «Video» mientras una pista suena en modo AUDIO. Dos etapas:
     *  • C1 (toda red): asegura que la URL del video muxed (itag 18) esté en el side-cache de
     *    StreamResolver. Normalmente ya se extrae GRATIS durante la resolución de audio, pero si la
     *    pista se sirvió de cache (disco/memoria) esa extracción NO ocurrió y la URL quedaba
     *    desconocida → el tap a «Video» hacía un fetch de red completo (lento). Aquí la resolvemos
     *    de fondo (metadata liviana, como el prefetch de audio) para que resolveStreamUrl NO toque
     *    la red al tocar «Video».
     *  • C2 (solo WiFi): precalienta ~1.5MB de la cabecera del stream en el exo_stream_cache para que
     *    el player del swap prepare casi desde disco. El warm pesado queda en WiFi para no gastar
     *    datos móviles con un stream que el usuario quizá nunca mire (respeta data-saver).
     * Con C1 asegurado en toda red, el swap es rápido también con datos móviles (sin fetch en el tap).
     */
    private void maybeWarmVideoStreamHead(@Nullable String videoId) {
        // Reinicia siempre el estado del precalentado previo (cambio de pista) — en el hilo principal.
        cancelVideoStreamWarm();
        warmedVideoId = null;
        if (TextUtils.isEmpty(videoId) || LocalFilesStore.isLocalVideoId(videoId)) return;
        // Candado "No reproducir videos": no gastar red (C1) ni cache (C2) en un video
        // que el usuario tiene bloqueado por Ajustes.
        if (noMusicVideosEnabled()) return;
        // Sin video musical (counterpart /next): la pastilla está bloqueada para esta canción,
        // no gastar red ni caché calentando un stream que nunca se pedirá.
        if (MusicVideoAvailability.get(videoId) == MusicVideoAvailability.State.NO) return;
        // Ya estamos en modo Video: el stream de video se cachea al reproducirlo, no hay que forzar.
        if (StreamResolver.isPreferVideoMode()) return;
        Context ctx = getContext();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        final String targetId = videoId;
        // Pool de resolución (no el backgroundExecutor de 1 hilo que usan el probe offline y el swap):
        // así este fetch corre en paralelo y nunca serializa detrás del resolve del hot-swap.
        streamResolverExecutor.submit(() -> {
            // C1: URL del video conocida sin red si ya estaba en el side-cache; si no, una resolución
            // de metadata (no descarga el media). NO toca urlCache (el audio sigue siendo DIRECT).
            String url = StreamResolver.getPrefetchedVideoUrl(targetId);
            if (TextUtils.isEmpty(url)) {
                url = StreamResolver.prefetchVideoUrl(appCtx, targetId);
            }
            if (TextUtils.isEmpty(url)) return; // la pista no tiene video muxed
            final boolean warmHead = StreamResolver.isOnWifi(appCtx); // ~1.5MB → solo WiFi
            final String resolvedUrl = url;
            localProgressHandler.post(() -> {
                // Sigue siendo la pista actual y no cambiamos a Video mientras resolvíamos.
                if (!isAdded() || !TextUtils.equals(loadedVideoId, targetId)) return;
                if (StreamResolver.isPreferVideoMode()) return;
                if (!warmHead) return; // C1 ya dejó la URL lista; el warm de cabecera solo en WiFi
                cancelVideoStreamWarm();
                videoWarmHandle = ExoMediaPlayer.warmStreamHead(
                        appCtx, resolvedUrl, StreamResolver.getHeadersFor(targetId), VIDEO_WARM_HEAD_BYTES);
                warmedVideoId = targetId;
            });
        });
    }

    /** Cancela (sin liberar el cache) el precalentado de la cabecera de video en curso. NO borra
     *  warmedVideoId: al tocar «Video» la cabecera ya está en cache y la ruta rápida sigue válida;
     *  el flag se reinicia solo en la siguiente pista (maybeWarmVideoStreamHead). */
    private void cancelVideoStreamWarm() {
        if (videoWarmHandle != null) {
            try { videoWarmHandle.cancel(); } catch (Exception ignored) { }
            videoWarmHandle = null;
        }
    }

    /**
     * Cambia la fuente Audio↔Video EN CALIENTE: resuelve la fuente nueva en background,
     * prepara un SEGUNDO player en silencio sincronizado a la posición actual, y solo
     * entonces intercambia — la música nunca se pausa (ver commitHotSwap).
     */
    private void hotSwapPlaybackMode(@NonNull PlayerTrack track, boolean videoMode) {
        final String swapVideoId = track.videoId;
        final Context appCtx = requireContext().getApplicationContext();
        modeSwapInProgress = true;

        // Resolve on the 3-thread resolver pool, NOT the single-thread backgroundExecutor: switching
        // to video used to queue behind the offline MediaExtractor probe and other serial work, which
        // is a big part of why the swap felt heavy. Same lifecycle (both shutdownNow in onDestroy).
        streamResolverExecutor.execute(() -> {
            String source = null;
            if (!videoMode) {
                // Modo audio: preferir el archivo offline si existe (sin red, instantáneo).
                java.io.File offline = OfflineAudioStore.getExistingOfflineAudioFile(appCtx, swapVideoId);
                if (offline.isFile() && offline.length() > 0L) {
                    source = offline.getAbsolutePath();
                    // El commit leerá isVideoTrack → offlineVideoProbeCache. Resolver el probe
                    // AQUÍ (ya estamos en background) da al commit la respuesta definitiva; el
                    // viejo flip async post-commit dejaba el hero negro (presentación de video
                    // sin superficie adjunta) cuando el .mp4 descargado sí traía video.
                    ensureOfflineVideoProbeCached(swapVideoId, offline);
                    // El playback real vuelve a audio SIN pasar por resolveStreamUrl: degradar la
                    // entrada VIDEO global para que isVideoSource() no siga reportando video.
                    StreamResolver.demoteVideoEntry(swapVideoId);
                }
            }
            if (source == null) {
                source = StreamResolver.resolveStreamUrl(appCtx, swapVideoId);
            }
            final String resolved = source;
            // Ruta rápida: fuente local/offline, o stream cuya cabecera YA está en el
            // exo_stream_cache (warm C2 en WiFi, o bytes que quedaron cacheados al reproducirlo
            // antes — el caso Video→Canción, donde el audio recién sonó y sus bytes están en
            // disco). Derivarla del CACHE REAL y no del flag de warm (solo-WiFi) habilita los
            // tiempos rápidos también con datos móviles.
            boolean headCached = false;
            if (resolved != null) {
                if (!isHttpStreamSource(resolved)) {
                    headCached = true;
                } else {
                    try {
                        // La clave del cache es la ESTABLE (gv:id:itag para googlevideo), no la
                        // URL cruda — con la URL este fast-path nunca acertaba para streams.
                        headCached = ExoMediaPlayer.getSharedCache(appCtx)
                                .getCachedBytes(ExoMediaPlayer.stableCacheKey(
                                        android.net.Uri.parse(resolved)), 0, 65_536L) > 0L;
                    } catch (Exception ignored) {
                    }
                }
            }
            final boolean fastSwap = headCached;
            localProgressHandler.post(() -> {
                if (!isAdded() || !TextUtils.equals(loadedVideoId, swapVideoId) || localExoMediaPlayer == null) {
                    modeSwapInProgress = false; // cambió la canción mientras resolvíamos
                    return;
                }
                if (TextUtils.isEmpty(resolved)
                        || (videoMode && !StreamResolver.isVideoSource(swapVideoId))) {
                    // Sin fuente, o pidió video y el track no tiene video muxed (el resolver
                    // cayó a audio): revertir la pastilla y avisar.
                    modeSwapInProgress = false;
                    revertPlaybackModeAfterFailure(videoMode);
                    return;
                }
                prepareHotSwapPlayer(track, resolved, videoMode, appCtx, fastSwap);
            });
        });
    }

    private void prepareHotSwapPlayer(
            @NonNull PlayerTrack track,
            @NonNull String source,
            boolean videoMode,
            @NonNull Context appCtx,
            boolean fastSwap
    ) {
        final String swapVideoId = track.videoId;
        final boolean networkSource = isHttpStreamSource(source);

        final ExoMediaPlayer next;
        try {
            // Player PROPIO: el actual puede estar sobre el ExoPlayer compartido; usarlo para
            // los dos a la vez es imposible. isCrossfadeComponent evita que stopOthers pause
            // al player actual cuando el nuevo haga start() (y viceversa).
            next = new ExoMediaPlayer(appCtx);
        } catch (Exception e) {
            modeSwapInProgress = false;
            revertPlaybackModeAfterFailure(videoMode);
            return;
        }
        pendingModeSwapPlayer = next;
        next.isCrossfadeComponent = true;
        next.setVolume(0f, 0f); // se prepara en silencio; suena recién en el commit
        // Seek EXACT mientras se sincroniza en silencio: el intercambio sin pausa depende de
        // posiciones casi exactas entre viejo y nuevo; CLOSEST_SYNC (el default del constructor)
        // se restablece al adoptarlo como player activo en commitHotSwap.
        next.setSeekToClosestSync(false);
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());

        final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        final Runnable swapTimeout = () -> {
            if (!settled.compareAndSet(false, true)) return;
            if (pendingModeSwapPlayer == next) pendingModeSwapPlayer = null;
            try { next.release(); } catch (Exception ignored) { }
            modeSwapInProgress = false;
            revertPlaybackModeAfterFailure(videoMode);
        };
        localProgressHandler.postDelayed(swapTimeout, HOT_SWAP_TIMEOUT_MS);

        next.setOnErrorListener((mp, what, extra) -> {
            if (!settled.compareAndSet(false, true)) return true;
            localProgressHandler.removeCallbacks(swapTimeout);
            if (pendingModeSwapPlayer == next) pendingModeSwapPlayer = null;
            try { mp.release(); } catch (Exception ignored) { }
            modeSwapInProgress = false;
            revertPlaybackModeAfterFailure(videoMode);
            return true;
        });

        next.setOnPreparedListener(mp -> {
            if (settled.get()) return;
            if (!isAdded() || !TextUtils.equals(loadedVideoId, swapVideoId) || localExoMediaPlayer == null) {
                if (settled.compareAndSet(false, true)) {
                    localProgressHandler.removeCallbacks(swapTimeout);
                    if (pendingModeSwapPlayer == next) pendingModeSwapPlayer = null;
                    try { mp.release(); } catch (Exception ignored) { }
                    modeSwapInProgress = false;
                }
                return;
            }
            // Sincronizar por delante de la posición actual (compensa el tiempo de commit) y
            // comprometer POR DISPONIBILIDAD: apenas el nuevo player tiene ~300ms de buffer por
            // delante del objetivo (poll de 50ms) se hace el intercambio — una fuente local o
            // con cabecera cacheada comete en el primer poll. El delay histórico queda solo
            // como techo; si se agota, el commit corre igual y su re-sincronización fina
            // corrige el drift.
            final int seekLead = fastSwap ? HOT_SWAP_SEEK_LEAD_FAST_MS : HOT_SWAP_SEEK_LEAD_MS;
            final long maxCommitDelay = fastSwap ? HOT_SWAP_COMMIT_DELAY_FAST_MS : HOT_SWAP_COMMIT_DELAY_MS;
            final int target = localExoMediaPlayer.getCurrentPosition() + seekLead;
            mp.seekTo(target);
            final long commitDeadlineMs = SystemClock.elapsedRealtime() + maxCommitDelay;
            final Runnable commitWhenBuffered = new Runnable() {
                @Override
                public void run() {
                    if (settled.get()) return;
                    boolean buffered;
                    try {
                        int bufferedPos = mp.getBufferedPosition();
                        int duration = mp.getDuration();
                        // Listo cuando hay margen por delante del objetivo, o cuando el buffer
                        // llegó al final del stream (objetivo cerca del fin de la pista).
                        buffered = bufferedPos >= target + HOT_SWAP_COMMIT_BUFFER_MS
                                || (duration > 0 && bufferedPos >= duration - 250);
                    } catch (Exception e) {
                        buffered = false;
                    }
                    if (!buffered && SystemClock.elapsedRealtime() < commitDeadlineMs) {
                        localProgressHandler.postDelayed(this, 50L);
                        return;
                    }
                    if (!settled.compareAndSet(false, true)) return;
                    localProgressHandler.removeCallbacks(swapTimeout);
                    commitHotSwap(mp, track, videoMode, networkSource, source);
                }
            };
            localProgressHandler.post(commitWhenBuffered);
        });

        try {
            if (networkSource) {
                next.setDataSource(appCtx, Uri.parse(source), StreamResolver.getHeadersFor(swapVideoId));
            } else {
                next.setDataSource(source);
            }
            next.prepareAsync();
        } catch (Exception e) {
            if (settled.compareAndSet(false, true)) {
                localProgressHandler.removeCallbacks(swapTimeout);
                if (pendingModeSwapPlayer == next) pendingModeSwapPlayer = null;
                try { next.release(); } catch (Exception ignored) { }
                modeSwapInProgress = false;
                revertPlaybackModeAfterFailure(videoMode);
            }
        }
    }

    /** El intercambio real: silencia el viejo, arranca el nuevo ya sincronizado y lo adopta
     *  como player activo con los listeners de régimen normal. La música no se pausa. */
    private void commitHotSwap(
            @NonNull ExoMediaPlayer next,
            @NonNull PlayerTrack track,
            boolean videoMode,
            boolean networkSource,
            @NonNull String source
    ) {
        if (pendingModeSwapPlayer == next) pendingModeSwapPlayer = null;
        if (!isAdded() || !TextUtils.equals(loadedVideoId, track.videoId)
                || localExoMediaPlayer == null || crossfadeManager.isInProgress()) {
            try { next.release(); } catch (Exception ignored) { }
            modeSwapInProgress = false;
            return;
        }

        ExoMediaPlayer old = localExoMediaPlayer;
        boolean keepPlaying = isPlaying && !pauseRequestedByUser;

        // Re-sincronización fina: si el buffering del nuevo tardó más de lo previsto y el
        // viejo se adelantó, corregir antes de sonar (el seek cae dentro del buffer → rápido).
        try {
            int drift = old.getCurrentPosition() - next.getCurrentPosition();
            if (Math.abs(drift) > 900) next.seekTo(old.getCurrentPosition() + 120);
        } catch (Exception ignored) { }

        // Swap sin pausa: mute del viejo y start del nuevo en el mismo frame.
        try { old.setVolume(0f, 0f); } catch (Exception ignored) { }
        next.setVolume(1f, 1f);
        if (keepPlaying) next.start();

        localExoMediaPlayer = next;
        // Promoción del swap de modo (canción/video): el nuevo player audible reporta al EQ.
        next.markAsActiveForEq();
        next.isCrossfadeComponent = false;
        // Ya sincronizado y adoptado: volver a CLOSEST_SYNC para que los seeks del usuario
        // sobre este player (ahora el activo) retomen rápido en fuentes con video.
        next.setSeekToClosestSync(true);
        usingOfflineSource = !networkSource;
        // Capture the committed source type BEFORE we tell the router / refresh presentation, using
        // the SAME isVideoTrack(track) the surface attach (below) uses. This is what makes a reverted
        // or aborted swap safe: if this commit runs, presentation and surface agree; if it never runs
        // (timeout/revert), the flag keeps the previous track's value and the global VIDEO cache set
        // during the resolve can no longer flip the cover to a black, surface-less "video".
        currentSourceIsVideo = isVideoTrack(track);
        currentVideoFilePath = networkSource ? null : source;

        next.setOnCompletionListener(mp -> {
            if (localExoMediaPlayer != mp) return;
            handleLocalPlaybackCompletion();
        });
        next.setOnErrorListener((mp, what, extra) -> {
            if (localExoMediaPlayer == mp) {
                stopLocalProgressTicker();
                releaseLocalExoMediaPlayer();
                usingOfflineSource = false;
            } else {
                releaseSingleExoMediaPlayer(mp);
            }
            advanceToNextTrackAfterFailure();
            return true;
        });

        // Diferido: liberar el player viejo dentro del frame del commit era parte de la pausa
        // perceptible del intercambio (ExoPlayer.release() bloquea hasta ~500ms en main).
        try { old.releaseAsync(); } catch (Exception ignored) { }

        try {
            AudioEffectsService.sendApply(requireContext().getApplicationContext());
        } catch (Exception ignored) { }

        try {
            totalSeconds = Math.max(1, next.getDuration() / 1000);
        } catch (Exception ignored) { }

        // Switch-to-video: keep the song cover as a POSTER over the still-black PlayerView until the
        // first video frame actually renders, so there is no black flash and the switch feels instant.
        // updatePlayerSurfaceForSource() below honours swapAwaitingFirstFrame and does NOT fade the
        // cover; this first-frame listener clears the flag and fades it out once the video paints.
        if (currentSourceIsVideo) {
            swapAwaitingFirstFrame = true;
            if (pbVideoLoading != null) pbVideoLoading.setVisibility(View.VISIBLE);
            final ExoMediaPlayer swapPlayer = next;
            next.setOnRenderedFirstFrameListener(mp -> {
                if (!isAdded() || localExoMediaPlayer != mp || !swapAwaitingFirstFrame) return;
                swapAwaitingFirstFrame = false;
                if (pbVideoLoading != null) pbVideoLoading.setVisibility(View.GONE);
                fadeOutCoverForVideo();
            });
            // Fallback so a stalled stream never leaves the poster (or spinner) up forever.
            localProgressHandler.postDelayed(() -> {
                if (swapAwaitingFirstFrame && localExoMediaPlayer == swapPlayer) {
                    swapAwaitingFirstFrame = false;
                    if (pbVideoLoading != null) pbVideoLoading.setVisibility(View.GONE);
                    fadeOutCoverForVideo();
                }
            }, SWAP_VIDEO_POSTER_TIMEOUT_MS);
        } else {
            swapAwaitingFirstFrame = false;
        }

        // Attach the surface FIRST, then refresh presentation — so the cover only hides once the
        // video surface is actually in place (never a black, surface-less hero mid-swap).
        videoRouter.onTrackStarted(next, track.videoId, currentSourceIsVideo);
        updatePlayerSurfaceForSource();
        updatePlayPauseIcon();
        updateMediaSessionState();
        if (keepPlaying) startLocalProgressTicker();
        modeSwapInProgress = false;
        Log.d(TAG, "modeSwap: committed videoMode=" + videoMode + " videoId=" + track.videoId);
    }

    /** Fades the song cover out and hides it, revealing the video surface underneath. Used by the
     *  normal video-start path and by the switch-to-video swap's first-frame listener (poster mode). */
    private void fadeOutCoverForVideo() {
        if (ivPlayerCover == null) return;
        if (ivPlayerCover.getVisibility() == View.VISIBLE) {
            ivPlayerCover.animate().cancel();
            ivPlayerCover.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                ivPlayerCover.setVisibility(View.GONE);
            }).start();
        } else {
            ivPlayerCover.setVisibility(View.GONE);
        }
    }

    private void updatePlayerSurfaceForSource() {
        // Keep cover visible if we are playing normal audio (network track or offline audio).
        // Only hide cover if we are playing a local device video file (which contains video content to show).
        boolean isLocalVideo = false;
        if (currentIndex >= 0 && currentIndex < tracks.size()) {
            PlayerTrack current = tracks.get(currentIndex);
            if (current != null && isVideoPresentation(current)) {
                isLocalVideo = true;
            }
        }

        if (ivPlayerCover != null) {
            if (isLocalVideo) {
                // VIDEO: the hero must be FULL-WIDTH (16:9, no side margins) or the video keeps the
                // song cover's 20dp inset + 1:1 square box and renders narrow. Reshape BEFORE touching
                // the cover so the surface lands full-width in one step.
                applyHeroShapeForVideo();
                if (swapAwaitingFirstFrame) {
                    // Poster mode (switch-to-video swap): keep the song cover fully visible ON TOP of
                    // the not-yet-rendered (black) PlayerView so there is no black flash. commitHotSwap's
                    // first-frame listener fades it out once the video actually paints.
                    ivPlayerCover.animate().cancel();
                    ivPlayerCover.setVisibility(View.VISIBLE);
                    ivPlayerCover.setAlpha(1f);
                    ivPlayerCover.bringToFront();
                    if (pbVideoLoading != null) pbVideoLoading.bringToFront();
                } else {
                    fadeOutCoverForVideo();
                }
            } else {
                boolean wasVideoShape = heroShapedForVideo; // ¿venimos saliendo de una presentación de video?
                if (wasVideoShape) {
                    // Returning from a video shape to a music presentation. Cancel any in-flight
                    // fade-out FIRST — otherwise its withEndAction keeps driving alpha→0 and re-hides
                    // the cover, which is exactly the "todo negro, no sale la portada ni el color"
                    // bug when returning to Canción.
                    ivPlayerCover.animate().cancel();
                    swapAwaitingFirstFrame = false;
                    restoreMusicHeroShape();
                    ivPlayerCover.setVisibility(View.VISIBLE);
                    // Restore the cover + its dominant-color gradient instantly ONLY when the cover
                    // still holds THIS song's bitmap (lastSongArtVideoId) — i.e. a same-track
                    // Video→Canción swap. Restoring an unverified drawable here used to resurrect
                    // the PREVIOUS track's art when the track had changed while in video mode.
                    if (ivPlayerCover.getDrawable() != null
                            && TextUtils.equals(lastSongArtVideoId, loadedVideoId)) {
                        ivPlayerCover.setAlpha(1f);
                        if (!StreamResolver.isPreferVideoMode()
                                && lastSongColorValid && playerBackgroundContainer != null) {
                            animateBackgroundTransition(buildDominantGradient(lastSongDominantColor));
                        }
                    } else if (lastSongCoverBitmap != null && !lastSongCoverBitmap.isRecycled()
                            && TextUtils.equals(lastSongArtVideoId, loadedVideoId)) {
                        // El drawable se anuló al entrar a video, pero la carátula de ESTA pista
                        // está cacheada (las entregas en modo video también se cachean): aplicarla
                        // con su forma y color al instante — el hero nunca queda negro esperando
                        // una carga que jamás se relanzó.
                        Bitmap b = lastSongCoverBitmap;
                        ivPlayerCover.animate().cancel();
                        applyHeroShapeForAspect((float) b.getWidth() / Math.max(1, b.getHeight()),
                                b.getWidth(), b.getHeight(), playerArtworkGeneration);
                        ivPlayerCover.setImageBitmap(b);
                        ivPlayerCover.setAlpha(1f);
                        if (lastSongColorValid && playerBackgroundContainer != null) {
                            animateBackgroundTransition(buildDominantGradient(lastSongDominantColor));
                        }
                    } else {
                        // Sin cache válido para esta pista: NINGUNA carga va a rellenar el cover
                        // sola (la entrega original pudo descartarse en modo video) — relanzar la
                        // carga de arte en vez de quedar negro para siempre. Se limpia el drawable
                        // primero: podría ser el arte VIEJO de otra pista y no debe flashear
                        // mientras la carga nueva está en vuelo.
                        ivPlayerCover.setImageDrawable(null);
                        ivPlayerCover.setAlpha(0f);
                        PlayerTrack loadTrack = (currentIndex >= 0 && currentIndex < tracks.size())
                                ? tracks.get(currentIndex) : null;
                        if (loadTrack != null) {
                            loadArtworkForCurrentTrack(loadTrack);
                        }
                    }
                } else {
                    // Normal music playback-start with the shape already correct. Leave the cover as
                    // the bind path set it: after a video→music TRACK CHANGE it is deliberately
                    // transparent (null drawable) until the new artwork lands — forcing alpha 1 here
                    // would flash the previous track's art over the new song.
                    ivPlayerCover.setVisibility(View.VISIBLE);
                    if (ivPlayerCover.getDrawable() != null) {
                        ivPlayerCover.setAlpha(1f);
                    }
                }
            }
        }

        // Music hero aspect/shape is otherwise owned by the cover artwork (bindCurrentTrack's
        // onResourceReady → applyHeroShapeForAspect). We still do NOT set a default ratio on every
        // music playback-start here (that stomped the previous cover's shape mid-swap and made the
        // image jump). restoreMusicHeroShape() above only fires when leaving the video shape.

        // Hide spinner for audio tracks; only show for local video
        if (pbVideoLoading != null && !isLocalVideo) {
            pbVideoLoading.setVisibility(View.GONE);
        }

        // If it's a local video, force black background. If not, the Palette extraction will set it dynamically.
        if (isLocalVideo && playerBackgroundContainer != null) {
            animateBackgroundTransition(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }
    }

    private boolean collapseToMiniMode(boolean animate) {
        if (!isAdded()) {
            return false;
        }
        if (swipeDismissGestureActive || swipeDismissAnimationRunning) {
            return true;
        }
        if (collapsingToMiniMode) {
            return true;
        }

        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) {
            return false;
        }

        collapsingToMiniMode = true;
        persistPlaybackSnapshot(false);
        syncMiniStateWithPlaylist();
        Fragment target = resolveReturnTarget(fm);

        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction()
                .setReorderingAllowed(true);

        Fragment equalizer = findAddedByTag(fm, "module_equalizer");
        Fragment settings = findAddedByTag(fm, "module_settings");
        Fragment scanner = findAddedByTag(fm, "module_scanner");

        if (equalizer != null) {
            transaction.hide(equalizer);
            if (equalizer.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                transaction.setMaxLifecycle(equalizer, androidx.lifecycle.Lifecycle.State.STARTED);
            }
        }
        if (settings != null) {
            transaction.hide(settings);
            if (settings.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                transaction.setMaxLifecycle(settings, androidx.lifecycle.Lifecycle.State.STARTED);
            }
        }
        if (scanner != null) {
            transaction.hide(scanner);
            if (scanner.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                transaction.setMaxLifecycle(scanner, androidx.lifecycle.Lifecycle.State.STARTED);
            }
        }

        if (target != null && target != this && target.isAdded()) {
            transaction
                    .setCustomAnimations(
                            R.anim.hold,
                            R.anim.player_screen_exit
                    )
                    .hide(this)
                    .show(target);
            transaction.commit();
        } else {
            transaction
                    .setCustomAnimations(
                            R.anim.hold,
                            R.anim.player_screen_exit
                    )
                    .remove(this);
            transaction.commit();
        }

        // Restore bottomNav visibility when returning to the main module
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).ensureHeaderVisibleForMusic();
        }

        collapsingToMiniMode = false;
        return true;
    }

    @Nullable
    private Fragment findAddedByTag(@NonNull FragmentManager fm, @NonNull String tag) {
        Fragment fragment = fm.findFragmentByTag(tag);
        if (fragment != null && fragment.isAdded() && fragment != this) {
            return fragment;
        }
        return null;
    }

    @Nullable
    private Fragment resolveReturnTarget(@NonNull FragmentManager fm) {
        if (!TextUtils.isEmpty(returnTargetTag)) {
            Fragment preferred = fm.findFragmentByTag(returnTargetTag);
            // A HIDDEN fragment is one the user navigated away from (e.g. a playlist_detail left
            // behind after switching modules). It must NOT be resurrected as the return target,
            // otherwise closing the player would pop a screen "out of nowhere" that was never open.
            if (preferred != null && preferred.isAdded() && !preferred.isHidden()) {
                return preferred;
            }
        }

        Fragment playlist = fm.findFragmentByTag(TAG_PLAYLIST_DETAIL);
        if (playlist != null && playlist.isAdded() && !playlist.isHidden()) {
            return playlist;
        }

        Fragment music = fm.findFragmentByTag(TAG_MODULE_MUSIC);
        if (music != null && music.isAdded()) {
            return music;
        }

        Fragment apps = findAddedByTag(fm, "module_apps");
        if (apps != null) {
            return apps;
        }

        Fragment settings = findAddedByTag(fm, "module_settings");
        if (settings != null) {
            return settings;
        }

        return null;
    }


    private void openQueuePlaylistDetail() {
        if (!isAdded()) {
            return;
        }

        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) {
            return;
        }

        persistPlaybackSnapshot(false);
        syncMiniStateWithPlaylist();

        Fragment existingDetail = fm.findFragmentByTag(TAG_PLAYLIST_DETAIL);
        if (existingDetail != null && existingDetail.isAdded()) {
            fm.beginTransaction()
                    .setReorderingAllowed(true)
                    .hide(this)
                    .show(existingDetail)
                    .commit();
            return;
        }

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String playlistId = safeValue(prefs.getString(PREF_LAST_PLAYLIST_ID, ""));
        if (TextUtils.isEmpty(playlistId)) {
            
            return;
        }

        String playlistTitle = safeValue(prefs.getString(PREF_LAST_PLAYLIST_TITLE, "Lista"));
        String playlistSubtitle = safeValue(prefs.getString(PREF_LAST_PLAYLIST_SUBTITLE, "Playlist"));
        String playlistThumbnail = safeValue(prefs.getString(PREF_LAST_PLAYLIST_THUMBNAIL, ""));
        String accessToken = safeValue(prefs.getString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, ""));
        String normalizedPlaylistId = normalizeLikedPlaylistId(playlistId, playlistTitle, playlistSubtitle);

        PlaylistDetailFragment detailFragment = PlaylistDetailFragment.newInstance(
                normalizedPlaylistId,
                TextUtils.isEmpty(playlistTitle) ? "Lista" : playlistTitle,
                TextUtils.isEmpty(playlistSubtitle) ? "Playlist" : playlistSubtitle,
                playlistThumbnail,
                accessToken
        );

        fm.beginTransaction()
                .setReorderingAllowed(true)
                .hide(this)
                .add(R.id.fragmentContainer, detailFragment, TAG_PLAYLIST_DETAIL)
                .addToBackStack(TAG_PLAYLIST_DETAIL)
                .commit();
    }

    @NonNull
    private String normalizeLikedPlaylistId(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            @NonNull String playlistSubtitle
    ) {
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
            return playlistId;
        }

        String title = playlistTitle.toLowerCase(Locale.US);
        String subtitle = playlistSubtitle.toLowerCase(Locale.US);
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

    private void syncMiniStateWithPlaylist() {
        if (!isAdded()) {
            return;
        }
        Fragment playlist = getParentFragmentManager().findFragmentByTag("playlist_detail");
        if (playlist instanceof PlaylistDetailFragment) {
            ((PlaylistDetailFragment) playlist).syncMiniStateFromPlayer(currentIndex, isEffectivePlaying());
        }
    }

    private void updatePlayPauseIcon() {
        if (btnPlayPause == null) {
            return;
        }
        btnPlayPause.setImageResource(isPlaying
                ? R.drawable.ic_player_pause
                : R.drawable.ic_player_play);
        
        if (animatedEqPlayer != null) {
            animatedEqPlayer.setAnimating(isEffectivePlaying());
        }
        updateSeekBarLoadingState();
    }

    private void showSeekBarThumb() {
        if (sbPlaybackProgress == null) return;
        localProgressHandler.removeCallbacks(seekBarThumbHideRunnable);
        if (seekBarThumbVisible) return;
        seekBarThumbVisible = true;
        animateSeekThumb(0, 255, 120);
    }

    private void hideSeekBarThumb() {
        if (sbPlaybackProgress == null) return;
        localProgressHandler.removeCallbacks(seekBarThumbHideRunnable);
        seekBarThumbVisible = false;
        animateSeekThumb(255, 0, 300);
    }

    private void animateSeekThumb(int fromAlpha, int toAlpha, long durationMs) {
        if (sbPlaybackProgress == null) return;
        if (seekThumbAnimator != null) seekThumbAnimator.cancel();
        final int baseColor = requireContext().getColor(R.color.stitch_blue);
        final int r = android.graphics.Color.red(baseColor);
        final int g = android.graphics.Color.green(baseColor);
        final int b = android.graphics.Color.blue(baseColor);
        seekThumbAnimator = android.animation.ValueAnimator.ofInt(fromAlpha, toAlpha);
        seekThumbAnimator.setDuration(durationMs);
        seekThumbAnimator.addUpdateListener(anim -> {
            if (sbPlaybackProgress == null) return;
            int alpha = (int) anim.getAnimatedValue();
            sbPlaybackProgress.setThumbTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.argb(alpha, r, g, b)));
        });
        seekThumbAnimator.start();
    }

    private void scheduleSeekBarThumbHide() {
        localProgressHandler.removeCallbacks(seekBarThumbHideRunnable);
        localProgressHandler.postDelayed(seekBarThumbHideRunnable, SEEK_THUMB_HIDE_DELAY_MS);
    }

    private final Runnable seekBarThumbHideRunnable = this::hideSeekBarThumb;

    private void updateSeekBarLoadingState() {
        if (sbPlaybackProgress == null) return;
        boolean loading = localSourcePreparing || hasPendingStreamResolution();
        sbPlaybackProgress.setEnabled(!loading);
        if (loading) {
            hideSeekBarThumb();
            if (pbSeekBarLoading != null) pbSeekBarLoading.setVisibility(View.VISIBLE);
        } else {
            if (pbSeekBarLoading != null) pbSeekBarLoading.setVisibility(View.GONE);
            // Don't force-show thumb here — it appears only on user touch
        }
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        PlayerTrack track = tracks.get(currentIndex);
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(1, totalSeconds) * 1000L);

        if (!TextUtils.isEmpty(track.videoId)
                && TextUtils.equals(track.videoId, mediaSessionArtworkVideoId)
                && mediaSessionArtwork != null
                && !mediaSessionArtwork.isRecycled()) {
            metadataBuilder
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, mediaSessionArtwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, mediaSessionArtwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, mediaSessionArtwork);
        } else {
            Log.w(TAG, "updateMediaSessionMetadata: artwork MISSING for videoId=" + track.videoId
                    + " cachedVideoId=" + mediaSessionArtworkVideoId
                    + " artworkNull=" + (mediaSessionArtwork == null)
                    + " recycled=" + (mediaSessionArtwork != null && mediaSessionArtwork.isRecycled()));
            // Fallback to app icon if no artwork is available
            try {
                Drawable iconDrawable = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
                if (iconDrawable instanceof android.graphics.drawable.BitmapDrawable) {
                    Bitmap iconBitmap = ((android.graphics.drawable.BitmapDrawable) iconDrawable).getBitmap();
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, iconBitmap)
                                   .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBitmap)
                                   .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBitmap);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load fallback icon for media session", e);
            }
        }

        // For local tracks the art comes from the embedded picture (set as the ART bitmaps
        // above); never publish an art URI for them (the legacy album URI was unreliable).
        if (!TextUtils.isEmpty(track.imageUrl) && !LocalFilesStore.isLocalVideoId(track.videoId)) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.imageUrl);
        }
        if (!TextUtils.isEmpty(track.videoId)) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.videoId);
        }

        mediaSession.setMetadata(metadataBuilder.build());
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) {
            return;
        }

        boolean effectivelyPlaying = isEffectivePlaying();
        int state = effectivelyPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        float speed = effectivelyPlaying ? 1f : 0f;

        playbackStateBuilder.setState(state, Math.max(0, currentSeconds) * 1000L, speed);
        mediaSession.setPlaybackState(playbackStateBuilder.build());
        mediaSession.setActive(true);
    }


    private void updateMediaNotification() {
        if (mediaSession == null || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            if (persistentAppContext != null) {
                PlaybackKeepAliveService.stop(persistentAppContext);
            }
            return;
        }
        if (persistentAppContext == null) return;
        PlayerTrack track = tracks.get(currentIndex);

        // Intento de abrir la app al tocar la notificacion
        Intent openAppIntent = new Intent(persistentAppContext, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(persistentAppContext, 8701, openAppIntent, pendingFlags);

        int mediaPendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Broadcast to the in-process MediaActionReceiver instead of PendingIntent.getActivity:
        // launching the activity per tap yanked the whole app to the foreground on Android <= 12.
        // The receiver dispatches to the live activity invisibly (or relaunches it as a fallback).
        Intent prevIntent = new Intent(persistentAppContext, MediaActionReceiver.class)
                .setAction(MainActivity.ACTION_MEDIA_PREV);
        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(
                persistentAppContext, 8702, prevIntent, mediaPendingFlags);

        Intent playPauseIntent = new Intent(persistentAppContext, MediaActionReceiver.class)
                .setAction(MainActivity.ACTION_MEDIA_PLAY_PAUSE);
        PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(
                persistentAppContext, 8703, playPauseIntent, mediaPendingFlags);

        Intent nextIntent = new Intent(persistentAppContext, MediaActionReceiver.class)
                .setAction(MainActivity.ACTION_MEDIA_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(
                persistentAppContext, 8704, nextIntent, mediaPendingFlags);

        boolean effectivelyPlaying = isEffectivePlaying();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(persistentAppContext, MEDIA_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_cat)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setContentIntent(contentIntent)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(effectivelyPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPendingIntent)
                .addAction(
                        effectivelyPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        effectivelyPlaying ? "Pausar" : "Reproducir",
                        playPausePendingIntent
                )
                .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        if (mediaSessionArtwork != null && !mediaSessionArtwork.isRecycled()) {
            builder.setLargeIcon(mediaSessionArtwork);
        } else {
            Log.w(TAG, "updateMediaNotification: NO artwork for videoId=" + track.videoId
                    + " cachedId=" + mediaSessionArtworkVideoId);
        }

        android.app.Notification notification = builder.build();
        NotificationManagerCompat.from(persistentAppContext).notify(MEDIA_NOTIFICATION_ID, notification);

        // Keep process alive while playing so the MediaSession binder stays valid in background.
        // When paused, downgrade from foreground but keep notification visible (dismissable).
        if (effectivelyPlaying) {
            PlaybackKeepAliveService.start(persistentAppContext, notification);
        } else if (!isPlaying && !localSourcePreparing) {
            // Downgrade ONLY on a real user pause. During a track switch the player briefly
            // reports not-playing (preparing) — downgrading + stopSelf here raced the restart
            // and made the media notification vanish until the next track change.
            PlaybackKeepAliveService.stopForegroundKeepNotification(persistentAppContext);
        }
        // else: transient preparing state with playback intent — keep the service as is.
    }

    private void ensureMediaNotificationChannel() {
        if (persistentAppContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = persistentAppContext.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(MEDIA_NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                MEDIA_NOTIFICATION_CHANNEL_ID,
                "Reproducción",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void hydrateTracksFromArgs() {
        Bundle args = getArguments();
        if (args == null) {
            tracks.add(new PlayerTrack("", "Track", "Artist", "0:00", ""));
            currentIndex = 0;
            cacheOriginalQueueOrder();
            return;
        }

        ArrayList<String> ids = safeList(args.getStringArrayList(ARG_VIDEO_IDS));
        ArrayList<String> titles = safeList(args.getStringArrayList(ARG_TITLES));
        ArrayList<String> artists = safeList(args.getStringArrayList(ARG_ARTISTS));
        ArrayList<String> durations = safeList(args.getStringArrayList(ARG_DURATIONS));
        ArrayList<String> images = safeList(args.getStringArrayList(ARG_IMAGES));
        currentIndex = args.getInt(ARG_SELECTED_INDEX, 0);
        isPlaying = args.getBoolean(ARG_START_PLAYING, true);
        isTemporaryPlayer = args.getBoolean(ARG_IS_TEMPORARY_PLAYER, false);

        int count = Math.min(ids.size(), Math.min(titles.size(), Math.min(artists.size(), Math.min(durations.size(), images.size()))));
        for (int i = 0; i < count; i++) {
            tracks.add(new PlayerTrack(
                    safeValue(ids.get(i)),
                    safeValue(titles.get(i)),
                    safeValue(artists.get(i)),
                    safeValue(durations.get(i)),
                    safeValue(images.get(i))
            ));
        }

        if (tracks.isEmpty()) {
            tracks.add(new PlayerTrack("", "Track", "Artist", "0:00", ""));
            currentIndex = 0;
        }

        if (pendingOriginalQueueOrder != null && !pendingOriginalQueueOrder.isEmpty()) {
            originalQueueOrder.clear();
            originalQueueOrder.addAll(pendingOriginalQueueOrder);
            pendingOriginalQueueOrder = null;
        } else {
            cacheOriginalQueueOrder();
        }
        if (shuffleEnabled) {
            currentIndex = Math.max(0, Math.min(currentIndex, tracks.size() - 1));
            randomizeQueueFromCurrentTrack();
        }
    }

    private void cacheOriginalQueueOrder() {
        originalQueueOrder.clear();
        originalQueueOrder.addAll(tracks);
    }

    public void externalSetOriginalQueueOrder(@NonNull List<PlayerTrack> original) {
        if (original.isEmpty()) return;
        // If tracks are already loaded, apply directly; otherwise store for hydrateTracksFromArgs
        if (!tracks.isEmpty()) {
            originalQueueOrder.clear();
            originalQueueOrder.addAll(original);
        } else {
            pendingOriginalQueueOrder = new ArrayList<>(original);
        }
    }

    private void randomizeQueueFromCurrentTrack() {
        randomizeQueueFromCurrentTrack(null);
    }

    private void randomizeQueueFromCurrentTrack(@Nullable String avoidFirstVideoId) {
        if (tracks.size() <= 1 || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        PlayerTrack currentTrack = tracks.get(currentIndex);
        ArrayList<PlayerTrack> upcoming = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            if (i == currentIndex) {
                continue;
            }
            upcoming.add(tracks.get(i));
        }

        Collections.shuffle(upcoming, random);

        // Avoid starting the new cycle with the track that just finished
        if (!TextUtils.isEmpty(avoidFirstVideoId) && !upcoming.isEmpty()) {
            if (TextUtils.equals(upcoming.get(0).videoId, avoidFirstVideoId)) {
                // Swap it with a random position further in the list
                int swapIdx = 1 + random.nextInt(Math.max(1, upcoming.size() - 1));
                if (swapIdx < upcoming.size()) {
                    PlayerTrack tmp = upcoming.get(0);
                    upcoming.set(0, upcoming.get(swapIdx));
                    upcoming.set(swapIdx, tmp);
                }
            }
        }

        tracks.clear();
        tracks.add(currentTrack);
        tracks.addAll(upcoming);
        currentIndex = 0;
    }

    private void restoreOriginalQueueOrder() {
        if (originalQueueOrder.isEmpty()) {
            return;
        }

        String currentVideoId = externalGetCurrentVideoId();
        tracks.clear();
        tracks.addAll(originalQueueOrder);

        int restoredIndex = findTrackIndexByVideoId(currentVideoId);
        currentIndex = restoredIndex >= 0
                ? restoredIndex
                : Math.max(0, Math.min(currentIndex, tracks.size() - 1));
    }

    private int findTrackIndexByVideoId(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            return -1;
        }

        for (int i = 0; i < tracks.size(); i++) {
            if (TextUtils.equals(videoId, tracks.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    private void applyNextUpOrderToQueue() {
        if (tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }

        tracks.clear();
        tracks.addAll(nextUpTracks);
        currentIndex = 0;

        if (!shuffleEnabled) {
            cacheOriginalQueueOrder();
        }

        persistPlaybackSnapshot(false);
        
        // CRITICAL: Invalidate gapless pre-buffer and prefetch because the "next" song has changed
        invalidateNextTrackPreparations();
        
        syncMiniStateWithPlaylist();
    }

    @NonNull
    private ArrayList<String> safeList(@Nullable ArrayList<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    @NonNull
    private String safeValue(@Nullable String value) {
        return value == null ? "" : value;
    }

    private void persistPlaybackSnapshot(boolean forcePaused) {
        persistPlaybackSnapshot(forcePaused, false);
    }

    private void persistPlaybackSnapshot(boolean forcePaused, boolean synchronous) {
        if (!isAdded() || tracks.isEmpty()) {
            return;
        }

        if (!forcePaused && !synchronous) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastSnapshotDispatchedAtMs < SNAPSHOT_DEBOUNCE_MS) {
                return;
            }
            lastSnapshotDispatchedAtMs = now;
        }

        final List<PlayerTrack> tracksCopy = new ArrayList<>(tracks);
        final List<PlayerTrack> originalCopy = shuffleEnabled && !originalQueueOrder.isEmpty()
                ? new ArrayList<>(originalQueueOrder) : null;
        final int index = currentIndex;
        final int current = externalGetCurrentSeconds();
        final int total = externalGetTotalSeconds();
        final boolean effectivelyPlaying = forcePaused ? false : isEffectivePlaying();
        final Context context = requireContext().getApplicationContext();

        Runnable task = () -> {
            try {
                List<PlaybackHistoryStore.QueueTrack> queue = new ArrayList<>(tracksCopy.size());
                for (PlayerTrack track : tracksCopy) {
                    queue.add(new PlaybackHistoryStore.QueueTrack(
                            track.videoId,
                            track.title,
                            track.artist,
                            track.duration,
                            track.imageUrl
                    ));
                }

                List<PlaybackHistoryStore.QueueTrack> origQueue = null;
                if (originalCopy != null) {
                    origQueue = new ArrayList<>(originalCopy.size());
                    for (PlayerTrack track : originalCopy) {
                        origQueue.add(new PlaybackHistoryStore.QueueTrack(
                                track.videoId,
                                track.title,
                                track.artist,
                                track.duration,
                                track.imageUrl
                        ));
                    }
                }

                int safeIndex = Math.max(0, Math.min(index, Math.max(0, tracksCopy.size() - 1)));
                PlaybackHistoryStore.save(
                        context,
                        queue,
                        safeIndex,
                        Math.max(0, current),
                        Math.max(1, total),
                        effectivelyPlaying,
                        synchronous,
                        origQueue
                );

                // Also persist fallback prefs so the mini-player can restore
                // even if the snapshot gets corrupted. Uses commit() because
                // this task already runs on a background thread.
                PlayerTrack currentTrack = (safeIndex >= 0 && safeIndex < tracksCopy.size())
                        ? tracksCopy.get(safeIndex) : null;
                if (currentTrack != null && !TextUtils.isEmpty(currentTrack.videoId)) {
                    context.getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                            .edit()
                            .putString("stream_last_video_id", currentTrack.videoId)
                            .putString("stream_last_track_title", currentTrack.title != null ? currentTrack.title : "")
                            .putString("stream_last_track_artist", currentTrack.artist != null ? currentTrack.artist : "")
                            .putString("stream_last_track_duration", currentTrack.duration != null ? currentTrack.duration : "")
                            .putString("stream_last_track_image", currentTrack.imageUrl != null ? currentTrack.imageUrl : "")
                            .putBoolean("stream_last_is_playing", effectivelyPlaying)
                            .commit();
                }
                // Notify listeners that the playback snapshot was persisted
                try {
                    PlaybackEventBus.notifyPlaybackSnapshotUpdated();
                } catch (Exception e) {
                    Log.w(TAG, "Event bus notification failed", e);
                }
            } catch (Exception e) {
                Log.w(TAG, "persistPlaybackSnapshot task failed", e);
            }
        };

        if (synchronous) {
            task.run();
        } else {
            backgroundExecutor.execute(task);
        }
    }

    private void cancelNextUpReveal() {
        if (nextUpRevealRunnable != null) {
            localProgressHandler.removeCallbacks(nextUpRevealRunnable);
            nextUpRevealRunnable = null;
        }
        nextUpRevealCursor = 0;
    }

    /** Programa (con debounce) el precalentado de carátulas de la cola. Diferido para no
     *  competir con la carga de la carátula grande ni el buffering del stream recién arrancado
     *  — mismo criterio que el prefetch del siguiente track en onPrepared. */
    private void scheduleNextUpArtworkPrewarm() {
        if (nextUpPrewarmRunnable != null) {
            localProgressHandler.removeCallbacks(nextUpPrewarmRunnable);
        }
        nextUpPrewarmRunnable = () -> {
            nextUpPrewarmRunnable = null;
            prewarmNextUpArtwork();
        };
        localProgressHandler.postDelayed(nextUpPrewarmRunnable, 1500L);
    }

    /** Pre-carga en el cache de Glide las carátulas de las próximas ~10 pistas con LA MISMA
     *  receta que el bind de NextUpAdapter (transform + RGB_565 + 160px → misma clave de cache),
     *  así abrir «A continuación» pega en memoria y las miniaturas no aparecen tarde durante la
     *  animación de apertura. Las pistas locales resuelven su arte por archivo y se omiten. */
    private void prewarmNextUpArtwork() {
        if (!isAdded() || tracks.size() <= 1) {
            return;
        }
        int limit = Math.min(tracks.size() - 1, 10);
        for (int offset = 1; offset <= limit; offset++) {
            PlayerTrack t = tracks.get((currentIndex + offset) % tracks.size());
            if (t == null || LocalFilesStore.isLocalVideoId(t.videoId) || TextUtils.isEmpty(t.imageUrl)) {
                continue;
            }
            Glide.with(this)
                    .load(t.imageUrl.trim())
                    .transform(SHARED_YT_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(160, 160)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload(160, 160);
        }
    }

    private void appendNextUpBatch(int totalCount, int batchSize) {
        if (nextUpAdapter == null || tracks.size() <= 1 || batchSize <= 0) {
            return;
        }

        int start = nextUpRevealCursor;
        int end = Math.min(totalCount, start + batchSize);
        if (end <= start) {
            return;
        }

        for (int offset = start + 1; offset <= end; offset++) {
            int idx = (currentIndex + offset) % tracks.size();
            if (nextUpTracks.size() < MAX_NEXT_UP) {
                PlayerTrack t = tracks.get(idx);
                nextUpTracks.add(t);
                nextUpAdapter.getItems().add(t);
            }
        }

        nextUpAdapter.notifyItemRangeInserted(start, end - start);
        nextUpRevealCursor = end;
    }

    private void refreshNextUp() {
        cancelNextUpReveal();
        // El head de la cola cambió: dejar las carátulas de las próximas pistas calientes en el
        // cache de Glide para que la hoja «A continuación» abra con binds instantáneos.
        scheduleNextUpArtworkPrewarm();
        nextUpTracks.clear();
        if (nextUpAdapter == null) {
            return;
        }

        if (tracks.size() <= 1) {
            nextUpAdapter.setItems(nextUpTracks);
            return;
        }

        nextUpAdapter.setItems(nextUpTracks);

        int maxQueue = 10;
        int total = Math.min(tracks.size() - 1, maxQueue);
        appendNextUpBatch(total, maxQueue);
    }

    private int parseDurationSeconds(@NonNull String duration) {
        if (duration.isEmpty() || duration.contains("--")) {
            return 1;
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
            return 1;
        }
        return 1;
    }

    @NonNull
    private String formatSeconds(int seconds) {
        int safe = Math.max(0, seconds);
        int hours = safe / 3600;
        int mins = (safe % 3600) / 60;
        int secs = safe % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }

    static final class PlayerTrack {
        final String videoId;
        final String title;
        final String artist;
        final String duration;
        final String imageUrl;

        PlayerTrack(@NonNull String videoId, @NonNull String title, @NonNull String artist, @NonNull String duration, @NonNull String imageUrl) {
            this.videoId = videoId;
            this.title = title;
            // Choke point for every queue-ingestion path (newInstance args, externalReplaceQueue,
            // externalEnqueue, snapshots): callers historically packed the raw baked YTM subtitle
            // ("Artista • Álbum • 3:22 • 500 M...") into the artist slot. The player, MediaSession,
            // notification and miniplayer must only ever show the artist name.
            this.artist = SongSubtitle.artistOnly(artist, title);
            this.duration = duration;
            this.imageUrl = imageUrl;
        }
    }

    private static final class SocialStats {
        final String likeCount;
        final String dislikeCount;
        final String commentCount;
        final boolean unavailable;

        SocialStats(
                @NonNull String likeCount,
                @NonNull String dislikeCount,
                @NonNull String commentCount,
                boolean unavailable
        ) {
            this.likeCount = likeCount;
            this.dislikeCount = dislikeCount;
            this.commentCount = commentCount;
            this.unavailable = unavailable;
        }

        @NonNull
        static SocialStats loading() {
            return new SocialStats("0", "", "0", true);
        }

        @NonNull
        static SocialStats unavailable() {
            return new SocialStats("0", "", "0", true);
        }
    }

    private static final class NextUpAdapter extends RecyclerView.Adapter<NextUpAdapter.NextUpViewHolder> {
        // ✅ Cache transformation to avoid creating new object per bind
        private static final YouTubeCropTransformation SHARED_YT_CROP = new YouTubeCropTransformation();

        interface OnNextUpTap {
            void onTap(int position);
        }

        interface OnDragStart {
            void onStartDrag(@NonNull NextUpViewHolder holder);
        }

        private final OnNextUpTap onNextUpTap;
        private final OnDragStart onDragStart;

        // Single mutable list — the direct source of truth, no DiffUtil interference
        private final List<PlayerTrack> items = new ArrayList<>();

        NextUpAdapter(
                @NonNull OnNextUpTap onNextUpTap,
                @NonNull OnDragStart onDragStart
        ) {
            this.onNextUpTap = onNextUpTap;
            this.onDragStart = onDragStart;
        }

        void setItems(@NonNull List<PlayerTrack> newItems) {
            // refreshNextUp() runs on every track change / shuffle / repeat toggle. When the queue
            // is actually unchanged (same ids in the same order — e.g. a repeat-mode toggle), skip
            // the full re-bind: notifyDataSetChanged() would otherwise re-bind every visible row
            // and re-issue its Glide load for nothing.
            if (sameQueue(newItems)) return;
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        private boolean sameQueue(@NonNull List<PlayerTrack> newItems) {
            if (items.size() != newItems.size()) return false;
            for (int i = 0; i < items.size(); i++) {
                PlayerTrack a = items.get(i);
                PlayerTrack b = newItems.get(i);
                if (a == null || b == null || !TextUtils.equals(a.videoId, b.videoId)) return false;
            }
            return true;
        }

        @NonNull
        List<PlayerTrack> getItems() {
            return items;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        boolean moveItem(int fromPosition, int toPosition) {
            if (fromPosition == toPosition) {
                return false;
            }
            if (fromPosition == 0 || toPosition < 1) {
                return false; // Restriction: cannot move 'Now Playing' or above
            }
            if (fromPosition >= items.size() || toPosition >= items.size()) {
                return false;
            }
            // Mutate items directly — single source of truth, no DiffUtil interference
            PlayerTrack moved = items.remove(fromPosition);
            items.add(toPosition, moved);
            notifyItemMoved(fromPosition, toPosition);
            return true;
        }

        @NonNull
        @Override
        public NextUpViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song_next_up, parent, false);
            NextUpViewHolder holder = new NextUpViewHolder(view);
            // Set listeners ONCE per holder — position is resolved dynamically via
            // getAdapterPosition(), so there's no need to reallocate two lambdas on every bind
            // while scrolling the queue.
            holder.itemView.setOnClickListener(v -> {
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION) {
                    return;
                }
                onNextUpTap.onTap(adapterPosition);
            });
            holder.ivQueueDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    onDragStart.onStartDrag(holder);
                    return true;
                }
                return false;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull NextUpViewHolder holder, int position) {
            PlayerTrack item = items.get(position);
            if (item == null) return;
            
            holder.tvNextUpTitle.setText(item.title);
            
            // Position 0 is the currently playing track
            if (position == 0) {
                holder.tvNextUpArtist.setText("Reproduciendo ahora");
                holder.tvNextUpArtist.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.stitch_blue)); // Primary color
            } else {
                holder.tvNextUpArtist.setText(SongSubtitle.forRowParts(item.artist, item.duration));
                holder.tvNextUpArtist.setTextColor(Color.parseColor("#A0A0A0")); // Default gray
            }

            if (LocalFilesStore.isLocalVideoId(item.videoId)) {
                // Local queued track: resolve the file's own embedded cover.
                LocalArtworkResolver.loadInto(holder.ivNextUpArt, item.videoId, 160);
            } else if (!TextUtils.isEmpty(item.imageUrl)) {
                LocalArtworkResolver.detach(holder.ivNextUpArt);
                String imageUrl = item.imageUrl.trim();
                // Do NOT null the drawable before loading — the placeholder replaces any
                // recycled art the instant the request starts, and memory-cache hits bind
                // synchronously, so rows never flash empty while scrolling the queue.
                Glide.with(holder.itemView)
                    .load(imageUrl)
                    .transform(SHARED_YT_CROP)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .override(160, 160)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .placeholder(new android.graphics.drawable.ColorDrawable(
                            ContextCompat.getColor(holder.itemView.getContext(), R.color.surface_high)))
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                    .into(holder.ivNextUpArt);
            } else {
                LocalArtworkResolver.detach(holder.ivNextUpArt);
                Glide.with(holder.itemView).clear(holder.ivNextUpArt);
                holder.ivNextUpArt.setImageDrawable(null);
            }
            // Click + drag listeners are set once in onCreateViewHolder (not here) to avoid
            // reallocating them on every bind.
        }

        static final class NextUpViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivNextUpArt;
            final TextView tvNextUpTitle;
            final TextView tvNextUpArtist;
            final ImageView ivQueueDragHandle;

            NextUpViewHolder(@NonNull View itemView) {
                super(itemView);
                ivNextUpArt = itemView.findViewById(R.id.ivNextUpArt);
                tvNextUpTitle = itemView.findViewById(R.id.tvNextUpTitle);
                tvNextUpArtist = itemView.findViewById(R.id.tvNextUpArtist);
                ivQueueDragHandle = itemView.findViewById(R.id.ivQueueDragHandle);
            }
        }
    }


    private void showSaveToPlaylistSheetFromPlayer() {
        if (!isAdded() || tracks.isEmpty() || currentIndex < 0 || currentIndex >= tracks.size()) return;
        PlayerTrack current = tracks.get(currentIndex);
        if (TextUtils.isEmpty(current.videoId)) return;
        String gKey = CustomPlaylistsStore.getLastSavedPlaylistKey(requireContext());
        String gName = CustomPlaylistsStore.getLastSavedPlaylistName(requireContext());
        if (gKey != null && gName != null) {
            if (isTrackInPlaylist(requireContext(), current.videoId, gKey)) {
                showPlayerActionBar("Ya está en " + gName, "Cambiar", v -> {
                    CustomPlaylistsStore.clearLastSavedPlaylist(requireContext());
                    showSaveToPlaylistSheet(current, null);
                });
            } else {
                addTrackToPlaylistByKey(gKey, current);
                showSavedInPlaylistBarPlayer(current, gKey, gName);
            }
            return;
        }
        showSaveToPlaylistSheet(current, null);
    }

    private void showSaveToPlaylistSheet(@NonNull PlayerTrack track, @Nullable String previousPlaylistKey) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        BottomSheetDialog saveDialog = new BottomSheetDialog(ctx);
        View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_save_to_playlist, null);
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
                showSavedInPlaylistBarPlayer(track, addedKey, addedName);
            } else if (removed) {
                showRemovedFromPlaylistBarPlayer();
            }
        });

        android.widget.LinearLayout llList = sheet.findViewById(R.id.llSavePlaylistList);
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

        // Favoritos
        java.util.List<FavoritesPlaylistStore.FavoriteTrack> favs = FavoritesPlaylistStore.loadFavorites(ctx);

        {
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            android.widget.TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            android.widget.TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
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
            android.widget.TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            android.widget.TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText("Música que te gustó");
            tvCount.setText(likedCached != null ? likedCached.subtitle : "Playlist");
            ivThumb.setBackgroundResource(R.drawable.bg_music_liked_gradient);
            ivThumb.setImageResource(R.drawable.ic_thumb_up_liked);
            ivThumb.setScaleType(ImageView.ScaleType.CENTER);
            ivThumb.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            // Checked = same union the like icon uses (server cache OR local mirror), so the
            // sheet row and the heart can never disagree about liked membership.
            boolean isIn = isTrackInLikedMusic(track.videoId);
            if (ivCheck != null) ivCheck.setVisibility(isIn ? View.VISIBLE : View.GONE);
            final boolean[] checked = {isIn};
            row.setOnClickListener(v -> {
                if (checked[0]) {
                    // Remove from BOTH stores (union semantics — mirror-only removal would leave
                    // server-cached likes permanently "liked").
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
                refreshLikeIconState();
            });
            // "Música que te gustó" always first, Favoritos second.
            llList.addView(row, 0);
        }

        // Custom playlists
        java.util.List<String> customNames = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(ctx);
        for (String name : customNames) {
            String playlistKey = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name;
            java.util.List<FavoritesPlaylistStore.FavoriteTrack> customTracks =
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            java.util.List<String> urls = new ArrayList<>();
            for (FavoritesPlaylistStore.FavoriteTrack t : customTracks) {
                if (!TextUtils.isEmpty(t.imageUrl)) {
                    if (!urls.contains(t.imageUrl)) urls.add(t.imageUrl);
                    if (urls.size() >= 4) break;
                }
            }

            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            android.widget.TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            android.widget.TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(name);
            tvCount.setText(customTracks.size() + " pistas");
            if (urls.size() >= 4) {
                PlaylistGridArtLoader.load(ivThumb, urls, thumbSizePx);
            } else if (!urls.isEmpty()) {
                Glide.with(this).load(urls.get(0))
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .override(200, 200).centerCrop().into(ivThumb);
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
        java.util.List<YouTubeMusicService.TrackResult> ytPlaylists = MusicPlayerFragment.getYouTubeLibraryPlaylists();
        for (YouTubeMusicService.TrackResult ytItem : ytPlaylists) {
            String ytPlaylistId = ytItem.contentId == null ? "" : ytItem.contentId.trim();
            if (ytPlaylistId.isEmpty()) continue;
            String ytMirrorKey = CustomPlaylistsStore.YT_MIRROR_PREFIX + ytPlaylistId;
            String ytFallbackThumb = ytItem.thumbnailUrl == null ? "" : ytItem.thumbnailUrl.trim();

            View row = LayoutInflater.from(ctx).inflate(R.layout.item_save_playlist_row, llList, false);
            ImageView ivThumb = row.findViewById(R.id.ivSavePlaylistThumb);
            android.widget.TextView tvName = row.findViewById(R.id.tvSavePlaylistName);
            android.widget.TextView tvCount = row.findViewById(R.id.tvSavePlaylistCount);
            ImageView ivCheck = row.findViewById(R.id.ivSaveCheck);
            tvName.setText(ytItem.title == null ? "" : ytItem.title);
            tvCount.setText(ytItem.subtitle == null ? "Playlist" : ytItem.subtitle);
            // Portada OFICIAL de YT (registrada al parsear home/biblioteca): manda sobre el
            // collage 2x2 sintetizado, igual que en el resto de superficies.
            if (!ytFallbackThumb.isEmpty()) {
                OfficialCoverStore.save(ctx, ytPlaylistId, ytFallbackThumb);
            }
            String ytOfficial = OfficialCoverStore.get(ctx, ytPlaylistId);
            if (TextUtils.isEmpty(ytOfficial)) ytOfficial = ytFallbackThumb;
            if (!TextUtils.isEmpty(ytOfficial)) {
                Glide.with(this).load(ytOfficial)
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .override(200, 200).centerCrop().into(ivThumb);
            } else {
                java.util.List<String> ytUrls = loadPersistedGridUrls(ctx, ytPlaylistId);
                if (ytUrls.size() < 4) {
                    ytUrls = new ArrayList<>();
                    java.util.List<FavoritesPlaylistStore.FavoriteTrack> ytMirrorTracks =
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
                    Glide.with(this).load(ytUrls.get(0))
                            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                            .override(200, 200).centerCrop().into(ivThumb);
                }
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
        saveDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        sheet.setAlpha(0f);
        saveDialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                View sheetParent = (View) sheet.getParent();
                if (sheetParent != null) sheetParent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                bottomSheet.setBackgroundResource(android.R.color.transparent);
            }
            sheet.post(() -> sheet.animate().alpha(1f).setDuration(150L).start());
        });
        saveDialog.show();
    }

    @NonNull
    private static java.util.List<String> loadPersistedGridUrls(@NonNull Context ctx, @NonNull String playlistId) {
        try {
            String raw = ctx.getApplicationContext()
                    .getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE)
                    .getString("playlist_grid_urls_" + playlistId, "");
            if (TextUtils.isEmpty(raw)) return java.util.Collections.emptyList();
            String[] parts = raw.split("\\n");
            java.util.List<String> result = new ArrayList<>(parts.length);
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
            java.util.List<FavoritesPlaylistStore.FavoriteTrack> tracks =
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(ctx, name);
            for (FavoritesPlaylistStore.FavoriteTrack t : tracks) {
                if (videoId.equals(t.videoId)) return true;
            }
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            String pid = playlistKey.substring(CustomPlaylistsStore.YT_MIRROR_PREFIX.length());
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(pid)) {
                return isTrackInLikedMusic(videoId);
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

    private void addTrackToPlaylistByKey(@NonNull String playlistKey, @NonNull PlayerTrack track) {
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
        maybeEnqueueOfflineDownloadForTrack(playlistKey, track.videoId, title, artist, duration);
        refreshLikeIconState();
    }

    private void maybeEnqueueOfflineDownloadForTrack(@NonNull String playlistKey, @NonNull String videoId,
                                                      @NonNull String title, @NonNull String artist, @NonNull String duration) {
        if (!isAdded() || TextUtils.isEmpty(videoId)) return;
        // Resolve actual playlistId (for custom playlists the key IS the id used in prefs)
        String playlistId = playlistKey;
        android.content.SharedPreferences cachePrefs = requireContext().getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE);
        boolean offlineAuto = cachePrefs.getBoolean("playlist_offline_auto_" + playlistId, false);
        if (!offlineAuto) return;
        if (OfflineAudioStore.hasOfflineAudio(requireContext(), videoId)) return;
        try {
            androidx.work.Data inputData = new androidx.work.Data.Builder()
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
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, android.content.Context.MODE_PRIVATE);
            boolean allowMobile = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
            androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(allowMobile ? androidx.work.NetworkType.CONNECTED : androidx.work.NetworkType.UNMETERED)
                    .build();
            androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    // Short linear backoff — shared manual chain, no stuck heads (see above).
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                    .addTag("offline_add_track_" + videoId)
                    .build();
            androidx.work.WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                    AppConstants.OFFLINE_MANUAL_TRACK_QUEUE,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request);
        } catch (Exception e) {
            Log.w(TAG, "Failed to enqueue offline download", e);
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
        refreshLikeIconState();
    }

    private void showSavedInPlaylistBarPlayer(@NonNull PlayerTrack track, @NonNull String playlistKey, @NonNull String playlistName) {
        showPlayerActionBar("Se guardó en " + playlistName, "Cambiar", v -> {
            CustomPlaylistsStore.clearLastSavedPlaylist(requireContext());
            showSaveToPlaylistSheet(track, playlistKey);
        });
    }

    private void showRemovedFromPlaylistBarPlayer() {
        showPlayerActionBar("Se eliminó correctamente", "Cambiar", v -> {
            showSaveToPlaylistSheetFromPlayer();
        });
    }

    /** Toast host inside the full player surface (renders above the footer triggers). */
    @Nullable
    private android.view.ViewGroup getPlayerToastRoot() {
        return (getView() instanceof android.view.ViewGroup) ? (android.view.ViewGroup) getView() : null;
    }

    private int playerToastBottomMarginPx() {
        float density = getResources().getDisplayMetrics().density;
        return (int) (56 * density);
    }

    private void showPlayerActionBar(@NonNull String message, @NonNull String actionLabel, @NonNull View.OnClickListener actionClick) {
        if (!isAdded() || getView() == null) return;
        AppSnackbar.showInView(getPlayerToastRoot(), message, actionLabel,
                () -> actionClick.onClick(getView()), playerToastBottomMarginPx(), 4000L);
    }

    private void showQueueBottomSheet() {
        if (!isAdded()) return;

        // Diálogo REUTILIZADO: se construye una sola vez por vista (buildQueueBottomSheet);
        // las aperturas siguientes solo reponen datos. Construir + inflar en cada tap era el
        // grueso de la latencia de apertura.
        if (queueSheetDialog == null) {
            buildQueueBottomSheet();
        }
        if (queueSheetDialog == null || nextUpAdapter == null) return;

        // Queue computation is O(n) on an in-memory ArrayList — no need for a background thread
        List<PlayerTrack> computedQueue = new ArrayList<>();
        if (!tracks.isEmpty() && currentIndex >= 0 && currentIndex < tracks.size()) {
            computedQueue.add(tracks.get(currentIndex));
        }
        for (int i = 1; i < tracks.size(); i++) {
            int idx = (currentIndex + i) % tracks.size();
            computedQueue.add(tracks.get(idx));
        }
        final int totalCount = Math.min(computedQueue.size(), MAX_NEXT_UP);
        final int initialCount = Math.min(totalCount, QUEUE_SHEET_INITIAL_ROWS);

        // Poblar ANTES de show(): el primer layout pass ya bindea los datos finales — sin un
        // segundo notifyDataSetChanged re-layouteando en plena animación de expansión. El
        // reset de estado pre-show (scroll a 0 + items frescos) es lo que hace innecesario el
        // viejo alpha-0 + fade de 150ms del «ghost flash» de la segunda apertura.
        cancelNextUpReveal();
        nextUpTracks.clear();
        nextUpTracks.addAll(computedQueue.subList(0, initialCount));
        nextUpAdapter.setItems(nextUpTracks);
        if (rvQueueSheet != null) {
            rvQueueSheet.scrollToPosition(0);
        }
        boolean queueEmpty = nextUpTracks.isEmpty();
        if (rvQueueSheet != null) {
            rvQueueSheet.setVisibility(queueEmpty ? View.GONE : View.VISIBLE);
        }
        if (tvEmptyQueueSheet != null) {
            tvEmptyQueueSheet.setVisibility(queueEmpty ? View.VISIBLE : View.GONE);
        }

        // Relleno escalonado: solo lo visible (~12 filas) se bindea antes del show(); el resto
        // (hasta MAX_NEXT_UP) entra vía notifyItemRangeInserted cuando la animación ya asentó.
        if (totalCount > initialCount) {
            final List<PlayerTrack> rest = new ArrayList<>(computedQueue.subList(initialCount, totalCount));
            nextUpRevealRunnable = () -> {
                nextUpRevealRunnable = null;
                if (nextUpAdapter == null || queueSheetDialog == null || !queueSheetDialog.isShowing()) {
                    return;
                }
                // La cola pudo cambiar mientras tanto (tap, cambio de pista); solo append si el
                // head sigue intacto — un refreshNextUp intermedio ya canceló este runnable.
                if (nextUpTracks.size() != initialCount || nextUpAdapter.getItems().size() != initialCount) {
                    return;
                }
                nextUpTracks.addAll(rest);
                nextUpAdapter.getItems().addAll(rest);
                nextUpAdapter.notifyItemRangeInserted(initialCount, rest.size());
            };
            localProgressHandler.postDelayed(nextUpRevealRunnable, 350L);
        }

        queueSheetDialog.show();
    }

    /** Construye (una vez por vista) la hoja «A continuación»: diálogo, layout, adapter y drag.
     *  El contenido ya no se oculta con alpha-0 + fade: ese workaround del «ghost flash» de la
     *  segunda apertura solo era necesario porque cada apertura inflaba un diálogo nuevo. */
    private void buildQueueBottomSheet() {
        if (!isAdded()) return;

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        View bsv = getLayoutInflater().inflate(R.layout.bottom_sheet_player_queue, null);
        bottomSheetDialog.setContentView(bsv);

        RecyclerView rvQueue = bsv.findViewById(R.id.rvQueue);
        tvEmptyQueueSheet = bsv.findViewById(R.id.tvEmptyQueue);
        ProgressBar pbQueueLoading = bsv.findViewById(R.id.pbQueueLoading);
        // Los datos se poblan antes de show() — el spinner de carga ya no tiene ventana.
        pbQueueLoading.setVisibility(View.GONE);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        rvQueue.setLayoutManager(layoutManager);
        // Filas de alto fijo en un RecyclerView match_parent: holders extra en cache para que
        // el primer scroll no vuelva a inflar.
        rvQueue.setHasFixedSize(true);
        rvQueue.setItemViewCacheSize(12);
        rvQueueSheet = rvQueue;

        nextUpAdapter = new NextUpAdapter(position -> {
            if (position == 0) {
                return;
            }
            if (position >= 0 && position < nextUpTracks.size()) {
                PlayerTrack tapped = nextUpTracks.get(position);
                int realIdx = -1;
                for (int j = 0; j < tracks.size(); j++) {
                    if (tracks.get(j) == tapped) {
                        realIdx = j;
                        break;
                    }
                }

                if (realIdx != -1) {
                    currentIndex = realIdx;
                    isPlaying = true;
                    currentSeconds = 0;
                    bindCurrentTrack(false);
                    playCurrentTrack();
                    if (queueSheetDialog != null) {
                        queueSheetDialog.dismiss();
                    }
                }
            }
        }, holder -> {
            if (nextUpItemTouchHelper != null) {
                nextUpItemTouchHelper.startDrag(holder);
            }
        });

        rvQueue.setAdapter(nextUpAdapter);

        bottomSheetDialog.setOnShowListener(dialog -> {
            BottomSheetDialog d = (BottomSheetDialog) dialog;
            View bottomSheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setSkipCollapsed(true);
                try {
                    behavior.setHideFriction(0.5f);
                } catch (Throwable ignored) {
                }
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        ItemTouchHelper.SimpleCallback dragCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0
        ) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getBindingAdapterPosition() == 0) return 0; // Now Playing: no drag
                return super.getMovementFlags(recyclerView, viewHolder);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                return nextUpAdapter.moveItem(from, to);
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public int interpolateOutOfBoundsScroll(@NonNull RecyclerView recyclerView, int viewSize, int viewSizeOutOfBounds, int totalSize, long msSinceStartScroll) {
                int direction = (int) Math.signum(viewSizeOutOfBounds);
                int baseValue = super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, Math.max(msSinceStartScroll, 2000L));
                return baseValue * 3;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // Sync nextUpTracks from the drag-mutated list before applying to queue
                nextUpTracks.clear();
                nextUpTracks.addAll(nextUpAdapter.getItems());
                applyNextUpOrderToQueue();
            }
        };

        nextUpItemTouchHelper = new ItemTouchHelper(dragCallback);
        nextUpItemTouchHelper.attachToRecyclerView(rvQueue);

        // NOTA: sin onDismissListener que anule adapter/helper — el diálogo se reutiliza tal
        // cual en la próxima apertura; todo se descarta junto en onDestroyView.
        queueSheetDialog = bottomSheetDialog;
    }
    private void setupSwipeToDismiss(View root) {
        if (!(root instanceof SwipeInterceptLayout)) return;
        final View scrollView = null; // No scrollable content — always allow swipe dismiss

        swipeDismissGestureActive = false;
        swipeDismissAnimationRunning = false;
        swipeDismissTouchSlopPx = ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        swipeDismissMinDistancePx = Math.max(dpToPx(96),
                Math.round(root.getResources().getDisplayMetrics().heightPixels * 0.12f));

        final float[] initialTouchY = {0f};
        final float[] dragStartY = {0f};
        final float[] lastTouchY = {0f};
        final long[] initialTouchTimeMs = {0L};
        final boolean[] isDragging = {false};
        final boolean[] canStart = {false};

        ((SwipeInterceptLayout) root).setSwipeInterceptCallback(new SwipeInterceptLayout.SwipeInterceptCallback() {
            @Override
            public boolean onInterceptSwipe(MotionEvent e) {
                return handleSwipeDismissTouch(root, scrollView, e,
                        initialTouchY, dragStartY, lastTouchY, initialTouchTimeMs, isDragging, canStart);
            }
            @Override
            public boolean onSwipeTouch(MotionEvent e) {
                if (swipeDismissAnimationRunning) return isDragging[0];
                return handleSwipeDismissTouch(root, scrollView, e,
                        initialTouchY, dragStartY, lastTouchY, initialTouchTimeMs, isDragging, canStart);
            }
        });
    }

    private boolean handleSwipeDismissTouch(
            View root, View scrollView, MotionEvent event,
            float[] initialTouchY, float[] dragStartY, float[] lastTouchY,
            long[] initialTouchTimeMs, boolean[] isDragging, boolean[] canStart) {

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                playerEnterAnimationRunning = false;
                root.animate().cancel();
                // CRITICAL: clear the dismiss-animation lock on every new touch. The dismiss/settle
                // animations reset this flag in withEndAction(), but withEndAction does NOT run when
                // the animation is cancelled — and the line above cancels it. Without this reset the
                // flag stayed true forever, so onSwipeTouch() returned early on every later gesture:
                // the sheet only nudged down a few px and froze on release, needing an app restart.
                // Clearing it here makes every touch self-heal that state.
                swipeDismissAnimationRunning = false;
                initialTouchY[0] = event.getRawY();
                dragStartY[0] = initialTouchY[0];
                lastTouchY[0] = initialTouchY[0];
                initialTouchTimeMs[0] = event.getEventTime();
                isDragging[0] = false;
                swipeDismissGestureActive = false;
                canStart[0] = scrollView == null || scrollView.getScrollY() <= swipeDismissTouchSlopPx;
                return false;

            case MotionEvent.ACTION_MOVE:
                float currentY = event.getRawY();
                float totalDeltaY = currentY - initialTouchY[0];
                lastTouchY[0] = currentY;

                if (!canStart[0] && (scrollView == null || scrollView.getScrollY() <= swipeDismissTouchSlopPx) && totalDeltaY > 0f) {
                    canStart[0] = true;
                    dragStartY[0] = currentY;
                    totalDeltaY = 0f;
                }

                if (!isDragging[0] && canStart[0] && totalDeltaY > swipeDismissTouchSlopPx) {
                    isDragging[0] = true;
                    swipeDismissGestureActive = true;
                    dragStartY[0] = currentY - swipeDismissTouchSlopPx;
                }

                if (isDragging[0]) {
                    float dragDeltaY = currentY - dragStartY[0];
                    root.setTranslationY(Math.max(0f, dragDeltaY * 0.92f));
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging[0]) {
                    float finalDragDeltaY = Math.max(0f, lastTouchY[0] - dragStartY[0]);
                    long elapsedMs = Math.max(1L, event.getEventTime() - initialTouchTimeMs[0]);
                    float velocityY = finalDragDeltaY / elapsedMs * 1000f;
                    boolean shouldDismiss = event.getActionMasked() == MotionEvent.ACTION_UP
                            && (finalDragDeltaY >= swipeDismissMinDistancePx
                                || finalDragDeltaY > root.getHeight() * 0.18f
                                || (finalDragDeltaY > dpToPx(48) && velocityY > dpToPx(900)));

                    isDragging[0] = false;
                    swipeDismissGestureActive = false;

                    if (shouldDismiss) {
                        swipeDismissAnimationRunning = true;
                        root.animate().cancel();
                        root.animate()
                                .translationY(root.getHeight())
                                .setDuration(250)
                                .withEndAction(() -> {
                                    swipeDismissAnimationRunning = false;
                                    if (isAdded() && getActivity() instanceof MainActivity) {
                                        ((MainActivity) getActivity()).externalClosePlayerImmediately();
                                    }
                                })
                                .start();
                    } else {
                        root.animate().cancel();
                        root.animate()
                                .translationY(0)
                                .setDuration(200)
                                .withEndAction(() -> {
                                    swipeDismissGestureActive = false;
                                    swipeDismissAnimationRunning = false;
                                })
                                .start();
                    }
                    return true;
                }
                // Not dragging. If a cancelled enter/dismiss animation left the view offset (e.g. the
                // user tapped mid-animation), settle it back to rest so it can never sit visually stuck
                // partway down. A no-op when already at rest (guard below).
                if (root.getTranslationY() > 0.5f) {
                    root.animate().cancel();
                    root.animate().translationY(0f).setDuration(180L).start();
                }
                return false;
        }
        return false;
    }


    private void releaseLocalExoMediaPlayer() {
        cancelOfflineCrossfade();
        if (localExoMediaPlayer == null) {
            Log.d(TAG, "[PLAYBACK_DBG] releaseLocalExoMediaPlayer: already null");
            return;
        }
        Log.d(TAG, "[PLAYBACK_DBG] releaseLocalExoMediaPlayer: releasing player=" + localExoMediaPlayer.hashCode());
        // Release video surface from router
        videoRouter.onPlayerReleased();
        try {
            // Diferido: el release pesado nunca bloquea el arranque de la pista siguiente.
            localExoMediaPlayer.releaseAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to release local player", e);
        }
        localExoMediaPlayer = null;
        usingOfflineSource = false;
    }

    @Nullable
    private Context getPlaybackAppContext() {
        Context currentContext = getContext();
        if (currentContext != null) {
            persistentAppContext = currentContext.getApplicationContext();
        }
        return persistentAppContext;
    }

    private void clearMediaNotificationArtwork() {
        mediaSessionArtwork = null;
        mediaSessionArtworkVideoId = "";
    }

    private void cacheMediaNotificationArtwork(@NonNull String videoId, @NonNull Bitmap source) {
        if (source.isRecycled()) {
            Log.w(TAG, "cacheMediaNotificationArtwork: bitmap already recycled videoId=" + videoId);
            return;
        }
        mediaSessionArtwork = scaleArtworkBitmap(source, MEDIA_SESSION_ARTWORK_MAX_SIZE_PX);
        mediaSessionArtworkVideoId = videoId;
    }

    @NonNull
    private Bitmap scaleArtworkBitmap(@NonNull Bitmap source, int maxEdgePx) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        int largestEdge = Math.max(width, height);
        if (largestEdge <= Math.max(1, maxEdgePx)) {
            return source;
        }

        float scale = maxEdgePx / (float) largestEdge;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        try {
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        } catch (Throwable ignored) {
            return source;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }

}

