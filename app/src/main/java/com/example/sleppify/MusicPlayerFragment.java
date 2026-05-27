package com.example.sleppify;
import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.example.sleppify.utils.YouTubeCropTransformation;
import com.example.sleppify.utils.YouTubeImageProcessor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.content.res.ColorStateList;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class MusicPlayerFragment extends Fragment implements PlaybackEventBus.Listener {
    private static final long LIBRARY_CACHE_TTL_MS = 20 * 60 * 1000L;
    private static final long LIBRARY_REFRESH_MIN_INTERVAL_MS = 90 * 1000L;
    private static final long TOKEN_CACHE_TTL_MS = 45 * 60 * 1000L;
    private static final String PREFS_PLAYER_STATE = "player_state";
    private static final String PREF_LAST_PLAYLIST_ID = "stream_last_playlist_id";
    private static final String PREF_LAST_PLAYLIST_TITLE = "stream_last_playlist_title";
    private static final String PREF_LAST_PLAYLIST_SUBTITLE = "stream_last_playlist_subtitle";
    private static final String PREF_LAST_PLAYLIST_THUMBNAIL = "stream_last_playlist_thumbnail";
    private static final String PREF_LAST_TRACK_IMAGE = "stream_last_track_image";
    private static final String PREF_LAST_TRACK_DURATION = "stream_last_track_duration";
    private static final String PREF_LAST_IS_PLAYING = "stream_last_is_playing";
    private static final String PREF_LAST_STREAM_SCREEN = "stream_last_screen";
    private static final String PREF_LAST_YOUTUBE_ACCESS_TOKEN = "stream_last_youtube_access_token";
    private static final String PREF_LAST_YOUTUBE_ACCESS_TOKEN_UPDATED_AT = "stream_last_youtube_access_token_updated_at";
    private static final String PREF_LAST_YOUTUBE_WEB_COOKIE = "stream_last_youtube_web_cookie";
    private static final String PREF_LAST_STREAMING_OAUTH_ACCOUNT_EMAIL = "stream_last_oauth_account_email";
    private static final String STREAM_SCREEN_LIBRARY = "library";
    private static final String STREAM_SCREEN_PLAYLIST_DETAIL = "playlist_detail";
    private static final long LIBRARY_INLINE_SEARCH_DEBOUNCE_MS = 320L;
    private static final String TAG_MODULE_MUSIC = "module_music";
    private static final String TAG_STREAMING = "SleppifyStreaming";
    private static final String PREFS_STREAMING_CACHE = "streaming_cache";
    private static final String PREF_LIBRARY_UPDATED_AT_PREFIX = "library_updated_at_";
    private static final String PREF_LIBRARY_DATA_PREFIX = "library_data_";
    private static final String PREF_TRACKS_UPDATED_AT_PREFIX = "playlist_tracks_updated_at_";
    private static final String PREF_TRACKS_DATA_PREFIX = "playlist_tracks_data_";
    private static final String PREF_TRACKS_FULL_CACHE_PREFIX = "playlist_tracks_cache_full_";
    private static final String PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX = "playlist_offline_complete_";
    private static final String PREF_PLAYLIST_OFFLINE_AUTO_PREFIX = "playlist_offline_auto_";
    private static final String PREF_PLAYLIST_GRID_URLS_PREFIX = "playlist_grid_urls_";
    private static final String PREF_LAST_STREAMING_ACCOUNT_KEY = "stream_last_library_account_key";
    private static final String OFFLINE_DOWNLOAD_UNIQUE_PREFIX = "offline_playlist_";
    private static final String OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME = "offline_playlist_queue";
    private static final String OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME = "offline_manual_track_queue";
    private static final int OFFLINE_AUTO_BATCH_TARGET_TRACKS = 3;
    private static final Set<String> offlineSyncRecentlyProcessedPlaylistIds = Collections.synchronizedSet(new HashSet<>());
    private static boolean isOfflineSyncInFlight = false;
    private static final long OFFLINE_TRACKS_PREFETCH_STALE_MS = 7L * 24 * 60 * 60 * 1000L;
    private static final int OFFLINE_TRACKS_PREFETCH_LIMIT = 12;
    private static final int OFFLINE_TRACKS_PREFETCH_PER_PLAYLIST_LIMIT = 250;
    private static final int LIBRARY_PLAYLIST_FETCH_LIMIT = 150;
    private static final int LIBRARY_INLINE_SEARCH_MAX_RESULTS = 220;
    private static final int LIBRARY_INLINE_ONLINE_MIN_QUERY_CHARS = 3;
    private static final List<YouTubeMusicService.TrackResult> LIBRARY_CACHE = new ArrayList<>();
    private static final ExecutorService STREAMING_WARMUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static String libraryCacheAccountKey = "";
    private static long libraryCacheUpdatedAtMs;
    private static String tokenCacheAccountKey = "";
    private static String cachedYoutubeAccessToken = "";
    private static long tokenCacheUpdatedAtMs;
    private static long lastLibrarySyncAtMs;
    private static boolean streamingWarmupInFlight;
    // Cached display library result to avoid redundant disk I/O on every navigation
    private List<YouTubeMusicService.TrackResult> cachedDisplayLibrary = null;
    private boolean displayLibraryDirty = true;
    // Cached network state to avoid repeated ConnectivityManager queries
    private boolean cachedNetworkAvailable;
    private long cachedNetworkAvailableAtMs;
    static void prewarmStreamingAfterAppSignIn(@NonNull Context context, @Nullable String primaryAppEmail) {
        String accountKey = normalizeAccountEmail(primaryAppEmail);
        if (TextUtils.isEmpty(accountKey)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(appContext);
        if (account == null) {
            return;
        }
        String signedEmail = normalizeAccountEmail(account.getEmail());
        if (TextUtils.isEmpty(signedEmail) || !accountKey.equals(signedEmail)) {
            return;
        }
        YouTubeMusicService warmupService = new YouTubeMusicService();
        Scope ytScope = new Scope(warmupService.getYoutubeReadonlyScope());
        if (!GoogleSignIn.hasPermissions(account, ytScope)) {
            return;
        }
        synchronized (fragmentLock) {
            if (streamingWarmupInFlight) {
                return;
            }
            boolean tokenReady = !TextUtils.isEmpty(cachedYoutubeAccessToken)
                    && accountKey.equals(tokenCacheAccountKey)
                    && (System.currentTimeMillis() - tokenCacheUpdatedAtMs) < TOKEN_CACHE_TTL_MS;
            boolean libraryReady = !LIBRARY_CACHE.isEmpty()
                    && accountKey.equals(libraryCacheAccountKey)
                    && (System.currentTimeMillis() - libraryCacheUpdatedAtMs) < LIBRARY_CACHE_TTL_MS;
            if (tokenReady && libraryReady) {
                return;
            }
            streamingWarmupInFlight = true;
        }
        Account accountRef = account.getAccount();
        if (accountRef == null) {
            accountRef = new Account(accountKey, "com.google");
        }
        final Account resolvedAccount = accountRef;
        STREAMING_WARMUP_EXECUTOR.execute(() -> {
            try {
                String scope = "oauth2:" + warmupService.getYoutubeReadonlyScope();
                String token = GoogleAuthUtil.getToken(appContext, resolvedAccount, scope);
                if (TextUtils.isEmpty(token)) {
                    finishStreamingWarmup();
                    return;
                }
                synchronized (fragmentLock) {
                    tokenCacheAccountKey = accountKey;
                    cachedYoutubeAccessToken = token;
                    tokenCacheUpdatedAtMs = System.currentTimeMillis();
                }
                appContext
                        .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, token)
                    .putLong(PREF_LAST_YOUTUBE_ACCESS_TOKEN_UPDATED_AT, System.currentTimeMillis())
                        .apply();
                SharedPreferences cachePrefs = appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE);
                cachePrefs.edit().putString(PREF_LAST_STREAMING_ACCOUNT_KEY, accountKey).apply();
                String warmToken = token;
                warmupService.fetchMyPlaylists(warmToken, 25, new YouTubeMusicService.PlaylistsCallback() {
                    @Override
                    public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistResult> playlists) {
                        List<YouTubeMusicService.TrackResult> mapped = mapPlaylistsToLibraryTracks(playlists);
                        long updatedAt = System.currentTimeMillis();
                        synchronized (fragmentLock) {
                            LIBRARY_CACHE.clear();
                            LIBRARY_CACHE.addAll(new ArrayList<>(mapped));
                            libraryCacheAccountKey = accountKey;
                            libraryCacheUpdatedAtMs = updatedAt;
                        }
                        try {
                            JSONArray array = new JSONArray();
                            for (YouTubeMusicService.TrackResult item : mapped) {
                                JSONObject obj = new JSONObject();
                                obj.put("resultType", item.resultType);
                                obj.put("contentId", item.contentId);
                                obj.put("title", item.title);
                                obj.put("subtitle", item.subtitle);
                                obj.put("thumbnailUrl", item.thumbnailUrl);
                                array.put(obj);
                            }
                            cachePrefs.edit()
                                    .putLong(PREF_LIBRARY_UPDATED_AT_PREFIX + accountKey, updatedAt)
                                    .putString(PREF_LIBRARY_DATA_PREFIX + accountKey, array.toString())
                                    .putString(PREF_LAST_STREAMING_ACCOUNT_KEY, accountKey)
                                    .apply();
                        } catch (Exception ignored) {
                        }
                        finishStreamingWarmup();
                    }
                    @Override
                    public void onError(@NonNull String error) {
                        finishStreamingWarmup();
                    }
                });
            } catch (UserRecoverableAuthException ignored) {
                // No abrimos UI aqui; este warmup es solo en segundo plano.
                finishStreamingWarmup();
            } catch (IOException | GoogleAuthException e) {
                finishStreamingWarmup();
            }
        });
    }
    private static void finishStreamingWarmup() {
        synchronized (fragmentLock) {
            streamingWarmupInFlight = false;
        }
    }
    @NonNull
    private static String normalizeAccountEmail(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US);
    }
    @NonNull
    private static List<YouTubeMusicService.TrackResult> mapPlaylistsToLibraryTracks(
            @NonNull List<YouTubeMusicService.PlaylistResult> playlists
    ) {
        List<YouTubeMusicService.TrackResult> mapped = new ArrayList<>();
        Set<String> seenContentIds = new HashSet<>();
        for (YouTubeMusicService.PlaylistResult playlist : playlists) {
            String title = playlist.title;
            String subtitle;
            String rawId = playlist.playlistId == null ? "" : playlist.playlistId.trim();
            boolean isLikedCollection = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(rawId)
                    || "LL".equals(rawId)
                    || "LM".equals(rawId)
                    || rawId.startsWith("VLLL");
            String playlistContentId = isLikedCollection
                    ? YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
                    : playlist.playlistId;
            if (!seenContentIds.add(playlistContentId)) {
                continue;
            }
            if (isLikedCollection) {
                title = "M\u00fasica que te gust\u00f3";
                subtitle = playlist.itemCount > 0
                        ? "Playlist autogenerada \u2022 " + playlist.itemCount + " canciones"
                        : "Playlist autogenerada";
            } else if (playlist.itemCount > 0) {
                subtitle = "Playlist \u2022 " + playlist.itemCount + " canciones";
            } else {
                subtitle = "Playlist";
            }
            mapped.add(new YouTubeMusicService.TrackResult(
                    "playlist",
                    playlistContentId,
                    title,
                    subtitle,
                    playlist.thumbnailUrl
            ));
        }
        return mapped;
    }
    private static boolean isLikedPlaylistStyle(@NonNull YouTubeMusicService.TrackResult item) {
        String cid = item.contentId == null ? "" : item.contentId.trim();
        return YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(cid)
                || "LL".equals(cid)
                || "LM".equals(cid)
                || cid.startsWith("VLLL");
    }
    /**
     * Builds the display list for the library: [Favoritos] + [Custom] + [YouTube playlists].
     * Does NOT mutate {@link #libraryTracks} â€” produces a fresh list each time.
     */
    void markDisplayLibraryDirty() {
        displayLibraryDirty = true;
    }
    @NonNull
    private List<YouTubeMusicService.TrackResult> buildDisplayLibrary() {
        if (!displayLibraryDirty && cachedDisplayLibrary != null) {
            return new ArrayList<>(cachedDisplayLibrary);
        }
        List<YouTubeMusicService.TrackResult> display = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        List<String> pinnedIds = isAdded()
                ? RadioHistoryStore.INSTANCE.getPinnedPlaylistIds(requireContext())
                : new ArrayList<>();
        if (isAdded()) {
            // 1. Música que te gustó (Liked) — always first
            YouTubeMusicService.TrackResult likedTrack = null;
            for (YouTubeMusicService.TrackResult item : libraryTracks) {
                if (item != null && isLikedPlaylistStyle(item)) {
                    likedTrack = item;
                    break;
                }
            }
            if (likedTrack != null) {
                display.add(likedTrack);
                seenIds.add(likedTrack.contentId == null ? "" : likedTrack.contentId);
            }
            // 2. Favoritos — always second
            List<FavoritesPlaylistStore.FavoriteTrack> favorites = FavoritesPlaylistStore.loadFavorites(requireContext());
            int count = favorites.size();
            String subtitle = FavoritesPlaylistStore.buildSubtitle(count);
            String coverUrl = "";
            for (FavoritesPlaylistStore.FavoriteTrack fav : favorites) {
                if (fav != null && !TextUtils.isEmpty(fav.imageUrl)) {
                    coverUrl = fav.imageUrl;
                    break;
                }
            }
            YouTubeMusicService.TrackResult favoritesTrack = new YouTubeMusicService.TrackResult(
                    "playlist",
                    FavoritesPlaylistStore.PLAYLIST_ID,
                    FavoritesPlaylistStore.PLAYLIST_TITLE,
                    subtitle,
                    coverUrl
            );
            display.add(favoritesTrack);
            seenIds.add(FavoritesPlaylistStore.PLAYLIST_ID);

            // 3. Pinned items (in pin order) — after Liked & Favoritos
            // Collect all candidate items (custom playlists, YouTube playlists, radios)
            Map<String, YouTubeMusicService.TrackResult> allCandidates = new java.util.LinkedHashMap<>();

            // Custom playlists
            List<String> customPlaylists = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
            for (String customName : customPlaylists) {
                List<FavoritesPlaylistStore.FavoriteTrack> customTracks = CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(requireContext(), customName);
                int countCustom = customTracks.size();
                String customSubtitle = countCustom == 1 ? "1 canción" : countCustom + " canciones";
                String customThumb = "";
                for (FavoritesPlaylistStore.FavoriteTrack t : customTracks) {
                    if (!TextUtils.isEmpty(t.imageUrl)) {
                        customThumb = t.imageUrl;
                        break;
                    }
                }
                String contentId = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + customName;
                allCandidates.put(contentId, new YouTubeMusicService.TrackResult(
                        "playlist", contentId, customName, customSubtitle, customThumb
                ));
            }

            // YouTube playlists
            for (YouTubeMusicService.TrackResult item : libraryTracks) {
                if (item == null) continue;
                String cid = item.contentId == null ? "" : item.contentId;
                if (!seenIds.contains(cid) && !allCandidates.containsKey(cid)) {
                    allCandidates.put(cid, item);
                }
            }

            // Radio history entries
            List<RadioHistoryStore.RadioEntry> radios = RadioHistoryStore.INSTANCE.getRadios(requireContext());
            for (RadioHistoryStore.RadioEntry radio : radios) {
                if (!allCandidates.containsKey(radio.getRadioPlaylistId())) {
                    int radioCount = radio.getTracks().size();
                    String radioSubtitle = "Radio \u2022 " + radioCount + (radioCount == 1 ? " canción" : " canciones");
                    allCandidates.put(radio.getRadioPlaylistId(), new YouTubeMusicService.TrackResult(
                            "playlist",
                            radio.getRadioPlaylistId(),
                            radio.getSongTitle() + " Radio",
                            radioSubtitle,
                            radio.getSongThumbnail()
                    ));
                }
            }

            // Insert pinned items first (in pin order)
            for (String pinnedId : pinnedIds) {
                if (seenIds.contains(pinnedId)) continue;
                YouTubeMusicService.TrackResult candidate = allCandidates.get(pinnedId);
                if (candidate != null) {
                    display.add(candidate);
                    seenIds.add(pinnedId);
                }
            }

            // Then non-pinned custom playlists
            for (String customName : customPlaylists) {
                String contentId = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + customName;
                if (seenIds.add(contentId)) {
                    YouTubeMusicService.TrackResult candidate = allCandidates.get(contentId);
                    if (candidate != null) display.add(candidate);
                }
            }

            // Then non-pinned YouTube playlists
            for (YouTubeMusicService.TrackResult item : libraryTracks) {
                if (item == null) continue;
                String cid = item.contentId == null ? "" : item.contentId;
                if (seenIds.add(cid)) {
                    display.add(item);
                }
            }

            // Then non-pinned radios at the end
            for (RadioHistoryStore.RadioEntry radio : radios) {
                if (seenIds.add(radio.getRadioPlaylistId())) {
                    YouTubeMusicService.TrackResult candidate = allCandidates.get(radio.getRadioPlaylistId());
                    if (candidate != null) display.add(candidate);
                }
            }

            // Local files playlist — always last if enabled
            if (LocalFilesStore.isEnabled(requireContext())) {
                List<LocalFilesStore.LocalTrack> localTracks = LocalFilesStore.getCachedFiles(requireContext());
                int localCount = localTracks.size();
                String localSubtitle = localCount == 1 ? "1 canción" : localCount + " canciones";
                display.add(new YouTubeMusicService.TrackResult(
                        "playlist",
                        LocalFilesStore.PLAYLIST_ID,
                        "Archivos locales",
                        localSubtitle,
                        ""
                ));
            }
        }
        cachedDisplayLibrary = new ArrayList<>(display);
        displayLibraryDirty = false;
        return display;
    }
    /**
     * Asynchronously fetches cloud playlists and refreshes the adapter if new ones are found.
     */
    private void maybeAppendCloudPlaylists() {
        if (!isAdded()) return;
        if (!AuthManager.getInstance(requireContext()).isSignedIn() || cloudPlaylistFetchInFlight) return;
        cloudPlaylistFetchInFlight = true;
        CloudSyncManager.getInstance(requireContext()).fetchCloudPlaylists(cloudMap -> {
            if (!isAdded()) {
                cloudPlaylistFetchInFlight = false;
                return;
            }
            mainHandler.post(() -> {
                try {
                    if (!isAdded()) return;
                    boolean changed = false;
                    Set<String> existingIds = new HashSet<>();
                    for (YouTubeMusicService.TrackResult r : libraryTracks) {
                        if (r != null && r.contentId != null) existingIds.add(r.contentId);
                    }
                    for (Map.Entry<String, List<FavoritesPlaylistStore.FavoriteTrack>> entry : cloudMap.entrySet()) {
                        String name = entry.getKey();
                        String contentId = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name;
                        if (existingIds.contains(contentId)) continue;
                        // Skip if already a local custom playlist
                        if (CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext()).contains(name)) continue;
                        List<FavoritesPlaylistStore.FavoriteTrack> cloudTracks = entry.getValue();
                        String sub = cloudTracks.size() == 1 ? "1 canci\u00f3n (Nube)" : cloudTracks.size() + " canciones (Nube)";
                        String thumb = "";
                        for (FavoritesPlaylistStore.FavoriteTrack t : cloudTracks) {
                            if (!TextUtils.isEmpty(t.imageUrl)) { thumb = t.imageUrl; break; }
                        }
                        libraryTracks.add(new YouTubeMusicService.TrackResult(
                                "playlist", contentId, name, sub, thumb
                        ));
                        existingIds.add(contentId);
                        changed = true;
                    }
                    if (changed) {
                        displayLibraryDirty = true;
                        if (activeScreen == ScreenMode.LIBRARY) {
                            List<YouTubeMusicService.TrackResult> display = buildDisplayLibrary();
                            if (adapter != null) adapter.submitResults(new ArrayList<>(display));
                        }
                    }
                } finally {
                    cloudPlaylistFetchInFlight = false;
                }
            });
        });
    }
    private void refreshCurrentPlayingPlaylistState() {
        if (!isAdded()) {
            return;
        }
        boolean previousActive = currentPlayingPlaylistActive;
        String previousPlaylistId = currentPlayingPlaylistId;
        boolean previousIsPlaying = currentIsActuallyPlaying;

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        boolean isPlaying = prefs.getBoolean(PREF_LAST_IS_PLAYING, false);
        String playlistId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        String normalizedPlaylistId = playlistId == null ? "" : playlistId.trim();

        if (TextUtils.isEmpty(normalizedPlaylistId)) {
            normalizedPlaylistId = "";
        }
        boolean changed = !TextUtils.equals(currentPlayingPlaylistId, normalizedPlaylistId)
                || previousIsPlaying != isPlaying;
        
        currentPlayingPlaylistId = normalizedPlaylistId;
        currentPlayingPlaylistActive = !TextUtils.isEmpty(normalizedPlaylistId);
        currentIsActuallyPlaying = isPlaying;
        if (changed && adapter != null) {
            adapter.notifyPlayingPlaylistChanged(
                    previousActive ? previousPlaylistId : "",
                    currentPlayingPlaylistActive ? currentPlayingPlaylistId : ""
            );
        }
    }
    private boolean isPlaylistCurrentlyPlaying(@Nullable String playlistId) {
        if (!currentPlayingPlaylistActive || TextUtils.isEmpty(playlistId)) {
            return false;
        }
        return TextUtils.equals(currentPlayingPlaylistId, playlistId == null ? "" : playlistId.trim());
    }
    private View llFragBrandHeader;
    private TextView tvFragBrandTitle;
    private ImageView btnFragHeaderSearch;
    private com.google.android.material.button.MaterialButton btnFragSignIn;
    private com.google.android.material.imageview.ShapeableImageView btnFragProfilePhoto;
    private View llLibraryHeaderRow;
    private View tvLibraryTitle;
    private View llMusicState;
    private MaterialButton btnYoutubeLogin;
    private LinearLayout llLibraryEmptyState;
    private ProgressBar progressMusic;
    private TextView tvMusicState;
    private RecyclerView rvMusicResults;
    private LinearLayout llFeaturedResult;
    private ImageView ivFeaturedThumb;
    private TextView tvFeaturedTitle;
    private TextView tvFeaturedSubtitle;
    private MaterialButton btnFeaturedPlay;
    private MaterialButton btnFeaturedSave;
    private SwipeRefreshLayout swipeLibraryRefresh;
    private View flLibraryLoadingOverlay;
    private boolean libraryGridOverlayActive;
    private boolean suppressGridOverlay;
    private int libraryGridPendingCount;
    private final YouTubeMusicService youTubeMusicService = new YouTubeMusicService();
    private final ExecutorService offlinePrefetchExecutor = Executors.newFixedThreadPool(3);
    private final YouTubeMusicService offlinePrefetchService = new YouTubeMusicService(offlinePrefetchExecutor);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService offlineStateExecutor = Executors.newSingleThreadExecutor();
    private final List<YouTubeMusicService.TrackResult> tracks = new ArrayList<>();
    private final List<YouTubeMusicService.TrackResult> libraryTracks = new ArrayList<>();
    private final android.util.LruCache<String, String> normalizedFilterCache = new android.util.LruCache<>(512);
    private final Set<String> offlinePrefetchInFlight = new HashSet<>();
    private final Object cachedPlaylistTracksLock = new Object();
    private final Map<String, ArrayList<CachedPlaylistTrack>> cachedPlaylistTracksById = new LinkedHashMap<String, ArrayList<CachedPlaylistTrack>>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ArrayList<CachedPlaylistTrack>> eldest) {
            return size() > 20;
        }
    };
    private final Map<String, Long> cachedPlaylistTracksUpdatedAtById = new HashMap<>();
    /** Cache: playlistId â†’ top-4 thumbnail URLs for the 2x2 grid cover. */
    private final Map<String, List<String>> playlistGridUrlsCache = new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 100;
        }
    };
    private MusicResultsAdapter adapter;
    private LinearLayoutManager musicResultsLayoutManager;
    private boolean loadingLibrary;
    private boolean libraryFetchInFlight;
    private boolean pendingScrollToTop = false;
    @Nullable
    private Runnable pendingLibraryInlineSearchRunnable;
    @NonNull
    private String lastLibraryInlineDispatchedQuery = "";
    private boolean libraryInlineManualModeActive;
    private boolean libraryInlinePendingReveal;
    @Nullable
    private GoogleSignInClient googleSignInClient;
    @Nullable
    private GoogleSignInAccount pendingRecoverableAccount;
    private boolean pendingWebSessionAuthRecovery;
    private boolean pendingWebSessionUserTriggered;
    private boolean pendingWebSessionBackgroundRefresh;
    private boolean pendingWebSessionForceRefresh;
    @Nullable
    private YouTubeMusicService.TrackResult featuredTrack;
    private String youtubeAccessToken = "";
    private boolean youtubeAuthRefreshInProgress;
    private boolean streamingOauthCompleted;
    private long lastAutoWebSessionLaunchAtMs;
    private boolean webSessionAlreadyAttempted;
    private boolean restoringHiddenMiniPlayerFromSnapshot;
    @Nullable
    private PopupWindow playlistActionPopupWindow;
    @NonNull
    private String streamingOauthAccountEmail = "";
    @NonNull
    private String currentPlayingPlaylistId = "";
    private boolean currentPlayingPlaylistActive;
    private boolean currentIsActuallyPlaying;
    @Nullable
    private Observer<List<WorkInfo>> offlineQueueObserver;
    @Nullable
    private Observer<List<WorkInfo>> offlineManualQueueObserver;
    private boolean offlineAutoQueueScanInFlight;
    private boolean offlineQueueHadActiveWork;
    private boolean offlineManualQueueHadActiveWork;
    private final Map<String, Float> autoQueueProgressByPlaylist = new HashMap<>();
    private final Set<String> autoQueueDownloading = new HashSet<>();
    private final Map<String, Float> manualQueueProgressByPlaylist = new HashMap<>();
    private final Set<String> manualQueueDownloading = new HashSet<>();
    private boolean cloudPlaylistFetchInFlight;
    private boolean lastMiniPlayerIsPlaying;
    private ActivityResultLauncher<Intent> signInLauncher;
    private ActivityResultLauncher<Intent> recoverAuthLauncher;
    private ActivityResultLauncher<Intent> webSessionLauncher;
    private enum ScreenMode {
        LIBRARY
    }
    private ScreenMode activeScreen = ScreenMode.LIBRARY;
    private static final Object fragmentLock = new Object();
    private static final YouTubeCropTransformation SHARED_YT_CROP = new YouTubeCropTransformation();
    private interface OnLibraryTrackClick {
        void onTrackClick(@NonNull YouTubeMusicService.TrackResult track);
    }
    private interface OnLibraryTrackMoreClick {
        void onTrackMoreClick(@NonNull YouTubeMusicService.TrackResult track, @NonNull View anchor);
    }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleGoogleSignInResult(result.getData())
        );
        recoverAuthLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    GoogleSignInAccount account = pendingRecoverableAccount;
                    boolean retryWebSessionFlow = pendingWebSessionAuthRecovery;
                    boolean retryUserTriggered = pendingWebSessionUserTriggered;
                    boolean retryBackgroundRefresh = pendingWebSessionBackgroundRefresh;
                    boolean retryForceRefresh = pendingWebSessionForceRefresh;
                    clearPendingRecoverableAuthState();
                    pendingRecoverableAccount = null;
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        if (account != null) {
                            requestYoutubeAccessToken(account, true);
                            return;
                        }
                        if (retryWebSessionFlow) {
                            requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(
                                    retryUserTriggered,
                                    retryBackgroundRefresh,
                                    retryForceRefresh
                            );
                            return;
                        }
                    }
                    setLibraryLoading(false, "Permiso de YouTube no concedido.");
                }
        );
        webSessionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        setLibraryLoading(false, "Sesion web cancelada.");
                        return;
                    }
                    handleWebSessionAuthSuccess(result.getData());
                }
        );
    }
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_music_player, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PlaybackEventBus.addListener(this);
        setupFragBrandHeader(view);
        llLibraryHeaderRow = view.findViewById(R.id.llLibraryHeaderRow);
        tvLibraryTitle = view.findViewById(R.id.tvLibraryTitle);
        llMusicState = view.findViewById(R.id.llMusicState);
        btnYoutubeLogin = view.findViewById(R.id.btnYoutubeLogin);
        llLibraryEmptyState = view.findViewById(R.id.llLibraryEmptyState);
        progressMusic = view.findViewById(R.id.progressMusic);
        tvMusicState = view.findViewById(R.id.tvMusicState);
        rvMusicResults = view.findViewById(R.id.rvMusicResults);
        llFeaturedResult = view.findViewById(R.id.llFeaturedResult);
        ivFeaturedThumb = view.findViewById(R.id.ivFeaturedThumb);
        tvFeaturedTitle = view.findViewById(R.id.tvFeaturedTitle);
        tvFeaturedSubtitle = view.findViewById(R.id.tvFeaturedSubtitle);
        btnFeaturedPlay = view.findViewById(R.id.btnFeaturedPlay);
        btnFeaturedSave = view.findViewById(R.id.btnFeaturedSave);
        swipeLibraryRefresh = view.findViewById(R.id.swipeLibraryRefresh);
        flLibraryLoadingOverlay = view.findViewById(R.id.flLibraryLoadingOverlay);
        adapter = new MusicResultsAdapter(this::openTrack, this::onMusicResultMoreClicked);
        musicResultsLayoutManager = new LinearLayoutManager(requireContext());
        rvMusicResults.setLayoutManager(musicResultsLayoutManager);
        rvMusicResults.setHasFixedSize(true);
        rvMusicResults.setItemViewCacheSize(5);
        rvMusicResults.setItemAnimator(null);
        rvMusicResults.setAdapter(adapter);
        rvMusicResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!isAdded()) return;
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    Glide.with(MusicPlayerFragment.this).pauseRequests();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE
                        || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    Glide.with(MusicPlayerFragment.this).resumeRequests();
                }
            }
        });
        rebuildGoogleSignInClient();
        restoreCachedStreamingSessionState();
        refreshCurrentPlayingPlaylistState();
        setupLibraryPullToRefresh();
        startObservingOfflineQueue();
        llFeaturedResult.setVisibility(View.GONE);
        btnYoutubeLogin.setVisibility(View.GONE);
        btnYoutubeLogin.setOnClickListener(v -> onYoutubeLoginClicked());
        btnFeaturedPlay.setOnClickListener(v -> {
            if (featuredTrack != null) {
                openTrack(featuredTrack);
            }
        });
        btnFeaturedSave.setOnClickListener(v -> {
            if (featuredTrack == null) {
                return;
            }
            shareTrackResult(featuredTrack);
        });
        updateYoutubeButtonLabel();
        switchScreen(ScreenMode.LIBRARY);
        if (!isPlaylistDetailStatePending()) {
            persistStreamingScreen(STREAM_SCREEN_LIBRARY);
        }
        view.postDelayed(() -> {
            if (!isAdded() || isRemoving() || isDetached()) return;
            maybeAutoLaunchWebSessionIfNeeded();
        }, 800L);
        view.post(() -> {
            if (!isAdded() || isRemoving() || isDetached()) return;
            maybeRestoreHiddenMiniPlayerFromPausedSnapshot();
        });
        // Always start from the top — post so it runs AFTER RecyclerView restores saved scroll state
        if (rvMusicResults != null) {
            rvMusicResults.post(() -> {
                scrollToTop();
            });
        }
    }
    @Override
    public void onResume() {
        super.onResume();
        offlineQueueHadActiveWork = false;
        offlineManualQueueHadActiveWork = false;
        if (isHidden()) return;
        refreshFragHeaderProfilePhoto();
        startObservingOfflineQueue();
        maybeResumeStarledOfflineDownloads();
        refreshCurrentPlayingPlaylistState();
        if (adapter != null) {
            adapter.invalidatePlaylistOfflineState(null);
        }
        maybeEnqueueNextOfflineAutoPlaylist();
        maybeRestoreHiddenMiniPlayerFromPausedSnapshot();
        maybeSyncLibraryIfAuthorized();
        if (isAdded()) {
            boolean offlineMode = requireContext()
                    .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, android.app.Activity.MODE_PRIVATE)
                    .getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false);
            if (offlineMode && activeScreen == ScreenMode.LIBRARY) {
                renderLibraryResults();
            }
        }
    }
    public void scrollToTop() {
        pendingScrollToTop = true;
        if (rvMusicResults != null && musicResultsLayoutManager != null) {
            rvMusicResults.post(() -> {
                if (rvMusicResults != null) musicResultsLayoutManager.scrollToPositionWithOffset(0, 0);
            });
        }
    }

    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            return;
        }
        refreshFragHeaderProfilePhoto();
        scrollToTop();
        startObservingOfflineQueue();
        refreshCurrentPlayingPlaylistState();
        if (adapter != null) {
            adapter.invalidatePlaylistOfflineState(null);
        }
        maybeEnqueueNextOfflineAutoPlaylist();
        maybeRestoreHiddenMiniPlayerFromPausedSnapshot();
        maybeSyncLibraryIfAuthorized();
    }

    private void setupFragBrandHeader(@NonNull View root) {
        llFragBrandHeader = root.findViewById(R.id.llFragBrandHeader);
        tvFragBrandTitle = root.findViewById(R.id.tvFragBrandTitle);
        btnFragHeaderSearch = root.findViewById(R.id.btnFragHeaderSearch);
        btnFragSignIn = root.findViewById(R.id.btnFragSignIn);
        btnFragProfilePhoto = root.findViewById(R.id.btnFragProfilePhoto);

        // Apply status bar inset as top padding so header doesn't render under the status bar
        if (llFragBrandHeader != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(llFragBrandHeader, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            llFragBrandHeader.requestApplyInsets();
        }

        // Brand title with icon — mirrors MainActivity.configureHeaderActionForMainModules()
        if (tvFragBrandTitle != null) {
            tvFragBrandTitle.setAllCaps(true);
            tvFragBrandTitle.setLetterSpacing(0.08f);
            try {
                Typeface brandFont = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.manrope_variable);
                if (brandFont != null) tvFragBrandTitle.setTypeface(brandFont);
            } catch (Exception ignored) {}
            float density = getResources().getDisplayMetrics().density;
            int iconSize = (int) (26 * density);
            android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
            if (icon != null) {
                icon.setBounds(0, 0, iconSize, iconSize);
                tvFragBrandTitle.setCompoundDrawablesRelative(icon, null, null, null);
            }
            tvFragBrandTitle.setCompoundDrawablePadding((int) (8 * density));
        }

        // Search button → opens SearchFragment via MainActivity
        if (btnFragHeaderSearch != null) {
            btnFragHeaderSearch.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openSearchFragment();
                }
            });
        }

        // Sign-in button → triggers auth via MainActivity
        if (btnFragSignIn != null) {
            btnFragSignIn.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    btnFragSignIn.setEnabled(false);
                    btnFragSignIn.setAlpha(0.56f);
                    ((MainActivity) getActivity()).requireAuth(
                        () -> {
                            if (btnFragSignIn != null) { btnFragSignIn.setEnabled(true); btnFragSignIn.setAlpha(1f); }
                            refreshFragHeaderProfilePhoto();
                        },
                        () -> {
                            if (btnFragSignIn != null) { btnFragSignIn.setEnabled(true); btnFragSignIn.setAlpha(1f); }
                        }
                    );
                }
            });
        }

        // Profile photo → opens Settings
        if (btnFragProfilePhoto != null) {
            btnFragProfilePhoto.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).enterSettings();
                }
            });
        }

        refreshFragHeaderProfilePhoto();
    }

    void refreshFragHeaderProfilePhoto() {
        if (!isAdded() || btnFragProfilePhoto == null || btnFragSignIn == null) return;
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("streaming_cache", android.app.Activity.MODE_PRIVATE);
        String cachedUrl = prefs.getString("cached_google_profile_photo_url", "");
        android.net.Uri photoUri = null;
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) photoUri = user.getPhotoUrl();
        if (photoUri == null && cachedUrl != null && !cachedUrl.isEmpty()) {
            photoUri = android.net.Uri.parse(cachedUrl);
        }
        boolean signedIn = (getActivity() instanceof MainActivity)
                && ((MainActivity) getActivity()).getAuthManager().isSignedIn();
        if (signedIn) {
            btnFragSignIn.setVisibility(View.GONE);
            btnFragProfilePhoto.setVisibility(View.VISIBLE);
            if (photoUri != null) {
                Glide.with(this)
                        .load(photoUri)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(btnFragProfilePhoto);
            } else {
                btnFragProfilePhoto.setImageResource(android.R.drawable.ic_menu_myplaces);
            }
        } else {
            btnFragProfilePhoto.setVisibility(View.GONE);
            btnFragProfilePhoto.setImageDrawable(null);
            btnFragSignIn.setVisibility(View.VISIBLE);
        }
    }

    private void maybeAutoLaunchWebSessionIfNeeded() {
        if (!isAdded()) {
            return;
        }
        if (streamingOauthCompleted || loadingLibrary || webSessionLauncher == null) {
            return;
        }
        if (webSessionAlreadyAttempted) {
            return;
        }
        if (!isNetworkAvailable()) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastAutoWebSessionLaunchAtMs) < 3000L) {
            return;
        }
        lastAutoWebSessionLaunchAtMs = now;
        webSessionAlreadyAttempted = true;
        onYoutubeLoginClicked();
    }
    private boolean isPlaylistDetailStatePending() {
        if (!isAdded()) {
            return false;
        }
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String lastScreen = prefs.getString(PREF_LAST_STREAM_SCREEN, STREAM_SCREEN_LIBRARY);
        return TextUtils.equals(STREAM_SCREEN_PLAYLIST_DETAIL, lastScreen);
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
    public boolean externalRestoreLastPlaylistDetailIfNeeded() {
        if (!isAdded()) {
            return false;
        }
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String lastScreen = prefs.getString(PREF_LAST_STREAM_SCREEN, STREAM_SCREEN_LIBRARY);
        if (!TextUtils.equals(STREAM_SCREEN_PLAYLIST_DETAIL, lastScreen)) {
            return false;
        }
        androidx.fragment.app.Fragment existing = getParentFragmentManager().findFragmentByTag("playlist_detail");
        if (existing != null && existing.isAdded()) {
            getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .show(existing)
                    .commit();
            return true;
        }
        boolean restored = openLastPlaylistDetailFromPrefs();
        if (!restored) {
            persistStreamingScreen(STREAM_SCREEN_LIBRARY);
        }
        return restored;
    }
    private void maybeRestoreHiddenMiniPlayerFromPausedSnapshot() {
        if (!isAdded() || isHidden() || restoringHiddenMiniPlayerFromSnapshot) {
            return;
        }
        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            return;
        }
        PlaybackHistoryStore.Snapshot snapshot = loadPlaybackSnapshot();
        if (!snapshot.isValid() || snapshot.queue.isEmpty()) {
            return;
        }
        if (getParentFragmentManager().isStateSaved()) {
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
        int snapshotIndex = Math.max(0, Math.min(snapshot.currentIndex, ids.size() - 1));
        persistSnapshotPositionToPlayerState(snapshot);
        SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                ids,
                titles,
                artists,
                durations,
                images,
                snapshotIndex,
                false
        );
        playerFragment.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
        restoringHiddenMiniPlayerFromSnapshot = true;
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .runOnCommit(() -> {
                    restoringHiddenMiniPlayerFromSnapshot = false;
                })
                .commit();
    }
    private void switchScreen(@NonNull ScreenMode mode) {
        activeScreen = mode;
        updateLibraryPullRefreshAvailability();
        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        updateYoutubeButtonLabel();
        llFeaturedResult.setVisibility(View.GONE);
        String inlineQuery = getLibraryInlineQuery();
        if (!TextUtils.isEmpty(inlineQuery)) {
            libraryInlineManualModeActive = true;
            showLibraryInlineBlankState();
            return;
        } else if (!libraryTracks.isEmpty()) {
            libraryInlineManualModeActive = false;
            renderLibraryResults();
        } else {
            libraryInlineManualModeActive = false;
            adapter.submitResults(new ArrayList<>());
            tvMusicState.setText("");
            showLibraryEmptyState();
        }
        maybeSyncLibraryIfAuthorized();
    }
    private void maybeSyncLibraryIfAuthorized() {
        if (!isAdded() || loadingLibrary || libraryFetchInFlight || !streamingOauthCompleted) {
            return;
        }
        if (!isNetworkAvailable()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean hasLocalData = !libraryTracks.isEmpty();
        if (hasLocalData && (now - lastLibrarySyncAtMs) < LIBRARY_REFRESH_MIN_INTERVAL_MS) {
            return;
        }
        String accountKey = resolveAccountKey();
        boolean backgroundRefresh = hasLocalData;
        if (hasValidCachedToken(accountKey)) {
            youtubeAccessToken = cachedYoutubeAccessToken;
            fetchLibraryPlaylists(false, backgroundRefresh, false);
            return;
        }
        if (!TextUtils.isEmpty(youtubeAccessToken)) {
            fetchLibraryPlaylists(false, backgroundRefresh, false);
        }
    }
    private void setupLibraryPullToRefresh() {
        if (swipeLibraryRefresh == null || !isAdded()) {
            return;
        }
        swipeLibraryRefresh.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.stitch_blue),
                ContextCompat.getColor(requireContext(), android.R.color.white)
        );
        swipeLibraryRefresh.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(requireContext(), R.color.surface_low)
        );
        swipeLibraryRefresh.setProgressViewOffset(false, dpToPx(-40), dpToPx(40));
        swipeLibraryRefresh.setDistanceToTriggerSync(dpToPx(140));
        swipeLibraryRefresh.setOnRefreshListener(this::triggerLibraryPullRefresh);
        updateLibraryPullRefreshAvailability();
    }
    private void updateLibraryPullRefreshAvailability() {
        if (swipeLibraryRefresh == null) {
            return;
        }
        swipeLibraryRefresh.setEnabled(activeScreen == ScreenMode.LIBRARY);
    }
    private void setLibraryPullRefreshState(boolean refreshing) {
        if (swipeLibraryRefresh == null) {
            return;
        }
        if (swipeLibraryRefresh.isRefreshing() == refreshing) {
            return;
        }
        swipeLibraryRefresh.setRefreshing(refreshing);
    }
    private void triggerLibraryPullRefresh() {
        if (!isAdded()) {
            setLibraryPullRefreshState(false);
            return;
        }
        if (activeScreen != ScreenMode.LIBRARY) {
            setLibraryPullRefreshState(false);
            return;
        }
        setLibraryPullRefreshState(true);
        suppressGridOverlay = true;
        lastLibrarySyncAtMs = 0L;
        // Keep persisted YouTube grid entries â€” only clear Favorites and custom playlists
        java.util.Iterator<java.util.Map.Entry<String, List<String>>> gridIter =
                playlistGridUrlsCache.entrySet().iterator();
        while (gridIter.hasNext()) {
            String pid = gridIter.next().getKey();
            if (FavoritesPlaylistStore.PLAYLIST_ID.equals(pid) || pid.startsWith("custom_")) {
                gridIter.remove();
            }
        }

        // Mark all per-playlist track caches as stale so playlist detail re-fetches when online,
        // but preserve the actual track data for offline fallback (allowStale=true reads it).
        synchronized (cachedPlaylistTracksLock) {
            cachedPlaylistTracksById.clear();
            cachedPlaylistTracksUpdatedAtById.clear();
        }
        SharedPreferences cachePrefs = getCachePrefs();
        SharedPreferences.Editor editor = cachePrefs.edit();
        for (String key : cachePrefs.getAll().keySet()) {
            if (key.contains(FavoritesPlaylistStore.PLAYLIST_ID)) {
                continue;
            }
            if (key.startsWith(PREF_TRACKS_UPDATED_AT_PREFIX)
                    || key.startsWith(PREF_TRACKS_FULL_CACHE_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();

        // Also refresh the active playlist detail if it's visible on top
        androidx.fragment.app.Fragment detailFragment =
                getParentFragmentManager().findFragmentByTag("playlist_detail");
        if (detailFragment instanceof PlaylistDetailFragment
                && detailFragment.isAdded()
                && !detailFragment.isHidden()) {
            ((PlaylistDetailFragment) detailFragment).externalForceRefresh();
        }

        if (!isNetworkAvailable()) {
            // Rescan local files so pull-to-refresh updates them even offline
            if (LocalFilesStore.isEnabled(requireContext())) {
                offlineStateExecutor.execute(() -> {
                    try {
                        List<LocalFilesStore.LocalTrack> fresh = LocalFilesStore.scanLocalFiles(requireContext());
                        LocalFilesStore.cacheFiles(requireContext(), fresh);
                    } catch (Exception ignored) {}
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            displayLibraryDirty = true;
                            setLibraryPullRefreshState(false);
                            renderLibraryResults();
                        });
                    }
                });
            } else {
                setLibraryPullRefreshState(false);
                renderLibraryResults();
            }
            return;
        }
        if (!streamingOauthCompleted) {
            onYoutubeLoginClicked();
            return;
        }
        // Verify token freshness â€” if expired, refresh it before fetching playlists
        String accountKey = resolveAccountKey();
        if (!TextUtils.isEmpty(youtubeAccessToken) && hasValidCachedToken(accountKey)) {
            fetchLibraryPlaylists(true, false, true);
            return;
        }
        // Token missing or expired â€” force a fresh OAuth token
        youtubeAccessToken = "";
        clearCachedToken();
        requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(true, false, true);
    }
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
    private void rebuildGoogleSignInClient() {
        Scope ytScope = new Scope(youTubeMusicService.getYoutubeReadonlyScope());
        String serverClientId = "";
        try {
            int resId = requireContext().getResources().getIdentifier(
                    "default_web_client_id",
                    "string",
                    requireContext().getPackageName()
            );
            if (resId != 0) {
                serverClientId = getString(resId);
            }
        } catch (Exception ignored) {}
        GoogleSignInOptions.Builder optionsBuilder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(ytScope);
        if (!TextUtils.isEmpty(serverClientId)) {
            optionsBuilder.requestIdToken(serverClientId);
            optionsBuilder.requestServerAuthCode(serverClientId);
        }
        String preferredEmail = getPrimaryAppGoogleEmail();
        if (!TextUtils.isEmpty(preferredEmail)) {
            optionsBuilder.setAccountName(preferredEmail);
        }
        googleSignInClient = GoogleSignIn.getClient(requireContext(), optionsBuilder.build());
    }
    @NonNull
    private String getPrimaryAppGoogleEmail() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || TextUtils.isEmpty(user.getEmail())) {
            return "";
        }
        return user.getEmail().trim();
    }
    private boolean isMatchingPrimaryAppGoogleAccount(@NonNull GoogleSignInAccount account) {
        String appEmail = getPrimaryAppGoogleEmail();
        if (TextUtils.isEmpty(appEmail)) {
            return true;
        }
        String accountEmail = account.getEmail();
        if (TextUtils.isEmpty(accountEmail)) {
            return false;
        }
        return appEmail.equalsIgnoreCase(accountEmail.trim());
    }
    private void onYoutubeLoginClicked() {
        if (!isAdded()) {
            return;
        }
        Log.i(TAG_STREAMING, "login:web_session_launch requested");
        streamingOauthCompleted = false;
        youtubeAccessToken = "";
        clearCachedToken();
        requireContext()
            .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
            .edit()
            .remove(PREF_LAST_YOUTUBE_WEB_COOKIE)
            .apply();
        updateYoutubeButtonLabel();
        setLibraryLoading(true, "Abriendo sesion web de YouTube Music...");
        if (webSessionLauncher == null) {
            setLibraryLoading(false, "No se pudo abrir el mininavegador de sesion.");
            return;
        }
        Intent intent = new Intent(requireContext(), YouTubeMusicWebSessionActivity.class);
        webSessionLauncher.launch(intent);
    }
    private void handleWebSessionAuthSuccess(@NonNull Intent data) {
        String cookieHeader = data.getStringExtra(YouTubeMusicWebSessionActivity.EXTRA_SESSION_COOKIE_HEADER);
        String userAgent = data.getStringExtra(YouTubeMusicWebSessionActivity.EXTRA_SESSION_USER_AGENT);
        if (TextUtils.isEmpty(cookieHeader)) {
            Log.w(TAG_STREAMING, "web_session_result missing_cookie_header");
            setLibraryLoading(false, "No se detecto sesion web valida.");
            return;
        }
        Log.i(
                TAG_STREAMING,
                "web_session_result authenticated cookieLength=" + cookieHeader.length()
        );
        if (isAdded()) {
            requireContext()
                    .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LAST_YOUTUBE_WEB_COOKIE, cookieHeader)
                    .apply();
        }
        // Trigger deferred heavy initialization (ExoPlayer, Glide, etc.) now that
        // the user has successfully logged in via the web session.
        if (isAdded()) {
            ((SleppifyApp) requireContext().getApplicationContext()).performDeferredInit();
        }
        // Activar inmediatamente la cookie en InnertubeResolver para que las
        // siguientes peticiones de resolución y playback estén autenticadas.
        InnertubeResolver.setAuthCookies(cookieHeader);
        streamingOauthCompleted = true;
        updateYoutubeButtonLabel();
        // Keep user-agent available for future web-session API extensions.
        if (TextUtils.isEmpty(userAgent)) {
            userAgent = "";
        }
        // If Firebase auth is not done yet, sign in first then proceed with OAuth
        if (FirebaseAuth.getInstance().getCurrentUser() == null && isAdded()) {
            MainActivity mainActivity = (getActivity() instanceof MainActivity) ? (MainActivity) getActivity() : null;
            if (mainActivity != null) {
                mainActivity.requireAuth(
                        () -> requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(true, false, true),
                        () -> requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(true, false, true)
                );
                return;
            }
        }
        requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(true, false, true);
    }
    private void requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(
            boolean userTriggered,
            boolean backgroundRefresh,
            boolean forceRefresh
    ) {
        if (!isAdded()) {
            return;
        }
        StreamingAccountCandidate accountCandidate = resolveStreamingOAuthAccountCandidate();
        if (accountCandidate == null || TextUtils.isEmpty(accountCandidate.email)) {
            Log.w(TAG_STREAMING, "oauth_account unresolved; trying account chooser fallback");
            if (googleSignInClient != null) {
                setLibraryLoading(true, "Selecciona tu cuenta Google para Streaming...");
                launchGoogleSignInChooser(false);
                return;
            }
            setLibraryLoading(false, "No se encontro cuenta Google para validar YouTube.");
            return;
        }
        Log.i(
                TAG_STREAMING,
                "oauth_account selected email=" + accountCandidate.email + " source=" + accountCandidate.source
        );
        if (!backgroundRefresh) {
            setLibraryLoading(true, "Validando sesion web y permisos YouTube...");
        }
        Account resolvedAccount = new Account(accountCandidate.email, "com.google");
        android.content.Context appContext = requireContext().getApplicationContext();
        authExecutor.execute(() -> {
            try {
                String scope = "oauth2:" + youTubeMusicService.getYoutubeReadonlyScope();
                String token = GoogleAuthUtil.getToken(appContext, resolvedAccount, scope);
                if (TextUtils.isEmpty(token)) {
                    throw new IllegalStateException("No se recibio token OAuth.");
                }
                String finalToken = token;
                mainHandler.post(() -> {
                    streamingOauthAccountEmail = accountCandidate.email;
                    rememberStreamingOauthAccountEmail(accountCandidate.email);
                    youtubeAccessToken = finalToken;
                    streamingOauthCompleted = true;
                    cacheTokenForCurrentAccount(finalToken);
                    updateYoutubeButtonLabel();
                    Log.i(TAG_STREAMING, "oauth_token acquired; loading playlists");
                    fetchLibraryPlaylists(userTriggered, backgroundRefresh, forceRefresh);
                });
            } catch (UserRecoverableAuthException recoverableException) {
                mainHandler.post(() -> {
                    youtubeAuthRefreshInProgress = false;
                    Log.w(TAG_STREAMING, "oauth_token requires user consent");
                    setLibraryLoading(false, "Sesion web lista. Falta permiso OAuth de YouTube.");
                    Intent recoverIntent = recoverableException.getIntent();
                    if (recoverIntent != null) {
                        try {
                            pendingWebSessionAuthRecovery = true;
                            pendingWebSessionUserTriggered = userTriggered;
                            pendingWebSessionBackgroundRefresh = backgroundRefresh;
                            pendingWebSessionForceRefresh = forceRefresh;
                            recoverAuthLauncher.launch(recoverIntent);
                        } catch (Exception launchError) {
                            clearPendingRecoverableAuthState();
                            try {
                                startActivity(recoverIntent);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            } catch (IOException | GoogleAuthException | IllegalStateException e) {
                mainHandler.post(() -> {
                    youtubeAuthRefreshInProgress = false;
                    Log.e(TAG_STREAMING, "oauth_token failed: " + e.getMessage());
                    setLibraryLoading(false, "No se pudo validar YouTube: " + e.getMessage());
                    if (userTriggered && isAdded()) {
                    }
                });
            }
        });
    }
    private void forcePrimaryAccountReSignIn() {
        if (googleSignInClient == null) {
            setLibraryLoading(false, "No se pudo sincronizar la cuenta de YouTube.");
            return;
        }
        setLibraryLoading(true, "Usando la misma cuenta con la que entraste a la app...");
        launchGoogleSignInChooser(true);
    }
    private void launchGoogleSignInChooser(boolean forceFreshSelection) {
        if (googleSignInClient == null) {
            setLibraryLoading(false, "No se pudo abrir Google Sign-In.");
            return;
        }
        Runnable launchIntent = () -> {
            if (!isAdded()) {
                return;
            }
            signInLauncher.launch(googleSignInClient.getSignInIntent());
        };
        if (!forceFreshSelection) {
            launchIntent.run();
            return;
        }
        googleSignInClient.signOut().addOnCompleteListener(task -> launchIntent.run());
    }
    private void handleGoogleSignInResult(@Nullable Intent data) {
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            if (account == null) {
                setLibraryLoading(false, "No se pudo obtener la cuenta.");
                return;
            }
            if (!isMatchingPrimaryAppGoogleAccount(account)) {
                if (googleSignInClient != null) {
                    googleSignInClient.signOut();
                }
                setLibraryLoading(false, "Selecciona la misma cuenta Google con la que iniciaste sesion en la app.");
                return;
            }
            requestYoutubeAccessToken(account, true);
        } catch (ApiException e) {
            String failureMessage = buildGoogleSignInFailureMessage(e);
            setLibraryLoading(false, failureMessage);
            if (isAdded()) {
            }
        }
    }
    @NonNull
    private String buildGoogleSignInFailureMessage(@NonNull ApiException exception) {
        int statusCode = exception.getStatusCode();
        if (statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            return "Inicio de sesion cancelado.";
        }
        if (statusCode == GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS) {
            return "Google Sign-In sigue en progreso. Intenta de nuevo en unos segundos.";
        }
        if (statusCode == GoogleSignInStatusCodes.SIGN_IN_FAILED) {
            return "No se pudo completar Google Sign-In en este dispositivo.";
        }
        if (statusCode == CommonStatusCodes.NETWORK_ERROR) {
            return "Sin conexion a internet. Revisa tu red e intenta de nuevo.";
        }
        if (statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            return "Configuracion OAuth invalida para Streaming (DEVELOPER_ERROR). Revisa SHA y client ID en Google Cloud.";
        }
        return "No se pudo iniciar sesion con Google (codigo " + statusCode + ").";
    }
    private void requestYoutubeAccessToken(@NonNull GoogleSignInAccount account, boolean userTriggered) {
        requestYoutubeAccessToken(account, userTriggered, false, false);
    }
    private void requestYoutubeAccessToken(
            @NonNull GoogleSignInAccount account,
            boolean userTriggered,
            boolean backgroundRefresh
    ) {
        requestYoutubeAccessToken(account, userTriggered, backgroundRefresh, false);
    }
    private void requestYoutubeAccessToken(
            @NonNull GoogleSignInAccount account,
            boolean userTriggered,
            boolean backgroundRefresh,
            boolean forceRefresh
    ) {
        if (!isAdded()) {
            return;
        }
        if (!backgroundRefresh) {
            setLibraryLoading(true, "Solicitando permiso de biblioteca...");
        }
        Account accountRef = account.getAccount();
        if (accountRef == null && !TextUtils.isEmpty(account.getEmail())) {
            accountRef = new Account(account.getEmail(), "com.google");
        }
        if (accountRef == null) {
            setLibraryLoading(false, "No se pudo resolver la cuenta de Google.");
            return;
        }
        Account resolvedAccount = accountRef;
        android.content.Context appContext = requireContext().getApplicationContext();
        authExecutor.execute(() -> {
            try {
                String scope = "oauth2:" + youTubeMusicService.getYoutubeReadonlyScope();
                String token = GoogleAuthUtil.getToken(appContext, resolvedAccount, scope);
                if (TextUtils.isEmpty(token)) {
                    throw new IllegalStateException("No se recibio token OAuth.");
                }
                String finalToken = token;
                mainHandler.post(() -> {
                    youtubeAccessToken = finalToken;
                    streamingOauthCompleted = true;
                    cacheTokenForCurrentAccount(finalToken);
                    updateYoutubeButtonLabel();
                    fetchLibraryPlaylists(userTriggered, backgroundRefresh, forceRefresh);
                });
            } catch (UserRecoverableAuthException recoverableException) {
                mainHandler.post(() -> {
                    youtubeAuthRefreshInProgress = false;
                    if (backgroundRefresh && !userTriggered) {
                        return;
                    }
                    clearPendingRecoverableAuthState();
                    pendingRecoverableAccount = account;
                    setLibraryLoading(false, "Confirma el permiso para leer tu biblioteca.");
                    Intent recoverIntent = recoverableException.getIntent();
                    if (recoverIntent == null) {
                        if (isAdded()) {
                        }
                        launchGoogleSignInChooser(true);
                        return;
                    }
                    try {
                        recoverAuthLauncher.launch(recoverIntent);
                    } catch (Exception launchError) {
                        if (isAdded()) {
                        }
                        launchGoogleSignInChooser(true);
                    }
                });
            } catch (IOException | GoogleAuthException | IllegalStateException e) {
                mainHandler.post(() -> {
                    youtubeAuthRefreshInProgress = false;
                    if (!backgroundRefresh) {
                        setLibraryLoading(false, "No se pudo validar YouTube: " + e.getMessage());
                    }
                    if (userTriggered && isAdded()) {
                    }
                });
            }
        });
    }
    private void clearPendingRecoverableAuthState() {
        pendingWebSessionAuthRecovery = false;
        pendingWebSessionUserTriggered = false;
        pendingWebSessionBackgroundRefresh = false;
        pendingWebSessionForceRefresh = false;
    }
    private void fetchLibraryPlaylists(boolean userTriggered) {
        fetchLibraryPlaylists(userTriggered, false, false);
    }
    private void fetchLibraryPlaylists(boolean userTriggered, boolean backgroundRefresh) {
        fetchLibraryPlaylists(userTriggered, backgroundRefresh, false);
    }
    private void fetchLibraryPlaylists(boolean userTriggered, boolean backgroundRefresh, boolean forceRefresh) {
        if (libraryFetchInFlight && !forceRefresh) {
            return;
        }
        libraryFetchInFlight = true;
        if (TextUtils.isEmpty(youtubeAccessToken)) {
            Log.w(TAG_STREAMING, "playlist_fetch skipped: empty_token");
            libraryFetchInFlight = false;
            boolean hasLocalContent = LocalFilesStore.isEnabled(requireContext()) ||
                    !CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext()).isEmpty() ||
                    !FavoritesPlaylistStore.loadFavorites(requireContext()).isEmpty();
            if (restoreLibraryFromPersistentCache() || hasLocalContent) {
                if (!backgroundRefresh) {
                    setLibraryLoading(false, "Modo offline: biblioteca guardada.");
                }
                if (activeScreen == ScreenMode.LIBRARY) {
                    renderLibraryResults();
                }
                updateYoutubeButtonLabel();
                return;
            }
            setLibraryLoading(false, "Conecta YouTube para cargar tu biblioteca.");
 
            return;
        }
        if (!isNetworkAvailable()) {
            Log.w(TAG_STREAMING, "playlist_fetch network_unavailable");
            libraryFetchInFlight = false;
            boolean hasLocalContent = LocalFilesStore.isEnabled(requireContext()) ||
                    !CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext()).isEmpty() ||
                    !FavoritesPlaylistStore.loadFavorites(requireContext()).isEmpty();
            if (restoreLibraryFromPersistentCache() || !libraryTracks.isEmpty() || hasLocalContent) {
                if (!backgroundRefresh) {
                    setLibraryLoading(false, "Sin internet. Mostrando biblioteca guardada.");
                }
                if (activeScreen == ScreenMode.LIBRARY) {
                    renderLibraryResults();
                }
                return;
            }
            setLibraryLoading(false, "Sin internet y sin cache de biblioteca.");
            return;
        }
        if (!backgroundRefresh) {
            setLibraryLoading(true, "Cargando playlists de tu biblioteca...");
        }
        Log.i(TAG_STREAMING, "playlist_fetch start limit=" + LIBRARY_PLAYLIST_FETCH_LIMIT);
        youTubeMusicService.fetchMyPlaylists(youtubeAccessToken, LIBRARY_PLAYLIST_FETCH_LIMIT, new YouTubeMusicService.PlaylistsCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistResult> playlists) {
                libraryFetchInFlight = false;
                if (!isAdded()) {
                    return;
                }
                Log.i(TAG_STREAMING, "playlist_fetch success playlists=" + playlists.size());
                libraryTracks.clear();
                libraryTracks.addAll(mapPlaylistsToLibraryTracks(playlists));
                displayLibraryDirty = true;
                cacheLibraryForCurrentAccount(libraryTracks);
                // Prefetch tracks on initial load. On pull-to-refresh, only prefetch
                // if some playlists are still missing grid images (first-install catch-up).
                if (!forceRefresh) {
                    prefetchLibraryTracksForOffline(libraryTracks, youtubeAccessToken);
                } else if (hasPlaylistsWithoutGrid(libraryTracks)) {
                    prefetchLibraryTracksForOffline(libraryTracks, youtubeAccessToken);
                }
                lastLibrarySyncAtMs = System.currentTimeMillis();
                youtubeAuthRefreshInProgress = false;
                if (!backgroundRefresh) {
                    setLibraryLoading(false, "");
                }
                if (forceRefresh) {
                    setLibraryPullRefreshState(false);
                    suppressGridOverlay = false;
                    // On pull-to-refresh: resume stalled offline downloads if none are active
                    maybeResumeStarledOfflineDownloads();
                }
                if (activeScreen == ScreenMode.LIBRARY) {
                    renderLibraryResults();
                }
            }
            @Override
            public void onError(@NonNull String error) {
                libraryFetchInFlight = false;
                if (!isAdded()) {
                    return;
                }
                Log.e(TAG_STREAMING, "playlist_fetch error: " + error);
                if (isLikelyNetworkError(error)) {
                    boolean restored = restoreLibraryFromPersistentCache();
                    boolean hasLocalContent = LocalFilesStore.isEnabled(requireContext()) ||
                            !CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext()).isEmpty() ||
                            !FavoritesPlaylistStore.loadFavorites(requireContext()).isEmpty();
                    if (restored || !libraryTracks.isEmpty() || hasLocalContent) {
                        youtubeAuthRefreshInProgress = false;
                        if (!backgroundRefresh) {
                            setLibraryLoading(false, "Sin internet. Mostrando biblioteca guardada.");
                        }
                        if (activeScreen == ScreenMode.LIBRARY) {
                            renderLibraryResults();
                        }
                        return;
                    }
                }
                if (shouldRetryYouTubeAuth(error) && !youtubeAuthRefreshInProgress) {
                    if (streamingOauthCompleted) {
                        youtubeAuthRefreshInProgress = true;
                        youtubeAccessToken = "";
                        clearCachedToken();
                        updateYoutubeButtonLabel();
                        requestYoutubeAccessTokenFromPrimaryAccountAfterWebSession(false, backgroundRefresh, forceRefresh);
                        return;
                    }
                }
                youtubeAuthRefreshInProgress = false;
                if (shouldInvalidateStoredYoutubeToken(error)) {
                    youtubeAccessToken = "";
                    streamingOauthCompleted = false;
                    clearCachedToken();
                    updateYoutubeButtonLabel();
                }
                if (!backgroundRefresh || libraryTracks.isEmpty()) {
                    setLibraryLoading(false, "Error al cargar biblioteca: " + error);
                }
                if (forceRefresh) {
                    setLibraryPullRefreshState(false);
                    suppressGridOverlay = false;
                }
            }
        });
    }
    public void refreshLibraryUi() {
        if (!isAdded()) return;
        // Invalidate favorites grid/tracks cache so fresh data from cloud sync is used
        playlistGridUrlsCache.remove(FavoritesPlaylistStore.PLAYLIST_ID);
        synchronized (cachedPlaylistTracksLock) {
            cachedPlaylistTracksById.remove(FavoritesPlaylistStore.PLAYLIST_ID);
            cachedPlaylistTracksUpdatedAtById.remove(FavoritesPlaylistStore.PLAYLIST_ID);
        }
        renderLibraryResults();
    }
    private void renderLibraryResults() {
        displayLibraryDirty = true;
        libraryInlinePendingReveal = false;
        String inlineQuery = getLibraryInlineQuery();
        if (!TextUtils.isEmpty(inlineQuery)) {
            showLibraryInlineBlankState();
            return;
        }
        featuredTrack = null;
        llFeaturedResult.setVisibility(View.GONE);
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        llMusicStateVisible(false);
        tracks.clear();
        List<YouTubeMusicService.TrackResult> displayList = buildDisplayLibrary();
        List<YouTubeMusicService.TrackResult> visibleLibraryTracks = displayList;
        boolean isOfflineMode = isAdded() && requireContext()
                .getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Activity.MODE_PRIVATE)
                .getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false);
        if (isOfflineMode) {
            Context offlineCtx = requireContext().getApplicationContext();
            visibleLibraryTracks = new ArrayList<>();
            for (YouTubeMusicService.TrackResult playlist : displayList) {
                if (playlist == null || !"playlist".equals(playlist.resultType)) {
                    visibleLibraryTracks.add(playlist);
                    continue;
                }
                String pid = playlist.contentId == null ? "" : playlist.contentId.trim();
                if (pid.isEmpty()) continue;
                if (LocalFilesStore.PLAYLIST_ID.equals(pid)) {
                    visibleLibraryTracks.add(playlist);
                    continue;
                }
                ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(offlineCtx, pid);
                boolean hasDownload = false;
                for (CachedPlaylistTrack t : cachedTracks) {
                    if (t != null && !TextUtils.isEmpty(t.videoId)
                            && OfflineAudioStore.hasOfflineAudio(offlineCtx, t.videoId)) {
                        hasDownload = true;
                        break;
                    }
                }
                if (hasDownload) {
                    visibleLibraryTracks.add(playlist);
                }
            }
        }
        adapter.submitResults(new ArrayList<>(visibleLibraryTracks));
        if (visibleLibraryTracks.isEmpty()) {
            tvMusicState.setText("");
            showLibraryEmptyState();
        } else {
            tvMusicState.setText("");
        }
        if (rvMusicResults != null) {
            rvMusicResults.animate().cancel();
            scrollToTop();
            if (rvMusicResults.getVisibility() != View.VISIBLE) {
                rvMusicResults.setAlpha(0f);
                rvMusicResults.setVisibility(View.VISIBLE);
                rvMusicResults.animate().alpha(1f).setDuration(180L).start();
            }
        }
        maybeEnqueueNextOfflineAutoPlaylist();
        maybeAppendCloudPlaylists();
    }
    private void showLibraryEmptyState() {
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.GONE);
        llMusicStateVisible(false);
        rvMusicResults.setVisibility(View.GONE);
        llLibraryEmptyState.setVisibility(View.VISIBLE);
        updateYoutubeButtonLabel();
    }
    @NonNull
    private String getLibraryInlineQuery() {
        return "";
    }
    private void applyLibraryInlineSongSearch() {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        String query = getLibraryInlineQuery();
        if (TextUtils.isEmpty(query)) {
            cancelPendingLibraryInlineSearch();
            lastLibraryInlineDispatchedQuery = "";
            libraryInlineManualModeActive = false;
            renderLibraryResults();
            return;
        }
        libraryInlineManualModeActive = true;
        showLibraryInlineBlankState();
    }
    private void submitLibraryInlineSongSearch() {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        String query = getLibraryInlineQuery();
        if (TextUtils.isEmpty(query)) {
            libraryInlineManualModeActive = false;
            renderLibraryResults();
            return;
        }
        libraryInlineManualModeActive = true;
        showLibraryInlineLoadingState();
        triggerOnlineMusicSearchFromLibrary(query);
    }
    private void showLibraryInlineBlankState() {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        cancelPendingLibraryInlineSearch();
        libraryInlinePendingReveal = false;
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        featuredTrack = null;
        llFeaturedResult.setVisibility(View.GONE);
        tracks.clear();
        if (adapter != null) {
            adapter.submitResults(new ArrayList<>());
        }
        if (rvMusicResults != null) {
            rvMusicResults.animate().cancel();
            rvMusicResults.setAlpha(0f);
            rvMusicResults.setVisibility(View.GONE);
        }
        llMusicStateVisible(false);
        tvMusicState.setText("");
    }
    private void showLibraryInlineLoadingState() {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        featuredTrack = null;
        llFeaturedResult.setVisibility(View.GONE);
        tracks.clear();
        if (adapter != null) {
            adapter.submitResults(new ArrayList<>());
        }
        if (rvMusicResults != null) {
            rvMusicResults.animate().cancel();
            rvMusicResults.setAlpha(0f);
            rvMusicResults.setVisibility(View.GONE);
        }
        libraryInlinePendingReveal = true;
        llMusicStateVisible(true);
        tvMusicState.setText("Buscando musica en YouTube...");
    }
    private void revealLibraryInlineResultsWithFade() {
        if (rvMusicResults == null) {
            return;
        }
        if (!libraryInlinePendingReveal) {
            rvMusicResults.setVisibility(View.VISIBLE);
            rvMusicResults.setAlpha(1f);
            return;
        }
        libraryInlinePendingReveal = false;
        rvMusicResults.animate().cancel();
        rvMusicResults.setAlpha(0f);
        rvMusicResults.setVisibility(View.VISIBLE);
        rvMusicResults.animate()
                .alpha(1f)
                .setDuration(210L)
                .start();
    }
    private void cancelPendingLibraryInlineSearch() {
        if (pendingLibraryInlineSearchRunnable == null) {
            return;
        }
        mainHandler.removeCallbacks(pendingLibraryInlineSearchRunnable);
        pendingLibraryInlineSearchRunnable = null;
    }
    private void scheduleLibraryInlineOnlineSearch(@NonNull String query) {
        cancelPendingLibraryInlineSearch();
        pendingLibraryInlineSearchRunnable = () -> {
            pendingLibraryInlineSearchRunnable = null;
            if (!isAdded() || activeScreen != ScreenMode.LIBRARY) {
                return;
            }
            String latestQuery = getLibraryInlineQuery();
            if (TextUtils.isEmpty(latestQuery)) {
                lastLibraryInlineDispatchedQuery = "";
                renderLibraryResults();
                return;
            }
            if (TextUtils.equals(latestQuery, lastLibraryInlineDispatchedQuery)) {
                return;
            }
            if (latestQuery.length() < LIBRARY_INLINE_ONLINE_MIN_QUERY_CHARS) {
                lastLibraryInlineDispatchedQuery = latestQuery;
                renderLibrarySongSearchResults(latestQuery);
                return;
            }
            lastLibraryInlineDispatchedQuery = latestQuery;
            triggerOnlineMusicSearchFromLibrary(latestQuery);
        };
        mainHandler.postDelayed(pendingLibraryInlineSearchRunnable, LIBRARY_INLINE_SEARCH_DEBOUNCE_MS);
    }

    private void triggerOnlineMusicSearchFromLibrary(@NonNull String query) {
        if (!isAdded()) {
            return;
        }
        lastLibraryInlineDispatchedQuery = query;
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        llMusicStateVisible(true);
        renderLibrarySongSearchResults(query);
    }

    private void renderLibrarySongSearchResults(@NonNull String query) {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        if (libraryTracks.isEmpty()) {
            restoreLibraryFromPersistentCache();
        }
        List<YouTubeMusicService.TrackResult> resultTracks = findSongsAcrossCachedPlaylists(query);
        tracks.clear();
        if (resultTracks.isEmpty()) {
            featuredTrack = null;
            llFeaturedResult.setVisibility(View.GONE);
        } else {
            featuredTrack = resultTracks.get(0);
            bindFeaturedTrack(featuredTrack);
            llFeaturedResult.setVisibility(View.VISIBLE);
            if (resultTracks.size() > 1) {
                tracks.addAll(resultTracks.subList(1, resultTracks.size()));
            }
        }
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        llMusicStateVisible(true);
        if (adapter != null) {
            adapter.submitResults(new ArrayList<>(tracks));
        }
        revealLibraryInlineResultsWithFade();
        if (resultTracks.isEmpty()) {
            tvMusicState.setText("Sin coincidencias en playlists guardadas para: " + query);
        } else {
            tvMusicState.setText(String.format(
                    Locale.getDefault(),
                    "%d canciones encontradas en tus playlists.",
                    resultTracks.size()
            ));
        }
    }
    private void renderLibrarySongSearchResultsWithPreloadedData(
            @NonNull String query,
            @NonNull List<YouTubeMusicService.TrackResult> preloadedResults
    ) {
        if (activeScreen != ScreenMode.LIBRARY) {
            return;
        }
        tracks.clear();
        if (preloadedResults.isEmpty()) {
            featuredTrack = null;
            llFeaturedResult.setVisibility(View.GONE);
        } else {
            featuredTrack = preloadedResults.get(0);
            bindFeaturedTrack(featuredTrack);
            llFeaturedResult.setVisibility(View.VISIBLE);
            if (preloadedResults.size() > 1) {
                tracks.addAll(preloadedResults.subList(1, preloadedResults.size()));
            }
        }
        if (tvLibraryTitle != null) tvLibraryTitle.setVisibility(View.VISIBLE);

        llLibraryEmptyState.setVisibility(View.GONE);
        updateLibraryInlineClearButton();
        llMusicStateVisible(true);
        if (adapter != null) {
            adapter.submitResults(new ArrayList<>(tracks));
        }
        revealLibraryInlineResultsWithFade();
        if (preloadedResults.isEmpty()) {
            tvMusicState.setText("Sin coincidencias en playlists guardadas para: " + query);
        } else {
            tvMusicState.setText(String.format(
                    Locale.getDefault(),
                    "%d canciones encontradas en tus playlists.",
                    preloadedResults.size()
            ));
        }
    }
    @NonNull
    private List<YouTubeMusicService.TrackResult> findSongsAcrossCachedPlaylists(@NonNull String query) {
        List<YouTubeMusicService.TrackResult> result = new ArrayList<>();
        if (!isAdded()) {
            return result;
        }
        String[] queryTokens = extractSearchTokens(query);
        if (queryTokens.length == 0) {
            return result;
        }
        Set<String> seenVideoIds = new HashSet<>();
        List<YouTubeMusicService.TrackResult> allPlaylists = buildDisplayLibrary();
        for (YouTubeMusicService.TrackResult playlist : allPlaylists) {
            if (playlist == null || !"playlist".equals(playlist.resultType)) {
                continue;
            }
            String playlistId = playlist.contentId == null ? "" : playlist.contentId.trim();
            if (playlistId.isEmpty()) {
                continue;
            }
            String playlistTitle = TextUtils.isEmpty(playlist.title) ? "Playlist" : playlist.title;
            ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(playlistId);
            if (cachedTracks.isEmpty()) {
                continue;
            }
            for (CachedPlaylistTrack cachedTrack : cachedTracks) {
                if (cachedTrack == null || TextUtils.isEmpty(cachedTrack.videoId)) {
                    continue;
                }
                String normalizedTitle = normalizeForFilter(cachedTrack.title);
                String normalizedArtist = normalizeForFilter(cachedTrack.artist);
                String normalizedPlaylistTitle = normalizeForFilter(playlistTitle);
                boolean matches = matchesOrderedTokens(normalizedTitle, queryTokens)
                        || matchesOrderedTokens(normalizedArtist, queryTokens)
                        || matchesOrderedTokens(normalizedPlaylistTitle, queryTokens)
                        || matchesOrderedTokens(
                        (normalizedTitle + " " + normalizedArtist + " " + normalizedPlaylistTitle).trim(),
                        queryTokens
                );
                if (!matches || !seenVideoIds.add(cachedTrack.videoId)) {
                    continue;
                }
                String displayTitle = TextUtils.isEmpty(cachedTrack.title) ? "Cancion" : cachedTrack.title;
                String subtitle = TextUtils.isEmpty(cachedTrack.artist)
                        ? "Playlist: " + playlistTitle
                        : cachedTrack.artist + " \u2022 " + playlistTitle;
                result.add(new YouTubeMusicService.TrackResult(
                        "video",
                        cachedTrack.videoId,
                        displayTitle,
                        subtitle,
                        cachedTrack.imageUrl
                ));
                if (result.size() >= LIBRARY_INLINE_SEARCH_MAX_RESULTS) {
                    return result;
                }
            }
        }
        sortSearchResultsByBestMatch(result, query);
        return result;
    }
    private void updateLibraryInlineClearButton() {
    }
    private void llMusicStateVisible(boolean visible) {
        if (llMusicState != null) {
            llMusicState.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    private void setLibraryLoading(boolean loading, @NonNull String stateMessage) {
        loadingLibrary = loading;
        if (btnYoutubeLogin != null) {
            btnYoutubeLogin.setEnabled(!loading);
        }
        refreshGlobalLoadingIndicator();
        if (!loading) {
            setLibraryPullRefreshState(false);
        }
        if (!stateMessage.isEmpty()) {
            tvMusicState.setText(stateMessage);
        }
    }
    private void refreshGlobalLoadingIndicator() {
        if (progressMusic == null) {
            return;
        }
        boolean visible = loadingLibrary;
        progressMusic.animate().cancel();
        if (visible) {
            if (progressMusic.getVisibility() != View.VISIBLE) {
                progressMusic.setAlpha(0f);
                progressMusic.setVisibility(View.VISIBLE);
            }
            progressMusic.animate()
                    .alpha(1f)
                    .setDuration(160L)
                    .start();
            return;
        }
        if (progressMusic.getVisibility() != View.VISIBLE) {
            return;
        }
        progressMusic.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction(() -> {
                    if (progressMusic != null) {
                        progressMusic.setVisibility(View.GONE);
                    }
                })
                .start();
    }
    private void showLibraryGridOverlay(int pendingCount) {
        if (pendingCount <= 0 || flLibraryLoadingOverlay == null || suppressGridOverlay) return;
        libraryGridPendingCount = pendingCount;
        libraryGridOverlayActive = true;
        flLibraryLoadingOverlay.setAlpha(1f);
        flLibraryLoadingOverlay.setVisibility(View.VISIBLE);
        // Safety timeout: dismiss after 5 seconds even if prefetches haven't finished
        mainHandler.postDelayed(this::dismissLibraryGridOverlay, 5000L);
    }
    private void onLibraryGridPrefetchComplete() {
        if (!libraryGridOverlayActive) return;
        libraryGridPendingCount--;
        if (libraryGridPendingCount <= 0) {
            dismissLibraryGridOverlay();
        }
    }
    private void dismissLibraryGridOverlay() {
        if (!libraryGridOverlayActive) return;
        libraryGridOverlayActive = false;
        libraryGridPendingCount = 0;
        mainHandler.removeCallbacks(this::dismissLibraryGridOverlay);
        mainHandler.post(() -> {
            if (!isAdded()) return;
            // Refresh adapter so grids appear with fade
            if (activeScreen == ScreenMode.LIBRARY && adapter != null) {
                List<YouTubeMusicService.TrackResult> display = buildDisplayLibrary();
                adapter.submitResults(new ArrayList<>(display));
            }
            if (flLibraryLoadingOverlay != null) {
                flLibraryLoadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(200L)
                        .withEndAction(() -> {
                            if (flLibraryLoadingOverlay != null) {
                                flLibraryLoadingOverlay.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        });
    }
    private void updateYoutubeButtonLabel() {
        if (!isAdded() || btnYoutubeLogin == null) {
            return;
        }
        // Always hide — web session activity auto-launches when needed
        btnYoutubeLogin.setVisibility(View.GONE);
    }
    private boolean hasLoggedInStreamingAccount() {
        return streamingOauthCompleted;
    }
    private boolean shouldRetryYouTubeAuth(@NonNull String error) {
        String normalized = normalizeForFilter(error);
        if (isYouTubeQuotaOrConfigIssue(normalized)) {
            return false;
        }
        return containsAny(normalized,
                "token de youtube expirado",
                "token de youtube invalido",
                "insufficientpermissions",
                "insufficient permission",
                "not properly authorized",
                "youtube.readonly",
                "authorizationrequired");
    }
    private boolean shouldInvalidateStoredYoutubeToken(@NonNull String error) {
        String normalized = normalizeForFilter(error);
        return containsAny(normalized,
                "token de youtube expirado",
                "token de youtube invalido",
                "insufficientpermissions",
                "insufficient permission",
                "not properly authorized",
                "youtube.readonly",
                "authorizationrequired");
    }
    private boolean isYouTubeQuotaOrConfigIssue(@NonNull String normalizedError) {
        return containsAny(normalizedError,
                "quota",
                "rate limit",
                "dailylimit",
                "daily limit",
                "accessnotconfigured",
                "api no esta habilitada",
                "api has not been used",
                "api is disabled",
                "service disabled");
    }
    private void restoreCachedStreamingSessionState() {
        if (!isAdded()) {
            streamingOauthCompleted = false;
            return;
        }
        SharedPreferences playerPrefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        streamingOauthAccountEmail = normalizeAccountEmail(
                playerPrefs.getString(PREF_LAST_STREAMING_OAUTH_ACCOUNT_EMAIL, "")
        );
        String accountKey = resolveAccountKey();
        String persistedToken = playerPrefs.getString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, "");
        long persistedTokenUpdatedAt = playerPrefs.getLong(PREF_LAST_YOUTUBE_ACCESS_TOKEN_UPDATED_AT, 0L);
        if (!TextUtils.isEmpty(accountKey) && !TextUtils.isEmpty(persistedToken)) {
            tokenCacheAccountKey = accountKey;
            cachedYoutubeAccessToken = persistedToken.trim();
            tokenCacheUpdatedAtMs = persistedTokenUpdatedAt > 0L
                    ? persistedTokenUpdatedAt
                    : System.currentTimeMillis();
            if (hasValidCachedToken(accountKey)) {
                youtubeAccessToken = cachedYoutubeAccessToken;
            }
        }
        hydrateCachedSessionState();
        String webCookie = playerPrefs.getString(PREF_LAST_YOUTUBE_WEB_COOKIE, "");
        boolean hasWebSession = !TextUtils.isEmpty(webCookie == null ? "" : webCookie.trim());
        // Pasar la cookie a InnertubeResolver para autenticar peticiones de
        // resolución y playback (evita LOGIN_REQUIRED y CDN 403 sin PO Token).
        InnertubeResolver.setAuthCookies(webCookie);
        boolean hasCachedLibrary = !libraryTracks.isEmpty();
        streamingOauthCompleted = hasWebSession || hasCachedLibrary || !TextUtils.isEmpty(youtubeAccessToken);
        Log.i(
            TAG_STREAMING,
            "restore_session web=" + hasWebSession
                + " cachedLibrary=" + hasCachedLibrary
                + " token=" + !TextUtils.isEmpty(youtubeAccessToken)
                + " oauthAccount=" + streamingOauthAccountEmail
        );
    }
    private void hydrateCachedSessionState() {
        String accountKey = resolveAccountKey();
        if (hasValidCachedToken(accountKey)) {
            youtubeAccessToken = cachedYoutubeAccessToken;
        }
        if (hasValidLibraryCache(accountKey) && libraryTracks.isEmpty()) {
            libraryTracks.clear();
            libraryTracks.addAll(new ArrayList<>(LIBRARY_CACHE));
            displayLibraryDirty = true;
            return;
        }
        List<YouTubeMusicService.TrackResult> persisted = loadPersistedLibrary(accountKey);
        if (!persisted.isEmpty() && libraryTracks.isEmpty()) {
            libraryTracks.clear();
            libraryTracks.addAll(persisted);
            displayLibraryDirty = true;
            LIBRARY_CACHE.clear();
            LIBRARY_CACHE.addAll(libraryTracks);
            libraryCacheAccountKey = accountKey;
            libraryCacheUpdatedAtMs = getPersistedLibraryUpdatedAt(accountKey);
        }
    }
    private boolean restoreLibraryFromPersistentCache() {
        String accountKey = resolveAccountKey();
        if (TextUtils.isEmpty(accountKey)) {
            return false;
        }
        List<YouTubeMusicService.TrackResult> persisted = loadPersistedLibrary(accountKey);
        if (persisted.isEmpty()) {
            return false;
        }
        libraryTracks.clear();
        libraryTracks.addAll(persisted);
        displayLibraryDirty = true;
        LIBRARY_CACHE.clear();
        LIBRARY_CACHE.addAll(libraryTracks);
        libraryCacheAccountKey = accountKey;
        libraryCacheUpdatedAtMs = getPersistedLibraryUpdatedAt(accountKey);
        return true;
    }
    @NonNull
    private String resolveAccountKey() {
        String appEmail = getPrimaryAppGoogleEmail();
        if (!TextUtils.isEmpty(appEmail)) {
            return appEmail.toLowerCase(Locale.US).trim();
        }
        if (!isAdded()) {
            return "";
        }
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
        if (account != null && !TextUtils.isEmpty(account.getEmail())) {
            return account.getEmail().toLowerCase(Locale.US).trim();
        }
        String persistedKey = resolvePersistedLibraryAccountKey();
        return persistedKey == null ? "" : persistedKey;
    }
    @Nullable
    private StreamingAccountCandidate resolveStreamingOAuthAccountCandidate() {
        LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
        addStreamingAccountCandidate(candidates, streamingOauthAccountEmail, "session_cached_oauth");
        addStreamingAccountCandidate(candidates, getPrimaryAppGoogleEmail(), "firebase_primary");
        if (isAdded()) {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
            if (account != null) {
                addStreamingAccountCandidate(candidates, account.getEmail(), "google_signin_last");
            }
            SharedPreferences playerPrefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
            addStreamingAccountCandidate(
                    candidates,
                    playerPrefs.getString(PREF_LAST_STREAMING_OAUTH_ACCOUNT_EMAIL, ""),
                    "prefs_oauth"
            );
        }
        addStreamingAccountCandidate(candidates, resolvePersistedLibraryAccountKey(), "library_cache_key");
        addStreamingAccountCandidate(candidates, tokenCacheAccountKey, "token_cache_key");
        if (candidates.isEmpty()) {
            return null;
        }
        Map.Entry<String, String> first = candidates.entrySet().iterator().next();
        return new StreamingAccountCandidate(first.getKey(), first.getValue());
    }
    private void addStreamingAccountCandidate(
            @NonNull LinkedHashMap<String, String> candidates,
            @Nullable String email,
            @NonNull String source
    ) {
        String normalized = normalizeAccountEmail(email);
        if (TextUtils.isEmpty(normalized) || candidates.containsKey(normalized)) {
            return;
        }
        candidates.put(normalized, source);
    }
    @NonNull
    private String resolvePersistedLibraryAccountKey() {
        if (!isAdded()) {
            return "";
        }
        SharedPreferences prefs = getCachePrefs();
        String direct = prefs.getString(PREF_LAST_STREAMING_ACCOUNT_KEY, "");
        if (!TextUtils.isEmpty(direct)) {
            return direct.trim().toLowerCase(Locale.US);
        }
        Map<String, ?> all = prefs.getAll();
        long latest = 0L;
        String latestAccountKey = "";
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (TextUtils.isEmpty(key) || !key.startsWith(PREF_LIBRARY_UPDATED_AT_PREFIX)) {
                continue;
            }
            String candidate = key.substring(PREF_LIBRARY_UPDATED_AT_PREFIX.length()).trim();
            if (candidate.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            long updatedAt = value instanceof Number ? ((Number) value).longValue() : 0L;
            if (updatedAt >= latest) {
                latest = updatedAt;
                latestAccountKey = candidate;
            }
        }
        if (!latestAccountKey.isEmpty()) {
            prefs.edit().putString(PREF_LAST_STREAMING_ACCOUNT_KEY, latestAccountKey).apply();
        }
        return latestAccountKey;
    }
    private boolean hasValidCachedToken(@NonNull String accountKey) {
        if (TextUtils.isEmpty(accountKey) || TextUtils.isEmpty(cachedYoutubeAccessToken)) {
            return false;
        }
        if (!accountKey.equals(tokenCacheAccountKey)) {
            return false;
        }
        return (System.currentTimeMillis() - tokenCacheUpdatedAtMs) < TOKEN_CACHE_TTL_MS;
    }
    private boolean hasValidLibraryCache(@NonNull String accountKey) {
        if (TextUtils.isEmpty(accountKey) || LIBRARY_CACHE.isEmpty()) {
            return false;
        }
        if (!accountKey.equals(libraryCacheAccountKey)) {
            return false;
        }
        return (System.currentTimeMillis() - libraryCacheUpdatedAtMs) < LIBRARY_CACHE_TTL_MS;
    }
    private void cacheTokenForCurrentAccount(@NonNull String token) {
        String accountKey = resolveActiveStreamingAccountKey();
        if (TextUtils.isEmpty(accountKey)) {
            return;
        }
        tokenCacheAccountKey = accountKey;
        cachedYoutubeAccessToken = token;
        tokenCacheUpdatedAtMs = System.currentTimeMillis();
        rememberStreamingAccountKey(accountKey);
        if (isAdded()) {
            requireContext()
                    .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, token)
                    .putLong(PREF_LAST_YOUTUBE_ACCESS_TOKEN_UPDATED_AT, tokenCacheUpdatedAtMs)
                    .putString(PREF_LAST_STREAMING_OAUTH_ACCOUNT_EMAIL, accountKey)
                    .apply();
        }
    }
    private void clearCachedToken() {
        cachedYoutubeAccessToken = "";
        tokenCacheAccountKey = "";
        tokenCacheUpdatedAtMs = 0L;
        if (isAdded()) {
            requireContext()
                    .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                    .edit()
                    .remove(PREF_LAST_YOUTUBE_ACCESS_TOKEN)
                    .remove(PREF_LAST_YOUTUBE_ACCESS_TOKEN_UPDATED_AT)
                    .apply();
        }
    }
    private void cacheLibraryForCurrentAccount(@NonNull List<YouTubeMusicService.TrackResult> source) {
        String accountKey = resolveActiveStreamingAccountKey();
        if (TextUtils.isEmpty(accountKey)) {
            return;
        }
        LIBRARY_CACHE.clear();
        LIBRARY_CACHE.addAll(source);
        libraryCacheAccountKey = accountKey;
        libraryCacheUpdatedAtMs = System.currentTimeMillis();
        rememberStreamingAccountKey(accountKey);
        persistLibrary(accountKey, source, libraryCacheUpdatedAtMs);
    }
    @NonNull
    private String resolveActiveStreamingAccountKey() {
        String oauthEmail = normalizeAccountEmail(streamingOauthAccountEmail);
        if (!TextUtils.isEmpty(oauthEmail)) {
            return oauthEmail;
        }
        return resolveAccountKey();
    }
    private void rememberStreamingOauthAccountEmail(@Nullable String email) {
        String normalized = normalizeAccountEmail(email);
        streamingOauthAccountEmail = normalized;
        if (!isAdded()) {
            return;
        }
        requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_STREAMING_OAUTH_ACCOUNT_EMAIL, normalized)
                .apply();
    }
    private static final class StreamingAccountCandidate {
        @NonNull
        final String email;
        @NonNull
        final String source;
        private StreamingAccountCandidate(@NonNull String email, @NonNull String source) {
            this.email = email;
            this.source = source;
        }
    }
    private void rememberStreamingAccountKey(@NonNull String accountKey) {
        if (!isAdded() || TextUtils.isEmpty(accountKey)) {
            return;
        }
        getCachePrefs().edit()
                .putString(PREF_LAST_STREAMING_ACCOUNT_KEY, accountKey)
                .apply();
    }
    @NonNull
    private SharedPreferences getCachePrefs() {
        return getCachePrefs(requireContext());
    }
    @NonNull
    private SharedPreferences getCachePrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE);
    }
    private void persistLibrary(
            @NonNull String accountKey,
            @NonNull List<YouTubeMusicService.TrackResult> data,
            long updatedAt
    ) {
        try {
            JSONArray array = new JSONArray();
            for (YouTubeMusicService.TrackResult item : data) {
                JSONObject obj = new JSONObject();
                obj.put("resultType", item.resultType);
                obj.put("contentId", item.contentId);
                obj.put("title", item.title);
                obj.put("subtitle", item.subtitle);
                obj.put("thumbnailUrl", item.thumbnailUrl);
                array.put(obj);
            }
            getCachePrefs().edit()
                    .putLong(PREF_LIBRARY_UPDATED_AT_PREFIX + accountKey, updatedAt)
                    .putString(PREF_LIBRARY_DATA_PREFIX + accountKey, array.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }
    private long getPersistedLibraryUpdatedAt(@NonNull String accountKey) {
        if (TextUtils.isEmpty(accountKey) || !isAdded()) {
            return 0L;
        }
        return getCachePrefs().getLong(PREF_LIBRARY_UPDATED_AT_PREFIX + accountKey, 0L);
    }
    @NonNull
    private List<YouTubeMusicService.TrackResult> loadPersistedLibrary(@NonNull String accountKey) {
        List<YouTubeMusicService.TrackResult> result = new ArrayList<>();
        if (TextUtils.isEmpty(accountKey) || !isAdded()) {
            return result;
        }
        long updatedAt = getPersistedLibraryUpdatedAt(accountKey);
        if (updatedAt <= 0L) {
            return result;
        }
        String raw = getCachePrefs().getString(PREF_LIBRARY_DATA_PREFIX + accountKey, "");
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
                String resultType = obj.optString("resultType", "playlist").trim();
                String contentId = obj.optString("contentId", "").trim();
                String title = obj.optString("title", "").trim();
                String subtitle = obj.optString("subtitle", "").trim();
                String thumbnailUrl = obj.optString("thumbnailUrl", "").trim();
                if (contentId.isEmpty() || title.isEmpty()) {
                    continue;
                }
                result.add(new YouTubeMusicService.TrackResult(
                        resultType,
                        contentId,
                        title,
                        subtitle,
                        thumbnailUrl
                ));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }
    private boolean hasPlaylistsWithoutGrid(@NonNull List<YouTubeMusicService.TrackResult> playlists) {
        for (YouTubeMusicService.TrackResult item : playlists) {
            if (!"playlist".equals(item.resultType)) continue;
            String pid = item.contentId == null ? "" : item.contentId.trim();
            if (pid.isEmpty()) continue;
            if (FavoritesPlaylistStore.PLAYLIST_ID.equals(pid) || pid.startsWith("custom_")) continue;
            if (!playlistGridUrlsCache.containsKey(pid)) return true;
        }
        return false;
    }
    private void prefetchLibraryTracksForOffline(
            @NonNull List<YouTubeMusicService.TrackResult> playlists,
            @NonNull String accessToken
    ) {
        if (!isAdded() || playlists.isEmpty() || TextUtils.isEmpty(accessToken)) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences cachePrefs = getCachePrefs();

        // First pass: count how many playlists need fetching so we can show overlay
        List<String> playlistIdsToFetch = new ArrayList<>();
        for (YouTubeMusicService.TrackResult item : playlists) {
            if (!"playlist".equals(item.resultType)) continue;
            String pid = item.contentId == null ? "" : item.contentId.trim();
            if (pid.isEmpty()) continue;
            long updatedAt = cachePrefs.getLong(PREF_TRACKS_UPDATED_AT_PREFIX + pid, 0L);
            if (updatedAt > 0L && (now - updatedAt) < OFFLINE_TRACKS_PREFETCH_STALE_MS) continue;
            synchronized (offlinePrefetchInFlight) {
                if (offlinePrefetchInFlight.contains(pid)) continue;
            }
            playlistIdsToFetch.add(pid);
            if (playlistIdsToFetch.size() >= OFFLINE_TRACKS_PREFETCH_LIMIT) break;
        }

        // Show loading overlay if there are playlists to fetch (first install scenario)
        if (!playlistIdsToFetch.isEmpty()) {
            mainHandler.post(() -> showLibraryGridOverlay(playlistIdsToFetch.size()));
        }

        // Second pass: actually dispatch the fetches
        for (String targetPlaylistId : playlistIdsToFetch) {
            synchronized (offlinePrefetchInFlight) {
                offlinePrefetchInFlight.add(targetPlaylistId);
            }
            offlinePrefetchService.fetchPlaylistTracks(
                    accessToken,
                    targetPlaylistId,
                    OFFLINE_TRACKS_PREFETCH_PER_PLAYLIST_LIMIT,
                    new YouTubeMusicService.PlaylistTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistTrackResult> tracks) {
                    if (isAdded() && !tracks.isEmpty()) {
                        cachePlaylistTracksForOffline(targetPlaylistId, tracks);
                        boolean isYtPlaylist = !FavoritesPlaylistStore.PLAYLIST_ID.equals(targetPlaylistId)
                                && !targetPlaylistId.startsWith("custom_");
                        // Eagerly resolve grid URLs from freshly cached tracks so the
                        // 2x2 grid is available immediately when the adapter rebinds.
                        boolean gridJustResolved = false;
                        if (!playlistGridUrlsCache.containsKey(targetPlaylistId)) {
                            playlistGridUrlsCache.remove(targetPlaylistId);
                            List<String> resolved = resolvePlaylistGridUrls(targetPlaylistId);
                            gridJustResolved = resolved.size() >= 4;
                        } else if (!isYtPlaylist) {
                            playlistGridUrlsCache.remove(targetPlaylistId);
                        }
                        final boolean needsFullRebind = gridJustResolved;
                        // Refresh adapter immediately per-playlist as soon as data is cached
                        mainHandler.post(() -> {
                            if (isAdded() && activeScreen == ScreenMode.LIBRARY && adapter != null) {
                                if (needsFullRebind) {
                                    // Grid just became available — force full rebind so DiffUtil
                                    // doesn't skip the ViewHolder that was showing a placeholder.
                                    adapter.notifyDataSetChanged();
                                } else {
                                    List<YouTubeMusicService.TrackResult> display = buildDisplayLibrary();
                                    adapter.submitResults(new ArrayList<>(display));
                                }
                            }
                            onLibraryGridPrefetchComplete();
                        });
                    } else {
                        mainHandler.post(() -> onLibraryGridPrefetchComplete());
                    }
                    markOfflinePrefetchFinished(targetPlaylistId);
                }
                @Override
                public void onError(@NonNull String error) {
                    markOfflinePrefetchFinished(targetPlaylistId);
                    mainHandler.post(() -> onLibraryGridPrefetchComplete());
                }
            });
        }
    }
    private void cachePlaylistTracksForOffline(
            @NonNull String playlistId,
            @NonNull List<YouTubeMusicService.PlaylistTrackResult> tracks
    ) {
        if (!isAdded() || playlistId.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (YouTubeMusicService.PlaylistTrackResult track : tracks) {
                String videoId = track.videoId == null ? "" : track.videoId.trim();
                String title = track.title == null ? "" : track.title.trim();
                if (videoId.isEmpty() || title.isEmpty()) {
                    continue;
                }
                // Normaliza la duración para evitar valores corruptos que rompan la validación offline
                String normalizedDuration = track.duration == null ? "" : track.duration.trim();
                if (normalizedDuration.isEmpty() || normalizedDuration.contains("--")) {
                    normalizedDuration = "";
                }

                JSONObject obj = new JSONObject();
                obj.put("videoId", videoId);
                obj.put("title", title);
                obj.put("artist", track.artist == null ? "" : track.artist);
                obj.put("duration", normalizedDuration);
                obj.put("imageUrl", track.thumbnailUrl == null ? "" : track.thumbnailUrl);
                array.put(obj);
            }
            if (array.length() == 0) {
                return;
            }
            getCachePrefs().edit()
                    .putLong(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId, System.currentTimeMillis())
                    .putBoolean(
                        PREF_TRACKS_FULL_CACHE_PREFIX + playlistId,
                        tracks.size() < OFFLINE_TRACKS_PREFETCH_PER_PLAYLIST_LIMIT
                    )
                    .putString(PREF_TRACKS_DATA_PREFIX + playlistId, array.toString())
                    .apply();
            synchronized (cachedPlaylistTracksLock) {
                cachedPlaylistTracksById.remove(playlistId);
                cachedPlaylistTracksUpdatedAtById.remove(playlistId);
            }
            if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(playlistId)) {
                FavoritesPlaylistStore.invalidateLikedMusicCache();
            }
        } catch (Exception ignored) {
        }
    }
    private void markOfflinePrefetchFinished(@NonNull String playlistId) {
        synchronized (offlinePrefetchInFlight) {
            offlinePrefetchInFlight.remove(playlistId);
        }
    }
    private boolean trackMatchesOrderedQuery(
            @NonNull YouTubeMusicService.TrackResult track,
            @NonNull String query
    ) {
        String[] queryTokens = extractSearchTokens(query);
        if (queryTokens.length == 0) {
            return true;
        }
        String normalizedTitle = normalizeForFilter(track.title);
        String normalizedSubtitle = normalizeForFilter(track.subtitle);
        if (matchesOrderedTokens(normalizedTitle, queryTokens)
                || matchesOrderedTokens(normalizedSubtitle, queryTokens)) {
            return true;
        }
        return matchesOrderedTokens((normalizedTitle + " " + normalizedSubtitle).trim(), queryTokens);
    }
    @NonNull
    private String[] extractSearchTokens(@Nullable String value) {
        String normalized = normalizeForFilter(value);
        if (TextUtils.isEmpty(normalized)) {
            return new String[0];
        }
        String[] rawTokens = normalized.split("[^\\p{L}\\p{N}]+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (!TextUtils.isEmpty(token)) {
                tokens.add(token);
            }
        }
        return tokens.toArray(new String[0]);
    }
    private boolean matchesOrderedTokens(
            @NonNull String normalizedSource,
            @NonNull String[] queryTokens
    ) {
        if (queryTokens.length == 0) {
            return true;
        }
        if (TextUtils.isEmpty(normalizedSource)) {
            return false;
        }
        String[] sourceTokens = normalizedSource.split("[^\\p{L}\\p{N}]+");
        int queryIndex = 0;
        for (String sourceToken : sourceTokens) {
            if (TextUtils.isEmpty(sourceToken)) {
                continue;
            }
            if (sourceToken.startsWith(queryTokens[queryIndex])) {
                queryIndex++;
                if (queryIndex >= queryTokens.length) {
                    return true;
                }
            }
        }
        return false;
    }
    @NonNull
    private String normalizeForFilter(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String cached = normalizedFilterCache.get(value);
        if (cached != null) {
            return cached;
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder builder = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                builder.append(Character.toLowerCase(c));
            }
        }
        String normalized = builder.toString().trim();
        normalizedFilterCache.put(value, normalized);
        return normalized;
    }
    private boolean containsAny(@NonNull String text, @NonNull String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
    private int bucketArtworkDimension(int value) {
        int safe = Math.max(1, value);
        return Math.max(64, ((safe + 63) / 64) * 64);
    }
    private int resolveArtworkTargetSize(int measured, int layoutValue) {
        if (measured > 0) {
            return measured;
        }
        if (layoutValue > 0) {
            return layoutValue;
        }
        return 0;
    }
    private void loadArtworkInto(@NonNull ImageView target, @Nullable String imageUrl) {
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
        ViewGroup.LayoutParams params = target.getLayoutParams();
        int rawWidth = resolveArtworkTargetSize(target.getWidth(), params == null ? 0 : params.width);
        int rawHeight = resolveArtworkTargetSize(target.getHeight(), params == null ? 0 : params.height);
        boolean hasTargetSize = rawWidth > 0 && rawHeight > 0;
        if (hasTargetSize) {
            targetWidth = bucketArtworkDimension(rawWidth);
            targetHeight = bucketArtworkDimension(rawHeight);
        } else {
            targetWidth = Math.round(160 * density); 
            targetHeight = targetWidth;
        }
        if (YouTubeImageProcessor.shouldProcess(safeUrl)) {
            int side = YouTubeImageProcessor.decodeDimensionForSmartCrop(targetWidth);
            targetWidth = side;
            targetHeight = side;
        }
        String signature = safeUrl + "|" + targetWidth + "x" + targetHeight;
        Object previousSignature = target.getTag(R.id.tag_artwork_signature);
        if (previousSignature instanceof String && signature.equals(previousSignature)) {
            return;
        }
        target.setTag(R.id.tag_artwork_signature, signature);
        boolean offlineOnly = !isNetworkAvailable();
        Glide.with(target)
            .load(safeUrl)
            .transform(SHARED_YT_CROP)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .onlyRetrieveFromCache(offlineOnly)
            .override(Math.max(targetWidth, 320), Math.max(targetHeight, 320))
            .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
            .into(target);
    }
    private void bindFeaturedTrack(@NonNull YouTubeMusicService.TrackResult track) {
        tvFeaturedTitle.setText(track.title);
        String typeLabel = searchTypeLabel(track);
        String subtitle = TextUtils.isEmpty(track.subtitle)
            ? typeLabel
            : typeLabel + " â€¢ " + track.subtitle;
        tvFeaturedSubtitle.setText(subtitle);
        loadArtworkInto(ivFeaturedThumb, track.thumbnailUrl);
    }
    private void sortSearchResultsByBestMatch(
            @NonNull List<YouTubeMusicService.TrackResult> source,
            @NonNull String query
    ) {
        String normalizedQuery = normalizeForFilter(query);
        if (normalizedQuery.isEmpty()) {
            return;
        }
        String[] queryTokens = normalizedQuery.split("\\s+");
        source.sort((left, right) -> {
            int leftScore = computeSearchMatchScore(left, normalizedQuery, queryTokens);
            int rightScore = computeSearchMatchScore(right, normalizedQuery, queryTokens);
            if (leftScore != rightScore) {
                return Integer.compare(rightScore, leftScore);
            }
            String leftTitle = normalizeForFilter(left.title);
            String rightTitle = normalizeForFilter(right.title);
            return leftTitle.compareTo(rightTitle);
        });
    }
    private int computeSearchMatchScore(
            @NonNull YouTubeMusicService.TrackResult track,
            @NonNull String normalizedQuery,
            @NonNull String[] queryTokens
    ) {
        String title = normalizeForFilter(track.title);
        String subtitle = normalizeForFilter(track.subtitle);
        int score = 0;
        if (title.equals(normalizedQuery)) {
            score += 800;
        } else if (title.startsWith(normalizedQuery + " ")) {
            score += 760;
        } else if (title.startsWith(normalizedQuery)) {
            score += 560;
        } else if (title.contains(normalizedQuery)) {
            score += 340;
        }
        if (title.startsWith(normalizedQuery)
                && title.length() > normalizedQuery.length()) {
            char next = title.charAt(normalizedQuery.length());
            if (Character.isLetterOrDigit(next)) {
                score -= 180;
            }
        }
        if (containsWordToken(title, normalizedQuery)) {
            score += 320;
        }
        if (subtitle.equals(normalizedQuery)) {
            score += 220;
        } else if (subtitle.startsWith(normalizedQuery)) {
            score += 180;
        } else if (subtitle.contains(normalizedQuery)) {
            score += 120;
        }
        for (String token : queryTokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (title.startsWith(token + " ")) {
                score += 92;
            } else if (title.startsWith(token)) {
                score += 75;
            } else if (title.contains(token)) {
                score += 45;
            }
            if (containsWordToken(title, token)) {
                score += 58;
            }
            if (subtitle.startsWith(token)) {
                score += 24;
            } else if (subtitle.contains(token)) {
                score += 14;
            }
        }
        if ("video".equals(track.resultType)) {
            score += 12;
        }
        return score;
    }
    private boolean containsWordToken(@NonNull String source, @NonNull String token) {
        if (token.isEmpty()) {
            return false;
        }
        if (source.equals(token)
                || source.startsWith(token + " ")
                || source.contains(" " + token + " ")
                || source.endsWith(" " + token)) {
            return true;
        }
        return source.contains("(" + token + ")")
                || source.contains("-" + token + " ")
                || source.contains(" " + token + "-");
    }
    @NonNull
    private String searchTypeLabel(@NonNull YouTubeMusicService.TrackResult track) {
        if ("playlist".equals(track.resultType)) {
            return "Playlist";
        }
        if ("album".equals(track.resultType)) {
            return "Álbum";
        }
        return "";
    }
    private void showAddToPlaylistDialog(@NonNull YouTubeMusicService.TrackResult track) {
        if (!isAdded()) return;
        List<String> localNames = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
        String token = resolveAccessTokenForPlaylistDetail();
        if (TextUtils.isEmpty(token)) {
            // Only local
            if (localNames.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No tienes playlists. Crea una primero.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            showAddToPlaylistDialogInternal(track, localNames, Collections.emptyList());
            return;
        }
        // Show loading and fetch YT playlists
        android.widget.Toast.makeText(requireContext(), "Cargando tus listas de YouTube...", android.widget.Toast.LENGTH_SHORT).show();
        youTubeMusicService.fetchMyPlaylists(token, 50, new YouTubeMusicService.PlaylistsCallback() {
            @Override
            public void onSuccess(@NonNull List<YouTubeMusicService.PlaylistResult> playlists) {
                if (!isAdded()) return;
                showAddToPlaylistDialogInternal(track, localNames, playlists);
            }
            @Override
            public void onError(@NonNull String error) {
                if (!isAdded()) return;
                showAddToPlaylistDialogInternal(track, localNames, Collections.emptyList());
            }
        });
    }
    private void showAddToPlaylistDialogInternal(@NonNull YouTubeMusicService.TrackResult track, @NonNull List<String> localNames, @NonNull List<YouTubeMusicService.PlaylistResult> ytPlaylists) {
        List<String> displayNames = new ArrayList<>();
        for (String name : localNames) {
            displayNames.add("[Local] " + name);
        }
        for (YouTubeMusicService.PlaylistResult playlist : ytPlaylists) {
            displayNames.add("[YT] " + playlist.title);
        }
        if (displayNames.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No tienes playlists. Crea una primero.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] array = displayNames.toArray(new String[0]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Añadir a playlist")
            .setItems(array, (dialog, which) -> {
                if (which < localNames.size()) {
                    String selected = localNames.get(which);
                    CustomPlaylistsStore.INSTANCE.addTrackToPlaylist(
                        requireContext(),
                        selected,
                        track.videoId,
                        TextUtils.isEmpty(track.title) ? "Tema" : track.title,
                        track.subtitle == null ? "" : track.subtitle,
                        "",
                        track.thumbnailUrl == null ? "" : track.thumbnailUrl
                    );
                    android.widget.Toast.makeText(requireContext(), "Añadida a local: " + selected, android.widget.Toast.LENGTH_SHORT).show();
                    fetchLibraryPlaylists(true);
                } else {
                    int ytIdx = which - localNames.size();
                    YouTubeMusicService.PlaylistResult selected = ytPlaylists.get(ytIdx);
                    String token = resolveAccessTokenForPlaylistDetail();
                    youTubeMusicService.insertTrackToPlaylist(token, selected.playlistId, track.videoId, (success, error) -> {
                        if (!isAdded()) return;
                        if (success) {
                            android.widget.Toast.makeText(requireContext(), "Añadida a YouTube: " + selected.title, android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            android.widget.Toast.makeText(requireContext(), "Error YouTube: " + error, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
    private void showRenamePlaylistDialog(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) return;
        final String oldName = playlistTrack.title == null ? "" : playlistTrack.title.trim();
        if (TextUtils.isEmpty(oldName)) {
            android.widget.Toast.makeText(requireContext(), "Nombre inválido", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setText(oldName);
        input.setSelection(Math.min(oldName.length(), oldName.length()));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Renombrar playlist")
            .setMessage("Nuevo nombre:")
            .setView(input)
            .setPositiveButton("Guardar", (dialog, which) -> {
                String newName = input.getText() == null ? "" : input.getText().toString().trim();
                boolean ok = CustomPlaylistsStore.INSTANCE.renamePlaylist(requireContext(), oldName, newName);
                if (ok) {
                    android.widget.Toast.makeText(requireContext(), "Playlist renombrada", android.widget.Toast.LENGTH_SHORT).show();
                    renderLibraryResults();
                } else {
                    android.widget.Toast.makeText(requireContext(), "No se pudo renombrar", android.widget.Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
    private void addSearchTrackToFavorites(@NonNull YouTubeMusicService.TrackResult track) {
        if (!isAdded() || !track.isVideo()) {
            return;
        }
        FavoritesPlaylistStore.upsertFavorite(
                requireContext(),
                track.videoId,
                TextUtils.isEmpty(track.title) ? "Tema" : track.title,
                track.subtitle == null ? "" : track.subtitle,
                "--:--",
                track.thumbnailUrl == null ? "" : track.thumbnailUrl
        );
        if (activeScreen == ScreenMode.LIBRARY) {
            renderLibraryResults();
        }
    }
    private void shareTrackResult(@NonNull YouTubeMusicService.TrackResult track) {
        if (!isAdded()) {
            return;
        }
        try {
            String watchUrl = track.getWatchUrl();
            String title = TextUtils.isEmpty(track.title) ? "Mira este resultado" : track.title;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            shareIntent.putExtra(Intent.EXTRA_TEXT, title + "\n" + watchUrl);
            startActivity(Intent.createChooser(shareIntent, "Compartir"));
        } catch (Exception e) {
            Log.w(TAG_STREAMING, "share_track_failed", e);
        }
    }
    @Nullable
    private SongPlayerFragment findSongPlayerFragment() {
        androidx.fragment.app.Fragment fragment = getParentFragmentManager().findFragmentByTag("song_player");
        if (fragment instanceof SongPlayerFragment) {
            return (SongPlayerFragment) fragment;
        }
        return null;
    }
    @NonNull
    private PlaybackHistoryStore.Snapshot loadPlaybackSnapshot() {
        if (!isAdded()) {
            return new PlaybackHistoryStore.Snapshot(new ArrayList<>(), 0, 0, 1, false, 0L);
        }
        return PlaybackHistoryStore.load(requireContext());
    }

    private boolean startHiddenPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid() || !isAdded()) {
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
        persistSnapshotPositionToPlayerState(snapshot);
        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
                existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, snapshotIndex, startPlaying);
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
                snapshotIndex,
                startPlaying
        );
        playerFragment.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
        getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, "song_player")
                .hide(playerFragment)
                .commit();
        return true;
    }
    private boolean openLastPlaylistDetailFromPrefs() {
        if (!isAdded()) {
            return false;
        }
        try {
            getParentFragmentManager().popBackStackImmediate(
                    "playlist_detail",
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String playlistId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        if (TextUtils.isEmpty(playlistId)) {
            return false;
        }
        String playlistTitle = prefs.getString(PREF_LAST_PLAYLIST_TITLE, "Lista");
        String playlistSubtitle = prefs.getString(PREF_LAST_PLAYLIST_SUBTITLE, "Playlist");
        String playlistThumbnail = prefs.getString(PREF_LAST_PLAYLIST_THUMBNAIL, "");
        // ID-only normalization â€” consistent with mapPlaylistsToLibraryTracks
        String rawId = playlistId == null ? "" : playlistId.trim();
        String normalizedPlaylistId;
        if (YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID.equals(rawId)
                || "LL".equals(rawId)
                || "LM".equals(rawId)
                || rawId.startsWith("VLLL")) {
            normalizedPlaylistId = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID;
        } else {
            normalizedPlaylistId = playlistId;
        }
        String accessTokenForDetail = resolveAccessTokenForPlaylistDetail();
        PlaylistDetailFragment detailFragment = PlaylistDetailFragment.newInstance(
                normalizedPlaylistId,
                TextUtils.isEmpty(playlistTitle) ? "Lista" : playlistTitle,
                TextUtils.isEmpty(playlistSubtitle) ? "Playlist" : playlistSubtitle,
                playlistThumbnail == null ? "" : playlistThumbnail,
            accessTokenForDetail
        );
        androidx.fragment.app.Fragment existingDetail = getParentFragmentManager().findFragmentByTag("playlist_detail");
        androidx.fragment.app.FragmentTransaction transaction = getParentFragmentManager()
            .beginTransaction()
                .setReorderingAllowed(true)
            ;
        if (existingDetail != null && existingDetail.isAdded() && existingDetail != this) {
            transaction.remove(existingDetail);
        }
        transaction
            .add(R.id.fragmentContainer, detailFragment, "playlist_detail")
            .addToBackStack("playlist_detail")
            .commit();
        return true;
    }
    private boolean openPlayerFromSnapshot(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            boolean startPlaying
    ) {
        if (!snapshot.isValid()) {
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
        persistSnapshotPositionToPlayerState(snapshot);
        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            existingPlayer.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
            existingPlayer.externalReplaceQueue(ids, titles, artists, durations, images, snapshotIndex, startPlaying);
            getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .show(existingPlayer)
                    .runOnCommit(existingPlayer::externalAnimateEnterSlide)
                    .commit();
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
            playerFragment.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
            final SongPlayerFragment newPlayerForAnim = playerFragment;
            getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.playerContainer, playerFragment, "song_player")
                    .runOnCommit(newPlayerForAnim::externalAnimateEnterSlide)
                    .commit();
        }
        return true;
    }
    private void persistSnapshotPositionToPlayerState(@NonNull PlaybackHistoryStore.Snapshot snapshot) {
        if (!isAdded()) {
            return;
        }
        PlaybackHistoryStore.QueueTrack current = snapshot.currentTrack();
        if (current == null || TextUtils.isEmpty(current.videoId)) {
            return;
        }
        requireContext()
                .getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .edit()
                .putString("stream_last_track_duration", current.duration)
                .putString("stream_last_track_image", current.imageUrl)
                .putBoolean("stream_last_is_playing", snapshot.isPlaying)
                .apply();
    }
    @Nullable
    private PlaybackHistoryStore.QueueTrack findSnapshotTrackByVideoId(
            @NonNull PlaybackHistoryStore.Snapshot snapshot,
            @Nullable String videoId
    ) {
        if (TextUtils.isEmpty(videoId)) {
            return null;
        }
        for (PlaybackHistoryStore.QueueTrack track : snapshot.queue) {
            if (TextUtils.equals(track.videoId, videoId)) {
                return track;
            }
        }
        return null;
    }
    private int parseDurationSeconds(@Nullable String duration) {
        if (TextUtils.isEmpty(duration) || duration.contains("--")) {
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
    private void onMusicResultMoreClicked(
            @NonNull YouTubeMusicService.TrackResult track,
            @NonNull View anchor
    ) {
        if (!isAdded()) return;
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        anchor.animate().cancel();
        anchor.animate().alpha(0.85f).setDuration(80L)
            .withEndAction(() -> anchor.animate().alpha(1f).setDuration(120L).start())
            .start();
        if ("playlist".equals(track.resultType)) {
            showPlaylistActionTooltip(anchor, track);
        } else {
            showLibraryTrackOptions(track, anchor);
        }
    }
    private void showPlaylistActionTooltip(@NonNull View anchor, @NonNull YouTubeMusicService.TrackResult track) {
        if (!isAdded()) return;
        String playlistId = track.contentId == null ? "" : track.contentId.trim();
        boolean isLocalFilesPlaylist = LocalFilesStore.PLAYLIST_ID.equals(playlistId);
        boolean isOfflineEnabled = isPersistedPlaylistOfflineAutoEnabled(playlistId);
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_track_options, null);
        dialog.setContentView(view);
        TextView tvTitle = view.findViewById(R.id.tvBsTrackTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvBsTrackSubtitle);
        ImageView ivArt = view.findViewById(R.id.ivBsTrackArt);
        tvTitle.setText(track.title);
        tvSubtitle.setText(searchTypeLabel(track));
        boolean isLikedStyle = isLikedPlaylistStyle(track);
        boolean isRadioItem = playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDEM") || playlistId.startsWith("RDTMAK");
        if (isLocalFilesPlaylist) {
            ivArt.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ivArt.setBackgroundColor(android.graphics.Color.BLACK);
            ivArt.setImageResource(R.drawable.ic_folder_white);
        } else if (isLikedStyle) {
            ivArt.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ivArt.setBackgroundResource(R.drawable.bg_music_liked_gradient);
            ivArt.setImageResource(R.drawable.ic_thumb_up_liked);
            ivArt.setColorFilter(android.graphics.Color.WHITE);
        } else if (isRadioItem && !TextUtils.isEmpty(track.thumbnailUrl)) {
            ivArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            loadArtworkInto(ivArt, track.thumbnailUrl);
        } else {
            List<String> gridUrls = playlistId.isEmpty() ? java.util.Collections.emptyList() : resolvePlaylistGridUrls(playlistId);
            if (gridUrls.size() >= 4) {
                float density = getResources().getDisplayMetrics().density;
                int sizePx = Math.round(60 * density);
                PlaylistGridArtLoader.load(ivArt, gridUrls, sizePx);
            } else {
                loadArtworkInto(ivArt, track.thumbnailUrl);
            }
        }
        // Top 3 buttons: Play, Share, Download/Delete Downloads
        View btnPlayNext = view.findViewById(R.id.btnBsPlayNext);
        View btnAddPrimary = view.findViewById(R.id.btnBsAddPrimary);
        View btnShare = view.findViewById(R.id.btnBsShare);
        ImageView ivPlayNext = view.findViewById(R.id.ivBsPlayNextIcon);
        TextView tvPlayNext = view.findViewById(R.id.tvBsPlayNextLabel);
        ImageView ivAddPrimary = view.findViewById(R.id.ivBsAddPrimary);
        TextView tvAddPrimary = view.findViewById(R.id.tvBsAddPrimary);
        ImageView ivShare = view.findViewById(R.id.ivBsShareIcon);
        TextView tvShare = view.findViewById(R.id.tvBsShareLabel);
        // Button 1: Reproducir playlist
        btnPlayNext.setVisibility(View.VISIBLE);
        ivPlayNext.setImageResource(R.drawable.ic_player_play);
        tvPlayNext.setText("Reproducir");
        btnPlayNext.setOnClickListener(v -> {
            dialog.dismiss();
            playPlaylistFromStart(track);
        });
        // Button 2: Compartir (hidden for local files)
        btnShare.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        ivShare.setImageResource(R.drawable.ic_playlist_share);
        tvShare.setText("Compartir");
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareTrackResult(track);
        });
        // Button 3: Descargar o Eliminar descargas (hidden for local files)
        btnAddPrimary.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        if (isOfflineEnabled) {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold);
            tvAddPrimary.setText("Eliminar\ndescargas");
            btnAddPrimary.setOnClickListener(v -> {
                dialog.dismiss();
                deletePlaylistDownloads(track);
            });
        } else {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold);
            tvAddPrimary.setText("Descargar");
            btnAddPrimary.setOnClickListener(v -> {
                dialog.dismiss();
                enqueuePlaylistOfflineDownload(track);
            });
        }
        // --- List actions below the top 3 buttons ---
        // 4. Reproducir a continuación
        View btnPlayPlaylist = view.findViewById(R.id.btnBsPlayPlaylist);
        btnPlayPlaylist.setVisibility(View.VISIBLE);
        ImageView ivPlayPlaylist = view.findViewById(R.id.ivBsPlayPlaylist);
        TextView tvPlayPlaylist = view.findViewById(R.id.tvBsPlayPlaylist);
        ivPlayPlaylist.setImageResource(R.drawable.ic_bs_play_next_yt);
        tvPlayPlaylist.setText("Reproducir a continuación");
        btnPlayPlaylist.setOnClickListener(v -> {
            dialog.dismiss();
            enqueuePlaylistTracksNext(track);
        });

        // 5. Agregar a la fila
        View btnAddToQueue = view.findViewById(R.id.btnBsAddToQueue);
        btnAddToQueue.setVisibility(View.VISIBLE);
        ImageView ivAddToQueue = view.findViewById(R.id.ivBsAddToQueue);
        TextView tvAddToQueue = view.findViewById(R.id.tvBsAddToQueue);
        ivAddToQueue.setImageResource(R.drawable.ic_bs_add_queue_yt);
        tvAddToQueue.setText("Agregar a la fila");
        btnAddToQueue.setOnClickListener(v -> {
            dialog.dismiss();
            enqueuePlaylistTracksToEnd(track);
        });

        // 6. Fijar / Desfijar playlist (hide for Liked & Favoritos — always pinned)
        View btnPin = view.findViewById(R.id.btnBsPlay);
        boolean isAlwaysPinned = isLikedPlaylistStyle(track)
                || FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistId)
                || isLocalFilesPlaylist;
        if (isAlwaysPinned) {
            btnPin.setVisibility(View.GONE);
        } else {
            boolean currentlyPinned = RadioHistoryStore.INSTANCE.isPlaylistPinned(requireContext(), playlistId);
            btnPin.setVisibility(View.VISIBLE);
            ImageView ivPin = view.findViewById(R.id.ivBsPlay);
            TextView tvPin = view.findViewById(R.id.tvBsPlayLabel);
            ivPin.setImageResource(R.drawable.ic_pin);
            tvPin.setText(currentlyPinned ? "Desfijar playlist" : "Fijar playlist");
            btnPin.setOnClickListener(v -> {
                dialog.dismiss();
                if (currentlyPinned) {
                    RadioHistoryStore.INSTANCE.unpinPlaylist(requireContext(), playlistId);
                } else {
                    RadioHistoryStore.INSTANCE.pinPlaylist(requireContext(), playlistId);
                }
                refreshLibraryUi();
            });
        }

        // 7. Iniciar radio (hidden for local files)
        View btnRadio = view.findViewById(R.id.btnBsReplace);
        btnRadio.setVisibility(isLocalFilesPlaylist ? View.GONE : View.VISIBLE);
        ImageView ivRadio = view.findViewById(R.id.ivBsReplace);
        TextView tvRadio = view.findViewById(R.id.tvBsReplace);
        ivRadio.setImageResource(R.drawable.ic_bs_radio);
        tvRadio.setText("Iniciar radio");
        btnRadio.setOnClickListener(v -> {
            dialog.dismiss();
            android.widget.Toast.makeText(requireContext(), "Próximamente disponible para playlists", android.widget.Toast.LENGTH_SHORT).show();
        });

        View btnDeletePlaylist = view.findViewById(R.id.btnBsFavorite);
        View btnRenamePlaylist = view.findViewById(R.id.btnBsDownload);
        boolean isCustomPlaylist = false;
        java.util.List<String> customPlaylists = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
        for (String customName : customPlaylists) {
            if (customName.equalsIgnoreCase(track.title)) {
                isCustomPlaylist = true;
                break;
            }
        }
        boolean isCloudOnlyPlaylist = !isCustomPlaylist
                && track.contentId != null
                && track.contentId.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX);
        if (isCustomPlaylist) {
            btnDeletePlaylist.setVisibility(View.VISIBLE);
            ImageView ivDelete = btnDeletePlaylist.findViewById(R.id.ivBsFavorite);
            TextView tvDelete = btnDeletePlaylist.findViewById(R.id.tvBsFavorite);
            ivDelete.setImageResource(R.drawable.ic_delete_modern);
            tvDelete.setText("Eliminar playlist");
            btnDeletePlaylist.setOnClickListener(v -> {
                dialog.dismiss();
                showDeletePlaylistConfirmDialog(track);
            });
            btnRenamePlaylist.setVisibility(View.VISIBLE);
            ImageView ivRename = btnRenamePlaylist.findViewById(R.id.ivBsDownload);
            TextView tvRename = btnRenamePlaylist.findViewById(R.id.tvBsDownload);
            ivRename.setImageResource(R.drawable.ic_edit_24);
            tvRename.setText("Renombrar playlist");
            btnRenamePlaylist.setOnClickListener(v -> {
                dialog.dismiss();
                showRenamePlaylistDialog(track);
            });
        } else if (isCloudOnlyPlaylist) {
            btnDeletePlaylist.setVisibility(View.VISIBLE);
            ImageView ivDelete = btnDeletePlaylist.findViewById(R.id.ivBsFavorite);
            TextView tvDelete = btnDeletePlaylist.findViewById(R.id.tvBsFavorite);
            ivDelete.setImageResource(R.drawable.ic_delete_modern);
            tvDelete.setText("Eliminar de la nube");
            btnDeletePlaylist.setOnClickListener(v -> {
                dialog.dismiss();
                showDeleteCloudPlaylistConfirmDialog(track);
            });
            btnRenamePlaylist.setVisibility(View.GONE);
        } else if (playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDEM") || playlistId.startsWith("RDTMAK")) {
            // Radio playlist — show delete radio option
            btnDeletePlaylist.setVisibility(View.VISIBLE);
            ImageView ivDelete = btnDeletePlaylist.findViewById(R.id.ivBsFavorite);
            TextView tvDelete = btnDeletePlaylist.findViewById(R.id.tvBsFavorite);
            ivDelete.setImageResource(R.drawable.ic_delete_modern);
            tvDelete.setText("Eliminar radio");
            btnDeletePlaylist.setOnClickListener(v -> {
                dialog.dismiss();
                RadioHistoryStore.INSTANCE.deleteRadio(requireContext(), playlistId);
                refreshLibraryUi();
            });
            btnRenamePlaylist.setVisibility(View.GONE);
        } else {
            btnDeletePlaylist.setVisibility(View.GONE);
            btnRenamePlaylist.setVisibility(View.GONE);
        }
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setFitToContents(true);
        view.setAlpha(0f);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                View sheetParent = (View) view.getParent();
                if (sheetParent != null) sheetParent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                bottomSheet.setBackgroundResource(android.R.color.transparent);
            }
            view.animate().alpha(1f).setDuration(180L).start();
        });
        dialog.show();
    }
    private void showLibraryTrackOptions(@NonNull YouTubeMusicService.TrackResult track, @NonNull View anchor) {
        if (!isAdded()) return;
        String videoId = track.videoId == null ? "" : track.videoId;
        boolean hasOfflineAudio = !TextUtils.isEmpty(videoId)
                && OfflineAudioStore.hasValidatedOfflineAudio(requireContext(), videoId, null);
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_track_options, null);
        dialog.setContentView(view);
        TextView tvTitle = view.findViewById(R.id.tvBsTrackTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvBsTrackSubtitle);
        ImageView ivArt = view.findViewById(R.id.ivBsTrackArt);
        tvTitle.setText(TextUtils.isEmpty(track.title) ? "Tema" : track.title);
        tvSubtitle.setText(track.subtitle == null ? "" : track.subtitle);
        loadArtworkInto(ivArt, track.thumbnailUrl);
        // Top row slot 1: Reproducir
        View btnPlayNext = view.findViewById(R.id.btnBsPlayNext);
        ImageView ivPlayNext = view.findViewById(R.id.ivBsPlayNextIcon);
        TextView tvPlayNext = view.findViewById(R.id.tvBsPlayNextLabel);
        btnPlayNext.setVisibility(View.VISIBLE);
        ivPlayNext.setImageResource(R.drawable.ic_player_play);
        tvPlayNext.setText("Reproducir");
        btnPlayNext.setOnClickListener(v -> {
            dialog.dismiss();
            openTrack(track);
        });
        // Top row slot 2: Descargar / Eliminar descarga
        View btnAddPrimary = view.findViewById(R.id.btnBsAddPrimary);
        ImageView ivAddPrimary = view.findViewById(R.id.ivBsAddPrimary);
        TextView tvAddPrimary = view.findViewById(R.id.tvBsAddPrimary);
        btnAddPrimary.setVisibility(View.VISIBLE);
        if (hasOfflineAudio) {
            ivAddPrimary.setImageResource(R.drawable.ic_delete_modern);
            tvAddPrimary.setText("Eliminar\ndescarga");
        } else {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold);
            tvAddPrimary.setText("Descargar");
        }
        btnAddPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (hasOfflineAudio) {
                if (!TextUtils.isEmpty(videoId)) {
                    OfflineAudioStore.deleteOfflineAudio(requireContext().getApplicationContext(), new ArrayList<>(Collections.singletonList(videoId)));
                }
            } else {
                android.widget.Toast.makeText(requireContext(), "Descarga disponible desde búsqueda", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // Top row slot 3: Compartir
        View btnShare = view.findViewById(R.id.btnBsShare);
        ImageView ivShare = view.findViewById(R.id.ivBsShareIcon);
        TextView tvShare = view.findViewById(R.id.tvBsShareLabel);
        btnShare.setVisibility(View.VISIBLE);
        ivShare.setImageResource(R.drawable.ic_playlist_share);
        tvShare.setText("Compartir");
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareTrackResult(track);
        });
        // Row: Añadir a playlist
        View btnFavorite = view.findViewById(R.id.btnBsFavorite);
        ImageView ivFav = btnFavorite.findViewById(R.id.ivBsFavorite);
        TextView tvFav = btnFavorite.findViewById(R.id.tvBsFavorite);
        btnFavorite.setVisibility(View.VISIBLE);
        ivFav.setImageResource(R.drawable.ic_stream_queue_add);
        tvFav.setText("Añadir a playlist");
        btnFavorite.setOnClickListener(v -> {
            dialog.dismiss();
            showAddToPlaylistDialog(track);
        });
        // Hide unused rows
        view.findViewById(R.id.btnBsPlay).setVisibility(View.GONE);
        view.findViewById(R.id.btnBsDownload).setVisibility(View.GONE);
        view.findViewById(R.id.btnBsAddToQueue).setVisibility(View.GONE);
        view.findViewById(R.id.btnBsPlayPlaylist).setVisibility(View.GONE);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setFitToContents(true);
        dialog.setOnShowListener(d -> {
            View bottomSheet2 = ((com.google.android.material.bottomsheet.BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet2 != null) {
                View sheetParent2 = (View) view.getParent();
                if (sheetParent2 != null) sheetParent2.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                bottomSheet2.setBackgroundResource(android.R.color.transparent);
            }
        });
        dialog.show();
    }
    private void playPlaylistFromStart(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) return;
        // 0 Local files playlist
        String plId = playlistTrack.contentId == null ? "" : playlistTrack.contentId.trim();
        if (LocalFilesStore.PLAYLIST_ID.equals(plId)) {
            List<LocalFilesStore.LocalTrack> localTracks = LocalFilesStore.getCachedFiles(requireContext());
            if (localTracks.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No hay archivos locales", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<String> artists = new ArrayList<>();
            ArrayList<String> durations = new ArrayList<>();
            ArrayList<String> images = new ArrayList<>();
            for (LocalFilesStore.LocalTrack t : localTracks) {
                ids.add(t.getVideoId());
                titles.add(t.getTitle().isEmpty() ? "Tema" : t.getTitle());
                artists.add(t.getArtist());
                durations.add(t.getDuration());
                images.add(t.getAlbumArtUri());
            }
            startPlaybackQueue(ids, titles, artists, durations, images, 0);
            return;
        }
        // 1 Local/custom playlist by name
        java.util.List<String> customPlaylists = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
        for (String customName : customPlaylists) {
            if (customName.equalsIgnoreCase(playlistTrack.title)) {
                java.util.List<FavoritesPlaylistStore.FavoriteTrack> tracks =
                        CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(requireContext(), customName);
                if (tracks.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "La playlist está vacía", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                ArrayList<String> ids = new ArrayList<>();
                ArrayList<String> titles = new ArrayList<>();
                ArrayList<String> artists = new ArrayList<>();
                ArrayList<String> durations = new ArrayList<>();
                ArrayList<String> images = new ArrayList<>();
                for (FavoritesPlaylistStore.FavoriteTrack t : tracks) {
                    if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                    ids.add(t.videoId);
                    titles.add(TextUtils.isEmpty(t.title) ? "Tema" : t.title);
                    artists.add(t.artist == null ? "" : t.artist);
                    durations.add(TextUtils.isEmpty(t.duration) ? "--:--" : t.duration);
                    images.add(t.imageUrl == null ? "" : t.imageUrl);
                }
                if (ids.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "La playlist está vacía", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                startPlaybackQueue(ids, titles, artists, durations, images, 0);
                return;
            }
        }
        // 2) YouTube playlist by id
        String playlistId = playlistTrack.contentId == null ? "" : playlistTrack.contentId.trim();
        if (TextUtils.isEmpty(playlistId)) {
            android.widget.Toast.makeText(requireContext(), "Playlist inválida", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String token = resolveAccessTokenForPlaylistDetail();
        if (TextUtils.isEmpty(token)) {
            android.widget.Toast.makeText(requireContext(), "Inicia sesión para reproducir esta playlist", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        youTubeMusicService.fetchPlaylistTracks(token, playlistId, 250, new YouTubeMusicService.PlaylistTracksCallback() {
            @Override
            public void onSuccess(java.util.List<YouTubeMusicService.PlaylistTrackResult> tracks) {
                if (!isAdded()) return;
                if (tracks == null || tracks.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "La playlist está vacía", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                ArrayList<String> ids = new ArrayList<>();
                ArrayList<String> titles = new ArrayList<>();
                ArrayList<String> artists = new ArrayList<>();
                ArrayList<String> durations = new ArrayList<>();
                ArrayList<String> images = new ArrayList<>();
                for (YouTubeMusicService.PlaylistTrackResult t : tracks) {
                    if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                    ids.add(t.videoId);
                    titles.add(TextUtils.isEmpty(t.title) ? "Tema" : t.title);
                    artists.add(t.artist == null ? "" : t.artist);
                    durations.add(TextUtils.isEmpty(t.duration) ? "--:--" : t.duration);
                    images.add(t.thumbnailUrl == null ? "" : t.thumbnailUrl);
                }
                if (ids.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "La playlist está vacía", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                startPlaybackQueue(ids, titles, artists, durations, images, 0);
            }
            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                android.widget.Toast.makeText(requireContext(), "No se pudo cargar la playlist", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void startPlaybackQueue(
            @NonNull ArrayList<String> ids,
            @NonNull ArrayList<String> titles,
            @NonNull ArrayList<String> artists,
            @NonNull ArrayList<String> durations,
            @NonNull ArrayList<String> images,
            int selectedIndex
    ) {
        if (!isAdded() || ids.isEmpty()) return;
        int index = Math.max(0, Math.min(selectedIndex, ids.size() - 1));
        // Hide global mini-player when opening full player
        if (getActivity() instanceof MainActivity) {
            GlobalMiniPlayerController ctrl = ((MainActivity) getActivity()).getGlobalMiniPlayerController();
            if (ctrl != null) ctrl.hide();
        }
        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null) {
            if (existingPlayer.isAdded()) {
                existingPlayer.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
                existingPlayer.externalReplaceQueueFromStart(ids, titles, artists, durations, images, index, true);
                getParentFragmentManager()
                        .beginTransaction()
                        .setReorderingAllowed(true)
                        .show(existingPlayer)
                        .runOnCommit(existingPlayer::externalAnimateEnterSlide)
                        .commit();
            }
        } else {
            SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                    ids,
                    titles,
                    artists,
                    durations,
                    images,
                    index,
                    true
            );
            playerFragment.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
            final SongPlayerFragment newPlayerForAnim = playerFragment;
            getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.playerContainer, playerFragment, "song_player")
                    .runOnCommit(newPlayerForAnim::externalAnimateEnterSlide)
                    .commit();
        }
    }
    private void enqueuePlaylistTracksNext(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        fetchPlaylistTracksForQueue(playlistTrack, true);
    }

    private void enqueuePlaylistTracksToEnd(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        fetchPlaylistTracksForQueue(playlistTrack, false);
    }

    private void fetchPlaylistTracksForQueue(@NonNull YouTubeMusicService.TrackResult playlistTrack, boolean insertNext) {
        if (!isAdded()) return;
        String playlistId = playlistTrack.contentId == null ? "" : playlistTrack.contentId.trim();
        if (TextUtils.isEmpty(playlistId)) return;

        // 0. Local files playlist
        if (LocalFilesStore.PLAYLIST_ID.equals(playlistId)) {
            List<LocalFilesStore.LocalTrack> localTracks = LocalFilesStore.getCachedFiles(requireContext());
            if (localTracks.isEmpty()) return;
            SongPlayerFragment sp = findSongPlayerFragment();
            if (sp == null || !sp.isAdded()) return;
            if (insertNext) {
                for (int i = localTracks.size() - 1; i >= 0; i--) {
                    LocalFilesStore.LocalTrack t = localTracks.get(i);
                    sp.externalInsertNext(t.getVideoId(), t.getTitle(), t.getArtist(), t.getDuration(), t.getAlbumArtUri());
                }
            } else {
                for (LocalFilesStore.LocalTrack t : localTracks) {
                    sp.externalEnqueue(t.getVideoId(), t.getTitle(), t.getArtist(), t.getDuration(), t.getAlbumArtUri());
                }
            }
            android.widget.Toast.makeText(requireContext(),
                    insertNext ? "Se reproducirá a continuación" : "Agregado a la fila",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Radio playlists — load from RadioHistoryStore
        if (playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDEM") || playlistId.startsWith("RDTMAK")) {
            List<RadioHistoryStore.RadioEntry> radios = RadioHistoryStore.INSTANCE.getRadios(requireContext());
            for (RadioHistoryStore.RadioEntry radio : radios) {
                if (playlistId.equals(radio.getRadioPlaylistId())) {
                    SongPlayerFragment sp = findSongPlayerFragment();
                    if (sp == null || !sp.isAdded()) return;
                    List<RadioHistoryStore.RadioTrack> radioTracks = radio.getTracks();
                    if (radioTracks.isEmpty()) return;
                    if (insertNext) {
                        for (int i = radioTracks.size() - 1; i >= 0; i--) {
                            RadioHistoryStore.RadioTrack t = radioTracks.get(i);
                            sp.externalInsertNext(t.getVideoId(), t.getTitle(), t.getArtist(), "--:--", t.getThumbnailUrl());
                        }
                    } else {
                        for (RadioHistoryStore.RadioTrack t : radioTracks) {
                            sp.externalEnqueue(t.getVideoId(), t.getTitle(), t.getArtist(), "--:--", t.getThumbnailUrl());
                        }
                    }
                    android.widget.Toast.makeText(requireContext(),
                            insertNext ? "Se reproducirá a continuación" : "Agregado a la fila",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            return;
        }

        // 2. Favoritos playlist
        if (FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistId)) {
            java.util.List<FavoritesPlaylistStore.FavoriteTrack> favTracks = FavoritesPlaylistStore.loadFavorites(requireContext());
            if (favTracks.isEmpty()) return;
            SongPlayerFragment sp = findSongPlayerFragment();
            if (sp == null || !sp.isAdded()) return;
            if (insertNext) {
                for (int i = favTracks.size() - 1; i >= 0; i--) {
                    FavoritesPlaylistStore.FavoriteTrack t = favTracks.get(i);
                    sp.externalInsertNext(t.videoId, t.title, t.artist, t.duration, t.imageUrl);
                }
            } else {
                for (FavoritesPlaylistStore.FavoriteTrack t : favTracks) {
                    sp.externalEnqueue(t.videoId, t.title, t.artist, t.duration, t.imageUrl);
                }
            }
            android.widget.Toast.makeText(requireContext(),
                    insertNext ? "Se reproducirá a continuación" : "Agregado a la fila",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. Custom playlists
        java.util.List<String> customPlaylists = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
        for (String customName : customPlaylists) {
            if (customName.equalsIgnoreCase(playlistTrack.title)) {
                java.util.List<FavoritesPlaylistStore.FavoriteTrack> customTracks =
                        CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(requireContext(), customName);
                if (customTracks.isEmpty()) return;
                SongPlayerFragment sp = findSongPlayerFragment();
                if (sp == null || !sp.isAdded()) return;
                if (insertNext) {
                    for (int i = customTracks.size() - 1; i >= 0; i--) {
                        FavoritesPlaylistStore.FavoriteTrack t = customTracks.get(i);
                        sp.externalInsertNext(t.videoId, t.title, t.artist, t.duration, t.imageUrl);
                    }
                } else {
                    for (FavoritesPlaylistStore.FavoriteTrack t : customTracks) {
                        sp.externalEnqueue(t.videoId, t.title, t.artist, t.duration, t.imageUrl);
                    }
                }
                android.widget.Toast.makeText(requireContext(),
                        insertNext ? "Se reproducirá a continuación" : "Agregado a la fila",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 3. YouTube playlists — fetch from API
        String token = resolveAccessTokenForPlaylistDetail();
        if (TextUtils.isEmpty(token)) {
            android.widget.Toast.makeText(requireContext(), "Inicia sesión para esta acción", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        youTubeMusicService.fetchPlaylistTracks(token, playlistId, 250, new YouTubeMusicService.PlaylistTracksCallback() {
            @Override
            public void onSuccess(java.util.List<YouTubeMusicService.PlaylistTrackResult> tracks) {
                if (!isAdded() || tracks == null || tracks.isEmpty()) return;
                SongPlayerFragment sp = findSongPlayerFragment();
                if (sp == null || !sp.isAdded()) return;
                if (insertNext) {
                    for (int i = tracks.size() - 1; i >= 0; i--) {
                        YouTubeMusicService.PlaylistTrackResult t = tracks.get(i);
                        if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                        sp.externalInsertNext(t.videoId,
                                TextUtils.isEmpty(t.title) ? "" : t.title,
                                t.artist == null ? "" : t.artist,
                                TextUtils.isEmpty(t.duration) ? "--:--" : t.duration,
                                t.thumbnailUrl == null ? "" : t.thumbnailUrl);
                    }
                } else {
                    for (YouTubeMusicService.PlaylistTrackResult t : tracks) {
                        if (t == null || TextUtils.isEmpty(t.videoId)) continue;
                        sp.externalEnqueue(t.videoId,
                                TextUtils.isEmpty(t.title) ? "" : t.title,
                                t.artist == null ? "" : t.artist,
                                TextUtils.isEmpty(t.duration) ? "--:--" : t.duration,
                                t.thumbnailUrl == null ? "" : t.thumbnailUrl);
                    }
                }
                android.widget.Toast.makeText(requireContext(),
                        insertNext ? "Se reproducirá a continuación" : "Agregado a la fila",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                android.widget.Toast.makeText(requireContext(), "No se pudo cargar la playlist", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeletePlaylistConfirmDialog(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Eliminar playlist")
            .setMessage("¿Estás seguro de que quieres eliminar la playlist \"" + playlistTrack.title + "\"?")
            .setPositiveButton("Eliminar", (dialog, which) -> {
                boolean deleted = CustomPlaylistsStore.INSTANCE.deletePlaylist(requireContext(), playlistTrack.title);
                if (deleted) {
                    // Also delete from cloud
                    CloudSyncManager.getInstance(requireContext()).deleteCloudPlaylist(playlistTrack.title);
                    // Remove from local list immediately for real-time feel
                    String contentId = playlistTrack.contentId;
                    for (int i = 0; i < libraryTracks.size(); i++) {
                        if (TextUtils.equals(libraryTracks.get(i).contentId, contentId)) {
                            libraryTracks.remove(i);
                            break;
                        }
                    }
                    // Re-render library list
                    renderLibraryResults();
                    // Refresh from source to ensure consistency
                    fetchLibraryPlaylists(true);
                    android.widget.Toast.makeText(requireContext(), "Playlist eliminada", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(requireContext(), "No se pudo eliminar la playlist", android.widget.Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
    private void showDeleteCloudPlaylistConfirmDialog(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Eliminar de la nube")
            .setMessage("¿Eliminar la playlist \"" + playlistTrack.title + "\" de la nube?\n\nSe eliminará solo de tu cuenta sincronizada.")
            .setPositiveButton("Eliminar", (dialog, which) -> {
                CloudSyncManager.getInstance(requireContext()).deleteCloudPlaylist(playlistTrack.title);
                String contentId = playlistTrack.contentId;
                for (int i = 0; i < libraryTracks.size(); i++) {
                    if (TextUtils.equals(libraryTracks.get(i).contentId, contentId)) {
                        libraryTracks.remove(i);
                        break;
                    }
                }
                renderLibraryResults();
                android.widget.Toast.makeText(requireContext(), "Playlist eliminada de la nube", android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
    private void deletePlaylistDownloads(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) return;
        String playlistId = playlistTrack.contentId == null ? "" : playlistTrack.contentId.trim();
        if (playlistId.isEmpty()) return;
        // Cancel any active download worker for this playlist before deleting files
        WorkManager.getInstance(requireContext().getApplicationContext())
                .cancelAllWorkByTag(OFFLINE_DOWNLOAD_UNIQUE_PREFIX + playlistId);
        // Get tracks from this playlist and delete their downloads
        java.util.List<String> trackIds = new java.util.ArrayList<>();
        // Check if it's a custom playlist
        java.util.List<String> customPlaylists = CustomPlaylistsStore.INSTANCE.getAllPlaylistNames(requireContext());
        boolean isCustomPlaylist = false;
        for (String name : customPlaylists) {
            if (name.equalsIgnoreCase(playlistTrack.title)) {
                isCustomPlaylist = true;
                java.util.List<FavoritesPlaylistStore.FavoriteTrack> tracks = 
                    CustomPlaylistsStore.INSTANCE.getTracksFromPlaylist(requireContext(), name);
                for (FavoritesPlaylistStore.FavoriteTrack track : tracks) {
                    if (!track.videoId.isEmpty()) {
                        trackIds.add(track.videoId);
                    }
                }
                break;
            }
        }
        // For non-custom playlists, load tracks from cached playlist data
        if (!isCustomPlaylist) {
            ArrayList<CachedPlaylistTrack> cached = loadCachedPlaylistTracksForOffline(playlistId);
            for (CachedPlaylistTrack ct : cached) {
                if (!TextUtils.isEmpty(ct.videoId)) {
                    trackIds.add(ct.videoId);
                }
            }
        }
        // Delete offline audio for all tracks
        int deleted = OfflineAudioStore.deleteOfflineAudio(requireContext(), trackIds);
        // Clear offline enabled state for this playlist
        persistPlaylistOfflineAutoEnabled(playlistId, false);
        persistPlaylistOfflineComplete(playlistId, false);
        // Refresh UI
        if (adapter != null) {
            adapter.invalidatePlaylistOfflineState(playlistId);
        }
        String message = deleted > 0 ? "Eliminadas " + deleted + " descargas" : "No hay descargas para eliminar";
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show();
    }
    private void dismissPlaylistActionTooltip() {
        if (playlistActionPopupWindow != null) {
            playlistActionPopupWindow.dismiss();
            playlistActionPopupWindow = null;
        }
    }
    private void enqueuePlaylistOfflineDownload(@NonNull YouTubeMusicService.TrackResult playlistTrack) {
        if (!isAdded()) {
            return;
        }
        String playlistId = playlistTrack.contentId == null ? "" : playlistTrack.contentId.trim();
        if (TextUtils.isEmpty(playlistId)) {
            return;
        }
        offlineSyncRecentlyProcessedPlaylistIds.remove(playlistId);
        String playlistTitle = playlistTrack.title == null ? "" : playlistTrack.title.trim();
        if (TextUtils.isEmpty(playlistTitle)) {
            playlistTitle = playlistId;
        }
        persistPlaylistOfflineAutoEnabled(playlistId, true);
        if (adapter != null) {
            Map<String, Float> immediateProgress = new HashMap<>(adapter.playlistDownloadProgressCache);
            immediateProgress.put(playlistId, 0f);
            Set<String> immediateDownloading = new HashSet<>(adapter.playlistsCurrentlyDownloading);
            immediateDownloading.add(playlistId);
            adapter.updateDownloadProgress(immediateProgress, immediateDownloading);
        }
        enqueuePlaylistOfflineDownloadInternal(playlistId, playlistTitle, true);
    }
    private boolean enqueuePlaylistOfflineDownloadInternal(
            @NonNull String playlistId,
            @NonNull String playlistTitle,
            boolean userInitiated
    ) {
        if (!isAdded()) {
            return false;
        }
        ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(playlistId);
        if (cachedTracks.isEmpty()) {
            return false;
        }
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int skippedAlreadyOffline = 0;
        int totalWithVideoId = 0;
        for (CachedPlaylistTrack track : cachedTracks) {
            if (TextUtils.isEmpty(track.videoId)) {
                continue;
            }
            if (!seen.add(track.videoId)) {
                continue;
            }
            totalWithVideoId++;
            if (OfflineAudioStore.hasValidatedOfflineAudio(requireContext(), track.videoId, track.duration)) {
                skippedAlreadyOffline++;
                continue;
            }
            ids.add(track.videoId);
            titles.add(track.title);
            artists.add(track.artist);
            durations.add(track.duration);
        }
        if (totalWithVideoId <= 0) {
            persistPlaylistOfflineComplete(playlistId, true);
            if (adapter != null) {
                adapter.invalidatePlaylistOfflineState(playlistId);
            }
            if (!userInitiated) {
                maybeEnqueueNextOfflineAutoPlaylist();
            }
            return false;
        }
        if (ids.isEmpty()) {
            persistPlaylistOfflineComplete(playlistId, true);
            if (adapter != null) {
                adapter.invalidatePlaylistOfflineState(playlistId);
            }
            if (!userInitiated) {
                maybeEnqueueNextOfflineAutoPlaylist();
            }
            return false;
        }
        Data input = new Data.Builder()
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, playlistId)
            .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, playlistTitle)
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, ids.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, titles.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, artists.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, durations.toArray(new String[0]))
                .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, skippedAlreadyOffline)
                .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, totalWithVideoId)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, userInitiated)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, false)
                .build();
        SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
                .addTag(OFFLINE_DOWNLOAD_UNIQUE_PREFIX + playlistId)
                .build();
        enqueueOfflineDownloadUniqueWork(request);
        if (adapter != null) {
            adapter.invalidatePlaylistOfflineState(playlistId);
        }
        return true;
    }
    private void enqueueOfflineDownloadUniqueWork(@NonNull OneTimeWorkRequest request) {
        WorkManager manager = WorkManager.getInstance(requireContext().getApplicationContext());
        manager.enqueueUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME, ExistingWorkPolicy.KEEP, request);
    }
    private void startObservingOfflineQueue() {
        if (!isAdded()) {
            return;
        }
        if (offlineQueueObserver == null) {
            offlineQueueObserver = this::onOfflineQueueWorkInfosChanged;
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .getWorkInfosForUniqueWorkLiveData(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
                    .observe(getViewLifecycleOwner(), offlineQueueObserver);
        }
        if (offlineManualQueueObserver == null) {
            offlineManualQueueObserver = this::onOfflineManualQueueWorkInfosChanged;
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .getWorkInfosForUniqueWorkLiveData(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)
                    .observe(getViewLifecycleOwner(), offlineManualQueueObserver);
        }
    }
    private void stopObservingOfflineQueue() {
        if (!isAdded()) {
            offlineQueueObserver = null;
            offlineManualQueueObserver = null;
            return;
        }
        if (offlineQueueObserver != null) {
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .getWorkInfosForUniqueWorkLiveData(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
                    .removeObserver(offlineQueueObserver);
        }
        if (offlineManualQueueObserver != null) {
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .getWorkInfosForUniqueWorkLiveData(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)
                    .removeObserver(offlineManualQueueObserver);
        }
        offlineQueueObserver = null;
        offlineManualQueueObserver = null;
    }
    private void onOfflineQueueWorkInfosChanged(@Nullable List<WorkInfo> workInfos) {
        if (!isAdded()) {
            return;
        }
        boolean hasActiveWork = hasActiveOfflineWork(workInfos);
        boolean hasEnqueuedWork = hasEnqueuedOfflineWork(workInfos);
        boolean hasBlockedWork = hasBlockedOfflineWork(workInfos);

        if (!hasActiveWork && !hasEnqueuedWork && hasBlockedWork) {
            Log.w(TAG_STREAMING, "offline_queue: detected BLOCKED-only work, resetting queue to unblock");
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .cancelUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME);
            offlineQueueHadActiveWork = false;
            synchronized (fragmentLock) {
                isOfflineSyncInFlight = false;
            }
            mainHandler.postDelayed(() -> {
                if (!isAdded()) return;
                maybeEnqueueNextOfflineAutoPlaylist();
            }, 800L);
            return;
        }

        // Extract per-playlist download progress for circular indicator
        Map<String, Float> progressByPlaylist = new HashMap<>();
        Set<String> downloading = new HashSet<>();
        if (workInfos != null) {
            for (WorkInfo info : workInfos) {
                if (info == null) continue;
                WorkInfo.State state = info.getState();
                String playlistId = extractPlaylistIdFromTags(info);
                if (state != WorkInfo.State.RUNNING && state != WorkInfo.State.ENQUEUED) continue;

                if (TextUtils.isEmpty(playlistId)) continue;

                downloading.add(playlistId);

                if (state == WorkInfo.State.RUNNING) {
                    Data progress = info.getProgress();
                    int downloaded = progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DOWNLOADED, 0);
                    int total = progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_TOTAL, 0);
                    if (total > 0) {
                        float fraction = Math.max(0f, Math.min(1f,
                                downloaded / (float) total));
                        progressByPlaylist.put(playlistId, fraction);
                    } else {
                        // Worker hasn't emitted first progress yet — retain previous fraction
                        Float previous = autoQueueProgressByPlaylist.get(playlistId);
                        if (previous != null) {
                            progressByPlaylist.put(playlistId, previous);
                        }
                    }
                }
            }
        }
        autoQueueProgressByPlaylist.clear();
        autoQueueProgressByPlaylist.putAll(progressByPlaylist);
        autoQueueDownloading.clear();
        autoQueueDownloading.addAll(downloading);
        dispatchMergedDownloadProgress();

        if (hasActiveWork) {
            offlineQueueHadActiveWork = true;
            if (!hasEnqueuedWork) {
                maybeEnqueueNextOfflineAutoPlaylist();
            }
            return;
        }
        if (offlineQueueHadActiveWork && adapter != null) {
            adapter.invalidatePlaylistOfflineState(null);
        }
        offlineQueueHadActiveWork = false;
        synchronized (fragmentLock) {
            isOfflineSyncInFlight = false;
        }
        maybeEnqueueNextOfflineAutoPlaylist();
    }

    @Nullable
    private String extractPlaylistIdFromTags(@NonNull WorkInfo info) {
        for (String tag : info.getTags()) {
            if (tag.equals(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
                    || tag.equals(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)) {
                continue;
            }
            if (tag.startsWith(OFFLINE_DOWNLOAD_UNIQUE_PREFIX)) {
                String id = tag.substring(OFFLINE_DOWNLOAD_UNIQUE_PREFIX.length()).trim();
                if (!id.isEmpty()) return id;
            }
        }
        return null;
    }
    private void onOfflineManualQueueWorkInfosChanged(@Nullable List<WorkInfo> workInfos) {
        if (!isAdded()) {
            return;
        }
        // Extract progress from manual queue too
        Map<String, Float> progressByPlaylist = new HashMap<>();
        Set<String> downloading = new HashSet<>();
        if (workInfos != null) {
            for (WorkInfo info : workInfos) {
                if (info == null) continue;
                WorkInfo.State state = info.getState();
                if (state != WorkInfo.State.RUNNING && state != WorkInfo.State.ENQUEUED) continue;
                String playlistId = extractPlaylistIdFromTags(info);
                if (TextUtils.isEmpty(playlistId)) continue;
                downloading.add(playlistId);
                if (state == WorkInfo.State.RUNNING) {
                    Data progress = info.getProgress();
                    int downloaded = progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DOWNLOADED, 0);
                    int total = progress.getInt(OfflinePlaylistDownloadWorker.PROGRESS_TOTAL, 0);
                    if (total > 0) {
                        float fraction = Math.max(0f, Math.min(1f,
                                downloaded / (float) total));
                        progressByPlaylist.put(playlistId, fraction);
                    } else {
                        // Worker hasn't emitted first progress yet — retain previous fraction
                        Float previous = manualQueueProgressByPlaylist.get(playlistId);
                        if (previous != null) {
                            progressByPlaylist.put(playlistId, previous);
                        }
                    }
                }
            }
        }
        manualQueueProgressByPlaylist.clear();
        manualQueueProgressByPlaylist.putAll(progressByPlaylist);
        manualQueueDownloading.clear();
        manualQueueDownloading.addAll(downloading);
        dispatchMergedDownloadProgress();

        boolean hasActiveWork = hasActiveOfflineWork(workInfos);
        if (hasActiveWork) {
            offlineManualQueueHadActiveWork = true;
            return;
        }
        if (offlineManualQueueHadActiveWork && adapter != null) {
            adapter.invalidatePlaylistOfflineState(null);
        }
        offlineManualQueueHadActiveWork = false;
    }
    private void dispatchMergedDownloadProgress() {
        if (adapter == null) return;
        Map<String, Float> merged = new HashMap<>(autoQueueProgressByPlaylist);
        merged.putAll(manualQueueProgressByPlaylist);
        Set<String> mergedDownloading = new HashSet<>(autoQueueDownloading);
        mergedDownloading.addAll(manualQueueDownloading);
        if (!mergedDownloading.isEmpty() || !adapter.playlistsCurrentlyDownloading.isEmpty()) {
            adapter.updateDownloadProgress(merged, mergedDownloading);
        }
    }
    private boolean hasActiveOfflineWork(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null || workInfos.isEmpty()) {
            return false;
        }
        for (WorkInfo info : workInfos) {
            if (info == null) {
                continue;
            }
            WorkInfo.State state = info.getState();
            if (state == WorkInfo.State.RUNNING
                    || state == WorkInfo.State.ENQUEUED) {
                return true;
            }
        }
        return false;
    }
    private boolean hasEnqueuedOfflineWork(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null || workInfos.isEmpty()) {
            return false;
        }
        for (WorkInfo info : workInfos) {
            if (info == null) {
                continue;
            }
            if (info.getState() == WorkInfo.State.ENQUEUED) {
                return true;
            }
        }
        return false;
    }
    private boolean hasBlockedOfflineWork(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null || workInfos.isEmpty()) {
            return false;
        }
        for (WorkInfo info : workInfos) {
            if (info == null) {
                continue;
            }
            if (info.getState() == WorkInfo.State.BLOCKED) {
                return true;
            }
        }
        return false;
    }
    /**
     * Called after pull-to-refresh succeeds. Checks if any offline-auto-enabled playlists
     * have stalled downloads (no RUNNING/ENQUEUED work) and re-enqueues them.
     * Skips if there are already 3+ active/enqueued workers to avoid flooding.
     */
    private void maybeResumeStarledOfflineDownloads() {
        if (!isAdded()) return;
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        wm.getWorkInfosForUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
                .addListener(() -> {
                    try {
                        List<WorkInfo> infos = wm.getWorkInfosForUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME).get();
                        int activeCount = 0;
                        boolean hasBlocked = false;
                        boolean hasEnqueued = false;
                        if (infos != null) {
                            for (WorkInfo info : infos) {
                                WorkInfo.State state = info.getState();
                                if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) {
                                    activeCount++;
                                }
                                if (state == WorkInfo.State.BLOCKED) hasBlocked = true;
                                if (state == WorkInfo.State.ENQUEUED) hasEnqueued = true;
                            }
                        }
                        final int finalActiveCount = activeCount;
                        final boolean onlyBlocked = hasBlocked && !hasEnqueued && activeCount == 0;
                        if (onlyBlocked) {
                            Log.w(TAG_STREAMING, "resume_stalled: BLOCKED-only chain detected, cancelling to unblock");
                            wm.cancelUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME);
                        }
                        if (!onlyBlocked && finalActiveCount >= 1) {
                            return;
                        }
                        mainHandler.postDelayed(() -> {
                            if (!isAdded()) return;
                            Log.i(TAG_STREAMING, "pull_refresh: resuming stalled offline downloads (active=" + finalActiveCount + " onlyBlocked=" + onlyBlocked + ")");
                            offlineSyncRecentlyProcessedPlaylistIds.clear();
                            synchronized (fragmentLock) {
                                isOfflineSyncInFlight = false;
                            }
                            maybeEnqueueNextOfflineAutoPlaylist();
                        }, onlyBlocked ? 600L : 0L);
                    } catch (Exception e) {
                        Log.w(TAG_STREAMING, "pull_refresh: failed to check work status", e);
                    }
                }, offlineStateExecutor);
    }
    private void maybeEnqueueNextOfflineAutoPlaylist() {
        if (!isAdded()) return;
        synchronized (fragmentLock) {
            if (isOfflineSyncInFlight || offlineAutoQueueScanInFlight) {
                return;
            }
        }
        Context appContext = requireContext().getApplicationContext();
        ArrayList<YouTubeMusicService.TrackResult> playlistsSnapshot = new ArrayList<>(libraryTracks);
        offlineAutoQueueScanInFlight = true;
        offlineStateExecutor.execute(() -> {
            PendingOfflineBatchCandidate candidate = resolveNextPendingOfflineAutoBatch(appContext, playlistsSnapshot);
            mainHandler.post(() -> {
                offlineAutoQueueScanInFlight = false;
                if (!isAdded() || candidate == null) {
                    return;
                }
                if (offlineSyncRecentlyProcessedPlaylistIds.contains(candidate.primaryPlaylistId)) {
                    return;
                }
                synchronized (fragmentLock) {
                    isOfflineSyncInFlight = true;
                }
                final String primaryId = candidate.primaryPlaylistId;
                offlineSyncRecentlyProcessedPlaylistIds.add(primaryId);
                mainHandler.postDelayed(() -> {
                    offlineSyncRecentlyProcessedPlaylistIds.remove(primaryId);
                }, 60000);
                boolean enqueued = enqueueOfflineAutoBatchDownloadInternal(candidate);
                if (enqueued && adapter != null) {
                    for (String playlistId : candidate.playlistIds) {
                        adapter.invalidatePlaylistOfflineState(playlistId);
                    }
                }
            });
        });
    }
    @Nullable
    private PendingOfflineBatchCandidate resolveNextPendingOfflineAutoBatch(
            @NonNull Context appContext,
            @NonNull List<YouTubeMusicService.TrackResult> playlistsSnapshot
    ) {
        ArrayList<PendingOfflinePlaylistCandidate> candidates = new ArrayList<>();
        for (YouTubeMusicService.TrackResult playlist : playlistsSnapshot) {
            if (playlist == null || !"playlist".equals(playlist.resultType)) {
                continue;
            }
            String playlistId = playlist.contentId == null ? "" : playlist.contentId.trim();
            if (TextUtils.isEmpty(playlistId) || !isPersistedPlaylistOfflineAutoEnabled(playlistId)) {
                continue;
            }
            if (isPlaylistFullyOffline(appContext, playlistId)) {
                continue;
            }
            ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(appContext, playlistId);
            if (cachedTracks.isEmpty()) {
                continue;
            }
            int pendingCount = countPendingOfflineTracks(appContext, cachedTracks);
            if (pendingCount <= 0) {
                persistPlaylistOfflineComplete(appContext, playlistId, true);
                continue;
            }
            String playlistTitle = playlist.title == null ? "" : playlist.title.trim();
            if (TextUtils.isEmpty(playlistTitle)) {
                playlistTitle = playlistId;
            }
            candidates.add(new PendingOfflinePlaylistCandidate(playlistId, playlistTitle, pendingCount, cachedTracks));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates, (left, right) -> Integer.compare(left.pendingCount, right.pendingCount));
        HashSet<String> seenIds = new HashSet<>();
        ArrayList<PendingOfflineTrackCandidate> batchTracks = new ArrayList<>();
        ArrayList<String> batchPlaylistIds = new ArrayList<>();
        String primaryPlaylistId = "";
        String primaryPlaylistTitle = "";
        for (PendingOfflinePlaylistCandidate candidate : candidates) {
            int addedFromPlaylist = 0;
            for (CachedPlaylistTrack track : candidate.cachedTracks) {
                if (track == null || TextUtils.isEmpty(track.videoId)) {
                    continue;
                }
                if (!seenIds.add(track.videoId)) {
                    continue;
                }
                if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)) {
                    continue;
                }
                batchTracks.add(new PendingOfflineTrackCandidate(track.videoId, track.title, track.artist, track.duration));
                addedFromPlaylist++;
                if (batchTracks.size() >= OFFLINE_AUTO_BATCH_TARGET_TRACKS) {
                    break;
                }
            }
            if (addedFromPlaylist > 0) {
                batchPlaylistIds.add(candidate.playlistId);
                if (TextUtils.isEmpty(primaryPlaylistId)) {
                    primaryPlaylistId = candidate.playlistId;
                    primaryPlaylistTitle = candidate.playlistTitle;
                }
            }
            if (batchTracks.size() >= OFFLINE_AUTO_BATCH_TARGET_TRACKS) {
                break;
            }
        }
        if (batchTracks.isEmpty() || TextUtils.isEmpty(primaryPlaylistId)) {
            return null;
        }
        return new PendingOfflineBatchCandidate(
                primaryPlaylistId,
                primaryPlaylistTitle,
                batchPlaylistIds,
                batchTracks
        );
    }
    private boolean enqueueOfflineAutoBatchDownloadInternal(@NonNull PendingOfflineBatchCandidate candidate) {
        if (!isAdded() || candidate.tracks.isEmpty()) {
            return false;
        }
        ArrayList<String> ids = new ArrayList<>(candidate.tracks.size());
        ArrayList<String> titles = new ArrayList<>(candidate.tracks.size());
        ArrayList<String> artists = new ArrayList<>(candidate.tracks.size());
        ArrayList<String> durations = new ArrayList<>(candidate.tracks.size());
        for (PendingOfflineTrackCandidate track : candidate.tracks) {
            ids.add(track.videoId);
            titles.add(track.title);
            artists.add(track.artist);
            durations.add(track.duration);
        }
        Data input = new Data.Builder()
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, candidate.primaryPlaylistId)
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, candidate.primaryPlaylistTitle)
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, ids.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, titles.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, artists.toArray(new String[0]))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, durations.toArray(new String[0]))
                .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, ids.size())
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, false)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, false)
                .build();
        SharedPreferences prefs = requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                .build();
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME);
        for (String playlistId : candidate.playlistIds) {
            if (TextUtils.isEmpty(playlistId)) {
                continue;
            }
            requestBuilder.addTag(OFFLINE_DOWNLOAD_UNIQUE_PREFIX + playlistId);
        }
        enqueueOfflineDownloadUniqueWork(requestBuilder.build());
        return true;
    }
    private int countPendingOfflineTracks(
            @NonNull Context appContext,
            @NonNull List<CachedPlaylistTrack> cachedTracks
    ) {
        int pending = 0;
        HashSet<String> seen = new HashSet<>();
        for (CachedPlaylistTrack track : cachedTracks) {
            if (track == null || TextUtils.isEmpty(track.videoId)) {
                continue;
            }
            if (!seen.add(track.videoId)) {
                continue;
            }
            if (!OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)) {
                pending++;
            }
        }
        return pending;
    }
    private boolean isPlaylistFullyOffline(@NonNull String playlistId) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(playlistId)) {
            return false;
        }
        return isPlaylistFullyOffline(context.getApplicationContext(), playlistId);
    }
    private boolean isPlaylistFullyOffline(@NonNull Context appContext, @NonNull String playlistId) {
        if (TextUtils.isEmpty(playlistId)) {
            return false;
        }
        ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(appContext, playlistId);
        if (cachedTracks.isEmpty()) {
            return isPersistedPlaylistOfflineComplete(appContext, playlistId);
        }
        HashSet<String> seen = new HashSet<>();
        int eligibleCount = 0;
        int offlineCount = 0;
        for (CachedPlaylistTrack track : cachedTracks) {
            if (TextUtils.isEmpty(track.videoId) || seen.contains(track.videoId)) {
                continue;
            }
            seen.add(track.videoId);
            eligibleCount++;
            if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)) {
                offlineCount++;
            }
        }
        if (eligibleCount <= 0) {
            persistPlaylistOfflineComplete(appContext, playlistId, true);
            return true;
        }
        boolean complete = offlineCount >= eligibleCount;
        persistPlaylistOfflineComplete(appContext, playlistId, complete);
        return complete;
    }
    private void persistPlaylistOfflineComplete(@NonNull String playlistId, boolean complete) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(playlistId)) {
            return;
        }
        persistPlaylistOfflineComplete(context.getApplicationContext(), playlistId, complete);
    }
    private void persistPlaylistOfflineComplete(
            @NonNull Context context,
            @NonNull String playlistId,
            boolean complete
    ) {
        if (TextUtils.isEmpty(playlistId)) {
            return;
        }
        getCachePrefs(context).edit()
                .putBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + playlistId, complete)
                .apply();
    }
    private boolean isPersistedPlaylistOfflineComplete(@NonNull String playlistId) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(playlistId)) {
            return false;
        }
        return isPersistedPlaylistOfflineComplete(context.getApplicationContext(), playlistId);
    }
    private boolean isPersistedPlaylistOfflineComplete(
            @NonNull Context context,
            @NonNull String playlistId
    ) {
        if (TextUtils.isEmpty(playlistId)) {
            return false;
        }
        return getCachePrefs(context).getBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + playlistId, false);
    }
    private void persistPlaylistOfflineAutoEnabled(@NonNull String playlistId, boolean enabled) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(playlistId)) {
            return;
        }
        getCachePrefs(context.getApplicationContext()).edit()
                .putBoolean(PREF_PLAYLIST_OFFLINE_AUTO_PREFIX + playlistId, enabled)
                .apply();
    }
    private boolean isPersistedPlaylistOfflineAutoEnabled(@NonNull String playlistId) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(playlistId)) {
            return false;
        }
        return getCachePrefs(context.getApplicationContext())
                .getBoolean(PREF_PLAYLIST_OFFLINE_AUTO_PREFIX + playlistId, false);
    }
    @NonNull
    private ArrayList<CachedPlaylistTrack> loadCachedPlaylistTracksForOffline(@NonNull String playlistId) {
        Context context = getContext();
        if (context == null) {
            return new ArrayList<>();
        }
        return loadCachedPlaylistTracksForOffline(context.getApplicationContext(), playlistId);
    }
    @NonNull
    private ArrayList<CachedPlaylistTrack> loadCachedPlaylistTracksForOffline(
            @NonNull Context context,
            @NonNull String playlistId
    ) {
        ArrayList<CachedPlaylistTrack> result = new ArrayList<>();
        if (TextUtils.isEmpty(playlistId)) {
            return result;
        }
        SharedPreferences prefs = getCachePrefs(context);
        long updatedAt = prefs.getLong(PREF_TRACKS_UPDATED_AT_PREFIX + playlistId, 0L);
        if (updatedAt > 0L) {
            synchronized (cachedPlaylistTracksLock) {
                Long cachedUpdatedAt = cachedPlaylistTracksUpdatedAtById.get(playlistId);
                ArrayList<CachedPlaylistTrack> cachedTracks = cachedPlaylistTracksById.get(playlistId);
                if (cachedUpdatedAt != null && cachedTracks != null && cachedUpdatedAt == updatedAt) {
                    return new ArrayList<>(cachedTracks);
                }
            }
        }
        String raw = prefs.getString(PREF_TRACKS_DATA_PREFIX + playlistId, "");
        if (TextUtils.isEmpty(raw)) {
            synchronized (cachedPlaylistTracksLock) {
                cachedPlaylistTracksById.remove(playlistId);
                cachedPlaylistTracksUpdatedAtById.remove(playlistId);
            }
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
                String duration = obj.optString("duration", "").trim();
                String imageUrl = obj.optString("imageUrl", "").trim();
                if (TextUtils.isEmpty(videoId)) {
                    continue;
                }
                result.add(new CachedPlaylistTrack(videoId, title, artist, duration, imageUrl));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }

        // Apply playlist overrides so replaced tracks are correctly detected as offline
        // The audio file is saved with the replacement videoId, not the original
        if (!playlistId.isEmpty()) {
            java.util.Map<String, PlaylistOverrideStore.Override> overridesMap =
                    PlaylistOverrideStore.INSTANCE.getOverrides(context, playlistId);
            if (!overridesMap.isEmpty()) {
                ArrayList<CachedPlaylistTrack> overridden = new ArrayList<>();
                for (CachedPlaylistTrack track : result) {
                    PlaylistOverrideStore.Override ovr = overridesMap.get(track.videoId);
                    if (ovr != null) {
                        // Track was replaced - use replacement videoId for offline check
                        overridden.add(new CachedPlaylistTrack(
                                ovr.getReplacementVideoId(),
                                ovr.getTitle(),
                                ovr.getArtist(),
                                ovr.getDuration(),
                                ovr.getImageUrl()
                        ));
                    } else {
                        overridden.add(track);
                    }
                }
                result = overridden;
            }
        }

        synchronized (cachedPlaylistTracksLock) {
            cachedPlaylistTracksById.put(playlistId, new ArrayList<>(result));
            cachedPlaylistTracksUpdatedAtById.put(playlistId, updatedAt);
        }
        return result;
    }
    /**
     * Returns the first 4 unique, non-empty thumbnail URLs for a playlist.
     * Uses {@link #playlistGridUrlsCache} to avoid recomputing on every bind.
     */
    @NonNull
    private List<String> resolvePlaylistGridUrls(@NonNull String playlistId) {
        if (TextUtils.isEmpty(playlistId)) return java.util.Collections.emptyList();
        List<String> cached = playlistGridUrlsCache.get(playlistId);
        if (cached != null) return cached;

        // For YouTube playlists, check persisted grid first (generate-once)
        boolean isYouTubePlaylist = !FavoritesPlaylistStore.PLAYLIST_ID.equals(playlistId)
                && !playlistId.startsWith("custom_");
        if (isYouTubePlaylist && isAdded()) {
            List<String> persisted = loadPersistedGridUrls(playlistId);
            if (persisted.size() >= 4) {
                playlistGridUrlsCache.put(playlistId, persisted);
                return persisted;
            }
        }

        ArrayList<CachedPlaylistTrack> tracks = loadCachedPlaylistTracksForOffline(playlistId);
        List<String> urls = new ArrayList<>(4);
        Set<String> seen = new HashSet<>();
        for (CachedPlaylistTrack t : tracks) {
            if (urls.size() >= 4) break;
            if (t == null || TextUtils.isEmpty(t.imageUrl)) continue;
            String url = t.imageUrl.trim();
            if (url.isEmpty() || !seen.add(url)) continue;
            urls.add(url);
        }
        if (urls.size() >= 4) {
            playlistGridUrlsCache.put(playlistId, urls);
            // Persist for YouTube playlists so grid survives pull-to-refresh and restarts
            if (isYouTubePlaylist && isAdded()) {
                persistGridUrls(playlistId, urls);
            }
        }
        return urls;
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
        } catch (Exception ignored) {}
    }
    @NonNull
    private List<String> loadPersistedGridUrls(@NonNull String playlistId) {
        try {
            String raw = getCachePrefs().getString(PREF_PLAYLIST_GRID_URLS_PREFIX + playlistId, "");
            if (TextUtils.isEmpty(raw)) return java.util.Collections.emptyList();
            String[] parts = raw.split("\\n");
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }
    private int withAlpha(int color, float alpha) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        int alphaInt = Math.round(255f * clamped);
        return (color & 0x00FFFFFF) | (alphaInt << 24);
    }
    private int dp(int value) {
        if (!isAdded()) {
            return value;
        }
        return Math.round(requireContext().getResources().getDisplayMetrics().density * value);
    }
    private void launchSearchActivity() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openSearchFragment();
        }
    }
    public void playTrackFromSearch(@NonNull Intent data) {
        if (!isAdded()) return;
        playTrackFromSearchInternal(data);
    }
    private void playTrackFromSearchInternal(@NonNull Intent data) {
        String resultType = data.getStringExtra(SearchFragment.EXTRA_RESULT_TYPE);
        String videoId = data.getStringExtra(SearchFragment.EXTRA_RESULT_VIDEO_ID);
        String contentId = data.getStringExtra(SearchFragment.EXTRA_RESULT_CONTENT_ID);
        String title = data.getStringExtra(SearchFragment.EXTRA_RESULT_TITLE);
        String subtitle = data.getStringExtra(SearchFragment.EXTRA_RESULT_SUBTITLE);
        String thumbnailUrl = data.getStringExtra(SearchFragment.EXTRA_RESULT_THUMBNAIL);
        String queueJson = data.getStringExtra(SearchFragment.EXTRA_RESULT_TRACKS_JSON);
        YouTubeMusicService.TrackResult track = new YouTubeMusicService.TrackResult(
                resultType, contentId, title, subtitle, thumbnailUrl);

        // Parse fallback queue from local playlists (used only if radio fetch fails)
        final List<YouTubeMusicService.TrackResult> fallbackTracks = new ArrayList<>();
        try {
            if (queueJson != null) {
                org.json.JSONArray array = new org.json.JSONArray(queueJson);
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject obj = array.getJSONObject(i);
                    YouTubeMusicService.TrackResult qTrack = new YouTubeMusicService.TrackResult(
                            obj.optString("resultType", ""),
                            obj.optString("contentId", ""),
                            obj.optString("title", ""),
                            obj.optString("subtitle", ""),
                            obj.optString("thumbnailUrl", "")
                    );
                    fallbackTracks.add(qTrack);
                }
            }
        } catch (Exception ignored) {
        }

        // For playlist types, open normally
        if ("playlist".equals(resultType)) {
            tracks.clear();
            featuredTrack = track;
            tracks.addAll(fallbackTracks);
            openTrack(track, true);
            return;
        }

        // Play the single track instantly (no queue delay)
        tracks.clear();
        featuredTrack = track;
        tracks.add(track);
        openTrack(track, true);

        // Fetch radio in background to enrich the queue
        if (!TextUtils.isEmpty(videoId)) {
            String cookie = "";
            if (isAdded()) {
                SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
                String raw = prefs.getString(PREF_LAST_YOUTUBE_WEB_COOKIE, "");
                cookie = raw != null ? raw.trim() : "";
            }
            String radioPlaylistId = "RDAMVM" + videoId;
            final String selectedVideoId = videoId;
            final YouTubeMusicService.TrackResult selectedTrack = track;
            final List<YouTubeMusicService.TrackResult> localFallback = fallbackTracks;
            youTubeMusicService.fetchMixTracks(cookie, radioPlaylistId, new YouTubeMusicService.MixTracksCallback() {
                @Override
                public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> radioTracks) {
                    if (!isAdded() || radioTracks.isEmpty()) {
                        applyFallbackQueue(selectedTrack, localFallback);
                        return;
                    }
                    // Build radio queue: selected track first, then radio tracks (deduplicated)
                    ArrayList<String> ids = new ArrayList<>();
                    ArrayList<String> titles = new ArrayList<>();
                    ArrayList<String> artists = new ArrayList<>();
                    ArrayList<String> durations = new ArrayList<>();
                    ArrayList<String> images = new ArrayList<>();
                    ids.add(selectedVideoId);
                    titles.add(TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title);
                    artists.add(selectedTrack.subtitle == null ? "" : selectedTrack.subtitle);
                    durations.add("--:--");
                    images.add(selectedTrack.thumbnailUrl == null ? "" : selectedTrack.thumbnailUrl);
                    for (YouTubeMusicService.TrackResult t : radioTracks) {
                        if (TextUtils.isEmpty(t.videoId) || TextUtils.equals(t.videoId, selectedVideoId)) continue;
                        ids.add(t.videoId);
                        titles.add(TextUtils.isEmpty(t.title) ? "" : t.title);
                        artists.add(t.subtitle == null ? "" : t.subtitle);
                        durations.add("--:--");
                        images.add(t.thumbnailUrl == null ? "" : t.thumbnailUrl);
                    }
                    SongPlayerFragment sp = findSongPlayerFragment();
                    if (sp != null && sp.isAdded() && !ids.isEmpty()) {
                        sp.externalReplaceQueue(ids, titles, artists, durations, images, 0, true);
                    }
                    // Save radio to history for library display
                    List<RadioHistoryStore.RadioTrack> radioStoreTracks = new ArrayList<>();
                    radioStoreTracks.add(new RadioHistoryStore.RadioTrack(
                            selectedVideoId,
                            TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title,
                            selectedTrack.subtitle == null ? "" : selectedTrack.subtitle,
                            selectedTrack.thumbnailUrl == null ? "" : selectedTrack.thumbnailUrl
                    ));
                    for (YouTubeMusicService.TrackResult t : radioTracks) {
                        if (TextUtils.isEmpty(t.videoId) || TextUtils.equals(t.videoId, selectedVideoId)) continue;
                        radioStoreTracks.add(new RadioHistoryStore.RadioTrack(
                                t.videoId,
                                TextUtils.isEmpty(t.title) ? "" : t.title,
                                t.subtitle == null ? "" : t.subtitle,
                                t.thumbnailUrl == null ? "" : t.thumbnailUrl
                        ));
                    }
                    RadioHistoryStore.INSTANCE.saveRadio(
                            requireContext(),
                            radioPlaylistId,
                            TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title,
                            selectedTrack.thumbnailUrl == null ? "" : selectedTrack.thumbnailUrl,
                            radioStoreTracks
                    );
                    refreshLibraryUi();
                }

                @Override
                public void onError(@NonNull String error) {
                    if (!isAdded()) return;
                    applyFallbackQueue(selectedTrack, localFallback);
                }
            });
        }
    }

    private void applyFallbackQueue(
            @NonNull YouTubeMusicService.TrackResult selectedTrack,
            @NonNull List<YouTubeMusicService.TrackResult> fallbackTracks
    ) {
        if (!isAdded() || fallbackTracks.isEmpty()) return;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        int selectedIndex = 0;
        for (int i = 0; i < fallbackTracks.size(); i++) {
            YouTubeMusicService.TrackResult t = fallbackTracks.get(i);
            if (t == null || TextUtils.isEmpty(t.videoId)) continue;
            if (TextUtils.equals(t.videoId, selectedTrack.videoId)) {
                selectedIndex = ids.size();
            }
            ids.add(t.videoId);
            titles.add(TextUtils.isEmpty(t.title) ? "" : t.title);
            artists.add(t.subtitle == null ? "" : t.subtitle);
            durations.add("--:--");
            images.add(t.thumbnailUrl == null ? "" : t.thumbnailUrl);
        }
        if (ids.isEmpty()) return;
        SongPlayerFragment sp = findSongPlayerFragment();
        if (sp != null && sp.isAdded()) {
            sp.externalReplaceQueue(ids, titles, artists, durations, images, selectedIndex, true);
        }
    }
    public void playNextFromSearch(@NonNull Intent data) {
        String videoId = data.getStringExtra(SearchFragment.EXTRA_RESULT_VIDEO_ID);
        String title = data.getStringExtra(SearchFragment.EXTRA_RESULT_TITLE);
        String subtitle = data.getStringExtra(SearchFragment.EXTRA_RESULT_SUBTITLE);
        String thumbnailUrl = data.getStringExtra(SearchFragment.EXTRA_RESULT_THUMBNAIL);
        SongPlayerFragment player = findSongPlayerFragment();
        if (player != null && player.isAdded()) {
            player.externalInsertNext(videoId, title, subtitle, "", thumbnailUrl);
            android.widget.Toast.makeText(requireContext(), "Se reproducirá a continuación", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    public void addToQueueFromSearch(@NonNull Intent data) {
        String videoId = data.getStringExtra(SearchFragment.EXTRA_RESULT_VIDEO_ID);
        String title = data.getStringExtra(SearchFragment.EXTRA_RESULT_TITLE);
        String subtitle = data.getStringExtra(SearchFragment.EXTRA_RESULT_SUBTITLE);
        String thumbnailUrl = data.getStringExtra(SearchFragment.EXTRA_RESULT_THUMBNAIL);
        SongPlayerFragment player = findSongPlayerFragment();
        if (player != null && player.isAdded()) {
            player.externalEnqueue(videoId, title, subtitle, "", thumbnailUrl);
            android.widget.Toast.makeText(requireContext(), "Agregado a la fila", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void openTrack(@NonNull YouTubeMusicService.TrackResult track) {
        openTrack(track, false);
    }
    private void openTrack(@NonNull YouTubeMusicService.TrackResult track, boolean startInMiniMode) {
        if ("playlist".equals(track.resultType)) {
            openPlaylistDetail(track);
            return;
        }
        if (track.isVideo()) {
            openTrackInIntegratedPlayer(track, startInMiniMode);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(track.getWatchUrl()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }
    private boolean shouldUseRadioQueueForTrack(@NonNull YouTubeMusicService.TrackResult selectedTrack) {
        if (!selectedTrack.isVideo()) {
            return false;
        }
        return activeScreen == ScreenMode.LIBRARY && !TextUtils.isEmpty(getLibraryInlineQuery());
    }
    @NonNull
    private ArrayList<YouTubeMusicService.TrackResult> buildPlaybackQueueCandidates(
            @NonNull YouTubeMusicService.TrackResult selectedTrack,
            boolean radioMode
    ) {
        ArrayList<YouTubeMusicService.TrackResult> queue = new ArrayList<>();
        HashSet<String> seenVideoIds = new HashSet<>();
        if (!TextUtils.isEmpty(selectedTrack.videoId)) {
            queue.add(selectedTrack);
            seenVideoIds.add(selectedTrack.videoId);
        }
        if (featuredTrack != null
                && featuredTrack.isVideo()
                && !TextUtils.isEmpty(featuredTrack.videoId)
                && !seenVideoIds.contains(featuredTrack.videoId)) {
            queue.add(featuredTrack);
            seenVideoIds.add(featuredTrack.videoId);
        }
        for (YouTubeMusicService.TrackResult item : tracks) {
            if (item == null || !item.isVideo() || TextUtils.isEmpty(item.videoId)) {
                continue;
            }
            if (seenVideoIds.contains(item.videoId)) {
                continue;
            }
            queue.add(item);
            seenVideoIds.add(item.videoId);
        }
        if (!radioMode || queue.size() <= 2) {
            return queue;
        }
        YouTubeMusicService.TrackResult anchor = queue.get(0);
        ArrayList<YouTubeMusicService.TrackResult> tail = new ArrayList<>(queue.subList(1, queue.size()));
        tail.sort((left, right) -> {
            int leftScore = computeRadioQueueScore(anchor, left);
            int rightScore = computeRadioQueueScore(anchor, right);
            if (leftScore != rightScore) {
                return Integer.compare(rightScore, leftScore);
            }
            String leftTitle = normalizeForFilter(left.title);
            String rightTitle = normalizeForFilter(right.title);
            return leftTitle.compareTo(rightTitle);
        });
        ArrayList<YouTubeMusicService.TrackResult> radioQueue = new ArrayList<>(queue.size());
        radioQueue.add(anchor);
        radioQueue.addAll(tail);
        return radioQueue;
    }
    private int computeRadioQueueScore(
            @NonNull YouTubeMusicService.TrackResult anchor,
            @NonNull YouTubeMusicService.TrackResult candidate
    ) {
        if (TextUtils.equals(anchor.videoId, candidate.videoId)) {
            return Integer.MAX_VALUE;
        }
        String anchorTitle = normalizeForFilter(anchor.title);
        String anchorSubtitle = normalizeForFilter(anchor.subtitle);
        String candidateTitle = normalizeForFilter(candidate.title);
        String candidateSubtitle = normalizeForFilter(candidate.subtitle);
        int score = 0;
        if (!anchorSubtitle.isEmpty() && candidateSubtitle.contains(anchorSubtitle)) {
            score += 240;
        }
        String[] tokens = extractSearchTokens((anchorTitle + " " + anchorSubtitle).trim());
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (candidateTitle.contains(token)) {
                score += 85;
            }
            if (candidateSubtitle.contains(token)) {
                score += 40;
            }
        }
        if (candidateTitle.startsWith(anchorTitle) || anchorTitle.startsWith(candidateTitle)) {
            score += 110;
        }
        if (candidateSubtitle.startsWith(anchorSubtitle) || anchorSubtitle.startsWith(candidateSubtitle)) {
            score += 60;
        }
        return score;
    }
    private void openTrackInIntegratedPlayer(@NonNull YouTubeMusicService.TrackResult selectedTrack, boolean startInMiniMode) {
        if (!isAdded()) {
            return;
        }
        if (TextUtils.isEmpty(selectedTrack.videoId)) {
            return;
        }
        
        requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_PLAYLIST_ID)
                .apply();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        boolean radioMode = shouldUseRadioQueueForTrack(selectedTrack);
        ArrayList<YouTubeMusicService.TrackResult> queueCandidates = buildPlaybackQueueCandidates(selectedTrack, radioMode);
        int selectedIndex = -1;
        for (YouTubeMusicService.TrackResult item : queueCandidates) {
            ids.add(item.videoId);
            titles.add(TextUtils.isEmpty(item.title) ? "Tema" : item.title);
            artists.add(item.subtitle == null ? "" : item.subtitle);
            durations.add("--:--");
            images.add(item.thumbnailUrl == null ? "" : item.thumbnailUrl);
            if (selectedIndex < 0 && TextUtils.equals(item.videoId, selectedTrack.videoId)) {
                selectedIndex = ids.size() - 1;
            }
        }
        if (selectedIndex < 0) {
            // El "mejor resultado" no vive en `tracks` (la lista secundaria),
            // por eso lo inyectamos al inicio para reproducir exactamente ese.
            ids.add(0, selectedTrack.videoId);
            titles.add(0, TextUtils.isEmpty(selectedTrack.title) ? "Tema" : selectedTrack.title);
            artists.add(0, selectedTrack.subtitle == null ? "" : selectedTrack.subtitle);
            durations.add(0, "--:--");
            images.add(0, selectedTrack.thumbnailUrl == null ? "" : selectedTrack.thumbnailUrl);
            selectedIndex = 0;
        }
        // Hide global mini-player when opening full player
        if (!startInMiniMode && getActivity() instanceof MainActivity) {
            GlobalMiniPlayerController ctrl = ((MainActivity) getActivity()).getGlobalMiniPlayerController();
            if (ctrl != null) ctrl.hide();
        }
        SongPlayerFragment existingPlayer = findSongPlayerFragment();
        if (existingPlayer != null && existingPlayer.isAdded()) {
            // If the selected track is already playing, just open the player
            if (TextUtils.equals(selectedTrack.videoId, existingPlayer.getLoadedVideoId())) {
                existingPlayer.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
                getParentFragmentManager()
                        .beginTransaction()
                        .setReorderingAllowed(true)
                        .show(existingPlayer)
                        .setMaxLifecycle(existingPlayer, androidx.lifecycle.Lifecycle.State.RESUMED)
                        .runOnCommit(existingPlayer::externalAnimateEnterSlide)
                        .commit();
                return;
            }
            existingPlayer.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
            existingPlayer.externalReplaceQueueFromStart(ids, titles, artists, durations, images, selectedIndex, true);
            if (!startInMiniMode) {
                getParentFragmentManager()
                        .beginTransaction()
                        .setReorderingAllowed(true)
                        .show(existingPlayer)
                        .setMaxLifecycle(existingPlayer, androidx.lifecycle.Lifecycle.State.RESUMED)
                        .runOnCommit(existingPlayer::externalAnimateEnterSlide)
                        .commit();
            } else {
                existingPlayer.externalTryEnterMiniMode();
            }
        } else {
            if (selectedIndex >= 0 && selectedIndex < ids.size()) {
                // Position persistence removed
            }
            SongPlayerFragment playerFragment = SongPlayerFragment.newInstance(
                    ids,
                    titles,
                    artists,
                    durations,
                    images,
                    selectedIndex,
                    true
            );
            playerFragment.externalSetReturnTargetTag(TAG_MODULE_MUSIC);
            final SongPlayerFragment newPlayerForAnim = playerFragment;
            androidx.fragment.app.FragmentTransaction transaction = getParentFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.playerContainer, playerFragment, "song_player");
            if (startInMiniMode) {
                transaction.hide(playerFragment);
                transaction.commit();
            } else {
                transaction.runOnCommit(newPlayerForAnim::externalAnimateEnterSlide).commit();
            }
        }
    }
    private void openPlaylistDetail(@NonNull YouTubeMusicService.TrackResult track) {
        if (!isAdded()) {
            return;
        }
        if (getParentFragmentManager().isStateSaved()) {
            return;
        }
        String accessTokenForDetail = resolveAccessTokenForPlaylistDetail();
        PlaylistDetailFragment detailFragment = PlaylistDetailFragment.newInstance(
                track.contentId,
                track.title,
                track.subtitle,
                track.thumbnailUrl,
            accessTokenForDetail
        );
        androidx.fragment.app.Fragment existingDetail = getParentFragmentManager().findFragmentByTag("playlist_detail");
        androidx.fragment.app.FragmentTransaction transaction = getParentFragmentManager()
            .beginTransaction()
                .setReorderingAllowed(true)
            ;
        if (existingDetail != null && existingDetail.isAdded() && existingDetail != this) {
            transaction.remove(existingDetail);
        }
        transaction
            .add(R.id.fragmentContainer, detailFragment, "playlist_detail")
            .addToBackStack("playlist_detail")
            .commit();
    }
    @NonNull
    private String resolveAccessTokenForPlaylistDetail() {
        String inMemory = youtubeAccessToken == null ? "" : youtubeAccessToken.trim();
        if (!TextUtils.isEmpty(inMemory)) {
            return inMemory;
        }
        String accountKey = resolveAccountKey();
        if (hasValidCachedToken(accountKey)) {
            youtubeAccessToken = cachedYoutubeAccessToken;
            return cachedYoutubeAccessToken;
        }
        if (!isAdded()) {
            return "";
        }
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE);
        String persisted = prefs.getString(PREF_LAST_YOUTUBE_ACCESS_TOKEN, "");
        if (persisted == null) {
            return "";
        }
        String trimmed = persisted.trim();
        if (!trimmed.isEmpty()) {
            youtubeAccessToken = trimmed;
        }
        return trimmed;
    }
    private boolean isPlaylistDetailOnTop() {
        try {
            if (!isAdded()) return false;
            androidx.fragment.app.Fragment detail = getParentFragmentManager().findFragmentByTag("playlist_detail");
            return detail != null && detail.isAdded() && detail.isVisible();
        } catch (Exception e) {
            return false;
        }
    }
    private static final long NETWORK_CHECK_CACHE_MS = 2000L;
    private boolean isNetworkAvailable() {
        if (!isAdded()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if ((now - cachedNetworkAvailableAtMs) < NETWORK_CHECK_CACHE_MS) {
            return cachedNetworkAvailable;
        }
        ConnectivityManager cm = ContextCompat.getSystemService(requireContext(), ConnectivityManager.class);
        if (cm == null) {
            cachedNetworkAvailable = false;
            cachedNetworkAvailableAtMs = now;
            return false;
        }
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean available = capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        cachedNetworkAvailable = available;
        cachedNetworkAvailableAtMs = now;
        return available;
    }
    private boolean isLikelyNetworkError(@NonNull String error) {
        String normalized = normalizeForFilter(error);
        return containsAny(normalized,
                "network",
                "sin internet",
                "unable to resolve host",
                "failed to connect",
                "timeout",
                "timed out",
                "connection reset",
                "no address associated");
    }
    private void showCreatePlaylistDialog() {
        if (!isAdded()) return;
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("Nombre de la playlist");
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Crear nueva playlist")
                .setView(input)
                .setPositiveButton("Crear", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(name)) {
                        boolean success = CustomPlaylistsStore.INSTANCE.createPlaylist(requireContext(), name);
                        if (success) {
                            android.widget.Toast.makeText(requireContext(), "Playlist creada", android.widget.Toast.LENGTH_SHORT).show();
                            fetchLibraryPlaylists(true);
                        } else {
                            android.widget.Toast.makeText(requireContext(), "La playlist ya existe", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
    @Override
    public void onDestroyView() {
        cancelPendingLibraryInlineSearch();
        lastLibraryInlineDispatchedQuery = "";
        dismissPlaylistActionTooltip();
        stopObservingOfflineQueue();
        normalizedFilterCache.evictAll();
        // Cancel ALL pending mainHandler callbacks to prevent leaks and stale UI updates
        mainHandler.removeCallbacksAndMessages(null);
        PlaybackEventBus.removeListener(this);
        super.onDestroyView();
    }
    @Override
    public void onDestroy() {
        authExecutor.shutdownNow();
        offlineStateExecutor.shutdownNow();
        offlinePrefetchExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onPlaybackSnapshotUpdated() {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                refreshCurrentPlayingPlaylistState();
                syncPlaybackStateToAdapter();
            });
        }
    }
    private void syncPlaybackStateToAdapter() {
        if (!isAdded()) return;
        SongPlayerFragment songPlayer = findSongPlayerFragment();
        boolean playing = songPlayer != null && songPlayer.isAdded() && songPlayer.externalIsPlaying();
        if (lastMiniPlayerIsPlaying != playing) {
            lastMiniPlayerIsPlaying = playing;
            if (adapter != null) {
                adapter.notifyPlaybackStateChanged();
            }
        }
    }
    private static final class PendingOfflinePlaylistCandidate {
        @NonNull
        final String playlistId;
        @NonNull
        final String playlistTitle;
        final int pendingCount;
        @NonNull
        final List<CachedPlaylistTrack> cachedTracks;
        private PendingOfflinePlaylistCandidate(
                @NonNull String playlistId,
                @NonNull String playlistTitle,
                int pendingCount,
                @NonNull List<CachedPlaylistTrack> cachedTracks
        ) {
            this.playlistId = playlistId;
            this.playlistTitle = playlistTitle;
            this.pendingCount = Math.max(0, pendingCount);
            this.cachedTracks = cachedTracks;
        }
    }
    private static final class PendingOfflineTrackCandidate {
        @NonNull
        final String videoId;
        @NonNull
        final String title;
        @NonNull
        final String artist;
        @NonNull
        final String duration;
        private PendingOfflineTrackCandidate(
                @NonNull String videoId,
                @Nullable String title,
                @Nullable String artist,
                @Nullable String duration
        ) {
            this.videoId = videoId;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.duration = duration == null ? "" : duration;
        }
    }
    private static final class PendingOfflineBatchCandidate {
        @NonNull
        final String primaryPlaylistId;
        @NonNull
        final String primaryPlaylistTitle;
        @NonNull
        final List<String> playlistIds;
        @NonNull
        final List<PendingOfflineTrackCandidate> tracks;
        private PendingOfflineBatchCandidate(
                @NonNull String primaryPlaylistId,
                @NonNull String primaryPlaylistTitle,
                @NonNull List<String> playlistIds,
                @NonNull List<PendingOfflineTrackCandidate> tracks
        ) {
            this.primaryPlaylistId = primaryPlaylistId;
            this.primaryPlaylistTitle = primaryPlaylistTitle;
            this.playlistIds = playlistIds;
            this.tracks = tracks;
        }
    }
    private final class MusicResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_LIST = 0;
        private static final int VIEW_TYPE_SEARCH = 2;
        private static final int VIEW_TYPE_CREATE_PLAYLIST = 3;
        private static final String PAYLOAD_PLAYBACK_STATE = "playback_state";
        private static final String PAYLOAD_OFFLINE_STATE = "offline_state";
        private static final String PAYLOAD_DOWNLOAD_PROGRESS = "download_progress";
        private final List<YouTubeMusicService.TrackResult> data = new ArrayList<>();
        private final OnLibraryTrackClick onTrackClick;
        private final OnLibraryTrackMoreClick onTrackMoreClick;
        private final Map<String, Boolean> playlistOfflineCompleteCache = new HashMap<>();
        private final Map<String, Integer> playlistPositionCache = new HashMap<>();  // Cache for O(1) lookups
        private final Set<String> playlistOfflineRefreshInFlight = new HashSet<>();
        private final Map<String, Float> playlistDownloadProgressCache = new HashMap<>();
        private final Set<String> playlistsCurrentlyDownloading = new HashSet<>();
        private long playlistOfflineStateGeneration;
        private boolean forceNextOfflineRecalculation = false;
        private long lastFullOfflineInvalidateAtMs = 0L;
        private static final long FULL_OFFLINE_INVALIDATE_THROTTLE_MS = 5000L;
        private boolean searchMode;
        MusicResultsAdapter(
                @NonNull OnLibraryTrackClick onTrackClick,
                @NonNull OnLibraryTrackMoreClick onTrackMoreClick
        ) {
            this.onTrackClick = onTrackClick;
            this.onTrackMoreClick = onTrackMoreClick;
            setHasStableIds(false);
        }
        void invalidatePlaylistOfflineState(@Nullable String playlistId) {
            invalidatePlaylistOfflineState(playlistId, false);
        }
        void invalidatePlaylistOfflineState(@Nullable String playlistId, boolean force) {
            if (TextUtils.isEmpty(playlistId)) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (!force && lastFullOfflineInvalidateAtMs > 0
                        && (now - lastFullOfflineInvalidateAtMs) < FULL_OFFLINE_INVALIDATE_THROTTLE_MS) {
                    // Throttled: just rebind existing cached states without heavy disk I/O
                    if (!data.isEmpty()) {
                        safeNotify(() -> notifyItemRangeChanged(0, data.size(), PAYLOAD_OFFLINE_STATE));
                    }
                    return;
                }
                lastFullOfflineInvalidateAtMs = now;
                playlistOfflineStateGeneration++;
                // Clear cache so async results always trigger UI notification
                playlistOfflineCompleteCache.clear();
                // Set flag to force recalculation from actual files during next submitResults
                forceNextOfflineRecalculation = true;
                long generation = playlistOfflineStateGeneration;
                // Recalculate from actual offline files and update persistence
                for (YouTubeMusicService.TrackResult item : data) {
                    if (!"playlist".equals(item.resultType)) {
                        continue;
                    }
                    String candidateId = item.contentId == null ? "" : item.contentId.trim();
                    if (candidateId.isEmpty()) {
                        continue;
                    }
                    requestPlaylistOfflineStateRefreshAsync(candidateId, generation, true);
                }
                if (!data.isEmpty()) {
                    safeNotify(() -> notifyItemRangeChanged(0, data.size(), PAYLOAD_OFFLINE_STATE));
                }
            } else {
                // For single playlist, remove from cache and recalculate from files
                playlistOfflineCompleteCache.remove(playlistId);
                requestPlaylistOfflineStateRefreshAsync(playlistId, playlistOfflineStateGeneration, true);
                int updatedPosition = findPlaylistPositionById(playlistId);
                if (updatedPosition >= 0) {
                    safeNotify(() -> notifyItemChanged(updatedPosition, PAYLOAD_OFFLINE_STATE));
                }
            }
        }

        /**
         * Force immediate recalculation of a playlist's offline state from actual files.
         * Used when notified from PlaylistDetailFragment that tracks were downloaded/deleted.
         */
        void forceRecalculatePlaylistState(@NonNull String playlistId) {
            if (playlistId.isEmpty()) {
                return;
            }
            // Remove from cache to force recalculation
            playlistOfflineCompleteCache.remove(playlistId);
            // Also invalidate the cached playlist tracks so we fetch fresh data
            synchronized (cachedPlaylistTracksLock) {
                cachedPlaylistTracksById.remove(playlistId);
                cachedPlaylistTracksUpdatedAtById.remove(playlistId);
            }
            // Trigger async recalculation with force flag
            requestPlaylistOfflineStateRefreshAsync(playlistId, playlistOfflineStateGeneration, true);
            // Also update UI immediately if position found
            int position = findPlaylistPositionById(playlistId);
            if (position >= 0) {
                safeNotify(() -> notifyItemChanged(position, PAYLOAD_OFFLINE_STATE));
            }
        }

        void notifyPlayingPlaylistChanged(@Nullable String previousPlaylistId, @Nullable String currentPlaylistId) {
            String previous = previousPlaylistId == null ? "" : previousPlaylistId.trim();
            String current = currentPlaylistId == null ? "" : currentPlaylistId.trim();
            int previousPosition = findPlaylistPositionById(previous);
            int currentPosition = findPlaylistPositionById(current);
            if (previousPosition >= 0) {
                safeNotify(() -> notifyItemChanged(previousPosition, PAYLOAD_PLAYBACK_STATE));
            }
            if (currentPosition >= 0) {
                safeNotify(() -> notifyItemChanged(currentPosition, PAYLOAD_PLAYBACK_STATE));
            }
        }
        @Override
        public long getItemId(int position) {
            if (searchMode) {
                if (position < 0 || position >= data.size()) return RecyclerView.NO_ID;
                YouTubeMusicService.TrackResult item = data.get(position);
                return (item.resultType + "|" + item.contentId + "|" + item.title).hashCode();
            } else {
                if (position == data.size()) return -1;
                if (position < 0 || position >= data.size()) return RecyclerView.NO_ID;
                YouTubeMusicService.TrackResult item = data.get(position);
                return (item.resultType + "|" + item.contentId + "|" + item.title).hashCode();
            }
        }
        void setSearchMode(boolean searchMode) {
            if (this.searchMode == searchMode) {
                return;
            }
            this.searchMode = searchMode;
            safeNotify(this::notifyDataSetChanged);
        }

        /**
         * Notify all visible items that playback state changed.
         * Uses payload for efficient partial bind instead of full dataset refresh.
         */
        void notifyPlaybackStateChanged() {
            if (data.isEmpty()) return;
            safeNotify(() -> notifyItemRangeChanged(0, data.size(), PAYLOAD_PLAYBACK_STATE));
        }

        void updateDownloadProgress(@NonNull Map<String, Float> progressByPlaylistId,
                                    @NonNull Set<String> downloading) {
            Set<String> changed = new HashSet<>(playlistsCurrentlyDownloading);
            changed.addAll(downloading);

            boolean anyChanged = false;
            for (String pid : changed) {
                float oldVal = playlistDownloadProgressCache.containsKey(pid)
                        ? playlistDownloadProgressCache.get(pid) : -1f;
                float newVal = progressByPlaylistId.containsKey(pid)
                        ? progressByPlaylistId.get(pid) : -1f;
                boolean wasDownloading = playlistsCurrentlyDownloading.contains(pid);
                boolean nowDownloading = downloading.contains(pid);
                if (wasDownloading != nowDownloading || Math.abs(oldVal - newVal) > 0.005f) {
                    anyChanged = true;
                    break;
                }
            }
            if (!anyChanged) return;

            playlistDownloadProgressCache.clear();
            playlistDownloadProgressCache.putAll(progressByPlaylistId);
            playlistsCurrentlyDownloading.clear();
            playlistsCurrentlyDownloading.addAll(downloading);

            for (String pid : changed) {
                int pos = findPlaylistPositionById(pid);
                if (pos >= 0) {
                    safeNotify(() -> notifyItemChanged(pos, PAYLOAD_DOWNLOAD_PROGRESS));
                }
            }
        }

        void safeNotifyItemInserted(int position) {
            safeNotify(() -> notifyItemInserted(position));
        }

        /**
         * Dispatches a notify* call only when the RecyclerView is NOT computing a layout
         * or scrolling. If it is busy, re-queues with a short postDelayed until safe.
         * This prevents both IndexOutOfBoundsException (inconsistency) and
         * IllegalStateException (cannot call while computing layout).
         */
        private void safeNotify(@NonNull Runnable notifyAction) {
            dispatchWhenIdle(notifyAction, 0);
        }

        private void dispatchWhenIdle(@NonNull Runnable action, int attempt) {
            RecyclerView rv = rvMusicResults;
            if (rv == null) {
                action.run();
                return;
            }
            if (!rv.isComputingLayout() && !rv.isLayoutRequested()) {
                // Run directly on the main thread â€” do NOT use rv.post() here.
                // rv.post() defers by one full frame, during which a pending touch/scroll
                // event can call consumePendingUpdateOperations with stale item counts,
                // causing the "Inconsistency detected" IndexOutOfBoundsException crash.
                action.run();
            } else {
                int delay = attempt < 3 ? 16 : 32;
                mainHandler.postDelayed(() -> dispatchWhenIdle(action, attempt + 1), delay);
            }
        }

        // pendingIncoming: the latest list waiting to be diffed. null = no work in flight.
        // Set to non-null on UI thread; cleared to null on background thread before computing diff.
        private final java.util.concurrent.atomic.AtomicReference<List<YouTubeMusicService.TrackResult>>
                pendingIncoming = new java.util.concurrent.atomic.AtomicReference<>(null);
        private boolean diffInFlight = false;

        void submitResults(@NonNull List<YouTubeMusicService.TrackResult> newData) {
            // Always runs on UI thread. Store the incoming list as the latest pending.
            // Callers already provide a defensive copy, so no need to copy again.
            pendingIncoming.set(newData);
            if (diffInFlight) {
                // A diff is already running; it will pick up pendingIncoming when it finishes.
                return;
            }
            diffInFlight = true;
            scheduleDiffWork();
        }

        private void scheduleDiffWork() {
            // Called on UI thread. Snapshot data and the latest pending list.
            final List<YouTubeMusicService.TrackResult> previous = new ArrayList<>(data);
            final List<YouTubeMusicService.TrackResult> incoming = pendingIncoming.getAndSet(null);
            if (incoming == null) {
                diffInFlight = false;
                return;
            }
            STREAMING_WARMUP_EXECUTOR.execute(() -> {
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
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
                    YouTubeMusicService.TrackResult oldItem = previous.get(oldItemPosition);
                    YouTubeMusicService.TrackResult newItem = incoming.get(newItemPosition);
                    if (!TextUtils.isEmpty(oldItem.contentId) && !TextUtils.isEmpty(newItem.contentId)) {
                        return TextUtils.equals(oldItem.contentId, newItem.contentId)
                                && TextUtils.equals(oldItem.resultType, newItem.resultType);
                    }
                    return TextUtils.equals(oldItem.title, newItem.title)
                            && TextUtils.equals(oldItem.subtitle, newItem.subtitle);
                }
                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    YouTubeMusicService.TrackResult oldItem = previous.get(oldItemPosition);
                    YouTubeMusicService.TrackResult newItem = incoming.get(newItemPosition);
                    boolean baseEqual = TextUtils.equals(oldItem.resultType, newItem.resultType)
                            && TextUtils.equals(oldItem.contentId, newItem.contentId)
                            && TextUtils.equals(oldItem.title, newItem.title)
                            && TextUtils.equals(oldItem.subtitle, newItem.subtitle);
                    if (!baseEqual) return false;
                    // For YouTube playlists with a cached grid, thumbnailUrl is irrelevant
                    // (the 2x2 grid comes from track thumbnails, not item.thumbnailUrl).
                    // Return false if the grid became available since the last bind so the
                    // ViewHolder is rebound and PlaylistGridArtLoader can draw the 2x2.
                    if ("playlist".equals(newItem.resultType) && !TextUtils.isEmpty(newItem.contentId)) {
                        String pid = newItem.contentId.trim();
                        boolean isYtPlaylist = !FavoritesPlaylistStore.PLAYLIST_ID.equals(pid)
                                && !pid.startsWith("custom_");
                        if (isYtPlaylist && playlistGridUrlsCache.containsKey(pid)) {
                            return true;
                        }
                    }
                    return TextUtils.equals(oldItem.thumbnailUrl, newItem.thumbnailUrl);
                }
            });
            final DiffUtil.DiffResult finalDiffResult = diffResult;
            mainHandler.post(() -> {
                boolean forceRecalc = forceNextOfflineRecalculation;
                forceNextOfflineRecalculation = false;
                applyDataAndNotify(incoming, forceRecalc, finalDiffResult);
            }); // end mainHandler.post
            }); // end STREAMING_WARMUP_EXECUTOR.execute
        }

        /**
         * Atomically swaps 'data', rebuilds caches, and dispatches DiffUtil updates.
         * The key insight: data swap and notify must happen in the SAME rv.post() callback so
         * the RecyclerView never sees a stale item count during onMeasure/dispatchLayoutStep1.
         */
        private void applyDataAndNotify(
                @NonNull List<YouTubeMusicService.TrackResult> incoming,
                boolean forceRecalc,
                @NonNull DiffUtil.DiffResult diffResult) {
            RecyclerView rv = rvMusicResults;
            Runnable applyAndNotify = () -> {
                // Swap data
                data.clear();
                data.addAll(incoming);

                // Rebuild position cache atomically
                Map<String, Integer> newPositionCache = new HashMap<>();
                for (int i = 0; i < incoming.size(); i++) {
                    YouTubeMusicService.TrackResult item = incoming.get(i);
                    if ("playlist".equals(item.resultType) && !TextUtils.isEmpty(item.contentId)) {
                        newPositionCache.put(item.contentId, i);
                    }
                }
                synchronized (playlistPositionCache) {
                    playlistPositionCache.clear();
                    playlistPositionCache.putAll(newPositionCache);
                }

                // Trim offline cache
                Set<String> incomingPlaylistIds = new HashSet<>();
                for (YouTubeMusicService.TrackResult item : incoming) {
                    if (!"playlist".equals(item.resultType)) continue;
                    String pid = item.contentId == null ? "" : item.contentId.trim();
                    if (!pid.isEmpty()) incomingPlaylistIds.add(pid);
                }
                playlistOfflineCompleteCache.keySet().removeIf(id -> !incomingPlaylistIds.contains(id));
                playlistOfflineRefreshInFlight.clear();

                // Dispatch granular updates instead of notifyDataSetChanged
                diffResult.dispatchUpdatesTo(MusicResultsAdapter.this);

                if (pendingScrollToTop) {
                    pendingScrollToTop = false;
                    if (rvMusicResults != null && musicResultsLayoutManager != null) {
                        musicResultsLayoutManager.scrollToPositionWithOffset(0, 0);
                    }
                }

                // Schedule offline refresh AFTER notify
                for (YouTubeMusicService.TrackResult item : incoming) {
                    if (!"playlist".equals(item.resultType)) continue;
                    String pid = item.contentId == null ? "" : item.contentId.trim();
                    if (pid.isEmpty()) continue;
                    if (forceRecalc) {
                        requestPlaylistOfflineStateRefreshAsync(pid, playlistOfflineStateGeneration, true);
                    } else if (!playlistOfflineCompleteCache.containsKey(pid)) {
                        requestPlaylistOfflineStateRefreshAsync(pid, playlistOfflineStateGeneration, false);
                    }
                }

                // Chain next diff if pending
                if (pendingIncoming.get() != null) {
                    scheduleDiffWork();
                } else {
                    diffInFlight = false;
                }
            };
            dispatchWhenIdle(applyAndNotify, 0);
        }
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_CREATE_PLAYLIST) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_create_playlist_row, parent, false);
                return new CreatePlaylistViewHolder(view);
            }
            int layoutRes;
            if (viewType == VIEW_TYPE_SEARCH) {
                layoutRes = R.layout.item_music_search_result;
            } else {
                layoutRes = R.layout.item_music_result;
            }
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
            return new TrackViewHolder(view);
        }
        @Override
        public int getItemViewType(int position) {
            if (searchMode) {
                return VIEW_TYPE_SEARCH;
            }
            if (position == data.size()) {
                return VIEW_TYPE_CREATE_PLAYLIST;
            }
            return VIEW_TYPE_LIST;
        }
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder genericHolder, int position, @NonNull List<Object> payloads) {
            if (genericHolder instanceof TrackViewHolder && !payloads.isEmpty()) {
                TrackViewHolder holder = (TrackViewHolder) genericHolder;
                YouTubeMusicService.TrackResult item = data.get(position);
                boolean isPlaylistItem = "playlist".equals(item.resultType);
                if (!isPlaylistItem) return;

                for (Object payload : payloads) {
                    if (PAYLOAD_PLAYBACK_STATE.equals(payload)) {
                        bindPlaybackState(holder, item);
                    } else if (PAYLOAD_OFFLINE_STATE.equals(payload)
                            || PAYLOAD_DOWNLOAD_PROGRESS.equals(payload)) {
                        bindOfflineState(holder, item);
                    }
                }
                return;
            }
            onBindViewHolder(genericHolder, position);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder genericHolder, int position) {
            int viewType = getItemViewType(position);
            if (viewType == VIEW_TYPE_CREATE_PLAYLIST) {
                genericHolder.itemView.setOnClickListener(v -> showCreatePlaylistDialog());
                return;
            }
            TrackViewHolder holder = (TrackViewHolder) genericHolder;
            YouTubeMusicService.TrackResult item = data.get(position);
                if (searchMode) {
                bindSearchRow(holder, item, position);
                return;
            }
            boolean isFavoritesPlaylistStyle = isFavoritesPlaylistStyle(item);
            boolean isLikedPlaylistStyle = !isFavoritesPlaylistStyle && isLikedPlaylistStyle(item);
            int favCount = isFavoritesPlaylistStyle && isAdded()
                    ? FavoritesPlaylistStore.getFavoritesCount(requireContext()) : 0;
            if (isFavoritesPlaylistStyle) {
                holder.tvTrackTitle.setText(FavoritesPlaylistStore.PLAYLIST_TITLE);
                holder.tvTrackSubtitle.setText(FavoritesPlaylistStore.buildSubtitle(favCount));
            } else if (isLikedPlaylistStyle) {
                holder.tvTrackTitle.setText("M\u00fasica que te gust\u00f3");
                if (TextUtils.isEmpty(item.subtitle)) {
                    holder.tvTrackSubtitle.setText("Playlist autogenerada \u2022 0 canciones");
                } else {
                    holder.tvTrackSubtitle.setText(item.subtitle);
                }
            } else {
                holder.tvTrackTitle.setText(item.title);
                if (TextUtils.isEmpty(item.subtitle)) {
                    holder.tvTrackSubtitle.setText("Playlist");
                } else {
                    holder.tvTrackSubtitle.setText(item.subtitle);
                }
            }
            if (TextUtils.isEmpty(item.subtitle) && !isLikedPlaylistStyle) {
                holder.tvTrackSubtitle.setText("Playlist");
            }
            // Only "Música que te gustó" uses the special icon style
            holder.vLikedBackground.setVisibility(isLikedPlaylistStyle ? View.VISIBLE : View.GONE);
            holder.ivLikedIcon.setVisibility(isLikedPlaylistStyle ? View.VISIBLE : View.GONE);
            holder.ivTrackThumb.setVisibility(isLikedPlaylistStyle ? View.GONE : View.VISIBLE);
            if (isLikedPlaylistStyle) {
                holder.ivLikedIcon.setImageResource(R.drawable.ic_thumb_up_liked);
                holder.ivLikedIcon.setColorFilter(Color.WHITE);
            }
            if (!isLikedPlaylistStyle) {
                String pid = item.contentId == null ? "" : item.contentId.trim();
                boolean isLocalFilesItem = LocalFilesStore.PLAYLIST_ID.equals(pid);
                boolean isRadioItem = pid.startsWith("RDAMVM") || pid.startsWith("RDEM") || pid.startsWith("RDTMAK");
                if (isLocalFilesItem) {
                    // Local files: white folder icon on black background
                    holder.ivTrackThumb.setTag(R.id.tag_artwork_signature, null);
                    holder.ivTrackThumb.setScaleType(ImageView.ScaleType.CENTER);
                    holder.ivTrackThumb.setBackgroundColor(android.graphics.Color.BLACK);
                    holder.ivTrackThumb.setImageResource(R.drawable.ic_folder_white);
                } else if (isRadioItem && !TextUtils.isEmpty(item.thumbnailUrl)) {
                    // Radio items: single image, no grid
                    holder.ivTrackThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    holder.ivTrackThumb.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    loadArtworkInto(holder.ivTrackThumb, item.thumbnailUrl);
                } else {
                    holder.ivTrackThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    holder.ivTrackThumb.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    List<String> gridUrls = pid.isEmpty() ? java.util.Collections.emptyList() : resolvePlaylistGridUrls(pid);
                    if (gridUrls.size() >= 4) {
                        float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
                        int sizePx = Math.round(60 * density);
                        PlaylistGridArtLoader.load(holder.ivTrackThumb, gridUrls, sizePx);
                    } else if (!TextUtils.isEmpty(item.thumbnailUrl)) {
                        loadArtworkInto(holder.ivTrackThumb, item.thumbnailUrl);
                    } else {
                        // Grey placeholder only if no previous grid image is loaded
                        Object prevSignature = holder.ivTrackThumb.getTag(R.id.tag_artwork_signature);
                        if (prevSignature == null) {
                            holder.ivTrackThumb.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                                    ContextCompat.getColor(holder.itemView.getContext(), R.color.surface_high)));
                        }
                    }
                }
            }
            boolean isPlaylistItem = "playlist".equals(item.resultType);
            String itemContentId = item.contentId == null ? "" : item.contentId;
            boolean isPinned = isFavoritesPlaylistStyle || isLikedPlaylistStyle
                    || RadioHistoryStore.INSTANCE.isPlaylistPinned(holder.itemView.getContext(), itemContentId);
            holder.ivTrackMore.setVisibility(isPlaylistItem ? View.VISIBLE : View.GONE);
            if (!isPlaylistItem) holder.flPlaylistOfflineContainer.setVisibility(View.GONE);
            if (holder.ivPlaylistPin != null) {
                holder.ivPlaylistPin.setVisibility(isPinned ? View.VISIBLE : View.GONE);
            }
            if (holder.llNowPlayingOverlay != null) {
                holder.llNowPlayingOverlay.setVisibility(View.GONE);
            }
            int defaultTitleColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary);
            int defaultSubtitleColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary);
            holder.tvTrackTitle.setTextColor(defaultTitleColor);
            holder.tvTrackSubtitle.setTextColor(defaultSubtitleColor);
            if (isPlaylistItem) {
                bindOfflineState(holder, item);
                bindPlaybackState(holder, item);
                holder.ivTrackMore.setOnClickListener(v -> onTrackMoreClick.onTrackMoreClick(item, holder.ivTrackMore));
                holder.itemView.setOnLongClickListener(v -> {
                    onTrackMoreClick.onTrackMoreClick(item, holder.itemView);
                    return true;
                });
            } else {
                holder.ivTrackMore.setOnClickListener(null);
                holder.itemView.setOnLongClickListener(null);
            }
            holder.itemView.setOnClickListener(v -> onTrackClick.onTrackClick(item));
        }

        private void bindOfflineState(@NonNull TrackViewHolder holder, @NonNull YouTubeMusicService.TrackResult item) {
            String playlistId = item.contentId == null ? "" : item.contentId.trim();
            if (playlistId.isEmpty()) return;

            boolean offlineAutoEnabled = isPersistedPlaylistOfflineAutoEnabled(playlistId);
            Boolean cachedState = playlistOfflineCompleteCache.get(playlistId);
            if (cachedState == null) {
                // Force recalculation from actual files — SharedPreferences may be stale after app restart
                requestPlaylistOfflineStateRefreshAsync(playlistId, playlistOfflineStateGeneration, true);
            }
            boolean completeOffline;
            if (cachedState != null) {
                completeOffline = cachedState;
            } else {
                // While async calculation is in flight, use persisted state as best guess.
                // The async callback will correct it and notify the item if different.
                completeOffline = isPersistedPlaylistOfflineComplete(playlistId);
            }

            boolean isDownloading = playlistsCurrentlyDownloading.contains(playlistId);
            float downloadFraction = 0f;
            if (isDownloading && playlistDownloadProgressCache.containsKey(playlistId)) {
                downloadFraction = playlistDownloadProgressCache.get(playlistId);
            }
            Context ctx = holder.itemView.getContext();

            if (isDownloading) {
                // State: downloading — circular arc + download icon (no background circle)
                holder.flPlaylistOfflineContainer.setVisibility(View.VISIBLE);
                holder.circularProgress.setVisibility(View.VISIBLE);
                holder.circularProgress.setProgress(downloadFraction);
                holder.ivPlaylistOfflineAll.setImageResource(R.drawable.ic_download_bold);
                holder.ivPlaylistOfflineAll.setBackground(null);
                holder.ivPlaylistOfflineAll.setColorFilter(ContextCompat.getColor(ctx, R.color.stitch_blue));
                holder.ivPlaylistOfflineAll.setAlpha(1f);
            } else if (completeOffline && offlineAutoEnabled) {
                // State: fully downloaded — filled circle + check
                holder.flPlaylistOfflineContainer.setVisibility(View.VISIBLE);
                holder.circularProgress.setVisibility(View.GONE);
                holder.circularProgress.setProgressImmediate(0f);
                holder.ivPlaylistOfflineAll.setImageResource(R.drawable.ic_check_small);
                holder.ivPlaylistOfflineAll.setBackgroundResource(R.drawable.bg_offline_state_filled_primary);
                holder.ivPlaylistOfflineAll.setColorFilter(ContextCompat.getColor(ctx, R.color.surface_dark));
                holder.ivPlaylistOfflineAll.setAlpha(1f);
            } else if (offlineAutoEnabled) {
                // State: queued (auto-offline enabled, waiting for worker)
                // Show empty circular track + download icon (like downloading at 0%)
                holder.flPlaylistOfflineContainer.setVisibility(View.VISIBLE);
                holder.circularProgress.setVisibility(View.VISIBLE);
                holder.circularProgress.setProgressImmediate(0f);
                holder.ivPlaylistOfflineAll.setImageResource(R.drawable.ic_download_bold);
                holder.ivPlaylistOfflineAll.setBackground(null);
                holder.ivPlaylistOfflineAll.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary));
                holder.ivPlaylistOfflineAll.setAlpha(1f);
            } else {
                // State: not downloaded — hide entirely
                holder.flPlaylistOfflineContainer.setVisibility(View.GONE);
            }
        }

        private void bindPlaybackState(@NonNull TrackViewHolder holder, @NonNull YouTubeMusicService.TrackResult item) {
            String playlistId = item.contentId == null ? "" : item.contentId.trim();
            boolean isPlayingThis = isPlaylistCurrentlyPlaying(playlistId);

            // Use tag to track current state and avoid unnecessary updates
            Object currentStateTag = holder.itemView.getTag(R.id.tag_playback_state);
            boolean wasPlaying = Boolean.TRUE.equals(currentStateTag);

            if (isPlayingThis) {
                // Only update if state changed or first bind
                if (!wasPlaying) {
                    holder.itemView.setBackgroundResource(R.drawable.bg_playlist_track_active);
                    holder.itemView.setTag(R.id.tag_playback_state, Boolean.TRUE);
                }
                if (holder.llNowPlayingOverlay != null) {
                    // Only change visibility if needed
                    if (holder.llNowPlayingOverlay.getVisibility() != View.VISIBLE) {
                        holder.llNowPlayingOverlay.setVisibility(View.VISIBLE);
                    }
                    if (holder.animatedEq != null) {
                        SongPlayerFragment songPlayer = findSongPlayerFragment();
                        boolean isActuallyPlaying = songPlayer != null && songPlayer.isPlaying();
                        holder.animatedEq.setAnimating(isActuallyPlaying);
                    }
                }
            } else {
                // Only update if state changed or first bind
                if (wasPlaying) {
                    holder.itemView.setBackgroundResource(R.drawable.bg_playlist_track_default);
                    holder.itemView.setTag(R.id.tag_playback_state, Boolean.FALSE);
                }
                if (holder.llNowPlayingOverlay != null) {
                    // Only change visibility if needed
                    if (holder.llNowPlayingOverlay.getVisibility() != View.GONE) {
                        holder.llNowPlayingOverlay.setVisibility(View.GONE);
                    }
                    if (holder.animatedEq != null) {
                        holder.animatedEq.setAnimating(false);
                    }
                }
            }
        }

        private void bindSearchRow(
                @NonNull TrackViewHolder holder,
                @NonNull YouTubeMusicService.TrackResult item,
                int position
        ) {
            holder.vLikedBackground.setVisibility(View.GONE);
            holder.ivLikedIcon.setVisibility(View.GONE);
            holder.flPlaylistOfflineContainer.setVisibility(View.GONE);
            holder.ivTrackThumb.setVisibility(View.VISIBLE);
            SongPlayerFragment songPlayer = findSongPlayerFragment();
            String currentVideoId = songPlayer != null ? songPlayer.getLoadedVideoId() : "";
            boolean isNowPlaying = !currentVideoId.isEmpty() && TextUtils.equals(currentVideoId, item.contentId);
            boolean isActuallyPlaying = isNowPlaying && songPlayer != null && songPlayer.isPlaying();
            if (holder.llNowPlayingOverlay != null) {
                holder.llNowPlayingOverlay.setVisibility(isNowPlaying ? View.VISIBLE : View.GONE);
                if (holder.animatedEq != null) {
                    holder.animatedEq.setAnimating(isActuallyPlaying);
                }
            }
            int activeTitleColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.stitch_blue_light);
            int defaultTitleColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary);
            holder.tvTrackTitle.setTextColor(defaultTitleColor);
            holder.itemView.setBackgroundResource(isNowPlaying ? R.drawable.bg_playlist_track_active : R.drawable.bg_playlist_track_default);
            String title = TextUtils.isEmpty(item.title) ? "Resultado" : item.title;
            holder.tvTrackTitle.setText(title);
            String typeLabel = searchTypeLabel(item);
            String subtitle = TextUtils.isEmpty(item.subtitle) ? typeLabel : item.subtitle;
            holder.tvTrackSubtitle.setText(subtitle);
            loadArtworkInto(holder.ivTrackThumb, item.thumbnailUrl);
            holder.ivTrackMore.setVisibility(View.VISIBLE);
            holder.ivTrackMore.setOnClickListener(v -> showLibraryTrackOptions(item, holder.ivTrackMore));
            holder.itemView.setOnLongClickListener(v -> {
                showLibraryTrackOptions(item, holder.itemView);
                return true;
            });
            holder.itemView.setOnClickListener(v -> onTrackClick.onTrackClick(item));
        }
        private boolean isFavoritesPlaylistStyle(@NonNull YouTubeMusicService.TrackResult item) {
            return TextUtils.equals(FavoritesPlaylistStore.PLAYLIST_ID, item.contentId);
        }
        @Override
        public int getItemCount() {
            if (searchMode) {
                return data.size();
            }
            return data.size() + 1;
        }
        private void requestPlaylistOfflineStateRefreshAsync(
                @NonNull String playlistId,
                long generation
        ) {
            requestPlaylistOfflineStateRefreshAsync(playlistId, generation, false);
        }
        
        private void requestPlaylistOfflineStateRefreshAsync(
                @NonNull String playlistId,
                long generation,
                boolean forceRecalculate
        ) {
            if (playlistId.isEmpty()) {
                return;
            }
            // If not forcing recalculation and we have cached state, skip
            if (!forceRecalculate && playlistOfflineCompleteCache.containsKey(playlistId)) {
                return;
            }
            if (playlistOfflineRefreshInFlight.contains(playlistId)) {
                return;
            }
            playlistOfflineRefreshInFlight.add(playlistId);
            if (offlineStateExecutor.isShutdown()) {
                playlistOfflineRefreshInFlight.remove(playlistId);
                return;
            }
            offlineStateExecutor.execute(() -> {
                // If forcing recalculation, bypass cache and persisted state
                // and check actual offline files
                boolean computed;
                if (forceRecalculate && isAdded()) {
                    computed = computePlaylistOfflineStateFromFiles(requireContext().getApplicationContext(), playlistId);
                } else {
                    computed = isPlaylistFullyOffline(playlistId);
                }
                mainHandler.post(() -> {
                    playlistOfflineRefreshInFlight.remove(playlistId);
                    if (!isAdded() || generation != playlistOfflineStateGeneration) {
                        return;
                    }
                    Boolean previous = playlistOfflineCompleteCache.put(playlistId, computed);
                    if (previous != null && previous == computed) {
                        return;
                    }
                    int updatedPosition = findPlaylistPositionById(playlistId);
                    if (updatedPosition >= 0) {
                        safeNotify(() -> notifyItemChanged(updatedPosition, PAYLOAD_OFFLINE_STATE));
                    } else {
                        safeNotify(() -> notifyItemRangeChanged(0, data.size(), PAYLOAD_OFFLINE_STATE));
                    }
                });
            });
        }
        
        /**
         * Compute playlist offline state by checking actual offline files.
         * This bypasses cache and persisted state to give the ground truth.
         */
        private boolean computePlaylistOfflineStateFromFiles(@NonNull Context appContext, @NonNull String playlistId) {
            if (TextUtils.isEmpty(playlistId)) {
                return false;
            }
            ArrayList<CachedPlaylistTrack> cachedTracks = loadCachedPlaylistTracksForOffline(appContext, playlistId);
            if (cachedTracks.isEmpty()) {
                // Track list was wiped (e.g. by pull-to-refresh) and prefetch hasn't finished yet.
                // Do NOT write false to SharedPreferences â€” that would corrupt a correct persisted
                // true value. Return the persisted state as-is and wait for the prefetch to repopulate.
                return isPersistedPlaylistOfflineComplete(appContext, playlistId);
            }
            HashSet<String> seen = new HashSet<>();
            int eligibleCount = 0;
            int offlineCount = 0;
            for (CachedPlaylistTrack track : cachedTracks) {
                if (track == null || TextUtils.isEmpty(track.videoId) || seen.contains(track.videoId)) {
                    continue;
                }
                seen.add(track.videoId);
                // Local files are always on device — skip from offline calculations
                if (LocalFilesStore.isLocalVideoId(track.videoId)) {
                    continue;
                }
                eligibleCount++;
                // Check actual offline files - this is the ground truth
                if (OfflineAudioStore.hasValidatedOfflineAudio(appContext, track.videoId, track.duration)) {
                    offlineCount++;
                }
            }
            boolean complete = eligibleCount > 0 && offlineCount >= eligibleCount;
            // Update persisted state with the computed truth
            persistPlaylistOfflineComplete(appContext, playlistId, complete);
            return complete;
        }
        private int findPlaylistPositionById(@NonNull String playlistId) {
            // âœ… O(1) HashMap lookup instead of O(n) linear search
            // Synchronize to prevent race conditions since this is called from async callbacks
            synchronized (playlistPositionCache) {
                Integer cachedPosition = playlistPositionCache.get(playlistId);
                return cachedPosition != null ? cachedPosition : -1;
            }
        }
        private final class CreatePlaylistViewHolder extends RecyclerView.ViewHolder {
            CreatePlaylistViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
        final class TrackViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivTrackThumb;
            final View vLikedBackground;
            final ImageView ivLikedIcon;
            final ImageView ivPlaylistPin;
            final View flPlaylistOfflineContainer;
            final ImageView ivPlaylistOfflineAll;
            final CircularProgressView circularProgress;
            final TextView tvTrackTitle;
            final TextView tvTrackSubtitle;
            final ImageView ivTrackMore;
            final View llNowPlayingOverlay;
            final AnimatedEqualizerView animatedEq;
            TrackViewHolder(@NonNull View itemView) {
                super(itemView);
                ivTrackThumb = itemView.findViewById(R.id.ivTrackThumb);
                vLikedBackground = itemView.findViewById(R.id.vLikedBackground);
                ivLikedIcon = itemView.findViewById(R.id.ivLikedIcon);
                ivPlaylistPin = itemView.findViewById(R.id.ivPlaylistPin);
                flPlaylistOfflineContainer = itemView.findViewById(R.id.flPlaylistOfflineContainer);
                ivPlaylistOfflineAll = itemView.findViewById(R.id.ivPlaylistOfflineAll);
                circularProgress = itemView.findViewById(R.id.circularProgress);
                tvTrackTitle = itemView.findViewById(R.id.tvTrackTitle);
                tvTrackSubtitle = itemView.findViewById(R.id.tvTrackSubtitle);
                ivTrackMore = itemView.findViewById(R.id.ivTrackMore);
                llNowPlayingOverlay = itemView.findViewById(R.id.llNowPlayingOverlay);
                animatedEq = itemView.findViewById(R.id.animatedEq);
            }
        }
    }
    private static final class CachedPlaylistTrack {
        final String videoId;
        final String title;
        final String artist;
        final String duration;
        final String imageUrl;
        CachedPlaylistTrack(
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
        }
    }
    /**
     * Public method to notify that a playlist's offline state has changed.
     * Called from PlaylistDetailFragment when tracks are downloaded or deleted.
     */
    public void notifyPlaylistOfflineChanged(@NonNull String playlistId) {
        if (!isAdded() || adapter == null) {
            return;
        }
        // Force recalculation from files and update UI
        adapter.forceRecalculatePlaylistState(playlistId);
    }

}
