package com.example.sleppify

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.sleppify.utils.YouTubeCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject

class PrincipalFragment : Fragment(), PlaybackEventBus.Listener {

    companion object {
        private const val TAG_SONG_PLAYER = "song_player"
        private const val PREFS_PLAYER_STATE = "player_state"
        private const val PREF_LAST_YOUTUBE_WEB_COOKIE = "stream_last_youtube_web_cookie"
        private const val PREFS_STREAMING_CACHE = "streaming_cache"
        private const val CACHE_KEY_HOME_MIXES = "home_mixes_data"
        private const val SHORTCUTS_PER_PAGE = 9
        private const val SHORTCUTS_MAX_PAGES = 3
        private const val COVERS_PER_PAGE = 4
        private const val NETWORK_REFRESH_THROTTLE_MS = 180_000L
        private val SHARED_YT_CROP = YouTubeCropTransformation()
        private val SHARED_CENTER_CROP = CenterCrop()
        private val SHARED_ROUNDED_16 = RoundedCorners(16)
    }

    // Brand header views
    private var llFragBrandHeader: View? = null
    private var tvFragBrandTitle: TextView? = null
    private var btnFragHeaderSearch: ImageView? = null
    private var btnFragSignIn: MaterialButton? = null
    private var btnFragProfilePhoto: ShapeableImageView? = null

    // Content views
    private var vpShortcuts: ViewPager2? = null
    private var tabDotsShortcuts: TabLayout? = null
    private var rvMixes: RecyclerView? = null
    private var tvMixesEmpty: TextView? = null
    private var ivShortcutProfilePhoto: ShapeableImageView? = null
    private var tvPersonalMixesLabel: TextView? = null
    private var rvPersonalMixes: RecyclerView? = null
    private var llCoversHeader: View? = null
    private var tvCoversLabel: TextView? = null
    private var btnCoversPlayAll: TextView? = null
    private var vpCovers: ViewPager2? = null
    private var tabDotsCovers: TabLayout? = null

    // State
    private val handler = Handler(Looper.getMainLooper())
    private var cachedSongPlayer: SongPlayerFragment? = null
    private var lastCachedSongPlayerTime = 0L
    private lateinit var youTubeMusicService: YouTubeMusicService

    // Throttling
    private var lastMixesNetworkFetchTimeMs = 0L
    private var lastCoversNetworkFetchTimeMs = 0L
    private var lastShortcutsFetchTimeMs = 0L
    private val playlistGridUrlsCache = HashMap<String, List<String>>()

    // Currently playing shortcut
    private var currentlyPlayingShortcutVideoId = ""
    private var lastEqRefreshVideoId = ""

    // Data
    private val shortcutEntries = mutableListOf<PlayCountStore.PlayCountEntry>()
    private val mixResults = mutableListOf<YouTubeMusicService.MixResult>()
    private val personalMixResults = mutableListOf<YouTubeMusicService.MixResult>()
    private val coversResults = mutableListOf<YouTubeMusicService.TrackResult>()

    // Helper structure
    private class QueueData {
        val ids = ArrayList<String>()
        val titles = ArrayList<String>()
        val artists = ArrayList<String>()
        val durations = ArrayList<String>()
        val images = ArrayList<String>()
    }

    private fun extractQueueData(tracks: List<YouTubeMusicService.TrackResult>): QueueData {
        val data = QueueData()
        for (t in tracks) {
            if (t.videoId.isNullOrEmpty()) continue
            data.ids.add(t.videoId)
            data.titles.add(t.title)
            data.artists.add(t.subtitle ?: "")
            data.durations.add("--:--")
            data.images.add(t.thumbnailUrl ?: "")
        }
        return data
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_principal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFragBrandHeader(view)

        vpShortcuts = view.findViewById(R.id.vpShortcuts)
        tabDotsShortcuts = view.findViewById(R.id.tabDotsShortcuts)
        rvMixes = view.findViewById(R.id.rvMixes)
        tvMixesEmpty = view.findViewById(R.id.tvMixesEmpty)
        ivShortcutProfilePhoto = view.findViewById(R.id.ivShortcutProfilePhoto)
        tvPersonalMixesLabel = view.findViewById(R.id.tvPersonalMixesLabel)
        rvPersonalMixes = view.findViewById(R.id.rvPersonalMixes)
        llCoversHeader = view.findViewById(R.id.llCoversHeader)
        tvCoversLabel = view.findViewById(R.id.tvCoversLabel)
        btnCoversPlayAll = view.findViewById(R.id.btnCoversPlayAll)
        vpCovers = view.findViewById(R.id.vpCovers)
        tabDotsCovers = view.findViewById(R.id.tabDotsCovers)

        youTubeMusicService = YouTubeMusicService()

        setupShortcuts()
        setupMixes()
        setupPersonalMixes()
        setupCovers()
        loadCachedMixes()
        loadCachedCovers()
    }

