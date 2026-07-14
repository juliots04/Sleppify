package com.example.sleppify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spotify-style artist detail screen, opened from the library when a user taps an artist row.
 * Loads a REAL artist page via YouTubeMusicService.fetchArtistPage(channelId) — header (name +
 * monthly listeners), top songs and albums — and gracefully falls back to a plain search if the
 * artist-page response can't be parsed. The hero is tinted with the artwork's dominant color
 * (Palette) and "Seguir" is synced to the cloud through the shared streaming bucket.
 */
public class ArtistDetailFragment extends Fragment {

    private static final String ARG_CHANNEL_ID = "artist_channel_id";
    private static final String ARG_NAME = "artist_name";
    private static final String ARG_SUBTITLE = "artist_subtitle";
    private static final String ARG_THUMB = "artist_thumb";

    private static final String TAG_SONG_PLAYER = AppConstants.TAG_SONG_PLAYER;
    private static final String TAG_MODULE_MUSIC = "module_music";

    // Cloud-synced key (whitelisted in CloudSyncManager.isStreamingFavoritesKey), stored in the
    // shared streaming_cache prefs so it uploads on change + restores on sign-in for free.
    private static final String KEY_FOLLOWED_ARTISTS = "followed_artists_channel_ids";
    // Tombstone set of artists the user has explicitly UN-followed. The library only ever surfaces
    // artists you already follow, so an artist page defaults to "Siguiendo" unless its id is here.
    // Whitelisted in CloudSyncManager.isStreamingFavoritesKey so unfollows sync + restore for free.
    private static final String KEY_UNFOLLOWED_ARTISTS = "unfollowed_artists_channel_ids";

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TRACK = 1;
    private static final int TYPE_ALBUMS = 2;
    private static final int TYPE_SHOW_MORE = 3;

    // Hero height must stay in sync with the FrameLayout height in item_artist_detail_header.xml.
    private static final int HERO_HEIGHT_DP = 430;
    // Never keep the module-loading overlay hostage to a stalled CDN: reveal after this anyway.
    private static final long REVEAL_TIMEOUT_MS = 1200L;

    private String channelId = "";
    private String artistName = "";
    private String artistSubtitle = "";
    private String heroImageUrl = "";
    private String monthlyListeners = "";

    private final List<YouTubeMusicService.TrackResult> songs = new ArrayList<>();
    private final List<YouTubeMusicService.PlaylistResult> albums = new ArrayList<>();

    // "Ver más" state: browseId of the artist's full songs playlist (from the songs shelf),
    // whether that browse already ran, and the fetched-but-not-yet-shown remainder.
    private String moreSongsBrowseId = "";
    private boolean moreSongsFetched = false;
    private boolean moreSongsLoading = false;
    private final List<YouTubeMusicService.TrackResult> pendingMoreSongs = new ArrayList<>();
    // When true the full remainder (pendingMoreSongs) is shown below the short list ("Ver menos");
    // when false only the short `songs` list shows ("Ver más"). A single tap toggles between them.
    private boolean songsExpanded = false;

    private YouTubeMusicService youTubeMusicService;
    private ArtistAdapter adapter;
    private boolean loaded = false;
    private boolean following = false;

    private int heroDominantColor = 0;
    private boolean paletteRequested = false;
    private int albumCardWidthPx = 0;

    // Overlay reveal is gated on BOTH the data load (revealRequested) and the hero image being
    // decoded (heroReady), with REVEAL_TIMEOUT_MS as the fallback so the overlay never hangs.
    private String lastHeroSizedUrl = "";
    private boolean heroReady = false;
    private boolean revealRequested = false;
    private boolean revealDone = false;
    private final Handler revealHandler = new Handler(Looper.getMainLooper());

    private TextView tvState;
    private TextView tvToolbarTitle;
    private View toolbar;

