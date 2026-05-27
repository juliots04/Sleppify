package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.io.File

class CloudSyncManager private constructor(context: Context) {

    fun interface SyncCallback {
        fun onComplete(success: Boolean, message: String?)
    }

    fun interface SyncStateListener {
        fun onSyncStateChanged(syncing: Boolean)
    }

    fun interface CloudPlaylistsCallback {
        fun onResult(playlists: @JvmSuppressWildcards Map<String, List<FavoritesPlaylistStore.FavoriteTrack>>)
    }

    fun interface CloudPlaylistOverridesCallback {
        fun onResult(overrides: @JvmSuppressWildcards Map<String, List<PlaylistOverrideStore.Override>>)
    }

    private val appContext: Context = context.applicationContext
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val eqPrefs: SharedPreferences =
        appContext.getSharedPreferences(AudioEffectsService.PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    private val streamingCachePrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var activeUserId: String? = null

    private var listenersRegistered = false
    private var suppressEqSync = false
    private var suppressSettingsSync = false
    private var suppressStreamingSync = false
    private var initialHydrationInProgress = false

    private val syncStateLock = Any()
    private var activeNetworkSyncCount = 0
    private var syncStateListener: SyncStateListener? = null
    private val syncStuckWatchdogRunnable = Runnable { forceClearSyncStateIfStuck() }

    private var eqListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var streamingListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val eqSyncRunnable = Runnable { uploadEqPreferences() }
    private val settingsSyncRunnable = Runnable { uploadSettingsPreferences() }
    private val streamingSyncRunnable = Runnable { uploadStreamingFavoritesPreferences() }
    private val eqUploadRetryRunnable = Runnable { uploadEqPreferences() }
    private val settingsUploadRetryRunnable = Runnable { uploadSettingsPreferences() }
    private val streamingUploadRetryRunnable = Runnable { uploadStreamingFavoritesPreferences() }

    private var eqUploadRetryAttempt = 0
    private var settingsUploadRetryAttempt = 0
    private var streamingUploadRetryAttempt = 0

    init {
        // Postpone heavy initialization to avoid blocking startup
        Thread {
            ensureEqDefaults()
            ensureSettingsDefaults()
            registerPreferenceListenersIfNeeded()
        }.apply {
            name = "CloudSync-Init"
            isDaemon = true
            start()
        }
    }

    // ----- Defaults helper -----

    private class DefaultsBuilder(private val prefs: SharedPreferences) {
        private var editor: SharedPreferences.Editor? = null
        private fun ed(): SharedPreferences.Editor =
            editor ?: prefs.edit().also { editor = it }

        fun bool(key: String, value: Boolean) {
            if (!prefs.contains(key)) ed().putBoolean(key, value)
        }
        fun int(key: String, value: Int) {
            if (!prefs.contains(key)) ed().putInt(key, value)
        }
        fun string(key: String, value: String) {
            if (!prefs.contains(key)) ed().putString(key, value)
        }
        fun stringSet(key: String, value: Set<String>) {
            if (!prefs.contains(key)) ed().putStringSet(key, value)
        }
        fun commit() {
            editor?.apply()
        }
    }

    private inline fun SharedPreferences.putDefaults(block: DefaultsBuilder.() -> Unit) {
        val b = DefaultsBuilder(this)
        b.block()
        b.commit()
    }

    private fun ensureEqDefaults() {
        eqPrefs.putDefaults {
            bool(AudioEffectsService.KEY_ENABLED, false)
            bool(AudioEffectsService.KEY_SPATIAL_ENABLED, false)
            int(AudioEffectsService.KEY_SPATIAL_STRENGTH, AudioEffectsService.SPATIAL_STRENGTH_DEFAULT)
            int(AudioEffectsService.KEY_REVERB_LEVEL, AudioEffectsService.REVERB_LEVEL_OFF)
        }
    }

    private fun ensureSettingsDefaults() {
        settingsPrefs.putDefaults {
            bool(KEY_AMOLED_MODE_ENABLED, true)
            bool(KEY_PLAYER_VIDEO_MODE_ENABLED, false)
            bool(KEY_PLAYER_SHUFFLE_ENABLED, false)
            int(KEY_PLAYER_REPEAT_MODE, 0)
            int(KEY_NOTIFICATION_LEAD_MINUTES, 15)
            int(KEY_DEFAULT_DURATION_MINUTES, 60)
            string(KEY_OFFLINE_DOWNLOAD_QUALITY, DOWNLOAD_QUALITY_HIGH)
            bool(KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
            bool(KEY_OFFLINE_MODE_ENABLED, false)
        }
    }

    private fun registerPreferenceListenersIfNeeded() {
        if (listenersRegistered) return

        eqListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (suppressEqSync || initialHydrationInProgress || activeUserId.isNullOrEmpty()) {
                return@OnSharedPreferenceChangeListener
            }
            handler.removeCallbacks(eqSyncRunnable)
            handler.postDelayed(eqSyncRunnable, DEBOUNCE_MS)
        }

        settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (suppressSettingsSync || initialHydrationInProgress || activeUserId.isNullOrEmpty()) {
                return@OnSharedPreferenceChangeListener
            }
            handler.removeCallbacks(settingsSyncRunnable)
            handler.postDelayed(settingsSyncRunnable, DEBOUNCE_MS)
        }

        streamingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (suppressStreamingSync || initialHydrationInProgress || activeUserId.isNullOrEmpty()) {
                return@OnSharedPreferenceChangeListener
            }
            if (!isStreamingFavoritesKey(key)) {
                return@OnSharedPreferenceChangeListener
            }
            handler.removeCallbacks(streamingSyncRunnable)
            handler.postDelayed(streamingSyncRunnable, DEBOUNCE_MS)
        }

