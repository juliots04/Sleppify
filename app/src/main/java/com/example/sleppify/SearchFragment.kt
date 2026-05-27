package com.example.sleppify

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager

import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import android.graphics.drawable.Drawable
import com.example.sleppify.utils.YouTubeCropTransformation
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.text.Normalizer
import java.util.Locale
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import android.text.TextUtils

class SearchFragment : Fragment() {

    companion object {
        const val TAG = "SearchFragment"
        
        const val EXTRA_RESULT_TYPE = "search_result_type"
        const val EXTRA_RESULT_VIDEO_ID = "search_result_video_id"
        const val EXTRA_RESULT_CONTENT_ID = "search_result_content_id"
        const val EXTRA_RESULT_TITLE = "search_result_title"
        const val EXTRA_RESULT_SUBTITLE = "search_result_subtitle"
        const val EXTRA_RESULT_THUMBNAIL = "search_result_thumbnail"
        const val EXTRA_RESULT_TRACKS_JSON = "search_result_tracks_json"

        private const val PREFS_STREAMING_CACHE = "streaming_cache"
        private const val PREF_RECENT_SEARCH_QUERIES = "stream_recent_search_queries"
        private const val PREF_RECENT_SEARCH_DATA = "stream_recent_search_data"
        private const val SEARCH_PAGE_SIZE = 30
        private const val SEARCH_SUGGESTION_RECENT_LIMIT = 6
        private const val SEARCH_SCROLL_LOAD_MORE_THRESHOLD = 4

        private val DEFAULT_SEARCH_SUGGESTIONS = emptyArray<String>()

        private val SHARED_YT_CROP = YouTubeCropTransformation()
        val WHITESPACE_REGEX = Regex("\\s+")

        fun newInstance() = SearchFragment()
    }

    data class RecentSearch(
        val query: String,
        val videoId: String = "",
        val title: String = "",
        val thumbnail: String = "",
        val artist: String = ""
    )

    private val youTubeMusicService = YouTubeMusicService()
    private val normalizedFilterCache = mutableMapOf<String, String>()
    private val allTracks = mutableListOf<YouTubeMusicService.TrackResult>()
    private val tracks = mutableListOf<YouTubeMusicService.TrackResult>()
    private val recentSearchData = mutableListOf<RecentSearch>()
    private val localTrackIndex = mutableListOf<FavoritesPlaylistStore.FavoriteTrack>()
    private data class IndexedTrack(
        val track: FavoritesPlaylistStore.FavoriteTrack,
        val normTitle: String,
        val normArtist: String,
        val titleWords: List<String>,
        val artistWords: List<String>
    )
    @Volatile private var streamingCacheIndex: List<IndexedTrack> = emptyList()
    @Volatile private var streamingCacheIndexReady = false

    private val suggestionsDebounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suggestionsDebounceRunnable: Runnable? = null
    private var suggestionsJob: kotlinx.coroutines.Job? = null
    private var cachedSmartSuggestions: List<String>? = null
    // lastSavedPlaylistKey/Name now read from CustomPlaylistsStore (global persistent)

    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var ivSearchClear: ImageView
    private lateinit var ivSearchBack: ImageView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvSearchState: TextView
    private lateinit var llSearchState: View
    private lateinit var nsvSearchContent: View
    private lateinit var rvSearchSuggestions: RecyclerView
    private lateinit var llSearchSuggestionsContainer: View
    private lateinit var moduleLoadingOverlay: View
    private lateinit var llFeaturedResult: View
    private lateinit var ivFeaturedThumb: ImageView
    private lateinit var tvFeaturedTitle: TextView
    private lateinit var tvFeaturedSubtitle: TextView
    private lateinit var ivFeaturedOfflineIndicator: ImageView
    private var adapter: SearchResultsAdapter? = null
    private var featuredTrack: YouTubeMusicService.TrackResult? = null
    
    private var searching = false
    private var searchPaginationInFlight = false
    private var hasMoreSearchPages = false
    private var nextSearchPageToken = ""
    private var innertubeNextToken = ""
    private var useInnertubePagination = false
    private var activeSearchQuery = ""
    private var latestSearchRequestId = 0L

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var autoPlayOnFirstResult = false
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupRecyclerView()
        setupSuggestionsRecyclerView()
        setupSearchInput()
        setupBackButton()

        restoreRecentSearchQueries()
        loadRecentSearchesFromFirebase()
        refreshSearchSuggestions("")

        setupBackNavigation()

        // Pre-load streaming_cache index in background so first search is fast
        lifecycleScope.launch(Dispatchers.IO) {
            val t = android.os.SystemClock.elapsedRealtime()
            streamingCacheIndex = buildStreamingCacheIndex()
            streamingCacheIndexReady = true
            Log.d(TAG, "[SEARCH] streamingCacheIndex ready: ${streamingCacheIndex.size} tracks in ${android.os.SystemClock.elapsedRealtime() - t}ms")
        }


