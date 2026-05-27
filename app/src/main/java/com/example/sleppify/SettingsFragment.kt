package com.example.sleppify

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.*

/**
 * Optimized Kotlin version of SettingsFragment.
 * Manages app configuration, profile state, and storage analytics.
 * Powered by Coroutines for non-blocking storage operations.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val DELETE_CONFIRM_WORD = "eliminar"
    }

    private lateinit var cardProfile: MaterialCardView
    private lateinit var ivProfilePhoto: ShapeableImageView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileBadge: TextView
    private lateinit var ivYoutubeMusicStatus: ImageView
    private lateinit var swDownloadOnMobileData: MaterialSwitch
    private lateinit var swOfflineMode: MaterialSwitch
    private lateinit var swGaplessPlayback: MaterialSwitch
    private lateinit var swDownloadCanvas: MaterialSwitch
    private lateinit var rowDownloadQuality: View
    private lateinit var tvDownloadQualityValue: TextView
    private lateinit var rowStreamingQuality: View
    private lateinit var rowOpenEqualizer: View
    private lateinit var tvStreamingQualityValue: TextView
    private lateinit var tvOfflineCrossfadeValue: TextView
    private lateinit var tvStorageOtherAppsValue: TextView
    private lateinit var tvStorageDownloadsValue: TextView
    private lateinit var tvStorageCacheValue: TextView
    private lateinit var tvStorageFreeValue: TextView
    private lateinit var sbOfflineCrossfade: SeekBar
    private lateinit var vStorageOtherApps: View
    private lateinit var vStorageDownloads: View
    private lateinit var vStorageCache: View
    private lateinit var vStorageFree: View
    private lateinit var rowDeleteCache: View
    private lateinit var tvDeleteCacheTitle: TextView
    private lateinit var tvDeleteCacheSubtitle: TextView
    private lateinit var rowDeleteDownloads: View
    private lateinit var tvDeleteDownloadsTitle: TextView
    private lateinit var tvDeleteDownloadsSubtitle: TextView
    private lateinit var swLocalFiles: MaterialSwitch

    private val settingsPrefs: SharedPreferences by lazy { 
        requireContext().getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE) 
    }
    
    private val localPrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("sleppify_local_config", Context.MODE_PRIVATE)
    }
    
    private val authManager: AuthManager by lazy { AuthManager.getInstance(requireContext()) }
    
    private val audioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LocalFilesStore.setEnabled(requireContext(), true)
            scanAndCacheLocalFiles()
            renderLocalFilesSwitch()
        } else {
            LocalFilesStore.setEnabled(requireContext(), false)
            renderLocalFilesSwitch()
            Toast.makeText(requireContext(), "Se necesita permiso para acceder a la música", Toast.LENGTH_SHORT).show()
        }
    }

    private val webSessionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val cookieHeader = data.getStringExtra(YouTubeMusicWebSessionActivity.EXTRA_SESSION_COOKIE_HEADER)
                if (!TextUtils.isEmpty(cookieHeader)) {
                    requireContext().getSharedPreferences("player_state", Context.MODE_PRIVATE)
                        .edit()
                        .putString("stream_last_youtube_web_cookie", cookieHeader)
                        .apply()
                    InnertubeResolver.setAuthCookies(cookieHeader)
                    Toast.makeText(requireContext(), "Cuenta de YouTube Music actualizada", Toast.LENGTH_SHORT).show()
                    renderProfileState()
                }
            }
        }
    }

    private var deleteAccountInFlight = false
    private var storageCleanupInFlight = false
    private var downloadCleanupInFlight = false
    private var hasSettingsSnapshot = false
    private var hasProfileSnapshot = false

    // State Snapshots
    private var lastOfflineCrossfadeSeconds = -1
    private var lastAllowMobileDataDownloads = false
    private var lastOfflineModeEnabled = false
    private var lastDownloadQuality: String? = null
    private var lastDownloadCanvasEnabled = false
    private var lastStreamingQuality: String? = null
    private var lastProfileSignedIn = false
    private var lastProfileDisplayName: String? = null
    private var lastProfileEmail: String? = null
    private var lastProfilePhotoUrl: String? = null

    fun refreshOfflineStateFromPrefs() {
        hasSettingsSnapshot = false
        renderSettingsState()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Own toolbar: back button + camera + status bar inset
        val llSettingsToolbar = view.findViewById<View>(R.id.llSettingsToolbar)
        view.findViewById<View>(R.id.btnSettingsBack)?.setOnClickListener {
            (activity as? MainActivity)?.returnFromSettings()
        }
        view.findViewById<View>(R.id.btnSettingsCamera)?.setOnClickListener {
            (activity as? MainActivity)?.openScannerFromSettings()
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            llSettingsToolbar?.setPadding(llSettingsToolbar.paddingLeft, top, llSettingsToolbar.paddingRight, llSettingsToolbar.paddingBottom)
            insets
        }

        initViews(view)
        setupInteractions()
        renderSettingsState()
        renderProfileState()
        refreshStorageBreakdownAsync()
    }

    private fun initViews(v: View) {
        cardProfile = v.findViewById(R.id.cardProfile)
        ivProfilePhoto = v.findViewById(R.id.ivProfilePhoto)
        tvProfileName = v.findViewById(R.id.tvProfileName)
        tvProfileBadge = v.findViewById(R.id.tvProfileBadge)
        ivYoutubeMusicStatus = v.findViewById(R.id.ivYoutubeMusicStatus)
        swDownloadOnMobileData = v.findViewById(R.id.swDownloadOnMobileData)
        swOfflineMode = v.findViewById(R.id.swOfflineMode)
        swGaplessPlayback = v.findViewById(R.id.swGaplessPlayback)
        swDownloadCanvas = v.findViewById(R.id.swDownloadCanvas)
        swLocalFiles = v.findViewById(R.id.swLocalFiles)
        rowDownloadQuality = v.findViewById(R.id.rowDownloadQuality)
        rowStreamingQuality = v.findViewById(R.id.rowStreamingQuality)
        rowOpenEqualizer = v.findViewById(R.id.rowOpenEqualizer)
        tvStreamingQualityValue = v.findViewById(R.id.tvStreamingQualityValue)
        tvDownloadQualityValue = v.findViewById(R.id.tvDownloadQualityValue)
        tvOfflineCrossfadeValue = v.findViewById(R.id.tvOfflineCrossfadeValue)
        sbOfflineCrossfade = v.findViewById(R.id.sbOfflineCrossfade)
        tvStorageOtherAppsValue = v.findViewById(R.id.tvStorageOtherAppsValue)
        tvStorageDownloadsValue = v.findViewById(R.id.tvStorageDownloadsValue)
        tvStorageCacheValue = v.findViewById(R.id.tvStorageCacheValue)
        tvStorageFreeValue = v.findViewById(R.id.tvStorageFreeValue)
        vStorageOtherApps = v.findViewById(R.id.vStorageOtherApps)
        vStorageDownloads = v.findViewById(R.id.vStorageDownloads)
        vStorageCache = v.findViewById(R.id.vStorageCache)
        vStorageFree = v.findViewById(R.id.vStorageFree)
        rowDeleteCache = v.findViewById(R.id.rowDeleteCache)
        tvDeleteCacheTitle = v.findViewById(R.id.tvDeleteCacheTitle)
        tvDeleteCacheSubtitle = v.findViewById(R.id.tvDeleteCacheSubtitle)
        rowDeleteDownloads = v.findViewById(R.id.rowDeleteDownloads)
        tvDeleteDownloadsTitle = v.findViewById(R.id.tvDeleteDownloadsTitle)
        tvDeleteDownloadsSubtitle = v.findViewById(R.id.tvDeleteDownloadsSubtitle)
    }

    private fun setupInteractions() {
        cardProfile.setOnClickListener {
            if (authManager.isSignedIn()) showAccountActionsDialog()
            else (activity as? MainActivity)?.requireAuth({ renderProfileState() }, { renderProfileState() })
        }
        
        rowDownloadQuality.setOnClickListener { showDownloadQualityPicker() }
        rowStreamingQuality.setOnClickListener { showStreamingQualityPicker() }
        rowOpenEqualizer.setOnClickListener { (activity as? MainActivity)?.openEqualizerFromSettings() }
        rowDeleteCache.setOnClickListener { showDeleteCacheConfirmation() }
        rowDeleteDownloads.setOnClickListener { showDeleteAllDownloadsConfirmation() }
        updateDeleteCacheButtonState()
        updateDeleteDownloadsButtonState()
        renderLocalFilesSwitch()
    }

    private fun renderLocalFilesSwitch() {
        val enabled = LocalFilesStore.isEnabled(requireContext())
        swLocalFiles.setOnCheckedChangeListener(null)
        swLocalFiles.isChecked = enabled
        swLocalFiles.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                requestAudioPermissionAndEnable()
            } else {
                LocalFilesStore.setEnabled(requireContext(), false)
                (activity as? MainActivity)?.refreshMusicLibrary()
            }
        }
    }

    private fun requestAudioPermissionAndEnable() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        audioPermissionLauncher.launch(permission)
    }

    private fun scanAndCacheLocalFiles() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val tracks = LocalFilesStore.scanLocalFiles(requireContext())
            LocalFilesStore.cacheFiles(requireContext(), tracks)
            launch(Dispatchers.Main) {
                (activity as? MainActivity)?.refreshMusicLibrary()
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.findViewById<ScrollView>(R.id.svSettingsContent)?.scrollTo(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        renderSettingsState()
        renderProfileState()
        refreshStorageBreakdownAsync()
    }

    private fun showDeleteCacheConfirmation() {
        if (!isAdded || storageCleanupInFlight) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_storage_delete_cache_title)
            .setMessage(R.string.settings_storage_delete_cache_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_storage_delete_cache_confirm) { _, _ -> clearSettingsCacheAsync() }
            .show()
    }

    private fun clearSettingsCacheAsync() {
        if (!isAdded || storageCleanupInFlight) return
        storageCleanupInFlight = true
        updateDeleteCacheButtonState()

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val freed = clearAppCache(appContext)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                storageCleanupInFlight = false
                updateDeleteCacheButtonState()
                val msg = if (freed >= 0) R.string.settings_storage_delete_cache_done else R.string.settings_storage_delete_cache_error
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                refreshStorageBreakdownAsync()
            }
        }
    }

    private suspend fun clearAppCache(context: Context): Long {
        return try {
            var deleted = 0L
            collectAppCacheRoots(context).forEach { deleted += deleteRecursively(it) }
            try { 
                Glide.get(context).clearDiskCache()
                withContext(Dispatchers.Main) { Glide.get(context).clearMemory() }
            } catch (e: Exception) {}
            deleted.coerceAtLeast(0L)
        } catch (e: Exception) { -1L }
    }

    private fun refreshStorageBreakdownAsync() {
        if (!isAdded) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val breakdown = calculateStorageBreakdown(appContext)
            withContext(Dispatchers.Main) {
                if (isAdded) renderStorageBreakdown(breakdown)
            }
        }
    }

    private fun renderStorageBreakdown(b: StorageBreakdown?) {
        if (b == null || !isAdded) return
        
        tvStorageOtherAppsValue.text = getString(R.string.settings_storage_row_value_format, getString(R.string.settings_storage_other_apps), formatSize(b.otherAppsBytes))
        tvStorageDownloadsValue.text = getString(R.string.settings_storage_row_value_format, getString(R.string.settings_storage_downloads), formatSize(b.downloadsBytes))
        tvStorageCacheValue.text = getString(R.string.settings_storage_row_value_format, getString(R.string.settings_storage_cache), formatSize(b.cacheBytes))
        tvStorageFreeValue.text = getString(R.string.settings_storage_row_value_format, getString(R.string.settings_storage_free), formatSize(b.freeBytes))

        val safeTotal = b.totalBytes.coerceAtLeast(1L)
        
        applySegmentWeight(vStorageOtherApps, b.otherAppsBytes, safeTotal)
        applySegmentWeight(vStorageDownloads, b.downloadsBytes, safeTotal)
        applySegmentWeight(vStorageCache, b.cacheBytes, safeTotal)
        applySegmentWeight(vStorageFree, b.freeBytes, safeTotal)
    }

    private fun applySegmentWeight(v: View, bytes: Long, total: Long) {
        val params = v.layoutParams as? LinearLayout.LayoutParams ?: return
        if (bytes <= 0L) {
            params.weight = 0f
            v.visibility = View.GONE
        } else {
            params.weight = (bytes / total.toFloat()).coerceAtLeast(0.025f)
            v.visibility = View.VISIBLE
        }
        v.layoutParams = params
    }

    private fun calculateStorageBreakdown(context: Context): StorageBreakdown? {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free

            val downloads = collectDownloadRoots(context).sumOf { calculateSize(it) }
            val cache = collectAppCacheRoots(context).sumOf { calculateSize(it) }
            val persistent = collectAppPersistentRoots(context).sumOf { calculateSize(it) }
            
            val appPersistentOnly = (persistent - downloads).coerceAtLeast(0L)
            val otherApps = (used - downloads - cache - appPersistentOnly).coerceAtLeast(0L)

            StorageBreakdown(total, free, downloads, cache, otherApps, appPersistentOnly)
        } catch (e: Exception) { null }
    }

    private fun collectDownloadRoots(context: Context) = mutableListOf<File>().apply {
        addDistinct(OfflineAudioStore.getOfflineAudioDir(context))
        context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { addDistinct(File(it, "offline_audio")) }
    }

    private fun collectAppCacheRoots(context: Context) = mutableListOf<File>().apply {
        addDistinct(context.cacheDir)
        addDistinct(context.codeCacheDir)
        context.getExternalCacheDirs()?.filterNotNull()?.forEach { addDistinct(it) }
    }

    private fun collectAppPersistentRoots(context: Context) = mutableListOf<File>().apply {
        addDistinct(context.filesDir)
        addDistinct(context.noBackupFilesDir)
        addDistinct(File(context.applicationInfo.dataDir, "databases"))
        addDistinct(File(context.applicationInfo.dataDir, "shared_prefs"))
        context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { addDistinct(it) }
        addDistinct(context.obbDir)
    }

    private fun MutableList<File>.addDistinct(f: File?) {
        val path = try { f?.canonicalPath } catch (e: IOException) { f?.absolutePath } ?: return
        if (none { try { it.canonicalPath == path } catch (e: IOException) { it.absolutePath == path } }) {
            add(f!!)
        }
    }

    private fun calculateSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length().coerceAtLeast(0L)
        return f.listFiles()?.sumOf { calculateSize(it) } ?: 0L
    }

    private fun deleteRecursively(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) {
            val s = f.length().coerceAtLeast(0L)
            return if (f.delete()) s else 0L
        }
        val deleted = f.listFiles()?.sumOf { deleteRecursively(it) } ?: 0L
        f.delete()
        return deleted
    }

    private fun formatSize(b: Long): String {
        val mb = b / (1024.0 * 1024.0)
        return if (mb < 1024.0) "%.0f MB".format(mb) else "%.2f GB".format(mb / 1024.0)
    }

    private fun updateDeleteCacheButtonState() {
        rowDeleteCache.isEnabled = !storageCleanupInFlight
        rowDeleteCache.alpha = if (storageCleanupInFlight) 0.5f else 1f
        tvDeleteCacheTitle.text = if (storageCleanupInFlight) "Limpiando cache…" else "Eliminar cache"
    }

    private fun updateDeleteDownloadsButtonState() {
        rowDeleteDownloads.isEnabled = !downloadCleanupInFlight
        rowDeleteDownloads.alpha = if (downloadCleanupInFlight) 0.5f else 1f
        tvDeleteDownloadsTitle.text = if (downloadCleanupInFlight) "Eliminando…" else "Eliminar descargas"
    }

    private fun showDeleteAllDownloadsConfirmation() {
        if (!isAdded || downloadCleanupInFlight) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar todas las descargas")
            .setMessage("Se eliminarán todos los archivos de audio descargados y se restablecerá el estado offline de todas las playlists.\n\nEsta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar todo") { _, _ -> performDeleteAllDownloads() }
            .show()
    }

    private fun performDeleteAllDownloads() {
        if (!isAdded || downloadCleanupInFlight) return
        downloadCleanupInFlight = true
        updateDeleteDownloadsButtonState()

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            OfflineAudioStore.deleteAllOfflineAudio(appContext)

            // Clear all playlist offline state prefs
            val cachePrefs = appContext.getSharedPreferences(CloudSyncManager.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            val editor = cachePrefs.edit()
            for (key in cachePrefs.all.keys) {
                if (key.startsWith("playlist_offline_complete_") || key.startsWith("playlist_offline_auto_")) {
                    editor.remove(key)
                }
            }
            editor.apply()

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                downloadCleanupInFlight = false
                updateDeleteDownloadsButtonState()
                Toast.makeText(requireContext(), "Todas las descargas eliminadas", Toast.LENGTH_SHORT).show()
                refreshStorageBreakdownAsync()
                (activity as? MainActivity)?.onAllDownloadsDeleted()
            }
        }
    }

    private fun renderSettingsState() {
        val crossfade = settingsPrefs.getInt(CloudSyncManager.KEY_OFFLINE_CROSSFADE_SECONDS, 0).coerceIn(0, 12)
        val mobileDownloads = settingsPrefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
        val offlineMode = settingsPrefs.getBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, false)
        val quality = normalizeQuality(settingsPrefs.getString(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_QUALITY, CloudSyncManager.DOWNLOAD_QUALITY_MEDIUM))
        val canvasEnabled = settingsPrefs.getBoolean(CloudSyncManager.KEY_DOWNLOAD_CANVAS_ENABLED, true)
        val streamingQuality = normalizeStreamingQuality(settingsPrefs.getString(CloudSyncManager.KEY_STREAMING_QUALITY, CloudSyncManager.STREAMING_QUALITY_MEDIUM))
        if (hasSettingsSnapshot &&
            lastOfflineCrossfadeSeconds == crossfade &&
            lastAllowMobileDataDownloads == mobileDownloads && lastOfflineModeEnabled == offlineMode &&
            lastDownloadQuality == quality &&
            lastDownloadCanvasEnabled == canvasEnabled &&
            lastStreamingQuality == streamingQuality) return

        hasSettingsSnapshot = true
        lastOfflineCrossfadeSeconds = crossfade
        lastAllowMobileDataDownloads = mobileDownloads; lastOfflineModeEnabled = offlineMode; lastDownloadQuality = quality
        lastDownloadCanvasEnabled = canvasEnabled
        lastStreamingQuality = streamingQuality

        swDownloadOnMobileData.apply {
            setOnCheckedChangeListener(null)
            isChecked = mobileDownloads
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, c).apply()
                renderSettingsState()
            }
        }

        swOfflineMode.apply {
            setOnCheckedChangeListener(null)
            isChecked = offlineMode
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_OFFLINE_MODE_ENABLED, c).apply()
                (activity as? MainActivity)?.notifyOfflineModeChanged()
                renderSettingsState()
            }
        }

        swGaplessPlayback.apply {
            setOnCheckedChangeListener(null)
            isChecked = settingsPrefs.getBoolean(CloudSyncManager.KEY_GAPLESS_PLAYBACK, true)
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_GAPLESS_PLAYBACK, c).apply()
                renderSettingsState()
            }
        }

        swDownloadCanvas.apply {
            setOnCheckedChangeListener(null)
            isChecked = canvasEnabled
            setOnCheckedChangeListener { _, c ->
                settingsPrefs.edit().putBoolean(CloudSyncManager.KEY_DOWNLOAD_CANVAS_ENABLED, c).apply()
                renderSettingsState()
            }
        }

        // When canvas is enabled, lock download quality (video is fixed 360p)
        rowDownloadQuality.alpha = if (canvasEnabled) 0.4f else 1f
        rowDownloadQuality.isClickable = !canvasEnabled
        rowDownloadQuality.isFocusable = !canvasEnabled

        tvDownloadQualityValue.text = if (canvasEnabled) "Video 360p" else labelForQuality(quality)
        tvStreamingQualityValue.text = labelForStreamingQuality(streamingQuality)

        sbOfflineCrossfade.apply {
            setOnSeekBarChangeListener(null)
            max = 12
            progress = crossfade
            updateIndicator(crossfade)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, p: Int, b: Boolean) = updateIndicator(p)
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    settingsPrefs.edit().putInt(CloudSyncManager.KEY_OFFLINE_CROSSFADE_SECONDS, progress).apply()
                    renderSettingsState()
                }
            })
        }
    }

    private fun updateIndicator(s: Int) {
        tvOfflineCrossfadeValue.text = if (s == 0) "off" else "${s}s"
    }

    private fun showDownloadQualityPicker() {
        val vals = arrayOf(CloudSyncManager.DOWNLOAD_QUALITY_LOW, CloudSyncManager.DOWNLOAD_QUALITY_MEDIUM, CloudSyncManager.DOWNLOAD_QUALITY_HIGH, CloudSyncManager.DOWNLOAD_QUALITY_VERY_HIGH)
        val labs = arrayOf("Baja", "Media", "Alta", "Muy alta")
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_stream_download_quality_title)
            .setItems(labs) { d, w ->
                settingsPrefs.edit().putString(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_QUALITY, vals[w]).apply()
                renderSettingsState()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStreamingQualityPicker() {
        val vals = arrayOf(CloudSyncManager.STREAMING_QUALITY_LOW, CloudSyncManager.STREAMING_QUALITY_MEDIUM, CloudSyncManager.STREAMING_QUALITY_HIGH, CloudSyncManager.STREAMING_QUALITY_VERY_HIGH)
        val labs = arrayOf("Baja", "Media", "Alta", "Muy alta")
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Calidad de streaming")
            .setItems(labs) { d, w ->
                settingsPrefs.edit().putString(CloudSyncManager.KEY_STREAMING_QUALITY, vals[w]).apply()
                renderSettingsState()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun normalizeQuality(q: String?) = when (q) {
        CloudSyncManager.DOWNLOAD_QUALITY_LOW -> q
        CloudSyncManager.DOWNLOAD_QUALITY_HIGH -> q
        CloudSyncManager.DOWNLOAD_QUALITY_VERY_HIGH -> q
        else -> CloudSyncManager.DOWNLOAD_QUALITY_MEDIUM
    }

    private fun normalizeStreamingQuality(q: String?) = when (q) {
        CloudSyncManager.STREAMING_QUALITY_LOW -> q
        CloudSyncManager.STREAMING_QUALITY_HIGH -> q
        CloudSyncManager.STREAMING_QUALITY_VERY_HIGH -> q
        else -> CloudSyncManager.STREAMING_QUALITY_MEDIUM
    }

    private fun labelForQuality(q: String) = when (q) {
        CloudSyncManager.DOWNLOAD_QUALITY_LOW -> "Baja"
        CloudSyncManager.DOWNLOAD_QUALITY_HIGH -> "Alta"
        CloudSyncManager.DOWNLOAD_QUALITY_VERY_HIGH -> "Muy alta"
        else -> "Media"
    }

    private fun labelForStreamingQuality(q: String) = when (q) {
        CloudSyncManager.STREAMING_QUALITY_LOW -> "Baja"
        CloudSyncManager.STREAMING_QUALITY_HIGH -> "Alta"
        CloudSyncManager.STREAMING_QUALITY_VERY_HIGH -> "Muy alta"
        else -> "Media"
    }

    private fun renderProfileState() {
        val signedIn = authManager.isSignedIn()
        val name: String? = if (signedIn) authManager.getDisplayName() else null
        val email: String? = if (signedIn) authManager.getEmail() else null
        val photo: String? = if (signedIn) authManager.getPhotoUrl()?.toString() else null

        if (hasProfileSnapshot && lastProfileSignedIn == signedIn && lastProfileDisplayName == name && lastProfileEmail == email && lastProfilePhotoUrl == photo) return

        hasProfileSnapshot = true
        lastProfileSignedIn = signedIn; lastProfileDisplayName = name; lastProfileEmail = email; lastProfilePhotoUrl = photo

        if (!signedIn) {
            tvProfileName.text = "Sleppify"
            tvProfileBadge.text = "Inicia sesion para sincronizar"
            ivProfilePhoto.setImageDrawable(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.surface_high)))
            ivYoutubeMusicStatus.visibility = View.GONE
            return
        }

        tvProfileName.text = name?.takeIf { it.isNotBlank() } ?: "Usuario"
        tvProfileBadge.text = email?.takeIf { it.isNotBlank() } ?: "Cuenta conectada · toca para cerrar sesion"
        
        val hasYtSession = requireContext().getSharedPreferences("player_state", Context.MODE_PRIVATE)
            .getString("stream_last_youtube_web_cookie", "")?.trim()?.isNotEmpty() == true
        if (hasYtSession) {
            ivYoutubeMusicStatus.visibility = View.VISIBLE
            ivYoutubeMusicStatus.alpha = 1.0f
        } else {
            ivYoutubeMusicStatus.visibility = View.VISIBLE
            ivYoutubeMusicStatus.alpha = 0.4f
        }

        if (!photo.isNullOrEmpty()) {
            Glide.with(this).load(photo).circleCrop()
                .placeholder(R.color.surface_high).error(R.color.surface_high).into(ivProfilePhoto)
        } else {
            ivProfilePhoto.setImageDrawable(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.surface_high)))
        }
    }

    private fun showAccountActionsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_account_actions, null)
        val tvSleppifyName = view.findViewById<TextView>(R.id.tvSleppifyName)
        val tvSleppifyEmail = view.findViewById<TextView>(R.id.tvSleppifyEmail)
        val btnSignOutSleppify = view.findViewById<MaterialButton>(R.id.btnSignOutSleppify)
        val btnDeleteSleppify = view.findViewById<MaterialButton>(R.id.btnDeleteSleppify)
        val tvYtStatus = view.findViewById<TextView>(R.id.tvYtStatus)
        val tvYtInfo = view.findViewById<TextView>(R.id.tvYtInfo)

        tvSleppifyName.text = authManager.getDisplayName()
        tvSleppifyEmail.text = authManager.getEmail()

        val cookie = requireContext().getSharedPreferences("player_state", Context.MODE_PRIVATE)
            .getString("stream_last_youtube_web_cookie", "")?.trim() ?: ""
        if (cookie.isNotEmpty()) {
            tvYtStatus.text = "Conectado"
            tvYtStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            val displayName = authManager.getDisplayName()?.takeIf { it.isNotBlank() }
            tvYtInfo.text = if (displayName != null) displayName else "Vinculada para streaming y descargas"
            tvYtInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            tvYtStatus.text = "No conectado"
            tvYtStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvYtInfo.text = "Vinculada para streaming y descargas"
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .show()

        btnSignOutSleppify.setOnClickListener {
            dialog.dismiss()
            performSignOut()
        }

        btnDeleteSleppify.setOnClickListener {
            dialog.dismiss()
            showDeleteAccountDataConfirmation()
        }
    }

    private fun performSignOut() {
        (activity as? MainActivity)?.pauseActiveMediaAndDownloadsForSessionChange()
        authManager.signOut(requireContext()) { _, _ ->
            CloudSyncManager.getInstance(requireContext()).onUserSignedOut()
            renderProfileState()
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
            .setMessage("Esta accion eliminara de forma permanente tu cuenta y datos locales.\n\nPara confirmar, escribe \"eliminar\".")
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
                        hasProfileSnapshot = false; hasSettingsSnapshot = false
                        renderProfileState(); renderSettingsState()
                        (activity as? MainActivity)?.refreshSessionUi()
                    }
                }
            } else {
                deleteAccountInFlight = false
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class StorageBreakdown(val totalBytes: Long, val freeBytes: Long, val downloadsBytes: Long, val cacheBytes: Long, val otherAppsBytes: Long, val appPersistentBytes: Long)
}
