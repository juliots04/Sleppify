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
        private const val TAG_SONG_PLAYER = AppConstants.TAG_SONG_PLAYER
        private const val PREFS_PLAYER_STATE = AppConstants.PREFS_PLAYER_STATE
        private const val PREF_LAST_YOUTUBE_WEB_COOKIE = AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE
        private const val PREFS_STREAMING_CACHE = AppConstants.PREFS_STREAMING_CACHE
        private const val PREFS_RADIO_CACHE = "sleppify_radio_ui_cache"
        // Bumped when the radio card design/selection logic changes so stale persisted subtitle
        // texts and side-url picks (computed with the old logic) are dropped once and recomputed.
        private const val RADIO_UI_VERSION = 2
        private const val SHORTCUTS_PER_PAGE = 9
        private const val SHORTCUTS_MAX_PAGES = 3
        // Partial-bind payload: refresh only the play/equalizer icon without reloading artwork or
        // re-creating click listeners (avoids the flash/jank when tapping a shortcut).
        private const val PAYLOAD_EQ = "eq"
        // RecyclerView.mTouchSlop is the same field for every instance — reflect it once, not per carousel.
        private var cachedTouchSlopField: java.lang.reflect.Field? = null
        private const val COVERS_PER_PAGE = 4
        private const val RECOMMENDED_LIMIT = 12
        // Cupo máximo de playlists de la biblioteca propia en "recomendadas" (el resto son
        // generadas por YT / la comunidad, que es lo que el usuario prefiere ver).
        private const val RECOMMENDED_OWN_QUOTA = 2
        private const val COVERS_CACHE_TTL_MS = 12L * 60 * 60 * 1000 // 12 hours
        // Last-resort: if no section produced content within this window on cold start, reveal the
        // module anyway so the loading spinner never hangs (e.g. brand-new account with no data).
        private const val COLD_START_REVEAL_SAFETY_MS = 2500L
        // Last-resort: dismiss any still-showing cold-start skeletons after this window even if a
        // section never loaded (e.g. covers when signed out), so a skeleton never pulses forever.
        private const val HOME_SKELETON_SAFETY_MS = 5000L
        // Re-entry throttle: below-the-fold carousels re-read disk/parse JSON on every module
        // re-entry even when nothing changed. Skip that work if refreshed within this window.
        private const val REENTRY_REFRESH_THROTTLE_MS = 15_000L
        private val SHARED_YT_CROP = YouTubeCropTransformation()
        private val SHARED_CENTER_CROP = CenterCrop()
        private val SHARED_ROUNDED_16 = RoundedCorners(16)
    }

    // Brand header views. The header is a single pinned view (fixed + floating at once): it sits
    // in its natural top position at rest and quick-returns via translationY while scrolled.
    private var llFragBrandHeader: View? = null
    private var tvFragBrandTitle: TextView? = null
    private var btnFragHeaderSearch: ImageView? = null
    private var btnFragSignIn: MaterialButton? = null
    private var btnFragProfilePhoto: ShapeableImageView? = null
    private var ivShortcutsProfilePhoto: ShapeableImageView? = null
    // Mutated on scroll: transparent at rest over the backdrop, solid while the header floats.
    private var headerBgDrawable: android.graphics.drawable.ColorDrawable? = null

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
    // Normalized (lowercase) names the user knows: followed library artists (minus unfollow
    // tombstones) + the artists they actually play. Used to bias the radio subtitle ("Con X, Y, Z
    // y más") and the side thumbnails toward familiar artists instead of unknown ones.
    private val knownArtistKeys = HashSet<String>()
    // Radio card width in px == the composite art render size. Computed once (used by
    // onCreateViewHolder and RadioArtComposer so the circle geometry maps 1:1 to the display).
    // Based on the SHORT screen axis so rotation/split-screen never stretches the squares.
    private val radioCardWidthPx: Int by lazy {
        val dm = resources.displayMetrics
        (minOf(dm.widthPixels, dm.heightPixels) * 0.46).toInt()
    }

    // Playlists recientes section
    private var llPlaylistsHeader: View? = null
    private var rvPlaylists: RecyclerView? = null
    private var llRecommendedHeader: View? = null
    private var rvRecommended: RecyclerView? = null
    private val recommendedEntries = mutableListOf<YouTubeMusicService.MixResult>()
    private var llRecapsHeader: View? = null
    private var rvRecaps: RecyclerView? = null
    private val recapEntries = mutableListOf<YouTubeMusicService.MixResult>()
    private var llNewFavesHeader: View? = null
    private var rvNewFaves: RecyclerView? = null
    private val newFavesEntries = mutableListOf<YouTubeMusicService.MixResult>()
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
    private var lastRadiosRefreshMs = 0L
    private var lastPlaylistsRefreshMs = 0L
    private var lastRecommendedFetchMs = 0L
    private val playlistGridUrlsCache = HashMap<String, List<String>>()

    /** Submit to [bgExecutor] without crashing if the fragment is tearing down and the executor
     *  was already shut down in onDestroy (late binds/persists could otherwise throw
     *  RejectedExecutionException). */
    private fun submitBg(task: () -> Unit) {
        try {
            bgExecutor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    // Cold-start reveal gate: on the very first content pass after the view is created, tell
    // MainActivity to fade out the loading overlay so the user never sees the empty/black first
    // frame. Reset per view lifecycle so a fresh cold start re-gates correctly.
    private var firstContentRevealDone = false

    // Cold-start skeleton placeholders (3x3 grid + carousel rows). Shown immediately on a cold
    // start so the home never renders empty, and hidden per-section as each section's data lands.
    // Their geometry matches the real content so swapping in the real views causes no layout jump;
    // the loading effect is the self-animating ShimmerFrameLayout each skeleton layout is wrapped in.
    private var llShortcutsSkeleton: View? = null
    private var llPlaylistsSkeleton: View? = null
    private var llCoversSkeleton: View? = null
    private var llRadiosSkeleton: View? = null
    private var llRecommendedSkeleton: View? = null
    private var llRecapsSkeleton: View? = null
    private var llNewFavesSkeleton: View? = null

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
            // The artist fed to the player/miniplayer queue must be ONLY the artist name
            // (no album/views residue) — nothing but the artist may show there.
            val meta = SongSubtitle.parse(t.subtitle, t.title, t.duration)
            data.ids.add(t.videoId)
            data.titles.add(t.title)
            data.artists.add(meta.artist)
            data.durations.add(if (meta.duration.isNotEmpty()) meta.duration else "--:--")
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
        // Defer the blur setup off the synchronous cold-start path. The backdrop carries no image
        // on the first frame, so applying the RenderEffect one frame later is visually identical.
        view.post { applyBackdropBlur() }

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
                // Use systemBars().top (same inset the brand headers pad with) so the overlay strip
                // and the header's status-bar strip line up pixel-for-pixel.
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                v.layoutParams = v.layoutParams.apply { height = statusBarHeight }
                insets
            }
            overlay.requestApplyInsets()
        }
        val nsv = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.nsvPrincipalContent)
        // Hoisted out of the scroll callback — this used to run findViewById on every scroll frame.
        val shortcutsHeader = view.findViewById<View>(R.id.llShortcutsHeader)
        nsv?.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val overlay = vStatusBarOverlay
            if (shortcutsHeader != null && overlay != null) {
                val target = shortcutsHeader.top.toFloat()
                overlay.alpha = if (target <= 0f) 1f else (scrollY / target).coerceIn(0f, 1f)
            }
            updateHeaderOnScroll(scrollY, scrollY - oldScrollY)
        })
        // Seed the header/background state once laid out (covers a restored scroll position).
        nsv?.post { if (isAdded && nsv != null) updateHeaderOnScroll(nsv.scrollY, 0) }

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

        llNewFavesHeader = view.findViewById(R.id.llNewFavesHeader)
        rvNewFaves = view.findViewById(R.id.rvNewFaves)
        setupNewFaves()
        loadCachedNewFaves()

        llRecommendedHeader = view.findViewById(R.id.llRecommendedHeader)
        rvRecommended = view.findViewById(R.id.rvRecommended)
        setupRecommended()
        loadCachedRecommended()

        llRecapsHeader = view.findViewById(R.id.llRecapsHeader)
        rvRecaps = view.findViewById(R.id.rvRecaps)
        setupRecaps()
        loadCachedRecaps()

        setupHomeSkeletons(view)

        // Safety-net: never leave the cold-start spinner up forever if content never arrives.
        handler.postDelayed({
            if (isAdded) revealAfterFirstContentPass(force = true)
        }, COLD_START_REVEAL_SAFETY_MS)

        // Safety-net: dismiss any skeleton whose section never produced data.
        armHomeSkeletonSafetyTimer()
    }

    override fun onResume() {
        super.onResume()
        PlaybackEventBus.addListener(this)
        if (isHidden) return
        // Re-arm the skeleton safety net paused in onPause (e.g. while the login webview
        // covered the activity) so covered time never consumes the skeleton budget invisibly.
        if (hasVisibleHomeSkeleton()) armHomeSkeletonSafetyTimer()
        vpShortcuts?.setCurrentItem(0, false)
        vpCovers?.setCurrentItem(0, false)
        // Defer the carousel rebuilds past the resume frame: the retained views already show
        // the previous content, so the return feels instant and the refreshes trickle in.
        handler.postDelayed({
            if (isAdded && !isHidden) {
                refreshFragHeaderProfilePhoto()
                refreshShortcuts()
                refreshCovers()
                refreshRadios()
                refreshPlaylists()
                refreshRecommended()
            }
        }, 150)
    }

    override fun onPause() {
        super.onPause()
        // Stop reacting to playback events while Principal is hidden/backgrounded: its only handler
        // refreshes this fragment's (now-invisible) EQ icons. onResume re-registers when shown again.
        PlaybackEventBus.removeListener(this)
        // Pause the skeleton safety timer: it used to keep counting behind the login webview and
        // dismissed all skeletons invisibly, which is why the post-login home showed none.
        handler.removeCallbacks(skeletonSafetyDismiss)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackEventBus.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        firstContentRevealDone = false
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
        headerBgDrawable = null
        cachedSongPlayer = null
        lastCachedSongPlayerTime = 0L
        llCoversHeader = null
        tvCoversLabel = null
        btnCoversPlayAll = null
        vpCovers = null
        tabDotsCovers = null
        llRadiosHeader = null
        rvRadios = null
        llPlaylistsHeader = null
        rvPlaylists = null
        llRecommendedHeader = null
        rvRecommended = null
        llRecapsHeader = null
        rvRecaps = null
        llNewFavesHeader = null
        rvNewFaves = null
        llShortcutsSkeleton = null
        llPlaylistsSkeleton = null
        llCoversSkeleton = null
        llRadiosSkeleton = null
        llRecommendedSkeleton = null
        llRecapsSkeleton = null
        llNewFavesSkeleton = null
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
            // Rebuilding all four carousels synchronously here is what made switching to
            // Principal feel slow — the switch frame paid for every adapter rebuild + Glide
            // bind at once. The kept-alive views still show the previous content, so defer
            // the refreshes past the module-switch frame and let them trickle in.
            handler.postDelayed({
                if (isAdded && !isHidden) refreshShortcuts()
            }, 200)
            handler.postDelayed({
                if (isAdded && !isHidden) {
                    refreshFragHeaderProfilePhoto()
                    refreshCovers()
                    refreshRadios()
                    refreshPlaylists()
                }
            }, 120)
        } else {
            // Al SALIR de Principal (cambio de módulo o entrar a un detalle — MainActivity oculta el
            // fragment con hide(), lo que dispara esto): deja todos los carruseles en el primer item
            // para que al volver empiecen desde el inicio, no donde se quedó el scroll.
            resetCarouselsToStart()
        }
    }

    /** Devuelve todos los carruseles horizontales + pagers + el scroll vertical al inicio. */
    private fun resetCarouselsToStart() {
        (rvRadios?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        (rvPlaylists?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        (rvRecommended?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        (rvRecaps?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        (rvNewFaves?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        // Pagers al primer item (los puntos siguen vía TabLayoutMediator).
        vpShortcuts?.setCurrentItem(0, false)
        vpCovers?.setCurrentItem(0, false)
        // Scroll vertical exterior arriba del todo.
        view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.nsvPrincipalContent)?.scrollTo(0, 0)
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

            // Background starts fully transparent (header rests over the backdrop) and turns solid
            // via updateHeaderOnScroll() as content scrolls underneath.
            headerBgDrawable = android.graphics.drawable.ColorDrawable(
                ContextCompat.getColor(requireContext(), R.color.surface_dark)
            ).apply { alpha = 0 }
            header.background = headerBgDrawable

            // The header is out of the scroll flow, so a spacer reserves its height at the top of
            // the content column. Sync it to the measured height (insets can change it).
            val spacer = root.findViewById<View>(R.id.vHeaderSpacer)
            header.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (h > 0 && spacer != null && spacer.layoutParams.height != h) {
                    spacer.layoutParams = spacer.layoutParams.apply { height = h }
                }
            }
        }

        styleBrandTitle(tvFragBrandTitle)

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

    // Loaded once and reused for both brand headers — the variable font parse is a synchronous
    // resource read that was happening twice on the cold-start frame.
    private var brandTypeface: Typeface? = null

    private fun styleBrandTitle(tv: TextView?) {
        tv ?: return
        tv.isAllCaps = true
        tv.letterSpacing = 0.08f
        try {
            if (brandTypeface == null) {
                brandTypeface = ResourcesCompat.getFont(requireContext(), R.font.manrope_variable)
            }
            brandTypeface?.let { tv.typeface = it }
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

    // ========== Pinned quick-return header ==========

    /**
     * Single pinned header that is fixed and floating at once (YT-Music style, one view). The
     * offset TRACKS the scroll delta 1:1 — scrolling down slides it up out of view, scrolling up
     * slides it back down into view, following the finger. Near the top an in-flow bound
     * (offset >= -scrollY) makes it behave exactly like an in-flow header: it settles into its
     * natural top position with NO show/hide animation or view swap — at scrollY 0 it simply IS
     * the resting header. Correct under programmatic scrollTo(0,0) jumps too (dy is huge, the
     * bound forces offset to 0 instantly).
     */
    private fun updateHeaderOnScroll(scrollY: Int, dy: Int) {
        val header = llFragBrandHeader ?: return
        val h = header.height
        if (h <= 0) return
        val hf = h.toFloat()

        var offset = header.translationY - dy
        offset = offset.coerceIn(-hf, 0f).coerceAtLeast(-scrollY.toFloat())
        header.translationY = offset

        // Transparent at rest (over the backdrop), solid while detached/floating. The ramp spans
        // the first header-height of scroll — while the header is still "attached" — so by the
        // time it can float over content it is already opaque, and scrolling back to the top
        // fades it back out to reveal the backdrop.
        headerBgDrawable?.alpha = ((scrollY / hf).coerceIn(0f, 1f) * 255f).toInt()
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
                listOfNotNull(btnFragProfilePhoto, ivShortcutsProfilePhoto).forEach { iv ->
                    Glide.with(this)
                        .load(photoUri)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(iv)
                }
            } else {
                // Sin foto aún (recién logueado, URL no cacheada): vacío, nunca un placeholder.
                btnFragProfilePhoto?.setImageDrawable(null)
                ivShortcutsProfilePhoto?.setImageDrawable(null)
            }
        } else {
            btnFragProfilePhoto?.visibility = View.GONE
            btnFragProfilePhoto?.setImageDrawable(null)
            btnFragSignIn?.visibility = View.VISIBLE
            ivShortcutsProfilePhoto?.setImageDrawable(null)
        }
    }

    // ========== PlaybackEventBus ==========

    override fun onPlaybackSnapshotUpdated() {
        // isHidden: a resumed-but-covered Principal has nothing visible to refresh.
        if (!isAdded || isHidden || activity == null) return
        activity?.runOnUiThread {
            val nowId = getCurrentPlayingVideoId()
            if (nowId != lastEqRefreshVideoId) {
                lastEqRefreshVideoId = nowId
                // Re-point the optimistic "tapped shortcut" id to the ACTUAL playing video. It was
                // set on tap and never cleared, so after the queue advanced the previously-tapped
                // shortcut kept animating its EQ bars forever. Syncing it here clears that.
                currentlyPlayingShortcutVideoId = nowId
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
            val touchSlopField = cachedTouchSlopField ?: RecyclerView::class.java
                .getDeclaredField("mTouchSlop").apply { isAccessible = true }
                .also { cachedTouchSlopField = it }
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

    private fun refreshShortcuts(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastShortcutsFetchTimeMs < REENTRY_REFRESH_THROTTLE_MS && shortcutEntries.isNotEmpty()) return
        if (!isAdded) return
        lastShortcutsFetchTimeMs = now
        val appContext = requireContext().applicationContext

        // All of this (PlayCountStore/PlaybackHistory/Favorites reads + JSON parsing + grid-art
        // resolution) is disk/DB work that used to run on the UI thread and stalled module entry.
        submitBg {
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
                if (shortcutEntries.isNotEmpty()) {
                    // Grid data landed: swap the skeleton for the real pager (same square height, no jump).
                    vpShortcuts?.visibility = View.VISIBLE
                    hideHomeSkeleton(llShortcutsSkeleton)
                } else if (llShortcutsSkeleton?.visibility != View.VISIBLE) {
                    // Warm empty state (skeleton already dismissed): keep the pager visible.
                    vpShortcuts?.visibility = View.VISIBLE
                }
                // Empty on fresh install is NOT terminal — cloud hydration lands seconds later,
                // so the skeleton keeps pulsing; the safety timer ends genuinely-empty accounts.
                revealAfterFirstContentPass()
            }
        }
    }

    /**
     * On the first content pass after view creation, ask MainActivity to reveal the module
     * (fade out the cold-start loading overlay). Idempotent and gated so it only fires once per
     * view lifecycle — normal navigations back to Principal keep the flag set and are unaffected.
     *
     * We only reveal once a section actually has content (or [force] fires from the safety-net).
     * On a cold start — especially a fresh install where the local caches are empty and the
     * covers depend on the network/cloud — the first pass can render nothing; revealing then
     * dropped the spinner onto an all-black home, which is exactly the "entro a Principal y sale
     * todo negro" bug. Waiting for real content means the user sees the spinner until the home is
     * ready; the safety-net guarantees we never hang on the spinner forever.
     */
    private fun revealAfterFirstContentPass(force: Boolean = false) {
        if (firstContentRevealDone) return
        if (!force && !hasAnyContent()) return
        firstContentRevealDone = true
        (activity as? MainActivity)?.revealModuleContent()
    }

    private fun hasAnyContent(): Boolean =
        shortcutEntries.isNotEmpty() || coversResults.isNotEmpty() ||
            radioEntries.isNotEmpty() || playlistEntries.isNotEmpty()

    // ========== Cold-start skeletons ==========

    /**
     * Show the cold-start skeleton for each section that has no data yet (grid + carousel rows),
     * matching their real heights so the real views swap in without a layout jump. On a warm
     * re-entry (data already present) nothing is shown. Because the home now has a visible loading
     * state, we lift the module spinner right away instead of waiting for the first real content
     * (which previously left an empty home on screen until a section happened to load).
     */
    private fun setupHomeSkeletons(root: View) {
        llShortcutsSkeleton = root.findViewById(R.id.llShortcutsSkeleton)
        llPlaylistsSkeleton = root.findViewById(R.id.llPlaylistsSkeleton)
        llCoversSkeleton = root.findViewById(R.id.llCoversSkeleton)
        llRadiosSkeleton = root.findViewById(R.id.llRadiosSkeleton)
        llRecommendedSkeleton = root.findViewById(R.id.llRecommendedSkeleton)
        llRecapsSkeleton = root.findViewById(R.id.llRecapsSkeleton)
        llNewFavesSkeleton = root.findViewById(R.id.llNewFavesSkeleton)

        // Grid skeleton is a square that must match vpShortcuts (whose height is set to screenWidth).
        val screenWidth = resources.displayMetrics.widthPixels
        llShortcutsSkeleton?.let { it.layoutParams = it.layoutParams.apply { height = screenWidth } }
        // The real grid content is top-aligned inside the square pager with 16dp page padding per
        // side, so its cells are (screenWidth - 32dp)/3 squares. Size the inner grid to that height
        // (top-aligned in the shimmer frame) so the skeleton cells are square like the real ones.
        (llShortcutsSkeleton as? ViewGroup)?.getChildAt(0)?.let { grid ->
            val gridHeight = screenWidth - (32 * resources.displayMetrics.density).toInt()
            grid.layoutParams = grid.layoutParams.apply { height = gridHeight }
        }
        // Carousel cards are 46% of screen width (== radioCardWidthPx), same as the real cards.
        sizeHomeSkeletonCards(llPlaylistsSkeleton)
        sizeHomeSkeletonCards(llRadiosSkeleton)
        sizeHomeSkeletonCards(llRecommendedSkeleton)
        sizeHomeSkeletonCards(llRecapsSkeleton)
        sizeHomeSkeletonCards(llNewFavesSkeleton)

        if (showHomeSkeletonsForEmptySections()) {
            // Double-post so the skeletons are laid out before the spinner fades to reveal them.
            root.post { root.post { if (isAdded) revealAfterFirstContentPass(force = true) } }
        }
    }

    /**
     * Shows the skeleton of every section that currently has no data and re-arms the safety
     * dismiss. Sections that already render content are untouched (no skeleton flash). Reused
     * by refreshAllContent() so the post-login / cloud-hydration repopulate has skeletons again
     * — before, nothing ever re-showed them after the initial onViewCreated pass.
     */
    private fun showHomeSkeletonsForEmptySections(): Boolean {
        var anyShown = false
        if (shortcutEntries.isEmpty()) {
            llShortcutsSkeleton?.visibility = View.VISIBLE
            vpShortcuts?.visibility = View.GONE
            anyShown = true
        }
        if (playlistEntries.isEmpty()) { llPlaylistsSkeleton?.visibility = View.VISIBLE; anyShown = true }
        if (coversResults.isEmpty()) { llCoversSkeleton?.visibility = View.VISIBLE; anyShown = true }
        if (radioEntries.isEmpty()) { llRadiosSkeleton?.visibility = View.VISIBLE; anyShown = true }
        if (recommendedEntries.isEmpty()) { llRecommendedSkeleton?.visibility = View.VISIBLE; anyShown = true }
        if (anyShown) armHomeSkeletonSafetyTimer()
        return anyShown
    }

    private val skeletonSafetyDismiss = Runnable { if (isAdded) dismissAllHomeSkeletons() }

    /**
     * (Re)arms the safety net that ends any skeleton whose section never produced data. Re-armed
     * on every refresh trigger (login result, cloud hydration) and paused in onPause so time
     * covered by another activity doesn't consume the budget invisibly.
     */
    private fun armHomeSkeletonSafetyTimer() {
        handler.removeCallbacks(skeletonSafetyDismiss)
        handler.postDelayed(skeletonSafetyDismiss, HOME_SKELETON_SAFETY_MS)
    }

    private fun hasVisibleHomeSkeleton(): Boolean =
        llShortcutsSkeleton?.visibility == View.VISIBLE ||
            llPlaylistsSkeleton?.visibility == View.VISIBLE ||
            llCoversSkeleton?.visibility == View.VISIBLE ||
            llRadiosSkeleton?.visibility == View.VISIBLE ||
            llRecommendedSkeleton?.visibility == View.VISIBLE ||
            llRecapsSkeleton?.visibility == View.VISIBLE ||
            llNewFavesSkeleton?.visibility == View.VISIBLE

    private fun sizeHomeSkeletonCards(row: View?) {
        row ?: return
        val size = radioCardWidthPx
        intArrayOf(R.id.skeletonCard1, R.id.skeletonCard2, R.id.skeletonCard3).forEach { id ->
            row.findViewById<View>(id)?.let { card ->
                card.layoutParams = card.layoutParams.apply { width = size; height = size }
            }
        }
    }

    /**
     * Every home carousel card is EXACTLY radioCardWidthPx wide (a perfect 1:1 square regardless
     * of screen shape). The item layouts no longer carry paddingHorizontal — the 6dp separation
     * lives here as layout margins so the square equals the item width pixel-for-pixel.
     */
    private fun applyCarouselCardLayout(view: View) {
        val margin = (6 * resources.displayMetrics.density).toInt()
        val lp = (view.layoutParams as? ViewGroup.MarginLayoutParams)
            ?: ViewGroup.MarginLayoutParams(radioCardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.width = radioCardWidthPx
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.marginStart = margin
        lp.marginEnd = margin
        view.layoutParams = lp
    }

    /**
     * Hide one section's skeleton once its data pass has completed. Instant (not animated); the
     * shimmer stops by itself when the view is GONE.
     */
    private fun hideHomeSkeleton(skeleton: View?) {
        skeleton ?: return
        if (skeleton.visibility == View.GONE) return
        skeleton.visibility = View.GONE
    }

    /** Safety-net: collapse any skeleton whose section never loaded (e.g. covers when signed out). */
    private fun dismissAllHomeSkeletons() {
        // The grid never legitimately stays empty long; reveal the (possibly empty) pager under it.
        if (llShortcutsSkeleton?.visibility == View.VISIBLE) vpShortcuts?.visibility = View.VISIBLE
        hideHomeSkeleton(llShortcutsSkeleton)
        hideHomeSkeleton(llPlaylistsSkeleton)
        hideHomeSkeleton(llCoversSkeleton)
        hideHomeSkeleton(llRadiosSkeleton)
        hideHomeSkeleton(llRecommendedSkeleton)
        hideHomeSkeleton(llRecapsSkeleton)
        hideHomeSkeleton(llNewFavesSkeleton)
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

    private fun refreshCovers(force: Boolean = false) {
        // Callable from async callbacks (e.g. requireAuth success) — guard before requireContext.
        if (!isAdded) return
        val now = System.currentTimeMillis()
        // Use persisted timestamp for 12h TTL check (survives process death). The persisted
        // timestamp answers "is the cache fresh?" even while the async cache read from
        // loadCachedCovers() hasn't landed yet — gating on coversResults.isNotEmpty() here used
        // to let a cold-start race trigger a redundant network fetch over a fresh cache.
        if (!force) {
            val cachedAt = if (lastCoversNetworkFetchTimeMs > 0L) lastCoversNetworkFetchTimeMs
            else requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                .getLong("home_covers_updated_at", 0L)
            if (now - cachedAt < COVERS_CACHE_TTL_MS) return
        }

        val cookie = getCookieHeader()
        if (cookie.isEmpty()) return
        val appContext = requireContext().applicationContext

        // The title-collection scan iterates EVERY cached playlist's track JSON — heavy disk +
        // parsing that must not run on the UI thread. Compute the seed titles off-main, then kick
        // off the network request back on the UI thread.
        submitBg {
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
            if (selected.isEmpty()) return@submitBg

            handler.post {
                if (!isAdded) return@post
                youTubeMusicService.fetchCoversAndRemixes(cookie, selected, object : YouTubeMusicService.CoversRemixesCallback {
                    override fun onSuccess(tracks: List<YouTubeMusicService.TrackResult>) {
                        if (!isAdded) return
                        lastCoversNetworkFetchTimeMs = System.currentTimeMillis()
                        // If the fetch returned the same tracks already on screen, refresh the
                        // timestamp silently — swapping equal content (reshuffled) made the section
                        // visibly "refresh itself" right after opening the app.
                        val newIds = tracks.mapTo(HashSet()) { it.videoId ?: "" }
                        val currentIds = coversResults.mapTo(HashSet()) { it.videoId ?: "" }
                        if (newIds == currentIds && coversResults.isNotEmpty()) {
                            requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                                .edit()
                                .putLong("home_covers_updated_at", lastCoversNetworkFetchTimeMs)
                                .apply()
                            return
                        }
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
        hideHomeSkeleton(llCoversSkeleton)
        revealAfterFirstContentPass()
    }

    // ========== Actions ==========


    // Home carousels (shortcuts grid, covers/remixes) play in the mini player only — the full
    // player stays closed and the user keeps browsing the home (search-style flow).
    private fun playTrackList(tracks: List<YouTubeMusicService.TrackResult>, startIndex: Int) {
        if (!isAdded || tracks.isEmpty()) return
        val data = extractQueueData(tracks)
        if (data.ids.isEmpty()) return
        SongPlayerLauncher.open(
            activity,
            data.ids, data.titles, data.artists, data.durations, data.images,
            startIndex,
            /* startPlaying = */ true,
            "module_principal",
            /* openPlayerUi = */ false
        )
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

        // 1. Play track immediately in the mini player
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
                    // Persist a clean artist, not the raw baked subtitle: this field resurfaces
                    // in radio rows, the local track index and the "Con X, Y..." radio labels.
                    radioStoreTracks.add(RadioHistoryStore.RadioTrack(
                        selectedVideoId,
                        track.title.ifEmpty { "Tema" },
                        SongSubtitle.artistOnly(track.subtitle, track.title),
                        track.thumbnailUrl ?: ""
                    ))
                    for (t in tracks) {
                        if (t.videoId.isNullOrEmpty() || t.videoId == selectedVideoId) continue
                        radioStoreTracks.add(RadioHistoryStore.RadioTrack(t.videoId, t.title ?: "", SongSubtitle.artistOnly(t.subtitle, t.title), t.thumbnailUrl ?: ""))
                    }
                    RadioHistoryStore.saveRadio(requireContext(), radioPlaylistId, track.title.ifEmpty { "Tema" }, track.thumbnailUrl ?: "", radioStoreTracks)
                }
                override fun onError(error: String) {}
            })
        }
    }

    private fun openPlaylistDetailFromPrincipal(
        playlistId: String,
        playlistName: String?,
        thumbnailUrl: String?,
        params: String = "",
        // ART_HINT_SINGLE for cards that always show ONE YT cover (recomendadas/Recaps + recap rows):
        // the detail then clones that exact cover instead of synthesizing a 2x2 / radio composite.
        artHint: String = ""
    ) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        // Delegate to the shared launcher: id normalization, radio-id/title canonicalization
        // (home radios arrive as "X Radio" — now stripped), overlay + top-bar hide, and the
        // remove-existing + add + addToBackStack transaction all live in one place now.
        PlaylistDetailLauncher.open(
            activity,
            parentFragmentManager,
            playlistId,
            if (playlistName.isNullOrEmpty()) "Playlist" else playlistName,
            ""/* subtitle: home never has one */,
            thumbnailUrl ?: "",
            params = params,
            artHint = artHint
        )
    }

    /**
     * True si el título corresponde a un "Recap" generado por YT Music (resumen periódico de
     * escucha). Los recaps traen SIEMPRE su propia portada única generada por YT, así que se
     * muestran como una sola imagen (nunca 2x2 ni radio) y se separan a la sección "Recaps".
     */
    private fun isRecapTitle(title: String?): Boolean {
        val t = title?.lowercase()?.trim() ?: return false
        return t.contains("recap") || t.contains("resumen de tu año") || t.contains("tu resumen")
    }

    /**
     * True para las mezclas personales de YT Music que van a "Lo nuevo y lo que más te gusta":
     * Replay ("Mix de tus canciones más escuchadas"), Nuevos lanzamientos, Archivo, Descubrir y
     * Supermix. Detección por título (ES + fallback EN); NO usa el prefijo RDTMAK genérico porque ese
     * también abarca los "My Mix 1-6" por género, que se quedan en recomendadas.
     */
    private fun isPersonalFavoriteMix(item: YouTubeMusicService.MixResult): Boolean {
        val t = item.title.lowercase().trim()
        // "Mi Supermix" / "Supermix" no siempre llevan "mix de…".
        if (t.contains("supermix")) return true
        // El resto son variantes "Mix de/para …" — exige "mix" + palabra clave para NO barrer
        // playlists de comunidad que casualmente contengan una de estas palabras.
        if (!t.contains("mix")) return false
        return t.contains("descubrir") || t.contains("discover")
            || t.contains("lanzamiento") || t.contains("novedades") || t.contains("new release")
            || t.contains("archivo") || t.contains("archive")
            || t.contains("temas escuchados") || t.contains("temas más escuchados") || t.contains("temas mas escuchados")
            || t.contains("canciones más escuchada") || t.contains("canciones mas escuchada")
            || t.contains("replay") || t.contains("más escuchad") || t.contains("mas escuchad")
    }

    /** Tarjeta sintética de "Música que te gustó": siempre presente como primer item de la nueva
     *  sección (es la playlist de likes de la app, no depende de que YT la incluya en el home). */
    private fun likedMusicCard() = YouTubeMusicService.MixResult(
        YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID, "Música que te gustó", "", "", ""
    )

    // ========== Playlists recomendadas (YT Music home feed) ==========

    private fun setupRecommended() {
        rvRecommended?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvRecommended)
        rvRecommended?.adapter = MixCardAdapter(recommendedEntries) { onRecommendedClicked(it) }
    }

    private fun refreshRecommended(force: Boolean = false) {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRecommendedFetchMs < REENTRY_REFRESH_THROTTLE_MS && recommendedEntries.isNotEmpty()) return
        val cookie = getCookieHeader()
        if (cookie.isEmpty()) return
        lastRecommendedFetchMs = now
        youTubeMusicService.fetchHomeBrowse(cookie, object : YouTubeMusicService.HomeBrowseCallback {
            override fun onSuccess(result: YouTubeMusicService.HomeBrowseResult) {
                if (!isAdded) return
                // El usuario quiere sobre todo listas generadas por YT / la comunidad y muy pocas
                // de su propia biblioteca. Clasificamos cada item por el título de su sección
                // (las secciones de biblioteca en el home de YT Music son "Escucha de nuevo",
                // "Tus playlists", "Tu biblioteca", etc.) y limitamos las propias a OWN_QUOTA.
                val seen = HashSet<String>()
                val ownItems = mutableListOf<YouTubeMusicService.MixResult>()
                val otherItems = mutableListOf<YouTubeMusicService.MixResult>()
                val recaps = mutableListOf<YouTubeMusicService.MixResult>()
                val newFaves = mutableListOf<YouTubeMusicService.MixResult>()
                for (section in result.allSections) {
                    val ownSection = isOwnLibrarySectionTitle(section.title)
                    val recapSection = isRecapTitle(section.title)
                    for (item in section.items) {
                        val pid = item.playlistId.trim()
                        if (pid.isEmpty() || item.title.isBlank()) continue
                        // La tarjeta liked dibuja overlay local: no exijas thumbnail (YT a veces la manda vacía).
                        val liked = isLikedPlaylistId(pid)
                        if (!liked && item.thumbnailUrl.isBlank()) continue
                        if (!seen.add(pid)) continue
                        // Favoritos (gato): fuera de TODO (ni recomendadas ni la nueva sección).
                        val fav = FavoritesArt.isFavorites(pid) ||
                            item.title.trim().equals(FavoritesPlaylistStore.PLAYLIST_TITLE, ignoreCase = true)
                        if (fav) continue
                        // "Música que te gustó" se añade sintetizada abajo (no desde el feed).
                        if (liked) continue
                        // Nueva sección "Lo nuevo y lo que más te gusta": mixes personales de YT.
                        if (isPersonalFavoriteMix(item)) { newFaves.add(item); continue }
                        // Los "Recap" (resúmenes YT) tienen su propia sección "Recaps": fuera de
                        // recomendadas para que no llenen ese carrusel ni se muestren duplicados.
                        if (recapSection || isRecapTitle(item.title)) { recaps.add(item); continue }
                        if (ownSection) ownItems.add(item) else otherItems.add(item)
                    }
                }
                // Prioriza comunidad/YT; reserva como mucho OWN_QUOTA para biblioteca. Si falta
                // para llenar el carrusel, rellena con lo que quede de cualquiera de los dos.
                val fromOwn = ownItems.take(RECOMMENDED_OWN_QUOTA)
                val fromOther = otherItems.take(RECOMMENDED_LIMIT - fromOwn.size)
                val remaining = otherItems.drop(fromOther.size) + ownItems.drop(fromOwn.size)
                val playlists = (fromOther + fromOwn + remaining).take(RECOMMENDED_LIMIT)
                if (playlists.isNotEmpty()) {
                    recommendedEntries.clear()
                    recommendedEntries.addAll(playlists)
                    cacheRecommended(playlists)
                    updateRecommendedUi()
                }
                // Lo nuevo y lo que más te gusta: tarjeta de "Música que te gustó" SIEMPRE primero,
                // luego los mixes personales de YT. Va antes de recomendadas.
                val newFavesOrdered = listOf(likedMusicCard()) + newFaves
                newFavesEntries.clear()
                newFavesEntries.addAll(newFavesOrdered)
                cacheNewFaves(newFavesOrdered)
                updateNewFavesUi()
                // Recaps: sección propia, debajo de "Selección rápida".
                if (recaps.isNotEmpty()) {
                    recapEntries.clear()
                    recapEntries.addAll(recaps)
                    cacheRecaps(recaps)
                    updateRecapsUi()
                }
            }

            override fun onError(error: String) { /* keep whatever cache is already shown */ }
        })
    }

    /**
     * True si el título de la sección del home de YT Music corresponde a contenido de la BIBLIOTECA
     * PROPIA del usuario (playlists guardadas/creadas o "escucha de nuevo"), frente a secciones
     * editoriales o generadas por YT/la comunidad. Se usa para limitar cuántas listas propias
     * aparecen en "Playlists recomendadas".
     */
    private fun isOwnLibrarySectionTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        if (t.isEmpty()) return false
        return t.contains("tu biblioteca") || t.contains("your library") ||
            t.contains("tus listas") || t.contains("tus playlists") || t.contains("your playlists") ||
            t.contains("tus mixes") ||
            t.contains("escucha de nuevo") || t.contains("listen again") ||
            t.contains("vuelve a escuchar") || t.contains("volver a escuchar") ||
            t.contains("guardad") || t.contains("saved") ||
            t.contains("creada por ti") || t.contains("creadas por ti")
    }

    private fun updateRecommendedUi() {
        if (!isAdded) return
        val has = recommendedEntries.isNotEmpty()
        llRecommendedHeader?.visibility = if (has) View.VISIBLE else View.GONE
        rvRecommended?.visibility = if (has) View.VISIBLE else View.GONE
        (rvRecommended?.adapter as? MixCardAdapter)?.notifyDataSetChanged()
        if (has) {
            hideHomeSkeleton(llRecommendedSkeleton)
            revealAfterFirstContentPass()
        }
    }

    private fun setupRecaps() {
        rvRecaps?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvRecaps)
        rvRecaps?.adapter = MixCardAdapter(recapEntries) { onRecapClicked(it) }
    }

    private fun updateRecapsUi() {
        if (!isAdded) return
        val has = recapEntries.isNotEmpty()
        llRecapsHeader?.visibility = if (has) View.VISIBLE else View.GONE
        rvRecaps?.visibility = if (has) View.VISIBLE else View.GONE
        (rvRecaps?.adapter as? MixCardAdapter)?.notifyDataSetChanged()
        if (has) {
            hideHomeSkeleton(llRecapsSkeleton)
            revealAfterFirstContentPass()
        }
    }

    // ========== Lo nuevo y lo que más te gusta (liked + mixes personales YT) ==========

    private fun setupNewFaves() {
        rvNewFaves?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvNewFaves)
        rvNewFaves?.adapter = MixCardAdapter(newFavesEntries) { onRecommendedClicked(it) }
        // Siembra la tarjeta "Música que te gustó" al instante (no depende del fetch ni de estar
        // online). El fetch luego reconstruye la lista con los mixes personales delante-detrás.
        if (newFavesEntries.isEmpty()) {
            newFavesEntries.add(likedMusicCard())
            updateNewFavesUi()
        }
    }

    private fun updateNewFavesUi() {
        if (!isAdded) return
        val has = newFavesEntries.isNotEmpty()
        llNewFavesHeader?.visibility = if (has) View.VISIBLE else View.GONE
        rvNewFaves?.visibility = if (has) View.VISIBLE else View.GONE
        (rvNewFaves?.adapter as? MixCardAdapter)?.notifyDataSetChanged()
        if (has) {
            hideHomeSkeleton(llNewFavesSkeleton)
            revealAfterFirstContentPass()
        }
    }

    private fun cacheNewFaves(list: List<YouTubeMusicService.MixResult>) =
        cacheMixList("home_newfaves_data", list)

    private fun loadCachedNewFaves() {
        if (!isAdded || newFavesEntries.size > 1) return
        submitBg {
            try {
                val ctx = context ?: return@submitBg
                val raw = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                    .getString("home_newfaves_data", "") ?: ""
                if (raw.isEmpty()) return@submitBg
                val parsed = parseRecommendedCacheList(raw)
                if (parsed.isEmpty()) return@submitBg
                handler.post {
                    if (!isAdded || newFavesEntries.size > 1) return@post
                    newFavesEntries.clear()
                    newFavesEntries.addAll(parsed)
                    updateNewFavesUi()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading newfaves cache", e)
            }
        }
    }

    private fun loadCachedRecommended() {
        if (!isAdded || recommendedEntries.isNotEmpty()) return
        submitBg {
            try {
                val ctx = context ?: return@submitBg
                val raw = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                    .getString("home_recommended_data", "") ?: ""
                if (raw.isEmpty()) return@submitBg
                val parsed = parseRecommendedCacheList(raw)
                if (parsed.isEmpty()) return@submitBg
                handler.post {
                    if (!isAdded || recommendedEntries.isNotEmpty()) return@post
                    // Un caché "home_recommended_data" escrito antes de este cambio puede contener
                    // liked/favoritos/mixes personales que ahora viven en otra sección: fíltralos para
                    // que no reaparezcan en recomendadas hasta el próximo fetch.
                    recommendedEntries.addAll(parsed.filterNot {
                        isLikedPlaylistId(it.playlistId) || FavoritesArt.isFavorites(it.playlistId) ||
                            it.title.trim().equals(FavoritesPlaylistStore.PLAYLIST_TITLE, ignoreCase = true) ||
                            isPersonalFavoriteMix(it)
                    })
                    updateRecommendedUi()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading recommended cache", e)
            }
        }
    }

    private fun parseRecommendedCacheList(raw: String): List<YouTubeMusicService.MixResult> {
        val out = mutableListOf<YouTubeMusicService.MixResult>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pid = obj.optString("playlistId", "")
                val title = obj.optString("title", "")
                if (pid.isEmpty() || title.isEmpty()) continue
                out.add(YouTubeMusicService.MixResult(pid, title, obj.optString("subtitle", ""), obj.optString("thumbnailUrl", ""), obj.optString("params", "")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing recommended cache", e)
        }
        return out
    }

    private fun cacheRecommended(list: List<YouTubeMusicService.MixResult>) =
        cacheMixList("home_recommended_data", list)

    private fun cacheRecaps(list: List<YouTubeMusicService.MixResult>) =
        cacheMixList("home_recaps_data", list)

    private fun cacheMixList(prefKey: String, list: List<YouTubeMusicService.MixResult>) {
        submitBg {
            try {
                val ctx = context ?: return@submitBg
                val arr = JSONArray()
                for (m in list) {
                    arr.put(JSONObject().apply {
                        put("playlistId", m.playlistId)
                        put("title", m.title)
                        put("subtitle", m.subtitle)
                        put("thumbnailUrl", m.thumbnailUrl)
                        // navigationEndpoint token: lets a cached YT-generated mix/recap still resolve
                        // its tracks after a cold start. Old cache entries lack it (optString → "").
                        put("params", m.params)
                    })
                }
                ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE).edit()
                    .putString(prefKey, arr.toString())
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error caching mix list $prefKey", e)
            }
        }
    }

    private fun loadCachedRecaps() {
        if (!isAdded || recapEntries.isNotEmpty()) return
        submitBg {
            try {
                val ctx = context ?: return@submitBg
                val raw = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                    .getString("home_recaps_data", "") ?: ""
                if (raw.isEmpty()) return@submitBg
                val parsed = parseRecommendedCacheList(raw)
                if (parsed.isEmpty()) return@submitBg
                handler.post {
                    if (!isAdded || recapEntries.isNotEmpty()) return@post
                    recapEntries.addAll(parsed)
                    updateRecapsUi()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading recaps cache", e)
            }
        }
    }

    private fun onRecommendedClicked(item: YouTubeMusicService.MixResult) {
        if (!isAdded) return
        // Opens the playlist detail, which loads ALL of its songs. Forward the card's params token so
        // YT-generated mixes/recaps (Replay/Archive/Recap) actually load instead of opening empty.
        // ART_HINT_SINGLE: this carousel ALWAYS shows one YT thumbnail, so even when the item is a
        // radio/mix id (e.g. a "listen again" seed like "mi gata") the detail clones that exact cover
        // instead of building a 3-circle radio composite the card never showed.
        openPlaylistDetailFromPrincipal(
            item.playlistId, item.title, item.thumbnailUrl, item.params,
            PlaylistDetailFragment.ART_HINT_SINGLE
        )
    }

    private fun onRecapClicked(item: YouTubeMusicService.MixResult) {
        if (!isAdded) return
        // Recaps carry their own single YT cover — clone it (ART_HINT_SINGLE), forward params so the
        // generated tracklist resolves.
        openPlaylistDetailFromPrincipal(
            item.playlistId, item.title, item.thumbnailUrl, item.params,
            PlaylistDetailFragment.ART_HINT_SINGLE
        )
    }

    /**
     * Single-cover carousel adapter shared by "Playlists recomendadas" and "Recaps": both render one
     * YT thumbnail per card (never a 2x2/radio) and open the detail with ART_HINT_SINGLE. Backed by an
     * explicit list + click callback so one class serves both sections.
     */
    private inner class MixCardAdapter(
        private val entries: List<YouTubeMusicService.MixResult>,
        private val onClick: (YouTubeMusicService.MixResult) -> Unit
    ) : RecyclerView.Adapter<MixCardAdapter.VH>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_carousel, parent, false)
            applyCarouselCardLayout(view)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(entries[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardPlaylist: View = itemView.findViewById(R.id.cardPlaylist)
            private val ivCover: ShapeableImageView = itemView.findViewById(R.id.ivPlaylistCover)
            private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
            private val vLikedBg: View = itemView.findViewById(R.id.vPlaylistLikedBg)
            private val ivLikedIcon: ImageView = itemView.findViewById(R.id.ivPlaylistLikedIcon)

            fun bind(item: YouTubeMusicService.MixResult) {
                val isLiked = isLikedPlaylistId(item.playlistId)
                val isFav = FavoritesArt.isFavorites(item.playlistId) ||
                    item.title.trim().equals(FavoritesPlaylistStore.PLAYLIST_TITLE, ignoreCase = true)
                val click = View.OnClickListener { onClick(item) }
                itemView.setOnClickListener(click)
                cardPlaylist.setOnClickListener(click)

                if (isLiked || isFav) {
                    // Misma portada compuesta que en "recientes": el arte remoto de YouTube trae
                    // el pulgar/gato a otra proporción, así que dibujamos el overlay local para que
                    // el icono central se vea idéntico en ambas secciones.
                    tvName.text = if (isFav) FavoritesPlaylistStore.PLAYLIST_TITLE else "Música que te gustó"
                    ivCover.setImageDrawable(null)
                    ivCover.visibility = View.INVISIBLE
                    vLikedBg.visibility = View.VISIBLE
                    ivLikedIcon.visibility = View.VISIBLE
                    vLikedBg.setBackgroundResource(
                        if (isFav) R.drawable.bg_music_favorites_gradient
                        else R.drawable.bg_music_liked_gradient
                    )
                    ivLikedIcon.setImageResource(
                        if (isFav) R.drawable.ic_cat_white else R.drawable.ic_thumb_up_liked
                    )
                    return
                }

                // Item normal: oculta el overlay (holder reciclado) y carga la portada remota.
                vLikedBg.visibility = View.GONE
                ivLikedIcon.visibility = View.GONE
                ivCover.visibility = View.VISIBLE
                tvName.text = item.title
                if (item.thumbnailUrl.isNotEmpty() && isAdded) {
                    try {
                        Glide.with(this@PrincipalFragment).load(item.thumbnailUrl)
                            .placeholder(R.color.surface_high)
                            .transform(SHARED_YT_CROP, SHARED_CENTER_CROP)
                            .into(ivCover)
                    } catch (_: Exception) {}
                } else {
                    ivCover.setImageResource(R.color.surface_high)
                }
            }
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

    // "Open the player with a queue" now goes through SongPlayerLauncher — the transaction
    // helpers that used to live here (and in SearchFragment) were per-fragment duplicates.


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
        // The streaming-cache prefs file is large (one entry per cached playlist), so the first
        // getString() blocks the UI thread loading/parsing the whole XML. Do the read + JSON parse
        // off-main (bgExecutor), then commit on the main thread — same pattern as refreshRadios.
        submitBg {
            try {
                val ctx = context ?: return@submitBg
                val cache = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                val raw = cache.getString("home_covers_data", "") ?: ""
                if (raw.isEmpty()) {
                    // No local cache — fall back to Firebase (async, off the cold-start path).
                    handler.post {
                        if (!isAdded || coversResults.isNotEmpty()) return@post
                        CloudSyncManager.getInstance(requireContext()).fetchCloudCovers(requireContext()) { restoredRaw ->
                            if (restoredRaw.isEmpty()) return@fetchCloudCovers
                            submitBg {
                                val parsed = parseCoversCacheList(restoredRaw)
                                if (parsed.isEmpty()) return@submitBg
                                handler.post {
                                    if (!isAdded || coversResults.isNotEmpty()) return@post
                                    coversResults.addAll(parsed)
                                    updateCoversUi()
                                }
                            }
                        }
                    }
                    return@submitBg
                }
                val updatedAt = cache.getLong("home_covers_updated_at", 0L)
                val parsed = parseCoversCacheList(raw)
                handler.post {
                    if (!isAdded || coversResults.isNotEmpty()) return@post
                    lastCoversNetworkFetchTimeMs = updatedAt
                    coversResults.addAll(parsed)
                    if (coversResults.isNotEmpty()) updateCoversUi()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }
        }
    }

    /** Pure parser (no shared-state mutation) so it can run off the main thread. */
    private fun parseCoversCacheList(raw: String): List<YouTubeMusicService.TrackResult> {
        val out = mutableListOf<YouTubeMusicService.TrackResult>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val videoId = obj.optString("videoId", "")
                val title = obj.optString("title", "")
                if (videoId.isEmpty() || title.isEmpty()) continue
                out.add(YouTubeMusicService.TrackResult(
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
        return out
    }

    // ========== Radios ==========

    private fun setupRadios() {
        rvRadios?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvRadios)
        rvRadios?.adapter = RadioCarouselAdapter()
    }

    private fun refreshRadios(force: Boolean = false) {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRadiosRefreshMs < REENTRY_REFRESH_THROTTLE_MS && radioEntries.isNotEmpty()) return
        lastRadiosRefreshMs = now
        val appContext = requireContext().applicationContext
        submitBg {
            // Solo las MÁS escuchadas: exige un mínimo de reproducciones (oculta las recién creadas)
            // y ordena por reproducciones desc. Las radios ancladas se mantienen siempre visibles.
            val radios = RadioHistoryStore.getRadios(appContext)
                .filter {
                    it.playCount >= RadioHistoryStore.MIN_PLAYS_FOR_TOP ||
                        RadioHistoryStore.isPinned(appContext, it.radioPlaylistId)
                }
                .sortedByDescending { it.playCount }
                .take(10)
            handler.post {
                if (!isAdded) return@post
                radioEntries.clear()
                radioEntries.addAll(radios)
                val hasRadios = radioEntries.isNotEmpty()
                llRadiosHeader?.visibility = if (hasRadios) View.VISIBLE else View.GONE
                rvRadios?.visibility = if (hasRadios) View.VISIBLE else View.GONE
                (rvRadios?.adapter as? RadioCarouselAdapter)?.notifyDataSetChanged()
                // Empty ≠ loaded: on fresh install the radios arrive with cloud hydration, so the
                // skeleton stays until data (or the safety timer) resolves it.
                if (hasRadios) hideHomeSkeleton(llRadiosSkeleton)
                // Pre-fetch side images so they're in Glide disk cache before scroll
                preloadRadioImages(radios)
                revealAfterFirstContentPass()
            }
        }
    }

    private fun preloadRadioImages(radios: List<RadioHistoryStore.RadioEntry>) {
        if (!isAdded || radios.isEmpty()) return
        val ctx = context?.applicationContext ?: return
        val sizePx = radioCardWidthPx
        try {
            for (radio in radios) {
                val centerUrl = radio.songThumbnail
                val (leftUrl, rightUrl) = resolveRadioSides(radio, centerUrl)
                // Build + cache the single 3-circle composite ahead of first paint (no-op if the
                // memory/disk cache already has it), so the very first scroll is already instant.
                RadioArtComposer.precompose(ctx, radio.radioPlaylistId, centerUrl, leftUrl, rightUrl, sizePx)
            }
        } catch (_: Exception) {}
    }

    /** Deterministically resolves the 2 side-thumbnail URLs for a radio, using the cache first,
     *  then the radio's own tracks, then the playlist grid fallback. Persists newly-computed sides. */
    private fun resolveRadioSides(
        radio: RadioHistoryStore.RadioEntry,
        centerUrl: String
    ): Pair<String, String> {
        radioSideUrlsCache[radio.radioPlaylistId]?.takeIf { it.first.isNotEmpty() }?.let { return it }

        val otherTracks = radio.tracks.filter { it.thumbnailUrl.isNotEmpty() && it.thumbnailUrl != centerUrl }
        val sides: Pair<String, String> = if (otherTracks.size >= 2) {
            val seed = radio.radioPlaylistId.hashCode().toLong()
            // Familiar faces first: tracks by followed/played artists win the two side slots,
            // deterministic seeded order breaks ties.
            val seeded = otherTracks.sortedWith(
                compareByDescending<RadioHistoryStore.RadioTrack> { isKnownArtist(it.artist) }
                    .thenBy { it.videoId.hashCode().toLong() xor seed }
            )
            Pair(seeded[0].thumbnailUrl, seeded.getOrNull(1)?.thumbnailUrl ?: "")
        } else if (isAdded) {
            val gridUrls = resolvePlaylistGridUrls(radio.radioPlaylistId).filter { it != centerUrl }
            when {
                gridUrls.size >= 2 -> Pair(gridUrls[0], gridUrls[1])
                gridUrls.size == 1 -> Pair(gridUrls[0], "")
                else -> Pair("", "")
            }
        } else Pair("", "")

        if (sides.first.isNotEmpty()) {
            persistSideUrls(radio.radioPlaylistId, sides.first, sides.second)
        }
        return sides
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
            applyCarouselCardLayout(view)
            return RadioVH(view)
        }

        override fun onBindViewHolder(holder: RadioVH, position: Int) {
            val radio = radioEntries[position]
            holder.bind(radio)
        }

        inner class RadioVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardRadio: View = itemView.findViewById(R.id.cardRadio)
            private val vBg: View = itemView.findViewById(R.id.vRadioBg)
            // URL currently bound to this holder — async Palette callbacks compare against it
            // so a recycled holder is never painted with a stale color.
            private var boundCenterUrl: String = ""
            private val ivComposite: ImageView = itemView.findViewById(R.id.ivRadioComposite)
            private val tvName: TextView = itemView.findViewById(R.id.tvRadioName)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvRadioTitle)

            fun bind(radio: RadioHistoryStore.RadioEntry) {
                val cachedText = radioArtistTextCache[radio.radioPlaylistId]
                val text = if (cachedText != null) cachedText else {
                    val uniqueArtists = radio.tracks.map { it.artist }.filter { it.isNotEmpty() }.distinct()
                    val random = java.util.Random(radio.radioPlaylistId.hashCode().toLong())
                    // Familiar artists first (followed/played), unknown ones only fill the rest —
                    // so "Con X, Y, Z y más" names people the user actually recognizes.
                    val (known, unknown) = uniqueArtists.partition { isKnownArtist(it) }
                    val top3 = (known.shuffled(random) + unknown.shuffled(random)).take(3)
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

                // Center seed thumbnail drives the card's gradient background color.
                val centerUrl = radio.songThumbnail
                boundCenterUrl = centerUrl
                if (centerUrl.isNotEmpty() && isAdded) {
                    val cachedColor = radioDominantColorCache[centerUrl]
                    if (cachedColor != null) {
                        // Skip rebuilding the gradient if this card already shows this exact color
                        // (recycled holder rebinding to the same radio, notifyDataSetChanged, etc.).
                        if (vBg.getTag(R.id.tag_radio_bg_color) != cachedColor) {
                            vBg.setTag(R.id.tag_radio_bg_color, cachedColor)
                            vBg.background = buildRadioCardGradient(cachedColor)
                        }
                    } else {
                        try {
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
                                            // Recycled holder may already show another radio — the
                                            // color stays cached, but don't paint the wrong card.
                                            if (boundCenterUrl != centerUrl) return@generate
                                            vBg.setTag(R.id.tag_radio_bg_color, dominant)
                                            vBg.background = buildRadioCardGradient(dominant)
                                        }
                                    }
                                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                                })
                        } catch (_: Exception) {}
                    }
                }

                // The 3 circular thumbnails are composed into ONE cached image (RadioArtComposer)
                // instead of 3 live Glide loads, so the carousel never streams them in one by one.
                val (leftUrl, rightUrl) = resolveRadioSides(radio, centerUrl)
                RadioArtComposer.load(
                    ivComposite,
                    radio.radioPlaylistId,
                    centerUrl,
                    leftUrl,
                    rightUrl,
                    radioCardWidthPx
                )
            }
        }
    }

    // ========== Radio UI Cache Persistence ==========

    private fun persistDominantColor(radioPlaylistId: String, url: String, color: Int) {
        radioDominantColorCache[url] = color
        val ctx = context?.applicationContext ?: return
        submitBg {
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
        submitBg {
            ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("sides_$radioPlaylistId", "$left\n$right")
                .apply()
        }
    }

    private fun persistArtistText(radioPlaylistId: String, text: String) {
        radioArtistTextCache[radioPlaylistId] = text
        val ctx = context?.applicationContext ?: return
        submitBg {
            ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("artists_$radioPlaylistId", text)
                .apply()
        }
    }

    private fun preloadRadioCaches() {
        val ctx = context?.applicationContext ?: return
        submitBg {
            try {
                val prefs = ctx.getSharedPreferences(PREFS_RADIO_CACHE, Context.MODE_PRIVATE)

                // One-shot migration to the Spotify-style radio cards: the persisted subtitle
                // texts and side-url picks were computed without the known-artist bias, and the
                // composed art uses the old geometry. Drop them so they recompute (colors stay —
                // they're raw Palette output, still valid input for the pastel gradient).
                if (prefs.getInt("radio_ui_version", 1) != RADIO_UI_VERSION) {
                    val editor = prefs.edit()
                    for (key in prefs.all.keys) {
                        if (key.startsWith("artists_") || key.startsWith("sides_")) editor.remove(key)
                    }
                    editor.putInt("radio_ui_version", RADIO_UI_VERSION).apply()
                    RadioArtComposer.clearAllCaches(ctx)
                }

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

                // "Known" artists = followed library artists (any signed-in account, minus the
                // unfollow tombstones) + the artists the user actually plays. Loaded in the same
                // bg pass so the FIFO executor + FIFO main-handler ordering guarantees the set is
                // in place before the first radio bind computes a subtitle.
                val known = HashSet<String>()
                try {
                    val streaming = ctx.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                    val unfollowed = streaming.getStringSet("unfollowed_artists_channel_ids", emptySet()) ?: emptySet()
                    for ((key, value) in streaming.all) {
                        if (!key.startsWith("library_artists_data_") || value !is String || value.isEmpty()) continue
                        try {
                            val arr = JSONArray(value)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                if (obj.optString("channelId", "") in unfollowed) continue
                                val name = obj.optString("name", "").trim().lowercase()
                                if (name.isNotEmpty()) known.add(name)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                try {
                    for (entry in PlayCountStore.getTopEntries(ctx, 50)) {
                        val name = entry.artist.trim().lowercase()
                        if (name.isNotEmpty()) known.add(name)
                    }
                } catch (_: Exception) {}

                handler.post {
                    if (!isAdded) return@post
                    radioDominantColorCache.putAll(colors)
                    radioSideUrlsCache.putAll(sides)
                    radioArtistTextCache.putAll(artists)
                    knownArtistKeys.clear()
                    knownArtistKeys.addAll(known)
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

    /** Flat fluorescent field for radio cards (see RadioArtComposer.cardBackgroundColor).
     *  [rawColor] is the Palette color as cached/persisted. */
    private fun buildRadioCardGradient(rawColor: Int): android.graphics.drawable.ColorDrawable =
        android.graphics.drawable.ColorDrawable(RadioArtComposer.cardBackgroundColor(rawColor))

    /** True if [artist] matches (or contains) an artist the user follows or plays. */
    private fun isKnownArtist(artist: String): Boolean {
        if (artist.isEmpty() || knownArtistKeys.isEmpty()) return false
        val norm = artist.trim().lowercase()
        if (norm in knownArtistKeys) return true
        // Compound credits ("A & B", "A ft. C"): any known name inside the string counts. Names
        // shorter than 4 chars are skipped to avoid accidental substring hits.
        return knownArtistKeys.any { it.length >= 4 && norm.contains(it) }
    }

    // ========== Playlists recientes ==========

    private fun setupPlaylists() {
        rvPlaylists?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        improveHorizontalScrollDetection(rvPlaylists)
        rvPlaylists?.adapter = PlaylistCarouselAdapter()
    }

    private fun refreshPlaylists(force: Boolean = false) {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPlaylistsRefreshMs < REENTRY_REFRESH_THROTTLE_MS && playlistEntries.isNotEmpty()) return
        lastPlaylistsRefreshMs = now
        // PlayCountStore.getRecentPlaylists() reads its in-memory snapshot (warm) or SharedPreferences
        // + JSON (cold); run it off the UI thread either way so module entry never stalls.
        val appContext = requireContext().applicationContext
        // Short (<4 urls) grid lists cached for the whole view lifetime hide grid urls persisted
        // later by PlaylistDetail (covers that never load). Snapshot those ids here (main thread —
        // the cache is main-confined) and re-resolve them from prefs on the bg pass.
        val staleGridIds = playlistGridUrlsCache.filterValues { it.size < 4 }.keys.toList()
        submitBg {
            val playlists = PlayCountStore.getRecentPlaylists(appContext, 10)
            val freshGridUrls = HashMap<String, List<String>>()
            for (pid in staleGridIds) {
                freshGridUrls[pid] = computePlaylistGridUrls(appContext, pid)
            }
            handler.post {
                if (!isAdded) return@post
                playlistGridUrlsCache.putAll(freshGridUrls)
                playlistEntries.clear()
                playlistEntries.addAll(playlists)
                val hasPlaylists = playlistEntries.isNotEmpty()
                llPlaylistsHeader?.visibility = if (hasPlaylists) View.VISIBLE else View.GONE
                rvPlaylists?.visibility = if (hasPlaylists) View.VISIBLE else View.GONE
                (rvPlaylists?.adapter as? PlaylistCarouselAdapter)?.notifyDataSetChanged()
                // Empty ≠ loaded (fresh install: la data llega con la hidratación cloud).
                if (hasPlaylists) hideHomeSkeleton(llPlaylistsSkeleton)
                revealAfterFirstContentPass()
            }
        }
    }

    /**
     * Public entry point for MainActivity to repopulate the home carousels when session/cloud
     * data lands while Principal is already foregrounded (e.g. right after first-install web login
     * or after cloud hydration). Without this, Principal renders once from empty caches on cold
     * start and stays black until the user navigates away and back. Safe to call repeatedly —
     * each refresh* method guards isAdded and throttles its own work.
     */
    fun refreshAllContent() {
        if (!isAdded || isHidden) return
        // Post-login / hydration repopulate: bring skeletons back for the still-empty sections
        // (and re-arm their safety dismiss) so the reload is visible instead of a black home.
        showHomeSkeletonsForEmptySections()
        // Force past the re-entry throttles: this is the explicit "new session/cloud data landed,
        // repopulate now" signal, so it must not be skipped as a redundant refresh.
        refreshShortcuts(force = true)
        // Covers: only force when nothing is on screen (first-install / empty-cache case). Cloud
        // hydration fires on EVERY signed-in app open, and forcing past the 12h TTL here replaced
        // freshly-rendered cached covers with a reshuffled network result seconds after entering.
        refreshCovers(force = coversResults.isEmpty())
        refreshRadios(force = true)
        refreshPlaylists(force = true)
        refreshRecommended(force = true)
    }

    /**
     * True for playlists that carry their OWN single cover art (albums / singles) rather than a
     * synthesized mosaic. YouTube album browse ids start with "MPRE" and album playlist ids with
     * "OLAK5uy"; local albums use LocalFilesStore's album prefix. These must show that one cover,
     * not a 2x2 built from play-history images (which the autoplay queue can pollute).
     */
    private fun isSingleCoverPlaylistId(playlistId: String): Boolean {
        val id = playlistId.trim()
        // "RDCLAK5uy_…": YT auto-generated playlists — fixed tracklist + own single cover, so they
        // must show that one cover (not a synthesized 2x2), matching the detail header.
        return id.startsWith("MPRE") || id.startsWith("OLAK5uy")
            || id.startsWith("RDCLAK") || LocalFilesStore.isLocalAlbumId(id)
    }

    private fun isLikedPlaylistId(playlistId: String): Boolean {
        val id = playlistId.trim()
        // "VLLM": browse-form liked id — cached recommended JSON may still carry it un-stripped.
        return id == YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
                || id == "LL" || id == "LM" || id.startsWith("VLLL") || id == "VLLM"
    }

    private fun onPlaylistClicked(entry: PlayCountStore.PlayCountEntry) {
        if (!isAdded) return
        // A recap that landed in "recientes" (the user played it) must open cloning its single YT
        // cover, not the synthesized 2x2 — same ART_HINT_SINGLE the Recaps carousel uses.
        val artHint = if (isRecapTitle(entry.playlistName)) PlaylistDetailFragment.ART_HINT_SINGLE else ""
        openPlaylistDetailFromPrincipal(
            entry.playlistId,
            entry.playlistName,
            entry.imageUrl,
            artHint = artHint
        )
    }

    private inner class PlaylistCarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_PLAYLIST = 0
        private val TYPE_RADIO = 1

        override fun getItemCount() = playlistEntries.size

        override fun getItemViewType(position: Int): Int {
            // RDCLAK5uy_… are YT auto-generated PLAYLISTS (fixed tracklist + single static cover),
            // not radios — render them as a single-image card so the library matches how the detail
            // header now opens them (single rounded cover, not a 3-circle radio composite).
            // Recaps also carry their own single YT cover → never a radio card.
            val entry = playlistEntries[position]
            val pid = entry.playlistId
            return if (pid.startsWith("RD") && !pid.startsWith("RDCLAK") && !isRecapTitle(entry.playlistName))
                TYPE_RADIO else TYPE_PLAYLIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_RADIO) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_radio_carousel, parent, false)
                applyCarouselCardLayout(view)
                return RadioPlaylistVH(view)
            }
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_carousel, parent, false)
            applyCarouselCardLayout(view)
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
                val isFav = FavoritesArt.isFavorites(entry.playlistId)
                tvName.text = if (isLiked) "Música que te gustó" else entry.playlistName
                val clickListener = View.OnClickListener { onPlaylistClicked(entry) }
                itemView.setOnClickListener(clickListener)
                cardPlaylist.setOnClickListener(clickListener)

                if (isLiked || isFav) {
                    ivCover.setImageDrawable(null)
                    ivCover.visibility = View.INVISIBLE
                    vLikedBg.visibility = View.VISIBLE
                    ivLikedIcon.visibility = View.VISIBLE
                    // Fondo+icono explícitos en ambas ramas: los holders se reciclan y el
                    // default del layout (liked) solo aplica al inflar.
                    vLikedBg.setBackgroundResource(
                        if (isFav) R.drawable.bg_music_favorites_gradient
                        else R.drawable.bg_music_liked_gradient
                    )
                    ivLikedIcon.setImageResource(
                        if (isFav) R.drawable.ic_cat_white else R.drawable.ic_thumb_up_liked
                    )
                    // Icon is sized to 40% of the cover deterministically via the layout
                    // (layout_constraintWidth_percent), so it renders correct on the first
                    // frame — no post-layout padding hack that flashes huge then shrinks.
                    return
                }

                // Reset liked views for recycled ViewHolder
                ivCover.visibility = View.VISIBLE
                vLikedBg.visibility = View.GONE
                ivLikedIcon.visibility = View.GONE

                if (isAdded) {
                    // Albums (and single-cover playlists) have their OWN authoritative artwork, so
                    // NEVER synthesize a 2x2 for them — the composite would mix in unrelated covers
                    // from tracks the autoplay/up-next queue recorded under the same playlist id.
                    // Recaps carry their own generated YT cover too → clone it, never a 2x2.
                    val forceSingle = isSingleCoverPlaylistId(entry.playlistId)
                        || isRecapTitle(entry.playlistName)
                    val gridUrls = if (forceSingle) emptyList()
                        else resolvePlaylistGridUrls(entry.playlistId)
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
            // URL currently bound to this holder — async Palette callbacks compare against it
            // so a recycled holder is never painted with a stale color.
            private var boundCenterUrl: String = ""
            private val ivComposite: ImageView = itemView.findViewById(R.id.ivRadioComposite)
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
                    // 1. Try from in-memory radio tracks (familiar artists win the side slots)
                    val radioTracks = radio?.tracks?.filter { it.thumbnailUrl.isNotEmpty() && it.thumbnailUrl != centerUrl } ?: emptyList()
                    if (radioTracks.size >= 2) {
                        val seed = entry.playlistId.hashCode().toLong()
                        val seeded = radioTracks.sortedWith(
                            compareByDescending<RadioHistoryStore.RadioTrack> { isKnownArtist(it.artist) }
                                .thenBy { it.videoId.hashCode().toLong() xor seed }
                        )
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

                boundCenterUrl = centerUrl
                if (centerUrl.isNotEmpty() && isAdded) {
                    val cachedColor = radioDominantColorCache[centerUrl]
                    if (cachedColor != null) {
                        // Skip rebuilding the gradient if this card already shows this exact color
                        // (recycled holder rebinding to the same radio, notifyDataSetChanged, etc.).
                        if (vBg.getTag(R.id.tag_radio_bg_color) != cachedColor) {
                            vBg.setTag(R.id.tag_radio_bg_color, cachedColor)
                            vBg.background = buildRadioCardGradient(cachedColor)
                        }
                    } else {
                        try {
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
                                            // Recycled holder may already show another playlist —
                                            // keep the cached color but don't paint the wrong card.
                                            if (boundCenterUrl != centerUrl) return@generate
                                            vBg.setTag(R.id.tag_radio_bg_color, dominant)
                                            vBg.background = buildRadioCardGradient(dominant)
                                        }
                                    }
                                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                                })
                        } catch (_: Exception) {}
                    }
                }

                // Single cached 3-circle composite instead of 3 live Glide loads (RadioArtComposer).
                RadioArtComposer.load(
                    ivComposite,
                    entry.playlistId,
                    centerUrl,
                    leftUrl,
                    rightUrl,
                    radioCardWidthPx
                )
            }
        }
    }

    // ========== Adapters ==========

    private inner class ShortcutsPagerAdapter : RecyclerView.Adapter<ShortcutsPagerAdapter.PageVH>() {
        // One pool shared by every page's inner RecyclerView (all pages use the single
        // item_shortcut_cell view type), so cells are reused across the 3 pages instead of each
        // page inflating its own 9 — cuts the cold-start inflation burst and refresh churn.
        private val sharedCellPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, SHORTCUTS_PER_PAGE * 2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                clipToPadding = false
                setRecycledViewPool(sharedCellPool)
                setItemViewCacheSize(SHORTCUTS_PER_PAGE)
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
            // Create the grid LayoutManager and the cell adapter ONCE and reuse them across binds.
            // Previously bind() built a NEW GridLayoutManager and a NEW ShortcutCellAdapter every
            // time, which discarded the recycled cell ViewHolders and re-inflated item_shortcut_cell
            // on every page rebind/refresh — a real source of home-screen jank.
            private val cellAdapter = ShortcutCellAdapter()
            init {
                // Non-scrolling grid: the 9 cells fit the fixed page height, so the inner
                // RecyclerView must NOT claim drag gestures. If it does, it fights the parent
                // ViewPager2 and makes swiping between pages hard. With scrolling disabled,
                // horizontal drags reach the pager and vertical drags reach the NestedScrollView.
                rv.layoutManager = object : GridLayoutManager(rv.context, 3) {
                    override fun canScrollVertically(): Boolean = false
                    override fun canScrollHorizontally(): Boolean = false
                }
                rv.adapter = cellAdapter
            }
            fun bind(items: List<PlayCountStore.PlayCountEntry>) {
                cellAdapter.setItems(items)
            }
        }
    }

    private inner class ShortcutCellAdapter :
        RecyclerView.Adapter<ShortcutCellAdapter.CellVH>() {

        private val items = ArrayList<PlayCountStore.PlayCountEntry>()

        fun setItems(newItems: List<PlayCountStore.PlayCountEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_cell, parent, false)
            return CellVH(v)
        }

        override fun onBindViewHolder(holder: CellVH, position: Int) {
            val entry = items[position]

            val isPlaylist = !entry.playlistId.isNullOrEmpty() && entry.videoId == entry.playlistId
            val isLiked = isPlaylist && isLikedPlaylistId(entry.playlistId)
            val isFav = isPlaylist && FavoritesArt.isFavorites(entry.playlistId)

            holder.tvTitle.text = if (isLiked) "Música que te gustó" else entry.title

            // Liked / Favoritos: gradient + icon, no artwork. Both branches set bg+icon
            // explicitly — cells recycle and the layout default (liked) only applies on inflate.
            if (isLiked || isFav) {
                LocalArtworkResolver.detach(holder.ivThumb)
                holder.ivThumb.setImageDrawable(null)
                holder.vLikedBg.visibility = View.VISIBLE
                holder.ivLikedIcon.visibility = View.VISIBLE
                holder.vLikedBg.setBackgroundResource(
                    if (isFav) R.drawable.bg_music_favorites_gradient
                    else R.drawable.bg_music_liked_gradient
                )
                holder.ivLikedIcon.setImageResource(
                    if (isFav) R.drawable.ic_cat_white else R.drawable.ic_thumb_up_liked
                )
                holder.ivThumb.setTag(R.id.tag_artwork_signature, if (isFav) "__fav__" else "__liked__")
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
            val start = position * COVERS_PER_PAGE
            val end = Math.min(start + COVERS_PER_PAGE, coversResults.size)
            val count = end - start

            // Reuse the page's existing row views instead of removeAllViews()+inflate on every
            // bind. The old code re-inflated item_cover_track_row up to 4× per page on each rebind
            // (page swipe / notifyDataSetChanged) — needless inflation + layout churn. Now we
            // inflate only to grow the page to the required row count, then rebind in place.
            while (page.childCount < count) {
                val row = LayoutInflater.from(page.context).inflate(R.layout.item_cover_track_row, page, false)
                page.addView(row)
            }
            for (i in 0 until page.childCount) {
                val row = page.getChildAt(i)
                if (i < count) {
                    val track = coversResults[start + i]
                    row.visibility = View.VISIBLE
                    val ivArt: ImageView = row.findViewById(R.id.ivCoverArt)
                    val tvTitle: TextView = row.findViewById(R.id.tvCoverTitle)
                    val tvSubtitle: TextView = row.findViewById(R.id.tvCoverSubtitle)
                    tvTitle.text = track.title
                    tvSubtitle.text = SongSubtitle.forRow(track.subtitle, track.title, track.duration)
                    if (!track.thumbnailUrl.isNullOrEmpty() && isAdded) {
                        try { Glide.with(this@PrincipalFragment).load(track.thumbnailUrl).placeholder(R.color.surface_high).transform(SHARED_YT_CROP, SHARED_CENTER_CROP).into(ivArt) } catch (_: Exception) {}
                    } else {
                        try { Glide.with(this@PrincipalFragment).clear(ivArt) } catch (_: Exception) {}
                        ivArt.setImageDrawable(null)
                    }
                    row.setOnClickListener { onCoversTrackClicked(track) }
                } else {
                    // Recycled page bound to a shorter (last) page: hide the leftover rows.
                    row.visibility = View.GONE
                    row.setOnClickListener(null)
                }
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