        // Overlay starts visible from XML; onResume will hide it after layout is complete.
    }

    private fun initViews(root: View) {
        etSearchQuery = root.findViewById(R.id.etSearchQuery)
        ivSearchClear = root.findViewById(R.id.ivSearchClear)
        ivSearchBack = root.findViewById(R.id.ivSearchBack)
        rvSearchResults = root.findViewById(R.id.rvSearchResults)
        tvSearchState = root.findViewById(R.id.tvSearchState)
        llSearchState = root.findViewById(R.id.llSearchState)
        nsvSearchContent = root.findViewById(R.id.nsvSearchContent)
        rvSearchSuggestions = root.findViewById(R.id.rvSearchSuggestions)
        llSearchSuggestionsContainer = root.findViewById(R.id.llSearchSuggestionsContainer)
        moduleLoadingOverlay = root.findViewById(R.id.moduleLoadingOverlay)
        llFeaturedResult = root.findViewById(R.id.llFeaturedResult)
        ivFeaturedThumb = root.findViewById(R.id.ivFeaturedThumb)
        tvFeaturedTitle = root.findViewById(R.id.tvFeaturedTitle)
        tvFeaturedSubtitle = root.findViewById(R.id.tvFeaturedSubtitle)
        ivFeaturedOfflineIndicator = root.findViewById(R.id.ivFeaturedOfflineIndicator)
        ivSearchClear.setOnClickListener {
            etSearchQuery.setText("")
            showSuggestionsMode()
        }

        root.findViewById<View>(R.id.btnFeaturedPlay).setOnClickListener { featuredTrack?.let { onTrackClicked(it) } }
        
        (requireActivity() as? MainActivity)?.let { mainActivity ->
            mainActivity.findViewById<View>(R.id.btnProfilePhoto)?.setOnClickListener {
                mainActivity.enterSettings()
            }
            mainActivity.findViewById<TextView>(R.id.tvModuleTitle)?.setOnClickListener {
                mainActivity.closeSearchFragment()
            }
        }

        root.findViewById<View>(R.id.btnFeaturedShare).setOnClickListener {
            featuredTrack?.let { track ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${track.videoId}")
                }
                startActivity(Intent.createChooser(sendIntent, "Compartir"))
            }
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(systemBars.left, 0, systemBars.right, 0)
            val llSearchBar = root.findViewById<View>(R.id.llSearchBar)
            llSearchBar?.setPadding(
                llSearchBar.paddingLeft,
                systemBars.top,
                llSearchBar.paddingRight,
                llSearchBar.paddingBottom
            )
            insets
        }

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideKeyboard()
            }
            false
        }
    }

    private fun setupBackButton() {
        ivSearchBack.setOnClickListener {
            if (requireActivity() is MainActivity) {
                (requireActivity() as MainActivity).closeSearchFragment()
            }
        }
    }

    private fun setupBackNavigation() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nsvSearchContent.visibility == View.VISIBLE) {
                    showSuggestionsMode()
                    etSearchQuery.requestFocus()
                    showKeyboard()
                } else {
                    isEnabled = false
                    if (requireActivity() is MainActivity) {
                        (requireActivity() as MainActivity).closeSearchFragment()
                    } else {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun setupRecyclerView() {
        rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        adapter = SearchResultsAdapter(
            onClick = { onTrackClicked(it) },
            onMoreClick = { track, anchor -> showTrackOptionsBottomSheet(track, anchor) }
        )
        rvSearchResults.adapter = adapter
        rvSearchResults.setHasFixedSize(true)
        rvSearchResults.itemAnimator = null
        rvSearchResults.setItemViewCacheSize(15)

        rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMoreSearchPages || searchPaginationInFlight) return
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= (adapter?.itemCount ?: 0) - SEARCH_SCROLL_LOAD_MORE_THRESHOLD) {
                    loadMoreSearchResults()
                }
            }
        })
    }

    private fun setupSuggestionsRecyclerView() {
        val suggestionsAdapter = SuggestionsAdapter {
            etSearchQuery.setText(it)
            etSearchQuery.setSelection(it.length)
            performSearch(it)
            hideKeyboard()
        }
        rvSearchSuggestions.layoutManager = LinearLayoutManager(requireContext())
        rvSearchSuggestions.adapter = suggestionsAdapter
    }


    private fun setupSearchInput() {
        etSearchQuery.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSuggestionsMode()
            }
        }
        
        etSearchQuery.setOnClickListener {
            showSuggestionsMode()
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(etSearchQuery.text.toString())
                hideKeyboard()
                true
            } else false
        }

        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                ivSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                suggestionsDebounceRunnable?.let { suggestionsDebounceHandler.removeCallbacks(it) }
                val r = Runnable { refreshSearchSuggestions(query) }
                suggestionsDebounceRunnable = r
                suggestionsDebounceHandler.postDelayed(r, 170)
            }
        })
    }


    private fun loadLocalTrackIndex() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val seen = mutableSetOf<String>()
            val result = mutableListOf<FavoritesPlaylistStore.FavoriteTrack>()

            fun tryAdd(videoId: String, title: String, artist: String, duration: String, imageUrl: String) {
                if (videoId.isEmpty() || videoId in seen) return
                seen.add(videoId)
                result.add(FavoritesPlaylistStore.FavoriteTrack(videoId, title, artist, duration, imageUrl))
            }

            // 1. Favorites
            try { FavoritesPlaylistStore.loadFavorites(ctx).forEach { tryAdd(it.videoId, it.title, it.artist, it.duration, it.imageUrl) } } catch (_: Exception) {}

            // 2. Custom playlists
            try {
                for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                    CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).forEach { tryAdd(it.videoId, it.title, it.artist, it.duration, it.imageUrl) }
                }
            } catch (_: Exception) {}

            // 3. All cached YT Music playlists (playlist_tracks_data_*)
            try {
                val cache = ctx.getSharedPreferences("streaming_cache", Context.MODE_PRIVATE)
                for ((key, value) in cache.all) {
                    if (key.startsWith("playlist_tracks_data_") && value is String) {
                        val arr = org.json.JSONArray(value)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            tryAdd(obj.optString("videoId"), obj.optString("title"), obj.optString("artist"), obj.optString("duration", ""), obj.optString("imageUrl"))
                        }
                    }
                }
            } catch (_: Exception) {}

            // 4. Playback history
            try {
                PlaybackHistoryStore.load(ctx).queue.forEach { tryAdd(it.videoId, it.title, it.artist, it.duration, it.imageUrl) }
            } catch (_: Exception) {}

            // 5. Radio history tracks
            try {
                RadioHistoryStore.getRadios(ctx).forEach { radio ->
                    radio.tracks.forEach { t -> tryAdd(t.videoId, t.title, t.artist, "", t.thumbnailUrl) }
                }
            } catch (_: Exception) {}

            launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                localTrackIndex.clear()
                localTrackIndex.addAll(result)
            }
        }
    }


    private fun showSuggestionsMode() {
        val query = etSearchQuery.text?.toString()?.trim() ?: ""
        refreshSearchSuggestions(query)
        llSearchSuggestionsContainer.visibility = View.VISIBLE
        rvSearchSuggestions.visibility = View.VISIBLE
        nsvSearchContent.visibility = View.GONE
        llSearchState.visibility = View.GONE
        view?.requestLayout()
    }

    private fun clearResults() {
        allTracks.clear()
        tracks.clear()
        featuredTrack = null
        adapter?.submitResults(emptyList())
        tvSearchState.text = ""
        activeSearchQuery = ""
        nsvSearchContent.visibility = View.GONE
        rvSearchSuggestions.visibility = View.VISIBLE
        refreshSearchSuggestions("")
    }

    /**
     * Called externally (e.g. from SongPlayerFragment "Buscar" chip) to trigger a search.
     */
    fun externalSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        etSearchQuery.setText(trimmed)
        etSearchQuery.setSelection(trimmed.length)
        performSearch(trimmed)
    }

    private fun performSearch(query: String) {
        if (searching) return
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            startPagedSearch(trimmedQuery)
        }
    }

    private fun startPagedSearch(query: String) {
        latestSearchRequestId++
        activeSearchQuery = query
        allTracks.clear()
        tracks.clear()
        featuredTrack = null
        adapter?.submitResults(emptyList())
        hasMoreSearchPages = false
        nextSearchPageToken = ""
        innertubeNextToken = ""
        useInnertubePagination = false

        refreshSearchSuggestions(query)
        rvSearchSuggestions.visibility = View.GONE
        llSearchSuggestionsContainer.visibility = View.GONE
        nsvSearchContent.visibility = View.VISIBLE
        rememberRecentSearchQuery(query)

        requestPagedSearchResults(query, "", false)
    }

    private fun requestPagedSearchResults(query: String, pageToken: String, append: Boolean) {
        val requestId = ++latestSearchRequestId
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "[SEARCH] START reqId=$requestId append=$append query=\"$query\"")

        // 1. Iniciar búsqueda local (Solo si no es scroll infinito/paginación)
        if (!append) {
            lifecycleScope.launch(Dispatchers.IO) {
                val t1 = android.os.SystemClock.elapsedRealtime()
                val localResults = performOfflineSearch(query)
                Log.d(TAG, "[SEARCH] LOCAL done in ${android.os.SystemClock.elapsedRealtime() - t1}ms — ${localResults.size} results reqId=$requestId")
                launch(Dispatchers.Main) {
                    if (activity == null || !isAdded || requestId != latestSearchRequestId) {
                        Log.d(TAG, "[SEARCH] LOCAL discarded (online arrived first) reqId=$requestId elapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                        return@launch
                    }
                    Log.d(TAG, "[SEARCH] LOCAL applied ${localResults.size} results elapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    appendUniqueTracks(localResults)
                    applyActiveFilter(query, forceSort = true)
                    
                    // Si no hay internet, cerramos el estado de carga aquí mismo
                    if (!isNetworkAvailable()) {
                        if (allTracks.isEmpty()) {
                            setSearchLoadingState(false, "No encontré resultados locales para: $query")
                        } else {
                            setSearchLoadingState(false, "")
                        }
                        revealModuleContent()
                        hideKeyboard()
                    }
                }
            }
        }

        if (!isNetworkAvailable()) {
            if (!append) setSearchLoadingState(true, "Buscando en tu biblioteca...")
            return
        }

        // 2. Proceso de búsqueda Online
        if (append) {
            searchPaginationInFlight = true
        } else {
            setSearchLoadingState(true, "Buscando música...")
        }

        // Innertube: 100% gratuita, ilimitada, sin cuotas de API Key.
        // First page returns ~20 results instantly; more loaded via scroll/background fetch.
        val maxResultsToFetch = SEARCH_PAGE_SIZE

        // For scroll-append with Innertube token, use continuation directly
        if (append && useInnertubePagination && innertubeNextToken.isNotEmpty()) {
            Log.d(TAG, "[SEARCH] CONTINUATION start reqId=$requestId")
            val tCont = android.os.SystemClock.elapsedRealtime()
            youTubeMusicService.continueInnertubeSearch(innertubeNextToken, SEARCH_PAGE_SIZE, object : YouTubeMusicService.SearchPageCallback {
                override fun onSuccess(pageResult: YouTubeMusicService.SearchPageResult) {
                    if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                    Log.d(TAG, "[SEARCH] CONTINUATION ok in ${android.os.SystemClock.elapsedRealtime() - tCont}ms — ${pageResult.tracks.size} tracks hasMore=${pageResult.nextPageToken.isNotEmpty()}")
                    searchPaginationInFlight = false
                    innertubeNextToken = pageResult.nextPageToken
                    hasMoreSearchPages = innertubeNextToken.isNotEmpty()
                    appendUniqueTracks(pageResult.tracks)
                    applyActiveFilter(query, forceSort = false)
                }
                override fun onError(error: String) {
                    if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                    Log.w(TAG, "[SEARCH] CONTINUATION error in ${android.os.SystemClock.elapsedRealtime() - tCont}ms — $error")
                    searchPaginationInFlight = false
                    hasMoreSearchPages = false
                }
            })
            return
        }

        Log.d(TAG, "[SEARCH] INNERTUBE start maxResults=$maxResultsToFetch reqId=$requestId")
        val tInnertube = android.os.SystemClock.elapsedRealtime()
        youTubeMusicService.searchTracksViaInnertube(query, maxResultsToFetch, object : YouTubeMusicService.SearchPageCallback {
            override fun onSuccess(pageResult: YouTubeMusicService.SearchPageResult) {
                if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                Log.d(TAG, "[SEARCH] INNERTUBE ok in ${android.os.SystemClock.elapsedRealtime() - tInnertube}ms — ${pageResult.tracks.size} tracks hasMore=${pageResult.nextPageToken.isNotEmpty()} totalElapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                if (append) searchPaginationInFlight = false

                // Enable infinite scroll if Innertube returned a continuation token
                innertubeNextToken = pageResult.nextPageToken
                useInnertubePagination = innertubeNextToken.isNotEmpty()
                hasMoreSearchPages = useInnertubePagination

                appendUniqueTracks(pageResult.tracks)
                applyActiveFilter(query, forceSort = true)

                if (allTracks.isEmpty() && !append) {
                    setSearchLoadingState(false, "No encontré resultados para: $query")
                } else if (!append) {
                    setSearchLoadingState(false, "")
                    allTracks.firstOrNull()?.videoId?.let { id ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            InnertubeResolver.resolveStreamUrl(requireContext(), id)
                        }
                    }
                    revealModuleContent()
                    rvSearchResults.alpha = 0f
                    rvSearchResults.animate().alpha(1f).setDuration(250).start()
                    hideKeyboard()

                    // Auto-fetch next page in background for faster scroll experience
                    if (hasMoreSearchPages) {
                        loadMoreSearchResults()
                    }
                }
            }

            override fun onError(error: String) {
                if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                Log.w(TAG, "[SEARCH] INNERTUBE error in ${android.os.SystemClock.elapsedRealtime() - tInnertube}ms — $error — falling back to YT Data API")
                // Fallback al API de datos oficial de YouTube en caso de fallo de Innertube
                val tFallback = android.os.SystemClock.elapsedRealtime()
                youTubeMusicService.searchTracksPaged(query, SEARCH_PAGE_SIZE, pageToken.takeIf { it.isNotEmpty() }, object : YouTubeMusicService.SearchPageCallback {
                    override fun onSuccess(pageResult: YouTubeMusicService.SearchPageResult) {
                        if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                        Log.d(TAG, "[SEARCH] FALLBACK ok in ${android.os.SystemClock.elapsedRealtime() - tFallback}ms — ${pageResult.tracks.size} tracks totalElapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                        if (append) searchPaginationInFlight = false
                        
                        nextSearchPageToken = pageResult.nextPageToken
                        hasMoreSearchPages = nextSearchPageToken.isNotEmpty()
                        useInnertubePagination = false

                        appendUniqueTracks(pageResult.tracks)
                        applyActiveFilter(query, forceSort = true)

                        if (allTracks.isEmpty() && !append) {
                            setSearchLoadingState(false, "No encontré resultados para: $query")
                        } else if (!append) {
                            setSearchLoadingState(false, "")
                            revealModuleContent()
                            hideKeyboard()
                        }
                    }

                    override fun onError(error: String) {
                        if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                        Log.w(TAG, "[SEARCH] FALLBACK error in ${android.os.SystemClock.elapsedRealtime() - tFallback}ms — $error totalElapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                        if (append) searchPaginationInFlight = false
                        
                        if (allTracks.isEmpty()) {
                            setSearchLoadingState(false, "Error: $error")
                        } else {
                            setSearchLoadingState(false, "")
                            if (append) Toast.makeText(requireContext(), "Error al cargar más resultados", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        })
    }

    private fun performOfflineSearch(query: String): List<YouTubeMusicService.TrackResult> {
        val normalizedQuery = normalizeForFilter(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryTokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scored = mutableListOf<Pair<YouTubeMusicService.TrackResult, Int>>()
        val seenIds = mutableSetOf<String>()

        fun tryAdd(track: FavoritesPlaylistStore.FavoriteTrack, sourceBonus: Int = 0) {
            val key = "video|${track.videoId}"
            if (key in seenIds) return
            val score = computeFuzzyScore(track.title, track.artist, normalizedQuery, queryTokens, sourceBonus)
            if (score <= 0) return
            seenIds.add(key)
            scored.add(YouTubeMusicService.TrackResult(
                "video", track.videoId, track.title, track.artist, track.imageUrl
            ) to score)
        }

        // Favorites: highest user signal (matches Spotify "liked songs" priority)
        var tPhase = android.os.SystemClock.elapsedRealtime()
        val favs = FavoritesPlaylistStore.loadFavorites(requireContext())
        favs.forEach { tryAdd(it, sourceBonus = 500) }
        Log.d(TAG, "[SEARCH] LOCAL phase=favorites ${favs.size} tracks in ${android.os.SystemClock.elapsedRealtime() - tPhase}ms")

        // Custom playlists: strong user signal
        tPhase = android.os.SystemClock.elapsedRealtime()
        var customCount = 0
        for (name in CustomPlaylistsStore.getAllPlaylistNames(requireContext())) {
            val playlist = CustomPlaylistsStore.getTracksFromPlaylist(requireContext(), name)
            playlist.forEach { tryAdd(it, sourceBonus = 300) }
            customCount += playlist.size
        }
        Log.d(TAG, "[SEARCH] LOCAL phase=custom_playlists $customCount tracks in ${android.os.SystemClock.elapsedRealtime() - tPhase}ms")

        // Cached online playlists: moderate signal (use pre-loaded index with pre-normalized strings)
        tPhase = android.os.SystemClock.elapsedRealtime()
        val cachedIndex = if (streamingCacheIndexReady) streamingCacheIndex else buildStreamingCacheIndex()
        cachedIndex.forEach { indexed ->
            val key = "video|${indexed.track.videoId}"
            if (key in seenIds) return@forEach
            val score = computeFuzzyScoreNormalized(indexed.normTitle, indexed.normArtist, indexed.titleWords, indexed.artistWords, normalizedQuery, queryTokens, 100)
            if (score <= 0) return@forEach
            seenIds.add(key)
            scored.add(YouTubeMusicService.TrackResult(
                "video", indexed.track.videoId, indexed.track.title, indexed.track.artist, indexed.track.imageUrl
            ) to score)
        }
        Log.d(TAG, "[SEARCH] LOCAL phase=streaming_cache ${cachedIndex.size} tracks in ${android.os.SystemClock.elapsedRealtime() - tPhase}ms (indexed=${streamingCacheIndexReady})")

        // Playback history: listening behavior signal
        tPhase = android.os.SystemClock.elapsedRealtime()
        try {
            val history = PlaybackHistoryStore.load(requireContext())
            history.queue.forEach { q ->
                val track = FavoritesPlaylistStore.FavoriteTrack(
                    q.videoId, q.title, q.artist, q.duration, q.imageUrl
                )
                tryAdd(track, sourceBonus = 200)
            }
            Log.d(TAG, "[SEARCH] LOCAL phase=history ${history.queue.size} tracks in ${android.os.SystemClock.elapsedRealtime() - tPhase}ms")
        } catch (e: Exception) {
            Log.e(TAG, "[SEARCH] LOCAL phase=history error: ${e.message}")
        }

        scored.sortByDescending { it.second }
        return scored.map { it.first }
            .filter { !LocalFilesStore.isLocalVideoId(it.videoId) }
    }

    private fun buildStreamingCacheIndex(): List<IndexedTrack> {
        val result = mutableListOf<IndexedTrack>()
        try {
            val prefs = requireContext().getSharedPreferences("streaming_cache", Context.MODE_PRIVATE)
            for ((key, value) in prefs.all) {
                if (key.startsWith("playlist_tracks_data_") && value is String) {
                    val arr = org.json.JSONArray(value)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val track = FavoritesPlaylistStore.FavoriteTrack(
                            obj.optString("videoId"),
                            obj.optString("title"),
                            obj.optString("artist"),
                            obj.optString("duration", ""),
                            obj.optString("imageUrl")
                        )
                        val nt = normalizeForFilter(track.title)
                        val na = normalizeForFilter(track.artist)
                        result.add(IndexedTrack(track, nt, na,
                            nt.split(Regex("\\s+")),
                            na.split(Regex("\\s+"))
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SEARCH] buildStreamingCacheIndex error: ${e.message}")
        }
        return result
    }

    private fun computeFuzzyScoreNormalized(t: String, a: String, titleWords: List<String>, artistWords: List<String>, query: String, tokens: List<String>, sourceBonus: Int = 0): Int {
        // Cheap pre-filter: skip fuzzy entirely if no token has any substring overlap
        val combined = "$t $a"
        val hasAnyOverlap = t.contains(query) || a.contains(query) ||
            tokens.any { tok -> combined.contains(tok) }
        if (!hasAnyOverlap) return 0

        var score = 0

        if (t == query) score += 1000
        else if (t.startsWith("$query ")) score += 900
        else if (t.startsWith(query)) score += 700
        else if (t.contains(query)) score += 500

        fun isFuzzyMatch(tok: String, word: String): Boolean {
            if (word.startsWith(tok)) return true
            return suggestFuzzyMatch(tok, word)
        }

        val anyHits = tokens.count {
            titleWords.contains(it) || artistWords.contains(it) ||
            titleWords.any { w -> isFuzzyMatch(it, w) } ||
            artistWords.any { w -> isFuzzyMatch(it, w) }
        }

        val exactMatchFallback = t.contains(query) || a.contains(query)
        if (anyHits < (tokens.size + 1) / 2 && !exactMatchFallback) return 0

        for (tok in tokens) {
            if (titleWords.contains(tok)) score += 100
            else if (titleWords.any { isFuzzyMatch(tok, it) }) score += 40
            else if (t.contains(tok)) score += 10
        }
        if (a == query) score += 200
        else if (a.startsWith("$query ")) score += 150
        else if (a.startsWith(query)) score += 120
        else if (a.contains(query)) score += 80
        for (tok in tokens) {
            if (artistWords.contains(tok)) score += 30
            else if (artistWords.any { isFuzzyMatch(tok, it) }) score += 10
        }
        score += sourceBonus + 5
        return score
    }

    private fun computeFuzzyScore(title: String, artist: String, query: String, tokens: List<String>, sourceBonus: Int = 0): Int {
        val t = normalizeForFilter(title)
        val a = normalizeForFilter(artist)
        var score = 0

        if (t == query) score += 1000
        else if (t.startsWith("$query ")) score += 900
        else if (t.startsWith(query)) score += 700
        else if (t.contains(query)) score += 500

        fun isFuzzyMatch(tok: String, word: String): Boolean {
            if (word.startsWith(tok)) return true
            return suggestFuzzyMatch(tok, word)
        }

        val titleWords = t.split(Regex("\\s+"))
        val artistWords = a.split(Regex("\\s+"))

        val anyHits = tokens.count {
            titleWords.contains(it) || artistWords.contains(it) ||
            titleWords.any { w -> isFuzzyMatch(it, w) } ||
            artistWords.any { w -> isFuzzyMatch(it, w) }
        }

        val exactMatchFallback = t.contains(query) || a.contains(query)
        if (anyHits < (tokens.size + 1) / 2 && !exactMatchFallback) {
            return 0
        }

        for (tok in tokens) {
            if (titleWords.contains(tok)) {
                score += 100
            } else if (titleWords.any { isFuzzyMatch(tok, it) }) {
                score += 40
            } else if (t.contains(tok)) {
                score += 10
            }
        }

        // Artist match — explicit bonus (Spotify-style: artist relevance matters)
        if (a == query) score += 200
        else if (a.startsWith("$query ")) score += 150
        else if (a.startsWith(query)) score += 120
        else if (a.contains(query)) score += 80

        for (tok in tokens) {
            if (artistWords.contains(tok)) {
                score += 30
            } else if (artistWords.any { isFuzzyMatch(tok, it) }) {
                score += 10
            }
        }

        // Source bonus: favorites > custom playlists > history > cached
        score += sourceBonus
        score += 5
        return score
    }

    private fun loadMoreSearchResults() {
        if (searching || searchPaginationInFlight || !hasMoreSearchPages || activeSearchQuery.isEmpty()) return
        requestPagedSearchResults(activeSearchQuery, nextSearchPageToken, true)
    }

    private fun appendUniqueTracks(incoming: List<YouTubeMusicService.TrackResult>) {
        val existingKeys = allTracks.map { "${it.resultType}|${it.contentId}" }.toSet()
        val newOnes = incoming.filterNot { "${it.resultType}|${it.contentId}" in existingKeys }
        allTracks.addAll(newOnes)
    }

    private data class UserSignals(
        val favoriteIds: Set<String>,
        val customPlaylistIds: Set<String>,
        val historyIds: Set<String>
    )

    private fun loadUserSignals(): UserSignals {
        val ctx = context ?: return UserSignals(emptySet(), emptySet(), emptySet())
        val favIds = try {
            FavoritesPlaylistStore.loadFavorites(ctx).map { it.videoId }.toSet()
        } catch (e: Exception) { emptySet() }
        val customIds = try {
            val ids = mutableSetOf<String>()
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).forEach { ids.add(it.videoId) }
            }
            ids
        } catch (e: Exception) { emptySet() }
        val historyIds = try {
            PlaybackHistoryStore.load(ctx).queue.map { it.videoId }.toSet()
        } catch (e: Exception) { emptySet() }
        return UserSignals(favIds, customIds, historyIds)
    }

    private fun applyActiveFilter(query: String?, forceSort: Boolean = false) {
        val normalizedQuery = query?.trim() ?: ""
        val filtered = allTracks.toMutableList()

        if (forceSort && normalizedQuery.isNotEmpty() && filtered.size > 1) {
            val signals = loadUserSignals()
            sortResults(filtered, normalizedQuery, signals)
        }

        tracks.clear()
        if (filtered.isEmpty()) {
            featuredTrack = null
            llFeaturedResult.visibility = View.GONE
        } else {
            featuredTrack = filtered[0].also { bindFeaturedTrack(it) }
            llFeaturedResult.visibility = View.VISIBLE
            if (filtered.size > 1) tracks.addAll(filtered.subList(1, filtered.size))
        }
        adapter?.submitResults(tracks.toList())

        view?.requestLayout()
    }

    private fun sortResults(list: MutableList<YouTubeMusicService.TrackResult>, query: String, signals: UserSignals = UserSignals(emptySet(), emptySet(), emptySet())) {
        val normalized = normalizeForFilter(query)
        val tokens = normalized.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val totalSize = list.size
        val indexed = list.mapIndexed { idx, track -> track to idx }
        val scored = indexed.map { (track, apiIndex) -> track to computeScore(track, normalized, tokens, signals, apiIndex, totalSize) }
        val sorted = scored.sortedByDescending { it.second }.map { it.first }
        list.clear()
        list.addAll(sorted)
    }

    private fun computeScore(track: YouTubeMusicService.TrackResult, query: String, tokens: List<String>, signals: UserSignals, apiIndex: Int, totalResults: Int): Int {
        val t = normalizeForFilter(track.title)
        val s = normalizeForFilter(track.subtitle)
        var score = 0

        // --- API position score (trust YouTube's relevance) ---
        score += maxOf(0, (totalResults - apiIndex) * 80)

        // --- Result type priority ---
        val type = track.resultType?.lowercase() ?: ""
        when {
            type == "track" || type == "video" || type == "song" -> score += 3000
            type == "artist" -> score += 500
            type == "album" -> score += 200
            type == "playlist" -> score -= 2000
        }

        // --- Exact and prefix title matches ---
        if (t == query) {
            score += 10000
        } else if (t.startsWith("$query ")) {
            score += 8000
        } else if (t.startsWith(query)) {
            score += 6000
        } else if (t.contains(query)) {
            score += 4000
        }

        val titleWords = t.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val subtitleWords = s.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

        var titleHits = 0
        var subtitleHits = 0

        tokens.forEach { tok ->
            if (titleWords.contains(tok)) {
                titleHits++
                score += 500
            } else if (titleWords.any { it.startsWith(tok) }) {
                titleHits++
                score += 350
            } else if (titleWords.any { tok.startsWith(it) }) {
                // Token is longer than word but word is prefix of token (e.g. "her" prefix of "hear")
                titleHits++
                score += 300
            } else if (titleWords.any { fuzzyMatch(tok, it) }) {
                // Levenshtein distance ≤ 1
                titleHits++
                score += 250
            }
            if (subtitleWords.contains(tok)) {
                subtitleHits++
                score += 100
            } else if (subtitleWords.any { it.startsWith(tok) || tok.startsWith(it) || fuzzyMatch(tok, it) }) {
                subtitleHits++
                score += 60
            }
        }

        if (titleHits >= tokens.size && tokens.isNotEmpty()) {
            score += 2000
        } else if (titleHits + subtitleHits >= tokens.size && tokens.isNotEmpty()) {
            score += 1000
        }

        if (s == query) score += 200
        else if (s.contains(query)) score += 100

        // --- User signals (light boost — don't overwhelm API relevance) ---
        val vid = track.videoId ?: ""
        if (vid.isNotEmpty()) {
            if (vid in signals.favoriteIds) score += 300
            if (vid in signals.customPlaylistIds) score += 200
            if (vid in signals.historyIds) score += 100
        }

        // --- Offline availability boost ---
        val isDownloaded = vid.isNotEmpty() && OfflineAudioStore.hasOfflineAudio(requireContext(), vid)
        if (isDownloaded) score += 400

        return score
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a.length < 3 || b.length < 3) return false
        val lenDiff = kotlin.math.abs(a.length - b.length)
        if (lenDiff > 1) return false
        return levenshtein(a, b) <= 1
    }

    private fun suggestFuzzyMatch(tok: String, word: String): Boolean {
        if (tok.length < 3 || word.length < 3) return false
        val lenDiff = kotlin.math.abs(tok.length - word.length)
        val maxDist = if (tok.length >= 5) 2 else 1
        if (lenDiff > maxDist) return false
        return levenshtein(tok, word) <= maxDist
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    private fun bindFeaturedTrack(track: YouTubeMusicService.TrackResult) {
        tvFeaturedTitle.text = track.title
        val typeLabel = searchTypeLabel(track)
        if (typeLabel.isEmpty()) {
            tvFeaturedSubtitle.text = track.subtitle
        } else {
            tvFeaturedSubtitle.text = if (track.subtitle.isEmpty()) typeLabel else "$typeLabel • ${track.subtitle}"
        }
        
        val isDownloaded = track.videoId.isNotEmpty() && OfflineAudioStore.hasOfflineAudio(requireContext(), track.videoId)
        if (isDownloaded) {
            ivFeaturedOfflineIndicator.visibility = View.VISIBLE
            ivFeaturedOfflineIndicator.setImageResource(R.drawable.ic_check_small)
            ivFeaturedOfflineIndicator.setBackgroundResource(R.drawable.bg_offline_state_filled_primary)
            ivFeaturedOfflineIndicator.setColorFilter(ContextCompat.getColor(requireContext(), R.color.surface_dark))
        } else {
            ivFeaturedOfflineIndicator.visibility = View.GONE
        }
        
        loadArtworkInto(ivFeaturedThumb, track.thumbnailUrl, track.videoId)
        llFeaturedResult.setOnClickListener { onTrackClicked(track) }
        llFeaturedResult.setOnLongClickListener {
            showTrackOptionsBottomSheet(track, it)
            true
        }
    }

    private fun onTrackClicked(track: YouTubeMusicService.TrackResult) {
        if ("playlist".equals(track.resultType, ignoreCase = true)) {
            val intent = Intent(requireContext(), MainActivity::class.java).apply {
                action = MainActivity.ACTION_PLAY_FROM_SEARCH
                putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
                putExtra(EXTRA_RESULT_CONTENT_ID, track.contentId ?: "")
                putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
                putExtra(EXTRA_RESULT_SUBTITLE, track.subtitle ?: "")
                putExtra(EXTRA_RESULT_THUMBNAIL, track.thumbnailUrl ?: "")
            }
            if (requireActivity() is MainActivity) {
                (requireActivity() as MainActivity).handlePlayFromSearchIntent(intent)
            }
            return
        }

        // First, try to find the track in a local playlist for full playlist playback
        val videoId = track.videoId ?: ""
        val playlistQueue = buildPlaylistQueueForTrack(videoId)

        val tracksArray: JSONArray
        if (playlistQueue.length() > 0) {
            // Use the playlist queue (contains the full playlist starting from selected track)
            tracksArray = playlistQueue
        } else {
            // Fall back to search results as queue
            val allResults = listOfNotNull(featuredTrack) + tracks
            val videoResults = allResults.filter { it.videoId?.isNotEmpty() == true }
            tracksArray = JSONArray()
            videoResults.forEach {
                val obj = JSONObject()
                obj.put("resultType", it.resultType)
                obj.put("videoId", it.videoId)
                obj.put("contentId", it.contentId)
                obj.put("title", it.title)
                obj.put("subtitle", it.subtitle)
                obj.put("thumbnailUrl", it.thumbnailUrl)
                tracksArray.put(obj)
            }
        }

        val playbackIntent = Intent(requireContext(), MainActivity::class.java).apply {
            action = MainActivity.ACTION_PLAY_FROM_SEARCH
            putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
            putExtra(EXTRA_RESULT_VIDEO_ID, track.videoId ?: "")
            putExtra(EXTRA_RESULT_CONTENT_ID, track.contentId ?: "")
            putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
            putExtra(EXTRA_RESULT_SUBTITLE, track.subtitle ?: "")
            putExtra(EXTRA_RESULT_THUMBNAIL, track.thumbnailUrl ?: "")
            putExtra(EXTRA_RESULT_TRACKS_JSON, tracksArray.toString())
        }
        
        // Save to recent searches only when user plays a track from search results
        if (activeSearchQuery.isNotEmpty()) {
            rememberRecentSearchQuery(activeSearchQuery, track)
        }

        if (requireActivity() is MainActivity) {
            (requireActivity() as MainActivity).handlePlayFromSearchIntent(playbackIntent)
        }
    }

    private fun playTrackDirectly(track: YouTubeMusicService.TrackResult) {
        val videoId = track.videoId ?: ""
        val tracksArray = buildPlaylistQueueForTrack(videoId)

        // If no playlist was found, fall back to just the single track
        if (tracksArray.length() == 0) {
            val obj = JSONObject()
            obj.put("resultType", track.resultType)
            obj.put("videoId", track.videoId)
            obj.put("contentId", track.contentId)
            obj.put("title", track.title)
            obj.put("subtitle", track.subtitle)
            obj.put("thumbnailUrl", track.thumbnailUrl)
            tracksArray.put(obj)
        }

        val playbackIntent = Intent(requireContext(), MainActivity::class.java).apply {
            action = MainActivity.ACTION_PLAY_FROM_SEARCH
            putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
            putExtra(EXTRA_RESULT_VIDEO_ID, track.videoId ?: "")
            putExtra(EXTRA_RESULT_CONTENT_ID, track.contentId ?: "")
            putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
            putExtra(EXTRA_RESULT_SUBTITLE, track.subtitle ?: "")
            putExtra(EXTRA_RESULT_THUMBNAIL, track.thumbnailUrl ?: "")
            putExtra(EXTRA_RESULT_TRACKS_JSON, tracksArray.toString())
        }

        if (requireActivity() is MainActivity) {
            (requireActivity() as MainActivity).handlePlayFromSearchIntent(playbackIntent)
        }
    }

    /**
     * Searches through all local playlists to find the best one containing the given videoId.
     * Priority order: Favoritos > Custom playlists > Cached YT playlists > Playback history.
     * Returns the full playlist as a JSONArray with the selected track positioned first,
     * or an empty JSONArray if no playlist contains the track.
     */
    private fun buildPlaylistQueueForTrack(videoId: String): JSONArray {
        if (videoId.isEmpty()) return JSONArray()
        val ctx = context ?: return JSONArray()

        // 1. Check Favoritos
        try {
            val favs = FavoritesPlaylistStore.loadFavorites(ctx)
            if (favs.any { it.videoId == videoId }) {
                return buildQueueFromFavoriteTracks(favs, videoId)
            }
        } catch (_: Exception) {}

        // 2. Check custom playlists
        try {
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                val playlistTracks = CustomPlaylistsStore.getTracksFromPlaylist(ctx, name)
                if (playlistTracks.any { it.videoId == videoId }) {
                    return buildQueueFromFavoriteTracks(playlistTracks, videoId)
                }
            }
        } catch (_: Exception) {}

        // 3. Check cached YouTube playlists
        try {
            val cache = ctx.getSharedPreferences("streaming_cache", Context.MODE_PRIVATE)
            for ((key, value) in cache.all) {
                if (key.startsWith("playlist_tracks_data_") && value is String) {
                    val arr = org.json.JSONArray(value)
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.getJSONObject(i).optString("videoId") == videoId) {
                            found = true
                            break
                        }
                    }
                    if (found) {
                        return buildQueueFromCachedPlaylist(arr, videoId)
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. Check playback history queue
        try {
            val snapshot = PlaybackHistoryStore.load(ctx)
            if (snapshot.queue.any { it.videoId == videoId }) {
                return buildQueueFromHistoryTracks(snapshot.queue, videoId)
            }
        } catch (_: Exception) {}

        return JSONArray()
    }

    private fun buildQueueFromFavoriteTracks(
        tracks: List<FavoritesPlaylistStore.FavoriteTrack>,
        selectedVideoId: String
    ): JSONArray {
        val result = JSONArray()
        // Place selected track first, then the rest in order
        val selectedIdx = tracks.indexOfFirst { it.videoId == selectedVideoId }
        if (selectedIdx < 0) return result

        val ordered = tracks.subList(selectedIdx, tracks.size) + tracks.subList(0, selectedIdx)
        for (t in ordered) {
            val obj = JSONObject()
            obj.put("resultType", "video")
            obj.put("videoId", t.videoId)
            obj.put("contentId", t.videoId)
            obj.put("title", t.title)
            obj.put("subtitle", t.artist)
            obj.put("thumbnailUrl", t.imageUrl)
            result.put(obj)
        }
        return result
    }

    private fun buildQueueFromCachedPlaylist(arr: org.json.JSONArray, selectedVideoId: String): JSONArray {
        val result = JSONArray()
        var selectedIdx = -1
        val items = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(obj)
            if (obj.optString("videoId") == selectedVideoId && selectedIdx < 0) {
                selectedIdx = i
            }
        }
        if (selectedIdx < 0) return result

        val ordered = items.subList(selectedIdx, items.size) + items.subList(0, selectedIdx)
        for (cached in ordered) {
            val obj = JSONObject()
            obj.put("resultType", "video")
            obj.put("videoId", cached.optString("videoId"))
            obj.put("contentId", cached.optString("videoId"))
            obj.put("title", cached.optString("title"))
            obj.put("subtitle", cached.optString("artist"))
            obj.put("thumbnailUrl", cached.optString("imageUrl"))
            result.put(obj)
        }
        return result
    }

    private fun buildQueueFromHistoryTracks(
        tracks: List<PlaybackHistoryStore.QueueTrack>,
        selectedVideoId: String
    ): JSONArray {
        val result = JSONArray()
        val selectedIdx = tracks.indexOfFirst { it.videoId == selectedVideoId }
        if (selectedIdx < 0) return result

        val ordered = tracks.subList(selectedIdx, tracks.size) + tracks.subList(0, selectedIdx)
        for (t in ordered) {
            val obj = JSONObject()
            obj.put("resultType", "video")
            obj.put("videoId", t.videoId)
            obj.put("contentId", t.videoId)
            obj.put("title", t.title)
            obj.put("subtitle", t.artist)
            obj.put("thumbnailUrl", t.imageUrl)
            result.put(obj)
        }
        return result
    }

    private fun showTrackOptionsBottomSheet(track: YouTubeMusicService.TrackResult, anchor: View) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)

        val ctx = requireContext()
        val videoId = track.videoId ?: ""
        val hasOfflineAudio = videoId.isNotEmpty()
            && OfflineAudioStore.hasOfflineAudio(ctx, videoId)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_track_options, null)
        dialog.setContentView(view)

        // Header
        val tvTitle = view.findViewById<TextView>(R.id.tvBsTrackTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvBsTrackSubtitle)
        val ivArt = view.findViewById<ImageView>(R.id.ivBsTrackArt)
        val ivBsOffline = view.findViewById<ImageView>(R.id.ivBsOfflineState)

        tvTitle.text = if (track.title.isNullOrEmpty()) "Tema" else track.title
        val typeLabel = searchTypeLabel(track)
        tvSubtitle.text = if (typeLabel.isEmpty()) track.subtitle
            else if (track.subtitle.isEmpty()) typeLabel else "$typeLabel • ${track.subtitle}"
        loadArtworkInto(ivArt, track.thumbnailUrl, videoId)
        ivBsOffline?.visibility = if (hasOfflineAudio) View.VISIBLE else View.GONE

        // Top row slot 1: Reproducir
        val btnPlayNext = view.findViewById<View>(R.id.btnBsPlayNext)
        val ivPlayNext = view.findViewById<ImageView>(R.id.ivBsPlayNextIcon)
        val tvPlayNext = view.findViewById<TextView>(R.id.tvBsPlayNextLabel)
        btnPlayNext.visibility = View.VISIBLE
        ivPlayNext.setImageResource(R.drawable.ic_player_play)
        tvPlayNext.text = "Reproducir"
        btnPlayNext.setOnClickListener {
            dialog.dismiss()
            onTrackClicked(track)
        }

        // Top row slot 2: Descargar / Eliminar descarga
        val btnAddPrimary = view.findViewById<View>(R.id.btnBsAddPrimary)
        val ivAddPrimary = view.findViewById<ImageView>(R.id.ivBsAddPrimary)
        val tvAddPrimary = view.findViewById<TextView>(R.id.tvBsAddPrimary)
        btnAddPrimary.visibility = View.VISIBLE
        if (hasOfflineAudio) {
            ivAddPrimary.setImageResource(R.drawable.ic_delete_modern)
            tvAddPrimary.text = "Eliminar\ndescarga"
        } else {
            ivAddPrimary.setImageResource(R.drawable.ic_download_bold)
            tvAddPrimary.text = "Descargar"
        }
        btnAddPrimary.setOnClickListener {
            dialog.dismiss()
            if (hasOfflineAudio) {
                if (videoId.isNotEmpty()) {
                    OfflineAudioStore.deleteOfflineAudio(ctx.applicationContext, arrayListOf(videoId))
                }
            } else {
                downloadTrackFromSearch(track)
            }
        }

        // Top row slot 3: Compartir
        val btnShare = view.findViewById<View>(R.id.btnBsShare)
        val ivShare = view.findViewById<ImageView>(R.id.ivBsShareIcon)
        val tvShare = view.findViewById<TextView>(R.id.tvBsShareLabel)
        btnShare.visibility = View.VISIBLE
        ivShare.setImageResource(R.drawable.ic_playlist_share)
        tvShare.text = "Compartir"
        btnShare.setOnClickListener {
            dialog.dismiss()
            shareTrack(track)
        }

        // Row: Reproducir a continuación
        val btnPlayPlaylist = view.findViewById<View>(R.id.btnBsPlayPlaylist)
        val ivPlayNextRow = btnPlayPlaylist.findViewById<ImageView>(R.id.ivBsPlayPlaylist)
        val tvPlayNextRow = btnPlayPlaylist.findViewById<TextView>(R.id.tvBsPlayPlaylist)
        btnPlayPlaylist.visibility = View.VISIBLE
        ivPlayNextRow.setImageResource(R.drawable.ic_bs_play_next_yt)
        tvPlayNextRow.text = "Reproducir a continuación"
        btnPlayPlaylist.setOnClickListener {
            dialog.dismiss()
            addToQueue(track, true)
        }

        // Row: Añadir a playlist
        val btnFavorite = view.findViewById<View>(R.id.btnBsFavorite)
        val ivFav = btnFavorite.findViewById<ImageView>(R.id.ivBsFavorite)
        val tvFav = btnFavorite.findViewById<TextView>(R.id.tvBsFavorite)
        btnFavorite.visibility = View.VISIBLE
        ivFav.setImageResource(R.drawable.ic_stream_queue_add)
        tvFav.text = "Añadir a playlist"
        btnFavorite.setOnClickListener {
            dialog.dismiss()
            val lspk = CustomPlaylistsStore.getLastSavedPlaylistKey(requireContext())
            val lspn = CustomPlaylistsStore.getLastSavedPlaylistName(requireContext())
            if (lspk != null && lspn != null) {
                addTrackToPlaylistByKey(lspk, track)
                showStatusBarSearch("Se guardó en $lspn") {
                    CustomPlaylistsStore.clearLastSavedPlaylist(requireContext())
                    showSaveToPlaylistSheet(track)
                }
            } else {
                showSaveToPlaylistSheet(track)
            }
        }

        // Row: Iniciar radio
        val btnPlay = view.findViewById<View>(R.id.btnBsPlay)
        val ivRadio = btnPlay.findViewById<ImageView>(R.id.ivBsPlay)
        val tvRadio = btnPlay.findViewById<TextView>(R.id.tvBsPlayLabel)
        btnPlay.visibility = View.VISIBLE
        ivRadio.setImageResource(R.drawable.ic_bs_radio)
        tvRadio.text = "Iniciar radio"
        btnPlay.setOnClickListener {
            dialog.dismiss()
            startRadioForTrack(track)
        }

        // Row: Agregar a la fila
        val btnAddToQueue = view.findViewById<View>(R.id.btnBsAddToQueue)
        val ivAddToQueue = btnAddToQueue.findViewById<ImageView>(R.id.ivBsAddToQueue)
        val tvAddToQueue = btnAddToQueue.findViewById<TextView>(R.id.tvBsAddToQueue)
        btnAddToQueue.visibility = View.VISIBLE
        ivAddToQueue.setImageResource(R.drawable.ic_bs_add_queue_yt)
        tvAddToQueue.text = "Agregar a la fila"
        btnAddToQueue.setOnClickListener {
            dialog.dismiss()
            addToQueue(track, false)
        }

        // Borrar de la playlist — show if track is in at least one local playlist
        val allPlaylistKeys = mutableListOf<String>().apply {
            add(FavoritesPlaylistStore.PLAYLIST_ID)
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx))
                add(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name)
        }
        val containingPlaylists = allPlaylistKeys.filter { isTrackInPlaylist(ctx, videoId, it) }
        val btnBsDownload = view.findViewById<View>(R.id.btnBsDownload)
        if (containingPlaylists.isNotEmpty()) {
            btnBsDownload.findViewById<ImageView>(R.id.ivBsDownload).setImageResource(R.drawable.ic_delete_modern)
            btnBsDownload.findViewById<TextView>(R.id.tvBsDownload).text = "Borrar de la playlist"
            btnBsDownload.visibility = View.VISIBLE
            btnBsDownload.setOnClickListener {
                dialog.dismiss()
                if (containingPlaylists.size == 1) {
                    val playlistKey = containingPlaylists[0]
                    val playlistName = resolvePlaylistName(playlistKey)
                    removeTrackFromPlaylistByKey(playlistKey, videoId)
                    showStatusBarSearchWithUndo(
                        "${track.title?.takeIf { it.isNotEmpty() } ?: "Tema"} eliminado de $playlistName",
                        playlistKey,
                        track
                    )
                } else {
                    showSaveToPlaylistSheet(track)
                }
            }
        } else {
            btnBsDownload.visibility = View.GONE
        }

        dialog.behavior.skipCollapsed = true
        dialog.behavior.isFitToContents = true
        dialog.setOnShowListener { d ->
            val bottomSheet = (d as com.google.android.material.bottomsheet.BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val sheetParent = view.parent as? View
            sheetParent?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomSheet.setBackgroundResource(android.R.color.transparent)
        }
        dialog.show()
    }

    private fun downloadTrackFromSearch(track: YouTubeMusicService.TrackResult) {
        val videoId = track.videoId ?: return
        if (videoId.isEmpty()) return
        val ctx = requireContext()
        val title = track.title ?: "Tema"
        val artist = track.subtitle ?: ""
        val input = Data.Builder()
            .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, "search")
            .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, "Búsqueda")
            .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, arrayOf(videoId))
            .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, arrayOf(title))
            .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, arrayOf(artist))
            .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, arrayOf("--:--"))
            .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
            .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
            .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
            .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
            .build()
        val prefs = ctx.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val allowMobile = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker::class.java)
            .setInputData(input)
            .setConstraints(constraints)
            .addTag("offline_search_dl_$videoId")
            .build()
        WorkManager.getInstance(ctx).enqueue(request)
    }

    private fun showSaveToPlaylistSheet(track: YouTubeMusicService.TrackResult) {
        if (!isAdded()) return
        val ctx = requireContext()
        val saveDialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_save_to_playlist, null)
        saveDialog.setContentView(sheet)

        var lastAddedKey: String? = null
        var lastAddedName: String? = null
        var didRemove = false

        sheet.findViewById<ImageView>(R.id.ivSaveClose).setOnClickListener { saveDialog.dismiss() }
        sheet.findViewById<View>(R.id.btnSaveCancel).setOnClickListener { saveDialog.dismiss() }
        sheet.findViewById<View>(R.id.btnSaveConfirm).setOnClickListener {
            saveDialog.dismiss()
            if (lastAddedKey != null && lastAddedName != null) {
                CustomPlaylistsStore.setLastSavedPlaylist(requireContext(), lastAddedKey, lastAddedName)
                showStatusBarSearch("Se guardó en $lastAddedName") {
                    CustomPlaylistsStore.clearLastSavedPlaylist(requireContext())
                    showSaveToPlaylistSheet(track)
                }
            } else if (didRemove) {
                showStatusBarSearch("Se eliminó correctamente") { showSaveToPlaylistSheet(track) }
            }
        }

        val llList = sheet.findViewById<android.widget.LinearLayout>(R.id.llSavePlaylistList)
        llList.removeAllViews()

        val density = ctx.resources.displayMetrics.density
        val thumbSizePx = (48 * density).toInt()

        val favs = FavoritesPlaylistStore.loadFavorites(ctx)
        val favUrls = mutableListOf<String>()
        for (f in favs) {
            if (!f.imageUrl.isNullOrEmpty() && f.imageUrl !in favUrls) {
                favUrls.add(f.imageUrl)
                if (favUrls.size >= 4) break
            }
        }

        // Favorites row
        run {
            val row = layoutInflater.inflate(R.layout.item_save_playlist_row, llList, false)
            val ivThumb = row.findViewById<ImageView>(R.id.ivSavePlaylistThumb)
            val tvName = row.findViewById<TextView>(R.id.tvSavePlaylistName)
            val tvCount = row.findViewById<TextView>(R.id.tvSavePlaylistCount)
            val ivCheck = row.findViewById<ImageView>(R.id.ivSaveCheck)
            tvName.text = FavoritesPlaylistStore.PLAYLIST_TITLE
            tvCount.text = "${favs.size} pistas"
            if (favUrls.size >= 4) {
                PlaylistGridArtLoader.load(ivThumb, favUrls, thumbSizePx)
            } else if (favUrls.isNotEmpty()) {
                loadArtworkInto(ivThumb, favUrls[0])
            }
            val isIn = isTrackInPlaylist(ctx, track.videoId ?: "", FavoritesPlaylistStore.PLAYLIST_ID)
            ivCheck?.visibility = if (isIn) View.VISIBLE else View.GONE
            var checked = isIn
            row.setOnClickListener {
                if (checked) {
                    removeTrackFromPlaylistByKey(FavoritesPlaylistStore.PLAYLIST_ID, track.videoId ?: "")
                    checked = false
                    didRemove = true
                    ivCheck?.visibility = View.GONE
                    if (lastAddedKey == FavoritesPlaylistStore.PLAYLIST_ID) {
                        lastAddedKey = null
                        lastAddedName = null
                    }
                } else {
                    addTrackToPlaylistByKey(FavoritesPlaylistStore.PLAYLIST_ID, track)
                    checked = true
                    ivCheck?.visibility = View.VISIBLE
                    lastAddedKey = FavoritesPlaylistStore.PLAYLIST_ID
                    lastAddedName = FavoritesPlaylistStore.PLAYLIST_TITLE
                }
                val newCount = getPlaylistTrackCount(ctx, FavoritesPlaylistStore.PLAYLIST_ID)
                tvCount.text = "$newCount pistas"
            }
            llList.addView(row)
        }

        // Custom playlists
        val customNames = CustomPlaylistsStore.getAllPlaylistNames(ctx)
        for (name in customNames) {
            val playlistKey = CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX + name
            val customTracks = CustomPlaylistsStore.getTracksFromPlaylist(ctx, name)
            val urls = mutableListOf<String>()
            for (t in customTracks) {
                if (!t.imageUrl.isNullOrEmpty() && t.imageUrl !in urls) {
                    urls.add(t.imageUrl)
                    if (urls.size >= 4) break
                }
            }

            val row = layoutInflater.inflate(R.layout.item_save_playlist_row, llList, false)
            val ivThumb = row.findViewById<ImageView>(R.id.ivSavePlaylistThumb)
            val tvName = row.findViewById<TextView>(R.id.tvSavePlaylistName)
            val tvCount = row.findViewById<TextView>(R.id.tvSavePlaylistCount)
            val ivCheck = row.findViewById<ImageView>(R.id.ivSaveCheck)
            tvName.text = name
            tvCount.text = "${customTracks.size} pistas"
            if (urls.size >= 4) {
                PlaylistGridArtLoader.load(ivThumb, urls, thumbSizePx)
            } else if (urls.isNotEmpty()) {
                loadArtworkInto(ivThumb, urls[0])
            }
            val isIn = isTrackInPlaylist(ctx, track.videoId ?: "", playlistKey)
            ivCheck?.visibility = if (isIn) View.VISIBLE else View.GONE
            var checked = isIn
            row.setOnClickListener {
                if (checked) {
                    removeTrackFromPlaylistByKey(playlistKey, track.videoId ?: "")
                    checked = false
                    didRemove = true
                    ivCheck?.visibility = View.GONE
                    if (lastAddedKey == playlistKey) {
                        lastAddedKey = null
                        lastAddedName = null
                    }
                } else {
                    addTrackToPlaylistByKey(playlistKey, track)
                    checked = true
                    ivCheck?.visibility = View.VISIBLE
                    lastAddedKey = playlistKey
                    lastAddedName = name
                }
                val newCount = getPlaylistTrackCount(ctx, playlistKey)
                tvCount.text = "$newCount pistas"
            }
            llList.addView(row)
        }

        saveDialog.behavior.skipCollapsed = true
        saveDialog.behavior.isFitToContents = true
        saveDialog.setOnShowListener { d ->
            val bottomSheet = (d as com.google.android.material.bottomsheet.BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val sheetParent = sheet.parent as? View
            sheetParent?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomSheet.setBackgroundResource(android.R.color.transparent)
        }
        saveDialog.show()
    }

    private fun showStatusBarSearch(message: String, onChangeClick: (() -> Unit)? = null) {
        if (!isAdded) return
        val activity = requireActivity() as? MainActivity ?: return
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val existing = rootView.findViewWithTag<View>("saved_bar")
        if (existing != null) rootView.removeView(existing)

        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(requireContext()).apply {
            tag = "saved_bar"
            id = View.generateViewId()
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#FF1E1E1E"))
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 8 * density
        }

        val tvMsg = TextView(requireContext()).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvMsg)

        if (onChangeClick != null) {
            val btnChange = TextView(requireContext()).apply {
                text = "Cambiar"
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((16 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    rootView.removeView(bar)
                    onChangeClick()
                }
            }
            bar.addView(btnChange)
        }

        val barBottomMargin = computeSnackbarBottomMargin(activity, density)
        val flp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            this.bottomMargin = barBottomMargin
        }
        rootView.addView(bar, flp)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bar.parent != null) {
                (bar.parent as android.view.ViewGroup).removeView(bar)
            }
        }, 4000L)
    }

    private fun computeSnackbarBottomMargin(activity: android.app.Activity, density: Float): Int {
        var margin = (8 * density).toInt()
        val bottomNav = activity.findViewById<View>(R.id.bottomNavigation)
        if (bottomNav != null && bottomNav.visibility == View.VISIBLE) {
            margin += bottomNav.height
        }
        val miniPlayer = activity.findViewById<View>(R.id.llGlobalMiniPlayer)
        if (miniPlayer != null && miniPlayer.visibility == View.VISIBLE) {
            margin += miniPlayer.height
        }
        return margin
    }

    private fun resolvePlaylistName(playlistKey: String): String {
        return if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            FavoritesPlaylistStore.PLAYLIST_TITLE
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
        } else {
            "playlist"
        }
    }

    private fun showStatusBarSearchWithUndo(message: String, playlistKey: String, track: YouTubeMusicService.TrackResult) {
        if (!isAdded) return
        val activity = requireActivity() as? MainActivity ?: return
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val existing = rootView.findViewWithTag<View>("saved_bar")
        if (existing != null) rootView.removeView(existing)

        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(requireContext()).apply {
            tag = "saved_bar"
            id = View.generateViewId()
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#FF1E1E1E"))
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 8 * density
        }

        val tvMsg = TextView(requireContext()).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvMsg)

        val btnUndo = TextView(requireContext()).apply {
            text = "Deshacer"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * density).toInt(), 0, 0, 0)
            setOnClickListener {
                rootView.removeView(bar)
                addTrackToPlaylistByKey(playlistKey, track)
                Toast.makeText(requireContext(), "Restaurado en ${resolvePlaylistName(playlistKey)}", Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(btnUndo)

        val barBottomMargin = computeSnackbarBottomMargin(activity, density)
        val flp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            this.bottomMargin = barBottomMargin
        }
        rootView.addView(bar, flp)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bar.parent != null) {
                (bar.parent as android.view.ViewGroup).removeView(bar)
            }
        }, 4000L)
    }

    private fun isTrackInPlaylist(ctx: android.content.Context, videoId: String, playlistKey: String): Boolean {
        if (videoId.isEmpty()) return false
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            return FavoritesPlaylistStore.isFavorite(ctx, videoId)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            return CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).any { it.videoId == videoId }
        }
        return false
    }

    private fun getPlaylistTrackCount(ctx: android.content.Context, playlistKey: String): Int {
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            return FavoritesPlaylistStore.loadFavorites(ctx).size
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            return CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).size
        }
        return 0
    }

    private fun removeTrackFromPlaylistByKey(playlistKey: String, videoId: String) {
        if (videoId.isEmpty()) return
        val ctx = requireContext()
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            FavoritesPlaylistStore.removeFavorite(ctx, videoId)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            CustomPlaylistsStore.removeTrackFromPlaylist(ctx, name, videoId)
        }
    }

    private fun addTrackToPlaylistByKey(playlistKey: String, track: YouTubeMusicService.TrackResult) {
        if (track.videoId.isNullOrEmpty()) return
        val ctx = requireContext()
        val title = track.title?.takeIf { it.isNotEmpty() } ?: "Tema"
        val artist = track.subtitle ?: ""
        val imageUrl = track.thumbnailUrl ?: ""
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            FavoritesPlaylistStore.upsertFavorite(ctx, track.videoId, title, artist, "--:--", imageUrl)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            CustomPlaylistsStore.addTrackToPlaylist(ctx, name, track.videoId, title, artist, "--:--", imageUrl)
        }
        maybeEnqueueOfflineDownloadForTrack(playlistKey, track.videoId, title, artist)
    }

    private fun maybeEnqueueOfflineDownloadForTrack(playlistKey: String, videoId: String, title: String, artist: String) {
        if (!isAdded || videoId.isEmpty()) return
        val ctx = requireContext()
        val cachePrefs = ctx.getSharedPreferences("streaming_cache", android.app.Activity.MODE_PRIVATE)
        val offlineAuto = cachePrefs.getBoolean("playlist_offline_auto_$playlistKey", false)
        if (!offlineAuto) return
        if (OfflineAudioStore.hasOfflineAudio(ctx, videoId)) return
        try {
            val inputData = androidx.work.Data.Builder()
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, playlistKey)
                .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE, playlistKey)
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_VIDEO_IDS, arrayOf(videoId))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_TITLES, arrayOf(title))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_ARTISTS, arrayOf(artist))
                .putStringArray(OfflinePlaylistDownloadWorker.INPUT_DURATIONS, arrayOf("--:--"))
                .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, 0)
                .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, 1)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, true)
                .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, true)
                .build()
            val prefs = ctx.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, android.content.Context.MODE_PRIVATE)
            val allowMobile = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(if (allowMobile) androidx.work.NetworkType.CONNECTED else androidx.work.NetworkType.UNMETERED)
                .build()
            val request = androidx.work.OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker::class.java)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag("offline_add_track_$videoId")
                .build()
            androidx.work.WorkManager.getInstance(ctx).enqueue(request)
        } catch (_: Exception) {}
    }

    private fun addToQueue(track: YouTubeMusicService.TrackResult, playNext: Boolean) {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            action = if (playNext) MainActivity.ACTION_PLAY_NEXT else MainActivity.ACTION_ADD_TO_QUEUE
            putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
            putExtra(EXTRA_RESULT_VIDEO_ID, track.videoId ?: "")
            putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
            putExtra(EXTRA_RESULT_SUBTITLE, track.subtitle ?: "")
            putExtra(EXTRA_RESULT_THUMBNAIL, track.thumbnailUrl ?: "")
        }
        if (requireActivity() is MainActivity) {
            (requireActivity() as MainActivity).handlePlayFromSearchIntent(intent)
        }
    }

    private fun shareTrack(track: YouTubeMusicService.TrackResult) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${track.videoId}")
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir"))
    }

    private fun startRadioForTrack(track: YouTubeMusicService.TrackResult) {
        val videoId = track.videoId ?: return
        if (videoId.isEmpty()) return
        val radioPlaylistId = "RDAMVM$videoId"
        val radioTitle = "Radio: ${track.title?.takeIf { it.isNotEmpty() } ?: "Tema"}"
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            action = MainActivity.ACTION_PLAY_FROM_SEARCH
            putExtra(EXTRA_RESULT_TYPE, "playlist")
            putExtra(EXTRA_RESULT_CONTENT_ID, radioPlaylistId)
            putExtra(EXTRA_RESULT_TITLE, radioTitle)
            putExtra(EXTRA_RESULT_SUBTITLE, track.subtitle ?: "")
            putExtra(EXTRA_RESULT_THUMBNAIL, track.thumbnailUrl ?: "")
        }
        if (requireActivity() is MainActivity) {
            (requireActivity() as MainActivity).handlePlayFromSearchIntent(intent)
        }
    }

    private fun setSearchLoadingState(loading: Boolean, msg: String) {
        searching = loading
        if (loading) {
            showModuleLoadingOverlay()
            tvSearchState.visibility = View.GONE
            llSearchState.visibility = View.VISIBLE
        } else {
            if (msg.isNotEmpty()) {
                tvSearchState.text = msg
                tvSearchState.visibility = View.VISIBLE
                llSearchState.visibility = View.VISIBLE
            } else {
                tvSearchState.visibility = View.GONE
                llSearchState.visibility = View.GONE
            }
        }
    }

    private fun restoreRecentSearchQueries() {
        val prefs = requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
        // Try to load extended data first
        val dataJson = prefs.getString(PREF_RECENT_SEARCH_DATA, "[]")
        recentSearchData.clear()
        try {
            val array = JSONArray(dataJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                recentSearchData.add(RecentSearch(
                    query = obj.optString("query", ""),
                    videoId = obj.optString("videoId", ""),
                    title = obj.optString("title", ""),
                    thumbnail = obj.optString("thumbnail", ""),
                    artist = obj.optString("artist", "")
                ))
            }
        } catch (e: Exception) {
            // Fallback: try old format
            val oldJson = prefs.getString(PREF_RECENT_SEARCH_QUERIES, "[]")
            try {
                val array = JSONArray(oldJson)
                for (i in 0 until array.length()) {
                    recentSearchData.add(RecentSearch(query = array.getString(i)))
                }
            } catch (e2: Exception) {}
        }
        // Back-fill missing artist for entries that have a videoId
        var dirty = false
        for (i in recentSearchData.indices) {
            val entry = recentSearchData[i]
            if (entry.videoId.isNotEmpty() && entry.artist.isEmpty()) {
                val resolved = resolveArtistForVideoId(entry.videoId)
                if (resolved.isNotEmpty()) {
                    recentSearchData[i] = entry.copy(artist = resolved)
                    dirty = true
                }
            }
        }
        if (dirty) saveRecentSearchQueries()
    }

    private fun rememberRecentSearchQuery(query: String, firstResult: YouTubeMusicService.TrackResult? = null) {
        val norm = query.trim()
        if (norm.isEmpty()) return
        
        val newSearch = RecentSearch(
            query = norm,
            videoId = firstResult?.videoId ?: "",
            title = firstResult?.title ?: "",
            thumbnail = firstResult?.thumbnailUrl ?: "",
            artist = firstResult?.subtitle ?: ""
        )
        
        recentSearchData.run {
            removeAll { it.query == norm }
            add(0, newSearch)
            if (size > SEARCH_SUGGESTION_RECENT_LIMIT) removeAt(size - 1)
        }
        saveRecentSearchQueries()
    }

    private fun saveRecentSearchQueries() {
        val array = JSONArray()
        recentSearchData.forEach { search ->
            val obj = JSONObject()
            obj.put("query", search.query)
            obj.put("videoId", search.videoId)
            obj.put("title", search.title)
            obj.put("thumbnail", search.thumbnail)
            obj.put("artist", search.artist)
            array.put(obj)
        }
        requireContext().getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE).edit()
            .putString(PREF_RECENT_SEARCH_DATA, array.toString())
            .apply()
        saveRecentSearchesToFirebase()
    }

    private fun saveRecentSearchesToFirebase() {
        val ctx = context ?: return
        if (!AuthManager.getInstance(ctx).isSignedIn()) return
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val searches = recentSearchData.map { search ->
            hashMapOf(
                "query" to search.query,
                "videoId" to search.videoId,
                "title" to search.title,
                "thumbnail" to search.thumbnail,
                "artist" to search.artist
            )
        }
        db.collection("users").document(uid)
            .collection("sleppify").document("recent_searches")
            .set(hashMapOf("searches" to searches))
            .addOnFailureListener { Log.e("SearchFragment", "Failed to save recent searches to Firebase", it) }
    }

    private fun loadRecentSearchesFromFirebase() {
        val ctx = context ?: return
        if (!AuthManager.getInstance(ctx).isSignedIn()) return
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        db.collection("users").document(uid)
            .collection("sleppify").document("recent_searches")
            .get()
            .addOnSuccessListener { doc ->
                if (!isAdded || doc == null || !doc.exists()) return@addOnSuccessListener
                val list = doc.get("searches") as? List<*> ?: return@addOnSuccessListener
                val parsed = list.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    RecentSearch(
                        query = map["query"] as? String ?: return@mapNotNull null,
                        videoId = map["videoId"] as? String ?: "",
                        title = map["title"] as? String ?: "",
                        thumbnail = map["thumbnail"] as? String ?: "",
                        artist = map["artist"] as? String ?: ""
                    )
                }
                val localHasImages = recentSearchData.any { it.thumbnail.isNotEmpty() }
                if (parsed.isNotEmpty() && (recentSearchData.isEmpty() || !localHasImages)) {
                    recentSearchData.clear()
                    recentSearchData.addAll(parsed)
                    saveRecentSearchQueries()
                    refreshSearchSuggestions(etSearchQuery.text?.toString()?.trim() ?: "")
                }
            }
    }

    private fun showDeleteSearchDialog(query: String) {
        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar búsqueda")
            .setMessage("¿Eliminar \"$query\" de las búsquedas recientes?")
            .setPositiveButton("Sí") { _, _ ->
                recentSearchData.removeAll { it.query == query }
                saveRecentSearchQueries()
                saveRecentSearchesToFirebase()
                refreshSearchSuggestions(etSearchQuery.text?.toString()?.trim() ?: "")
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun refreshSearchSuggestions(draft: String?) {
        suggestionsJob?.cancel()
        val norm = draft?.trim() ?: ""
        val recentSnapshot = recentSearchData.toList()
        val trackSnapshot = localTrackIndex.toList()
        suggestionsJob = lifecycleScope.launch {
            val items = kotlinx.coroutines.withContext(Dispatchers.Default) {
                poolSuggestionItems(norm, recentSnapshot, trackSnapshot)
            }
            if (!isAdded) return@launch
            (rvSearchSuggestions.adapter as? SuggestionsAdapter)?.updateItems(items)
            rvSearchSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun poolSuggestionItems(draft: String, recentSearchData: List<RecentSearch> = this.recentSearchData, localTrackIndex: List<FavoritesPlaylistStore.FavoriteTrack> = this.localTrackIndex): List<SuggestionItem> {
        val normDraft = normalizeForFilter(draft)
        val recentQueries = recentSearchData.map { it.query }

        val matchingRecent = recentSearchData.filter { candidate ->
            if (normDraft.isEmpty()) true
            else normalizeForFilter(candidate.query).let { it.contains(normDraft) || normDraft.contains(it) }
        }.take(SEARCH_SUGGESTION_RECENT_LIMIT)

        val smartSuggestions = buildSmartSuggestions(normDraft, recentQueries)

        val result = mutableListOf<SuggestionItem>()

        // Always show the raw query as the first row so the user can always
        // trigger an exact search, regardless of what suggestions follow.
        if (normDraft.isNotEmpty()) {
            result.add(SuggestionItem.Suggestion(draft.trim()))
        }

        // Text-based autocomplete: suggest full track titles/artists that match the typed text (with fuzzy fallback)
        if (normDraft.length >= 2 && localTrackIndex.isNotEmpty()) {
            val normDraftLower = normDraft.lowercase()
            val seenNorm = mutableSetOf<String>()
            val autocompleteCandidates = localTrackIndex
                .mapNotNull { track ->
                    val normTitle = normalizeForFilter(track.title)
                    val normArtist = normalizeForFilter(track.artist)
                    val titleMatch = normTitle.startsWith(normDraftLower) || (normDraftLower.length >= 3 && normTitle.split(WHITESPACE_REGEX).any { suggestFuzzyMatch(normDraftLower, it) })
                    val artistMatch = normArtist.startsWith(normDraftLower) || (normDraftLower.length >= 3 && normArtist.split(WHITESPACE_REGEX).any { suggestFuzzyMatch(normDraftLower, it) })
                    val label = if (titleMatch) normTitle else normArtist
                    val display = if (titleMatch) track.title.trim() else track.artist.trim()
                    if ((titleMatch || artistMatch) && label != normDraftLower && seenNorm.add(label)) {
                        display
                    } else null
                }
                .take(3)
            autocompleteCandidates.forEach { result.add(SuggestionItem.Autocomplete(it)) }
        }

        // Show recent images carousel only when bar is empty (no query typed)
        if (normDraft.isEmpty()) {
            val itemsWithImages = recentSearchData.filter { it.thumbnail.isNotEmpty() }.take(5)
            if (itemsWithImages.isNotEmpty()) {
                result.add(SuggestionItem.RecentImages(itemsWithImages))
            }
        }

        if (matchingRecent.isNotEmpty()) {
            if (normDraft.isNotEmpty()) result.add(SuggestionItem.Header("Búsquedas recientes"))
            matchingRecent.forEach { result.add(SuggestionItem.Recent(it.query, it.thumbnail)) }
        }

        if (smartSuggestions.isNotEmpty() && normDraft.isEmpty()) {
            result.add(SuggestionItem.Header("Temas relacionados"))
            smartSuggestions.take(7).forEach { result.add(SuggestionItem.Suggestion(it)) }
        }

        if (normDraft.length >= 3 && localTrackIndex.isNotEmpty()) {
            val draftTokens = normDraft.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            val trackMatches = localTrackIndex
                .filter { track ->
                    val titleNorm = normalizeForFilter(track.title)
                    val artistNorm = normalizeForFilter(track.artist)
                    // Full-string contains (original fast path)
                    if (titleNorm.contains(normDraft) || artistNorm.contains(normDraft)) return@filter true
                    // Per-token prefix matching: each query token must be a prefix of some word in title or artist
                    val titleWords = titleNorm.split(WHITESPACE_REGEX)
                    val artistWords = artistNorm.split(WHITESPACE_REGEX)
                    val allWords = titleWords + artistWords
                    val hits = draftTokens.count { tok ->
                        allWords.any { w -> w.startsWith(tok) || suggestFuzzyMatch(tok, w) }
                    }
                    hits >= (draftTokens.size + 1) / 2
                }
                .sortedByDescending {
                    val titleNorm = normalizeForFilter(it.title)
                    val artistNorm = normalizeForFilter(it.artist)
                    when {
                        titleNorm.startsWith(normDraft) -> 3
                        titleNorm.contains(normDraft) -> 2
                        artistNorm.startsWith(normDraft) -> 1
                        else -> 0
                    }
                }
                .take(5)
            if (trackMatches.isNotEmpty()) {
                result.add(SuggestionItem.Header("En tu biblioteca"))
                trackMatches.forEach { result.add(SuggestionItem.Track(it)) }
            }

            // Add artist suggestions from local library (with fuzzy fallback)
            val matchingArtists = localTrackIndex
                .map { it.artist.trim() }
                .filter { it.isNotEmpty() &&
                    (normalizeForFilter(it).contains(normDraft) ||
                     normalizeForFilter(it).split(WHITESPACE_REGEX).any { w -> suggestFuzzyMatch(normDraft, w) })
                }
                .distinct()
                .sorted()
                .take(4)
            if (matchingArtists.isNotEmpty()) {
                result.add(SuggestionItem.Header("Artistas"))
                matchingArtists.forEach { result.add(SuggestionItem.Suggestion(it)) }
            }
        }

        return result
    }

    fun invalidateSmartSuggestionsCache() {
        cachedSmartSuggestions = null
    }

    private fun buildSmartSuggestions(normDraft: String, recentQueries: List<String>): List<String> {
        val ctx = context ?: return emptyList()
        val recentNorm = recentQueries.map { normalizeForFilter(it) }.toSet()

        // Build base pool once and cache it to avoid disk I/O on every keystroke
        val basePool = cachedSmartSuggestions ?: run {
            val pool = LinkedHashSet<String>()
            val snapshot = PlaybackHistoryStore.load(ctx)
            val current = snapshot.currentTrack()
            if (current != null) {
                val artist = current.artist.trim()
                val title = current.title.trim()
                if (artist.isNotEmpty()) pool.add(artist)
                if (title.isNotEmpty()) pool.add(title)
            }
            for (track in snapshot.queue) {
                val artist = track.artist.trim()
                val title = track.title.trim()
                if (artist.isNotEmpty()) pool.add(artist)
                if (title.isNotEmpty() && pool.size < 20) pool.add(title)
            }
            pool.toList().also { cachedSmartSuggestions = it }
        }

        return basePool
            .filter { candidate ->
                val norm = normalizeForFilter(candidate)
                !recentNorm.contains(norm) && (
                    if (normDraft.isEmpty()) true
                    else norm.contains(normDraft) || normDraft.contains(norm)
                )
            }
            .take(12)
    }

    private fun resolveArtistForVideoId(videoId: String): String {
        val ctx = context ?: return ""
        // 1. Playback history (most likely to have full metadata)
        try {
            val history = PlaybackHistoryStore.load(ctx)
            history.queue.firstOrNull { it.videoId == videoId }?.let {
                if (it.artist.isNotEmpty()) return it.artist
            }
        } catch (_: Exception) {}
        // 2. Favorites
        try {
            FavoritesPlaylistStore.loadFavorites(ctx).firstOrNull { it.videoId == videoId }?.let {
                if (it.artist.isNotEmpty()) return it.artist
            }
        } catch (_: Exception) {}
        // 3. Custom playlists
        try {
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).firstOrNull { it.videoId == videoId }?.let {
                    if (it.artist.isNotEmpty()) return it.artist
                }
            }
        } catch (_: Exception) {}
        // 4. Cached online playlists
        try {
            val cache = ctx.getSharedPreferences("streaming_cache", Context.MODE_PRIVATE)
            for ((key, value) in cache.all) {
                if (key.startsWith("playlist_tracks_data_") && value is String) {
                    val arr = org.json.JSONArray(value)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (obj.optString("videoId") == videoId) {
                            val artist = obj.optString("artist", "")
                            if (artist.isNotEmpty()) return artist
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun loadArtworkInto(target: ImageView, url: String?, videoId: String? = null) {
        var finalUrl = url?.trim()
        
        if (finalUrl.isNullOrEmpty() && !videoId.isNullOrEmpty() && OfflineAudioStore.hasOfflineAudio(requireContext(), videoId)) {
            finalUrl = OfflineAudioStore.getThumbnailUri(requireContext(), videoId)?.toString()
        }

        if (finalUrl.isNullOrEmpty()) {
            target.setImageDrawable(null)
            return
        }

        val safeUrl = if (finalUrl.startsWith("//")) "https:$finalUrl" else finalUrl
        val isLocalUri = safeUrl.startsWith("file://") || safeUrl.startsWith("content://")
        val offlineOnly = !isNetworkAvailable() && !isLocalUri

        val density = target.context.resources.displayMetrics.density
        val params = target.layoutParams
        val rawW = if (target.width > 0) target.width else if (params != null && params.width > 0) params.width else 0
        val rawH = if (target.height > 0) target.height else if (params != null && params.height > 0) params.height else 0
        val side = if (rawW > 0 && rawH > 0) maxOf(rawW, rawH) else Math.round(160 * density)
        val overrideSize = maxOf(side, 320)

        Glide.with(this)
            .load(safeUrl)
            .transform(SHARED_YT_CROP)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .onlyRetrieveFromCache(offlineOnly)
            .override(overrideSize, overrideSize)
            .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
            .into(target)
    }

    private fun searchTypeLabel(track: YouTubeMusicService.TrackResult) = when (track.resultType?.lowercase(Locale.US)) {
        "video" -> ""
        "channel" -> "Artista"
        "playlist" -> "Playlist"
        else -> "Resultado"
    }

    private fun normalizeForFilter(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        normalizedFilterCache[value]?.let { return it }
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val norm = decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase().trim()
        if (normalizedFilterCache.size > 2048) normalizedFilterCache.clear()
        normalizedFilterCache[value] = norm
        return norm
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private var hasBeenVisible = false

    private fun showModuleLoadingOverlay() {
        moduleLoadingOverlay.alpha = 1f
        moduleLoadingOverlay.visibility = View.VISIBLE
    }

    private fun revealModuleContent() {
        moduleLoadingOverlay.animate().cancel()
        moduleLoadingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { moduleLoadingOverlay.visibility = View.GONE }
            .start()
    }

    private fun scheduleOverlayRevealAfterDraw() {
        val v = view ?: return
        v.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                v.viewTreeObserver.removeOnPreDrawListener(this)
                if (isAdded && !isHidden) revealModuleContent()
                return true
            }
        })
    }

    private inner class SearchResultsAdapter(
        val onClick: (YouTubeMusicService.TrackResult) -> Unit,
        val onMoreClick: (YouTubeMusicService.TrackResult, View) -> Unit
    ) : RecyclerView.Adapter<SearchResultsAdapter.TrackViewHolder>() {
        private val data = mutableListOf<YouTubeMusicService.TrackResult>()
        private val offlineStateCache = HashMap<String, Boolean>()
        private val pendingLookups = HashSet<String>()
        private val lookupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())

        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = data[position].let { "${it.resultType}|${it.contentId}|${it.title}".hashCode().toLong() }

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        fun submitResults(newData: List<YouTubeMusicService.TrackResult>) {
            val old = data.toList()
            val callback = object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newData.size
                override fun areItemsTheSame(op: Int, np: Int) = old[op].contentId == newData[np].contentId && old[op].resultType == newData[np].resultType
                override fun areContentsTheSame(op: Int, np: Int) = old[op] == newData[np]
            }
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val diff = DiffUtil.calculateDiff(callback)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    data.clear()
                    data.addAll(newData)
                    diff.dispatchUpdatesTo(this@SearchResultsAdapter)
                }
            }
        }

        fun invalidateOfflineCache() {
            offlineStateCache.clear()
            pendingLookups.clear()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TrackViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_music_search_result, parent, false))

        override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
            val item = data[position]
            holder.title.text = item.title ?: "Resultado"
            val typeLabel = searchTypeLabel(item)
            if (typeLabel.isEmpty()) {
                holder.subtitle.text = item.subtitle
            } else {
                holder.subtitle.text = if (item.subtitle.isEmpty()) typeLabel else "$typeLabel • ${item.subtitle}"
            }
            loadArtworkInto(holder.thumb, item.thumbnailUrl, item.videoId)

            val vid = item.videoId ?: ""
            if (vid.isNotEmpty()) {
                val cached = offlineStateCache[vid]
                if (cached == true) {
                    holder.offlineIndicator.visibility = View.VISIBLE
                    holder.offlineIndicator.setImageResource(R.drawable.ic_check_small)
                    holder.offlineIndicator.setBackgroundResource(R.drawable.bg_offline_state_filled_primary)
                    holder.offlineIndicator.setColorFilter(ContextCompat.getColor(requireContext(), R.color.surface_dark))
                } else {
                    holder.offlineIndicator.visibility = View.GONE
                    if (cached == null) triggerOfflineLookup(vid, position)
                }
            } else {
                holder.offlineIndicator.visibility = View.GONE
            }
            
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onMoreClick(item, it)
                true
            }
            holder.more.setOnClickListener { onMoreClick(item, it) }
        }

        private fun triggerOfflineLookup(videoId: String, position: Int) {
            if (pendingLookups.contains(videoId)) return
            pendingLookups.add(videoId)
            val ctx = context?.applicationContext ?: return
            lookupExecutor.execute {
                val available = OfflineAudioStore.hasOfflineAudio(ctx, videoId)
                handler.post {
                    pendingLookups.remove(videoId)
                    val prev = offlineStateCache[videoId]
                    offlineStateCache[videoId] = available
                    if (available && prev != true && position >= 0 && position < data.size
                        && data[position].videoId == videoId) {
                        notifyItemChanged(position)
                    }
                }
            }
        }

        override fun getItemCount() = data.size

        inner class TrackViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.ivTrackThumb)
            val title: TextView = v.findViewById(R.id.tvTrackTitle)
            val subtitle: TextView = v.findViewById(R.id.tvTrackSubtitle)
            val more: ImageView = v.findViewById(R.id.ivTrackMore)
            val offlineIndicator: ImageView = v.findViewById(R.id.ivOfflineIndicator)
        }
    }

    override fun onResume() {
        super.onResume()
        // Only hide the global header when this fragment is actually visible (not hidden).
        // Hidden fragments still receive onResume() — without this guard, SearchFragment
        // would unconditionally kill the header every time the activity resumes, even when
        // the user is on Biblioteca or Principal.
        if (isHidden) return
        (activity as? MainActivity)?.hideTopAppBarForSearch()
        // Clear input on every entry
        etSearchQuery.setText("")
        showSuggestionsMode()
        loadLocalTrackIndex()
        // Show overlay only on first creation; skip on re-entry to avoid delay
        if (!hasBeenVisible) {
            showModuleLoadingOverlay()
            scheduleOverlayRevealAfterDraw()
            hasBeenVisible = true
        } else {
            moduleLoadingOverlay.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        suggestionsDebounceRunnable?.let { suggestionsDebounceHandler.removeCallbacks(it) }
        suggestionsJob?.cancel()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // no-op
        } else {
            rvSearchResults.post { rvSearchResults.scrollToPosition(0) }
            rvSearchSuggestions.post { rvSearchSuggestions.scrollToPosition(0) }
            // Re-enable back pressed callback when fragment becomes visible again
            backPressedCallback?.isEnabled = true
            cachedSmartSuggestions = null
            // Hide global header when fragment becomes visible
            (activity as? MainActivity)?.findViewById<View>(R.id.topAppBar)?.visibility = View.GONE
            // Only reset to suggestions mode if there is no active search (e.g. entering fresh).
            // When returning from the player after a search, preserve results and query.
            if (activeSearchQuery.isEmpty()) {
                etSearchQuery.setText("")
                showSuggestionsMode()
            }
            loadLocalTrackIndex()
            // Retry Firebase load if local data has no thumbnails (e.g. auth wasn't ready at startup)
            if (recentSearchData.none { it.thumbnail.isNotEmpty() }) {
                loadRecentSearchesFromFirebase()
            }
            if (!hasBeenVisible) {
                showModuleLoadingOverlay()
                scheduleOverlayRevealAfterDraw()
                hasBeenVisible = true
            } else {
                moduleLoadingOverlay.visibility = View.GONE
            }
        }
    }

    sealed class SuggestionItem {
        data class Header(val label: String) : SuggestionItem()
        data class Recent(val query: String, val thumbnail: String = "") : SuggestionItem()
        data class Suggestion(val query: String) : SuggestionItem()
        data class Autocomplete(val query: String) : SuggestionItem()
        data class Track(val track: FavoritesPlaylistStore.FavoriteTrack) : SuggestionItem()
        data class RecentImages(val items: List<RecentSearch>) : SuggestionItem()
    }

    private inner class SuggestionsAdapter(val onClick: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val data = mutableListOf<SuggestionItem>()

        private val TYPE_HEADER = 0
        private val TYPE_RECENT = 1
        private val TYPE_SUGGESTION = 2
        private val TYPE_TRACK = 3
        private val TYPE_RECENT_IMAGES = 4

        fun updateItems(newList: List<SuggestionItem>) {
            data.clear()
            data.addAll(newList)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (data[position]) {
            is SuggestionItem.Header -> TYPE_HEADER
            is SuggestionItem.Recent -> TYPE_RECENT
            is SuggestionItem.Suggestion -> TYPE_SUGGESTION
            is SuggestionItem.Autocomplete -> TYPE_SUGGESTION
            is SuggestionItem.Track -> TYPE_TRACK
            is SuggestionItem.RecentImages -> TYPE_RECENT_IMAGES
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(p.context)
            return when (t) {
                TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_search_suggestion_header, p, false))
                TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_autocomplete_track, p, false))
                TYPE_RECENT_IMAGES -> RecentImagesViewHolder(inflater.inflate(R.layout.item_suggestion_recent_images, p, false))
                else -> RowViewHolder(inflater.inflate(R.layout.item_search_suggestion, p, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            when (val item = data[p]) {
                is SuggestionItem.Header -> (h as HeaderViewHolder).label.text = item.label
                is SuggestionItem.Recent -> {
                    (h as RowViewHolder).apply {
                        text.text = item.query
                        icon.setImageResource(R.drawable.ic_time_24)
                        icon.setColorFilter(android.graphics.Color.WHITE)
                        icon.scaleType = android.widget.ImageView.ScaleType.CENTER
                        icon.setPadding(0, 0, 0, 0)
                        itemView.setOnClickListener { onClick(item.query) }
                        itemView.setOnLongClickListener { showDeleteSearchDialog(item.query); true }
                    }
                }
                is SuggestionItem.Suggestion -> {
                    (h as RowViewHolder).apply {
                        text.text = item.query
                        icon.setImageResource(R.drawable.ic_search)
                        icon.setColorFilter(android.graphics.Color.WHITE)
                        icon.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        icon.setPadding(12, 12, 12, 12)
                        itemView.setOnClickListener { onClick(item.query) }
                        itemView.setOnLongClickListener { true }
                    }
                }
                is SuggestionItem.Autocomplete -> {
                    (h as RowViewHolder).apply {
                        text.text = item.query
                        icon.setImageResource(R.drawable.ic_search)
                        icon.setColorFilter(android.graphics.Color.WHITE)
                        icon.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        icon.setPadding(12, 12, 12, 12)
                        itemView.setOnClickListener { onClick(item.query) }
                        itemView.setOnLongClickListener { true }
                    }
                }
                is SuggestionItem.Track -> {
                    (h as TrackViewHolder).apply {
                        title.text = item.track.title
                        artist.text = item.track.artist
                        if (item.track.imageUrl.isNotEmpty()) {
                            Glide.with(this@SearchFragment)
                                .load(item.track.imageUrl)
                                .transform(SHARED_YT_CROP)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(android.R.color.darker_gray)
                                .into(thumb)
                            thumb.visibility = View.VISIBLE
                        } else {
                            thumb.visibility = View.GONE
                        }
                        val trackResult = YouTubeMusicService.TrackResult(
                            "video", item.track.videoId, item.track.title, item.track.artist, item.track.imageUrl
                        )
                        val isOffline = item.track.videoId.isNotEmpty()
                            && OfflineAudioStore.hasOfflineAudio(requireContext(), item.track.videoId)
                        if (isOffline) {
                            offline.setImageResource(R.drawable.ic_check_small)
                            offline.setBackgroundResource(R.drawable.bg_offline_state_filled_primary)
                            offline.setColorFilter(ContextCompat.getColor(requireContext(), R.color.surface_dark))
                            offline.visibility = View.VISIBLE
                        } else {
                            offline.visibility = View.INVISIBLE
                        }
                        itemView.setOnClickListener { playTrackDirectly(trackResult) }
                        itemView.setOnLongClickListener {
                            showTrackOptionsBottomSheet(trackResult, it)
                            true
                        }
                        more.setOnClickListener { showTrackOptionsBottomSheet(trackResult, it) }
                    }
                }
                is SuggestionItem.RecentImages -> {
                    val vh = h as RecentImagesViewHolder
                    val imgAdapter = vh.rv.adapter as? RecentSearchImageAdapter
                        ?: RecentSearchImageAdapter { recentSearch ->
                            if (recentSearch.videoId.isNotEmpty()) {
                                var artist = recentSearch.artist
                                if (artist.isEmpty()) artist = resolveArtistForVideoId(recentSearch.videoId)
                                val track = YouTubeMusicService.TrackResult(
                                    "video", recentSearch.videoId, recentSearch.title, artist, recentSearch.thumbnail
                                )
                                if (recentSearch.artist.isEmpty() && artist.isNotEmpty()) {
                                    val idx = recentSearchData.indexOfFirst { it.videoId == recentSearch.videoId }
                                    if (idx >= 0) {
                                        recentSearchData[idx] = recentSearch.copy(artist = artist)
                                        saveRecentSearchQueries()
                                    }
                                }
                                playTrackDirectly(track)
                            } else {
                                etSearchQuery.setText(recentSearch.query)
                                etSearchQuery.setSelection(recentSearch.query.length)
                            }
                        }.also {
                            vh.rv.layoutManager = LinearLayoutManager(vh.rv.context, LinearLayoutManager.HORIZONTAL, false)
                            vh.rv.adapter = it
                            vh.rv.itemAnimator = null
                        }
                    imgAdapter.updateItems(item.items)
                }
            }
        }

        override fun getItemCount() = data.size

        inner class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.tvSuggestionHeader)
        }

        inner class RowViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.tvSuggestionText)
            val icon: ImageView = v.findViewById(R.id.ivSuggestionIcon)
        }

        inner class TrackViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvAutoCompleteTitle)
            val artist: TextView = v.findViewById(R.id.tvAutoCompleteArtist)
            val thumb: ImageView = v.findViewById(R.id.ivAutoCompleteThumb)
            val offline: ImageView = v.findViewById(R.id.ivAutoCompleteOffline)
            val more: ImageView = v.findViewById(R.id.ivAutoCompleteMore)
        }

        inner class RecentImagesViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val rv: RecyclerView = v.findViewById(R.id.rvRecentImagesInline)
        }
    }

    private inner class RecentSearchImageAdapter(val onClick: (RecentSearch) -> Unit) : RecyclerView.Adapter<RecentSearchImageAdapter.ViewHolder>() {
        private val data = mutableListOf<RecentSearch>()

        fun updateItems(newList: List<RecentSearch>) {
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize() = data.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return data[oldPos].videoId == newList[newPos].videoId &&
                           data[oldPos].query == newList[newPos].query
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return data[oldPos] == newList[newPos]
                }
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            data.clear()
            data.addAll(newList)
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): ViewHolder {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_recent_search_image, p, false)
            // Calculate width for 2 compact items (~40% of screen each)
            val density = p.context.resources.displayMetrics.density
            val parentWidth = p.measuredWidth.takeIf { it > 0 }
                ?: p.context.resources.displayMetrics.widthPixels
            val itemWidth = ((parentWidth - (32 * density).toInt()) * 0.40f).toInt()
            val marginEnd = (12 * density).toInt()
            view.layoutParams = RecyclerView.LayoutParams(itemWidth, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, marginEnd, 0)
            }
            // Set image container height for 16:9
            val imageHeight = (itemWidth * 9f / 16f).toInt()
            val container = view.findViewById<View>(R.id.flImageContainer)
            container.layoutParams = (container.layoutParams).also { it.height = imageHeight }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val item = data[p]
            h.title.text = item.title.ifEmpty { item.query }
            
            // Show loading placeholder
            h.loadingPlaceholder.visibility = View.VISIBLE
            h.image.alpha = 0f
            
            if (item.thumbnail.isNotEmpty()) {
                Glide.with(this@SearchFragment)
                    .load(item.thumbnail)
                    .transform(YouTubeCropTransformation())
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            h.loadingPlaceholder.visibility = View.GONE
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            h.loadingPlaceholder.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction { h.loadingPlaceholder.visibility = View.GONE }
                                .start()
                            h.image.animate()
                                .alpha(1f)
                                .setDuration(300)
                                .start()
                            return false
                        }
                    })
                    .into(h.image)
            } else {
                h.loadingPlaceholder.visibility = View.GONE
            }
            
            h.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = data.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.ivRecentSearchImage)
            val loadingPlaceholder: View = v.findViewById(R.id.vLoadingPlaceholder)
            val title: TextView = v.findViewById(R.id.tvRecentSearchTitle)
        }
    }
}
