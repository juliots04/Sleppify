package com.example.sleppify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
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
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
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

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TRACK = 1;
    private static final int TYPE_ALBUMS = 2;

    private String channelId = "";
    private String artistName = "";
    private String artistSubtitle = "";
    private String heroImageUrl = "";
    private String monthlyListeners = "";

    private final List<YouTubeMusicService.TrackResult> songs = new ArrayList<>();
    private final List<YouTubeMusicService.PlaylistResult> albums = new ArrayList<>();

    private YouTubeMusicService youTubeMusicService;
    private ArtistAdapter adapter;
    private boolean loaded = false;
    private boolean following = false;

    private int heroDominantColor = 0;
    private boolean paletteRequested = false;

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
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).revealModuleContent();
        }
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
        for (YouTubeMusicService.TrackResult t : songs) {
            if (TextUtils.isEmpty(t.videoId)) continue;
            ids.add(t.videoId);
            titles.add(t.title == null ? "" : t.title);
            artists.add(t.subtitle == null ? "" : t.subtitle);
            durations.add("--:--");
            images.add(t.thumbnailUrl == null ? "" : t.thumbnailUrl);
        }
        if (ids.isEmpty()) return;
        SongPlayerLauncher.open(
                getActivity(), ids, titles, artists, durations, images,
                index, /* startPlaying = */ true, TAG_MODULE_MUSIC, /* openPlayerUi = */ true);
    }

    private void playShuffled() {
        if (songs.isEmpty()) return;
        int start = (int) (Math.random() * songs.size());
        playFrom(start);
    }

    private void openAlbum(@NonNull YouTubeMusicService.PlaylistResult album) {
        if (!isAdded() || TextUtils.isEmpty(album.playlistId)) return;
        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) return;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showModuleLoadingOverlay();
        }
        PlaylistDetailFragment detail = PlaylistDetailFragment.newInstance(
                album.playlistId, album.title, "", album.thumbnailUrl, "");
        fm.beginTransaction().setReorderingAllowed(true)
                .add(R.id.fragmentContainer, detail, "playlist_detail")
                .addToBackStack("playlist_detail")
                .commit();
    }

    // ----- Follow (cloud-synced) -----

    private SharedPreferences streamingPrefs() {
        return requireContext().getSharedPreferences(
                AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE);
    }

    private boolean isFollowed(String id) {
        if (TextUtils.isEmpty(id) || getContext() == null) return false;
        Set<String> set = streamingPrefs().getStringSet(KEY_FOLLOWED_ARTISTS, new HashSet<>());
        return set != null && set.contains(id);
    }

    private void toggleFollow() {
        if (TextUtils.isEmpty(channelId) || getContext() == null) return;
        Set<String> set = new HashSet<>(streamingPrefs().getStringSet(KEY_FOLLOWED_ARTISTS, new HashSet<>()));
        following = !following;
        if (following) set.add(channelId); else set.remove(channelId);
        // Writing this whitelisted key triggers CloudSyncManager's streaming listener -> Firebase.
        streamingPrefs().edit().putStringSet(KEY_FOLLOWED_ARTISTS, set).apply();
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

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            if (hasAlbums() && position == getItemCount() - 1) return TYPE_ALBUMS;
            return TYPE_TRACK;
        }

        @Override
        public int getItemCount() {
            return 1 + songs.size() + (hasAlbums() ? 1 : 0);
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
            return new TrackVH(inflater.inflate(R.layout.item_artist_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind();
            } else if (holder instanceof AlbumsVH) {
                ((AlbumsVH) holder).bind();
            } else if (holder instanceof TrackVH) {
                ((TrackVH) holder).bind(songs.get(position - 1), position - 1);
            }
        }
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
            if (!TextUtils.isEmpty(heroImageUrl)) {
                Glide.with(backdrop)
                        .load(heroImageUrl.trim())
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()
                        .into(backdrop);
            }
            if (heroTint != null) applyHeroTint(heroTint);
            bindFollowButton(follow);
            follow.setOnClickListener(v -> {
                toggleFollow();
                bindFollowButton(follow);
            });
            play.setOnClickListener(v -> playFrom(0));
            shuffle.setOnClickListener(v -> playShuffled());
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

    private class AlbumAdapter extends RecyclerView.Adapter<AlbumVH> {
        @Override
        public int getItemCount() {
            return albums.size();
        }

        @NonNull
        @Override
        public AlbumVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new AlbumVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_artist_album, parent, false));
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
                Glide.with(cover)
                        .load(album.thumbnailUrl.trim())
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(300, 300)
                        .centerCrop()
                        .into(cover);
            } else {
                cover.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                        ContextCompat.getColor(cover.getContext(), R.color.surface_high)));
            }
            itemView.setOnClickListener(v -> openAlbum(album));
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
            subtitle.setText(TextUtils.isEmpty(track.subtitle) ? artistName : track.subtitle);
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