        eqPrefs.registerOnSharedPreferenceChangeListener(eqListener)
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        streamingCachePrefs.registerOnSharedPreferenceChangeListener(streamingListener)
        listenersRegistered = true
    }

    fun isCloudEnabledForCurrentUser(): Boolean = !activeUserId.isNullOrEmpty()

    fun setSyncStateListener(listener: SyncStateListener?) {
        val syncing: Boolean
        synchronized(syncStateLock) {
            syncStateListener = listener
            syncing = activeNetworkSyncCount > 0
        }
        dispatchSyncState(syncing)
    }

    fun onUserSignedIn(userId: String, callback: SyncCallback) {
        activeUserId = userId
        handler.removeCallbacks(eqSyncRunnable)
        handler.removeCallbacks(settingsSyncRunnable)
        handler.removeCallbacks(streamingSyncRunnable)
        clearUploadRetryCallbacks()
        resetUploadRetryAttempts()
        initialHydrationInProgress = true
        ensureEqDefaults()
        ensureSettingsDefaults()

        startInitialHydrationAttempt(userId, 0, callback)
    }

    fun syncNow(callback: SyncCallback) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) {
            callback.onComplete(false, "No hay sesion activa para sincronizar.")
            return
        }
        syncNow(uid, callback)
    }

    fun syncNow(userId: String, callback: SyncCallback) {
        if (TextUtils.isEmpty(userId)) {
            callback.onComplete(false, "No se pudo resolver el usuario para sincronizar.")
            return
        }

        activeUserId = userId
        handler.removeCallbacks(eqSyncRunnable)
        handler.removeCallbacks(settingsSyncRunnable)
        handler.removeCallbacks(streamingSyncRunnable)
        clearUploadRetryCallbacks()
        resetUploadRetryAttempts()
        initialHydrationInProgress = true
        ensureEqDefaults()
        ensureSettingsDefaults()

        startInitialHydrationAttempt(userId, 0, callback)
    }

    fun syncSettingsNowIfSignedIn() {
        if (activeUserId.isNullOrEmpty() || initialHydrationInProgress) return
        handler.removeCallbacks(settingsSyncRunnable)
        uploadSettingsPreferences()
    }

    private fun startInitialHydrationAttempt(
        userId: String,
        attempt: Int,
        callback: SyncCallback
    ) {
        if (!TextUtils.equals(activeUserId, userId)) return

        val pending = PendingSync(4) { success, message ->
            if (!TextUtils.equals(activeUserId, userId)) return@PendingSync

            if (!success && isRecoverableSyncMessage(message)) {
                val nextAttempt = attempt + 1
                val retryStep = maxOf(1, minOf(nextAttempt, 6)).toLong()
                val delayMs = minOf(
                    INITIAL_HYDRATION_RETRY_MAX_MS,
                    INITIAL_HYDRATION_RETRY_BASE_MS * retryStep
                )
                handler.postDelayed(
                    { startInitialHydrationAttempt(userId, nextAttempt, callback) },
                    delayMs
                )
                return@PendingSync
            }

            initialHydrationInProgress = false
            callback.onComplete(success, message)
        }
        syncEqFromCloudThenLocal(pending)
        syncSettingsFromCloudThenLocal(pending)
        syncStreamingFavoritesFromCloudThenLocal(pending)
        syncPlaylistOverridesFromCloudThenLocal(pending)
    }

    fun onUserSignedOut() {
        activeUserId = null
        initialHydrationInProgress = false
        handler.removeCallbacks(eqSyncRunnable)
        handler.removeCallbacks(settingsSyncRunnable)
        handler.removeCallbacks(streamingSyncRunnable)
        clearUploadRetryCallbacks()
        resetUploadRetryAttempts()
        pauseUserScopedBackgroundWork()
        forceClearSyncState()
    }

    fun pauseUserScopedBackgroundWork() {
        runCatching {
            val manager = WorkManager.getInstance(appContext)
            manager.cancelUniqueWork(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
            manager.cancelAllWorkByTag(OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME)
            manager.cancelUniqueWork(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)
            manager.cancelAllWorkByTag(OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME)
        }
    }

    fun deleteUserDataFromCloud(userId: String, callback: SyncCallback) {
        if (TextUtils.isEmpty(userId)) {
            callback.onComplete(false, "No se encontro usuario para eliminar datos.")
            return
        }

        beginNetworkSync()

        val userDoc = firestore.collection(USERS_COLLECTION).document(userId)
        userDoc.collection(APP_SCOPE_COLLECTION)
            .get(Source.SERVER)
            .addOnSuccessListener { scopeDocs -> deleteScopeDocumentsThenParent(userId, scopeDocs, callback) }
            .addOnFailureListener {
                // Fallback: borra docs conocidos aunque no sea posible listar la coleccion completa.
                val fallbackBatch = firestore.batch()
                fallbackBatch.delete(eqDoc(userId))
                fallbackBatch.delete(settingsDoc(userId))
                fallbackBatch.delete(streamingDoc(userId))
                fallbackBatch.delete(userScope(userId))
                fallbackBatch.commit()
                    .addOnSuccessListener { deleteUserDocBestEffort(userId, callback) }
                    .addOnFailureListener { e ->
                        endNetworkSync()
                        callback.onComplete(false, toUserFacingSyncError(e))
                    }
            }
    }

    private fun deleteScopeDocumentsThenParent(
        userId: String,
        scopeDocs: QuerySnapshot,
        callback: SyncCallback
    ) {
        val batch = firestore.batch()
        var deleteCount = 0

        for (doc in scopeDocs.documents) {
            batch.delete(doc.reference)
            deleteCount++
        }

        if (deleteCount == 0) {
            batch.delete(eqDoc(userId))
            batch.delete(settingsDoc(userId))
            batch.delete(streamingDoc(userId))
            batch.delete(userScope(userId))
        }

        batch.commit()
            .addOnSuccessListener { deleteUserDocBestEffort(userId, callback) }
            .addOnFailureListener { e ->
                endNetworkSync()
                callback.onComplete(false, toUserFacingSyncError(e))
            }
    }

    private fun deleteUserDocBestEffort(userId: String, callback: SyncCallback) {
        val userDoc = firestore.collection(USERS_COLLECTION).document(userId)
        userDoc.delete()
            .addOnSuccessListener {
                endNetworkSync()
                callback.onComplete(true, null)
            }
            .addOnFailureListener { parentDeleteError ->
                if (parentDeleteError is FirebaseFirestoreException &&
                    parentDeleteError.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                ) {
                    endNetworkSync()
                    callback.onComplete(true, null)
                    return@addOnFailureListener
                }
                endNetworkSync()
                callback.onComplete(false, toUserFacingSyncError(parentDeleteError))
            }
    }

    fun clearLocalUserData() {
        try {
            eqPrefs.edit().clear().apply()
            settingsPrefs.edit().clear().apply()
            streamingCachePrefs.edit().clear().apply()
        } finally {
            suppressEqSync = false
            suppressSettingsSync = false
            suppressStreamingSync = false
        }

        ensureEqDefaults()
        ensureSettingsDefaults()
    }

    fun clearLocalUserDataCompletely() {
        onUserSignedOut()

        runCatching { WorkManager.getInstance(appContext).cancelAllWork() }
        runCatching { NotificationManagerCompat.from(appContext).cancelAll() }

        clearAllSharedPreferencesFiles()
        clearAllDatabases()

        clearDirectoryContents(appContext.filesDir)
        clearDirectoryContents(appContext.cacheDir)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clearDirectoryContents(appContext.noBackupFilesDir)
            clearDirectoryContents(appContext.codeCacheDir)
        }

        appContext.getExternalFilesDirs(null)?.forEach { clearDirectoryContents(it) }
        appContext.externalCacheDirs?.forEach { clearDirectoryContents(it) }
    }

    private fun clearAllSharedPreferencesFiles() {
        runCatching {
            val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
            val prefFiles = sharedPrefsDir.listFiles() ?: return@runCatching

            for (prefFile in prefFiles) {
                if (prefFile == null) continue
                val fileName = prefFile.name
                if (TextUtils.isEmpty(fileName) || !fileName.endsWith(".xml")) continue

                val prefName = fileName.substring(0, fileName.length - 4)
                runCatching {
                    appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                }
                runCatching { prefFile.delete() }
            }
        }
    }

    private fun clearAllDatabases() {
        runCatching {
            val databaseNames = appContext.databaseList() ?: return@runCatching
            for (dbName in databaseNames) {
                if (TextUtils.isEmpty(dbName)) continue
                runCatching { appContext.deleteDatabase(dbName) }
            }
        }
    }

    private fun clearDirectoryContents(rootDir: File?) {
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory) return
        val children = rootDir.listFiles() ?: return
        for (child in children) deleteRecursively(child)
    }

    private fun deleteRecursively(node: File?) {
        if (node == null || !node.exists()) return
        if (node.isDirectory) {
            node.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { node.delete() }
    }

    // ----- Cloud-then-local sync generic helper -----

    private inline fun syncBucketFromCloudThenLocal(
        pending: PendingSync,
        crossinline docRef: (String) -> DocumentReference,
        crossinline handle: (DocumentSnapshot, Boolean, Throwable?, PendingSync) -> Unit
    ) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) {
            pending.finish(true, null)
            return
        }

        beginNetworkSync()

        docRef(uid).get(Source.SERVER)
            .addOnSuccessListener { snapshot -> handle(snapshot, true, null, pending) }
            .addOnFailureListener { serverError ->
                docRef(uid).get(Source.CACHE)
                    .addOnSuccessListener { snapshot -> handle(snapshot, false, serverError, pending) }
                    .addOnFailureListener {
                        endNetworkSync()
                        pending.finish(false, toUserFacingSyncError(serverError))
                    }
            }
    }

    private fun syncEqFromCloudThenLocal(pending: PendingSync) =
        syncBucketFromCloudThenLocal(pending, ::eqDoc, ::handleEqSyncSnapshot)

    private fun syncSettingsFromCloudThenLocal(pending: PendingSync) =
        syncBucketFromCloudThenLocal(pending, ::settingsDoc, ::handleSettingsSyncSnapshot)

    private fun syncStreamingFavoritesFromCloudThenLocal(pending: PendingSync) =
        syncBucketFromCloudThenLocal(pending, ::streamingDoc, ::handleStreamingSyncSnapshot)

    private fun syncPlaylistOverridesFromCloudThenLocal(pending: PendingSync) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) {
            pending.finish(true, null)
            return
        }

        beginNetworkSync()

        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLIST_OVERRIDES)
            .get()
            .addOnSuccessListener { documents ->
                for (doc in documents) {
                    val playlistId = doc.id
                    @Suppress("UNCHECKED_CAST")
                    val rawList = doc.get("overrides") as? List<Map<String, Any?>> ?: continue
                    val parsed = ArrayList<PlaylistOverrideStore.Override>(rawList.size)
                    for (m in rawList) {
                        val o = PlaylistOverrideStore.Override.fromMap(m) ?: continue
                        parsed.add(o)
                    }
                    if (parsed.isNotEmpty()) {
                        PlaylistOverrideStore.mergeFromCloud(appContext, playlistId, parsed)
                    }
                }
                endNetworkSync()
                pending.finish(true, null)
            }
            .addOnFailureListener { serverError ->
                endNetworkSync()
                pending.finish(false, toUserFacingSyncError(serverError))
            }
    }

    private fun handleEqSyncSnapshot(
        snapshot: DocumentSnapshot,
        fromServer: Boolean,
        serverError: Throwable?,
        pending: PendingSync
    ) {
        if (!snapshot.exists()) {
            if (fromServer) {
                uploadEqPreferences()
                endNetworkSync()
                pending.finish(true, null)
            } else {
                endNetworkSync()
                pending.finish(false, toUserFacingSyncError(serverError))
            }
            return
        }

        if (applyEncodedPrefsFromSnapshot(eqPrefs, snapshot)) {
            ensureEqDefaults()
            if (fromServer && !activeUserId.isNullOrEmpty()) {
                uploadEqPreferences()
            }
            endNetworkSync()
            pending.finish(true, null)
            return
        }

        endNetworkSync()
        if (fromServer) {
            pending.finish(false, "No se pudo leer la configuracion EQ en la nube.")
        } else {
            pending.finish(false, toUserFacingSyncError(serverError))
        }
    }

    private fun handleSettingsSyncSnapshot(
        snapshot: DocumentSnapshot,
        fromServer: Boolean,
        serverError: Throwable?,
        pending: PendingSync
    ) {
        if (!snapshot.exists()) {
            if (fromServer) {
                uploadSettingsPreferences()
                endNetworkSync()
                pending.finish(true, null)
            } else {
                endNetworkSync()
                pending.finish(false, toUserFacingSyncError(serverError))
            }
            return
        }

        if (applyEncodedPrefsFromSnapshot(settingsPrefs, snapshot)) {
            ensureSettingsDefaults()
            pruneDeprecatedSettingsKeys()
            endNetworkSync()
            if (fromServer && !activeUserId.isNullOrEmpty()) {
                uploadSettingsPreferences()
            }
            pending.finish(true, null)
            return
        }

        endNetworkSync()
        if (fromServer) {
            pending.finish(false, "No se pudo leer los ajustes en la nube.")
        } else {
            pending.finish(false, toUserFacingSyncError(serverError))
        }
    }


    private fun handleStreamingSyncSnapshot(
        snapshot: DocumentSnapshot,
        fromServer: Boolean,
        serverError: Throwable?,
        pending: PendingSync
    ) {
        if (!snapshot.exists()) {
            if (fromServer) {
                uploadStreamingFavoritesPreferences()
                endNetworkSync()
                pending.finish(true, null)
            } else {
                endNetworkSync()
                pending.finish(false, toUserFacingSyncError(serverError))
            }
            return
        }

        if (applyStreamingFavoritesFromSnapshot(snapshot)) {
            endNetworkSync()
            pending.finish(true, null)
            return
        }

        endNetworkSync()
        if (fromServer) {
            pending.finish(false, "No se pudo leer favoritos de streaming en la nube.")
        } else {
            pending.finish(false, toUserFacingSyncError(serverError))
        }
    }

    private fun toUserFacingSyncError(throwable: Throwable?): String {
        if (throwable is FirebaseFirestoreException) {
            when (throwable.code) {
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    return "Cloud Firestore no esta inicializado en Firebase. Crea la base de datos Firestore en modo nativo."
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    return "Reglas de Firestore bloquean la sincronizacion. Verifica permisos para users/{uid}/sleppify."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    return "Sesion Firebase invalida. Cierra sesion e inicia nuevamente."
                else -> { /* fallthrough */ }
            }
        }

        if (isRecoverableSyncThrowable(throwable)) {
            return "Sin conexion con la nube por ahora. Reintentando automaticamente."
        }
        val message = throwable?.message
        if (message.isNullOrEmpty()) {
            return "No se pudo sincronizar en la nube."
        }
        return message
    }

    private fun isRecoverableSyncMessage(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        val normalized = message.lowercase()
        return normalized.contains("offline") ||
                normalized.contains("sin conexion") ||
                normalized.contains("network") ||
                normalized.contains("unavailable") ||
                normalized.contains("tempor") ||
                normalized.contains("timeout") ||
                normalized.contains("timed out")
    }

    private fun isRecoverableSyncThrowable(throwable: Throwable?): Boolean {
        if (throwable is FirebaseFirestoreException) {
            return when (throwable.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.CANCELLED -> true
                else -> false
            }
        }
        return isRecoverableSyncMessage(throwable?.message)
    }

    private fun uploadAllBucketsNow() {
        if (activeUserId.isNullOrEmpty()) return

        handler.removeCallbacks(eqSyncRunnable)
        handler.removeCallbacks(settingsSyncRunnable)
        handler.removeCallbacks(streamingSyncRunnable)
        uploadEqPreferences()
        uploadSettingsPreferences()
        uploadStreamingFavoritesPreferences()
    }

    private fun uploadEqPreferences() {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return

        val payload = hashMapOf<String, Any>(
            FIELD_PREFS to encodeEqPreferences(),
            FIELD_UPDATED_AT to FieldValue.serverTimestamp()
        )
        beginNetworkSync()
        eqDoc(uid).set(payload)
            .addOnSuccessListener {
                eqUploadRetryAttempt = 0
                handler.removeCallbacks(eqUploadRetryRunnable)
                endNetworkSync()
            }
            .addOnFailureListener { e ->
                endNetworkSync()
                Log.w(TAG, "Fallo subiendo preferencias de EQ", e)
                scheduleEqUploadRetryIfNeeded(e)
            }
    }

    private fun uploadSettingsPreferences() {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return

        val payload = hashMapOf<String, Any>(
            FIELD_PREFS to encodeSettingsPreferences(),
            FIELD_UPDATED_AT to FieldValue.serverTimestamp()
        )
        beginNetworkSync()
        settingsDoc(uid).set(payload)
            .addOnSuccessListener {
                settingsUploadRetryAttempt = 0
                handler.removeCallbacks(settingsUploadRetryRunnable)
                endNetworkSync()
            }
            .addOnFailureListener { e ->
                endNetworkSync()
                Log.w(TAG, "Fallo subiendo preferencias de ajustes", e)
                scheduleSettingsUploadRetryIfNeeded(e)
            }
    }

    private fun uploadStreamingFavoritesPreferences() {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return

        val favorites = FavoritesPlaylistStore.loadFavorites(appContext)
        val favoritesList = ArrayList<Map<String, Any?>>(favorites.size)
        for (i in favorites.indices) {
            val t = favorites[i]
            favoritesList.add(
                mapOf(
                    "videoId" to t.videoId,
                    "title" to t.title,
                    "artist" to t.artist,
                    "duration" to t.duration,
                    "imageUrl" to t.imageUrl,
                    "order" to i
                )
            )
        }

        val payload = hashMapOf<String, Any>(
            FIELD_PREFS to encodeStreamingFavoritesPreferences(),
            "tracks" to favoritesList,
            FIELD_UPDATED_AT to FieldValue.serverTimestamp()
        )
        beginNetworkSync()
        streamingDoc(uid).set(payload)
            .addOnSuccessListener {
                streamingUploadRetryAttempt = 0
                handler.removeCallbacks(streamingUploadRetryRunnable)
                endNetworkSync()
            }
            .addOnFailureListener { e ->
                endNetworkSync()
                Log.w(TAG, "Fallo subiendo favoritos de streaming", e)
                scheduleStreamingUploadRetryIfNeeded(e)
            }
    }

    private fun clearUploadRetryCallbacks() {
        handler.removeCallbacks(eqUploadRetryRunnable)
        handler.removeCallbacks(settingsUploadRetryRunnable)
        handler.removeCallbacks(streamingUploadRetryRunnable)
    }

    private fun resetUploadRetryAttempts() {
        eqUploadRetryAttempt = 0
        settingsUploadRetryAttempt = 0
        streamingUploadRetryAttempt = 0
    }

    private inline fun scheduleUploadRetry(
        throwable: Throwable?,
        retryRunnable: Runnable,
        getAttempt: () -> Int,
        setAttempt: (Int) -> Unit
    ) {
        if (!shouldRetryUpload(throwable) || activeUserId.isNullOrEmpty()) return
        val next = minOf(getAttempt() + 1, UPLOAD_RETRY_MAX_ATTEMPTS)
        setAttempt(next)
        val delayMs = computeUploadRetryDelayMs(next)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun scheduleEqUploadRetryIfNeeded(throwable: Throwable?) {
        scheduleUploadRetry(throwable, eqUploadRetryRunnable,
            { eqUploadRetryAttempt }, { eqUploadRetryAttempt = it })
    }

    private fun scheduleSettingsUploadRetryIfNeeded(throwable: Throwable?) {
        scheduleUploadRetry(throwable, settingsUploadRetryRunnable,
            { settingsUploadRetryAttempt }, { settingsUploadRetryAttempt = it })
    }


    private fun scheduleStreamingUploadRetryIfNeeded(throwable: Throwable?) {
        scheduleUploadRetry(throwable, streamingUploadRetryRunnable,
            { streamingUploadRetryAttempt }, { streamingUploadRetryAttempt = it })
    }

    private fun computeUploadRetryDelayMs(attempt: Int): Long {
        val boundedAttempt = maxOf(1, attempt).toLong()
        return minOf(UPLOAD_RETRY_MAX_MS, UPLOAD_RETRY_BASE_MS * boundedAttempt)
    }

    private fun shouldRetryUpload(throwable: Throwable?): Boolean {
        if (throwable is FirebaseFirestoreException) {
            return when (throwable.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.CANCELLED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> true
                else -> false
            }
        }

        val msg = throwable?.message
        if (msg.isNullOrEmpty()) return false

        val normalized = msg.lowercase()
        return normalized.contains("offline") ||
                normalized.contains("network") ||
                normalized.contains("timeout") ||
                normalized.contains("timed out") ||
                normalized.contains("unavailable")
    }

    private fun applyEncodedPrefsFromSnapshot(
        targetPrefs: SharedPreferences,
        snapshot: DocumentSnapshot
    ): Boolean {
        val encodedPrefs = extractPrefsMap(snapshot)
        if (encodedPrefs.isNullOrEmpty()) return false

        val eqBucket = targetPrefs === eqPrefs
        val settingsBucket = targetPrefs === settingsPrefs

        when {
            eqBucket -> suppressEqSync = true
            settingsBucket -> suppressSettingsSync = true
        }

        try {
            val editor = targetPrefs.edit()
            editor.clear()
            val decodedCount = decodePreferences(encodedPrefs, editor)
            if (decodedCount <= 0) return false
            editor.apply()
            return true
        } finally {
            when {
                eqBucket -> suppressEqSync = false
                settingsBucket -> suppressSettingsSync = false
            }
        }
    }

    private fun applyStreamingFavoritesFromSnapshot(snapshot: DocumentSnapshot): Boolean {
        val encodedPrefs = extractPrefsMap(snapshot) ?: return false

        suppressStreamingSync = true
        try {
            val editor = streamingCachePrefs.edit()
            editor.remove(FAVORITES_TRACKS_UPDATED_AT_KEY)
            editor.remove(FAVORITES_TRACKS_DATA_KEY)
            editor.remove(FAVORITES_TRACKS_FULL_CACHE_KEY)
            editor.remove(FAVORITES_OFFLINE_COMPLETE_KEY)

            for ((key, value) in encodedPrefs) {
                if (!isStreamingFavoritesKey(key)) continue
                decodePreferenceEntry(key, value, editor)
            }
            editor.apply()
            FavoritesPlaylistStore.invalidateCache()
            return true
        } finally {
            suppressStreamingSync = false
        }
    }

    private fun extractPrefsMap(snapshot: DocumentSnapshot): Map<String, Any?>? {
        val raw = snapshot.get(FIELD_PREFS)
        if (raw is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return raw as Map<String, Any?>
        }

        val rootData = snapshot.data
        if (rootData.isNullOrEmpty()) return null

        val fallback = HashMap<String, Any?>()
        fallback.putAll(rootData)
        fallback.remove(FIELD_PREFS)
        fallback.remove(FIELD_UPDATED_AT)
        return if (fallback.isEmpty()) null else fallback
    }

    private fun beginNetworkSync() {
        val shouldNotify: Boolean
        synchronized(syncStateLock) {
            activeNetworkSyncCount++
            shouldNotify = activeNetworkSyncCount == 1
        }
        handler.removeCallbacks(syncStuckWatchdogRunnable)
        handler.postDelayed(syncStuckWatchdogRunnable, SYNC_STUCK_TIMEOUT_MS)
        if (shouldNotify) dispatchSyncState(true)
    }

    private fun endNetworkSync() {
        val shouldNotify: Boolean
        synchronized(syncStateLock) {
            activeNetworkSyncCount = maxOf(0, activeNetworkSyncCount - 1)
            shouldNotify = activeNetworkSyncCount == 0
        }
        if (shouldNotify) {
            handler.removeCallbacks(syncStuckWatchdogRunnable)
        } else {
            handler.removeCallbacks(syncStuckWatchdogRunnable)
            handler.postDelayed(syncStuckWatchdogRunnable, SYNC_STUCK_TIMEOUT_MS)
        }
        if (shouldNotify) dispatchSyncState(false)
    }

    private fun forceClearSyncStateIfStuck() {
        val shouldNotify: Boolean
        synchronized(syncStateLock) {
            shouldNotify = activeNetworkSyncCount > 0
            activeNetworkSyncCount = 0
        }
        if (shouldNotify) dispatchSyncState(false)
    }

    private fun forceClearSyncState() {
        handler.removeCallbacks(syncStuckWatchdogRunnable)
        forceClearSyncStateIfStuck()
    }

    private fun dispatchSyncState(syncing: Boolean) {
        val listenerSnapshot: SyncStateListener?
        synchronized(syncStateLock) {
            listenerSnapshot = syncStateListener
        }
        if (listenerSnapshot == null) return
        handler.post { listenerSnapshot.onSyncStateChanged(syncing) }
    }

    // ----- Encoding -----

    private fun encodeEntry(value: Any?): Map<String, Any>? {
        if (value == null) return null
        return when (value) {
            is Boolean -> mapOf("type" to "bool", "value" to value)
            is Int -> mapOf("type" to "int", "value" to value.toLong())
            is Long -> mapOf("type" to "long", "value" to value)
            is Float -> mapOf("type" to "float", "value" to value.toDouble())
            is String -> mapOf("type" to "string", "value" to value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                val stringSet = value as Set<String>
                mapOf("type" to "string_set", "value" to ArrayList(stringSet))
            }
            else -> null
        }
    }

    private fun encodePreferences(
        prefs: SharedPreferences,
        keyFilter: (String) -> Boolean = { true }
    ): Map<String, Any> {
        val all = prefs.all
        val encoded = HashMap<String, Any>()
        for ((key, value) in all) {
            if (key == null || !keyFilter(key)) continue
            val wrapper = encodeEntry(value) ?: continue
            encoded[key] = wrapper
        }
        return encoded
    }

    private fun encodeEqPreferences(): Map<String, Any> =
        encodePreferences(eqPrefs) { !isDeprecatedEqSyncKey(it) }

    private fun encodeSettingsPreferences(): Map<String, Any> =
        encodePreferences(settingsPrefs) { !isDeprecatedSettingsKey(it) }

    private fun encodeStreamingFavoritesPreferences(): Map<String, Any> =
        encodePreferences(streamingCachePrefs) { isStreamingFavoritesKey(it) }

    private fun pruneDeprecatedSettingsKeys() {
        val all = settingsPrefs.all
        if (all.isEmpty()) return

        var editor: SharedPreferences.Editor? = null
        for (key in all.keys) {
            if (!isDeprecatedSettingsKey(key)) continue
            if (editor == null) editor = settingsPrefs.edit()
            editor.remove(key)
        }

        if (editor == null) return

        val previousSuppress = suppressSettingsSync
        suppressSettingsSync = true
        try {
            editor.apply()
        } finally {
            suppressSettingsSync = previousSuppress
        }
    }

    private fun isDeprecatedSettingsKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        return key.startsWith("schedule_ai_")
    }

    private fun isStreamingFavoritesKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        return key == FAVORITES_TRACKS_UPDATED_AT_KEY ||
                key == FAVORITES_TRACKS_DATA_KEY ||
                key == FAVORITES_TRACKS_FULL_CACHE_KEY ||
                key == FAVORITES_OFFLINE_COMPLETE_KEY ||
                key == "stream_recent_search_queries"
    }

    private fun isDeprecatedEqSyncKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        return key.startsWith(DEBUG_EQ_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_NEXT_AT_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_LAST_PROCESS_SESSION_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_DISMISSED_SESSION_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_PENDING_JSON_PREFIX)
    }

    private fun decodePreferences(
        encodedPrefs: Map<String, Any?>,
        editor: SharedPreferences.Editor
    ): Int {
        var decodedCount = 0
        for ((key, value) in encodedPrefs) {
            if (decodePreferenceEntry(key, value, editor)) decodedCount++
        }
        return decodedCount
    }

    private fun decodePreferenceEntry(
        key: String,
        value: Any?,
        editor: SharedPreferences.Editor
    ): Boolean {
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val wrapper = value as Map<String, Any?>
            if (decodeWrappedPreference(key, wrapper, editor)) return true
        }
        return decodeLegacyPreference(key, value, editor)
    }

    private fun decodeWrappedPreference(
        key: String,
        wrapper: Map<String, Any?>,
        editor: SharedPreferences.Editor
    ): Boolean {
        val typeObj = wrapper["type"] ?: return decodeLegacyPreference(key, wrapper["value"], editor)
        val raw = wrapper["value"]
        return when (typeObj.toString()) {
            "bool" -> {
                if (raw is Boolean) { editor.putBoolean(key, raw); true } else false
            }
            "int" -> {
                if (raw is Number) { editor.putInt(key, raw.toInt()); true } else false
            }
            "long" -> {
                if (raw is Number) { editor.putLong(key, raw.toLong()); true } else false
            }
            "float" -> {
                if (raw is Number) { editor.putFloat(key, raw.toFloat()); true } else false
            }
            "string" -> {
                if (raw != null) { editor.putString(key, raw.toString()); true } else false
            }
            "string_set" -> decodeStringSetPreference(key, raw, editor)
            else -> decodeLegacyPreference(key, raw, editor)
        }
    }

    private fun decodeLegacyPreference(
        key: String,
        raw: Any?,
        editor: SharedPreferences.Editor
    ): Boolean {
        if (raw == null) return false

        when (raw) {
            is Boolean -> { editor.putBoolean(key, raw); return true }
            is String -> { editor.putString(key, raw); return true }
            is Number -> {
                if (isLikelyFloatKey(key)) { editor.putFloat(key, raw.toFloat()); return true }
                if (isLikelyIntKey(key)) { editor.putInt(key, raw.toInt()); return true }
                if (isLikelyLongKey(key)) { editor.putLong(key, raw.toLong()); return true }

                val asDouble = raw.toDouble()
                if (Math.floor(asDouble) != asDouble) {
                    editor.putFloat(key, asDouble.toFloat())
                    return true
                }

                val asLong = raw.toLong()
                if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    editor.putInt(key, asLong.toInt())
                    return true
                }
                editor.putLong(key, asLong)
                return true
            }
            is List<*>, is Set<*> -> return decodeStringSetPreference(key, raw, editor)
        }
        return false
    }

    private fun decodeStringSetPreference(
        key: String,
        raw: Any?,
        editor: SharedPreferences.Editor
    ): Boolean {
        val values: List<*> = when (raw) {
            is List<*> -> raw
            is Set<*> -> ArrayList(raw)
            else -> return false
        }

        val set = HashSet<String>()
        for (item in values) {
            if (item != null) set.add(item.toString())
        }
        editor.putStringSet(key, set)
        return true
    }

    private fun isLikelyFloatKey(key: String): Boolean {
        if (AudioEffectsService.KEY_BASS_DB == key ||
            AudioEffectsService.KEY_BASS_FREQUENCY_HZ == key ||
            key.startsWith(LEGACY_BAND_PREFIX) ||
            key.startsWith(LEGACY_USER_PRESET_BAND_PREFIX)
        ) {
            return true
        }

        if (!key.startsWith(LEGACY_DEVICE_PROFILE_PREFIX)) return false

        return key.endsWith("_" + AudioEffectsService.KEY_BASS_DB) ||
                key.endsWith("_" + AudioEffectsService.KEY_BASS_FREQUENCY_HZ) ||
                key.contains("_$LEGACY_BAND_PREFIX")
    }

    private fun isLikelyIntKey(key: String): Boolean {
        if (AudioEffectsService.KEY_SPATIAL_STRENGTH == key ||
            AudioEffectsService.KEY_REVERB_LEVEL == key ||
            AudioEffectsService.KEY_BASS_TYPE == key ||
            KEY_DEFAULT_DURATION_MINUTES == key ||
            KEY_NOTIFICATION_LEAD_MINUTES == key ||
            KEY_DAILY_SUMMARY_INTERVAL_HOURS == key ||
            KEY_OFFLINE_CROSSFADE_SECONDS == key ||
            KEY_OFFLINE_CROSSFADE_SECONDS == key
        ) {
            return true
        }

        return key.startsWith(LEGACY_DEVICE_PROFILE_PREFIX) &&
                key.endsWith("_" + AudioEffectsService.KEY_BASS_TYPE)
    }

    private fun isLikelyLongKey(key: String): Boolean {
        return key.startsWith(LEGACY_AI_EQ_NEXT_AT_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_LAST_PROCESS_SESSION_PREFIX) ||
                key.startsWith(LEGACY_AI_EQ_DISMISSED_SESSION_PREFIX)
    }

    private fun userScope(uid: String): DocumentReference =
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .collection(APP_SCOPE_COLLECTION)
            .document("root")

    private fun eqDoc(uid: String): DocumentReference =
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .collection(APP_SCOPE_COLLECTION)
            .document(DOC_EQ)

    private fun settingsDoc(uid: String): DocumentReference =
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .collection(APP_SCOPE_COLLECTION)
            .document(DOC_SETTINGS)


    private fun streamingDoc(uid: String): DocumentReference =
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .collection(APP_SCOPE_COLLECTION)
            .document(DOC_STREAMING)

    fun syncPlaylistToCloud(playlistName: String, tracks: List<FavoritesPlaylistStore.FavoriteTrack>) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return

        val tracksList = ArrayList<Map<String, Any?>>(tracks.size)
        for (i in tracks.indices) {
            val t = tracks[i]
            tracksList.add(
                mapOf(
                    "videoId" to t.videoId,
                    "title" to t.title,
                    "artist" to t.artist,
                    "duration" to t.duration,
                    "imageUrl" to t.imageUrl,
                    "order" to i
                )
            )
        }
        val data = hashMapOf<String, Any>("tracks" to tracksList)

        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLISTS).document(playlistName)
            .set(data, SetOptions.merge())
            .addOnFailureListener { e -> Log.e(TAG, "Error syncing playlist $playlistName", e) }
    }

    fun syncPlaylistOverridesToCloud(
        playlistId: String,
        overrides: List<PlaylistOverrideStore.Override>
    ) {
        val uid = activeUserId
        if (uid.isNullOrEmpty() || playlistId.isBlank()) return

        val overrideMaps = ArrayList<Map<String, Any?>>(overrides.size)
        for (o in overrides) overrideMaps.add(o.toMap())
        val data = hashMapOf<String, Any>(
            "overrides" to overrideMaps,
            FIELD_UPDATED_AT to FieldValue.serverTimestamp()
        )

        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLIST_OVERRIDES).document(sanitizePlaylistDocId(playlistId))
            .set(data, SetOptions.merge())
            .addOnFailureListener { e -> Log.e(TAG, "Error syncing overrides for playlist $playlistId", e) }
    }

    fun fetchAllPlaylistOverridesFromCloud(callback: CloudPlaylistOverridesCallback) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) {
            callback.onResult(emptyMap())
            return
        }

        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLIST_OVERRIDES)
            .get()
            .addOnSuccessListener { documents ->
                val result = HashMap<String, List<PlaylistOverrideStore.Override>>()
                for (doc in documents) {
                    val playlistId = doc.id
                    @Suppress("UNCHECKED_CAST")
                    val rawList = doc.get("overrides") as? List<Map<String, Any?>> ?: continue
                    val parsed = ArrayList<PlaylistOverrideStore.Override>(rawList.size)
                    for (m in rawList) {
                        val o = PlaylistOverrideStore.Override.fromMap(m) ?: continue
                        parsed.add(o)
                    }
                    if (parsed.isNotEmpty()) result[playlistId] = parsed
                }
                callback.onResult(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching cloud playlist overrides", e)
                callback.onResult(emptyMap())
            }
    }

    private fun sanitizePlaylistDocId(playlistId: String): String {
        // Firestore doc ids may not contain '/' and must not start/end with '__' (reserved).
        return playlistId.trim()
            .replace('/', '_')
            .replace(Regex("^_+|_+$"), "")
            .ifEmpty { "playlist" }
    }

    fun syncPlayCountsToCloud(context: Context) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return
        val entries = PlayCountStore.exportToJson(context)
        val data = hashMapOf<String, Any>(
            "entries" to entries.toString(),
            FIELD_UPDATED_AT to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(APP_SCOPE_COLLECTION).document(DOC_PLAY_COUNTS)
            .set(data, SetOptions.merge())
            .addOnFailureListener { e -> Log.e(TAG, "Error syncing play counts", e) }
    }

    fun fetchCloudPlayCounts(context: Context) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return
        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(APP_SCOPE_COLLECTION).document(DOC_PLAY_COUNTS)
            .get()
            .addOnSuccessListener { doc ->
                val raw = doc.getString("entries") ?: return@addOnSuccessListener
                try {
                    val arr = org.json.JSONArray(raw)
                    PlayCountStore.importFromJson(context, arr)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing cloud play counts", e)
                }
            }
            .addOnFailureListener { e -> Log.e(TAG, "Error fetching cloud play counts", e) }
    }

    fun deleteCloudPlaylist(playlistName: String) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) return
        
        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLISTS)
            .document(playlistName)
            .delete()
            .addOnFailureListener { e -> Log.e(TAG, "Error deleting cloud playlist: $playlistName", e) }
    }

    fun fetchCloudPlaylists(callback: CloudPlaylistsCallback) {
        val uid = activeUserId
        if (uid.isNullOrEmpty()) {
            callback.onResult(emptyMap())
            return
        }

        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(COLLECTION_PLAYLISTS)
            .get()
            .addOnSuccessListener { documents ->
                val result = HashMap<String, List<FavoritesPlaylistStore.FavoriteTrack>>()
                for (doc in documents) {
                    val name = doc.id
                    @Suppress("UNCHECKED_CAST")
                    val tracksDataRaw = doc.get("tracks") as? List<Map<String, Any?>> ?: continue
                    val tracksData = tracksDataRaw.sortedBy { (it["order"] as? Number)?.toInt() ?: 0 }

                    val tracks = ArrayList<FavoritesPlaylistStore.FavoriteTrack>(tracksData.size)
                    for (m in tracksData) {
                        tracks.add(
                            FavoritesPlaylistStore.FavoriteTrack(
                                m["videoId"] as? String ?: "",
                                m["title"] as? String ?: "",
                                m["artist"] as? String ?: "",
                                m["duration"] as? String ?: "",
                                m["imageUrl"] as? String ?: ""
                            )
                        )
                    }
                    result[name] = tracks
                }
                callback.onResult(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching cloud playlists", e)
                callback.onResult(emptyMap())
            }
    }

    private class PendingSync(
        total: Int,
        private val callback: SyncCallback
    ) {
        private var remaining: Int = total
        private var hasError: Boolean = false
        private var firstError: String? = null

        @Synchronized
        fun finish(success: Boolean, message: String?) {
            if (!success && !hasError) {
                hasError = true
                firstError = if (message.isNullOrEmpty()) "Error de sincronizacion" else message
            }

            remaining--
            if (remaining <= 0) {
                callback.onComplete(!hasError, firstError)
            }
        }
    }

    companion object {
        private const val TAG = "CloudSyncManager"

        @JvmField val PREFS_SETTINGS = "sleppify_settings"
        @JvmField val PREFS_STREAMING_CACHE = "streaming_cache"
        @JvmField val KEY_AI_SHIFT_ENABLED = "ai_shift_enabled"
        @JvmField val KEY_SMART_SUGGESTIONS_ENABLED = "smart_suggestions_enabled"
        @JvmField val KEY_DEFAULT_DURATION_MINUTES = "default_duration_minutes"
        @JvmField val KEY_NOTIFICATION_LEAD_MINUTES = "notification_lead_minutes"
        @JvmField val KEY_DAILY_SUMMARY_INTERVAL_HOURS = "daily_summary_interval_hours"
        @JvmField val KEY_PLAYER_VIDEO_MODE_ENABLED = "player_video_mode_enabled"
        @JvmField val KEY_PLAYER_SHUFFLE_ENABLED = "player_shuffle_enabled"
        @JvmField val KEY_PLAYER_REPEAT_MODE = "player_repeat_mode"
        @JvmField val KEY_AMOLED_MODE_ENABLED = "amoled_mode_enabled"
        @JvmField val KEY_OFFLINE_CROSSFADE_SECONDS = "offline_crossfade_seconds"
        @JvmField val KEY_OFFLINE_DOWNLOAD_QUALITY = "offline_download_quality"
        @JvmField val KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA = "offline_download_allow_mobile_data"
        @JvmField val KEY_OFFLINE_MODE_ENABLED = "offline_mode_enabled"
        @JvmField val KEY_STREAMING_QUALITY = "streaming_quality"
        @JvmField val STREAMING_QUALITY_LOW = "low"
        @JvmField val STREAMING_QUALITY_MEDIUM = "medium"
        @JvmField val STREAMING_QUALITY_HIGH = "high"
        @JvmField val STREAMING_QUALITY_VERY_HIGH = "very_high"

        @JvmField val DOWNLOAD_QUALITY_LOW = "low"
        @JvmField val DOWNLOAD_QUALITY_MEDIUM = "medium"
        @JvmField val DOWNLOAD_QUALITY_HIGH = "high"
        @JvmField val DOWNLOAD_QUALITY_VERY_HIGH = "very_high"

        @JvmField val KEY_DOWNLOAD_CANVAS_ENABLED = "download_canvas_enabled"
        @JvmField val KEY_GAPLESS_PLAYBACK = "gapless_playback_enabled"

        private const val USERS_COLLECTION = "users"
        private const val APP_SCOPE_COLLECTION = "sleppify"
        private const val DOC_EQ = "eq"
        private const val DOC_SETTINGS = "settings"
        private const val DOC_STREAMING = "streaming"
        private const val DOC_PLAY_COUNTS = "play_counts"
        private const val COLLECTION_PLAYLISTS = "playlists"
        private const val COLLECTION_PLAYLIST_OVERRIDES = "playlist_overrides"

        private const val FIELD_PREFS = "prefs"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val OFFLINE_DOWNLOAD_QUEUE_UNIQUE_NAME = "offline_playlist_queue"
        private const val OFFLINE_DOWNLOAD_MANUAL_TRACK_QUEUE_UNIQUE_NAME = "offline_manual_track_queue"
        private const val LEGACY_SELECTED_PRESET = "selected_preset"
        private const val LEGACY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val LEGACY_DEVICE_PROFILE_PREFIX = "device_profile_"
        private const val LEGACY_BAND_PREFIX = "band_db_"
        private const val LEGACY_USER_PRESET_BAND_PREFIX = "user_preset_band_"
        private const val LEGACY_AI_EQ_NEXT_AT_PREFIX = "ai_eq_next_at_"
        private const val LEGACY_AI_EQ_LAST_PROCESS_SESSION_PREFIX = "ai_eq_last_process_session_"
        private const val LEGACY_AI_EQ_DISMISSED_SESSION_PREFIX = "ai_eq_dismissed_session_"
        private const val LEGACY_AI_EQ_PENDING_JSON_PREFIX = "ai_eq_pending_json_"
        private const val DEBUG_EQ_PREFIX = "debug_"

        private val FAVORITES_TRACKS_UPDATED_AT_KEY =
            "playlist_tracks_updated_at_" + FavoritesPlaylistStore.PLAYLIST_ID
        private val FAVORITES_TRACKS_DATA_KEY =
            "playlist_tracks_data_" + FavoritesPlaylistStore.PLAYLIST_ID
        private val FAVORITES_TRACKS_FULL_CACHE_KEY =
            "playlist_tracks_cache_full_" + FavoritesPlaylistStore.PLAYLIST_ID
        private val FAVORITES_OFFLINE_COMPLETE_KEY =
            "playlist_offline_complete_" + FavoritesPlaylistStore.PLAYLIST_ID

        private const val DEBOUNCE_MS = 650L
        private const val SYNC_STUCK_TIMEOUT_MS = 16000L
        private const val INITIAL_HYDRATION_RETRY_BASE_MS = 3000L
        private const val INITIAL_HYDRATION_RETRY_MAX_MS = 15000L
        private const val UPLOAD_RETRY_BASE_MS = 2500L
        private const val UPLOAD_RETRY_MAX_MS = 30000L
        private const val UPLOAD_RETRY_MAX_ATTEMPTS = 80

        @Volatile private var instance: CloudSyncManager? = null

        @JvmStatic
        fun getInstance(context: Context): CloudSyncManager {
            return instance ?: synchronized(CloudSyncManager::class.java) {
                instance ?: CloudSyncManager(context).also { instance = it }
            }
        }
    }
}
