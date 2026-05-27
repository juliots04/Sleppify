package com.example.sleppify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import android.transition.TransitionManager
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * Optimized Kotlin version of MainActivity.
 * Central hub for navigation, authentication, and core UI orchestration.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_MODULE_PRINCIPAL = "module_principal"
        private const val TAG_MODULE_MUSIC = "module_music"
        private const val TAG_MODULE_SCANNER = "module_scanner"
        private const val TAG_MODULE_EQUALIZER = "module_equalizer"
        const val TAG_MODULE_SETTINGS = "module_settings"
        const val TAG_MODULE_SEARCH = "module_search"
        private const val TAG_PLAYLIST_DETAIL = "playlist_detail"
        private const val TAG_SONG_PLAYER = "song_player"

        private const val PREFS_PLAYER_STATE = "player_state"
        private const val PREFS_BOOTSTRAP = "sleppify_bootstrap"
        private const val PREF_LAST_STREAM_SCREEN = "stream_last_screen"
        private const val PREF_LAST_MAIN_MODULE = "last_main_module"
        private const val STREAM_SCREEN_LIBRARY = "library"
        private const val STREAM_SCREEN_PLAYLIST_DETAIL = "playlist_detail"

        private const val RESUME_WORK_DELAY_MS = 180L
        private const val PREFETCH_DEBOUNCE_MS = 800L
        private const val PREFETCH_MIN_INTERVAL_MS = 3 * 60 * 60 * 1000L // 3 hours
        private const val MODULE_LOAD_OVERLAY_MIN_MS = 200L
        private const val MODULE_CONTENT_FADE_IN_MS = 280L
        
        const val ACTION_PLAY_FROM_SEARCH = "com.example.sleppify.ACTION_PLAY_FROM_SEARCH"
        const val ACTION_PLAY_NEXT = "com.example.sleppify.ACTION_PLAY_NEXT"
        const val ACTION_ADD_TO_QUEUE = "com.example.sleppify.ACTION_ADD_TO_QUEUE"
        const val ACTION_OPEN_CURRENT_PLAYER = "com.example.sleppify.ACTION_OPEN_CURRENT_PLAYER"
        const val ACTION_TOGGLE_CURRENT_PLAYBACK = "com.example.sleppify.ACTION_TOGGLE_CURRENT_PLAYBACK"
        const val ACTION_PAUSE_CURRENT_PLAYBACK = "com.example.sleppify.ACTION_PAUSE_CURRENT_PLAYBACK"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.example.sleppify.action.PLAY_PAUSE"
        const val ACTION_MEDIA_NEXT = "com.example.sleppify.action.NEXT"
        const val ACTION_MEDIA_PREV = "com.example.sleppify.action.PREV"
        private const val REQUEST_CODE_RECORD_AUDIO = 4107

        @Volatile
        private var activeInstance: WeakReference<MainActivity>? = null

        @JvmStatic
        fun dispatchSearchPlayback(intent: Intent): Boolean {
            val activity = activeInstance?.get() ?: return false
            // Permite procesar el intent incluso si la actividad está en background (parada),
            // siempre que no haya sido destruida. Esto es vital para SearchActivity.
            if (activity.isDestroyed || activity.isFinishing) {
                return false
            }
            activity.runOnUiThread {
                activity.handlePlayFromSearchIntent(intent)
            }
            return true
        }
    }

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tvModuleTitle: TextView
    private lateinit var btnProfilePhoto: com.google.android.material.imageview.ShapeableImageView
    private lateinit var btnHeaderSearch: ImageView
    private lateinit var btnCamera: ImageView
    private lateinit var topAppBar: View
    private lateinit var fragmentContainer: View
    private lateinit var moduleLoadingOverlay: View
    private lateinit var scannerLoadingOverlay: View
    private var btnSignInHeader: MaterialButton? = null

    private var inSettings = false
    private var inEqualizerFromSettings = false
    private var inEqualizerFromPlayer = false
    private var inScannerFromSettings = false
    private var isNavigating = false
    private var suppressNavListener = false


    private val authManagerLazy: AuthManager by lazy { AuthManager.getInstance(this) }

    /** Exposed for Java callers (e.g. [WeeklySchedulerFragment]). */
    fun getAuthManager(): AuthManager = authManagerLazy

    private val cloudSyncManager: CloudSyncManager by lazy { CloudSyncManager.getInstance(this) }
    
    private var principalFragment: Fragment? = null
    private var musicFragment: Fragment? = null
    private var scannerFragment: Fragment? = null
    private var equalizerFragment: Fragment? = null
    private var settingsFragment: Fragment? = null
    private var searchFragment: Fragment? = null
    private var playlistDetailFragment: Fragment? = null
    private var songPlayerFragment: Fragment? = null

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var localPrefs: SharedPreferences

    private var statusBarHeightPx = 0
    private var currentMainNavItemId = View.NO_ID
    private var lastSmartPrefetchAtMs = 0L
    private var hasAudioServiceStateSnapshot = false
    private var lastEqEnabled = false
    private var lastSpatialEnabled = false
    private var lastReverbLevel = 0
    
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjectionPermissionLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingAudioProcessingAuthorization = false
    private var headerBrandTypeface: Typeface? = null
    private var headerSettingsTypeface: Typeface? = null
    private var audioManager: AudioManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var wasNetworkAvailable: Boolean? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var globalMiniPlayer: GlobalMiniPlayerController? = null

    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>?) {
            syncAudioProfile(true)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
            syncAudioProfile(true)
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (inScannerFromSettings) {
                returnFromScanner()
                return
            }
            if (inEqualizerFromPlayer) {
                exitEqualizerToMusic()
                return
            }
            if (inEqualizerFromSettings) {
                exitEqualizerToSettings()
                return
            }
            if (inSettings) {
                returnFromSettings()
                return
            }

            if (handleSongPlayerBackPressed()) return
            if (handlePlaylistDetailBackPressed()) return

            if (shouldMoveTaskToBackForOngoingPlayback()) {
                moveTaskToBack(true)
                return
            }

            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        activeInstance = WeakReference(this)
        setContentView(R.layout.activity_main)
        applySystemBarsStyle()
        PlaybackLoadingBus.clearLoading()

        initViews()
        globalMiniPlayer = GlobalMiniPlayerController(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        syncAudioProfile(false) // Initial sync on startup
        // Cargar cookies de la sesión web YouTube Music (si existen) lo más
        // temprano posible para que las primeras peticiones InnerTube ya estén
        // autenticadas y eviten LOGIN_REQUIRED / 403 en el CDN.
        InnertubeResolver.loadAuthCookiesFromPrefs(this)
        setupListeners()
        configureHeaderActionForMainModules()
        configureAudioAuthorizationFlow()
        restoreMainModuleReferences()

        settingsPrefs = getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
        localPrefs = getSharedPreferences("sleppify_local_config", Context.MODE_PRIVATE)
        updateNavigationForScreenSize()

        // Always open directly on Biblioteca — no login gate.
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_music
        suppressNavListener = false
        currentMainNavItemId = R.id.nav_music
        switchToMainModule(R.id.nav_music)
        showMainShell()

        // Defer heavy startup work to background thread
        lifecycleScope.launch {
            delay(100) // Allow UI to render first
            withContext(Dispatchers.IO) {
                syncAudioEffectsServiceFromPreferences(forceSync = true)
            }
            // Sync signed-in user state if already authenticated
            if (authManagerLazy.isSignedIn()) {
                authManagerLazy.getCurrentUser()?.let { handleSignedInUser(it, null) }
            }
        }

        if (intent?.getBooleanExtra("SHOW_SETTINGS", false) == true) {
            enterSettings()
        }
        if (intent?.action == ACTION_PLAY_FROM_SEARCH
            || intent?.action == ACTION_PLAY_NEXT
            || intent?.action == ACTION_ADD_TO_QUEUE
            || intent?.action == ACTION_OPEN_CURRENT_PLAYER
            || intent?.action == ACTION_TOGGLE_CURRENT_PLAYBACK) {
            bottomNav.post { handlePlayFromSearchIntent(intent) }
        }
        if (intent?.action == ACTION_MEDIA_PLAY_PAUSE
            || intent?.action == ACTION_MEDIA_NEXT
            || intent?.action == ACTION_MEDIA_PREV) {
            bottomNav.post { dispatchMediaNotificationAction(intent.action) }
        }

        registerNetworkCallback()
    }

    private fun initViews() {
        val root = findViewById<View>(R.id.main)
        bottomNav = findViewById(R.id.bottomNavigation)
        tvModuleTitle = findViewById(R.id.tvModuleTitle)
        btnProfilePhoto = findViewById(R.id.btnProfilePhoto)
        btnHeaderSearch = findViewById(R.id.btnHeaderSearch)
        btnCamera = findViewById(R.id.btnCamera)
        topAppBar = findViewById(R.id.topAppBar)
        fragmentContainer = findViewById(R.id.fragmentContainer)
        moduleLoadingOverlay = findViewById(R.id.moduleLoadingOverlay)
        scannerLoadingOverlay = findViewById(R.id.scannerLoadingOverlay)
        btnSignInHeader = findViewById(R.id.btnSignInHeader)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarHeightPx = systemBars.top
            topAppBar.setPadding(topAppBar.paddingLeft, statusBarHeightPx, topAppBar.paddingRight, topAppBar.paddingBottom)
            
            if (bottomNav.paddingBottom != systemBars.bottom) {
                bottomNav.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            }
            
            val playerContainer = findViewById<View>(R.id.playerContainer)
            playerContainer?.setPadding(0, 0, 0, systemBars.bottom)
            
            insets
        }
    }

    private fun updateNavigationForScreenSize() {
        bottomNav.visibility = if (inSettings || inEqualizerFromSettings || inScannerFromSettings) View.GONE else View.VISIBLE
    }

    private fun setupListeners() {
        btnSignInHeader?.setOnClickListener { triggerHeaderSignIn() }
        btnProfilePhoto.setOnClickListener { if (inSettings) returnFromSettings() else enterSettings() }
        btnCamera.setOnClickListener { openScannerFromSettings() }
        bottomNav.setOnItemSelectedListener { item ->
            if (suppressNavListener) return@setOnItemSelectedListener true
            if (item.itemId == R.id.nav_search) {
                if (inSettings) returnFromSettings()
                openSearchFragment()
                return@setOnItemSelectedListener true
            }
            if (inSettings) returnFromSettings()
            if (isSearchFragmentVisible()) {
                currentMainNavItemId = item.itemId
                closeSearchFragment()
                return@setOnItemSelectedListener true
            }
            switchToMainModule(item.itemId)
        }
        cloudSyncManager.setSyncStateListener(this::setSyncOverlayVisible)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_PLAY_FROM_SEARCH || intent.action == ACTION_PLAY_NEXT || intent.action == ACTION_ADD_TO_QUEUE) {
            handlePlayFromSearchIntent(intent)
        } else if (intent.action == ACTION_OPEN_CURRENT_PLAYER || intent.action == ACTION_TOGGLE_CURRENT_PLAYBACK
                || intent.action == ACTION_PAUSE_CURRENT_PLAYBACK) {
            handlePlayFromSearchIntent(intent)
        } else if (intent.action == ACTION_MEDIA_PLAY_PAUSE
                || intent.action == ACTION_MEDIA_NEXT
                || intent.action == ACTION_MEDIA_PREV) {
            dispatchMediaNotificationAction(intent.action)
        }
        if (intent.getBooleanExtra("SHOW_SETTINGS", false)) {
            enterSettings()
        }
    }

    private fun dispatchMediaNotificationAction(action: String?) {
        val fm = supportFragmentManager
        val player = fm.findFragmentByTag(TAG_SONG_PLAYER)
        if (player is SongPlayerFragment) {
            when (action) {
                ACTION_MEDIA_PLAY_PAUSE -> player.externalTogglePlayback()
                ACTION_MEDIA_NEXT -> player.externalSkipNext()
                ACTION_MEDIA_PREV -> player.externalSkipPrevious()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_main_nav_item_id", currentMainNavItemId)
        outState.putBoolean("in_settings", inSettings)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Always land on music — do not restore whichever module was last open.
        // (See matching policy in onCreate.)
        currentMainNavItemId = R.id.nav_music
        inSettings = savedInstanceState.getBoolean("in_settings", false)
    }

    fun handlePlayFromSearchIntent(intent: Intent) {

        if (currentMainNavItemId != R.id.nav_music) {
            bottomNav.selectedItemId = R.id.nav_music
            switchToMainModule(R.id.nav_music)
        }

        val invokedNow = invokeSearchActionOnMusicFragment(intent)
        if (!invokedNow) {
            bottomNav.postDelayed({ invokeSearchActionOnMusicFragment(intent) }, 220L)
        }
    }

    private fun invokeSearchActionOnMusicFragment(intent: Intent): Boolean {
        val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
        if (music == null || music.javaClass.simpleName != "MusicPlayerFragment") {
            return false
        }
        return try {
            val methodName = when (intent.action) {
                ACTION_PLAY_NEXT -> "playNextFromSearch"
                ACTION_ADD_TO_QUEUE -> "addToQueueFromSearch"
                ACTION_OPEN_CURRENT_PLAYER -> "openPlayerFromMiniBar"
                ACTION_TOGGLE_CURRENT_PLAYBACK -> "toggleMiniPlayback"
                ACTION_PAUSE_CURRENT_PLAYBACK -> "pauseMiniPlayback"
                else -> "playTrackFromSearch"
            }

            // Try method with Intent parameter first (common for play actions)
            try {
                val methodWithIntent = music.javaClass.getDeclaredMethod(methodName, Intent::class.java)
                methodWithIntent.isAccessible = true
                methodWithIntent.invoke(music, intent)
                return true
            } catch (noIntent: NoSuchMethodException) {
                // Fall through and try no-arg method
            }

            try {
                val methodNoArg = music.javaClass.getDeclaredMethod(methodName)
                methodNoArg.isAccessible = true
                methodNoArg.invoke(music)
                return true
            } catch (noArgEx: NoSuchMethodException) {
                Log.e("MainActivity", "Method $methodName not found on MusicPlayerFragment", noArgEx)
                return false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error invoking search playback method: ${e.message}", e)
            false
        }
    }

    override fun onResume() {
        super.onResume()
        globalMiniPlayer?.onResume()
        lifecycleScope.launch {
            delay(RESUME_WORK_DELAY_MS)
            runDeferredResumeWork()
        }
    }

    override fun onPause() {
        globalMiniPlayer?.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        runCatching { audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null) }
    }

    override fun onStop() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback) }
        super.onStop()
    }

    fun refreshSessionUi() {
        runDeferredResumeWork()
    }

    fun refreshMusicLibrary() {
        val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
        if (music is MusicPlayerFragment && music.isAdded) {
            music.refreshLibraryUi()
        }
    }

    fun onAllDownloadsDeleted() {
        val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
        if (music is MusicPlayerFragment && music.isAdded) {
            music.refreshLibraryUi()
        }
    }

    fun notifyOfflineModeChanged() {
        val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
        if (music is MusicPlayerFragment && music.isAdded) {
            music.refreshLibraryUi()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val m = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
                if (m is MusicPlayerFragment && m.isAdded) m.refreshLibraryUi()
            }, 800)
        }
        val detail = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
        if (detail is PlaylistDetailFragment && detail.isAdded) {
            detail.refreshForOfflineModeChange()
        }
        val settings = supportFragmentManager.findFragmentByTag(TAG_MODULE_SETTINGS)
        if (settings is SettingsFragment && settings.isAdded) {
            settings.refreshOfflineStateFromPrefs()
        }
    }

    private fun runDeferredResumeWork() {
        if (isFinishing || isDestroyed) return

        cloudSyncManager.setSyncStateListener(this::setSyncOverlayVisible)
        syncAudioEffectsServiceFromPreferences(false)

        showMainShell()
        // AI prefetch deliberately disabled here: the only allowed triggers are
        // pull-to-refresh in the agenda and task creation (see WeeklySchedulerFragment).
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsStyle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    @Suppress("DEPRECATION")
    private fun setSolidNavigationBar(solid: Boolean) {
        val navColor = if (solid) ContextCompat.getColor(this, R.color.surface_low) else Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = navColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = solid
        }
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        cloudSyncManager.setSyncStateListener(null)
        unregisterNetworkCallback()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        // Seed initial state without showing bar
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        wasNetworkAvailable = caps != null
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // onAvailable fires before validation; wait for onCapabilitiesChanged
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                val hasInternet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (hasInternet && wasNetworkAvailable != true) {
                    wasNetworkAvailable = true
                    mainHandler.post { onNetworkRestored() }
                }
            }

            override fun onLost(network: android.net.Network) {
                // Delay check to allow system to fully release network and avoid false positive
                mainHandler.postDelayed({
                    val cmInner = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val active = cmInner?.activeNetwork
                    val activeCaps = if (active != null) cmInner.getNetworkCapabilities(active) else null
                    val stillOnline = activeCaps != null
                            && activeCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && activeCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (!stillOnline && wasNetworkAvailable != false) {
                        wasNetworkAvailable = false
                        onNetworkLost()
                    }
                }, 400L)
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun onNetworkLost() {
        if (isFinishing || isDestroyed) return
        settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, true).apply()
        showNetworkStatusBar("Modo offline activado", android.graphics.Color.parseColor("#FF1E1E1E"))
        mainHandler.postDelayed({ notifyOfflineModeChanged() }, 1500L)
    }

    private fun onNetworkRestored() {
        if (isFinishing || isDestroyed) return
        settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false).apply()
        showNetworkStatusBar("Vuelves a tener conexión", android.graphics.Color.parseColor("#FF00C853"))
        mainHandler.postDelayed({ notifyOfflineModeChanged() }, 1500L)
    }

    private fun showNetworkStatusBar(message: String, bgColor: Int) {
        val root = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main) ?: return
        val existing = root.findViewWithTag<View>("network_status_bar")
        if (existing != null) root.removeView(existing)

        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(this).apply {
            tag = "network_status_bar"
            id = View.generateViewId()
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(bgColor)
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 45 * density
        }

        val tv = TextView(this).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val font = ResourcesCompat.getFont(this@MainActivity, R.font.inter_variable)
            setTypeface(font, android.graphics.Typeface.NORMAL)
        }
        bar.addView(tv)

        // Compute extra margin if global mini-player is visible
        var extraMiniPlayerMargin = 0
        val miniPlayer = findViewById<View>(R.id.llGlobalMiniPlayer)
        if (miniPlayer != null && miniPlayer.visibility == View.VISIBLE) {
            extraMiniPlayerMargin = miniPlayer.height
        }

        val clp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomToTop = R.id.bottomNavigation
            bottomMargin = (12 * density).toInt() + extraMiniPlayerMargin
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        // Start off-screen and slide up
        bar.translationY = 100 * density
        bar.alpha = 0f
        root.addView(bar, clp)
        bar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .start()

        // Auto-dismiss after 4 seconds
        mainHandler.postDelayed({
            if (bar.parent != null) {
                bar.animate()
                    .translationY(100 * density)
                    .alpha(0f)
                    .setDuration(280)
                    .withEndAction { (bar.parent as? android.view.ViewGroup)?.removeView(bar) }
                    .start()
            }
        }, 4000L)
    }

    fun requestAudioEffectsApplyFromUi() = syncAudioEffectsServiceFromPreferences(true, true)

    fun requestAudioEffectsStopFromUi() {
        hasAudioServiceStateSnapshot = false
        AudioEffectsService.sendStop(applicationContext)
    }

    private fun syncAudioProfile(applyIfChanged: Boolean) {
        val manager = audioManager ?: return
        val audioPrefs = getSharedPreferences(AudioEffectsService.PREFS_NAME, MODE_PRIVATE)
        val selected = AudioDeviceProfileStore.selectPreferredOutput(manager)

        val profileSwitched = AudioDeviceProfileStore.syncActiveProfileForOutput(audioPrefs, selected)

        if (profileSwitched && applyIfChanged) {
            syncAudioEffectsServiceFromPreferences(forceSync = true)
            // Notify EqualizerFragment if it's active
            (getMainModuleFragment(R.id.nav_equalizer) as? EqualizerFragment)?.onOutputProfileSwitchedLocally()
        }
    }

    private fun syncAudioEffectsServiceFromPreferences(forceSync: Boolean, allowAuthorizationPrompt: Boolean = false) {
        val audioPrefs = getSharedPreferences(AudioEffectsService.PREFS_NAME, MODE_PRIVATE)

        val eqEnabled = audioPrefs.getBoolean(AudioEffectsService.KEY_ENABLED, false)
        val spatialEnabled = audioPrefs.getBoolean(AudioEffectsService.KEY_SPATIAL_ENABLED, false)
        val reverbLevel = audioPrefs.getInt(AudioEffectsService.KEY_REVERB_LEVEL, AudioEffectsService.REVERB_LEVEL_OFF)

        if (!forceSync && hasAudioServiceStateSnapshot && eqEnabled == lastEqEnabled && spatialEnabled == lastSpatialEnabled && reverbLevel == lastReverbLevel) {
            return
        }

        hasAudioServiceStateSnapshot = true
        lastEqEnabled = eqEnabled
        lastSpatialEnabled = spatialEnabled
        lastReverbLevel = reverbLevel

        if (eqEnabled) AudioEffectsService.sendApply(applicationContext) else AudioEffectsService.sendStop(applicationContext)
    }

    private fun configureAudioAuthorizationFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                onMediaProjectionPermissionResult(result.resultCode, result.data)
            }
        }
    }

    private fun onMediaProjectionPermissionResult(resultCode: Int, data: Intent?) {
        pendingAudioProcessingAuthorization = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            (application as? SleppifyApp)?.setMediaProjectionPermissionData(data)
            syncAudioEffectsServiceFromPreferences(true)
        } else {
            AudioEffectsService.sendStop(applicationContext)
            Toast.makeText(this, "Permiso denegado para el EQ system-wide.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                if (pendingAudioProcessingAuthorization && ensureAudioProcessingAuthorization(true)) {
                    pendingAudioProcessingAuthorization = false
                    syncAudioEffectsServiceFromPreferences(true)
                }
            } else {
                pendingAudioProcessingAuthorization = false
                AudioEffectsService.sendStop(applicationContext)
                Toast.makeText(this, "Se requiere permiso de micrófono.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensureAudioProcessingAuthorization(allowPrompt: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (allowPrompt) {
                pendingAudioProcessingAuthorization = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO)
            }
            return false
        }

        if ((application as? SleppifyApp)?.hasMediaProjectionPermissionData() == true) return true

        if (allowPrompt) {
            mediaProjectionManager?.let { mgr ->
                pendingAudioProcessingAuthorization = true
                mediaProjectionPermissionLauncher?.launch(mgr.createScreenCaptureIntent())
            }
        }
        return false
    }

    fun requireAuth(onSuccess: Runnable? = null, onError: Runnable? = null) {
        if (authManagerLazy.isSignedIn()) {
            authManagerLazy.getCurrentUser()?.let {
                handleSignedInUser(it, onSuccess)
                return
            }
        }

        authManagerLazy.signIn(this, object : AuthManager.AuthCallback {
            override fun onSuccess(user: FirebaseUser) {
                handleSignedInUser(user, onSuccess)
            }
            override fun onError(message: String) {
                onError?.run()
            }
        })
    }

    private fun triggerHeaderSignIn() {
        btnSignInHeader?.isEnabled = false
        btnSignInHeader?.alpha = 0.56f
        requireAuth(
            onSuccess = {
                btnSignInHeader?.isEnabled = true
                btnSignInHeader?.alpha = 1f
                loadProfilePhoto()
            },
            onError = {
                btnSignInHeader?.isEnabled = true
                btnSignInHeader?.alpha = 1f
            }
        )
    }

    private fun showMainShell() {
        // Do not force header/bottomNav visible when inside overlay screens
        if (inSettings || inEqualizerFromSettings || inEqualizerFromPlayer || inScannerFromSettings) return
        if (!isSearchFragmentVisible() && !isPlaylistDetailVisible()) {
            // Music and Principal fragments own their own header — hide topAppBar for them
            val isFragOwnedHeader = currentMainNavItemId == R.id.nav_music || currentMainNavItemId == R.id.nav_principal
            topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
        }
        fragmentContainer.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        // Refresh profile photo / sign-in button every time the shell becomes visible
        loadProfilePhoto()
    }

    private fun handleSignedInUser(user: FirebaseUser, onSuccess: Runnable?) {
        // Immediately refresh header so sign-in button swaps to profile photo
        // without waiting for cloud hydration to complete.
        runOnUiThread {
            loadProfilePhoto()
            val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
            if (music is MusicPlayerFragment && music.isAdded) {
                music.refreshFragHeaderProfilePhoto()
            }
            val principal = supportFragmentManager.findFragmentByTag(TAG_MODULE_PRINCIPAL)
            if (principal is PrincipalFragment && principal.isAdded) {
                principal.refreshFragHeaderProfilePhoto()
            }
        }
        cloudSyncManager.onUserSignedIn(user.uid) { ok, _ ->
            if (!ok) return@onUserSignedIn
            applyHydratedUserState(onSuccess)
        }
    }

    private fun applyHydratedUserState(onSuccess: Runnable?) {
        val finalize = {
            if (!isFinishing && !isDestroyed) {
                notifyHydrationCompleted()
                syncAudioEffectsServiceFromPreferences(true)
                // Always refresh header (swap sign-in button → profile photo)
                runOnUiThread { loadProfilePhoto() }
                // Refresh library so playlists/favorites from cloud appear immediately
                val music = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
                if (music is MusicPlayerFragment && music.isAdded) {
                    runOnUiThread { music.refreshLibraryUi() }
                }
                onSuccess?.run()
            }
        }

        finalize()
    }

    private fun notifyHydrationCompleted() {
        (getMainModuleFragment(R.id.nav_equalizer) as? EqualizerFragment)?.onCloudEqHydrationCompleted()
    }

    private fun setSyncOverlayVisible(visible: Boolean) { /* Signals visual sync in header */ }

    fun getStatusBarHeight(): Int = statusBarHeightPx

    private fun setTopAppBarExtraTopPadding(extraDp: Int) {
        val density = resources.displayMetrics.density
        val extraPx = (extraDp * density).toInt()
        topAppBar.setPadding(topAppBar.paddingLeft, statusBarHeightPx + extraPx, topAppBar.paddingRight, topAppBar.paddingBottom)
    }

    private fun configureHeaderActionForMainModules() {
        setTopAppBarExtraTopPadding(0)
        btnCamera.visibility = View.GONE
        btnHeaderSearch.visibility = View.VISIBLE
        btnHeaderSearch.setOnClickListener { openSearchFragment() }

        // Profile photo as settings button
        btnProfilePhoto.visibility = View.VISIBLE
        btnProfilePhoto.contentDescription = getString(R.string.header_action_settings)
        btnProfilePhoto.setOnClickListener { enterSettings() }
        loadProfilePhoto()

        tvModuleTitle.apply {
            text = getString(R.string.header_brand_title)
            isAllCaps = true
            letterSpacing = 0.08f
            typeface = resolveHeaderBrandTypeface()
            
            val density = resources.displayMetrics.density
            val iconSize = (26 * density).toInt()
            ContextCompat.getDrawable(this@MainActivity, R.mipmap.ic_launcher)?.apply {
                setBounds(0, 0, iconSize, iconSize)
                setCompoundDrawablesRelative(this, null, null, null)
            }
            compoundDrawablePadding = (8 * density).toInt()
            setOnClickListener(null)
        }
    }

    private fun loadProfilePhoto() {
        val prefs = getSharedPreferences("streaming_cache", MODE_PRIVATE)
        val cachedUrl = prefs.getString("cached_google_profile_photo_url", "") ?: ""
        val photoUri = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl
            ?: if (cachedUrl.isNotEmpty()) android.net.Uri.parse(cachedUrl) else null

        val signedIn = authManagerLazy.isSignedIn()
        if (signedIn) {
            btnSignInHeader?.visibility = View.GONE
            btnProfilePhoto.visibility = View.VISIBLE
            if (photoUri != null) {
                com.bumptech.glide.Glide.with(this)
                    .load(photoUri)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop()
                    .into(btnProfilePhoto)
            } else {
                btnProfilePhoto.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        } else {
            btnProfilePhoto.visibility = View.GONE
            btnProfilePhoto.setImageDrawable(null)
            btnSignInHeader?.visibility = View.VISIBLE
        }
    }

    private fun configureHeaderActionForSettings() {
        setTopAppBarExtraTopPadding(14)
        btnProfilePhoto.visibility = View.GONE
        btnSignInHeader?.visibility = View.GONE
        btnHeaderSearch.visibility = View.GONE
        btnCamera.visibility = View.VISIBLE

        tvModuleTitle.apply {
            text = getString(R.string.header_title_settings)
            isAllCaps = false
            letterSpacing = 0f
            typeface = resolveHeaderSettingsTypeface()
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_arrow_back, 0, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            setOnClickListener { returnFromSettings() }
        }
    }

    private fun configureHeaderActionForScanner() {
        setTopAppBarExtraTopPadding(14)
        btnProfilePhoto.visibility = View.GONE
        btnSignInHeader?.visibility = View.GONE
        btnCamera.visibility = View.GONE

        tvModuleTitle.apply {
            text = "Scanner"
            isAllCaps = false
            letterSpacing = 0f
            typeface = resolveHeaderSettingsTypeface()
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_arrow_back, 0, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            setOnClickListener { returnFromScanner() }
        }
    }

    private fun resolveHeaderBrandTypeface() = headerBrandTypeface ?: ResourcesCompat.getFont(this, R.font.manrope_variable).also { headerBrandTypeface = it } ?: Typeface.DEFAULT_BOLD

    private fun resolveHeaderSettingsTypeface() = headerSettingsTypeface ?: ResourcesCompat.getFont(this, R.font.inter_variable).also { headerSettingsTypeface = it } ?: Typeface.DEFAULT_BOLD

    fun enterSettings() {
        if (inSettings) return
        dismissSavedBar()
        if (bottomNav.selectedItemId == R.id.nav_music) markStreamingEntryAsLibrary()

        inSettings = true
        globalMiniPlayer?.hide()
        val target = settingsFragment ?: SettingsFragment().also { settingsFragment = it }
        val isNew = !target.isAdded
        setOverlayFullscreen(true)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
                songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
                val current = getMainModuleFragment(currentMainNavItemId)
                hideIfVisible(this, current, target)
                hideIfVisible(this, playlistDetailFragment, target)
                hideIfVisible(this, songPlayerFragment, target)
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_SETTINGS)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    fun returnFromSettings() {
        if (!inSettings) return
        inSettings = false
        val selectedId = bottomNav.selectedItemId
        val target = getMainModuleFragment(selectedId) ?: getOrCreateMainModuleFragment(selectedId)
        val isNew = target?.isAdded == false
        setOverlayFullscreen(false)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                settingsFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
                target?.let {
                    if (it.isAdded) show(it) else moduleTagForItem(selectedId)?.let { tag -> add(R.id.fragmentContainer, it, tag) }
                    setMaxLifecycle(it, Lifecycle.State.RESUMED)
                }
                commit()
            }
            val isFragOwnedHeader = selectedId == R.id.nav_music || selectedId == R.id.nav_principal
            topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
            configureHeaderActionForMainModules()
            setSolidNavigationBar(false)
            bottomNav.visibility = View.VISIBLE
            updateHeaderTitleForModule(selectedId)
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    fun openEqualizerFromPlayer() {
        if (isNavigating) return
        inEqualizerFromPlayer = true
        inEqualizerFromSettings = false
        globalMiniPlayer?.hide()
        val target = equalizerFragment ?: EqualizerFragment().also { equalizerFragment = it }
        val isNew = !target.isAdded
        setOverlayFullscreen(true)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                val current = getMainModuleFragment(currentMainNavItemId)
                hideIfVisible(this, current, target)
                hideIfVisible(this, supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL), target)
                hideIfVisible(this, supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER), target)
                hideIfVisible(this, settingsFragment, target)
                hideIfVisible(this, searchFragment, target)
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_EQUALIZER)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    fun returnFromEqualizer() {
        if (inEqualizerFromSettings) exitEqualizerToSettings()
        else if (inEqualizerFromPlayer) exitEqualizerToMusic()
    }

    private fun exitEqualizerToMusic() {
        if (!inEqualizerFromPlayer) return
        inEqualizerFromPlayer = false
        val songPlayer = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
        setOverlayFullscreen(false)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                equalizerFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
                if (songPlayer != null && songPlayer.isAdded) {
                    show(songPlayer)
                    setMaxLifecycle(songPlayer, Lifecycle.State.RESUMED)
                }
                commit()
            }
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    fun openEqualizerFromSettings() {
        if (isNavigating) return
        inEqualizerFromSettings = true
        globalMiniPlayer?.hide()
        val target = equalizerFragment ?: EqualizerFragment().also { equalizerFragment = it }
        val isNew = !target.isAdded
        setOverlayFullscreen(true)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
                songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
                val current = getMainModuleFragment(currentMainNavItemId)
                hideIfVisible(this, current, target)
                hideIfVisible(this, playlistDetailFragment, target)
                hideIfVisible(this, songPlayerFragment, target)
                hideIfVisible(this, settingsFragment, target)
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_EQUALIZER)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    private fun exitEqualizerToSettings() {
        if (!inEqualizerFromSettings) return
        inEqualizerFromSettings = false
        val target = settingsFragment ?: SettingsFragment().also { settingsFragment = it }
        val isNew = !target.isAdded
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                equalizerFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_SETTINGS)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    private fun configureHeaderActionForEqualizer() {
        setTopAppBarExtraTopPadding(14)
        btnProfilePhoto.visibility = View.GONE
        btnSignInHeader?.visibility = View.GONE
        btnCamera.visibility = View.GONE
        btnHeaderSearch.visibility = View.GONE

        tvModuleTitle.apply {
            text = "Equalizer"
            isAllCaps = false
            letterSpacing = 0f
            typeface = resolveHeaderSettingsTypeface()
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_arrow_back, 0, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            setOnClickListener { exitEqualizerToSettings() }
        }
    }

    fun openScannerFromSettings() {
        if (isNavigating) return
        inScannerFromSettings = true
        globalMiniPlayer?.hide()
        val target = scannerFragment ?: ScannerFragment().also { scannerFragment = it }
        val isNew = !target.isAdded
        scannerLoadingOverlay.alpha = 1f
        scannerLoadingOverlay.visibility = View.VISIBLE
        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            topAppBar.visibility = View.GONE
            setSolidNavigationBar(true)
            bottomNav.visibility = View.GONE
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
                songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
                val current = getMainModuleFragment(currentMainNavItemId)
                hideIfVisible(this, current, target)
                hideIfVisible(this, playlistDetailFragment, target)
                hideIfVisible(this, songPlayerFragment, target)
                hideIfVisible(this, settingsFragment, target)
                hideIfVisible(this, equalizerFragment, target)
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_SCANNER)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            fragmentContainer.post { fragmentContainer.post {
                if (isFinishing || isDestroyed) return@post
                scannerLoadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(150L)
                    .withEndAction { scannerLoadingOverlay.visibility = View.GONE }
                    .start()
            } }
        }
    }

    fun returnFromScanner() {
        if (!inScannerFromSettings) return
        inScannerFromSettings = false

        showModuleLoadingOverlay()

        val returnToEqualizer = inEqualizerFromSettings
        val target = if (returnToEqualizer) {
            equalizerFragment ?: EqualizerFragment().also { equalizerFragment = it }
        } else {
            settingsFragment ?: SettingsFragment().also { settingsFragment = it }
        }
        val tag = if (returnToEqualizer) TAG_MODULE_EQUALIZER else TAG_MODULE_SETTINGS
        val isNew = !target.isAdded

        fragmentContainer.post {
            if (isFinishing || isDestroyed) return@post
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                scannerFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
                if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, tag)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commit()
            }
            topAppBar.visibility = View.GONE
            bottomNav.visibility = View.GONE
            setSolidNavigationBar(true)
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
    }

    fun findSongPlayerFragment(): SongPlayerFragment? {
        return supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment
    }

    fun dismissSavedBar() {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val bar = rootView.findViewWithTag<View>("saved_bar")
        if (bar != null) rootView.removeView(bar)
    }

    fun openSongPlayer() {
        dismissSavedBar()
        val player = findSongPlayerFragment() ?: return
        if (player.isAdded) {
            globalMiniPlayer?.animateOut()
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .show(player)
                .setMaxLifecycle(player, Lifecycle.State.RESUMED)
                .runOnCommit { player.externalAnimateEnterSlide() }
                .commit()
        }
    }

    fun isSongPlayerVisible(): Boolean {
        val player = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment ?: return false
        return player.isAdded && !player.isHidden && !player.isRemoving
    }

    fun getGlobalMiniPlayer(): GlobalMiniPlayerController? = globalMiniPlayer
    fun getGlobalMiniPlayerController(): GlobalMiniPlayerController? = globalMiniPlayer

    fun isMiniPlayerAllowedForCurrentScreen(): Boolean {
        // Mini-player only in these 4 screens — block all overlay screens
        if (inSettings || inEqualizerFromSettings || inEqualizerFromPlayer || inScannerFromSettings) return false
        if (isSearchFragmentVisible()) return true
        if (isPlaylistDetailVisible()) return true
        return when (currentMainNavItemId) {
            R.id.nav_music, R.id.nav_principal -> true
            else -> false
        }
    }

    fun isSearchFragmentVisible(): Boolean {
        val sf = searchFragment ?: return false
        return sf.isAdded && !sf.isHidden && !sf.isRemoving
    }

    private fun isPlaylistDetailVisible(): Boolean {
        val pd = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL) ?: return false
        return pd.isAdded && !pd.isHidden && !pd.isRemoving
    }

    fun openSearchFragmentWithQuery(query: String) {
        dismissSavedBar()
        openSearchFragment()
        // Delay to ensure SearchFragment is fully attached and visible
        fragmentContainer.postDelayed({
            val sf = searchFragment as? SearchFragment
            if (sf != null && sf.isAdded) {
                sf.externalSearchQuery(query)
            }
        }, 400)
    }

    fun openSearchFragment() {
        if (isFinishing || isDestroyed) return
        // Show activity overlay briefly; SearchFragment has its own scoped overlay too.
        showModuleLoadingOverlay()
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_search
        suppressNavListener = false

        val target = (supportFragmentManager.findFragmentByTag(TAG_MODULE_SEARCH) as? SearchFragment)
            ?.also { searchFragment = it }
            ?: SearchFragment.newInstance().also { searchFragment = it }

        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
            songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)

            // Hide ALL main module fragments to prevent overlap after activity restore
            hideIfVisible(this, principalFragment, target)
            hideIfVisible(this, musicFragment, target)
            hideIfVisible(this, supportFragmentManager.findFragmentByTag(TAG_MODULE_SCANNER), target)
            hideIfVisible(this, supportFragmentManager.findFragmentByTag(TAG_MODULE_EQUALIZER), target)
            hideIfVisible(this, playlistDetailFragment, target)
            hideIfVisible(this, songPlayerFragment, target)
            hideIfVisible(this, settingsFragment, target)

            if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_SEARCH).addToBackStack(TAG_MODULE_SEARCH)
            setMaxLifecycle(target, Lifecycle.State.RESUMED)
            commit()
        }

        topAppBar.visibility = View.GONE
        
        fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
    }

    fun closeSearchFragment() {
        suppressNavListener = true
        bottomNav.selectedItemId = currentMainNavItemId
        suppressNavListener = false
        showModuleLoadingOverlay()
        
        val selectedId = bottomNav.selectedItemId
        val target = getMainModuleFragment(selectedId) ?: getOrCreateMainModuleFragment(selectedId)
        val isNew = target?.isAdded == false

        // Hide SearchFragment FIRST so isHidden=true before popBackStack triggers onResume,
        // preventing SearchFragment.onResume from hiding the topAppBar on the returning module.
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            searchFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
            commitNow()
        }

        supportFragmentManager.popBackStackImmediate(TAG_MODULE_SEARCH, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val playlistDetail = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
        val wasPlaylistDetailActive = playlistDetail != null && playlistDetail.isAdded

        restoreMainModuleReferences()
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            
            if (wasPlaylistDetailActive) {
                hideIfVisible(this, principalFragment, playlistDetail!!)
                hideIfVisible(this, musicFragment, playlistDetail!!)
                show(playlistDetail!!)
                setMaxLifecycle(playlistDetail, Lifecycle.State.RESUMED)
            } else {
                target?.let {
                    hideIfVisible(this, principalFragment, it)
                    hideIfVisible(this, musicFragment, it)
                    hideIfVisible(this, equalizerFragment, it)
                    hideIfVisible(this, settingsFragment, it)
                    hideIfVisible(this, scannerFragment, it)
                    if (it.isAdded) show(it) else moduleTagForItem(selectedId)?.let { tag -> add(R.id.fragmentContainer, it, tag) }
                    setMaxLifecycle(it, Lifecycle.State.RESUMED)
                }
            }
            commit()
        }

        if (wasPlaylistDetailActive) {
            topAppBar.visibility = View.GONE
        } else {
            // Music and Principal own their header — keep topAppBar hidden for them
            val isFragOwnedHeader = selectedId == R.id.nav_music || selectedId == R.id.nav_principal
            topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
            configureHeaderActionForMainModules()
            updateHeaderTitleForModule(selectedId)
        }
        
        fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
    }

    private fun restoreMainModuleReferences() {
        principalFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_PRINCIPAL)
        musicFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)
        scannerFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_SCANNER)
        equalizerFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_EQUALIZER)
        settingsFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_SETTINGS)
        searchFragment = supportFragmentManager.findFragmentByTag(TAG_MODULE_SEARCH)
        playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
        songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)
    }

    private fun getOrCreateMainModuleFragment(itemId: Int): Fragment? {
        getMainModuleFragment(itemId)?.let { return it }
        val fragment: Fragment? = when (itemId) {
            R.id.nav_principal -> PrincipalFragment()
            R.id.nav_music -> MusicPlayerFragment()
            R.id.nav_scanner -> ScannerFragment()
            R.id.nav_equalizer -> EqualizerFragment()
            else -> null
        }
        fragment?.let {
            when (itemId) {
                R.id.nav_principal -> principalFragment = it
                R.id.nav_music -> musicFragment = it
                R.id.nav_scanner -> scannerFragment = it
                R.id.nav_equalizer -> equalizerFragment = it
            }
        }
        return fragment
    }

    private fun getMainModuleFragment(itemId: Int): Fragment? = when (itemId) {
        R.id.nav_principal -> principalFragment
        R.id.nav_music -> musicFragment
        R.id.nav_scanner -> scannerFragment
        R.id.nav_equalizer -> equalizerFragment
        else -> null
    }

    private fun moduleTagForItem(itemId: Int) = when (itemId) {
        R.id.nav_principal -> TAG_MODULE_PRINCIPAL
        R.id.nav_music -> TAG_MODULE_MUSIC
        R.id.nav_scanner -> TAG_MODULE_SCANNER
        R.id.nav_equalizer -> TAG_MODULE_EQUALIZER
        else -> null
    }

    private fun hideIfVisible(transaction: FragmentTransaction, fragment: Fragment?, target: Fragment) {
        if (fragment == null || fragment == target || !fragment.isAdded || fragment.isHidden) return
        transaction.hide(fragment)
        if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            transaction.setMaxLifecycle(fragment, Lifecycle.State.STARTED)
        }
    }

    private fun switchToMainModule(itemId: Int): Boolean {
        if (isNavigating) return true
        isNavigating = true

        // Reset stale sub-navigation flags
        inEqualizerFromPlayer = false
        inEqualizerFromSettings = false
        inScannerFromSettings = false

        supportFragmentManager.executePendingTransactions()
        setContainerOverlayMode(false)
        val tag = moduleTagForItem(itemId) ?: run { isNavigating = false; return false }
        val target = supportFragmentManager.findFragmentByTag(tag)
            ?: getOrCreateMainModuleFragment(itemId)
            ?: run { isNavigating = false; return false }

        if (currentMainNavItemId == R.id.nav_music && itemId != R.id.nav_music) markStreamingEntryAsLibrary()

        if (bottomNav.selectedItemId != itemId) {
            suppressNavListener = true
            bottomNav.selectedItemId = itemId
            suppressNavListener = false
        }

        val isTrulySwitching = currentMainNavItemId != itemId || !target.isAdded || target.isHidden
        if (!isTrulySwitching && !inSettings) {
            updateHeaderTitleForModule(itemId)
            if (itemId == R.id.nav_music) {
                (target as? MusicPlayerFragment)?.scrollToTop()
            }
            isNavigating = false
            return true
        }

        val isNew = !target.isAdded
        // Update immediately so any re-entrant calls see the correct module id
        currentMainNavItemId = itemId
        // Show overlay immediately — post the heavy fragment work so this frame
        // is rendered before the FragmentManager blocks the UI thread.
        setOverlayFullscreen(false)
        showModuleLoadingOverlay()
        fragmentContainer.post {
            if (isFinishing || isDestroyed) { isNavigating = false; return@post }
            // Re-resolve all fragment references to avoid stale properties after deferred post
            restoreMainModuleReferences()
            val resolvedTarget = supportFragmentManager.findFragmentByTag(tag) ?: target
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)

                hideIfVisible(this, principalFragment, resolvedTarget)
                hideIfVisible(this, musicFragment, resolvedTarget)
                hideIfVisible(this, playlistDetailFragment, resolvedTarget)
                hideIfVisible(this, songPlayerFragment, resolvedTarget)
                hideIfVisible(this, settingsFragment, resolvedTarget)
                hideIfVisible(this, equalizerFragment, resolvedTarget)
                hideIfVisible(this, scannerFragment, resolvedTarget)
                hideIfVisible(this, searchFragment, resolvedTarget)

                if (songPlayerFragment != null && songPlayerFragment!!.isAdded && !songPlayerFragment!!.isHidden) {
                    hide(songPlayerFragment!!)
                }

                if (resolvedTarget.isAdded) show(resolvedTarget) else add(R.id.fragmentContainer, resolvedTarget, tag)
                setMaxLifecycle(resolvedTarget, Lifecycle.State.RESUMED)
                commitAllowingStateLoss()
            }

            isNavigating = false

            getSharedPreferences(PREFS_BOOTSTRAP, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_LAST_MAIN_MODULE, itemId)
                .apply()

            if (itemId == R.id.nav_music) markStreamingEntryAsLibrary()
            if (!isSearchFragmentVisible()) {
                // Music and Principal own their header — keep topAppBar hidden for them
                val isFragOwnedHeader = itemId == R.id.nav_music || itemId == R.id.nav_principal
                topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
            }
            configureHeaderActionForMainModules()
            updateHeaderTitleForModule(itemId)
            if (!isMiniPlayerAllowedForCurrentScreen()) {
                globalMiniPlayer?.hide()
            }

            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        }
        return true
    }

    fun showModuleLoadingOverlay() {
        moduleLoadingOverlay.alpha = 1f
        moduleLoadingOverlay.visibility = View.VISIBLE
    }

    private fun setOverlayFullscreen(fullscreen: Boolean) {
        val lp = moduleLoadingOverlay.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        if (fullscreen) {
            lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToBottom = R.id.main
            lp.bottomMargin = 0
        } else {
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = R.id.bottomNavigation
            lp.bottomMargin = (72 * resources.displayMetrics.density).toInt()
        }
        moduleLoadingOverlay.layoutParams = lp
    }

    fun revealModuleContent() {
        if (isFinishing || isDestroyed) return
        moduleLoadingOverlay.animate().cancel()
        moduleLoadingOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction { moduleLoadingOverlay.visibility = View.GONE }
            .start()
    }

    private fun markStreamingEntryAsLibrary() {
        persistStreamingScreenState(STREAM_SCREEN_LIBRARY)
        (supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment)?.externalSetReturnTargetTag(TAG_MODULE_MUSIC)
    }

    private fun persistStreamingScreenState(screen: String) {
        getSharedPreferences(PREFS_PLAYER_STATE, MODE_PRIVATE).edit().putString(PREF_LAST_STREAM_SCREEN, screen).apply()
    }

    private fun snapshotStreamingScreenBeforeNavigation() {
        supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)?.let { fragment ->
            if (fragment is SongPlayerFragment && fragment.isAdded && !fragment.isHidden) {
                fragment.externalSnapshotForNavigation()
                val target = if (fragment.externalGetReturnTargetTag() == TAG_PLAYLIST_DETAIL) STREAM_SCREEN_PLAYLIST_DETAIL else STREAM_SCREEN_LIBRARY
                persistStreamingScreenState(target)
                return
            }
        }
        val isDetailVisible = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL).let { it != null && it.isAdded && !it.isHidden }
        persistStreamingScreenState(if (isDetailVisible) STREAM_SCREEN_PLAYLIST_DETAIL else STREAM_SCREEN_LIBRARY)
    }

    private fun handleSongPlayerBackPressed(): Boolean {
        val player = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment ?: return false
        if (!player.isAdded || player.isHidden) return false

        if (player.externalTryEnterMiniMode()) return true

        snapshotStreamingScreenBeforeNavigation()
        val fallback = resolveSongPlayerReturnTarget(player.externalGetReturnTargetTag())
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            setCustomAnimations(
                R.anim.hold,
                R.anim.player_screen_exit
            )
            if (fallback != null && fallback.isAdded && fallback != player) {
                hide(player).show(fallback)
            } else {
                remove(player)
            }
            commit()
        }
        return true
    }

    /** Called by [SongPlayerFragment] when dismissed via swipe. */
    fun externalClosePlayerImmediately() {
        val player = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment ?: return
        if (!player.isAdded || player.isHidden) return



        val fallback = resolveSongPlayerReturnTarget(player.externalGetReturnTargetTag())
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            if (fallback != null && fallback.isAdded && fallback != player) {
                hide(player).show(fallback)
            } else {
                remove(player)
            }
            commitNowAllowingStateLoss()
        }
        // Restore bottomNav when returning to a main module
        val isFragOwnedHeader = currentMainNavItemId == R.id.nav_music || currentMainNavItemId == R.id.nav_principal
        topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        PlaybackEventBus.notifyPlaybackSnapshotUpdated()
    }

    private fun resolveSongPlayerReturnTarget(preferredTag: String?): Fragment? {
        if (!preferredTag.isNullOrEmpty()) {
            supportFragmentManager.findFragmentByTag(preferredTag)?.let { if (it.isAdded) return it }
        }
        supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)?.let { if (it.isAdded) return it }
        supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)?.let { if (it.isAdded) return it }
        return getMainModuleFragment(currentMainNavItemId)?.takeIf { it.isAdded }
    }

    private fun handlePlaylistDetailBackPressed(): Boolean {
        val detail = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL) ?: return false
        if (!detail.isAdded || detail.isHidden) return false

        // Count how many playlist_detail entries are on the back stack
        var detailEntryCount = 0
        for (i in 0 until supportFragmentManager.backStackEntryCount) {
            if (supportFragmentManager.getBackStackEntryAt(i).name == TAG_PLAYLIST_DETAIL) {
                detailEntryCount++
            }
        }

        if (detailEntryCount > 1) {
            // Multiple playlist details stacked (e.g. source playlist → radio).
            // Pop only the topmost one so we return to the previous playlist.
            supportFragmentManager.popBackStackImmediate()
        } else {
            // Single detail — pop all the way back to library
            markStreamingEntryAsLibrary()
            supportFragmentManager.popBackStackImmediate(TAG_PLAYLIST_DETAIL, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            
            // Switch to the music module cleanly to hide any other main modules (like PrincipalFragment)
            // and show MusicPlayerFragment.
            switchToMainModule(R.id.nav_music)
        }
        return true
    }

    fun ensureHeaderVisibleForMusic() {
        if (inSettings || inEqualizerFromSettings || inEqualizerFromPlayer || inScannerFromSettings) return
        // Music fragment now owns its own header — just ensure bottomNav is visible
        topAppBar.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
    }

    fun hideTopAppBarForSearch() {
        topAppBar.visibility = View.GONE
    }

    fun hideTopAppBarForPlaylistDetail() {
        topAppBar.visibility = View.GONE
    }

    private fun updateHeaderTitleForModule(itemId: Int) {
        tvModuleTitle.text = if (inSettings) getString(R.string.header_title_settings) else getString(R.string.header_brand_title)
    }

    fun setContainerOverlayMode(enabled: Boolean) {
        // Obsolete: SongPlayerFragment now uses its own edge-to-edge container (playerContainer).
    }

    fun pauseActiveMediaAndDownloadsForSessionChange() {
        // 1. Halt background work like downloads
        cloudSyncManager.pauseUserScopedBackgroundWork()
        
        // 2. Stop music playback if the player is active
        (supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment)?.externalPauseForSessionExit()
    }

    private fun shouldMoveTaskToBackForOngoingPlayback(): Boolean {
        val snapshot = PlaybackHistoryStore.load(this)
        return snapshot.isPlaying && snapshot.queue.isNotEmpty()
    }
}
