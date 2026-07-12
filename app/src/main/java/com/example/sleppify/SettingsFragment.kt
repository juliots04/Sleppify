package com.example.sleppify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tree-structured settings screen (YT Music style).
 * Root shows 4 categories; tapping opens a sub-section.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val DELETE_CONFIRM_WORD = "eliminar"
        private const val SECTION_ROOT = 0
        private const val SECTION_PLAYBACK = 1
        private const val SECTION_DOWNLOADS = 2
        private const val SECTION_HISTORY = 3
        private const val SECTION_ACCOUNT = 4
        private const val SECTION_DATA_SAVER = 5
        private const val SECTION_UPDATE = 6
        private const val HISTORY_PAGE_SIZE = 20
        private const val HISTORY_DAY_VISIBLE_LIMIT = 20
        // Única fuente de verdad de la clave: OfflineAudioStore (quien la consume al descargar).
        private const val KEY_USE_SD_CARD = OfflineAudioStore.KEY_USE_SD_CARD
        private const val STATE_CURRENT_SECTION = "settings_current_section"
        private const val STATE_ENTRY_SECTION = "settings_entry_section"
        private const val STATE_PENDING_ENTRY = "settings_pending_entry"
        // Update encontrado (para no re-buscar tras rotar). La descarga en curso NO se guarda aquí:
        // su fuente de verdad es AppUpdateManager (re-enganchado en renderUpdateSection).
        private const val STATE_UPD_NAME = "settings_upd_name"
        private const val STATE_UPD_CODE = "settings_upd_code"
        private const val STATE_UPD_URL = "settings_upd_url"
        private const val STATE_UPD_NOTES = "settings_upd_notes"
        private const val STATE_UPD_SIZE = "settings_upd_size"
    }

    // --- Navigation state ---
    private var currentSection = SECTION_ROOT
    private var pendingSection: Int? = null
    // The section the user landed on when Settings was opened. Back-press exits Settings
    // (instead of dropping to the root tree) when the user is at this section — so History
    // opened directly from the library returns to the library, while History reached by
    // drilling into the settings tree still backs out to the root.
    private var entrySection = SECTION_ROOT
    // True while a navigateTo*() request is in flight. entrySection is committed only when the
    // requested section is actually rendered (in showSection), so it can never point at a section
    // that was never shown — the desync behind "Back from History drops to the settings root tree".
    private var pendingEntry = false

    // --- Views ---
    private lateinit var tvToolbarTitle: TextView
    // El botón de cámara (scanner) solo tiene sentido en la raíz de Configuración.
    private var btnSettingsCamera: View? = null
    private lateinit var sectionRoot: View
    private lateinit var sectionPlayback: View
    private lateinit var sectionDownloads: View
    private lateinit var sectionHistory: View
    private lateinit var sectionAccount: View
    private lateinit var sectionDataSaver: View
    private lateinit var sectionUpdate: View

    // Playback sub-section
    private lateinit var sbPlaybackCrossfade: SeekBar
    private lateinit var tvPlaybackCrossfadeValue: TextView
    private lateinit var swPlaybackGapless: MaterialSwitch
    private lateinit var swPlaybackOffline: MaterialSwitch
    private lateinit var swPlaybackEqGlobal: MaterialSwitch

    // Downloads sub-section
    private lateinit var tvDownloadsStorageFree: TextView
    private lateinit var tvDownloadsStorageUsed: TextView
    private lateinit var pbStorageInternal: ProgressBar
    private lateinit var tvDownloadsSdFree: TextView
    private lateinit var tvDownloadsSdUsed: TextView
    private lateinit var pbStorageSd: ProgressBar
    private lateinit var swUseSdCard: MaterialSwitch
    private lateinit var swShowDeviceFiles: MaterialSwitch
    private lateinit var swDownloadWifiOnly: MaterialSwitch
    private lateinit var tvDownloadsDeleteTitle: TextView

    // History sub-section
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvHistoryEmpty: TextView
    private var historyAdapter: HistoryAdapter? = null
    private var historyLoaded = 0

    // Data saver sub-section
    private lateinit var tvQualityMobileValue: TextView
    private lateinit var tvQualityWifiValue: TextView
    private lateinit var swLimitMobileData: MaterialSwitch
    private lateinit var swWifiOnlyPlayback: MaterialSwitch
    private lateinit var swNoMusicVideos: MaterialSwitch

    // Account sub-section
    private lateinit var ivAccountPhoto: ShapeableImageView
    private lateinit var tvAccountName: TextView
    private lateinit var tvAccountEmail: TextView
    private lateinit var rvTopPlayed: RecyclerView
    private lateinit var llAccountTopPlayedEmpty: View

    // Update sub-section
    private lateinit var tvUpdateInstalledVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var llUpdateCard: View
    private lateinit var tvUpdateNewVersion: TextView
    private lateinit var tvUpdateVersionPill: TextView
    private lateinit var tvUpdateNotes: TextView
    private lateinit var llUpdateProgress: View
    private lateinit var pbUpdateDownload: ProgressBar
    private lateinit var tvUpdatePercent: TextView
    private lateinit var btnUpdateAction: TextView
    private var availableUpdate: AppUpdateManager.UpdateInfo? = null
    private var updateCheckInFlight = false
    private var autoStartDownloadOnOpen = false

    private val settingsPrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    private val authManager: AuthManager by lazy { AuthManager.getInstance(requireContext()) }

    private val audioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LocalFilesStore.setEnabled(requireContext(), true)
            scanAndCacheLocalFiles()
            renderShowDeviceFiles()
        } else {
            LocalFilesStore.setEnabled(requireContext(), false)
            renderShowDeviceFiles()
            AppSnackbar.show(activity, "Se necesita permiso para acceder a la música")
        }
    }

    private var deleteAccountInFlight = false
    private var downloadCleanupInFlight = false

    fun refreshOfflineStateFromPrefs() {
        if (isAdded && currentSection == SECTION_PLAYBACK) renderPlaybackSection()
    }

    fun isAccountSubSectionActive(): Boolean = currentSection == SECTION_ACCOUNT
    fun isHistoryOrAccountActive(): Boolean = currentSection == SECTION_HISTORY || currentSection == SECTION_ACCOUNT

    // --- Lifecycle ---

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val llSettingsToolbar = view.findViewById<View>(R.id.llSettingsToolbar)
        tvToolbarTitle = view.findViewById(R.id.tvSettingsToolbarTitle)
        view.findViewById<View>(R.id.btnSettingsBack)?.setOnClickListener { onBackPressed() }
        btnSettingsCamera = view.findViewById(R.id.btnSettingsCamera)
        btnSettingsCamera?.setOnClickListener {
            (activity as? MainActivity)?.openScannerFromSettings()
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            llSettingsToolbar?.setPadding(llSettingsToolbar.paddingLeft, top, llSettingsToolbar.paddingRight, llSettingsToolbar.paddingBottom)
            insets
        }

        sectionRoot = view.findViewById(R.id.settingsRoot)
        sectionPlayback = view.findViewById(R.id.settingsPlayback)
        sectionDownloads = view.findViewById(R.id.settingsDownloads)
        sectionDataSaver = view.findViewById(R.id.settingsDataSaver)
        sectionHistory = view.findViewById(R.id.settingsHistory)
        sectionAccount = view.findViewById(R.id.settingsAccount)
        sectionUpdate = view.findViewById(R.id.settingsUpdate)

        setupRootSection(view)
        setupPlaybackSection(view)
        setupDownloadsSection(view)
        setupDataSaverSection(view)
        setupHistorySection(view)
        setupAccountSection(view)
        setupUpdateSection(view)

        // Restore across process death (e.g. the user switched apps and the system reclaimed
        // us). MainActivity re-enters Settings by finding this fragment by tag without calling
        // navigateTo*, so the entry/current section would otherwise reset to the root tree and
        // break back-navigation. A pending section (a fresh navigateTo* request) takes priority.
        if (savedInstanceState != null) {
            entrySection = savedInstanceState.getInt(STATE_ENTRY_SECTION, entrySection)
            pendingEntry = savedInstanceState.getBoolean(STATE_PENDING_ENTRY, pendingEntry)
            if (pendingSection == null) {
                currentSection = savedInstanceState.getInt(STATE_CURRENT_SECTION, currentSection)
            }
            val updName = savedInstanceState.getString(STATE_UPD_NAME)
            if (updName != null) {
                availableUpdate = AppUpdateManager.UpdateInfo(
                    updName,
                    savedInstanceState.getInt(STATE_UPD_CODE),
                    savedInstanceState.getString(STATE_UPD_URL, ""),
                    savedInstanceState.getString(STATE_UPD_NOTES, ""),
                    savedInstanceState.getLong(STATE_UPD_SIZE, 0L)
                )
            }
        }

        val initialSection = pendingSection ?: currentSection
        pendingSection = null
        showSection(initialSection)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_SECTION, currentSection)
        outState.putInt(STATE_ENTRY_SECTION, entrySection)
        outState.putBoolean(STATE_PENDING_ENTRY, pendingEntry)
        availableUpdate?.let {
            outState.putString(STATE_UPD_NAME, it.versionName)
            outState.putInt(STATE_UPD_CODE, it.versionCode)
            outState.putString(STATE_UPD_URL, it.apkUrl)
            outState.putString(STATE_UPD_NOTES, it.notes)
            outState.putLong(STATE_UPD_SIZE, it.sizeBytes)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Fall back to the section already shown — NOT SECTION_ROOT. With
            // setReorderingAllowed(true) a freshly added fragment can receive this
            // dispatch right after onViewCreated already consumed pendingSection; if we
            // defaulted to root here we'd clobber the requested section (e.g. History)
            // back to the configuration tree.
            val target = pendingSection ?: currentSection
            pendingSection = null
            showSection(target)
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentSection == SECTION_PLAYBACK) renderPlaybackSection()
        if (currentSection == SECTION_ACCOUNT) renderAccountSection()
        // Al volver del instalador del sistema (p. ej. el usuario canceló), el botón se quedaba
        // pegado en "Instalando…". Re-render deja la card lista para reintentar.
        if (currentSection == SECTION_UPDATE && !AppUpdateManager.isDownloadInFlight()) {
            restoreUpdateActionButton()
        }
    }

    fun onBackPressed() {
        // Exit Settings when we're at the section the user entered on (e.g. History opened
        // from the library → back returns to the library). Otherwise a deeper sub-section
        // backs out to the settings root tree first.
        if (currentSection != SECTION_ROOT && currentSection != entrySection) {
            showSection(SECTION_ROOT)
        } else {
            (activity as? MainActivity)?.returnFromSettings()
        }
    }

    fun refreshCurrentSectionVisibility() {
        if (isAdded) {
            showSection(currentSection)
        }
    }

    fun navigateToHistory() {
        pendingEntry = true
        requestSection(SECTION_HISTORY)
    }

    fun navigateToRoot() {
        pendingEntry = true
        requestSection(SECTION_ROOT)
    }

    /**
     * Abre directo la sección Actualizar. Con [update] (viene de la ventana emergente) la card
     * se pinta al instante y LA DESCARGA ARRANCA SOLA — el usuario llega con el % ya corriendo.
     * Sin update, se dispara el chequeo automáticamente.
     */
    fun navigateToUpdate(update: AppUpdateManager.UpdateInfo?) {
        if (update != null) {
            availableUpdate = update
            autoStartDownloadOnOpen = true
        }
        pendingEntry = true
        requestSection(SECTION_UPDATE)
    }

    /**
     * Declares which section to show the next time the fragment becomes visible.
     * If it is already added and visible the switch happens immediately; otherwise the
     * section is recorded and applied by onViewCreated/onHiddenChanged when it appears.
     */
    private fun requestSection(section: Int) {
        if (isAdded && !isHidden) {
            pendingSection = null
            showSection(section)
        } else {
            pendingSection = section
        }
    }

    // --- Section navigation ---

    private fun showSection(section: Int) {
        currentSection = section
        // Commit the entry section only when the requested section is actually rendered. A deferred
        // navigateTo*() (fragment hidden when the request arrived, e.g. process-death restore) can
        // therefore never leave entrySection pointing at a section that was never shown.
        if (pendingEntry) {
            entrySection = section
            pendingEntry = false
        }
        sectionRoot.visibility = if (section == SECTION_ROOT) View.VISIBLE else View.GONE
        sectionPlayback.visibility = if (section == SECTION_PLAYBACK) View.VISIBLE else View.GONE
        sectionDownloads.visibility = if (section == SECTION_DOWNLOADS) View.VISIBLE else View.GONE
        sectionDataSaver.visibility = if (section == SECTION_DATA_SAVER) View.VISIBLE else View.GONE
        sectionHistory.visibility = if (section == SECTION_HISTORY) View.VISIBLE else View.GONE
        sectionAccount.visibility = if (section == SECTION_ACCOUNT) View.VISIBLE else View.GONE
        sectionUpdate.visibility = if (section == SECTION_UPDATE) View.VISIBLE else View.GONE

        tvToolbarTitle.text = when (section) {
            SECTION_PLAYBACK -> "Reproducción"
            SECTION_DOWNLOADS -> "Descargas y almacenamiento"
            SECTION_DATA_SAVER -> "Ahorro de datos"
            SECTION_HISTORY -> "Historial"
            SECTION_ACCOUNT -> "Cuenta"
            SECTION_UPDATE -> "Actualizar"
            else -> "Configuración"
        }

        // La cámara (scanner) solo en la raíz; en las subsecciones el header va limpio.
        btnSettingsCamera?.visibility = if (section == SECTION_ROOT) View.VISIBLE else View.GONE

        // Scroll to top
        when (section) {
            SECTION_PLAYBACK -> sectionPlayback.findViewById<ScrollView>(R.id.svSettingsPlayback)?.scrollTo(0, 0)
            SECTION_DOWNLOADS -> sectionDownloads.findViewById<ScrollView>(R.id.svSettingsDownloads)?.scrollTo(0, 0)
            SECTION_DATA_SAVER -> sectionDataSaver.findViewById<ScrollView>(R.id.svSettingsDataSaver)?.scrollTo(0, 0)
            SECTION_HISTORY -> rvHistory.scrollToPosition(0)
            SECTION_ACCOUNT -> sectionAccount.findViewById<ScrollView>(R.id.svSettingsAccount)?.scrollTo(0, 0)
            SECTION_UPDATE -> sectionUpdate.findViewById<ScrollView>(R.id.svSettingsUpdate)?.scrollTo(0, 0)
        }

        // Render content on open
        when (section) {
            SECTION_PLAYBACK -> renderPlaybackSection()
            SECTION_DOWNLOADS -> renderDownloadsSection()
            SECTION_DATA_SAVER -> renderDataSaverSection()
            SECTION_HISTORY -> loadHistory(reset = true)
            SECTION_ACCOUNT -> renderAccountSection()
            SECTION_UPDATE -> renderUpdateSection()
        }

        // Bottom nav + mini-player visibility for History and Account
        val mainActivity = activity as? MainActivity
        if (section == SECTION_HISTORY || section == SECTION_ACCOUNT) {
            mainActivity?.findViewById<View>(R.id.bottomNavigation)?.visibility = View.VISIBLE
            mainActivity?.getGlobalMiniPlayer()?.updateUi()
        } else {
            mainActivity?.findViewById<View>(R.id.bottomNavigation)?.visibility = View.GONE
            mainActivity?.getGlobalMiniPlayer()?.hide()
        }
    }

    // --- Root section ---

    private fun setupRootSection(view: View) {
        view.findViewById<View>(R.id.rowSettingsPlayback)?.setOnClickListener { showSection(SECTION_PLAYBACK) }
        view.findViewById<View>(R.id.rowSettingsDownloads)?.setOnClickListener { showSection(SECTION_DOWNLOADS) }
        view.findViewById<View>(R.id.rowSettingsDataSaver)?.setOnClickListener { showSection(SECTION_DATA_SAVER) }
        view.findViewById<View>(R.id.rowSettingsHistory)?.setOnClickListener { showSection(SECTION_HISTORY) }
        view.findViewById<View>(R.id.rowSettingsAccount)?.setOnClickListener { showSection(SECTION_ACCOUNT) }
        view.findViewById<View>(R.id.rowSettingsUpdate)?.setOnClickListener { showSection(SECTION_UPDATE) }
    }

    // --- Playback section ---

    private fun setupPlaybackSection(view: View) {
        sbPlaybackCrossfade = view.findViewById(R.id.sbPlaybackCrossfade)
        tvPlaybackCrossfadeValue = view.findViewById(R.id.tvPlaybackCrossfadeValue)
        swPlaybackGapless = view.findViewById(R.id.swPlaybackGapless)
        swPlaybackOffline = view.findViewById(R.id.swPlaybackOffline)
        swPlaybackEqGlobal = view.findViewById(R.id.swPlaybackEqGlobal)

        view.findViewById<View>(R.id.rowPlaybackEqualizer)?.setOnClickListener {
            (activity as? MainActivity)?.openEqualizerFromSettings()
        }
    }

    private fun renderPlaybackSection() {
        if (!isAdded) return
        val crossfade = settingsPrefs.getInt(CloudSyncManager.KEY_OFFLINE_CROSSFADE_SECONDS, 0).coerceIn(0, 12)
        val gapless = settingsPrefs.getBoolean(CloudSyncManager.KEY_GAPLESS_PLAYBACK, true)
        val offlineMode = settingsPrefs.getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false)

        sbPlaybackCrossfade.apply {
            setOnSeekBarChangeListener(null)
            max = 12; progress = crossfade
            tvPlaybackCrossfadeValue.text = if (crossfade == 0) "off" else "${crossfade}s"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tvPlaybackCrossfadeValue.text = if (p == 0) "off" else "${p}s" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { settingsPrefs.edit().putInt(CloudSyncManager.KEY_OFFLINE_CROSSFADE_SECONDS, s?.progress ?: 0).apply() }
            })
        }

        swPlaybackGapless.apply {
            setOnCheckedChangeListener(null); isChecked = gapless
            setOnCheckedChangeListener { _, c -> settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_GAPLESS_PLAYBACK, c).apply() }
        }

        swPlaybackOffline.apply {
            setOnCheckedChangeListener(null); isChecked = offlineMode
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, c).apply()
                (activity as? MainActivity)?.notifyOfflineModeChanged()
            }
        }

        // EQ scope: global (session 0, todo el sistema) vs solo Sleppify (sesión del player).
        // Vive en global_eq_prefs porque es AudioEffectsService quien la resuelve en cada apply.
        val eqPrefs = requireContext().getSharedPreferences(AudioEffectsService.PREFS_NAME, Context.MODE_PRIVATE)
        swPlaybackEqGlobal.apply {
            setOnCheckedChangeListener(null)
            isChecked = eqPrefs.getBoolean(AudioEffectsService.KEY_APPLY_GLOBAL, true)
            setOnCheckedChangeListener { _, c ->
                eqPrefs.edit().putBoolean(AudioEffectsService.KEY_APPLY_GLOBAL, c).apply()
                // Re-apply inmediato: el service rehace el engine sobre la sesión que corresponda
                // (si el EQ está apagado, sendApply degrada a stop y no pasa nada).
                AudioEffectsService.sendApply(requireContext().applicationContext)
            }
        }
    }

    // --- Downloads section ---

    private fun setupDownloadsSection(view: View) {
        tvDownloadsStorageFree = view.findViewById(R.id.tvDownloadsStorageFree)
        tvDownloadsStorageUsed = view.findViewById(R.id.tvDownloadsStorageUsed)
        pbStorageInternal = view.findViewById(R.id.pbStorageInternal)
        tvDownloadsSdFree = view.findViewById(R.id.tvDownloadsSdFree)
        tvDownloadsSdUsed = view.findViewById(R.id.tvDownloadsSdUsed)
        pbStorageSd = view.findViewById(R.id.pbStorageSd)
        swUseSdCard = view.findViewById(R.id.swUseSdCard)
        swShowDeviceFiles = view.findViewById(R.id.swShowDeviceFiles)
        swDownloadWifiOnly = view.findViewById(R.id.swDownloadWifiOnly)
        tvDownloadsDeleteTitle = view.findViewById(R.id.tvDownloadsDeleteTitle)

        view.findViewById<View>(R.id.rowDownloadsDelete)?.setOnClickListener { showDeleteAllDownloadsConfirmation() }
    }

    private fun renderDownloadsSection() {
        if (!isAdded) return
        // Storage info. "En uso" = lo que OCUPA LA APP en ese volumen (descargas + caches),
        // no el uso total del dispositivo; la barra muestra ese uso relativo al volumen.
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Internal volume totals
            val stat = try { StatFs(Environment.getDataDirectory().absolutePath) } catch (_: Exception) { null }
            val totalInternal = stat?.totalBytes ?: 1L
            val freeInternal = stat?.availableBytes ?: 0L

            // App usage on the INTERNAL volume: downloads + every cache the app grows
            // (exo_stream_cache, Glide image cache, comments cache, updates…).
            val internalDownloads = calculateSize(File(appCtx.filesDir, "offline_audio"))
            val internalCaches = calculateSize(appCtx.cacheDir)
            val appUsedInternal = internalDownloads + internalCaches
            val internalPercent = appUsagePercent(appUsedInternal, totalInternal)

            // SD volume (slot 0 of getExternalFilesDirs is ALWAYS the primary/internal one)
            val sdFilesDir = try {
                appCtx.getExternalFilesDirs(null)?.drop(1)?.filterNotNull()
                    ?.firstOrNull { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            } catch (_: Exception) { null }
            val sdStat = sdFilesDir?.let { try { StatFs(it.absolutePath) } catch (_: Exception) { null } }
            val totalSd = sdStat?.totalBytes ?: 1L
            val freeSd = sdStat?.availableBytes ?: 0L

            // App usage on the SD volume: downloads there + any external cache on that card.
            val sdDownloads = sdFilesDir?.let { calculateSize(File(it, "offline_audio")) } ?: 0L
            val sdCaches = try {
                appCtx.externalCacheDirs?.drop(1)?.filterNotNull()?.sumOf { calculateSize(it) } ?: 0L
            } catch (_: Exception) { 0L }
            val appUsedSd = sdDownloads + sdCaches
            val sdPercent = if (sdStat != null) appUsagePercent(appUsedSd, totalSd) else 0

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                tvDownloadsStorageFree.text = "${formatSize(freeInternal)} libres"
                tvDownloadsStorageUsed.text = "${formatSize(appUsedInternal)} en uso"
                pbStorageInternal.progress = internalPercent

                if (sdStat != null) {
                    tvDownloadsSdFree.text = "${formatSize(freeSd)} libres"
                    tvDownloadsSdUsed.text = "${formatSize(appUsedSd)} en uso"
                    pbStorageSd.progress = sdPercent
                } else {
                    tvDownloadsSdFree.text = "No disponible"
                    tvDownloadsSdUsed.text = ""
                    pbStorageSd.progress = 0
                }
            }
        }

        // SD card switch — OfflineAudioStore lee esta pref para decidir el volumen de escritura.
        val useSd = settingsPrefs.getBoolean(KEY_USE_SD_CARD, false)
        swUseSdCard.apply {
            setOnCheckedChangeListener(null); isChecked = useSd
            setOnCheckedChangeListener { _, c ->
                if (c && !OfflineAudioStore.hasSdCard(requireContext())) {
                    // Sin SD montada no hay nada que activar — el switch vuelve solo.
                    setOnCheckedChangeListener(null); isChecked = false
                    renderDownloadsSection()
                    AppSnackbar.show(activity, "No se detectó una tarjeta SD")
                    return@setOnCheckedChangeListener
                }
                settingsPrefs.edit().putBoolean(KEY_USE_SD_CARD, c).apply()
                // Los estados cacheados siguen siendo válidos (los lookups recorren ambos
                // volúmenes), pero limpiar es barato y evita cualquier residuo de rutas.
                OfflineAudioStore.clearOfflineAudioStateCache()
            }
        }

        // Switches
        renderShowDeviceFiles()

        val wifiOnly = !settingsPrefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
        swDownloadWifiOnly.apply {
            setOnCheckedChangeListener(null); isChecked = wifiOnly
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, !c).apply()
            }
        }

        tvDownloadsDeleteTitle.text = if (downloadCleanupInFlight) "Eliminando…" else "Borrar descargas"
    }

    private fun renderShowDeviceFiles() {
        if (!isAdded) return
        val enabled = LocalFilesStore.isEnabled(requireContext())
        swShowDeviceFiles.setOnCheckedChangeListener(null)
        swShowDeviceFiles.isChecked = enabled
        swShowDeviceFiles.setOnCheckedChangeListener { _, checked ->
            if (checked) requestAudioPermissionAndEnable()
            else {
                LocalFilesStore.setEnabled(requireContext(), false)
                (activity as? MainActivity)?.refreshMusicLibrary()
            }
        }
    }

    private fun requestAudioPermissionAndEnable() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        audioPermissionLauncher.launch(permission)
    }

    private fun scanAndCacheLocalFiles() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val tracks = LocalFilesStore.scanLocalFiles(requireContext())
            LocalFilesStore.cacheFiles(requireContext(), tracks)
            withContext(Dispatchers.Main) { (activity as? MainActivity)?.refreshMusicLibrary() }
        }
    }

    // --- Data saver section ---

    private fun setupDataSaverSection(view: View) {
        tvQualityMobileValue = view.findViewById(R.id.tvQualityMobileValue)
        tvQualityWifiValue = view.findViewById(R.id.tvQualityWifiValue)
        swLimitMobileData = view.findViewById(R.id.swLimitMobileData)
        swWifiOnlyPlayback = view.findViewById(R.id.swWifiOnlyPlayback)
        swNoMusicVideos = view.findViewById(R.id.swNoMusicVideos)

        view.findViewById<View>(R.id.rowQualityMobile)?.setOnClickListener {
            showQualityDialog(CloudSyncManager.KEY_STREAMING_QUALITY_MOBILE, "Calidad del audio en redes móviles", tvQualityMobileValue)
        }
        view.findViewById<View>(R.id.rowQualityWifi)?.setOnClickListener {
            showQualityDialog(CloudSyncManager.KEY_STREAMING_QUALITY_WIFI, "Calidad del audio con Wi-Fi", tvQualityWifiValue)
        }
    }

    private fun renderDataSaverSection() {
        if (!isAdded) return
        tvQualityMobileValue.text = qualityDisplayName(
            settingsPrefs.getString(CloudSyncManager.KEY_STREAMING_QUALITY_MOBILE, CloudSyncManager.STREAMING_QUALITY_MEDIUM) ?: CloudSyncManager.STREAMING_QUALITY_MEDIUM
        )
        tvQualityWifiValue.text = qualityDisplayName(
            settingsPrefs.getString(CloudSyncManager.KEY_STREAMING_QUALITY_WIFI, CloudSyncManager.STREAMING_QUALITY_MEDIUM) ?: CloudSyncManager.STREAMING_QUALITY_MEDIUM
        )

        swLimitMobileData.apply {
            setOnCheckedChangeListener(null)
            isChecked = settingsPrefs.getBoolean(CloudSyncManager.KEY_LIMIT_MOBILE_DATA, false)
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_LIMIT_MOBILE_DATA, c).apply()
            }
        }
        swWifiOnlyPlayback.apply {
            setOnCheckedChangeListener(null)
            isChecked = settingsPrefs.getBoolean(CloudSyncManager.KEY_WIFI_ONLY_PLAYBACK, false)
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_WIFI_ONLY_PLAYBACK, c).apply()
            }
        }
        swNoMusicVideos.apply {
            setOnCheckedChangeListener(null)
            isChecked = settingsPrefs.getBoolean(CloudSyncManager.KEY_NO_MUSIC_VIDEOS, true)
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_NO_MUSIC_VIDEOS, c).apply()
            }
        }
    }

    private fun qualityDisplayName(key: String): String = when (key) {
        CloudSyncManager.STREAMING_QUALITY_LOW -> "Baja"
        CloudSyncManager.STREAMING_QUALITY_MEDIUM -> "Normal"
        CloudSyncManager.STREAMING_QUALITY_HIGH -> "Alta"
        CloudSyncManager.STREAMING_QUALITY_VERY_HIGH -> "Siempre alta"
        else -> "Normal"
    }

    private fun showQualityDialog(prefKey: String, title: String, valueTextView: TextView) {
        if (!isAdded) return
        val ctx = requireContext()
        val currentValue = settingsPrefs.getString(prefKey, CloudSyncManager.STREAMING_QUALITY_MEDIUM)
            ?: CloudSyncManager.STREAMING_QUALITY_MEDIUM

        val options = arrayOf(
            CloudSyncManager.STREAMING_QUALITY_LOW,
            CloudSyncManager.STREAMING_QUALITY_MEDIUM,
            CloudSyncManager.STREAMING_QUALITY_HIGH,
            CloudSyncManager.STREAMING_QUALITY_VERY_HIGH
        )
        val labels = arrayOf("Baja", "Normal", "Alta", "Siempre alta")
        var selectedIndex = options.indexOf(currentValue).coerceAtLeast(0)

        val dialogView = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(12))
            setBackgroundColor(android.graphics.Color.parseColor("#FF1A1A1A"))
        }

        val tvTitle = TextView(ctx).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        dialogView.addView(tvTitle, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val radioGroup = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        for (i in labels.indices) {
            val rb = android.widget.RadioButton(ctx).apply {
                text = labels[i]
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(R.color.stitch_blue)
                )
                id = i
                isChecked = (i == selectedIndex)
            }
            radioGroup.addView(rb)
        }
        dialogView.addView(radioGroup)

        val dialog = android.app.Dialog(ctx).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = TextView(ctx).apply {
            text = "CANCELAR"
            setTextColor(ctx.getColor(R.color.stitch_blue))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { dialog.dismiss() }
        }
        val cancelContainer = android.widget.LinearLayout(ctx).apply {
            gravity = android.view.Gravity.END
            addView(btnCancel)
        }
        dialogView.addView(cancelContainer, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId in options.indices) {
                selectedIndex = checkedId
                settingsPrefs.edit().putString(prefKey, options[selectedIndex]).apply()
                valueTextView.text = labels[selectedIndex]
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // --- History section ---

    private fun setupHistorySection(view: View) {
        rvHistory = view.findViewById(R.id.rvSettingsHistory)
        tvHistoryEmpty = view.findViewById(R.id.tvHistoryEmpty)
        historyAdapter = HistoryAdapter(
            onClick = { entry -> playHistoryEntry(entry) },
            onMore = { entry -> showHistoryTrackOptions(entry) }
        )
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter

        rvHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= (historyAdapter?.itemCount ?: 0) - 5) {
                    loadHistory(reset = false)
                }
            }
        })
    }

    private fun loadHistory(reset: Boolean) {
        if (!isAdded) return
        if (reset) historyLoaded = 0
        val offset = historyLoaded
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val page = ListenHistoryStore.getPage(requireContext(), offset, HISTORY_PAGE_SIZE)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (reset) historyAdapter?.setItems(page)
                else historyAdapter?.appendItems(page)
                historyLoaded = offset + page.size
                tvHistoryEmpty.visibility = if (historyAdapter?.itemCount == 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun playHistoryEntry(entry: ListenHistoryStore.HistoryEntry) {
        if (!isAdded) return
        SongPlayerLauncher.open(
            activity,
            arrayListOf(entry.videoId), arrayListOf(entry.title), arrayListOf(entry.artist),
            arrayListOf(""), arrayListOf(entry.imageUrl),
            0,
            /* startPlaying = */ true,
            "module_settings",
            /* openPlayerUi = */ false,
            /* fromStart = */ true
        )
    }

    private fun showHistoryTrackOptions(entry: ListenHistoryStore.HistoryEntry) {
        if (!isAdded) return
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_track_options, null)
        dialog.setContentView(view)

        // Header
        val tvTitle = view.findViewById<TextView>(R.id.tvBsTrackTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvBsTrackSubtitle)
        val ivArt = view.findViewById<ImageView>(R.id.ivBsTrackArt)
        tvTitle.text = entry.title.ifEmpty { "Tema" }
        tvSubtitle.text = entry.artist.ifEmpty { "Artista" }
        if (LocalFilesStore.isLocalVideoId(entry.videoId)) {
            LocalArtworkResolver.loadInto(ivArt, entry.videoId)
        } else if (entry.imageUrl.isNotEmpty()) {
            Glide.with(ivArt).load(entry.imageUrl).centerCrop().placeholder(R.color.surface_high).into(ivArt)
        }
        view.findViewById<View>(R.id.ivBsOfflineState)?.visibility = View.GONE

        // Top 3 buttons: Reproducir, Descargar, Compartir
        val btnPlay = view.findViewById<View>(R.id.btnBsPlayNext)
        val ivPlay = view.findViewById<ImageView>(R.id.ivBsPlayNextIcon)
        val tvPlay = view.findViewById<TextView>(R.id.tvBsPlayNextLabel)
        ivPlay.setImageResource(R.drawable.ic_player_play)
        tvPlay.text = "Reproducir"
        btnPlay.setOnClickListener { dialog.dismiss(); playHistoryEntry(entry) }

        val btnDownload = view.findViewById<View>(R.id.btnBsAddPrimary)
        val ivDownload = view.findViewById<ImageView>(R.id.ivBsAddPrimary)
        val tvDownload = view.findViewById<TextView>(R.id.tvBsAddPrimary)
        val hasOffline = OfflineAudioStore.hasOfflineAudio(ctx, entry.videoId)
        ivDownload.setImageResource(if (hasOffline) R.drawable.ic_delete_modern else R.drawable.ic_download_bold)
        tvDownload.text = if (hasOffline) "Eliminar\ndescarga" else "Descargar"
        btnDownload.setOnClickListener {
            dialog.dismiss()
            if (hasOffline) {
                OfflineAudioStore.deleteOfflineAudio(ctx, entry.videoId)
                AppSnackbar.show(activity, "Descarga eliminada")
            } else {
                AppSnackbar.show(activity, "Descarga iniciada")
            }
        }

        val btnShare = view.findViewById<View>(R.id.btnBsShare)
        btnShare.setOnClickListener {
            dialog.dismiss()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${entry.videoId}")
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir"))
        }

        // List actions
        // Iniciar radio
        val btnRadio = view.findViewById<View>(R.id.btnBsPlay)
        btnRadio.visibility = View.VISIBLE
        view.findViewById<ImageView>(R.id.ivBsPlay).setImageResource(R.drawable.ic_bs_radio)
        view.findViewById<TextView>(R.id.tvBsPlayLabel).text = "Iniciar radio"
        btnRadio.setOnClickListener {
            dialog.dismiss()
            val radioId = "RDAMVM${entry.videoId}"
            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = MainActivity.ACTION_PLAY_FROM_SEARCH
                putExtra(SearchFragment.EXTRA_RESULT_TYPE, "playlist")
                putExtra(SearchFragment.EXTRA_RESULT_CONTENT_ID, radioId)
                putExtra(SearchFragment.EXTRA_RESULT_TITLE, "Radio: ${entry.title}")
                putExtra(SearchFragment.EXTRA_RESULT_SUBTITLE, entry.artist)
                putExtra(SearchFragment.EXTRA_RESULT_THUMBNAIL, entry.imageUrl)
            }
            (activity as? MainActivity)?.handlePlayFromSearchIntent(intent)
        }

        // Ir a artista
        val btnArtist = view.findViewById<View>(R.id.btnBsGoToArtist)
        if (entry.artist.isNotEmpty()) {
            btnArtist.visibility = View.VISIBLE
            btnArtist.setOnClickListener {
                dialog.dismiss()
                (activity as? MainActivity)?.openSearchFragmentWithQuery(entry.artist)
            }
        }

        // Agregar a la fila
        val btnQueue = view.findViewById<View>(R.id.btnBsAddToQueue)
        btnQueue.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvBsAddToQueue).text = "Agregar a la fila"
        btnQueue.setOnClickListener {
            dialog.dismiss()
            val fm = parentFragmentManager
            val player = fm.findFragmentByTag(AppConstants.TAG_SONG_PLAYER) as? SongPlayerFragment
            if (player != null && player.isAdded) {
                player.externalEnqueue(entry.videoId, entry.title, entry.artist, "", entry.imageUrl)
                AppSnackbar.show(activity, "Agregado a la fila")
            } else {
                playHistoryEntry(entry)
            }
        }

        // Añadir a playlist
        val btnFav = view.findViewById<View>(R.id.btnBsFavorite)
        btnFav.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvBsFavorite).text = "Añadir a playlist"
        btnFav.setOnClickListener {
            dialog.dismiss()
            FavoritesPlaylistStore.upsertFavorite(ctx, entry.videoId, entry.title, entry.artist, "", entry.imageUrl)
            AppSnackbar.show(activity, "Añadido a Favoritos")
        }

        // Hide unused rows
        view.findViewById<View>(R.id.btnBsPlayPlaylist).visibility = View.GONE
        view.findViewById<View>(R.id.btnBsReplace).visibility = View.GONE
        view.findViewById<View>(R.id.btnBsDownload).visibility = View.GONE

        dialog.show()
    }

    // --- Account section ---

    private fun setupAccountSection(view: View) {
        ivAccountPhoto = view.findViewById(R.id.ivAccountPhoto)
        tvAccountName = view.findViewById(R.id.tvAccountName)
        tvAccountEmail = view.findViewById(R.id.tvAccountEmail)
        rvTopPlayed = view.findViewById(R.id.rvAccountTopPlayed)
        rvTopPlayed.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        llAccountTopPlayedEmpty = view.findViewById(R.id.llAccountTopPlayedEmpty)

        view.findViewById<View>(R.id.btnAccountDiscover)?.setOnClickListener {
            val mainActivity = activity as? MainActivity
            mainActivity?.returnFromSettings()
            mainActivity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId = R.id.nav_principal
        }

        view.findViewById<View>(R.id.rowAccountSignOut)?.setOnClickListener { performSignOut() }
        view.findViewById<View>(R.id.rowAccountDelete)?.setOnClickListener { showDeleteAccountDataConfirmation() }
    }

    private fun renderAccountSection() {
        if (!isAdded) return
        val signedIn = authManager.isSignedIn()
        if (signedIn) {
            tvAccountName.text = authManager.getDisplayName()?.takeIf { it.isNotBlank() } ?: "Usuario"
            tvAccountEmail.text = authManager.getEmail() ?: ""
            val photo = authManager.getPhotoUrl()?.toString()
            if (!photo.isNullOrEmpty()) {
                Glide.with(this).load(photo).circleCrop()
                    .placeholder(R.color.surface_high).error(R.color.surface_high).into(ivAccountPhoto)
            } else {
                ivAccountPhoto.setImageDrawable(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.surface_high)))
            }
        } else {
            tvAccountName.text = "No has iniciado sesión"
            tvAccountEmail.text = ""
            ivAccountPhoto.setImageDrawable(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.surface_high)))
        }

        // Top played carousel — songs played since last Monday 00:00 local time
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mondayMidnight = Calendar.getInstance().apply {
                // Roll back to Monday of the current week
                while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val top = PlayCountStore.getTopEntries(requireContext(), 50)
                .filter { it.lastPlayedAtMs >= mondayMidnight && it.videoId != it.playlistId }
                .take(20)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                rvTopPlayed.adapter = TopPlayedAdapter(top) { entry -> playTopEntry(entry) }

                if (top.isEmpty()) {
                    rvTopPlayed.visibility = View.GONE
                    llAccountTopPlayedEmpty.visibility = View.VISIBLE
                } else {
                    rvTopPlayed.visibility = View.VISIBLE
                    llAccountTopPlayedEmpty.visibility = View.GONE
                }
            }
        }
    }

    private fun playTopEntry(entry: PlayCountStore.PlayCountEntry) {
        if (!isAdded) return
        SongPlayerLauncher.open(
            activity,
            arrayListOf(entry.videoId), arrayListOf(entry.title), arrayListOf(entry.artist),
            arrayListOf(""), arrayListOf(entry.imageUrl),
            0,
            /* startPlaying = */ true,
            "module_settings",
            /* openPlayerUi = */ false,
            /* fromStart = */ true
        )

        // Fetch radio for this song and append to queue
        fetchRadioForTopEntry(entry)
    }

    private fun fetchRadioForTopEntry(entry: PlayCountStore.PlayCountEntry) {
        val cookie = StreamResolver.getAuthCookieHeader()
        if (cookie.isNullOrEmpty()) return
        val radioPlaylistId = "RDAMVM${entry.videoId}"
        val service = YouTubeMusicService()
        service.fetchMixTracks(cookie, radioPlaylistId, object : YouTubeMusicService.MixTracksCallback {
            override fun onSuccess(tracks: List<YouTubeMusicService.TrackResult>) {
                if (!isAdded || tracks.isEmpty()) return
                val qIds = ArrayList<String>()
                val qTitles = ArrayList<String>()
                val qArtists = ArrayList<String>()
                val qDurations = ArrayList<String>()
                val qImages = ArrayList<String>()
                // Include the seed track first
                qIds.add(entry.videoId)
                qTitles.add(entry.title)
                qArtists.add(entry.artist)
                qDurations.add("")
                qImages.add(entry.imageUrl)
                for (t in tracks) {
                    if (t.videoId.isNullOrEmpty() || t.videoId == entry.videoId) continue
                    qIds.add(t.videoId)
                    qTitles.add(t.title ?: "")
                    qArtists.add(t.subtitle ?: "")
                    qDurations.add("")
                    qImages.add(t.thumbnailUrl ?: "")
                }
                val fm = parentFragmentManager
                val sp = fm.findFragmentByTag(AppConstants.TAG_SONG_PLAYER) as? SongPlayerFragment
                if (sp != null && sp.isAdded) {
                    sp.externalReplaceQueue(qIds, qTitles, qArtists, qDurations, qImages, 0, true)
                }
            }
            override fun onError(error: String) {}
        })
    }

    // --- Account actions ---

    private fun performSignOut() {
        (activity as? MainActivity)?.pauseActiveMediaAndDownloadsForSessionChange()
        authManager.signOut(requireContext()) { _, _ ->
            CloudSyncManager.getInstance(requireContext()).onUserSignedOut()
            renderAccountSection()
            (activity as? MainActivity)?.refreshSessionUi()
        }
    }

    private fun showDeleteAccountDataConfirmation() {
        if (!authManager.isSignedIn()) return
        val input = EditText(requireContext()).apply {
            hint = "Escribe \"eliminar\""
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(input)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar cuenta y borrar todo")
            .setMessage("Esta acción eliminará de forma permanente tu cuenta y datos locales.\n\nPara confirmar, escribe \"eliminar\".")
            .setView(container)
            .setPositiveButton("Eliminar cuenta y todo", null)
            .setNegativeButton("Cancelar", null).show()

        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val matches = s?.toString()?.trim()?.lowercase(Locale.ROOT) == DELETE_CONFIRM_WORD
                btn.isEnabled = matches
                input.error = if (!matches && !s.isNullOrEmpty()) "Debes escribir eliminar" else null
            }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        btn.setOnClickListener {
            if (input.text?.toString()?.trim()?.lowercase(Locale.ROOT) == DELETE_CONFIRM_WORD) {
                dialog.dismiss()
                performDeleteAccountAndData()
            }
        }
    }

    private fun performDeleteAccountAndData() {
        if (deleteAccountInFlight || !isAdded) return
        (activity as? MainActivity)?.pauseActiveMediaAndDownloadsForSessionChange()
        val uid = authManager.getCurrentUser()?.uid ?: return
        deleteAccountInFlight = true
        val appContext = requireContext().applicationContext
        val cloudSync = CloudSyncManager.getInstance(appContext)

        cloudSync.deleteUserDataFromCloud(uid) { ok: Boolean, _: String? ->
            if (ok && isAdded) {
                authManager.deleteCurrentUser(requireActivity()) { authOk: Boolean, _: String? ->
                    deleteAccountInFlight = false
                    if (authOk && isAdded) {
                        cloudSync.onUserSignedOut()
                        cloudSync.clearLocalUserDataCompletely()
                        renderAccountSection()
                        (activity as? MainActivity)?.refreshSessionUi()
                    }
                }
            } else { deleteAccountInFlight = false }
        }
    }

    // --- Downloads actions ---

    private fun showDeleteAllDownloadsConfirmation() {
        if (!isAdded || downloadCleanupInFlight) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Borrar descargas")
            .setMessage("Se eliminarán todos los archivos de audio descargados.\n\nEsta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar todo") { _, _ -> performDeleteAllDownloads() }
            .show()
    }

    private fun performDeleteAllDownloads() {
        if (!isAdded || downloadCleanupInFlight) return
        downloadCleanupInFlight = true
        tvDownloadsDeleteTitle.text = "Eliminando…"
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            OfflineAudioStore.deleteAllOfflineAudio(appContext)
            val cachePrefs = appContext.getSharedPreferences(CloudSyncManager.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val editor = cachePrefs.edit()
            for (key in cachePrefs.all.keys) {
                if (key.startsWith("playlist_offline_complete_") || key.startsWith("playlist_offline_auto_")) editor.remove(key)
            }
            editor.apply()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                downloadCleanupInFlight = false
                tvDownloadsDeleteTitle.text = "Borrar descargas"
                AppSnackbar.show(activity, "Todas las descargas eliminadas")
                renderDownloadsSection()
                (activity as? MainActivity)?.onAllDownloadsDeleted()
            }
        }
    }

    // --- Update section ---

    private fun setupUpdateSection(view: View) {
        tvUpdateInstalledVersion = view.findViewById(R.id.tvUpdateInstalledVersion)
        tvUpdateStatus = view.findViewById(R.id.tvUpdateStatus)
        llUpdateCard = view.findViewById(R.id.llUpdateCard)
        tvUpdateNewVersion = view.findViewById(R.id.tvUpdateNewVersion)
        tvUpdateVersionPill = view.findViewById(R.id.tvUpdateVersionPill)
        tvUpdateNotes = view.findViewById(R.id.tvUpdateNotes)
        llUpdateProgress = view.findViewById(R.id.llUpdateProgress)
        pbUpdateDownload = view.findViewById(R.id.pbUpdateDownload)
        tvUpdatePercent = view.findViewById(R.id.tvUpdatePercent)
        btnUpdateAction = view.findViewById(R.id.btnUpdateAction)

        tvUpdateInstalledVersion.text = "Versión instalada ${BuildConfig.VERSION_NAME}"
        btnUpdateAction.setOnClickListener { onUpdateActionTapped() }
    }

    private fun renderUpdateSection() {
        if (!isAdded) return

        // Descarga viva (posible recreación del fragment por rotación/tema): reconstruir la card
        // de "descargando" y RE-ENGANCHAR los callbacks a esta instancia para que el % avance.
        if (AppUpdateManager.isDownloadInFlight()) {
            val u = AppUpdateManager.getInProgressUpdate() ?: return
            availableUpdate = u
            showAvailableUpdate(u)
            enterDownloadingUi(AppUpdateManager.getLastProgress())
            AppUpdateManager.reattach(::onUpdateProgress, ::onUpdateInstallStarted, ::onUpdateDownloadError)
            return
        }

        val update = availableUpdate
        if (update != null) {
            // Ya sabemos que hay versión nueva (vino del popup o de un chequeo previo).
            showAvailableUpdate(update)
            if (autoStartDownloadOnOpen) {
                autoStartDownloadOnOpen = false
                startUpdateDownload(update)
            }
            return
        }
        // Sin datos previos: buscamos SOLOS al abrir la sección, así el usuario no adivina.
        startUpdateCheck()
    }

    private fun onUpdateActionTapped() {
        val update = availableUpdate
        if (update == null) startUpdateCheck() else startUpdateDownload(update)
    }

    /** Deja el botón listo para reintentar tras volver del instalador (sin re-buscar). */
    private fun restoreUpdateActionButton() {
        if (!isAdded || availableUpdate == null) return
        llUpdateProgress.visibility = View.GONE
        btnUpdateAction.visibility = View.VISIBLE
        btnUpdateAction.isEnabled = true
        btnUpdateAction.text = "Actualizar"
        styleUpdateButton(prominent = true)
    }

    /**
     * "Actualizar" (hay descarga real) = botón prominente (accent, ancho completo).
     * Buscar de nuevo / Reintentar = botón sutil, chico y centrado (acción secundaria).
     */
    private fun styleUpdateButton(prominent: Boolean) {
        if (!isAdded) return
        val lp = btnUpdateAction.layoutParams as? LinearLayout.LayoutParams ?: return
        val hPad = dp(22)
        if (prominent) {
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = dp(48)
            lp.gravity = android.view.Gravity.NO_GRAVITY
            btnUpdateAction.layoutParams = lp
            btnUpdateAction.setBackgroundResource(R.drawable.bg_update_button)
            btnUpdateAction.setTextColor(0xFF000000.toInt())
        } else {
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.height = dp(40)
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            btnUpdateAction.layoutParams = lp
            btnUpdateAction.setBackgroundResource(R.drawable.bg_update_button_subtle)
            btnUpdateAction.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        }
        btnUpdateAction.setPadding(hPad, 0, hPad, 0)
    }

    private fun startUpdateCheck() {
        if (updateCheckInFlight || !isAdded) return
        updateCheckInFlight = true
        // Estado "buscando": ocultamos card y botón, dejamos solo el mensaje.
        llUpdateCard.visibility = View.GONE
        tvUpdateStatus.visibility = View.VISIBLE
        tvUpdateStatus.text = "Buscando actualización…"
        btnUpdateAction.visibility = View.GONE

        AppUpdateManager.checkForUpdate(requireContext().applicationContext) { update, error ->
            updateCheckInFlight = false
            if (!isAdded) return@checkForUpdate
            btnUpdateAction.visibility = View.VISIBLE
            btnUpdateAction.isEnabled = true
            when {
                error != null -> {
                    tvUpdateStatus.visibility = View.VISIBLE
                    tvUpdateStatus.text = "No se pudo conectar con el servidor de actualizaciones.\nRevisa tu conexión e inténtalo de nuevo."
                    llUpdateCard.visibility = View.GONE
                    btnUpdateAction.text = "Reintentar"
                    styleUpdateButton(prominent = false)
                }
                update == null -> {
                    availableUpdate = null // por si había una card previa ya instalada
                    tvUpdateStatus.visibility = View.VISIBLE
                    tvUpdateStatus.text = "Tienes la última versión ✓"
                    llUpdateCard.visibility = View.GONE
                    btnUpdateAction.text = "Buscar de nuevo"
                    styleUpdateButton(prominent = false)
                }
                else -> {
                    availableUpdate = update
                    showAvailableUpdate(update)
                }
            }
        }
    }

    private fun showAvailableUpdate(update: AppUpdateManager.UpdateInfo) {
        tvUpdateStatus.visibility = View.GONE
        // "🔥 Nueva versión" es fijo (XML); la versión va en la pastilla de la derecha.
        tvUpdateVersionPill.text = update.versionName
        // Mismo formateo que la vista previa del panel (viñetas, guion opcional).
        tvUpdateNotes.text = AppUpdateManager.formatNotesAsBullets(update.notes)
        llUpdateCard.visibility = View.VISIBLE
        llUpdateProgress.visibility = View.GONE
        btnUpdateAction.visibility = View.VISIBLE
        btnUpdateAction.text = "Actualizar"
        btnUpdateAction.isEnabled = true
        styleUpdateButton(prominent = true)
    }

    private fun startUpdateDownload(update: AppUpdateManager.UpdateInfo) {
        if (!isAdded || AppUpdateManager.isDownloadInFlight()) return
        val ctx = requireContext()

        // Sin el permiso de "instalar apps desconocidas" el instalador no abre: pedirlo primero.
        if (!AppUpdateManager.canInstallUnknownApps(ctx)) {
            AppSnackbar.show(activity, "Permite instalar apps de Sleppify y vuelve a tocar Actualizar")
            try {
                startActivity(AppUpdateManager.buildUnknownSourcesIntent(ctx))
            } catch (_: Exception) {
            }
            return
        }

        enterDownloadingUi(0)
        AppUpdateManager.downloadAndInstall(
            ctx.applicationContext, update,
            onProgress = ::onUpdateProgress,
            onInstallStarted = ::onUpdateInstallStarted,
            onError = ::onUpdateDownloadError
        )
    }

    /** Card en modo "descargando": barra visible + botón prominente deshabilitado. */
    private fun enterDownloadingUi(initialPercent: Int) {
        if (!isAdded) return
        llUpdateProgress.visibility = View.VISIBLE
        pbUpdateDownload.progress = initialPercent
        tvUpdatePercent.text = "$initialPercent%"
        btnUpdateAction.visibility = View.VISIBLE
        styleUpdateButton(prominent = true)
        btnUpdateAction.isEnabled = false
        btnUpdateAction.text = "Descargando…"
    }

    // Callbacks de descarga compartidos por startUpdateDownload y por reattach (tras recrearse).
    private fun onUpdateProgress(percent: Int) {
        if (!isAdded) return
        pbUpdateDownload.progress = percent
        tvUpdatePercent.text = "$percent%"
    }

    private fun onUpdateInstallStarted() {
        if (isAdded) btnUpdateAction.text = "Instalando…"
    }

    private fun onUpdateDownloadError(message: String) {
        if (!isAdded) return
        llUpdateProgress.visibility = View.GONE
        btnUpdateAction.visibility = View.VISIBLE
        btnUpdateAction.isEnabled = true
        btnUpdateAction.text = "Actualizar"
        styleUpdateButton(prominent = true)
        AppSnackbar.show(activity, "Error al descargar: $message")
    }

    // --- Helpers ---

    private fun formatSize(b: Long): String {
        val mb = b / (1024.0 * 1024.0)
        return if (mb < 1024.0) "%.0f MB".format(mb) else "%.2f GB".format(mb / 1024.0)
    }

    /** % de la barra: uso de la app relativo al volumen; nunca 0 si hay algo ocupado. */
    private fun appUsagePercent(used: Long, total: Long): Int {
        if (total <= 0L || used <= 0L) return 0
        val percent = ((used * 100.0) / total).toInt().coerceIn(0, 100)
        return if (percent == 0) 1 else percent
    }

    private fun calculateSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length().coerceAtLeast(0L)
        return f.listFiles()?.sumOf { calculateSize(it) } ?: 0L
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- History Adapter ---

    private class HistoryAdapter(
        private val onClick: (ListenHistoryStore.HistoryEntry) -> Unit,
        private val onMore: (ListenHistoryStore.HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ENTRY = 1
            private const val TYPE_SHOW_MORE = 2
            private const val DAY_VISIBLE_LIMIT = HISTORY_DAY_VISIBLE_LIMIT
        }

        private sealed class Item {
            data class DayHeader(val label: String, val dayKey: String) : Item()
            data class Entry(val entry: ListenHistoryStore.HistoryEntry) : Item()
            data class ShowMore(val dayKey: String, val remaining: Int) : Item()
        }

        // All entries grouped by day key
        private val allEntriesByDay = LinkedHashMap<String, MutableList<ListenHistoryStore.HistoryEntry>>()
        // How many entries are currently visible per day
        private val visibleCountByDay = HashMap<String, Int>()
        private val items = mutableListOf<Item>()
        private val dayFormat = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es"))
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun setItems(entries: List<ListenHistoryStore.HistoryEntry>) {
            allEntriesByDay.clear()
            visibleCountByDay.clear()
            appendToStorage(entries)
            rebuildItems()
            notifyDataSetChanged()
        }

        fun appendItems(entries: List<ListenHistoryStore.HistoryEntry>) {
            if (entries.isEmpty()) return
            appendToStorage(entries)
            rebuildItems()
            notifyDataSetChanged()
        }

        fun expandDay(dayKey: String) {
            val current = visibleCountByDay[dayKey] ?: DAY_VISIBLE_LIMIT
            visibleCountByDay[dayKey] = current + DAY_VISIBLE_LIMIT
            rebuildItems()
            notifyDataSetChanged()
        }

        private fun appendToStorage(entries: List<ListenHistoryStore.HistoryEntry>) {
            for (e in entries) {
                val dKey = dayKey(e.timestampMs)
                allEntriesByDay.getOrPut(dKey) { mutableListOf() }.add(e)
            }
        }

        private fun rebuildItems() {
            items.clear()
            for ((dKey, dayEntries) in allEntriesByDay) {
                if (dayEntries.isEmpty()) continue
                val label = dayFormat.format(Date(dayEntries[0].timestampMs)).replaceFirstChar { it.uppercase() }
                items.add(Item.DayHeader(label, dKey))
                val limit = visibleCountByDay[dKey] ?: DAY_VISIBLE_LIMIT
                val visible = dayEntries.take(limit)
                for (e in visible) items.add(Item.Entry(e))
                val remaining = dayEntries.size - visible.size
                if (remaining > 0) {
                    items.add(Item.ShowMore(dKey, remaining))
                }
            }
        }

        private fun dayKey(ms: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}-${c.get(Calendar.DAY_OF_MONTH)}"
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.DayHeader -> TYPE_HEADER
            is Item.Entry -> TYPE_ENTRY
            is Item.ShowMore -> TYPE_SHOW_MORE
        }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_history_day_header, parent, false))
                TYPE_SHOW_MORE -> ShowMoreVH(TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                    setTextColor(ContextCompat.getColor(parent.context, R.color.stitch_blue))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val ta = parent.context.obtainStyledAttributes(intArrayOf(it))
                        val resId = ta.getResourceId(0, 0)
                        ta.recycle()
                        resId
                    })
                })
                else -> EntryVH(inflater.inflate(R.layout.item_history_entry, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.DayHeader -> (holder as HeaderVH).bind(item.label)
                is Item.Entry -> (holder as EntryVH).bind(item.entry)
                is Item.ShowMore -> (holder as ShowMoreVH).bind(item)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv = v.findViewById<TextView>(R.id.tvHistoryDayHeader)
            fun bind(label: String) { tv.text = label }
        }

        inner class ShowMoreVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv = v as TextView
            fun bind(item: Item.ShowMore) {
                tv.text = "Ver más (${item.remaining})"
                tv.setOnClickListener { expandDay(item.dayKey) }
            }
        }

        inner class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
            private val ivThumb = v.findViewById<ImageView>(R.id.ivHistoryThumb)
            private val tvTitle = v.findViewById<TextView>(R.id.tvHistoryTitle)
            private val tvArtist = v.findViewById<TextView>(R.id.tvHistoryArtist)
            private val tvTime = v.findViewById<TextView>(R.id.tvHistoryTime)
            private val btnMore = v.findViewById<ImageView>(R.id.btnHistoryMore)
            fun bind(entry: ListenHistoryStore.HistoryEntry) {
                tvTitle.text = entry.title
                tvArtist.text = entry.artist
                tvTime.text = timeFormat.format(Date(entry.timestampMs))
                if (LocalFilesStore.isLocalVideoId(entry.videoId)) {
                    LocalArtworkResolver.loadInto(ivThumb, entry.videoId)
                } else if (entry.imageUrl.isNotEmpty()) {
                    LocalArtworkResolver.detach(ivThumb)
                    Glide.with(ivThumb).load(entry.imageUrl).centerCrop().placeholder(R.color.surface_high).into(ivThumb)
                } else {
                    LocalArtworkResolver.detach(ivThumb)
                    ivThumb.setImageDrawable(ColorDrawable(ContextCompat.getColor(ivThumb.context, R.color.surface_high)))
                }
                itemView.setOnClickListener { onClick(entry) }
                itemView.setOnLongClickListener { onMore(entry); true }
                btnMore.setOnClickListener { onMore(entry) }
            }
        }
    }

    // --- Top Played Adapter ---

    private class TopPlayedAdapter(
        private val entries: List<PlayCountStore.PlayCountEntry>,
        private val onClick: (PlayCountStore.PlayCountEntry) -> Unit
    ) : RecyclerView.Adapter<TopPlayedAdapter.VH>() {

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_top_played_carousel, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(entries[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val ivThumb = v.findViewById<ImageView>(R.id.ivTopPlayedThumb)
            private val tvTitle = v.findViewById<TextView>(R.id.tvTopPlayedTitle)
            fun bind(entry: PlayCountStore.PlayCountEntry) {
                tvTitle.text = entry.title
                if (LocalFilesStore.isLocalVideoId(entry.videoId)) {
                    LocalArtworkResolver.loadInto(ivThumb, entry.videoId)
                } else if (entry.imageUrl.isNotEmpty()) {
                    LocalArtworkResolver.detach(ivThumb)
                    Glide.with(ivThumb).load(entry.imageUrl).centerCrop().placeholder(R.color.surface_high).into(ivThumb)
                } else {
                    LocalArtworkResolver.detach(ivThumb)
                    ivThumb.setImageDrawable(ColorDrawable(ContextCompat.getColor(ivThumb.context, R.color.surface_high)))
                }
                itemView.setOnClickListener { onClick(entry) }
            }
        }
    }
}