    override fun onResume() {
        super.onResume()
        PlaybackEventBus.addListener(this)
        if (isHidden) return
        refreshFragHeaderProfilePhoto()
        refreshShortcuts()
        refreshMixes()
        loadGoogleProfilePhoto()
        refreshCovers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackEventBus.removeListener(this)
        playlistGridUrlsCache.clear()
        vpShortcuts = null
        tabDotsShortcuts = null
        rvMixes = null
        tvMixesEmpty = null
        llFragBrandHeader = null
        tvFragBrandTitle = null
        btnFragHeaderSearch = null
        btnFragSignIn = null
        btnFragProfilePhoto = null
        ivShortcutProfilePhoto = null
        tvPersonalMixesLabel = null
        rvPersonalMixes = null
        llCoversHeader = null
        tvCoversLabel = null
        btnCoversPlayAll = null
        vpCovers = null
        tabDotsCovers = null
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.findViewById<View>(R.id.nsvPrincipalContent)?.let { nsv ->
                nsv.post { nsv.scrollTo(0, 0) }
            }
            handler.postDelayed({
                if (isAdded && !isHidden) refreshShortcuts()
            }, 200)
            refreshFragHeaderProfilePhoto()
            refreshMixes()
            loadGoogleProfilePhoto()
            refreshCovers()
        }
    }

    // ========== Brand Header ==========

    private fun setupFragBrandHeader(root: View) {
        llFragBrandHeader = root.findViewById(R.id.llFragBrandHeader)
        tvFragBrandTitle = root.findViewById(R.id.tvFragBrandTitle)
        btnFragHeaderSearch = root.findViewById(R.id.btnFragHeaderSearch)
        btnFragSignIn = root.findViewById(R.id.btnFragSignIn)
        btnFragProfilePhoto = root.findViewById(R.id.btnFragProfilePhoto)

        llFragBrandHeader?.let { header ->
            ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
                insets
            }
            header.requestApplyInsets()
        }

        tvFragBrandTitle?.let { tv ->
            tv.isAllCaps = true
            tv.letterSpacing = 0.08f
            try {
                val brandFont: Typeface? = ResourcesCompat.getFont(requireContext(), R.font.manrope_variable)
                if (brandFont != null) tv.typeface = brandFont
            } catch (_: Exception) {}
            val density = resources.displayMetrics.density
            val iconSize = (26 * density).toInt()
            val icon = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher)
            icon?.setBounds(0, 0, iconSize, iconSize)
            tv.setCompoundDrawablesRelative(icon, null, null, null)
            tv.compoundDrawablePadding = (8 * density).toInt()
        }

        btnFragHeaderSearch?.setOnClickListener {
            (activity as? MainActivity)?.openSearchFragment()
        }

        btnFragSignIn?.setOnClickListener {
            val mainActivity = activity as? MainActivity ?: return@setOnClickListener
            btnFragSignIn?.isEnabled = false
            btnFragSignIn?.alpha = 0.56f
            mainActivity.requireAuth(
                {
                    btnFragSignIn?.isEnabled = true
                    btnFragSignIn?.alpha = 1f
                    refreshFragHeaderProfilePhoto()
                },
                {
                    btnFragSignIn?.isEnabled = true
                    btnFragSignIn?.alpha = 1f
                }
            )
        }

        btnFragProfilePhoto?.setOnClickListener {
            (activity as? MainActivity)?.enterSettings()
        }

        refreshFragHeaderProfilePhoto()
    }

    fun refreshFragHeaderProfilePhoto() {
        if (!isAdded || btnFragProfilePhoto == null || btnFragSignIn == null) return
        val prefs = requireContext().getSharedPreferences("streaming_cache", Activity.MODE_PRIVATE)
        val cachedUrl = prefs.getString("cached_google_profile_photo_url", "") ?: ""
        var photoUri: Uri? = FirebaseAuth.getInstance().currentUser?.photoUrl
        if (photoUri == null && cachedUrl.isNotEmpty()) {
            photoUri = Uri.parse(cachedUrl)
        }
        val signedIn = (activity as? MainActivity)?.getAuthManager()?.isSignedIn() == true
        if (signedIn) {
            btnFragSignIn?.visibility = View.GONE
            btnFragProfilePhoto?.visibility = View.VISIBLE
            if (photoUri != null) {
                Glide.with(this)
                    .load(photoUri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .into(btnFragProfilePhoto!!)
            } else {
                btnFragProfilePhoto?.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        } else {
            btnFragProfilePhoto?.visibility = View.GONE
            btnFragProfilePhoto?.setImageDrawable(null)
            btnFragSignIn?.visibility = View.VISIBLE
        }
    }

    // ========== PlaybackEventBus ==========

    override fun onPlaybackSnapshotUpdated() {
        if (!isAdded || activity == null) return
        activity?.runOnUiThread {
            val nowId = getCurrentPlayingVideoId()
            if (nowId != lastEqRefreshVideoId) {
                lastEqRefreshVideoId = nowId
                refreshShortcutEqIcons()
            }
        }
    }

    // ========== Shortcuts (ViewPager2 with 3x3 grids) ==========

    private fun setupShortcuts() {
        vpShortcuts?.adapter = ShortcutsPagerAdapter()
        vpShortcuts?.offscreenPageLimit = SHORTCUTS_MAX_PAGES
        TabLayoutMediator(tabDotsShortcuts!!, vpShortcuts!!) { _, _ -> }.attach()
    }

    private fun refreshShortcuts() {
        val now = System.currentTimeMillis()
        if (now - lastShortcutsFetchTimeMs < 15_000L && shortcutEntries.isNotEmpty()) return

        val totalNeeded = SHORTCUTS_PER_PAGE * SHORTCUTS_MAX_PAGES
        lastShortcutsFetchTimeMs = now

        val topTracks = ArrayList(PlayCountStore.getTopEntries(requireContext(), totalNeeded))
        val topPlaylists = ArrayList(PlayCountStore.getTopPlaylists(requireContext(), 5))

        topPlaylists.removeAll { LocalFilesStore.PLAYLIST_ID == it.playlistId }
        if (topPlaylists.size > 1) topPlaylists.subList(1, topPlaylists.size).clear()

        val seenIds = HashSet<String>()
        val merged = mutableListOf<PlayCountStore.PlayCountEntry>()
        for (p in topPlaylists) { merged.add(p); seenIds.add(p.videoId) }
        for (t in topTracks) {
            if (t.videoId in seenIds) continue
            seenIds.add(t.videoId)
            merged.add(t)
        }
        merged.sortWith(compareByDescending<PlayCountStore.PlayCountEntry> { it.count }.thenByDescending { it.lastPlayedAtMs })

        val top = ArrayList(merged.take(totalNeeded))
        if (top.size < totalNeeded) fillShortcutsFromHistory(top, totalNeeded)

        shortcutEntries.clear()
        shortcutEntries.addAll(top)

        vpShortcuts?.adapter?.notifyDataSetChanged()
        tabDotsShortcuts?.let {
            val pageCount = Math.max(1, Math.ceil(shortcutEntries.size / SHORTCUTS_PER_PAGE.toFloat().toDouble()).toInt())
            it.visibility = if (pageCount > 1) View.VISIBLE else View.GONE
        }
    }

    private fun fillShortcutsFromHistory(existing: MutableList<PlayCountStore.PlayCountEntry>, totalNeeded: Int) {
        if (!isAdded) return
        val existingIds = existing.map { it.videoId }.toHashSet()

        val snapshot = PlaybackHistoryStore.load(requireContext())
        for (track in snapshot.queue) {
            if (existing.size >= totalNeeded) break
            if (track.videoId in existingIds) continue
            existingIds.add(track.videoId)
            existing.add(PlayCountStore.PlayCountEntry(track.videoId, track.title, track.artist, track.imageUrl, "", "", 0, 0L))
        }

        try {
            val favs = FavoritesPlaylistStore.loadFavorites(requireContext())
            for (fav in favs) {
                if (existing.size >= totalNeeded) break
                if (fav.videoId in existingIds) continue
                existingIds.add(fav.videoId)
                existing.add(PlayCountStore.PlayCountEntry(fav.videoId, fav.title, fav.artist, fav.imageUrl, FavoritesPlaylistStore.PLAYLIST_ID, "Favoritos", 0, 0L))
            }
        } catch (_: Exception) {}
    }

    // ========== Mixes ==========

    private fun setupMixes() {
        rvMixes?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvMixes?.adapter = MixesAdapter(mixResults)
    }

    private fun refreshMixes() {
        val now = System.currentTimeMillis()
        if (now - lastMixesNetworkFetchTimeMs < NETWORK_REFRESH_THROTTLE_MS && mixResults.isNotEmpty()) return

        val cookie = getCookieHeader()
        if (cookie.isEmpty()) {
            tvMixesEmpty?.visibility = View.GONE
            return
        }

        youTubeMusicService.fetchHomeBrowse(cookie, object : YouTubeMusicService.HomeBrowseCallback {
            override fun onSuccess(result: YouTubeMusicService.HomeBrowseResult) {
                if (!isAdded) return
                lastMixesNetworkFetchTimeMs = System.currentTimeMillis()

                mixResults.clear()
                mixResults.addAll(result.genericMixes)
                cacheMixes(result.genericMixes, result.personalMixes)
                rvMixes?.adapter?.notifyDataSetChanged()
                val empty = mixResults.isEmpty()
                tvMixesEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
                rvMixes?.visibility = if (empty) View.GONE else View.VISIBLE

                personalMixResults.clear()
                personalMixResults.addAll(result.personalMixes)
                rvPersonalMixes?.adapter?.notifyDataSetChanged()
                val personalEmpty = personalMixResults.isEmpty()
                tvPersonalMixesLabel?.visibility = if (personalEmpty) View.GONE else View.VISIBLE
                rvPersonalMixes?.visibility = if (personalEmpty) View.GONE else View.VISIBLE
            }

            override fun onError(error: String) {
                if (!isAdded) return
                if (mixResults.isEmpty()) {
                    tvMixesEmpty?.visibility = View.VISIBLE
                    rvMixes?.visibility = View.GONE
                }
            }
        })
    }

    private fun loadGoogleProfilePhoto() {
        if (!isAdded || ivShortcutProfilePhoto == null) return
        var photoUri: Uri? = FirebaseAuth.getInstance().currentUser?.photoUrl
        if (photoUri == null) {
            val cached = requireContext().getSharedPreferences("streaming_cache", Context.MODE_PRIVATE)
                .getString("cached_google_profile_photo_url", "") ?: ""
            if (cached.isNotEmpty()) photoUri = Uri.parse(cached)
        }
        if (photoUri != null) {
            try { Glide.with(this).load(photoUri).circleCrop().into(ivShortcutProfilePhoto!!) } catch (_: Exception) {}
        }
    }

    private fun setupPersonalMixes() {
        rvPersonalMixes ?: return
        rvPersonalMixes?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvPersonalMixes?.adapter = MixesAdapter(personalMixResults)
    }

    // ========== Covers ==========

    private fun setupCovers() {
        vpCovers ?: return
        vpCovers?.adapter = CoversPagerAdapter()
        vpCovers?.offscreenPageLimit = 3
        tabDotsCovers?.let { TabLayoutMediator(it, vpCovers!!) { _, _ -> }.attach() }
        btnCoversPlayAll?.setOnClickListener {
            if (coversResults.isNotEmpty()) playTrackList(coversResults, 0)
        }
    }

    private fun refreshCovers() {
        val now = System.currentTimeMillis()
        if (now - lastCoversNetworkFetchTimeMs < NETWORK_REFRESH_THROTTLE_MS && coversResults.isNotEmpty()) return

        val cookie = getCookieHeader()
        if (cookie.isEmpty()) return

        val topTitles = PlayCountStore.getTopEntries(requireContext(), 10)
            .mapNotNull { it.title.takeIf { t -> t.isNotEmpty() } }
            .toMutableList()

        val extraTitles = mutableListOf<String>()
        try {
            val cachePrefs = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val topSet = topTitles.toHashSet()
            for (key in cachePrefs.all.keys) {
                if (!key.startsWith("playlist_tracks_data_")) continue
                val raw = cachePrefs.getString(key, "") ?: ""
                if (raw.isEmpty()) continue
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val title = arr.optJSONObject(i)?.optString("title", "")?.trim() ?: ""
                        if (title.isNotEmpty() && title !in topSet) extraTitles.add(title)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        val selected = mutableListOf<String>()
        topTitles.shuffle()
        selected.addAll(topTitles.take(6))
        if (extraTitles.isNotEmpty()) {
            extraTitles.shuffle()
            selected.addAll(extraTitles.take(8 - selected.size))
        }
        if (selected.size < 8) {
            for (t in topTitles) { if (selected.size >= 8) break; if (t !in selected) selected.add(t) }
        }
        if (selected.isEmpty()) return

        youTubeMusicService.fetchCoversAndRemixes(cookie, selected, object : YouTubeMusicService.CoversRemixesCallback {
            override fun onSuccess(tracks: List<YouTubeMusicService.TrackResult>) {
                if (!isAdded) return
                lastCoversNetworkFetchTimeMs = System.currentTimeMillis()
                coversResults.clear()
                coversResults.addAll(tracks.shuffled())
                cacheCovers(coversResults)
                updateCoversUi()
            }
            override fun onError(error: String) {}
        })
    }

    private fun updateCoversUi() {
        val empty = coversResults.isEmpty()
        llCoversHeader?.visibility = if (empty) View.GONE else View.VISIBLE
        vpCovers?.let {
            it.visibility = if (empty) View.GONE else View.VISIBLE
            it.adapter?.notifyDataSetChanged()
        }
        tabDotsCovers?.let {
            val pageCount = Math.ceil(coversResults.size / COVERS_PER_PAGE.toFloat().toDouble()).toInt()
            it.visibility = if (pageCount > 1) View.VISIBLE else View.GONE
        }
    }

    // ========== Actions ==========

    private fun onMixClicked(mix: YouTubeMusicService.MixResult) {
        if (!isAdded || mix.playlistId.isEmpty()) return
        openPlaylistDetailFromPrincipal(mix.playlistId, mix.title, mix.thumbnailUrl)
    }

    private fun playTrackList(tracks: List<YouTubeMusicService.TrackResult>, startIndex: Int) {
        if (!isAdded || tracks.isEmpty()) return
        val data = extractQueueData(tracks)
        if (data.ids.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, data.ids.size - 1)

        (activity as? MainActivity)?.getGlobalMiniPlayer()?.animateOut()

        val existingPlayer = findSongPlayerFragment()
        if (existingPlayer != null && existingPlayer.isAdded) {
            existingPlayer.externalSetReturnTargetTag("module_principal")
            existingPlayer.externalReplaceQueue(data.ids, data.titles, data.artists, data.durations, data.images, safeIndex, true)
            showSongPlayerWithEnterAnimation(existingPlayer)
            return
        }

        val playerFragment = SongPlayerFragment.newInstance(data.ids, data.titles, data.artists, data.durations, data.images, safeIndex, true)
        playerFragment.externalSetReturnTargetTag("module_principal")
        addSongPlayerWithEnterAnimation(playerFragment)
    }

    private fun onShortcutClicked(entry: PlayCountStore.PlayCountEntry) {
        if (!isAdded || entry.videoId.isNullOrEmpty()) return

        if (!entry.playlistId.isNullOrEmpty() && entry.videoId == entry.playlistId) {
            openPlaylistDetailFromPrincipal(entry.playlistId, entry.playlistName, entry.imageUrl)
            return
        }

        currentlyPlayingShortcutVideoId = entry.videoId
        refreshShortcutEqIcons()

        val clickedTrack = YouTubeMusicService.TrackResult("video", entry.videoId, entry.title, entry.artist, entry.imageUrl)
        playTrackInBackground(listOf(clickedTrack), 0)

        val cookie = getCookieHeader()
        if (cookie.isNotEmpty()) {
            val radioPlaylistId = "RDAMVM${entry.videoId}"
            val selectedVideoId = entry.videoId
            youTubeMusicService.fetchMixTracks(cookie, radioPlaylistId, object : YouTubeMusicService.MixTracksCallback {
                override fun onSuccess(tracks: List<YouTubeMusicService.TrackResult>) {
                    if (!isAdded || tracks.isEmpty()) return
                    val radioList = mutableListOf(clickedTrack)
                    for (t in tracks) { if (t.videoId != selectedVideoId) radioList.add(t) }

                    findSongPlayerFragment()?.let { sp ->
                        if (sp.isAdded) {
                            val qd = extractQueueData(radioList)
                            if (qd.ids.isNotEmpty()) sp.externalReplaceQueue(qd.ids, qd.titles, qd.artists, qd.durations, qd.images, 0, true)
                        }
                    }

                    val radioStoreTracks = mutableListOf<RadioHistoryStore.RadioTrack>()
                    radioStoreTracks.add(RadioHistoryStore.RadioTrack(
                        selectedVideoId,
                        clickedTrack.title.ifEmpty { "Tema" },
                        clickedTrack.subtitle ?: "",
                        clickedTrack.thumbnailUrl ?: ""
                    ))
                    for (t in tracks) {
                        if (t.videoId.isNullOrEmpty() || t.videoId == selectedVideoId) continue
                        radioStoreTracks.add(RadioHistoryStore.RadioTrack(t.videoId, t.title ?: "", t.subtitle ?: "", t.thumbnailUrl ?: ""))
                    }
                    RadioHistoryStore.saveRadio(requireContext(), radioPlaylistId, clickedTrack.title.ifEmpty { "Tema" }, clickedTrack.thumbnailUrl ?: "", radioStoreTracks)
                }
                override fun onError(error: String) {}
            })
        }
    }

    private fun openPlaylistDetailFromPrincipal(playlistId: String, playlistName: String?, thumbnailUrl: String?) {
        if (!isAdded || parentFragmentManager.isStateSaved) return

        val prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
        val accessToken = prefs.getString("stream_last_youtube_access_token", "") ?: ""

        val detailFragment = PlaylistDetailFragment.newInstance(
            playlistId,
            if (playlistName.isNullOrEmpty()) "Playlist" else playlistName,
            "",
            thumbnailUrl ?: "",
            accessToken
        )

        (activity as? MainActivity)?.let {
            it.showModuleLoadingOverlay()
            it.hideTopAppBarForPlaylistDetail()
        }

        val existing = parentFragmentManager.findFragmentByTag("playlist_detail")
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .apply { if (existing != null && existing.isAdded) remove(existing) }
            .add(R.id.fragmentContainer, detailFragment, "playlist_detail")
            .addToBackStack("playlist_detail")
            .commit()
    }

    // ========== Play in Background (global mini-player handles UI) ==========

    private fun playTrackInBackground(tracks: List<YouTubeMusicService.TrackResult>, startIndex: Int) {
        if (!isAdded || tracks.isEmpty()) return
        val data = extractQueueData(tracks)
        if (data.ids.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, data.ids.size - 1)

        cachedSongPlayer = null
        lastCachedSongPlayerTime = 0

        val rawPlayer = parentFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
        val existingPlayer = rawPlayer as? SongPlayerFragment

        if (existingPlayer != null && existingPlayer.isAdded) {
            existingPlayer.externalSetReturnTargetTag("module_principal")
            existingPlayer.externalReplaceQueue(data.ids, data.titles, data.artists, data.durations, data.images, safeIndex, true)
        } else {
            val playerFragment = SongPlayerFragment.newInstance(data.ids, data.titles, data.artists, data.durations, data.images, safeIndex, true)
            playerFragment.externalSetReturnTargetTag("module_principal")
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.playerContainer, playerFragment, TAG_SONG_PLAYER)
                .hide(playerFragment)
                .runOnCommit {
                    cachedSongPlayer = null
                    lastCachedSongPlayerTime = 0
                }
                .commitAllowingStateLoss()
        }
    }

    // ========== EQ Icons ==========

    private fun refreshShortcutEqIcons() {
        val vp = vpShortcuts ?: return
        val internalRv = vp.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until internalRv.childCount) {
            val pageView = internalRv.getChildAt(i)
            if (pageView is RecyclerView) pageView.adapter?.notifyDataSetChanged()
        }
    }

    private fun getCurrentPlayingVideoId(): String {
        return findSongPlayerFragment()?.takeIf { it.isAdded }?.externalGetCurrentVideoId() ?: ""
    }

    // ========== Helpers ==========

    private fun findSongPlayerFragment(): SongPlayerFragment? {
        val now = System.currentTimeMillis()
        if (lastCachedSongPlayerTime > 0 && now - lastCachedSongPlayerTime < 100L) return cachedSongPlayer
        val fragment = parentFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
        cachedSongPlayer = fragment as? SongPlayerFragment
        lastCachedSongPlayerTime = now
        return cachedSongPlayer
    }

    private fun resolvePlaylistGridUrls(playlistId: String): List<String> {
        if (playlistId.isEmpty() || !isAdded) return emptyList()
        playlistGridUrlsCache[playlistId]?.let { return it }

        val cachePrefs = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)

        val gridRaw = cachePrefs.getString("playlist_grid_urls_$playlistId", "") ?: ""
        if (gridRaw.isNotEmpty()) {
            val result = gridRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (result.size >= 4) { playlistGridUrlsCache[playlistId] = result; return result }
        }

        val tracksRaw = cachePrefs.getString("playlist_tracks_data_$playlistId", "") ?: ""
        if (tracksRaw.isNotEmpty()) {
            try {
                val arr = JSONArray(tracksRaw)
                val urls = mutableListOf<String>()
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    if (urls.size >= 4) break
                    val imgUrl = arr.optJSONObject(i)?.optString("imageUrl", "")?.trim() ?: ""
                    if (imgUrl.isNotEmpty() && seen.add(imgUrl)) urls.add(imgUrl)
                }
                if (urls.size >= 4) { playlistGridUrlsCache[playlistId] = urls; return urls }
            } catch (_: Exception) {}
        }

        val dbUrls = PlayCountStore.getPlaylistTrackImages(requireContext(), playlistId, 4)
        if (dbUrls != null) { playlistGridUrlsCache[playlistId] = dbUrls; return dbUrls }
        return emptyList()
    }

    private fun getCookieHeader(): String {
        if (!isAdded) return ""
        val prefs = requireContext().getSharedPreferences(PREFS_PLAYER_STATE, Activity.MODE_PRIVATE)
        return (prefs.getString(PREF_LAST_YOUTUBE_WEB_COOKIE, "") ?: "").trim()
    }

    private fun showSongPlayerWithEnterAnimation(player: SongPlayerFragment) {
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .show(player)
            .runOnCommit { player.externalAnimateEnterSlide() }
            .commit()
    }

    private fun addSongPlayerWithEnterAnimation(player: SongPlayerFragment) {
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.playerContainer, player, TAG_SONG_PLAYER)
            .runOnCommit { player.externalAnimateEnterSlide() }
            .commit()
    }

    // ========== Cache Mixes ==========

    private fun cacheMixes(generic: List<YouTubeMusicService.MixResult>, personal: List<YouTubeMusicService.MixResult>) {
        if (!isAdded) return
        try {
            val arr = JSONArray()
            for (mix in generic) {
                arr.put(JSONObject().apply {
                    put("playlistId", mix.playlistId)
                    put("title", mix.title)
                    put("subtitle", mix.subtitle)
                    put("thumbnailUrl", mix.thumbnailUrl)
                })
            }
            val arrPers = JSONArray()
            for (mix in personal) {
                arrPers.put(JSONObject().apply {
                    put("playlistId", mix.playlistId)
                    put("title", mix.title)
                    put("subtitle", mix.subtitle)
                    put("thumbnailUrl", mix.thumbnailUrl)
                })
            }
            requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString(CACHE_KEY_HOME_MIXES, arr.toString())
                .putString("home_personal_mixes_data", arrPers.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadCachedMixes() {
        if (!isAdded) return
        try {
            val cache = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val raw = cache.getString(CACHE_KEY_HOME_MIXES, "") ?: ""
            if (raw.isNotEmpty()) {
                val arr = JSONArray(raw)
                mixResults.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    mixResults.add(YouTubeMusicService.MixResult(
                        obj.optString("playlistId", ""),
                        obj.optString("title", ""),
                        obj.optString("subtitle", ""),
                        obj.optString("thumbnailUrl", "")
                    ))
                }
                rvMixes?.adapter?.notifyDataSetChanged()
                val empty = mixResults.isEmpty()
                tvMixesEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
                rvMixes?.visibility = if (empty) View.GONE else View.VISIBLE
            }

            val rawPers = cache.getString("home_personal_mixes_data", "") ?: ""
            if (rawPers.isNotEmpty()) {
                val arr = JSONArray(rawPers)
                personalMixResults.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    personalMixResults.add(YouTubeMusicService.MixResult(
                        obj.optString("playlistId", ""),
                        obj.optString("title", ""),
                        obj.optString("subtitle", ""),
                        obj.optString("thumbnailUrl", "")
                    ))
                }
                rvPersonalMixes?.adapter?.notifyDataSetChanged()
                val personalEmpty = personalMixResults.isEmpty()
                tvPersonalMixesLabel?.visibility = if (personalEmpty) View.GONE else View.VISIBLE
                rvPersonalMixes?.visibility = if (personalEmpty) View.GONE else View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    // ========== Cache Covers ==========

    private fun cacheCovers(tracks: List<YouTubeMusicService.TrackResult>) {
        if (!isAdded || tracks.isEmpty()) return
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(JSONObject().apply {
                    put("videoId", t.videoId ?: "")
                    put("title", t.title ?: "")
                    put("subtitle", t.subtitle ?: "")
                    put("thumbnailUrl", t.thumbnailUrl ?: "")
                })
            }
            requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("home_covers_data", arr.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadCachedCovers() {
        if (!isAdded) return
        if (coversResults.isNotEmpty()) return
        try {
            val cache = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val raw = cache.getString("home_covers_data", "") ?: ""
            if (raw.isEmpty()) return
            val arr = JSONArray(raw)
            coversResults.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val videoId = obj.optString("videoId", "")
                val title = obj.optString("title", "")
                if (videoId.isEmpty() || title.isEmpty()) continue
                coversResults.add(YouTubeMusicService.TrackResult(
                    "video",
                    videoId,
                    title,
                    obj.optString("subtitle", ""),
                    obj.optString("thumbnailUrl", "")
                ))
            }
            if (coversResults.isNotEmpty()) updateCoversUi()
        } catch (_: Exception) {}
    }

    // ========== Adapters ==========

    private inner class ShortcutsPagerAdapter : RecyclerView.Adapter<ShortcutsPagerAdapter.PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
            }
            return PageVH(rv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val start = position * SHORTCUTS_PER_PAGE
            val end = Math.min(start + SHORTCUTS_PER_PAGE, shortcutEntries.size)
            val pageItems = if (start < shortcutEntries.size) shortcutEntries.subList(start, end) else emptyList()
            holder.bind(pageItems)
        }

        override fun getItemCount(): Int {
            if (shortcutEntries.isEmpty()) return 1
            return Math.ceil(shortcutEntries.size / SHORTCUTS_PER_PAGE.toFloat().toDouble()).toInt()
        }

        inner class PageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rv = itemView as RecyclerView
            fun bind(items: List<PlayCountStore.PlayCountEntry>) {
                rv.layoutManager = GridLayoutManager(rv.context, 3)
                rv.adapter = ShortcutCellAdapter(items)
            }
        }
    }

    private inner class ShortcutCellAdapter(private val items: List<PlayCountStore.PlayCountEntry>) :
        RecyclerView.Adapter<ShortcutCellAdapter.CellVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_cell, parent, false)
            return CellVH(v)
        }

        override fun onBindViewHolder(holder: CellVH, position: Int) {
            val entry = items[position]
            holder.tvTitle.text = entry.title

            val isPlaylist = !entry.playlistId.isNullOrEmpty() && entry.videoId == entry.playlistId
            val currentTag = (holder.ivThumb.getTag(R.id.tag_artwork_signature) as? String) ?: ""

            if (isPlaylist && isAdded) {
                val gridUrls = resolvePlaylistGridUrls(entry.playlistId)
                if (gridUrls.size >= 4) {
                    val signature = "${entry.playlistId}_${gridUrls[0]}"
                    if (signature != currentTag) {
                        holder.ivThumb.setTag(R.id.tag_artwork_signature, signature)
                        val density = holder.itemView.context.resources.displayMetrics.density
                        val sizePx = (120 * density).toInt()
                        PlaylistGridArtLoader.load(holder.ivThumb, gridUrls, sizePx)
                    }
                } else {
                    val fallbackUrl = gridUrls.firstOrNull() ?: entry.imageUrl
                    if (!fallbackUrl.isNullOrEmpty() && fallbackUrl != currentTag) {
                        holder.ivThumb.setTag(R.id.tag_artwork_signature, fallbackUrl)
                        try { Glide.with(this@PrincipalFragment).load(fallbackUrl).placeholder(R.color.surface_high).transform(SHARED_YT_CROP, SHARED_CENTER_CROP).into(holder.ivThumb) } catch (_: Exception) {}
                    }
                }
            } else if (!entry.imageUrl.isNullOrEmpty() && isAdded) {
                if (entry.imageUrl != currentTag) {
                    holder.ivThumb.setTag(R.id.tag_artwork_signature, entry.imageUrl)
                    try { Glide.with(this@PrincipalFragment).load(entry.imageUrl).placeholder(R.color.surface_high).transform(SHARED_YT_CROP, SHARED_CENTER_CROP).into(holder.ivThumb) } catch (_: Exception) {}
                }
            }

            val nowPlaying = getCurrentPlayingVideoId()
            val isThisPlaying = !isPlaylist && nowPlaying.isNotEmpty() && (entry.videoId == nowPlaying || entry.videoId == currentlyPlayingShortcutVideoId)
            val sp = findSongPlayerFragment()
            val actuallyPlaying = sp != null && sp.isAdded && sp.externalIsPlaying()

            if (isThisPlaying && actuallyPlaying) {
                holder.ivPlay.visibility = View.GONE
                holder.eqView.visibility = View.VISIBLE
                holder.eqView.setAnimating(true)
            } else {
                holder.eqView.setAnimating(false)
                holder.eqView.visibility = View.GONE
                holder.ivPlay.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener { onShortcutClicked(entry) }
        }

        override fun getItemCount() = items.size

        inner class CellVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivThumb: ImageView = itemView.findViewById(R.id.ivShortcutThumb)
            val tvTitle: TextView = itemView.findViewById(R.id.tvShortcutTitle)
            val ivPlay: ImageView = itemView.findViewById(R.id.ivShortcutPlay)
            val eqView: AnimatedEqualizerView = itemView.findViewById(R.id.eqShortcut)
        }
    }

    private inner class MixesAdapter(private val items: List<YouTubeMusicService.MixResult>) :
        RecyclerView.Adapter<MixesAdapter.MixVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MixVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mix_card, parent, false)
            return MixVH(v)
        }

        override fun onBindViewHolder(holder: MixVH, position: Int) {
            val mix = items[position]
            holder.tvTitle.text = mix.title
            holder.tvSubtitle.text = mix.subtitle
            if (mix.thumbnailUrl.isNotEmpty() && isAdded) {
                try { Glide.with(this@PrincipalFragment).load(mix.thumbnailUrl).placeholder(R.color.surface_high).transform(SHARED_CENTER_CROP, SHARED_ROUNDED_16).into(holder.ivThumb) } catch (_: Exception) {}
            }
            holder.itemView.setOnClickListener { onMixClicked(mix) }
        }

        override fun getItemCount() = items.size

        inner class MixVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivThumb: ImageView = itemView.findViewById(R.id.ivMixThumb)
            val tvTitle: TextView = itemView.findViewById(R.id.tvMixTitle)
            val tvSubtitle: TextView = itemView.findViewById(R.id.tvMixSubtitle)
        }
    }

    private inner class CoversPagerAdapter : RecyclerView.Adapter<CoversPagerAdapter.PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val page = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return PageVH(page)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = holder.itemView as LinearLayout
            page.removeAllViews()
            val start = position * COVERS_PER_PAGE
            val end = Math.min(start + COVERS_PER_PAGE, coversResults.size)
            for (i in start until end) {
                val track = coversResults[i]
                val row = LayoutInflater.from(page.context).inflate(R.layout.item_cover_track_row, page, false)
                val ivArt: ImageView = row.findViewById(R.id.ivCoverArt)
                val tvTitle: TextView = row.findViewById(R.id.tvCoverTitle)
                val tvSubtitle: TextView = row.findViewById(R.id.tvCoverSubtitle)

                tvTitle.text = track.title
                tvSubtitle.text = track.subtitle
                if (!track.thumbnailUrl.isNullOrEmpty() && isAdded) {
                    try { Glide.with(this@PrincipalFragment).load(track.thumbnailUrl).placeholder(R.color.surface_high).transform(SHARED_YT_CROP, SHARED_CENTER_CROP).into(ivArt) } catch (_: Exception) {}
                }
                row.setOnClickListener { onCoversTrackClicked(track) }
                page.addView(row)
            }
        }

        override fun getItemCount(): Int {
            return if (coversResults.isEmpty()) 0 else Math.ceil(coversResults.size / COVERS_PER_PAGE.toFloat().toDouble()).toInt()
        }

        inner class PageVH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    private fun onCoversTrackClicked(track: YouTubeMusicService.TrackResult) {
        if (!isAdded || track.videoId.isNullOrEmpty()) return
        val startIndex = coversResults.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        playTrackList(coversResults, startIndex)
    }
}