    @NonNull
    public static ArtistDetailFragment newInstance(
            @NonNull String channelId,
            @NonNull String name,
            @NonNull String subtitle,
            @NonNull String thumbnailUrl
    ) {
        ArtistDetailFragment f = new ArtistDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CHANNEL_ID, channelId);
        args.putString(ARG_NAME, name);
        args.putString(ARG_SUBTITLE, subtitle);
        args.putString(ARG_THUMB, thumbnailUrl);
        f.setArguments(args);
        return f;
    }

    /**
     * Warms Glide's caches with the artist hero BEFORE the fragment transaction so the header's
     * first bind is a pure cache hit and the image is on screen when the overlay drops.
     * CRITICAL: the URL, transforms and override here MUST exactly match the HeaderVH.bind()
     * request — a mismatched key warms an entry the bind can never hit and Glide double-fetches
     * (same caveat as PlaylistDetailFragment's track preloads).
     */
    public static void preloadHero(@NonNull Context context, @Nullable String thumbnailUrl) {
        if (thumbnailUrl == null || thumbnailUrl.trim().isEmpty()) return;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = (int) (HERO_HEIGHT_DP * dm.density);
        Glide.with(context.getApplicationContext())
                .load(ThumbnailUrls.atSize(thumbnailUrl.trim(), w))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .centerCrop()
                .override(w, h)
                .preload(w, h);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle a = getArguments();
        if (a != null) {
            channelId = a.getString(ARG_CHANNEL_ID, "");
            artistName = a.getString(ARG_NAME, "");
            artistSubtitle = a.getString(ARG_SUBTITLE, "");
            heroImageUrl = a.getString(ARG_THUMB, "");
        }
        youTubeMusicService = new YouTubeMusicService();
        following = isFollowed(channelId);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_artist_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // A recreated view means a fresh header ImageView: forget the last hero load so the
        // rebind reloads (from cache), and re-arm the overlay reveal gate.
        lastHeroSizedUrl = "";
        heroReady = false;
        revealRequested = false;
        revealDone = false;

        tvState = view.findViewById(R.id.tvArtistState);
        tvToolbarTitle = view.findViewById(R.id.tvArtistToolbarTitle);
        toolbar = view.findViewById(R.id.llArtistToolbar);
        tvToolbarTitle.setText(artistName);

        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top + dp(6), v.getPaddingRight(), dp(6));
            return insets;
        });
        toolbar.requestApplyInsets();

        view.findViewById(R.id.btnArtistBack).setOnClickListener(v -> goBack());

        RecyclerView rv = view.findViewById(R.id.rvArtistContent);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setHasFixedSize(false);
        rv.setItemAnimator(null);
        adapter = new ArtistAdapter();
        rv.setAdapter(adapter);

        final int[] scrollY = {0};
        final float density = getResources().getDisplayMetrics().density;
        final int base = ContextCompat.getColor(requireContext(), R.color.surface_dark);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                scrollY[0] += dy;
                float start = 300f * density, end = 384f * density;
                float f = Math.max(0f, Math.min(1f, (scrollY[0] - start) / (end - start)));
                tvToolbarTitle.setAlpha(f);
                int alpha = (int) (f * 255);
                toolbar.setBackgroundColor((alpha << 24) | (base & 0x00FFFFFF));
            }
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        goBack();
                    }
                });

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideTopAppBarForPlaylistDetail();
        }

        loadArtist();
    }

    // ----- Loading -----

    private void loadArtist() {
        if (loaded) {
            revealNow();
            return;
        }
        String cookie = readCookie();
        youTubeMusicService.fetchArtistPage(channelId, cookie,
                new YouTubeMusicService.ArtistPageCallback() {
                    @Override
                    public void onSuccess(@NonNull YouTubeMusicService.ArtistPage page) {
                        if (!isAdded()) return;
                        if (!TextUtils.isEmpty(page.subtitle)) monthlyListeners = page.subtitle;
                        if (!TextUtils.isEmpty(page.thumbnailUrl)) heroImageUrl = page.thumbnailUrl;
                        moreSongsBrowseId = page.moreSongsBrowseId == null
                                ? "" : page.moreSongsBrowseId.trim();
                        albums.clear();
                        for (YouTubeMusicService.PlaylistResult p : page.albums) {
                            if (p != null && !TextUtils.isEmpty(p.playlistId) && !TextUtils.isEmpty(p.title)) {
                                albums.add(p);
                            }
                        }
                        List<YouTubeMusicService.TrackResult> top = dedupeSongs(page.topSongs);
                        if (top.isEmpty()) {
                            // browse gave no songs we could parse — fall back to search for the list
                            fetchViaSearch();
                            return;
                        }
                        loaded = true;
                        songs.clear();
                        songs.addAll(top);
                        finishLoad();
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        if (!isAdded()) return;
                        fetchViaSearch();
                    }
                });
    }

    private void fetchViaSearch() {
        // Search results aren't the artist's songs shelf — a "Ver más" browse over them would mix
        // two different orderings, so the button only exists on the real browse path.
        moreSongsBrowseId = "";
        String cookie = readCookie();
        String query = artistName == null ? "" : artistName.trim();
        if (query.isEmpty()) {
            loaded = true;
            finishLoad();
            return;
        }
        youTubeMusicService.searchTracksViaInnertube(query, 40, cookie,
                new YouTubeMusicService.SearchPageCallback() {
                    @Override
                    public void onSuccess(@NonNull YouTubeMusicService.SearchPageResult pageResult) {
                        if (!isAdded()) return;
                        loaded = true;
                        songs.clear();
                        songs.addAll(dedupeSongs(pageResult.tracks));
                        finishLoad();
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        if (!isAdded()) return;
                        loaded = true;
                        finishLoad();
                    }
                });
    }

    private List<YouTubeMusicService.TrackResult> dedupeSongs(List<YouTubeMusicService.TrackResult> in) {
        List<YouTubeMusicService.TrackResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (in == null) return out;
        for (YouTubeMusicService.TrackResult t : in) {
            if (t == null || !t.isVideo() || TextUtils.isEmpty(t.videoId)) continue;
            if (!seen.add(t.videoId)) continue;
            out.add(t);
        }
        return out;
    }

    private void finishLoad() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvState != null) {
            if (songs.isEmpty() && albums.isEmpty()) {
                tvState.setText("No se encontró contenido de este artista.");
                tvState.setVisibility(View.VISIBLE);
            } else {
                tvState.setVisibility(View.GONE);
            }
        }
        revealNow();
    }

    private void revealNow() {
        // Gate the overlay reveal on the hero image being decoded (see onHeroImageSettled) so the
        // screen never appears with a gray hero; the timeout keeps a stalled CDN from hanging it.
        if (revealDone) return;
        revealRequested = true;
        if (heroReady) {
            doRevealNow();
            return;
        }
        revealHandler.postDelayed(this::doRevealNow, REVEAL_TIMEOUT_MS);
    }

    private void doRevealNow() {
        if (revealDone) return;
        revealDone = true;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).revealModuleContent();
        }
    }

    /** Hero Glide request finished (ready OR failed) — release the reveal gate. */
    private void onHeroImageSettled() {
        revealHandler.post(() -> {
            heroReady = true;
            if (revealRequested) doRevealNow();
        });
    }

    @Override
    public void onDestroyView() {
        revealHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void goBack() {
        if (!isAdded()) return;
        FragmentManager fm = getParentFragmentManager();
        if (!fm.isStateSaved()) {
            fm.popBackStack();
        }
    }

    // ----- Playback -----

    private void playFrom(int index) {
        if (!isAdded() || songs.isEmpty()) return;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> artists = new ArrayList<>();
        ArrayList<String> durations = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        // Build the queue from the same list the tapped row index refers to (short list, plus the
        // expanded remainder when "Ver menos" is showing), so index maps to the right track.
        for (YouTubeMusicService.TrackResult t : visibleSongs()) {
            if (TextUtils.isEmpty(t.videoId)) continue;
            ids.add(t.videoId);
            titles.add(t.title == null ? "" : t.title);
            SongSubtitle.Parts p = SongSubtitle.parse(t.subtitle, t.title, t.duration);
            artists.add(p.artist.isEmpty() ? artistName : p.artist);
            durations.add(p.duration.isEmpty() ? "--:--" : p.duration);
            images.add(t.thumbnailUrl == null ? "" : t.thumbnailUrl);
        }
        if (ids.isEmpty()) return;
        SongPlayerLauncher.open(
                getActivity(), ids, titles, artists, durations, images,
                index, /* startPlaying = */ true, TAG_MODULE_MUSIC, /* openPlayerUi = */ true);
    }

    private void playShuffled() {
        int size = visibleSongs().size();
        if (size == 0) return;
        int start = (int) (Math.random() * size);
        playFrom(start);
    }

    private void openAlbum(@NonNull YouTubeMusicService.PlaylistResult album) {
        if (!isAdded() || TextUtils.isEmpty(album.playlistId)) return;
        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) return;
        // Shared launcher: adds the remove-existing step + the top-bar hide this path used to skip
        // (stacking artist→album could leave two live detail instances under one tag). The artist
        // name rides the subtitle slot so the header + track rows can fall back to it. Token dropped.
        PlaylistDetailLauncher.open(
                getActivity(),
                fm,
                album.playlistId,
                album.title,
                artistName == null ? "" : artistName,
                album.thumbnailUrl
        );
    }

    // ----- Follow (cloud-synced) -----

    private SharedPreferences streamingPrefs() {
        return requireContext().getSharedPreferences(
                AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE);
    }

    private boolean isFollowed(String id) {
        // You only reach an artist page from your library's artists (all followed), so default to
        // followed and only fall back to "Seguir" when the id sits in the unfollowed tombstone set.
        if (TextUtils.isEmpty(id) || getContext() == null) return true;
        Set<String> unfollowed = streamingPrefs().getStringSet(KEY_UNFOLLOWED_ARTISTS, null);
        return unfollowed == null || !unfollowed.contains(id);
    }

    private void toggleFollow() {
        if (TextUtils.isEmpty(channelId) || getContext() == null) return;
        Set<String> unfollowed = new HashSet<>(
                streamingPrefs().getStringSet(KEY_UNFOLLOWED_ARTISTS, new HashSet<>()));
        following = !following;
        // Following = not in the tombstone; unfollowing = add the tombstone. Writing this
        // whitelisted key triggers CloudSyncManager's streaming listener -> Firebase.
        if (following) unfollowed.remove(channelId); else unfollowed.add(channelId);
        streamingPrefs().edit().putStringSet(KEY_UNFOLLOWED_ARTISTS, unfollowed).apply();
    }

    private void bindFollowButton(@NonNull TextView btn) {
        btn.setText(following ? "Siguiendo" : "Seguir");
        int color = following
                ? ContextCompat.getColor(requireContext(), R.color.stitch_blue)
                : ContextCompat.getColor(requireContext(), R.color.text_primary);
        btn.setTextColor(color);
    }

    // ----- Palette hero tint -----

    private void applyHeroTint(@NonNull View tintView) {
        if (heroDominantColor != 0) {
            tintView.setBackground(buildHeroGradient(heroDominantColor));
            return;
        }
        if (paletteRequested || TextUtils.isEmpty(heroImageUrl)) return;
        paletteRequested = true;
        final View captured = tintView;
        Glide.with(this)
                .asBitmap()
                .load(heroImageUrl.trim())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        try {
                            Palette.from(resource).generate(palette -> {
                                if (palette == null || !isAdded()) return;
                                int dominant = palette.getDominantColor(0xFF1B1D26);
                                heroDominantColor = dominant;
                                captured.setBackground(buildHeroGradient(dominant));
                            });
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    private GradientDrawable buildHeroGradient(int dominant) {
        int surface = ContextCompat.getColor(requireContext(), R.color.surface_dark);
        int r = (int) (Color.red(dominant) * 0.55f);
        int g = (int) (Color.green(dominant) * 0.55f);
        int b = (int) (Color.blue(dominant) * 0.55f);
        int tint = Color.rgb(r, g, b);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, tint, surface});
        gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private String readCookie() {
        if (getContext() == null) return "";
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, Context.MODE_PRIVATE);
        String raw = prefs.getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "");
        return raw == null ? "" : raw.trim();
    }

    // ----- Adapters -----

    private class ArtistAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean hasAlbums() {
            return !albums.isEmpty();
        }

        // The "Ver más" row shows while the browse id exists and there are still more songs to
        // reveal (either not fetched yet, or fetched with a non-empty remainder).
        private boolean hasShowMore() {
            return !TextUtils.isEmpty(moreSongsBrowseId)
                    && !songs.isEmpty()
                    && (!moreSongsFetched || !pendingMoreSongs.isEmpty());
        }

        // Row order: header, tracks, [Ver más], [Álbumes]. The last two are optional and, when both
        // present, "Ver más" precedes "Álbumes".
        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            int albumsPos = hasAlbums() ? getItemCount() - 1 : -1;
            if (position == albumsPos) return TYPE_ALBUMS;
            int showMorePos = hasShowMore() ? 1 + visibleTrackCount() : -1;
            if (position == showMorePos) return TYPE_SHOW_MORE;
            return TYPE_TRACK;
        }

        @Override
        public int getItemCount() {
            return 1 + visibleTrackCount() + (hasShowMore() ? 1 : 0) + (hasAlbums() ? 1 : 0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_artist_detail_header, parent, false));
            }
            if (viewType == TYPE_ALBUMS) {
                return new AlbumsVH(inflater.inflate(R.layout.item_artist_albums_section, parent, false));
            }
            if (viewType == TYPE_SHOW_MORE) {
                return new ShowMoreVH(inflater.inflate(R.layout.item_artist_show_more, parent, false));
            }
            return new TrackVH(inflater.inflate(R.layout.item_artist_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind();
            } else if (holder instanceof AlbumsVH) {
                ((AlbumsVH) holder).bind();
            } else if (holder instanceof ShowMoreVH) {
                ((ShowMoreVH) holder).bind();
            } else if (holder instanceof TrackVH) {
                ((TrackVH) holder).bind(trackAt(position - 1), position - 1);
            }
        }
    }

    /** Number of track rows currently shown: the short list, plus the full remainder when expanded. */
    private int visibleTrackCount() {
        return songs.size() + (songsExpanded ? pendingMoreSongs.size() : 0);
    }

    /** Track shown at the given 0-based row index across the short list + expanded remainder. */
    private YouTubeMusicService.TrackResult trackAt(int index) {
        if (index < songs.size()) return songs.get(index);
        return pendingMoreSongs.get(index - songs.size());
    }

    /** The tracks currently on screen (short list, plus the remainder when expanded), for playback. */
    private List<YouTubeMusicService.TrackResult> visibleSongs() {
        List<YouTubeMusicService.TrackResult> out = new ArrayList<>(songs);
        if (songsExpanded) out.addAll(pendingMoreSongs);
        return out;
    }

    /**
     * Single expand/collapse toggle for the artist's popular songs. The first tap browses the full
     * songs playlist (the shelf's bottomEndpoint) once, keeps the WHOLE remainder in memory and
     * expands ("Ver menos"); every later tap just flips between showing/hiding that remainder
     * ("Ver más" / "Ver menos") with no further network.
     */
    private void onToggleSongs() {
        if (moreSongsLoading) return;
        if (moreSongsFetched) {
            songsExpanded = !songsExpanded;
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        if (TextUtils.isEmpty(moreSongsBrowseId)) return;
        moreSongsLoading = true;
        if (adapter != null) adapter.notifyDataSetChanged();
        // The songs shelf's bottomEndpoint browseId is normally already the "VL"-prefixed browse
        // form; prefix it defensively when a bare playlist id slipped through (InnerTube convention).
        String browseId = moreSongsBrowseId.trim();
        if (!browseId.startsWith("VL") && !browseId.startsWith("MPRE") && !browseId.startsWith("OLAK")) {
            browseId = "VL" + browseId;
        }
        youTubeMusicService.fetchAlbumTracks(readCookie(), browseId,
                new YouTubeMusicService.MixTracksCallback() {
                    @Override
                    public void onSuccess(@NonNull List<YouTubeMusicService.TrackResult> tracks) {
                        if (!isAdded()) return;
                        moreSongsLoading = false;
                        moreSongsFetched = true;
                        // Keep only songs we're not already showing.
                        Set<String> known = new HashSet<>();
                        for (YouTubeMusicService.TrackResult s : songs) {
                            if (s != null && !TextUtils.isEmpty(s.videoId)) known.add(s.videoId);
                        }
                        pendingMoreSongs.clear();
                        for (YouTubeMusicService.TrackResult t : dedupeSongs(tracks)) {
                            if (known.add(t.videoId)) pendingMoreSongs.add(t);
                        }
                        // First tap reveals the whole remainder at once. If the browse added nothing,
                        // hasShowMore() becomes false and the row disappears.
                        songsExpanded = true;
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        if (!isAdded()) return;
                        moreSongsLoading = false;
                        moreSongsFetched = true; // don't hammer a failing browse; hide the button
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                });
    }

    private class HeaderVH extends RecyclerView.ViewHolder {
        private final ImageView backdrop;
        private final View heroTint;
        private final TextView name;
        private final TextView subtitle;
        private final TextView follow;
        private final View play;
        private final View shuffle;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            backdrop = itemView.findViewById(R.id.ivArtistBackdrop);
            heroTint = itemView.findViewById(R.id.vArtistHeroTint);
            name = itemView.findViewById(R.id.tvArtistHeaderName);
            subtitle = itemView.findViewById(R.id.tvArtistHeaderSubtitle);
            follow = itemView.findViewById(R.id.btnArtistFollow);
            play = itemView.findViewById(R.id.btnArtistPlay);
            shuffle = itemView.findViewById(R.id.btnArtistShuffle);
        }

        void bind() {
            name.setText(TextUtils.isEmpty(artistName) ? "Artista" : artistName);
            String sub = !TextUtils.isEmpty(monthlyListeners) ? monthlyListeners : artistSubtitle;
            if (TextUtils.isEmpty(sub)) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(sub);
            }
            loadHero();
            if (heroTint != null) applyHeroTint(heroTint);
            bindFollowButton(follow);
            follow.setOnClickListener(v -> {
                toggleFollow();
                bindFollowButton(follow);
            });
            play.setOnClickListener(v -> playFrom(0));
            shuffle.setOnClickListener(v -> playShuffled());
        }

        /**
         * Loads the hero at display resolution. The URL/transforms/override match preloadHero() so
         * a pre-warmed entry is a pure cache hit; a small low-res .thumbnail() paints instantly
         * while the HD frame decodes; and the request listener releases the overlay reveal gate.
         * When fetchArtistPage overwrites heroImageUrl with a URL that resolves to the SAME sized
         * request, the reload is skipped so the header doesn't visibly re-load.
         */
        void loadHero() {
            if (TextUtils.isEmpty(heroImageUrl)) return;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = (int) (HERO_HEIGHT_DP * dm.density);
            String raw = heroImageUrl.trim();
            String sizedNullable = ThumbnailUrls.atSize(raw, w);
            String sized = sizedNullable == null ? raw : sizedNullable;
            if (sized.equals(lastHeroSizedUrl)) return; // already loaded this exact request
            lastHeroSizedUrl = sized;

            String lowNullable = ThumbnailUrls.atSize(raw, 120);
            String low = lowNullable == null ? raw : lowNullable;

            Glide.with(backdrop)
                    .load(sized)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(w, h)
                    .centerCrop()
                    .thumbnail(Glide.with(backdrop)
                            .load(low)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .centerCrop())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            onHeroImageSettled();
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            onHeroImageSettled();
                            return false;
                        }
                    })
                    .into(backdrop);
        }
    }

    private class AlbumsVH extends RecyclerView.ViewHolder {
        private final RecyclerView rv;
        private boolean initialized = false;

        AlbumsVH(@NonNull View itemView) {
            super(itemView);
            rv = itemView.findViewById(R.id.rvArtistAlbums);
        }

        void bind() {
            if (!initialized) {
                rv.setLayoutManager(new LinearLayoutManager(
                        rv.getContext(), LinearLayoutManager.HORIZONTAL, false));
                rv.setHasFixedSize(true);
                rv.setAdapter(new AlbumAdapter());
                initialized = true;
            } else if (rv.getAdapter() != null) {
                rv.getAdapter().notifyDataSetChanged();
            }
        }
    }

    /** Card width for artist albums: 46% of the SHORT screen axis, matching the home carousels. */
    private int albumCardWidthPx() {
        if (albumCardWidthPx <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            albumCardWidthPx = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.46f);
        }
        return albumCardWidthPx;
    }

    private class AlbumAdapter extends RecyclerView.Adapter<AlbumVH> {
        @Override
        public int getItemCount() {
            return albums.size();
        }

        @NonNull
        @Override
        public AlbumVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_artist_album, parent, false);
            // Force each card to the home-carousel width with 6dp side margins (the layout is
            // match_parent so the width is owned here, like PrincipalFragment's carousels).
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    albumCardWidthPx(), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(6));
            lp.setMarginEnd(dp(6));
            v.setLayoutParams(lp);
            return new AlbumVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull AlbumVH holder, int position) {
            holder.bind(albums.get(position));
        }
    }

    private class AlbumVH extends RecyclerView.ViewHolder {
        private final ShapeableImageView cover;
        private final TextView title;
        private final TextView subtitle;

        AlbumVH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.ivArtistAlbumCover);
            title = itemView.findViewById(R.id.tvArtistAlbumTitle);
            subtitle = itemView.findViewById(R.id.tvArtistAlbumSubtitle);
        }

        void bind(@NonNull YouTubeMusicService.PlaylistResult album) {
            title.setText(album.title);
            if (TextUtils.isEmpty(album.ownerName)) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(album.ownerName);
            }
            if (!TextUtils.isEmpty(album.thumbnailUrl)) {
                int px = albumCardWidthPx();
                String sized = ThumbnailUrls.atSize(album.thumbnailUrl.trim(), px);
                Glide.with(cover)
                        .load(sized == null ? album.thumbnailUrl.trim() : sized)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(px, px)
                        .centerCrop()
                        .into(cover);
            } else {
                cover.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                        ContextCompat.getColor(cover.getContext(), R.color.surface_high)));
            }
            itemView.setOnClickListener(v -> openAlbum(album));
        }
    }

    private class ShowMoreVH extends RecyclerView.ViewHolder {
        private final TextView btn;

        ShowMoreVH(@NonNull View itemView) {
            super(itemView);
            btn = itemView.findViewById(R.id.btnArtistShowMore);
        }

        void bind() {
            btn.setText(moreSongsLoading ? "Cargando…" : (songsExpanded ? "Ver menos" : "Ver más"));
            btn.setEnabled(!moreSongsLoading);
            btn.setAlpha(moreSongsLoading ? 0.5f : 1f);
            btn.setOnClickListener(v -> onToggleSongs());
        }
    }

    private class TrackVH extends RecyclerView.ViewHolder {
        private final ShapeableImageView art;
        private final TextView title;
        private final TextView subtitle;

        TrackVH(@NonNull View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.ivArtistTrackArt);
            title = itemView.findViewById(R.id.tvArtistTrackTitle);
            subtitle = itemView.findViewById(R.id.tvArtistTrackSubtitle);
        }

        void bind(@NonNull YouTubeMusicService.TrackResult track, int index) {
            title.setText(track.title);
            String row = SongSubtitle.forRow(track.subtitle, track.title, track.duration);
            subtitle.setText(TextUtils.isEmpty(row) ? artistName : row);
            if (!TextUtils.isEmpty(track.thumbnailUrl)) {
                Glide.with(art)
                        .load(track.thumbnailUrl.trim())
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(160, 160)
                        .centerCrop()
                        .into(art);
            } else {
                art.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                        ContextCompat.getColor(art.getContext(), R.color.surface_high)));
            }
            itemView.setOnClickListener(v -> playFrom(index));
        }
    }
}
