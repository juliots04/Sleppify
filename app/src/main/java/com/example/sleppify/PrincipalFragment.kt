package com.example.sleppify

import android.app.Activity
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
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
        private const val TAG = "PrincipalFragment"
        private const val TAG_SONG_PLAYER = "song_player"
        private const val PREFS_PLAYER_STATE = "player_state"
        private const val PREF_LAST_YOUTUBE_WEB_COOKIE = "stream_last_youtube_web_cookie"
        private const val PREFS_STREAMING_CACHE = "streaming_cache"
        private const val PREFS_RADIO_CACHE = "sleppify_radio_ui_cache"
        private const val SHORTCUTS_PER_PAGE = 9
        private const val SHORTCUTS_MAX_PAGES = 3
        // Partial-bind payload: refresh only the play/equalizer icon without reloading artwork or
        // re-creating click listeners (avoids the flash/jank when tapping a shortcut).
        private const val PAYLOAD_EQ = "eq"
        private const val COVERS_PER_PAGE = 4
        private const val NETWORK_REFRESH_THROTTLE_MS = 180_000L
        private const val COVERS_CACHE_TTL_MS = 12L * 60 * 60 * 1000 // 12 hours
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
    private var ivShortcutsProfilePhoto: ShapeableImageView? = null

    // Backdrop
    private var ivHomeBackdrop: ImageView? = null
    private var lastBackdropUrl: String? = null
    private var vStatusBarOverlay: View? = null

    // Content views
    private var vpShortcuts: ViewPager2? = null
    private var tabDotsShortcuts: TabLayout? = null
    private var llCoversHeader: View? = null
    private var tvCoversLabel: TextView? = null
    private var btnCoversPlayAll: TextView? = null
    private var vpCovers: ViewPager2? = null
    private var tabDotsCovers: TabLayout? = null

    // Radios section
    private var llRadiosHeader: View? = null
    private var rvRadios: RecyclerView? = null
    private val radioEntries = mutableListOf<RadioHistoryStore.RadioEntry>()
    private val radioDominantColorCache = HashMap<String, Int>()
    private val radioArtistTextCache = HashMap<String, String>()
    private val radioSideUrlsCache = HashMap<String, Pair<String, String>>()

    // Playlists recientes section
    private var llPlaylistsHeader: View? = null
    private var rvPlaylists: RecyclerView? = null
    private val playlistEntries = mutableListOf<PlayCountStore.PlayCountEntry>()

    // State
    private val handler = Handler(Looper.getMainLooper())
    // Off-main-thread worker for disk/DB/JSON reads (PlayCountStore, prefs scans, grid art urls)
    // that were previously done on the UI thread and made entering the module janky.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cachedSongPlayer: SongPlayerFragment? = null
    private var lastCachedSongPlayerTime = 0L
    private lateinit var youTubeMusicService: YouTubeMusicService

    // Throttling
    private var lastCoversNetworkFetchTimeMs = 0L
    private var lastShortcutsFetchTimeMs = 0L
    private val playlistGridUrlsCache = HashMap<String, List<String>>()

    // Currently playing shortcut
    private var currentlyPlayingShortcutVideoId = ""
    private var lastEqRefreshVideoId = ""

    // Data
    private val shortcutEntries = mutableListOf<PlayCountStore.PlayCountEntry>()
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

        ivHomeBackdrop = view.findViewById(R.id.ivHomeBackdrop)
        applyBackdropBlur()

        vpShortcuts = view.findViewById(R.id.vpShortcuts)
        tabDotsShortcuts = view.findViewById(R.id.tabDotsShortcuts)
        llCoversHeader = view.findViewById(R.id.llCoversHeader)
        tvCoversLabel = view.findViewById(R.id.tvCoversLabel)
        btnCoversPlayAll = view.findViewById(R.id.btnCoversPlayAll)
        vpCovers = view.findViewById(R.id.vpCovers)
        tabDotsCovers = view.findViewById(R.id.tabDotsCovers)

        youTubeMusicService = YouTubeMusicService()

        // Status bar overlay: set height to status bar size and fade on scroll
        vStatusBarOverlay = view.findViewById(R.id.vStatusBarOverlay)
        vStatusBarOverlay?.let { overlay ->
            ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.layoutParams = v.layoutParams.apply { height = statusBarHeight }
                insets
            }
            overlay.requestApplyInsets()
        }
        val nsv = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.nsvPrincipalContent)
        nsv?.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val shortcutsHeader = view.findViewById<View>(R.id.llShortcutsHeader)
            if (shortcutsHeader != null && vStatusBarOverlay != null) {
                val target = shortcutsHeader.top.toFloat()
                val fraction = if (target <= 0f) 1f else (scrollY / target).coerceIn(0f, 1f)
                vStatusBarOverlay?.alpha = fraction
            }
        })

        setupShortcuts()
        setupCovers()
        loadCachedCovers()

        llRadiosHeader = view.findViewById(R.id.llRadiosHeader)
        rvRadios = view.findViewById(R.id.rvRadios)
        preloadRadioCaches()
        setupRadios()

        llPlaylistsHeader = view.findViewById(R.id.llPlaylistsHeader)
        rvPlaylists = view.findViewById(R.id.rvPlaylists)
        setupPlaylists()
    }

    override fun onResume() {
        super.onResume()
        PlaybackEventBus.addListener(this)
        if (isHidden) return
        vpShortcuts?.setCurrentItem(0, false)
        vpCovers?.setCurrentItem(0, false)
        refreshFragHeaderProfilePhoto()
        refreshShortcuts()
        refreshCovers()
        refreshRadios()
        refreshPlaylists()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackEventBus.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        playlistGridUrlsCache.clear()
        vpShortcuts = null
        tabDotsShortcuts = null
        ivHomeBackdrop = null
        lastBackdropUrl = null
        vStatusBarOverlay = null
        llFragBrandHeader = null
        tvFragBrandTitle = null
        btnFragHeaderSearch = null
        btnFragSignIn = null
        btnFragProfilePhoto = null
        ivShortcutsProfilePhoto = null
        llCoversHeader = null
        tvCoversLabel = null
        btnCoversPlayAll = null
        vpCovers = null
        tabDotsCovers = null
        llRadiosHeader = null
        rvRadios = null
        llPlaylistsHeader = null
        rvPlaylists = null
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.findViewById<View>(R.id.nsvPrincipalContent)?.let { nsv ->
                nsv.post { nsv.scrollTo(0, 0) }
            }
            // Always reset to first page when re-entering
            vpShortcuts?.setCurrentItem(0, false)
            vpCovers?.setCurrentItem(0, false)
            handler.postDelayed({
                if (isAdded && !isHidden) refreshShortcuts()
            }, 200)
            refreshFragHeaderProfilePhoto()
            refreshCovers()
            refreshRadios()
            refreshPlaylists()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdownNow()
    }

    // ========== Brand Header ==========

    private fun setupFragBrandHeader(root: View) {
        llFragBrandHeader = root.findViewById(R.id.llFragBrandHeader)
        tvFragBrandTitle = root.findViewById(R.id.tvFragBrandTitle)
        btnFragHeaderSearch = root.findViewById(R.id.btnFragHeaderSearch)
        btnFragSignIn = root.findViewById(R.id.btnFragSignIn)
        btnFragProfilePhoto = root.findViewById(R.id.btnFragProfilePhoto)
        ivShortcutsProfilePhoto = root.findViewById(R.id.ivShortcutsProfilePhoto)

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
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }
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
                    lastShortcutsFetchTimeMs = 0L
                    lastCoversNetworkFetchTimeMs = 0L
                    refreshShortcuts()
                    refreshCovers()
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
        val prefs = requireContext().getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
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
                ivShortcutsProfilePhoto?.let { iv ->
                    Glide.with(this)
                        .load(photoUri)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(iv)
                }
            } else {
                btnFragProfilePhoto?.setImageResource(android.R.drawable.ic_menu_myplaces)
                ivShortcutsProfilePhoto?.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        } else {
            btnFragProfilePhoto?.visibility = View.GONE
            btnFragProfilePhoto?.setImageDrawable(null)
            btnFragSignIn?.visibility = View.VISIBLE
            ivShortcutsProfilePhoto?.setImageResource(android.R.drawable.ic_menu_myplaces)
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
        vpShortcuts ?: return

        // Dynamically set height to exactly match a 3x3 square grid (ScreenWidth)
        val screenWidth = resources.displayMetrics.widthPixels
        vpShortcuts?.layoutParams?.height = screenWidth
        vpShortcuts?.requestLayout()

        vpShortcuts?.adapter = ShortcutsPagerAdapter()
        // Keep only the current page + one neighbour resident instead of all 3, so entering the
        // module doesn't inflate/bind 27 cells up front. The first swipe is still instant.
        vpShortcuts?.offscreenPageLimit = 1
        // Don't persist page position across config changes / re-entries
        vpShortcuts?.isSaveEnabled = false
        // Increase touch slop so slight vertical finger drift doesn't cancel the horizontal swipe
        val shortcutsRv = vpShortcuts?.getChildAt(0) as? RecyclerView
        improveHorizontalScrollDetection(shortcutsRv)
        TabLayoutMediator(tabDotsShortcuts!!, vpShortcuts!!) { _, _ -> }.attach()
    }

    private fun improveHorizontalScrollDetection(recyclerView: RecyclerView?) {
        if (recyclerView == null) return
        try {
            val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            touchSlopField.isAccessible = true
            val currentSlop = touchSlopField.getInt(recyclerView)
            // Reduce the internal RV slop so the pager starts scrolling sooner
            touchSlopField.setInt(recyclerView, (currentSlop * 0.5).toInt())

            // Prevent the parent NestedScrollView from stealing a predominantly-horizontal swipe.
            // Claim the gesture early (half the normal slop) so slight vertical drift while
            // swiping diagonally doesn't cancel the page change.
            val halfSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop / 2
            recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                private var startX = 0f
                private var startY = 0f
                private var locked = false

                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = e.x
                            startY = e.y
                            locked = false
                            // Eagerly request parent to not intercept on DOWN so we get MOVE events
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = Math.abs(e.x - startX)
                            val dy = Math.abs(e.y - startY)
                            if (!locked) {
                                if (dx > halfSlop && dx > dy * 0.6f) {
                                    // Horizontal intent — keep the lock
                                    locked = true
                                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                                } else if (dy > halfSlop && dy > dx) {
                                    // Vertical intent — release to parent
                                    locked = false
                                    rv.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            locked = false
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        } catch (_: Exception) { }
    }

    private fun refreshShortcuts() {
        val now = System.currentTimeMillis()
        if (now - lastShortcutsFetchTimeMs < 15_000L && shortcutEntries.isNotEmpty()) return
        if (!isAdded) return
        lastShortcutsFetchTimeMs = now
        val appContext = requireContext().applicationContext

        // All of this (PlayCountStore/PlaybackHistory/Favorites reads + JSON parsing + grid-art
        // resolution) is disk/DB work that used to run on the UI thread and stalled module entry.
        bgExecutor.execute {
            val totalNeeded = SHORTCUTS_PER_PAGE * SHORTCUTS_MAX_PAGES

            // YT Music Speed Dial logic: recency-first, with the single most-played playlist pinned at pos 0.
            val topPlaylist = PlayCountStore.getTopPlaylists(appContext, 1)
                .firstOrNull { it.playlistId != LocalFilesStore.PLAYLIST_ID }

            // Composite score: frequency weighted by recency decay.
            // score = count / (1 + daysSinceLastPlayed / 3)
            val nowMs = System.currentTimeMillis()
            val allEntries = PlayCountStore.getAllEntries(appContext)
                .sortedByDescending { entry ->
                    val daysSince = (nowMs - entry.lastPlayedAtMs) / 86_400_000.0
                    entry.count / (1.0 + daysSince / 3.0)
                }

            val seenIds = HashSet<String>()
            val merged = mutableListOf<PlayCountStore.PlayCountEntry>()

            // Pin the most-played playlist at position 0
            if (topPlaylist != null) {
                merged.add(topPlaylist)
                seenIds.add(topPlaylist.videoId)
            }

            // Fill remaining slots with composite score (recency + frequency)
            for (t in allEntries) {
                if (merged.size >= totalNeeded) break
                if (t.videoId in seenIds) continue
                seenIds.add(t.videoId)
                merged.add(t)
            }

            val top = ArrayList(merged)
            if (top.size < totalNeeded) fillShortcutsFromHistory(appContext, top, totalNeeded)

            // Warm the grid-art cache off the UI thread so cell binds don't touch disk.
            val gridCache = HashMap<String, List<String>>()
            for (e in top) {
                val pid = e.playlistId
                if (!pid.isNullOrEmpty() && e.videoId == pid) {
                    gridCache[pid] = computePlaylistGridUrls(appContext, pid)
                }
            }

            // Sync shortcuts to Firebase if user is signed in
            if (top.isNotEmpty()) {
                try {
                    CloudSyncManager.getInstance(appContext).syncShortcutsToCloud(top)
                } catch (e: Exception) {
                    Log.w(TAG, "syncShortcutsToCloud failed", e)
                }
            }

            handler.post {
                if (!isAdded) return@post
                shortcutEntries.clear()
                shortcutEntries.addAll(top)
                playlistGridUrlsCache.putAll(gridCache)
                vpShortcuts?.adapter?.notifyDataSetChanged()
                tabDotsShortcuts?.let {
                    val pageCount = Math.max(1, Math.ceil(shortcutEntries.size / SHORTCUTS_PER_PAGE.toFloat().toDouble()).toInt())
                    it.visibility = if (pageCount > 1) View.VISIBLE else View.GONE
                }
                updateBackdropImage()
            }
        }
    }

    private fun fillShortcutsFromHistory(appContext: Context, existing: MutableList<PlayCountStore.PlayCountEntry>, totalNeeded: Int) {
        val existingIds = existing.map { it.videoId }.toHashSet()

        val snapshot = PlaybackHistoryStore.load(appContext)
        for (track in snapshot.queue) {
            if (existing.size >= totalNeeded) break
            if (track.videoId in existingIds) continue
            existingIds.add(track.videoId)
            existing.add(PlayCountStore.PlayCountEntry(track.videoId, track.title, track.artist, track.imageUrl, "", "", 0, 0L))
        }

        try {
            val favs = FavoritesPlaylistStore.loadFavorites(appContext)
            for (fav in favs) {
                if (existing.size >= totalNeeded) break
                if (fav.videoId in existingIds) continue
                existingIds.add(fav.videoId)
                existing.add(PlayCountStore.PlayCountEntry(fav.videoId, fav.title, fav.artist, fav.imageUrl, FavoritesPlaylistStore.PLAYLIST_ID, "Favoritos", 0, 0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
    }


    // ========== Covers ==========

    private fun setupCovers() {
        vpCovers ?: return
        vpCovers?.adapter = CoversPagerAdapter()
        vpCovers?.offscreenPageLimit = 1
        val coversRv = vpCovers?.getChildAt(0) as? RecyclerView
        improveHorizontalScrollDetection(coversRv)
        tabDotsCovers?.let { TabLayoutMediator(it, vpCovers!!) { _, _ -> }.attach() }
        btnCoversPlayAll?.setOnClickListener {
            if (coversResults.isNotEmpty()) playTrackList(coversResults, 0)
        }
    }

    private fun refreshCovers() {
        val now = System.currentTimeMillis()
        // Use persisted timestamp for 12h TTL check (survives process death)
        if (coversResults.isNotEmpty()) {
            val cachedAt = if (lastCoversNetworkFetchTimeMs > 0L) lastCoversNetworkFetchTimeMs
            else requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                .getLong("home_covers_updated_at", 0L)
            if (now - cachedAt < COVERS_CACHE_TTL_MS) return
        }

        val cookie = getCookieHeader()
        if (cookie.isEmpty()) return
        if (!isAdded) return
        val appContext = requireContext().applicationContext

        // The title-collection scan iterates EVERY cached playlist's track JSON — heavy disk +
        // parsing that must not run on the UI thread. Compute the seed titles off-main, then kick
        // off the network request back on the UI thread.
        bgExecutor.execute {
            val topTitles = PlayCountStore.getTopEntries(appContext, 10)
                .mapNotNull { it.title.takeIf { t -> t.isNotEmpty() } }
                .toMutableList()

            val extraTitles = mutableListOf<String>()
            try {
                val cachePrefs = appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Unexpected error", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }

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
            if (selected.isEmpty()) return@execute

            handler.post {
                if (!isAdded) return@post
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
        }
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

        val clickedTrack = YouTubeMusicService.TrackResult("video", entry.videoId, entry.title, entry.artist, entry.imageUrl)
        playTrackWithRadio(clickedTrack)
    }

    private fun playTrackWithRadio(track: YouTubeMusicService.TrackResult) {
        if (!isAdded || track.videoId.isNullOrEmpty()) return

        currentlyPlayingShortcutVideoId = track.videoId
        refreshShortcutEqIcons()

        // 1. Play track immediately and open player
        playTrackList(listOf(track), 0)

        // 2. Fetch radio/mix in background
        val cookie = getCookieHeader()
        if (cookie.isNotEmpty()) {
            val radioPlaylistId = "RDAMVM${track.videoId}"
            val selectedVideoId = track.videoId
            youTubeMusicService.fetchMixTracks(cookie, radioPlaylistId, object : YouTubeMusicService.MixTracksCallback {
                override fun onSuccess(tracks: List<YouTubeMusicService.TrackResult>) {
                    if (!isAdded || tracks.isEmpty()) return
                    val radioList = mutableListOf(track)
                    for (t in tracks) { if (t.videoId != selectedVideoId) radioList.add(t) }

                    findSongPlayerFragment()?.let { sp ->
                        if (sp.isAdded) {
                            val qd = extractQueueData(radioList)
                            if (qd.ids.isNotEmpty()) {
                                sp.externalReplaceQueue(qd.ids, qd.titles, qd.artists, qd.durations, qd.images, 0, true)
                            }
                        }
                    }

                    val radioStoreTracks = mutableListOf<RadioHistoryStore.RadioTrack>()
                    radioStoreTracks.add(RadioHistoryStore.RadioTrack(
                        selectedVideoId,
                        track.title.ifEmpty { "Tema" },
                        track.subtitle ?: "",
                        track.thumbnailUrl ?: ""
                    ))
                    for (t in tracks) {
                        if (t.videoId.isNullOrEmpty() || t.videoId == selectedVideoId) continue
                        radioStoreTracks.add(RadioHistoryStore.RadioTrack(t.videoId, t.title ?: "", t.subtitle ?: "", t.thumbnailUrl ?: ""))
                    }
                    RadioHistoryStore.saveRadio(requireContext(), radioPlaylistId, track.title.ifEmpty { "Tema" }, track.thumbnailUrl ?: "", radioStoreTracks)
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
            if (pageView is RecyclerView) {
                val a = pageView.adapter ?: continue
                // Partial bind (PAYLOAD_EQ): only the play/eq icon flips — no artwork reload or
                // listener churn — so tapping a shortcut no longer flashes/feels heavy.
                if (a.itemCount > 0) a.notifyItemRangeChanged(0, a.itemCount, PAYLOAD_EQ)
            }
        }
    }

    private fun getCurrentPlayingVideoId(): String {
        return findSongPlayerFragment()?.takeIf { it.isAdded }?.externalGetCurrentVideoId() ?: ""
    }

    // ========== Backdrop ==========

    private fun applyBackdropBlur() {
        val backdrop = ivHomeBackdrop ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun updateBackdropImage() {
        val backdrop = ivHomeBackdrop ?: return
        if (!isAdded || shortcutEntries.isEmpty()) return
        val firstEntry = shortcutEntries[0]
        if (LocalFilesStore.isLocalVideoId(firstEntry.videoId)) {
            val key = "localart:${firstEntry.videoId}"
            if (key == lastBackdropUrl) return
            lastBackdropUrl = key
            LocalArtworkResolver.loadBytes(requireContext(), firstEntry.videoId) { bytes ->
                if (!isAdded || bytes == null) return@loadBytes
                try {
                    Glide.with(this)
                        .load(bytes)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .signature(com.bumptech.glide.signature.ObjectKey(key))
                        .override(320, 320)
                        .transform(SHARED_CENTER_CROP)
                        .into(backdrop)
                } catch (e: Exception) {
                    Log.w(TAG, "Backdrop load error", e)
                }
            }
            return
        }
        val url = firstEntry.imageUrl
        if (url.isNullOrEmpty() || url == lastBackdropUrl) return
        lastBackdropUrl = url
        try {
            Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(320, 320)
                .transform(SHARED_CENTER_CROP)
                .into(backdrop)
        } catch (e: Exception) {
            Log.w(TAG, "Backdrop load error", e)
        }
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
        // Fallback for a playlist not pre-warmed by refreshShortcuts. The common path hits the
        // cache above (warmed off the UI thread); this only runs for a newly-appeared id and is
        // then cached so subsequent binds are free.
        val result = computePlaylistGridUrls(requireContext().applicationContext, playlistId)
        playlistGridUrlsCache[playlistId] = result
        return result
    }

    /** Pure disk/DB resolution of up to 4 grid-art urls for a playlist. Safe to call off the UI
     *  thread (no fragment state, no shared-cache access). */
    private fun computePlaylistGridUrls(appContext: Context, playlistId: String): List<String> {
        if (playlistId.isEmpty()) return emptyList()
        val cachePrefs = appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)

        val gridRaw = cachePrefs.getString("playlist_grid_urls_$playlistId", "") ?: ""
        if (gridRaw.isNotEmpty()) {
            val result = gridRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (result.size >= 4) return result
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
                if (urls.size >= 4) return urls
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }
        }

        return PlayCountStore.getPlaylistTrackImages(appContext, playlistId, 4)
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
                .putLong("home_covers_updated_at", System.currentTimeMillis())
                .apply()
            // Sync covers to Firebase for persistence across installs
            CloudSyncManager.getInstance(requireContext()).syncCoversToCloud(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
    }

    private fun loadCachedCovers() {
        if (!isAdded) return
        if (coversResults.isNotEmpty()) return
        try {
            val cache = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val raw = cache.getString("home_covers_data", "") ?: ""
            if (raw.isEmpty()) {
                // Try loading from Firebase if local cache is empty
                CloudSyncManager.getInstance(requireContext()).fetchCloudCovers(requireContext()) { restoredRaw ->
                    if (restoredRaw.isNotEmpty() && isAdded && coversResults.isEmpty()) {
                        parseCoversCacheString(restoredRaw)
                        if (coversResults.isNotEmpty()) {
                            handler.post { if (isAdded) updateCoversUi() }
                        }
                    }
                }
                return
            }
            lastCoversNetworkFetchTimeMs = cache.getLong("home_covers_updated_at", 0L)
            parseCoversCacheString(raw)
            if (coversResults.isNotEmpty()) updateCoversUi()
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
    }

    private fun parseCoversCacheString(raw: String) {
        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing covers cache", e)
        }
    }

    // ========== Radios ==========

    private fun setupRadios() {
        rvRadios?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvRadios)
        rvRadios?.adapter = RadioCarouselAdapter()
    }

    private fun refreshRadios() {
        if (!isAdded) return
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            val radios = RadioHistoryStore.getRadios(appContext).take(10)
            handler.post {
                if (!isAdded) return@post
                radioEntries.clear()
                radioEntries.addAll(radios)
                val hasRadios = radioEntries.isNotEmpty()
                llRadiosHeader?.visibility = if (hasRadios) View.VISIBLE else View.GONE
                rvRadios?.visibility = if (hasRadios) View.VISIBLE else View.GONE
                (rvRadios?.adapter as? RadioCarouselAdapter)?.notifyDataSetChanged()
                // Pre-fetch side images so they're in Glide disk cache before scroll
                preloadRadioImages(radios)
            }
        }
    }

    private fun preloadRadioImages(radios: List<RadioHistoryStore.RadioEntry>) {
        if (!isAdded || radios.isEmpty()) return
        try {
            val glide = Glide.with(this)
            for (radio in radios) {
                if (radio.songThumbnail.isNotEmpty()) {
                    glide.load(radio.songThumbnail).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().preload()
                }
                val sides = radioSideUrlsCache[radio.radioPlaylistId] ?: continue
                if (sides.first.isNotEmpty()) {
                    glide.load(sides.first).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().preload()
                }
                if (sides.second.isNotEmpty()) {
                    glide.load(sides.second).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().preload()
                }
            }
        } catch (_: Exception) {}
    }

    private fun onRadioClicked(radio: RadioHistoryStore.RadioEntry) {
        if (!isAdded) return
        openPlaylistDetailFromPrincipal(
            radio.radioPlaylistId,
            "${radio.songTitle} Radio",
            radio.songThumbnail
        )
    }

    private inner class RadioCarouselAdapter : RecyclerView.Adapter<RadioCarouselAdapter.RadioVH>() {

        override fun getItemCount() = radioEntries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RadioVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_radio_carousel, parent, false)
            // Narrow cards, almost 1:1 aspect for stacked circle illusion
            val cardWidth = (parent.context.resources.displayMetrics.widthPixels * 0.46).toInt()
            view.layoutParams = ViewGroup.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RadioVH(view)
        }

        override fun onBindViewHolder(holder: RadioVH, position: Int) {
            val radio = radioEntries[position]
            holder.bind(radio)
        }

        inner class RadioVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardRadio: View = itemView.findViewById(R.id.cardRadio)
            private val vBg: View = itemView.findViewById(R.id.vRadioBg)
            private val ivCenter: ShapeableImageView = itemView.findViewById(R.id.ivRadioCenter)
            private val ivLeft: ShapeableImageView = itemView.findViewById(R.id.ivRadioLeft)
            private val ivRight: ShapeableImageView = itemView.findViewById(R.id.ivRadioRight)
            private val tvName: TextView = itemView.findViewById(R.id.tvRadioName)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvRadioTitle)

            fun bind(radio: RadioHistoryStore.RadioEntry) {
                val cachedText = radioArtistTextCache[radio.radioPlaylistId]
                val text = if (cachedText != null) cachedText else {
                    val uniqueArtists = radio.tracks.map { it.artist }.filter { it.isNotEmpty() }.distinct()
                    val random = java.util.Random(radio.radioPlaylistId.hashCode().toLong())
                    val shuffledArtists = uniqueArtists.shuffled(random)
                    val top3 = shuffledArtists.take(3)
                    val computed = when (top3.size) {
                        0 -> "Con varios artistas"
                        1 -> "Con ${top3[0]} y más"
                        2 -> "Con ${top3[0]}, ${top3[1]} y más"
                        else -> "Con ${top3[0]}, ${top3[1]}, ${top3[2]} y más"
                    }
                    persistArtistText(radio.radioPlaylistId, computed)
                    computed
                }
                tvName.text = text
                tvTitle.text = radio.songTitle
                val clickListener = View.OnClickListener { onRadioClicked(radio) }
                itemView.setOnClickListener(clickListener)
                cardRadio.setOnClickListener(clickListener)

                // Center = seed song thumbnail
                val centerUrl = radio.songThumbnail
                if (centerUrl.isNotEmpty() && isAdded) {
                    try {
                        Glide.with(this@PrincipalFragment)
                            .load(centerUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .placeholder(R.color.surface_high)
                            .into(ivCenter)

                        // Use cached dominant color or extract it
                        val cachedColor = radioDominantColorCache[centerUrl]
                        if (cachedColor != null) {
                            vBg.background = GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(cachedColor, darkenColor(cachedColor, 0.6f))
                            ).apply { cornerRadius = 0f }
                        } else {
                            Glide.with(this@PrincipalFragment)
                                .asBitmap()
                                .load(centerUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(64, 64)
                                .centerCrop()
                                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                                        Palette.from(resource).generate { palette ->
                                            val dominant = palette?.getDarkMutedColor(
                                                palette.getMutedColor(0xFF333333.toInt())
                                            ) ?: 0xFF333333.toInt()
                                            persistDominantColor(radio.radioPlaylistId, centerUrl, dominant)
                                            vBg.background = GradientDrawable(
                                                GradientDrawable.Orientation.TOP_BOTTOM,
                                                intArrayOf(dominant, darkenColor(dominant, 0.6f))
                                            ).apply { cornerRadius = 0f }
                                        }
                                    }
                                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                                })
                        }
                    } catch (_: Exception) {}
                }

                // Left and Right = 2 deterministic tracks from the radio (excluding seed)
                val cachedSides = radioSideUrlsCache[radio.radioPlaylistId]?.takeIf { it.first.isNotEmpty() }
                val sides: Pair<String, String> = if (cachedSides != null) {
                    cachedSides
                } else {
                    val otherTracks = radio.tracks.filter { it.thumbnailUrl.isNotEmpty() && it.thumbnailUrl != centerUrl }
                    if (otherTracks.size >= 2) {
                        val seed = radio.radioPlaylistId.hashCode().toLong()
                        val seeded = otherTracks.sortedBy { it.videoId.hashCode().toLong() xor seed }
                        Pair(seeded[0].thumbnailUrl, seeded.getOrNull(1)?.thumbnailUrl ?: "")
                    } else if (isAdded) {
                        val gridUrls = resolvePlaylistGridUrls(radio.radioPlaylistId).filter { it != centerUrl }
                        if (gridUrls.size >= 2) Pair(gridUrls[0], gridUrls[1])
                        else if (gridUrls.size == 1) Pair(gridUrls[0], "")
                        else Pair("", "")
                    } else Pair("", "")
                }
                if (sides.first.isNotEmpty() && cachedSides == null) {
                    persistSideUrls(radio.radioPlaylistId, sides.first, sides.second)
                }
                val (leftUrl, rightUrl) = sides

                if (leftUrl.isNotEmpty() && isAdded) {
                    try { Glide.with(this@PrincipalFragment).load(leftUrl).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().placeholder(R.color.surface_high).into(ivLeft) } catch (_: Exception) {}
                } else {
                    ivLeft.setImageResource(R.color.surface_high)
                }
                if (rightUrl.isNotEmpty() && isAdded) {
                    try { Glide.with(this@PrincipalFragment).load(rightUrl).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().placeholder(R.color.surface_high).into(ivRight) } catch (_: Exception) {}
                } else {
                    ivRight.setImageResource(R.color.surface_high)
                }
            }
        }
    }

    // ========== Radio UI Cache Persistence ==========

    private fun persistDominantColor(radioPlaylistId: String, url: String, color: Int) {
        radioDominantColorCache[url] = color
        val ctx = context?.applicationContext ?: return
        bgExecutor.execute {
            ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putInt("color_$radioPlaylistId", color)
                .putString("colorurl_$radioPlaylistId", url)
                .apply()
        }
    }

    private fun persistSideUrls(radioPlaylistId: String, left: String, right: String) {
        radioSideUrlsCache[radioPlaylistId] = Pair(left, right)
        val ctx = context?.applicationContext ?: return
        bgExecutor.execute {
            ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("sides_$radioPlaylistId", "$left\n$right")
                .apply()
        }
    }

    private fun persistArtistText(radioPlaylistId: String, text: String) {
        radioArtistTextCache[radioPlaylistId] = text
        val ctx = context?.applicationContext ?: return
        bgExecutor.execute {
            ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("artists_$radioPlaylistId", text)
                .apply()
        }
    }

    private fun preloadRadioCaches() {
        val ctx = context?.applicationContext ?: return
        bgExecutor.execute {
            try {
                val prefs = ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                val all = prefs.all
                val colors = HashMap<String, Int>()
                val sides = HashMap<String, Pair<String, String>>()
                val artists = HashMap<String, String>()
                for ((key, value) in all) {
                    when {
                        key.startsWith("color_") && !key.startsWith("colorurl_") && value is Int -> {
                            val id = key.removePrefix("color_")
                            val url = all["colorurl_$id"] as? String
                            if (!url.isNullOrEmpty()) colors[url] = value
                        }
                        key.startsWith("sides_") && value is String -> {
                            val id = key.removePrefix("sides_")
                            val parts = value.split("\n", limit = 2)
                            if (parts.size == 2) sides[id] = Pair(parts[0], parts[1])
                        }
                        key.startsWith("artists_") && value is String -> {
                            val id = key.removePrefix("artists_")
                            artists[id] = value
                        }
                    }
                }

                handler.post {
                    if (!isAdded) return@post
                    radioDominantColorCache.putAll(colors)
                    radioSideUrlsCache.putAll(sides)
                    radioArtistTextCache.putAll(artists)
                }
            } catch (e: Exception) {
                Log.w(TAG, "preloadRadioCaches failed", e)
            }
        }
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = ((color shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ========== Playlists recientes ==========

    private fun setupPlaylists() {
        rvPlaylists?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvPlaylists)
        rvPlaylists?.adapter = PlaylistCarouselAdapter()
    }

    private fun refreshPlaylists() {
        if (!isAdded) return
        val playlists = PlayCountStore.getTopPlaylists(requireContext(), 10)
        playlistEntries.clear()
        playlistEntries.addAll(playlists)
        val hasPlaylists = playlistEntries.isNotEmpty()
        llPlaylistsHeader?.visibility = if (hasPlaylists) View.VISIBLE else View.GONE
        rvPlaylists?.visibility = if (hasPlaylists) View.VISIBLE else View.GONE
        (rvPlaylists?.adapter as? PlaylistCarouselAdapter)?.notifyDataSetChanged()
    }

    private fun isLikedPlaylistId(playlistId: String): Boolean {
        val id = playlistId.trim()
        return id == YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
                || id == "LL" || id == "LM" || id.startsWith("VLLL")
    }

    private fun onPlaylistClicked(entry: PlayCountStore.PlayCountEntry) {
        if (!isAdded) return
        openPlaylistDetailFromPrincipal(
            entry.playlistId,
            entry.playlistName,
            entry.imageUrl
        )
    }

    private inner class PlaylistCarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_PLAYLIST = 0
        private val TYPE_RADIO = 1

        override fun getItemCount() = playlistEntries.size

        override fun getItemViewType(position: Int): Int {
            return if (playlistEntries[position].playlistId.startsWith("RD")) TYPE_RADIO else TYPE_PLAYLIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val screenWidth = resources.displayMetrics.widthPixels
            val width = (screenWidth * 0.46f).toInt()
            if (viewType == TYPE_RADIO) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_radio_carousel, parent, false)
                view.layoutParams.width = width
                return RadioPlaylistVH(view)
            }
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_carousel, parent, false)
            view.layoutParams.width = width
            return PlaylistVH(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = playlistEntries[position]
            when (holder) {
                is PlaylistVH -> holder.bind(entry)
                is RadioPlaylistVH -> holder.bind(entry)
            }
        }

        inner class PlaylistVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardPlaylist: View = itemView.findViewById(R.id.cardPlaylist)
            private val ivCover: ShapeableImageView = itemView.findViewById(R.id.ivPlaylistCover)
            private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
            private val vLikedBg: View = itemView.findViewById(R.id.vPlaylistLikedBg)
            private val ivLikedIcon: ImageView = itemView.findViewById(R.id.ivPlaylistLikedIcon)

            fun bind(entry: PlayCountStore.PlayCountEntry) {
                val isLiked = isLikedPlaylistId(entry.playlistId)
                tvName.text = if (isLiked) "Música que te gustó" else entry.playlistName
                val clickListener = View.OnClickListener { onPlaylistClicked(entry) }
                itemView.setOnClickListener(clickListener)
                cardPlaylist.setOnClickListener(clickListener)

                if (isLiked) {
                    ivCover.setImageDrawable(null)
                    ivCover.visibility = View.INVISIBLE
                    vLikedBg.visibility = View.VISIBLE
                    ivLikedIcon.visibility = View.VISIBLE
                    // Scale icon to ~40% of cover (30% padding each side)
                    ivLikedIcon.post {
                        val w = ivLikedIcon.width
                        if (w > 0) {
                            val pad = (w * 0.30f).toInt()
                            ivLikedIcon.setPadding(pad, pad, pad, pad)
                        }
                    }
                    return
                }

                // Reset liked views for recycled ViewHolder
                ivCover.visibility = View.VISIBLE
                vLikedBg.visibility = View.GONE
                ivLikedIcon.visibility = View.GONE

                if (isAdded) {
                    val gridUrls = resolvePlaylistGridUrls(entry.playlistId)
                    if (gridUrls.size >= 4) {
                        val density = itemView.context.resources.displayMetrics.density
                        val sizePx = (180 * density).toInt()
                        PlaylistGridArtLoader.load(ivCover, gridUrls, sizePx)
                    } else {
                        val fallbackUrl = gridUrls.firstOrNull() ?: entry.imageUrl
                        if (fallbackUrl.isNotEmpty()) {
                            try {
                                Glide.with(this@PrincipalFragment)
                                    .load(fallbackUrl)
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .centerCrop()
                                    .placeholder(R.color.surface_high)
                                    .into(ivCover)
                            } catch (_: Exception) {}
                        } else {
                            ivCover.setImageResource(R.color.surface_high)
                        }
                    }
                }
            }
        }

        inner class RadioPlaylistVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardRadio: View = itemView.findViewById(R.id.cardRadio)
            private val vBg: View = itemView.findViewById(R.id.vRadioBg)
            private val ivCenter: ShapeableImageView = itemView.findViewById(R.id.ivRadioCenter)
            private val ivLeft: ShapeableImageView = itemView.findViewById(R.id.ivRadioLeft)
            private val ivRight: ShapeableImageView = itemView.findViewById(R.id.ivRadioRight)
            private val tvName: TextView = itemView.findViewById(R.id.tvRadioName)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvRadioTitle)

            fun bind(entry: PlayCountStore.PlayCountEntry) {
                tvName.text = "Radio de ${entry.title}"
                tvTitle.text = entry.title
                val clickListener = View.OnClickListener { onPlaylistClicked(entry) }
                itemView.setOnClickListener(clickListener)
                cardRadio.setOnClickListener(clickListener)

                // Use already-loaded radioEntries instead of reading from disk per bind
                val radio = radioEntries.find { it.radioPlaylistId == entry.playlistId }
                val centerUrl = radio?.songThumbnail?.takeIf { it.isNotEmpty() } ?: entry.imageUrl
                val cachedSides = radioSideUrlsCache[entry.playlistId]?.takeIf { it.first.isNotEmpty() }
                val sides: Pair<String, String> = if (cachedSides != null) {
                    cachedSides
                } else {
                    // 1. Try from in-memory radio tracks
                    val radioTracks = radio?.tracks?.filter { it.thumbnailUrl.isNotEmpty() && it.thumbnailUrl != centerUrl } ?: emptyList()
                    if (radioTracks.size >= 2) {
                        val seed = entry.playlistId.hashCode().toLong()
                        val seeded = radioTracks.sortedBy { it.videoId.hashCode().toLong() xor seed }
                        Pair(seeded[0].thumbnailUrl, seeded.getOrNull(1)?.thumbnailUrl ?: "")
                    } else if (isAdded) {
                        // 2. Fallback: streaming cache (playlist_tracks_data / grid_urls)
                        val gridUrls = resolvePlaylistGridUrls(entry.playlistId).filter { it != centerUrl }
                        if (gridUrls.size >= 2) Pair(gridUrls[0], gridUrls[1])
                        else if (gridUrls.size == 1) Pair(gridUrls[0], "")
                        else Pair("", "")
                    } else {
                        Pair("", "")
                    }
                }
                if (sides.first.isNotEmpty() && cachedSides == null) {
                    persistSideUrls(entry.playlistId, sides.first, sides.second)
                }
                val (leftUrl, rightUrl) = sides

                if (centerUrl.isNotEmpty() && isAdded) {
                    try {
                        Glide.with(this@PrincipalFragment)
                            .load(centerUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .placeholder(R.color.surface_high)
                            .into(ivCenter)

                        val cachedColor = radioDominantColorCache[centerUrl]
                        if (cachedColor != null) {
                            vBg.background = GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(cachedColor, darkenColor(cachedColor, 0.6f))
                            ).apply { cornerRadius = 0f }
                        } else {
                            Glide.with(this@PrincipalFragment)
                                .asBitmap()
                                .load(centerUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(64, 64)
                                .centerCrop()
                                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                                        Palette.from(resource).generate { palette ->
                                            val dominant = palette?.getDarkMutedColor(
                                                palette.getMutedColor(0xFF333333.toInt())
                                            ) ?: 0xFF333333.toInt()
                                            persistDominantColor(entry.playlistId, centerUrl, dominant)
                                            vBg.background = GradientDrawable(
                                                GradientDrawable.Orientation.TOP_BOTTOM,
                                                intArrayOf(dominant, darkenColor(dominant, 0.6f))
                                            ).apply { cornerRadius = 0f }
                                        }
                                    }
                                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                                })
                        }

                        if (leftUrl.isNotEmpty()) {
                            Glide.with(this@PrincipalFragment).load(leftUrl).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().placeholder(R.color.surface_high).into(ivLeft)
                        } else {
                            ivLeft.setImageResource(R.color.surface_high)
                        }
                        if (rightUrl.isNotEmpty()) {
                            Glide.with(this@PrincipalFragment).load(rightUrl).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().placeholder(R.color.surface_high).into(ivRight)
                        } else {
                            ivRight.setImageResource(R.color.surface_high)
                        }
                    } catch (_: Exception) {}
                } else {
                    ivCenter.setImageResource(R.color.surface_high)
                    ivLeft.setImageResource(R.color.surface_high)
                    ivRight.setImageResource(R.color.surface_high)
                }
            }
        }
    }

    // ========== Adapters ==========

    private inner class ShortcutsPagerAdapter : RecyclerView.Adapter<ShortcutsPagerAdapter.PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                clipToPadding = false
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, 0)
            }
            return PageVH(rv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val start = position * SHORTCUTS_PER_PAGE
            val end = Math.min(start + SHORTCUTS_PER_PAGE, shortcutEntries.size)
            val pageItems = if (start < shortcutEntries.size) ArrayList(shortcutEntries.subList(start, end)) else emptyList()
            holder.bind(pageItems)
        }

        override fun getItemCount(): Int {
            if (shortcutEntries.isEmpty()) return 1
            return Math.ceil(shortcutEntries.size / SHORTCUTS_PER_PAGE.toFloat().toDouble()).toInt()
        }

        inner class PageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rv = itemView as RecyclerView
            fun bind(items: List<PlayCountStore.PlayCountEntry>) {
                // Non-scrolling grid: the 9 cells fit the fixed page height, so the inner
                // RecyclerView must NOT claim drag gestures. If it does, it fights the parent
                // ViewPager2 and makes swiping between pages hard. With scrolling disabled,
                // horizontal drags reach the pager and vertical drags reach the NestedScrollView.
                rv.layoutManager = object : GridLayoutManager(rv.context, 3) {
                    override fun canScrollVertically(): Boolean = false
                    override fun canScrollHorizontally(): Boolean = false
                }
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

            val isPlaylist = !entry.playlistId.isNullOrEmpty() && entry.videoId == entry.playlistId
            val isLiked = isPlaylist && isLikedPlaylistId(entry.playlistId)

            holder.tvTitle.text = if (isLiked) "Música que te gustó" else entry.title

            // Liked playlist: gradient + icon, no artwork
            if (isLiked) {
                LocalArtworkResolver.detach(holder.ivThumb)
                holder.ivThumb.setImageDrawable(null)
                holder.vLikedBg.visibility = View.VISIBLE
                holder.ivLikedIcon.visibility = View.VISIBLE
                holder.ivThumb.setTag(R.id.tag_artwork_signature, "__liked__")
            } else {
                holder.vLikedBg.visibility = View.GONE
                holder.ivLikedIcon.visibility = View.GONE

                val currentTag = (holder.ivThumb.getTag(R.id.tag_artwork_signature) as? String) ?: ""

                if (isPlaylist && isAdded) {
                    LocalArtworkResolver.detach(holder.ivThumb)
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
                } else if (LocalFilesStore.isLocalVideoId(entry.videoId) && isAdded) {
                    // Local track shortcut: render the file's own embedded cover.
                    val signature = "localart:${entry.videoId}"
                    if (signature != currentTag) {
                        holder.ivThumb.setTag(R.id.tag_artwork_signature, signature)
                        val density = holder.itemView.context.resources.displayMetrics.density
                        LocalArtworkResolver.loadInto(holder.ivThumb, entry.videoId, (120 * density).toInt())
                    }
                } else if (!entry.imageUrl.isNullOrEmpty() && isAdded) {
                    LocalArtworkResolver.detach(holder.ivThumb)
                    if (entry.imageUrl != currentTag) {
                        holder.ivThumb.setTag(R.id.tag_artwork_signature, entry.imageUrl)
                        try { Glide.with(this@PrincipalFragment).load(entry.imageUrl).placeholder(R.color.surface_high).transform(SHARED_YT_CROP, SHARED_CENTER_CROP).into(holder.ivThumb) } catch (_: Exception) {}
                    }
                }
            }

            bindEqState(holder, entry)

            holder.clCell.setOnClickListener { onShortcutClicked(entry) }
        }

        override fun onBindViewHolder(holder: CellVH, position: Int, payloads: MutableList<Any>) {
            // Lightweight path used by refreshShortcutEqIcons(): only flip the play/eq icon,
            // leaving artwork and click listeners untouched so nothing flashes on tap.
            if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_EQ)) {
                bindEqState(holder, items[position])
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        private fun bindEqState(holder: CellVH, entry: PlayCountStore.PlayCountEntry) {
            val isPlaylist = !entry.playlistId.isNullOrEmpty() && entry.videoId == entry.playlistId
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
        }

        override fun getItemCount() = items.size

        inner class CellVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            // The inner ConstraintLayout is clickable=true, so it consumes the touch — the click
            // listener MUST be set on it, not on the root itemView, or taps never fire.
            val clCell: View = itemView.findViewById(R.id.clShortcutCell)
            val ivThumb: ImageView = itemView.findViewById(R.id.ivShortcutThumb)
            val tvTitle: TextView = itemView.findViewById(R.id.tvShortcutTitle)
            val ivPlay: ImageView = itemView.findViewById(R.id.ivShortcutPlay)
            val eqView: AnimatedEqualizerView = itemView.findViewById(R.id.eqShortcut)
            val vLikedBg: View = itemView.findViewById(R.id.vShortcutLikedBg)
            val ivLikedIcon: ImageView = itemView.findViewById(R.id.ivShortcutLikedIcon)
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
        playTrackWithRadio(track)
    }
}
