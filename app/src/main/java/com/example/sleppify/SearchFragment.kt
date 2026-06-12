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

        private val PREFS_STREAMING_CACHE = AppConstants.PREFS_STREAMING_CACHE
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
    private var searchResultsBaseBottomPadding = 0
    private var searchContentBaseBottomPadding = 0
    
    private var searching = false
    private var searchPaginationInFlight = false
    private var hasMoreSearchPages = false
    private var nextSearchPageToken = ""
    private var innertubeNextToken = ""
    private var useInnertubePagination = false
    private var activeSearchQuery = ""
    private var latestSearchRequestId = 0L
    private var autoPrefetchPagesRemaining = 0

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
        searchResultsBaseBottomPadding = rvSearchResults.paddingBottom
        searchContentBaseBottomPadding = nsvSearchContent.paddingBottom
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
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(systemBars.left, 0, systemBars.right, 0)
            val llSearchBar = root.findViewById<View>(R.id.llSearchBar)
            llSearchBar?.setPadding(
                llSearchBar.paddingLeft,
                systemBars.top,
                llSearchBar.paddingRight,
                llSearchBar.paddingBottom
            )
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            nsvSearchContent.setPadding(
                nsvSearchContent.paddingLeft,
                nsvSearchContent.paddingTop,
                nsvSearchContent.paddingRight,
                searchContentBaseBottomPadding + bottomInset
            )
            rvSearchResults.setPadding(
                rvSearchResults.paddingLeft,
                rvSearchResults.paddingTop,
                rvSearchResults.paddingRight,
                searchResultsBaseBottomPadding + bottomInset
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
        rvSearchResults.setHasFixedSize(false)
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
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }

            // 3. All cached YT Music playlists (playlist_tracks_data_*)
            try {
                val cache = ctx.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
                for ((key, value) in cache.all) {
                    if (key.startsWith("playlist_tracks_data_") && value is String) {
                        val arr = org.json.JSONArray(value)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            tryAdd(obj.optString("videoId"), obj.optString("title"), obj.optString("artist"), obj.optString("duration", ""), obj.optString("imageUrl"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }

            // 4. Playback history
            try {
                PlaybackHistoryStore.load(ctx).queue.forEach { tryAdd(it.videoId, it.title, it.artist, it.duration, it.imageUrl) }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }

            // 5. Radio history tracks
            try {
                RadioHistoryStore.getRadios(ctx).forEach { radio ->
                    radio.tracks.forEach { t -> tryAdd(t.videoId, t.title, t.artist, "", t.thumbnailUrl) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error", e)
            }

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

        if (!isNetworkAvailable()) {
            if (!append) setSearchLoadingState(false, "Sin conexión a internet")
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
            youTubeMusicService.continueInnertubeSearch(innertubeNextToken, SEARCH_PAGE_SIZE, getCookieHeader(), object : YouTubeMusicService.SearchPageCallback {
                override fun onSuccess(pageResult: YouTubeMusicService.SearchPageResult) {
                    if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                    Log.d(TAG, "[SEARCH] CONTINUATION ok in ${android.os.SystemClock.elapsedRealtime() - tCont}ms — ${pageResult.tracks.size} tracks hasMore=${pageResult.nextPageToken.isNotEmpty()}")
                    searchPaginationInFlight = false
                    innertubeNextToken = pageResult.nextPageToken
                    hasMoreSearchPages = innertubeNextToken.isNotEmpty()
                    appendUniqueTracks(pageResult.tracks)
                    applyActiveFilter(query, forceSort = false)

                    // Auto-prefetch next pages in background
                    if (hasMoreSearchPages && autoPrefetchPagesRemaining > 0) {
                        autoPrefetchPagesRemaining--
                        loadMoreSearchResults()
                    }
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
        youTubeMusicService.searchTracksViaInnertube(query, maxResultsToFetch, getCookieHeader(), object : YouTubeMusicService.SearchPageCallback {
            override fun onSuccess(pageResult: YouTubeMusicService.SearchPageResult) {
                if (activity == null || !isAdded || requestId != latestSearchRequestId) return
                Log.d(TAG, "[SEARCH] INNERTUBE ok in ${android.os.SystemClock.elapsedRealtime() - tInnertube}ms — ${pageResult.tracks.size} tracks hasMore=${pageResult.nextPageToken.isNotEmpty()} totalElapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                if (append) searchPaginationInFlight = false

                // Enable infinite scroll if Innertube returned a continuation token
                innertubeNextToken = pageResult.nextPageToken
                useInnertubePagination = innertubeNextToken.isNotEmpty()
                hasMoreSearchPages = useInnertubePagination

                if (!append) {
                    allTracks.clear()
                    allTracks.addAll(pageResult.tracks)
                } else {
                    appendUniqueTracks(pageResult.tracks)
                }
                applyActiveFilter(query, forceSort = true)

                if (allTracks.isEmpty() && !append) {
                    setSearchLoadingState(false, "No encontré resultados para: $query")
                } else if (!append) {
                    setSearchLoadingState(false, "")
                    allTracks.firstOrNull()?.videoId?.let { id ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            StreamResolver.resolveStreamUrl(requireContext(), id)
                        }
                    }
                    revealModuleContent()
                    rvSearchResults.alpha = 0f
                    rvSearchResults.animate().alpha(1f).setDuration(250).start()
                    hideKeyboard()

                    // Auto-fetch continuation pages in background for richer results
                    if (hasMoreSearchPages) {
                        autoPrefetchPagesRemaining = 4
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

        // When authenticated, trust YTM's ranking for online results — only sort if no cookie
        val hasSession = getCookieHeader().isNotEmpty()
        if (forceSort && normalizedQuery.isNotEmpty() && filtered.size > 1 && !hasSession) {
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
        Log.d(TAG, "[SEARCH] applyFilter: allTracks=${allTracks.size} filtered=${filtered.size} tracksForAdapter=${tracks.size} featured=${featuredTrack?.title}")
        adapter?.submitResults(tracks.toList())

        view?.requestLayout()
    }

    private fun sortResults(list: MutableList<YouTubeMusicService.TrackResult>, query: String, signals: UserSignals = UserSignals(emptySet(), emptySet(), emptySet())) {
        val normalized = normalizeForFilter(query)
        val tokens = normalized.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val totalSize = list.size

        // --- Detect "artist mode": if query tokens match the artist of top results ---
        val detectedArtist = detectDominantArtist(list, tokens)
        Log.d(TAG, "[SORT] query=\"$query\" tokens=$tokens detectedArtist=\"$detectedArtist\" totalItems=$totalSize")

        val indexed = list.mapIndexed { idx, track -> track to idx }
        val scored = indexed.map { (track, apiIndex) -> track to computeScore(track, normalized, tokens, signals, apiIndex, totalSize, detectedArtist) }
        val sorted = scored.sortedByDescending { it.second }.map { it.first }

        // Log top 5 scores for debugging
        scored.sortedByDescending { it.second }.take(5).forEachIndexed { i, (track, score) ->
            val artist = extractArtistFromSubtitle(track.subtitle)
            Log.d(TAG, "[SORT] #$i score=$score title=\"${track.title}\" artist=\"$artist\" subtitle=\"${track.subtitle?.take(60)}\"")
        }

        list.clear()
        list.addAll(sorted)
    }

    /**
     * Detect if the query is targeting a specific artist.
     * If multiple top results share the same artist AND query tokens match that artist name,
     * we enter "artist mode" where other songs by that artist get a boost.
     */
    private fun detectDominantArtist(list: List<YouTubeMusicService.TrackResult>, queryTokens: List<String>): String {
        if (list.size < 2 || queryTokens.isEmpty()) return ""
        // Extract artist from subtitle (first segment before " • ")
        val artistCounts = HashMap<String, Int>()
        for (track in list.take(10)) {
            val artist = extractArtistFromSubtitle(track.subtitle)
            if (artist.isNotEmpty()) {
                artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            }
        }
        // Find the most frequent artist in top results
        val topArtistEntry = artistCounts.entries.maxByOrNull { it.value } ?: return ""
        if (topArtistEntry.value < 2) return ""

        // Check if query tokens match this artist name
        val normalizedArtist = normalizeForFilter(topArtistEntry.key)
        val artistWords = normalizedArtist.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val matchCount = queryTokens.count { tok ->
            artistWords.any { it == tok || it.startsWith(tok) || tok.startsWith(it) || fuzzyMatch(tok, it) }
        }
        // At least one query token must match the artist
        return if (matchCount >= 1) normalizedArtist else ""
    }

    /** Extract the artist name from a YTM subtitle like "Song • Artist • Album • 3:22 • 500 M reproducciones" */
    private fun extractArtistFromSubtitle(subtitle: String?): String {
        if (subtitle.isNullOrEmpty()) return ""
        val parts = subtitle.split(" \u2022 ").map { it.trim() }
        if (parts.isEmpty()) return ""
        // YTM subtitle format with filter: "Song • Artist • Album • Duration" or "Video • Artist • Duration"
        // Without filter or Topic channels: "Artist • Album • Duration"
        val typeLabels = setOf("song", "video", "album", "playlist", "single", "ep", "canción", "cancion", "álbum")
        val firstLower = parts[0].lowercase(Locale.ROOT)
        return if (firstLower in typeLabels && parts.size > 1) {
            parts[1]
        } else {
            // Subtitle starts with artist directly (e.g. "Bad Bunny - Topic" or "The Weeknd • Album • ...")
            // Also handle "Artist - Topic" format
            val raw = parts[0]
            if (raw.endsWith(" - Topic")) raw.removeSuffix(" - Topic") else raw
        }
    }

    /** Extract duration string (e.g. "1:58") from a YTM subtitle like "Artist • ny2mia • 1:58" */
    private fun extractDurationFromSubtitle(subtitle: String?): String {
        if (subtitle.isNullOrEmpty()) return ""
        val parts = subtitle.split(" \u2022 ").map { it.trim() }
        for (part in parts.asReversed()) {
            if (part.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")))
                return part
        }
        return ""
    }

    /** Build a clean subtitle for search display: artist + duration only (no repeated song name) */
    private fun buildCleanSearchSubtitle(track: YouTubeMusicService.TrackResult): String {
        val artist = extractArtistFromSubtitle(track.subtitle)
        val duration = extractDurationFromSubtitle(track.subtitle)
        return when {
            artist.isNotEmpty() && duration.isNotEmpty() -> "$artist \u2022 $duration"
            artist.isNotEmpty() -> artist
            else -> track.subtitle ?: ""
        }
    }

    /** Extract play count as a numeric value from subtitle for popularity tiebreaking */
    private fun extractPlayCount(subtitle: String?): Long {
        if (subtitle.isNullOrEmpty()) return 0L
        val parts = subtitle.split(" \u2022 ")
        for (part in parts) {
            val trimmed = part.trim().lowercase(Locale.ROOT)
            if (trimmed.contains("reproducciones") || trimmed.contains("plays") || trimmed.contains("views")) {
                // Handle "10 mil M reproducciones" = 10,000 M = 10 billion
                val milMMatch = Regex("([\\d,.]+)\\s*mil\\s+m", RegexOption.IGNORE_CASE).find(trimmed)
                if (milMMatch != null) {
                    val num = milMMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
                    return (num * 1_000_000_000L).toLong()
                }
                // Handle "3570 M reproducciones", "459 k reproducciones", "28 reproducciones"
                val numMatch = Regex("([\\d,.]+)\\s*(m|k|b|mil)?\\s*reproducciones", RegexOption.IGNORE_CASE).find(trimmed)
                    ?: Regex("([\\d,.]+)\\s*(m|k|b|mil)?\\s*(plays|views)", RegexOption.IGNORE_CASE).find(trimmed)
                    ?: Regex("([\\d,.]+)\\s*(m|k|b|mil)?", RegexOption.IGNORE_CASE).find(trimmed)
                if (numMatch != null) {
                    val numStr = numMatch.groupValues[1].replace(",", ".")
                    val multiplier = when (numMatch.groupValues[2].lowercase(Locale.ROOT)) {
                        "b" -> 1_000_000_000L
                        "m" -> 1_000_000L
                        "mil" -> 1_000L
                        "k" -> 1_000L
                        else -> 1L
                    }
                    val num = numStr.toDoubleOrNull() ?: 0.0
                    return (num * multiplier).toLong()
                }
            }
        }
        return 0L
    }

    private fun computeScore(track: YouTubeMusicService.TrackResult, query: String, tokens: List<String>, signals: UserSignals, apiIndex: Int, totalResults: Int, detectedArtist: String): Int {
        val t = normalizeForFilter(track.title)
        val s = normalizeForFilter(track.subtitle)
        val artistName = normalizeForFilter(extractArtistFromSubtitle(track.subtitle))
        var score = 0

        // --- API position score (trust YouTube's relevance but allow re-ranking) ---
        score += maxOf(0, (totalResults - apiIndex) * 50)

        // --- Result type priority ---
        val type = track.resultType?.lowercase() ?: ""
        when {
            type == "track" || type == "video" || type == "song" -> score += 2000
            type == "artist" -> score += 500
            type == "album" -> score += 200
            type == "playlist" -> score -= 2000
        }

        // --- Artist detection from query ---
        val artistWords = artistName.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        var artistTokenHits = 0
        val nonArtistTokens = mutableListOf<String>()

        tokens.forEach { tok ->
            val hitsArtist = artistWords.any { it == tok || it.startsWith(tok) || tok.startsWith(it) || fuzzyMatch(tok, it) }
            if (hitsArtist) {
                artistTokenHits++
            } else {
                nonArtistTokens.add(tok)
            }
        }

        val queryMatchesArtist = artistTokenHits > 0

        // --- Combined Artist + Title match (highest priority, like "bad bunny monaco") ---
        val titleWords = t.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        var titleHitsFromNonArtist = 0
        nonArtistTokens.forEach { tok ->
            if (titleWords.any { it == tok || it.startsWith(tok) || tok.startsWith(it) || fuzzyMatch(tok, it) }) {
                titleHitsFromNonArtist++
            }
        }

        if (queryMatchesArtist && nonArtistTokens.isNotEmpty() && titleHitsFromNonArtist >= nonArtistTokens.size) {
            // Artist matches AND all remaining query words match the title → perfect match
            score += 25000
        } else if (queryMatchesArtist && nonArtistTokens.isNotEmpty() && titleHitsFromNonArtist > 0) {
            // Partial title match with artist match
            score += 18000
        } else if (queryMatchesArtist && nonArtistTokens.isEmpty()) {
            // Query is ONLY an artist name — boost all tracks by this artist
            score += 12000
        }

        // --- Exact and prefix title matches (full query in title) ---
        if (t == query) {
            score += 15000
        } else if (t.startsWith("$query ") || t.startsWith(query)) {
            score += 10000
        } else if (t.contains(query)) {
            score += 6000
        }

        // --- Token-level title matching ---
        var titleHits = 0
        var subtitleHits = 0
        val subtitleWords = s.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

        tokens.forEach { tok ->
            if (titleWords.contains(tok)) {
                titleHits++
                score += 400
            } else if (titleWords.any { it.startsWith(tok) }) {
                titleHits++
                score += 300
            } else if (titleWords.any { tok.startsWith(it) }) {
                titleHits++
                score += 250
            } else if (titleWords.any { fuzzyMatch(tok, it) }) {
                titleHits++
                score += 200
            }
            if (subtitleWords.contains(tok)) {
                subtitleHits++
                score += 80
            } else if (subtitleWords.any { it.startsWith(tok) || tok.startsWith(it) || fuzzyMatch(tok, it) }) {
                subtitleHits++
                score += 50
            }
        }

        if (titleHits >= tokens.size && tokens.isNotEmpty()) {
            score += 3000
        } else if (titleHits + subtitleHits >= tokens.size && tokens.isNotEmpty()) {
            score += 1500
        }

        // --- "Artist mode" bonus: same artist as detected dominant artist ---
        if (detectedArtist.isNotEmpty() && artistName.isNotEmpty()) {
            if (artistName == detectedArtist || artistName.contains(detectedArtist) || detectedArtist.contains(artistName)) {
                score += 5000
            }
        }

        // --- Popularity tiebreaker (logarithmic so it doesn't overwhelm relevance) ---
        val plays = extractPlayCount(track.subtitle)
        if (plays > 0) {
            score += (kotlin.math.ln(plays.toDouble()) * 15).toInt()
        }

        // --- User signals (light boost) ---
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
            tvFeaturedSubtitle.text = buildCleanSearchSubtitle(track)
        } else {
            val clean = buildCleanSearchSubtitle(track)
            tvFeaturedSubtitle.text = if (clean.isEmpty()) typeLabel else "$typeLabel • $clean"
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
                putExtra(EXTRA_RESULT_SUBTITLE, extractArtistFromSubtitle(track.subtitle))
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
            putExtra(EXTRA_RESULT_SUBTITLE, extractArtistFromSubtitle(track.subtitle))
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
            obj.put("subtitle", extractArtistFromSubtitle(track.subtitle))
            obj.put("thumbnailUrl", track.thumbnailUrl)
            tracksArray.put(obj)
        }

        val cleanArtist = extractArtistFromSubtitle(track.subtitle)
        val playbackIntent = Intent(requireContext(), MainActivity::class.java).apply {
            action = MainActivity.ACTION_PLAY_FROM_SEARCH
            putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
            putExtra(EXTRA_RESULT_VIDEO_ID, track.videoId ?: "")
            putExtra(EXTRA_RESULT_CONTENT_ID, track.contentId ?: "")
            putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
            putExtra(EXTRA_RESULT_SUBTITLE, cleanArtist)
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
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }

        // 2. Check custom playlists
        try {
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                val playlistTracks = CustomPlaylistsStore.getTracksFromPlaylist(ctx, name)
                if (playlistTracks.any { it.videoId == videoId }) {
                    return buildQueueFromFavoriteTracks(playlistTracks, videoId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }

        // 3. Check cached YouTube playlists
        try {
            val cache = ctx.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
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
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }

        // 4. Check playback history queue
        try {
            val snapshot = PlaybackHistoryStore.load(ctx)
            if (snapshot.queue.any { it.videoId == videoId }) {
                return buildQueueFromHistoryTracks(snapshot.queue, videoId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }

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
        val cleanSub = buildCleanSearchSubtitle(track)
        tvSubtitle.text = if (typeLabel.isEmpty()) cleanSub
            else if (cleanSub.isEmpty()) typeLabel else "$typeLabel • $cleanSub"
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
                if (isTrackInPlaylist(requireContext(), track.videoId ?: "", lspk)) {
                    showStatusBarSearch("Ya está en $lspn") {
                        CustomPlaylistsStore.clearLastSavedPlaylist(requireContext())
                        showSaveToPlaylistSheet(track)
                    }
                } else {
                    addTrackToPlaylistByKey(lspk, track)
                    showStatusBarSearch("Se guardó en $lspn") {
                        CustomPlaylistsStore.clearLastSavedPlaylist(requireContext())
                        showSaveToPlaylistSheet(track)
                    }
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

        // Row: Ir a artista
        val btnGoToArtist = view.findViewById<View>(R.id.btnBsGoToArtist)
        val artistName = extractArtistFromSubtitle(track.subtitle)
        if (artistName.isNotEmpty()) {
            btnGoToArtist.visibility = View.VISIBLE
            btnGoToArtist.setOnClickListener {
                dialog.dismiss()
                externalSearchQuery(artistName)
            }
        } else {
            btnGoToArtist.visibility = View.GONE
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
        val artist = extractArtistFromSubtitle(track.subtitle)
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
            val addedKey = lastAddedKey
            val addedName = lastAddedName
            val removed = didRemove
            saveDialog.dismiss()
            if (addedKey != null && addedName != null) {
                CustomPlaylistsStore.setLastSavedPlaylist(requireContext(), addedKey, addedName)
                showStatusBarSearch("Se guardó en $addedName") {
                    CustomPlaylistsStore.clearLastSavedPlaylist(requireContext())
                    showSaveToPlaylistSheet(track)
                }
            } else if (removed) {
                showStatusBarSearch("Se eliminó correctamente") { showSaveToPlaylistSheet(track) }
            }
        }

        val llList = sheet.findViewById<android.widget.LinearLayout>(R.id.llSavePlaylistList)
        llList.removeAllViews()

        val density = ctx.resources.displayMetrics.density
        val thumbSizePx = (48 * density).toInt()

        // Cap scroll area so footer buttons remain visible
        val svScroll = sheet.findViewById<View>(R.id.svSavePlaylistScroll)
        if (svScroll != null) {
            val maxH = (320 * density).toInt()
            svScroll.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            svScroll.post {
                if (svScroll.height > maxH) {
                    val lp = svScroll.layoutParams
                    lp.height = maxH
                    svScroll.layoutParams = lp
                }
            }
        }

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

        // "Música que te gustó" row (local mirror, insert at top)
        run {
            val likedPid = YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
            val likedMirrorKey = CustomPlaylistsStore.YT_MIRROR_PREFIX + likedPid
            val likedCached = MusicPlayerFragment.getLikedPlaylistFromCache()
            val row = layoutInflater.inflate(R.layout.item_save_playlist_row, llList, false)
            val ivThumb = row.findViewById<ImageView>(R.id.ivSavePlaylistThumb)
            val tvName = row.findViewById<TextView>(R.id.tvSavePlaylistName)
            val tvCount = row.findViewById<TextView>(R.id.tvSavePlaylistCount)
            val ivCheck = row.findViewById<ImageView>(R.id.ivSaveCheck)
            tvName.text = "Música que te gustó"
            tvCount.text = likedCached?.subtitle ?: "Playlist"
            ivThumb.setBackgroundResource(R.drawable.bg_music_liked_gradient)
            ivThumb.setImageResource(R.drawable.ic_thumb_up_liked)
            ivThumb.scaleType = ImageView.ScaleType.CENTER
            ivThumb.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            val isIn = CustomPlaylistsStore.isTrackInYtMirror(ctx, likedPid, track.videoId ?: "")
            ivCheck?.visibility = if (isIn) View.VISIBLE else View.GONE
            var checked = isIn
            row.setOnClickListener {
                if (checked) {
                    CustomPlaylistsStore.removeTrackFromYtMirror(ctx, likedPid, track.videoId ?: "")
                    checked = false
                    didRemove = true
                    ivCheck?.visibility = View.GONE
                    if (lastAddedKey == likedMirrorKey) {
                        lastAddedKey = null
                        lastAddedName = null
                    }
                } else {
                    val tTitle = track.title?.takeIf { it.isNotEmpty() } ?: "Tema"
                    val tArtist = extractArtistFromSubtitle(track.subtitle)
                    val tImage = track.thumbnailUrl ?: ""
                    CustomPlaylistsStore.addTrackToYtMirror(ctx, likedPid, track.videoId ?: "",
                        tTitle, tArtist, "--:--", tImage, true)
                    checked = true
                    ivCheck?.visibility = View.VISIBLE
                    lastAddedKey = likedMirrorKey
                    lastAddedName = "Música que te gustó"
                }
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

        // YouTube library playlists (local mirror)
        val ytPlaylists = MusicPlayerFragment.getYouTubeLibraryPlaylists()
        for (ytItem in ytPlaylists) {
            val ytPlaylistId = ytItem.contentId?.trim().orEmpty()
            if (ytPlaylistId.isEmpty()) continue
            val ytMirrorKey = CustomPlaylistsStore.YT_MIRROR_PREFIX + ytPlaylistId
            val ytFallbackThumb = ytItem.thumbnailUrl?.trim().orEmpty()

            val row = layoutInflater.inflate(R.layout.item_save_playlist_row, llList, false)
            val ivThumb = row.findViewById<ImageView>(R.id.ivSavePlaylistThumb)
            val tvName = row.findViewById<TextView>(R.id.tvSavePlaylistName)
            val tvCount = row.findViewById<TextView>(R.id.tvSavePlaylistCount)
            val ivCheck = row.findViewById<ImageView>(R.id.ivSaveCheck)
            tvName.text = ytItem.title ?: ""
            tvCount.text = ytItem.subtitle ?: "Playlist"
            var ytUrls = loadPersistedGridUrls(ctx, ytPlaylistId)
            if (ytUrls.size < 4) {
                ytUrls = mutableListOf()
                val ytMirrorTracks = CustomPlaylistsStore.getYtMirrorTracks(ctx, ytPlaylistId)
                for (t in ytMirrorTracks) {
                    if (!t.imageUrl.isNullOrEmpty() && t.imageUrl !in ytUrls) {
                        ytUrls.add(t.imageUrl)
                        if (ytUrls.size >= 4) break
                    }
                }
            }
            if (ytUrls.size >= 4) {
                PlaylistGridArtLoader.load(ivThumb, ytUrls, thumbSizePx)
            } else if (ytUrls.isNotEmpty()) {
                loadArtworkInto(ivThumb, ytUrls[0])
            } else if (ytFallbackThumb.isNotEmpty()) {
                loadArtworkInto(ivThumb, ytFallbackThumb)
            }
            val isIn = CustomPlaylistsStore.isTrackInYtMirror(ctx, ytPlaylistId, track.videoId ?: "")
            ivCheck?.visibility = if (isIn) View.VISIBLE else View.GONE
            var checked = isIn
            val ytPName = ytItem.title ?: ""
            row.setOnClickListener {
                if (checked) {
                    CustomPlaylistsStore.removeTrackFromYtMirror(ctx, ytPlaylistId, track.videoId ?: "")
                    checked = false
                    didRemove = true
                    ivCheck?.visibility = View.GONE
                    if (lastAddedKey == ytMirrorKey) {
                        lastAddedKey = null
                        lastAddedName = null
                    }
                } else {
                    val tTitle = track.title?.takeIf { it.isNotEmpty() } ?: "Tema"
                    val tArtist = extractArtistFromSubtitle(track.subtitle)
                    val tImage = track.thumbnailUrl ?: ""
                    CustomPlaylistsStore.addTrackToYtMirror(ctx, ytPlaylistId, track.videoId ?: "",
                        tTitle, tArtist, "--:--", tImage, false)
                    checked = true
                    ivCheck?.visibility = View.VISIBLE
                    lastAddedKey = ytMirrorKey
                    lastAddedName = ytPName
                }
            }
            llList.addView(row)
        }

        saveDialog.behavior.skipCollapsed = true
        saveDialog.behavior.isFitToContents = true
        try { saveDialog.behavior.setHideFriction(0.5f) } catch (_: Throwable) {}
        saveDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        sheet.alpha = 0f
        saveDialog.setOnShowListener { d ->
            val bottomSheet = (d as com.google.android.material.bottomsheet.BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val sheetParent = sheet.parent as? View
            sheetParent?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomSheet.setBackgroundResource(android.R.color.transparent)
            sheet.post { sheet.animate().alpha(1f).setDuration(150L).start() }
        }
        saveDialog.show()
    }

    private fun showStatusBarSearch(message: String, onChangeClick: (() -> Unit)? = null) {
        if (!isAdded) return
        val activity = requireActivity() as? MainActivity ?: return
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(requireContext()).apply {
            tag = "saved_bar"
            id = View.generateViewId()
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#FF1E1E1E"))
            val hPad = (16 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 8 * density
        }

        val tvMsg = TextView(requireContext()).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvMsg)

        val changeClick = onChangeClick
        if (changeClick != null) {
            val btnChange = TextView(requireContext()).apply {
                text = "Cambiar"
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((16 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    TransientBottomBarAnimator.dismiss(bar) {
                        changeClick()
                    }
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
        TransientBottomBarAnimator.show(rootView, bar, flp, "saved_bar", 4000L)
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
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            val pid = playlistKey.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
            if (pid == YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID) "Música que te gustó"
            else MusicPlayerFragment.getYouTubeLibraryPlaylists().firstOrNull { it.contentId?.trim() == pid }?.title ?: "playlist"
        } else {
            "playlist"
        }
    }

    private fun showStatusBarSearchWithUndo(message: String, playlistKey: String, track: YouTubeMusicService.TrackResult) {
        if (!isAdded) return
        val activity = requireActivity() as? MainActivity ?: return
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(requireContext()).apply {
            tag = "saved_bar"
            id = View.generateViewId()
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#FF1E1E1E"))
            val hPad = (16 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 8 * density
        }

        val tvMsg = TextView(requireContext()).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvMsg)

        val btnUndo = TextView(requireContext()).apply {
            text = "Deshacer"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * density).toInt(), 0, 0, 0)
            setOnClickListener {
                TransientBottomBarAnimator.dismiss(bar) {
                    addTrackToPlaylistByKey(playlistKey, track)
                    Toast.makeText(requireContext(), "Restaurado en ${resolvePlaylistName(playlistKey)}", Toast.LENGTH_SHORT).show()
                }
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
        TransientBottomBarAnimator.show(rootView, bar, flp, "saved_bar", 4000L)
    }

    private fun isTrackInPlaylist(ctx: android.content.Context, videoId: String, playlistKey: String): Boolean {
        if (videoId.isEmpty()) return false
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            return FavoritesPlaylistStore.isFavorite(ctx, videoId)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            return CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).any { it.videoId == videoId }
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            val pid = playlistKey.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
            return CustomPlaylistsStore.isTrackInYtMirror(ctx, pid, videoId)
        }
        return false
    }

    private fun getPlaylistTrackCount(ctx: android.content.Context, playlistKey: String): Int {
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            return FavoritesPlaylistStore.loadFavorites(ctx).size
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            return CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).size
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            val pid = playlistKey.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
            return CustomPlaylistsStore.getYtMirrorTracks(ctx, pid).size
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
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            val pid = playlistKey.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
            CustomPlaylistsStore.removeTrackFromYtMirror(ctx, pid, videoId)
        }
    }

    private fun addTrackToPlaylistByKey(playlistKey: String, track: YouTubeMusicService.TrackResult) {
        if (track.videoId.isNullOrEmpty()) return
        val ctx = requireContext()
        val title = track.title?.takeIf { it.isNotEmpty() } ?: "Tema"
        val artist = extractArtistFromSubtitle(track.subtitle)
        val duration = extractDurationFromSubtitle(track.subtitle).ifEmpty { "--:--" }
        val imageUrl = track.thumbnailUrl ?: ""
        if (playlistKey == FavoritesPlaylistStore.PLAYLIST_ID) {
            FavoritesPlaylistStore.upsertFavorite(ctx, track.videoId, title, artist, duration, imageUrl)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
            val name = playlistKey.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
            CustomPlaylistsStore.addTrackToPlaylist(ctx, name, track.videoId, title, artist, duration, imageUrl)
        } else if (playlistKey.startsWith(CustomPlaylistsStore.YT_MIRROR_PREFIX)) {
            val pid = playlistKey.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
            CustomPlaylistsStore.addTrackToYtMirror(ctx, pid, track.videoId, title, artist, duration, imageUrl, false)
        }
        maybeEnqueueOfflineDownloadForTrack(playlistKey, track.videoId, title, artist)
    }

    private fun maybeEnqueueOfflineDownloadForTrack(playlistKey: String, videoId: String, title: String, artist: String) {
        if (!isAdded || videoId.isEmpty()) return
        val ctx = requireContext()
        val cachePrefs = ctx.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE)
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
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
    }

    private fun addToQueue(track: YouTubeMusicService.TrackResult, playNext: Boolean) {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            action = if (playNext) MainActivity.ACTION_PLAY_NEXT else MainActivity.ACTION_ADD_TO_QUEUE
            putExtra(EXTRA_RESULT_TYPE, track.resultType ?: "")
            putExtra(EXTRA_RESULT_VIDEO_ID, track.videoId ?: "")
            putExtra(EXTRA_RESULT_TITLE, track.title ?: "")
            putExtra(EXTRA_RESULT_SUBTITLE, extractArtistFromSubtitle(track.subtitle))
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
            putExtra(EXTRA_RESULT_SUBTITLE, extractArtistFromSubtitle(track.subtitle))
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
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
        // 2. Favorites
        try {
            FavoritesPlaylistStore.loadFavorites(ctx).firstOrNull { it.videoId == videoId }?.let {
                if (it.artist.isNotEmpty()) return it.artist
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
        // 3. Custom playlists
        try {
            for (name in CustomPlaylistsStore.getAllPlaylistNames(ctx)) {
                CustomPlaylistsStore.getTracksFromPlaylist(ctx, name).firstOrNull { it.videoId == videoId }?.let {
                    if (it.artist.isNotEmpty()) return it.artist
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
        // 4. Cached online playlists
        try {
            val cache = ctx.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
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
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error", e)
        }
        return ""
    }

    private fun loadPersistedGridUrls(ctx: android.content.Context, playlistId: String): List<String> {
        return try {
            val raw = ctx.applicationContext
                .getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, android.app.Activity.MODE_PRIVATE)
                .getString("playlist_grid_urls_$playlistId", "") ?: ""
            if (raw.isEmpty()) emptyList()
            else raw.split("\n").filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
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

    private fun getCookieHeader(): String {
        if (!isAdded) return ""
        val prefs = requireContext().getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, android.app.Activity.MODE_PRIVATE)
        return (prefs.getString("stream_last_youtube_web_cookie", "") ?: "").trim()
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
                holder.subtitle.text = buildCleanSearchSubtitle(item)
            } else {
                val clean = buildCleanSearchSubtitle(item)
                holder.subtitle.text = if (clean.isEmpty()) typeLabel else "$typeLabel • $clean"
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
