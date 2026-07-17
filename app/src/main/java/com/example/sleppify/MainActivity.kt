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
        private const val TAG_ARTIST_DETAIL = "artist_detail"
        private const val TAG_SONG_PLAYER = AppConstants.TAG_SONG_PLAYER

        private const val PREFS_PLAYER_STATE = AppConstants.PREFS_PLAYER_STATE
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

        // El popup de nueva versión solo debe saltar una vez por proceso.
        @Volatile
        private var updatePopupShownThisProcess = false

        @Volatile
        private var activeInstance: WeakReference<MainActivity>? = null

        /**
         * Routes a media notification action (prev / play-pause / next) to the live activity
         * without launching it. Returns false when no usable activity exists so the caller
         * (MediaActionReceiver) can decide on a fallback.
         */
        @JvmStatic
        fun dispatchMediaAction(action: String): Boolean {
            val activity = activeInstance?.get() ?: return false
            if (activity.isDestroyed || activity.isFinishing) return false
            activity.runOnUiThread { activity.dispatchMediaNotificationAction(action) }
            return true
        }

        /**
         * Re-renders the Principal home carousels if the app is live and Principal is visible. Called
         * from CloudSyncManager once cloud radio hydration lands (it arrives async, after the initial
         * hydration pass), so restored radios appear without the user navigating away and back.
         */
        @JvmStatic
        fun requestPrincipalRefresh() {
            val activity = activeInstance?.get() ?: return
            if (activity.isDestroyed || activity.isFinishing) return
            activity.refreshPrincipalContentIfVisible()
        }

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
    // The main-module tab Settings was opened from. Back out of Settings returns HERE instead of to
    // bottomNav.selectedItemId, which onCreate force-resets to Principal on every Activity recreation.
    private var settingsReturnNavItemId: Int = R.id.nav_music
    private var inEqualizerFromSettings = false
    private var inScannerFromSettings = false
    private var isNavigating = false
    // Latest module requested while a navigation is already in flight. We never drop a
    // request (that is what let the footer and the visible fragment drift apart); we
    // remember the most recent one and reconcile to it once the current switch settles.
    private var pendingNavItemId = View.NO_ID
    private var suppressNavListener = false

    // Historial de módulos raíz (Principal/Search/Música) para el back del sistema: cada vez que
    // el usuario cambia de módulo se apila el módulo que ABANDONA; el back lo recorre hacia atrás
    // (con Principal como último peldaño) antes de salir/mandar la app a segundo plano. Se
    // persiste en el instance state para que sobreviva a la muerte del proceso en segundo plano —
    // antes de esto, el back tras volver a la app cerraba directamente a la pantalla de inicio.
    private val moduleNavHistory = ArrayDeque<Int>()
    private var suppressNavHistoryPush = false

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
    private var artistDetailFragment: Fragment? = null

    // Resolves "Ir a artista" (name → channelId) for openArtistDetailByName
    private val artistLookupService by lazy { YouTubeMusicService() }

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
            val eq = supportFragmentManager.findFragmentByTag(TAG_MODULE_EQUALIZER)
            if (eq != null && eq.isAdded && !eq.isHidden) {
                returnFromEqualizer()
                return
            }
            // Settings back-handling keyed on the fragment's REAL visibility, not the inSettings
            // flag: when they desync (observed in the wild) the old guard swallowed the event
            // doing nothing — back looked completely dead while Settings was on screen.
            val settingsFrag = supportFragmentManager.findFragmentByTag(TAG_MODULE_SETTINGS) as? SettingsFragment
            if (settingsFrag != null && settingsFrag.isAdded && !settingsFrag.isHidden) {
                settingsFragment = settingsFrag
                inSettings = true // self-heal the flag so returnFromSettings() can run
                settingsFrag.onBackPressed()
                return
            }
            if (inSettings) {
                // Flag says Settings but nothing is actually visible — heal and fall through.
                inSettings = false
            }

            if (handleSongPlayerBackPressed()) return
            if (handlePlaylistDetailBackPressed()) return
            if (handleModuleBackNavigation()) return

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
        // Auth cookies + NewPipe warmup run on background thread inside warmUp
        StreamResolver.warmUp(this)
        setupListeners()
        configureHeaderActionForMainModules()
        configureAudioAuthorizationFlow()
        restoreMainModuleReferences()
        if (savedInstanceState != null) {
            inSettings = settingsFragment != null
            inEqualizerFromSettings = equalizerFragment != null
            inScannerFromSettings = scannerFragment != null
            savedInstanceState.getIntArray("module_nav_history")?.let { saved ->
                moduleNavHistory.clear()
                for (id in saved) if (isMainModuleNavId(id)) moduleNavHistory.addLast(id)
            }
        }

        settingsPrefs = getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
        localPrefs = getSharedPreferences("sleppify_local_config", Context.MODE_PRIVATE)
        updateNavigationForScreenSize()

        // Caches are pre-warmed by SleppifyApp on a background thread.
        // By now they are likely ready (O(1) read from in-memory cache).

        // Cold start abre en Principal; una RECREACIÓN de la Activity (rotación, vuelta a la app
        // tras muerte por memoria, cambio de tema) restaura el módulo donde estaba el usuario.
        // Antes se forzaba Principal en cada recreación (y onRestoreInstanceState forzaba Música):
        // dos políticas contradictorias que hacían que el footer "cambiara solo" de módulo.
        // Don't clobber a restored Settings shell: when recreated INTO Settings (inSettings set
        // from the restored fragment just above), the FragmentManager already restored Settings
        // visible, and showMainShell() early-returns for overlays — so forcing a module here would
        // only corrupt the footer/module state that Back-out-of-Settings relies on.
        if (!inSettings) {
            val restoredNav = savedInstanceState?.getInt("current_main_nav_item_id", View.NO_ID) ?: View.NO_ID
            val startNav = if (isMainModuleNavId(restoredNav)) restoredNav else R.id.nav_principal
            suppressNavListener = true
            bottomNav.selectedItemId = startNav
            suppressNavListener = false
            currentMainNavItemId = startNav
            switchToMainModule(startNav)
        }
        showMainShell()

        // Pre-create MusicPlayerFragment hidden so its onViewCreated triggers the
        // web session auto-launch (login prompt). Previously it was created immediately
        // because Biblioteca was the default module; now Principal is first.
        // DIFERIDO 600 ms: su onViewCreated hace ~30 findViewById + adapter + GoogleSignIn justo
        // después del primer frame de Principal y le robaba ese hueco al primer contenido; el
        // auto-launch del login (postDelayed interno de 800 ms) sigue saliendo enseguida.
        fragmentContainer.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC) == null) {
                val music = getOrCreateMainModuleFragment(R.id.nav_music) ?: return@postDelayed
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragmentContainer, music, TAG_MODULE_MUSIC)
                    .hide(music)
                    .setMaxLifecycle(music, Lifecycle.State.STARTED)
                    .commitAllowingStateLoss()
            }
        }, 600L)

        // Defer heavy startup work to background thread
        lifecycleScope.launch {
            delay(100) // Allow UI to render first
            syncAudioProfile(false)
            withContext(Dispatchers.IO) {
                syncAudioEffectsServiceFromPreferences(forceSync = true)
            }
            // Sync signed-in user state if already authenticated
            if (authManagerLazy.isSignedIn()) {
                authManagerLazy.getCurrentUser()?.let { handleSignedInUser(it, null) }
            }
            // Actualizaciones: consulta el version.json del hosting. El popup solo sale cuando la
            // cuenta ya está COMPLETAMENTE montada (ver maybeShowStartupUpdatePopup) — nunca durante
            // el primer arranque/configuración, que era lo que la dejaba a medias.
            delay(1400)
            maybeShowStartupUpdatePopup()
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
        if (inEqualizerFromSettings || inScannerFromSettings) {
            bottomNav.visibility = View.GONE
        } else if (inSettings) {
            // SettingsFragment controls bottomNav visibility per sub-section
            val showNav = (settingsFragment as? SettingsFragment)?.isHistoryOrAccountActive() == true
            bottomNav.visibility = if (showNav) View.VISIBLE else View.GONE
        } else {
            bottomNav.visibility = View.VISIBLE
        }
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
                // closeSearchFragment no apila (también lo invoca el back de Search); el cambio
                // Search → módulo por tap del footer se registra aquí.
                pushModuleNavHistory(R.id.nav_search, item.itemId)
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
        val player = fm.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment
        if (player != null && player.isAdded) {
            when (action) {
                ACTION_MEDIA_PLAY_PAUSE -> player.externalTogglePlayback()
                ACTION_MEDIA_NEXT -> player.externalSkipNext()
                ACTION_MEDIA_PREV -> player.externalSkipPrevious()
            }
            return
        }

        // No player attached — restore from snapshot so notification buttons work when the app is closed
        val snapshot = PlaybackHistoryStore.load(this)
        if (!snapshot.isValid()) return

        globalMiniPlayer?.resumePlaybackFromSnapshot(true)
        if (action == ACTION_MEDIA_NEXT || action == ACTION_MEDIA_PREV) {
            fragmentContainer.post {
                val restored = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment
                if (restored != null && restored.isAdded) {
                    when (action) {
                        ACTION_MEDIA_NEXT -> restored.externalSkipNext()
                        ACTION_MEDIA_PREV -> restored.externalSkipPrevious()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_main_nav_item_id", currentMainNavItemId)
        outState.putBoolean("in_settings", inSettings)
        outState.putInt("settings_return_nav_item_id", settingsReturnNavItemId)
        outState.putIntArray("module_nav_history", moduleNavHistory.toIntArray())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Restaurar el MISMO módulo que onCreate ya montó desde el estado guardado. Forzar aquí
        // un módulo distinto (antes: siempre Música, mientras onCreate forzaba Principal) dejaba
        // el footer y currentMainNavItemId desincronizados — el "se cambia solo de módulo".
        val restoredNav = savedInstanceState.getInt("current_main_nav_item_id", View.NO_ID)
        if (isMainModuleNavId(restoredNav)) {
            currentMainNavItemId = restoredNav
        }
        inSettings = savedInstanceState.getBoolean("in_settings", false)
        settingsReturnNavItemId = savedInstanceState.getInt("settings_return_nav_item_id", R.id.nav_music)
    }

    /** True para los ids de módulo raíz del bottom nav (los únicos restaurables al recrear). */
    private fun isMainModuleNavId(itemId: Int): Boolean =
        itemId == R.id.nav_principal || itemId == R.id.nav_search || itemId == R.id.nav_music

    /** Módulo raíz actualmente EN PANTALLA. Search abierto cuenta como nav_search aunque
     *  [currentMainNavItemId] siga apuntando al módulo subyacente (así funciona su cierre). */
    private fun visibleMainNavId(): Int =
        if (isSearchFragmentVisible()) R.id.nav_search else currentMainNavItemId

    /** Apila el módulo que se abandona al navegar a otro. No-op durante los pops del back
     *  ([suppressNavHistoryPush]) para que volver atrás no re-apile lo que acaba de sacar. */
    private fun pushModuleNavHistory(fromNav: Int, toNav: Int) {
        if (suppressNavHistoryPush) return
        if (fromNav == toNav || !isMainModuleNavId(fromNav) || !isMainModuleNavId(toNav)) return
        moduleNavHistory.addLast(fromNav)
        while (moduleNavHistory.size > 16) moduleNavHistory.removeFirst()
    }

    /** Back del sistema a nivel de módulos: vuelve al módulo anterior del historial, con
     *  Principal como último peldaño. Devuelve false solo en Principal sin historial — únicamente
     *  ahí el back sale de la app (o la manda a segundo plano si hay reproducción). */
    private fun handleModuleBackNavigation(): Boolean {
        val current = visibleMainNavId()
        var target = View.NO_ID
        while (moduleNavHistory.isNotEmpty()) {
            val cand = moduleNavHistory.removeLast()
            if (cand != current && isMainModuleNavId(cand)) {
                target = cand
                break
            }
        }
        if (target == View.NO_ID) {
            if (current == R.id.nav_principal) return false
            target = R.id.nav_principal
        }
        suppressNavHistoryPush = true
        try {
            when {
                target == R.id.nav_search -> openSearchFragment()
                isSearchFragmentVisible() -> {
                    currentMainNavItemId = target
                    closeSearchFragment()
                }
                else -> switchToMainModule(target)
            }
        } finally {
            suppressNavHistoryPush = false
        }
        return true
    }

    fun handlePlayFromSearchIntent(intent: Intent) {

        if (currentMainNavItemId != R.id.nav_music) {
            // switchToMainModule reconciles the footer itself; setting selectedItemId here
            // (unsuppressed) would fire the listener and start a second, racing navigation.
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
            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
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

        // Restore the mini player from the last playback snapshot on cold start. Previously only
        // MusicPlayerFragment did this restore, so opening the app on any other module left the
        // mini player invisible until the user visited the Music module. Idempotent: no-ops when
        // the player fragment already exists.
        globalMiniPlayer?.resumePlaybackFromSnapshot(false)

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
        mainHandler.removeCallbacksAndMessages(null)
        // Evita WindowLeaked del popup de actualización si la Activity se destruye (p. ej. rotación).
        updateDialog?.setOnDismissListener(null)
        updateDialog?.dismiss()
        updateDialog = null
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
        AppSnackbar.show(this, "Modo offline activado", 4000L)
        mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) notifyOfflineModeChanged() }, 1500L)
    }

    private fun onNetworkRestored() {
        if (isFinishing || isDestroyed) return
        settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false).apply()
        AppSnackbar.show(this, "Vuelves a tener conexión", 4000L)
        mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) notifyOfflineModeChanged() }, 1500L)
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
            AppSnackbar.show(this, "Permiso denegado para el EQ system-wide.", 3500L)
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
                AppSnackbar.show(this, "Se requiere permiso de micrófono.", 3500L)
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
        if (inSettings || inEqualizerFromSettings || inScannerFromSettings || isAnyOverlayModuleVisible()) return
        if (!isSearchFragmentVisible() && !isPlaylistDetailVisible()) {
            // Music and Principal fragments own their own header — hide topAppBar for them
            val isFragOwnedHeader = currentMainNavItemId == R.id.nav_music || currentMainNavItemId == R.id.nav_principal
            topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
        }
        fragmentContainer.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        // Defer profile photo load so it doesn't block the first rendered frame
        bottomNav.post { loadProfilePhoto() }
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
                // Repopulate Principal too: its carousels were rendered once from empty caches on
                // cold start and would otherwise stay black until the user navigated away and back.
                refreshPrincipalContentIfVisible()
                onSuccess?.run()
                // Cuenta COMPLETAMENTE montada (cookie + Google + Firebase hidratado + biblioteca):
                // recién ahora es seguro ofrecer la actualización a un usuario que se acaba de
                // configurar — al FINAL, nunca a mitad del primer arranque.
                maybeShowUpdatePopupAfterSetup()
            }
        }

        finalize()
    }

    private fun notifyHydrationCompleted() {
        (getMainModuleFragment(R.id.nav_equalizer) as? EqualizerFragment)?.onCloudEqHydrationCompleted()
    }

    /**
     * Repopulate the Principal home carousels if it is the currently-visible module. Called when
     * session/cloud data arrives after Principal was already rendered on cold start (post web
     * login, post cloud hydration). Thread-safe: hops to the UI thread itself, so callers may
     * invoke it from any thread.
     */
    fun refreshPrincipalContentIfVisible() {
        val principal = supportFragmentManager.findFragmentByTag(TAG_MODULE_PRINCIPAL)
        if (principal is PrincipalFragment && principal.isAdded && !principal.isHidden) {
            runOnUiThread { principal.refreshAllContent() }
        }
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
        // Defer the profile-photo load off the first-frame path (it does a synchronous streaming_cache
        // prefs read + Glide build). showMainShell()/returnFromSettings()/handleSignedInUser re-trigger it.
        btnProfilePhoto.post { loadProfilePhoto() }

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

    // Última combinación (signedIn|uri) ya pintada en el header — loadProfilePhoto se dispara
    // desde varios puntos cerca del primer frame (configureHeaderActionForMainModules,
    // showMainShell, cada onResume); sin esta memo cada disparo re-lanzaba el mismo Glide.
    private var lastProfilePhotoState: String? = null

    private fun loadProfilePhoto() {
        val prefs = getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, MODE_PRIVATE)
        val cachedUrl = prefs.getString("cached_google_profile_photo_url", "") ?: ""
        val photoUri = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl
            ?: if (cachedUrl.isNotEmpty()) android.net.Uri.parse(cachedUrl) else null

        val signedIn = authManagerLazy.isSignedIn()
        val state = "$signedIn|${photoUri?.toString() ?: ""}"
        if (signedIn) {
            // Las visibilidades se restauran SIEMPRE (otros módulos ocultan estos botones);
            // solo la carga de Glide se memoiza.
            btnSignInHeader?.visibility = View.GONE
            btnProfilePhoto.visibility = View.VISIBLE
            if (photoUri != null) {
                if (state != lastProfilePhotoState) {
                    lastProfilePhotoState = state
                    com.bumptech.glide.Glide.with(this)
                        .load(photoUri)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(btnProfilePhoto)
                }
            } else {
                // Sin foto aún: vacío, nunca un placeholder. Reset del memo para que la foto
                // cargue en cuanto exista.
                lastProfilePhotoState = null
                btnProfilePhoto.setImageDrawable(null)
            }
        } else {
            lastProfilePhotoState = null
            btnProfilePhoto.visibility = View.GONE
            btnProfilePhoto.setImageDrawable(null)
            btnSignInHeader?.visibility = View.VISIBLE
        }
    }

    private fun configureHeaderActionForSettings() {
        setTopAppBarExtraTopPadding(0)
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
        setTopAppBarExtraTopPadding(0)
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

    // ───────────────────── Actualizaciones (sin Firebase) ─────────────────────

    /**
     * Muestra la ventana de nueva versión SOLO cuando la cuenta está COMPLETAMENTE montada (sesión
     * web de YT Music guardada + sesión de Google/Firebase iniciada). Así el popup nunca aparece
     * durante el primer arranque/configuración — que era lo que, al pulsar "Actualizar" a media
     * configuración, reiniciaba el proceso y dejaba la cuenta sin montar (3x3 y playlists vacías).
     * Para un usuario recién instalado se re-dispara al terminar el login (ver [handleSignedInUser]/
     * [onWebSessionEstablished]), así lo ve al FINAL de la configuración, ya con todo cargado.
     */
    private fun maybeShowStartupUpdatePopup() {
        if (updatePopupShownThisProcess) return
        // Gate: cuenta completamente montada. Si aún no (primer arranque en curso), no molestar;
        // se reintentará al completarse el login.
        if (!hasSessionCookie() || !authManagerLazy.isSignedIn()) {
            Log.d("SleppifyUpdate", "popup diferido: cuenta aún no montada del todo")
            return
        }
        AppUpdateManager.checkForUpdate(applicationContext) { update, _ ->
            if (update == null || isFinishing || isDestroyed || updatePopupShownThisProcess) return@checkForUpdate
            updatePopupShownThisProcess = true
            showUpdateAvailablePopup(update)
        }
    }

    /** True si hay una sesión web de YT Music guardada (cookie no vacía). */
    private fun hasSessionCookie(): Boolean = try {
        getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, MODE_PRIVATE)
            .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "")?.trim().orEmpty().isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** Reintenta mostrar el popup de actualización una vez la cuenta terminó de montarse (login web
     *  + Google). Lo llaman los puntos que completan la configuración; no-op si ya se mostró o si
     *  todavía falta algo. Un pequeño retardo deja asentar la UI antes del diálogo. */
    fun maybeShowUpdatePopupAfterSetup() {
        if (updatePopupShownThisProcess) return
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) maybeShowStartupUpdatePopup()
        }, 1200L)
    }

    /**
     * Ventana emergente de nueva versión: mismo estilo AMOLED que la pantalla Actualizar.
     * Actualización obligatoria: no tiene "Ahora no" y no se puede cerrar con el botón atrás
     * ni tocando fuera — la única salida es tocar "Actualizar".
     */
    fun showUpdateAvailablePopup(update: AppUpdateManager.UpdateInfo) {
        if (isFinishing || isDestroyed) return
        updateDialog?.dismiss() // nunca dos a la vez
        val view = layoutInflater.inflate(R.layout.dialog_update_available, null)
        // Header editable desde el panel (con fallback ya resuelto en AppUpdateManager).
        view.findViewById<TextView>(R.id.tvUpdateDialogTitle).text = update.dialogTitle
        // "🔥 Nueva versión" es fijo; la versión va en la pastilla de la derecha.
        view.findViewById<TextView>(R.id.tvUpdateDialogPill).text = update.versionName
        // Novedades (formatNotesAsBullets ya da "Mejoras y correcciones." si vienen vacías).
        view.findViewById<TextView>(R.id.tvUpdateDialogNotes).text =
            AppUpdateManager.formatNotesAsBullets(update.notes, 4)

        // Opcional (mandatory=false): el diálogo se puede cerrar y aparece "Más tarde".
        val optional = !update.mandatory
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setCancelable(optional)
            setCanceledOnTouchOutside(false)
            setOnDismissListener { if (updateDialog === this) updateDialog = null }
        }
        updateDialog = dialog

        val btnGo = view.findViewById<TextView>(R.id.btnUpdateDialogGo)
        val btnLater = view.findViewById<TextView>(R.id.btnUpdateDialogLater)
        val llProgress = view.findViewById<View>(R.id.llUpdateDialogProgress)
        val pbDownload = view.findViewById<android.widget.ProgressBar>(R.id.pbUpdateDialog)
        val tvPercent = view.findViewById<TextView>(R.id.tvUpdateDialogPercent)

        btnLater.visibility = if (optional) View.VISIBLE else View.GONE
        btnLater.setOnClickListener { dialog.dismiss() }

        btnGo.setOnClickListener {
            // Sin el permiso de "instalar apps desconocidas" el instalador no abre: pedirlo primero.
            if (!AppUpdateManager.canInstallUnknownApps(this)) {
                AppSnackbar.show(this, "Permite instalar apps de Sleppify y vuelve a tocar Actualizar")
                try { startActivity(AppUpdateManager.buildUnknownSourcesIntent(this)) } catch (_: Exception) {}
                return@setOnClickListener
            }
            // Descarga in-situ: el botón se cambia por la barra de progreso, mismo proceso que la
            // antigua sección Actualizar (AppUpdateManager descarga y lanza el instalador al 100%).
            btnGo.visibility = View.GONE
            btnLater.visibility = View.GONE
            dialog.setCancelable(false) // ya descargando: no cerrar a media descarga
            llProgress.visibility = View.VISIBLE
            pbDownload.progress = 0
            tvPercent.text = "0%"
            AppUpdateManager.downloadAndInstall(
                applicationContext, update,
                onProgress = { percent ->
                    pbDownload.progress = percent
                    tvPercent.text = "$percent%"
                },
                onInstallStarted = { tvPercent.text = "Instalando…" },
                onError = { message ->
                    AppSnackbar.show(this, "Error al descargar: $message")
                    llProgress.visibility = View.GONE
                    btnGo.visibility = View.VISIBLE
                }
            )
        }
        dialog.show()
    }

    fun enterSettings() = enterSettingsForSection(SettingsEntry.ROOT)

    fun enterSettingsAtHistory() = enterSettingsForSection(SettingsEntry.HISTORY)

    private enum class SettingsEntry { ROOT, HISTORY }

    // Ventana emergente de nueva versión (referencia para descartarla y no filtrarla al destruir).
    private var updateDialog: android.app.Dialog? = null

    private fun enterSettingsForSection(entry: SettingsEntry) {
        val target = (settingsFragment as? SettingsFragment)
            ?: SettingsFragment().also { settingsFragment = it }
        // Declare the entry section up front. requestSection() applies it immediately
        // when Settings is already on screen, otherwise records it as the pending section
        // that the fragment applies as soon as it appears. Doing this before the
        // transaction means the section is fixed regardless of which lifecycle callback
        // (onViewCreated or onHiddenChanged) ends up driving the first render.
        when (entry) {
            SettingsEntry.ROOT -> target.navigateToRoot()
            SettingsEntry.HISTORY -> target.navigateToHistory()
        }
        if (inSettings) return
        // Capture the origin module AFTER the re-entrancy guard, so a second enter-Settings call
        // while already in Settings can't overwrite it. Back out of Settings returns here.
        settingsReturnNavItemId = currentMainNavItemId

        dismissSavedBar()
        if (bottomNav.selectedItemId == R.id.nav_music) markStreamingEntryAsLibrary()

        inSettings = true
        globalMiniPlayer?.hide()
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
        // Return to the module Settings was opened from (persisted across recreation), not to the
        // footer selection, and re-align the footer highlight to match.
        val selectedId = settingsReturnNavItemId
        syncBottomNavSelection(selectedId)
        // Repoint a player that was opened from INSIDE Settings (return target == module_settings) to
        // the module we're returning to. Otherwise the stale tag survives, and later closing that
        // player (e.g. re-opened from the mini player in Biblioteca) re-shows the still-added, hidden
        // Settings fragment instead of the current module. Guarded on the settings tag so players
        // opened from Music/Principal/PlaylistDetail keep their own target, and so closing the player
        // WHILE still in Settings (before this method runs) still correctly returns to Settings.
        (supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment)?.let { p ->
            if (p.isAdded && p.externalGetReturnTargetTag() == TAG_MODULE_SETTINGS) {
                moduleTagForItem(selectedId)?.let { tag -> p.externalSetReturnTargetTag(tag) }
            }
        }
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

    fun returnFromEqualizer() {
        inEqualizerFromSettings = false
        supportFragmentManager.popBackStackImmediate(TAG_MODULE_EQUALIZER, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        // popBackStackImmediate is synchronous: the EqualizerFragment is already destroyed here.
        // Null the field so the destroyed fragment (~277kB) isn't retained via the live Activity.
        equalizerFragment = null

        topAppBar.visibility = View.GONE
        setSolidNavigationBar(true)
        bottomNav.visibility = View.GONE
        
        val settings = supportFragmentManager.findFragmentByTag(TAG_MODULE_SETTINGS) as? SettingsFragment
        settings?.refreshCurrentSectionVisibility()
    }

    fun openEqualizerFromSettings() {
        if (isNavigating) return
        inEqualizerFromSettings = true
        globalMiniPlayer?.hide()
        val target = equalizerFragment ?: EqualizerFragment().also { equalizerFragment = it }
        
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            replace(R.id.fragmentContainer, target, TAG_MODULE_EQUALIZER)
            addToBackStack(TAG_MODULE_EQUALIZER)
            commit()
        }
        
        topAppBar.visibility = View.GONE
        setSolidNavigationBar(true)
        bottomNav.visibility = View.GONE
    }

    private fun configureHeaderActionForEqualizer() {
        setTopAppBarExtraTopPadding(0)
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
            setOnClickListener { returnFromEqualizer() }
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

    fun isMiniPlayerAllowedForCurrentScreen(): Boolean {
        // Mini-player only in these 4 screens — block all overlay screens
        if (inEqualizerFromSettings || inScannerFromSettings) return false
        // Keyed on the Settings fragment's REAL visibility (not the inSettings flag, which can
        // desync): with a stale flag the mini player leaked into Settings sections where it
        // must not appear (only History and Account may show it).
        val settingsFrag = supportFragmentManager.findFragmentByTag(TAG_MODULE_SETTINGS)
        if (settingsFrag != null && settingsFrag.isAdded && !settingsFrag.isHidden) {
            return (settingsFrag as? SettingsFragment)?.isHistoryOrAccountActive() == true
        }
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

    /**
     * "Ir a artista" from a track: resolve the artist's channelId by name (Artists-filtered
     * Innertube search, top hit) and open ArtistDetailFragment. Falls back to a plain search
     * if the artist can't be resolved.
     */
    fun openArtistDetailByName(artistName: String) {
        // Subtitles often decorate the artist ("Artista • Álbum • 3:45") — keep only the name part.
        val name = SongSubtitle.artistOnly(artistName)
        if (name.isEmpty() || isFinishing || isDestroyed) return
        dismissSavedBar()
        showModuleLoadingOverlay()
        val cookie = getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, MODE_PRIVATE)
            .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "")?.trim() ?: ""
        artistLookupService.searchArtistByName(name, cookie, object : YouTubeMusicService.ArtistSearchCallback {
            override fun onSuccess(artist: YouTubeMusicService.ArtistResult) {
                if (isFinishing || isDestroyed) return
                openArtistDetail(artist)
            }

            override fun onError(error: String) {
                if (isFinishing || isDestroyed) return
                openSearchFragmentWithQuery(name)
            }
        })
    }

    private fun openArtistDetail(artist: YouTubeMusicService.ArtistResult) {
        if (supportFragmentManager.isStateSaved) {
            hideModuleLoadingOverlayImmediate()
            return
        }
        hideTopAppBarForPlaylistDetail()
        // Warm the hero into Glide's cache before the transaction so the header is ready when the
        // overlay drops (the artist search hop already gave us the thumbnail URL).
        ArtistDetailFragment.preloadHero(this, artist.thumbnailUrl)
        val detail = ArtistDetailFragment.newInstance(
            artist.channelId, artist.name, artist.subtitle, artist.thumbnailUrl
        )
        val existing = supportFragmentManager.findFragmentByTag(TAG_ARTIST_DETAIL)
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            if (existing != null && existing.isAdded) remove(existing)
            add(R.id.fragmentContainer, detail, TAG_ARTIST_DETAIL)
            addToBackStack(TAG_ARTIST_DETAIL)
            commit()
        }
    }

    fun openSearchFragment() {
        if (isFinishing || isDestroyed) return
        pushModuleNavHistory(visibleMainNavId(), R.id.nav_search)

        val target = (supportFragmentManager.findFragmentByTag(TAG_MODULE_SEARCH) as? SearchFragment)
            ?.also { searchFragment = it }
            ?: SearchFragment.newInstance().also { searchFragment = it }

        // Warm re-entry (the fragment is kept alive on close): its view is resident and shows
        // instantly, so skip the activity overlay — flashing it on every re-entry is what made
        // Search feel like a fresh load each time. First create keeps the overlay (SearchFragment
        // has its own scoped one too).
        val isWarmReentry = target.isAdded && target.view != null
        if (!isWarmReentry) showModuleLoadingOverlay()
        suppressNavListener = true
        bottomNav.selectedItemId = R.id.nav_search
        suppressNavListener = false

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

            // No addToBackStack: Search is a resident module now (hidden on close, never popped).
            // System-back while it is visible is handled by SearchFragment's OnBackPressedCallback.
            if (target.isAdded) show(target) else add(R.id.fragmentContainer, target, TAG_MODULE_SEARCH)
            setMaxLifecycle(target, Lifecycle.State.RESUMED)
            commit()
        }

        topAppBar.visibility = View.GONE

        if (!isWarmReentry) {
            fragmentContainer.post { fragmentContainer.post { revealModuleContent() } }
        } else if (moduleLoadingOverlay.visibility == View.VISIBLE) {
            // A stray overlay from another flow must not stay stuck over the warm fragment.
            revealModuleContent()
        }
    }

    fun closeSearchFragment() {
        suppressNavListener = true
        bottomNav.selectedItemId = currentMainNavItemId
        suppressNavListener = false
        showModuleLoadingOverlay()
        
        val selectedId = bottomNav.selectedItemId
        val target = getMainModuleFragment(selectedId) ?: getOrCreateMainModuleFragment(selectedId)
        val isNew = target?.isAdded == false

        // Keep SearchFragment ALIVE on close: hide it and cap at STARTED (like the other resident
        // modules) instead of the old popBackStackImmediate INCLUSIVE that destroyed it. Destroying
        // it wiped recents/results/suggestions state and forced full inflation + setup + a
        // Firestore GET on every re-entry. Hiding FIRST keeps isHidden=true so SearchFragment's
        // lifecycle callbacks never hide the topAppBar on the returning module; its own back
        // callback disables itself in onHiddenChanged(true), so system-back flows on to the
        // module now on screen.
        (supportFragmentManager.findFragmentByTag(TAG_MODULE_SEARCH))?.let { searchFragment = it }
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            searchFragment?.let { if (it.isAdded) { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) } }
            commitNow()
        }

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
        artistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_ARTIST_DETAIL)
    }

    private fun isAnyOverlayModuleVisible(): Boolean {
        fun Fragment?.isActuallyVisible(): Boolean = this != null && isAdded && !isHidden && !isRemoving
        return settingsFragment.isActuallyVisible() ||
            equalizerFragment.isActuallyVisible() ||
            scannerFragment.isActuallyVisible() ||
            searchFragment.isActuallyVisible()
    }

    private fun hideAllMainScreens(transaction: FragmentTransaction, target: Fragment) {
        restoreMainModuleReferences()
        playlistDetailFragment = supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)
        songPlayerFragment = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER)

        hideIfVisible(transaction, principalFragment, target)
        hideIfVisible(transaction, musicFragment, target)
        hideIfVisible(transaction, scannerFragment, target)
        hideIfVisible(transaction, searchFragment, target)
        hideIfVisible(transaction, settingsFragment, target)
        hideIfVisible(transaction, equalizerFragment, target)
        hideIfVisible(transaction, playlistDetailFragment, target)
        hideIfVisible(transaction, songPlayerFragment, target)
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

    /** Keep the footer highlight locked onto the module actually shown — the footer is a
     *  pure reflection of [currentMainNavItemId], never an independent source of truth. */
    private fun syncBottomNavSelection(itemId: Int) {
        if (itemId == View.NO_ID || itemId == R.id.nav_search) return
        if (bottomNav.selectedItemId != itemId) {
            suppressNavListener = true
            bottomNav.selectedItemId = itemId
            suppressNavListener = false
        }
    }

    /**
     * Ends the in-flight navigation and reconciles state. If another module was requested
     * while we were busy, honor that latest request now; otherwise lock the footer onto the
     * module we actually showed. Either way the footer, [currentMainNavItemId] and the single
     * visible main-module fragment converge on the same value — they can no longer disagree.
     */
    private fun finishMainModuleNavigation(shownItemId: Int) {
        isNavigating = false
        val pending = pendingNavItemId
        pendingNavItemId = View.NO_ID
        if (pending != View.NO_ID && pending != shownItemId && moduleTagForItem(pending) != null) {
            switchToMainModule(pending)
        } else {
            syncBottomNavSelection(shownItemId)
        }
    }

    private fun switchToMainModule(itemId: Int): Boolean {
        if (isNavigating) {
            // Coalesce instead of dropping: record the most recent request and run it when the
            // current switch finishes. Dropping it here while still returning success is exactly
            // what let the footer say "Principal" while Biblioteca was on screen.
            pendingNavItemId = itemId
            return true
        }
        isNavigating = true
        // Único choke point de cambio de módulo (tap del footer y navegaciones programáticas):
        // registra el módulo visible que se abandona. from==to (restauraciones, re-taps) es no-op.
        pushModuleNavHistory(visibleMainNavId(), itemId)

        // Reset stale sub-navigation flags — but only if the EQ overlay is no longer shown,
        // otherwise resetting inEqualizerFromSettings here would orphan the overlay with no
        // back-press handler, causing the stuck-EQ bug.
        val eqFrag = equalizerFragment
        val eqOverlayVisible = eqFrag != null && eqFrag.isAdded && !eqFrag.isHidden
        if (!eqOverlayVisible) inEqualizerFromSettings = false
        inScannerFromSettings = false

        supportFragmentManager.executePendingTransactions()
        setContainerOverlayMode(false)
        val tag = moduleTagForItem(itemId) ?: run { finishMainModuleNavigation(currentMainNavItemId); return false }
        val target = supportFragmentManager.findFragmentByTag(tag)
            ?: getOrCreateMainModuleFragment(itemId)
            ?: run { finishMainModuleNavigation(currentMainNavItemId); return false }

        if (currentMainNavItemId == R.id.nav_music && itemId != R.id.nav_music) markStreamingEntryAsLibrary()

        syncBottomNavSelection(itemId)

        // ArtistDetailFragment is added ON TOP of the main module without hiding it (no addToBackStack
        // reveal of what's underneath), so its own visibility must also force a "truly switching" pass
        // — otherwise tapping the footer item for the module already underneath (currentMainNavItemId
        // unchanged, target never hidden) short-circuited below and left the artist screen covering it,
        // making every footer tap look like a no-op. A fresh lookup (not the cached field, which
        // openArtistDetail() never refreshes) so this is correct right after the artist screen opens.
        val artistDetailVisible = supportFragmentManager.findFragmentByTag(TAG_ARTIST_DETAIL)
            ?.let { it.isAdded && !it.isHidden } == true
        val isTrulySwitching = currentMainNavItemId != itemId || !target.isAdded || target.isHidden || artistDetailVisible
        if (!isTrulySwitching && !inSettings) {
            updateHeaderTitleForModule(itemId)
            if (itemId == R.id.nav_music) {
                (target as? MusicPlayerFragment)?.scrollToTop()
            }
            finishMainModuleNavigation(itemId)
            return true
        }

        val isNew = !target.isAdded
        // Update immediately so any re-entrant calls see the correct module id
        currentMainNavItemId = itemId

        // For the very first module on cold start (isNew + nothing visible to hide),
        // skip the overlay + deferred post pattern entirely — execute synchronously
        // to shave ~32-48ms (2-3 frames) off the critical path.
        val isColdStartFirstModule = isNew && supportFragmentManager.fragments.none { it.isAdded && !it.isHidden }

        if (isColdStartFirstModule) {
            // Cover the first frame with the loading overlay (dark bg + spinner) so the user never
            // sees Principal's empty/black first frame while its carousels populate from disk/cloud.
            // PrincipalFragment fades it out once a section actually has content
            // (revealAfterFirstContentPass), and it owns its own ~2.5s "reveal anyway" safety-net.
            // This postDelayed is only a last-resort backstop in case the fragment never calls back
            // (e.g. it died) — so it must be LONGER than the fragment's net, otherwise it would be
            // the thing that reveals an empty/black home early (the exact bug we're fixing).
            val gateReveal = itemId == R.id.nav_principal
            if (gateReveal) {
                setOverlayFullscreen(false)
                showModuleLoadingOverlay()
            }
            supportFragmentManager.beginTransaction().apply {
                setReorderingAllowed(true)
                add(R.id.fragmentContainer, target, tag)
                setMaxLifecycle(target, Lifecycle.State.RESUMED)
                commitAllowingStateLoss()
            }
            getSharedPreferences(PREFS_BOOTSTRAP, Context.MODE_PRIVATE)
                .edit().putInt(PREF_LAST_MAIN_MODULE, itemId).apply()
            if (!isSearchFragmentVisible()) {
                val isFragOwnedHeader = itemId == R.id.nav_music || itemId == R.id.nav_principal
                topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
            }
            // Header (search button, profile-photo prefs read + Glide build, title) was already
            // configured synchronously by onCreate before this cold-start branch runs; showMainShell()
            // re-loads the photo after the first frame. Skip the redundant pre-first-frame call.
            updateHeaderTitleForModule(itemId)
            if (gateReveal) {
                fragmentContainer.postDelayed({ revealModuleContent() }, 3500L)
            }
            finishMainModuleNavigation(itemId)
            return true
        }

        // A "warm" switch targets a module that is already added with its view still resident
        // (main modules are hidden/shown, not replaced). Its content is laid out and appears
        // instantly, so we skip the loading overlay and the extra reveal frame entirely — showing
        // the dark spinner on every re-entry is what made navigation feel like a fresh load.
        val isWarmSwitch = target.isAdded && target.view != null
        // Normal path: show overlay (cold target only), then post fragment work
        if (!isWarmSwitch) {
            setOverlayFullscreen(false)
            showModuleLoadingOverlay()
        }
        // Warm target: its view is already laid out so hide/show is instant. Run the transaction
        // SYNCHRONOUSLY — the deferred post below added a frame of latency to every footer tap,
        // which is what made warm navigation feel heavy. Cold targets still post so the overlay
        // gets a frame to render before the heavy first layout.
        val commitSwitch = Runnable {
            if (isFinishing || isDestroyed) { isNavigating = false; return@Runnable }
            // Re-resolve all fragment references to avoid stale properties after a deferred post
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
                hideIfVisible(this, artistDetailFragment, resolvedTarget)

                if (songPlayerFragment != null && songPlayerFragment!!.isAdded && !songPlayerFragment!!.isHidden) {
                    hide(songPlayerFragment!!)
                }

                if (resolvedTarget.isAdded) show(resolvedTarget) else add(R.id.fragmentContainer, resolvedTarget, tag)
                setMaxLifecycle(resolvedTarget, Lifecycle.State.RESUMED)
                commitAllowingStateLoss()
            }

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

            // Only cold targets were covered by the overlay; warm ones were never hidden. Still
            // clear any overlay that happened to be left visible so a warm switch can't get stuck
            // behind it.
            if (!isWarmSwitch) {
                fragmentContainer.post { revealModuleContent() }
            } else if (moduleLoadingOverlay.visibility == View.VISIBLE) {
                revealModuleContent()
            }

            // Navigation settled — clear the busy flag and reconcile the footer with the
            // module actually shown (or honor a newer request that arrived meanwhile).
            finishMainModuleNavigation(itemId)
        }
        if (isWarmSwitch) commitSwitch.run() else fragmentContainer.post(commitSwitch)
        return true
    }

    fun showModuleLoadingOverlay() {
        moduleLoadingOverlay.alpha = 1f
        moduleLoadingOverlay.visibility = View.VISIBLE
    }

    private fun setOverlayFullscreen(fullscreen: Boolean) {
        val lp = moduleLoadingOverlay.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        if (fullscreen) {
            // Settings / equalizer: cover everything except mini player & footer
            lp.bottomToTop = R.id.llGlobalMiniPlayer
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            // Normal module switch: stop above the mini player (which chains to bottomNav)
            lp.bottomToTop = R.id.llGlobalMiniPlayer
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        }
        lp.bottomMargin = 0
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

    /**
     * Hides the activity-level module loading overlay INSTANTLY (no 200ms cross-fade). Used by
     * PlaylistDetailFragment, which shows its own identical spinner and would otherwise render two
     * overlapping spinners during revealModuleContent()'s fade. Everyone else keeps the fade.
     */
    fun hideModuleLoadingOverlayImmediate() {
        if (isFinishing || isDestroyed) return
        moduleLoadingOverlay.animate().cancel()
        moduleLoadingOverlay.alpha = 0f
        moduleLoadingOverlay.visibility = View.GONE
    }

    private fun markStreamingEntryAsLibrary() {
        persistStreamingScreenState(STREAM_SCREEN_LIBRARY)
        val player = supportFragmentManager.findFragmentByTag(TAG_SONG_PLAYER) as? SongPlayerFragment ?: return
        // Only override return target if the player was originally opened from Music or has no target
        val currentTarget = player.externalGetReturnTargetTag()
        if (currentTarget.isEmpty() || currentTarget == TAG_MODULE_MUSIC) {
            player.externalSetReturnTargetTag(TAG_MODULE_MUSIC)
        }
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

        transferPlayerSavedBarToActivity(player)
        snapshotStreamingScreenBeforeNavigation()
        hideEqualizerImmediately()
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

        transferPlayerSavedBarToActivity(player)

        hideEqualizerImmediately()
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
        // Restore bottomNav ONLY when returning to a main module. When the player was opened
        // from Settings (return target = the settings fragment), forcing the nav visible here
        // painted the bottom bar over the Settings screen.
        val returnedToSettings = fallback != null && fallback.tag == TAG_MODULE_SETTINGS
        if (returnedToSettings) {
            topAppBar.visibility = View.GONE
            bottomNav.visibility = View.GONE
            setSolidNavigationBar(true)
        } else {
            val isFragOwnedHeader = currentMainNavItemId == R.id.nav_music || currentMainNavItemId == R.id.nav_principal
            topAppBar.visibility = if (isFragOwnedHeader) View.GONE else View.VISIBLE
            bottomNav.visibility = View.VISIBLE
        }
        PlaybackEventBus.notifyPlaybackSnapshotUpdated()
    }

    private fun transferPlayerSavedBarToActivity(player: SongPlayerFragment) {
        val playerRoot = player.view as? android.view.ViewGroup ?: return
        val bar = playerRoot.findViewWithTag<View>("saved_bar") ?: return
        // NOTE: do NOT call bar.handler.removeCallbacksAndMessages(null) here — View.getHandler()
        // is the window-shared ViewRootImpl handler, so that purged EVERY pending view.post in the
        // activity. The bar's original auto-dismiss timer firing later is harmless: dismiss() is
        // idempotent via the state machine.
        bar.animate().cancel()
        (bar.parent as? android.view.ViewGroup)?.removeView(bar)

        val activityRoot = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val density = resources.displayMetrics.density
        var margin = (80 * density).toInt()
        val bottomNav = findViewById<View>(R.id.bottomNavigation)
        if (bottomNav != null && bottomNav.visibility == View.VISIBLE) margin += bottomNav.height
        val miniPlayer = findViewById<View>(R.id.llGlobalMiniPlayer)
        if (miniPlayer != null && miniPlayer.visibility == View.VISIBLE) margin += miniPlayer.height

        // Prepare for slide-up entry animation (reset any mid-flight scale from AppSnackbar)
        val enterTranslation = 48 * density
        bar.alpha = 0f
        bar.translationY = enterTranslation
        bar.scaleX = 1f
        bar.scaleY = 1f
        val flp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        flp.gravity = android.view.Gravity.BOTTOM
        flp.bottomMargin = margin
        flp.marginStart = (10 * density).toInt()
        flp.marginEnd = (10 * density).toInt()

        val existing = activityRoot.findViewWithTag<View>("saved_bar")
        if (existing != null) activityRoot.removeView(existing)

        activityRoot.addView(bar, flp)
        bar.post {
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
                .start()
        }
        bar.postDelayed({ TransientBottomBarAnimator.dismiss(bar) }, 3000L)
    }

    private fun resolveSongPlayerReturnTarget(preferredTag: String?): Fragment? {
        // Un tag de MÓDULO raíz capturado al abrir el player queda obsoleto si el usuario cambió
        // de módulo con la cola sonando (abrió desde Principal, navegó a Biblioteca, reabrió el
        // player desde la mini-barra y lo cerró): mostrar ese módulo viejo cambiaba el contenido
        // visible SIN tocar el footer — el "se cambia solo de módulo". Los tags de módulo se
        // ignoran a favor del módulo actualmente seleccionado; los overlays con estado propio
        // (Settings, detalle de playlist) sí se respetan.
        val preferredIsMainModule = preferredTag == TAG_MODULE_PRINCIPAL ||
            preferredTag == TAG_MODULE_MUSIC || preferredTag == TAG_MODULE_SEARCH
        if (!preferredTag.isNullOrEmpty() && !preferredIsMainModule) {
            supportFragmentManager.findFragmentByTag(preferredTag)?.let { if (it.isAdded) return it }
        }
        supportFragmentManager.findFragmentByTag(TAG_PLAYLIST_DETAIL)?.let { if (it.isAdded && !it.isHidden) return it }
        // Return to the currently selected main module (Principal, Music, etc.)
        val current = getMainModuleFragment(currentMainNavItemId)
        if (current != null && current.isAdded && currentMainNavItemId != R.id.nav_equalizer) return current
        // Final fallback
        supportFragmentManager.findFragmentByTag(TAG_MODULE_MUSIC)?.let { if (it.isAdded) return it }
        return null
    }

    private fun hideEqualizerImmediately() {
        val eq = supportFragmentManager.findFragmentByTag(TAG_MODULE_EQUALIZER) ?: return
        if (!eq.isAdded || eq.isHidden || isFinishing || isDestroyed) return
        supportFragmentManager.popBackStackImmediate(TAG_MODULE_EQUALIZER, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        inEqualizerFromSettings = false
        // Release the just-destroyed EqualizerFragment (see returnFromEqualizer): both close paths pop.
        equalizerFragment = null
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
            // Single detail — pop it and return to the module the playlist was opened FROM
            // (the bottom-nav selection is unchanged while a detail is on top), instead of always
            // forcing Music. So a playlist opened from Principal returns to Principal.
            val returnNav = bottomNav.selectedItemId
            if (returnNav == R.id.nav_music) markStreamingEntryAsLibrary()
            supportFragmentManager.popBackStackImmediate(TAG_PLAYLIST_DETAIL, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)

            // Show the origin module cleanly (hides the detail and any other resident module).
            if (!switchToMainModule(returnNav)) switchToMainModule(R.id.nav_music)
        }
        return true
    }

    fun ensureHeaderVisibleForMusic() {
        if (inSettings || inEqualizerFromSettings || inScannerFromSettings) return
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
