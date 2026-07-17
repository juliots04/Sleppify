package com.example.sleppify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Process-wide store for the Search module's recent searches (typed queries + tracks played from
 * results, with artwork). Owns the in-memory list, the per-account SharedPreferences copy and the
 * Firestore mirror (users/{uid}/sleppify/recent_searches), so the data survives the SearchFragment
 * being closed and keeps hydrating even while the module is closed — the fragment only observes.
 *
 * Sync protocol (the ordering here is what used to eat users' recents):
 *  - Prefs are scoped PER ACCOUNT ("stream_recent_search_data_<uid>", "..._anon" signed out), so
 *    an account switch just swaps lists instead of wiping the previous account's copy before the
 *    new one loads. The pre-refactor unscoped key migrates one-time into the first bucket loaded,
 *    and guest ("anon") recents are MERGED into the account bucket on sign-in, never replaced.
 *  - Firestore uploads are gated on ONE successful GET for the signed-in uid
 *    ([cloudHydratedForUid]): the doc holds a single "searches" array (full replace on every
 *    set()), so a search typed before hydration used to clobber the entire cloud history with a
 *    sparse local list. Failed GETs now retry with bounded exponential backoff instead of
 *    silently leaving the gate closed forever.
 *  - Disk restore and the artist backfill run on a background executor, never the main thread,
 *    and entries whose artist can't be resolved are flagged ([RecentSearch.artistResolveAttempted])
 *    so they don't re-pay the full streaming-cache scan on every load.
 *
 * NOTE: CloudSyncManager still whitelists the LEGACY "stream_recent_search_queries" key in its
 * streaming bucket (CloudSyncManager.isStreamingFavoritesKey). That key is read here only as a
 * last-resort migration source and is never written back, so the two channels cannot diverge;
 * the whitelist entry itself is deliberately left untouched (another change owns that file).
 */
object RecentSearchesStore {
    private const val TAG = "RecentSearchesStore"

    private const val PREF_RECENT_SEARCH_DATA = "stream_recent_search_data"
    private const val PREF_RECENT_SEARCH_QUERIES_LEGACY = "stream_recent_search_queries"
    private const val BUCKET_ANON = "anon"

    // Stored recents (memory + prefs + Firestore). Larger than the text-row limit so the images
    // carousel can surface thumbnailed entries beyond the 6 visible text rows.
    private const val STORE_LIMIT = 20

    // On a cloud merge, this many of the MOST-RECENT local entries survive the trim regardless of
    // thumbnail (freshly typed queries have no artwork yet and used to be the first evicted).
    private const val PROTECTED_LOCAL_ON_MERGE = 6

    private const val MAX_HYDRATE_ATTEMPTS = 5
    private const val HYDRATE_BASE_DELAY_MS = 2000L

    data class RecentSearch(
        val query: String,
        val videoId: String = "",
        val title: String = "",
        val thumbnail: String = "",
        val artist: String = "",
        /** Local-only flag: the artist backfill already scanned every local source for this entry
         *  and found nothing — never re-pay that scan for it. Not uploaded to Firestore. */
        val artistResolveAttempted: Boolean = false
    )

    fun interface Listener {
        fun onRecentSearchesChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Owned by the main thread; mutate only via [runOnMain]. */
    private val entries = mutableListOf<RecentSearch>()

    @Volatile private var appContext: Context? = null

    /** uid, or [BUCKET_ANON] signed out; null until the first auth callback. Main thread. */
    private var currentBucket: String? = null

    /** Bucket whose prefs copy finished loading into [entries]. Main thread. */
    private var loadedBucket: String? = null

    /** Bumped on every bucket change so a stale disk load can't clobber a newer account's list. */
    @Volatile private var loadGeneration = 0

    /** Last uid whose Firestore doc was successfully fetched and merged — the upload gate. */
    private var cloudHydratedForUid: String? = null
    private var hydrateInFlightForUid: String? = null
    private var hydrateAttempts = 0

    /**
     * Idempotent app-scoped init (call from [SleppifyApp.onCreate]). Registers a FirebaseAuth
     * listener at APPLICATION scope — the old fragment-owned listener died in onDestroyView, so a
     * sign-in that resolved while Search was closed hydrated nothing until the next entry.
     */
    @JvmStatic
    fun init(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        try {
            // Fires immediately with the current user (and again when a delayed sign-in resolves),
            // so registering it IS the initial load+hydrate.
            com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
                val uid = auth.currentUser?.uid
                runOnMain { onAuthChanged(uid) }
            }
        } catch (e: Exception) {
            // Firebase unavailable: recents stay local (anon bucket).
            Log.w(TAG, "FirebaseAuth listener unavailable — recents stay local", e)
            runOnMain { onAuthChanged(null) }
        }
    }

    /** Safety net for callers that might run before [SleppifyApp] init (no-op afterwards). */
    @JvmStatic
    fun ensureInitialized(context: Context) = init(context)

    // ---------------------------------------------------------------- public API (main thread)

    /** Snapshot of the current list, newest first. Call from the main thread. */
    fun snapshot(): List<RecentSearch> = entries.toList()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Records a search (or a track played from search results), MERGING with any existing entry
     * for the same query/videoId so an artwork-bearing entry is never clobbered by a plain
     * re-search — same semantics the fragment had, now in one place.
     */
    fun remember(query: String, videoId: String = "", title: String = "", thumbnail: String = "", artist: String = "") {
        val norm = query.trim()
        if (norm.isEmpty()) return
        runOnMain {
            val incomingVideoId = videoId.takeIf { it.isNotEmpty() }
            val existing = entries.firstOrNull {
                it.query.equals(norm, ignoreCase = true) || (incomingVideoId != null && it.videoId == incomingVideoId)
            }
            val merged = RecentSearch(
                query = norm,
                videoId = incomingVideoId ?: existing?.videoId ?: "",
                title = title.takeIf { it.isNotEmpty() } ?: existing?.title ?: "",
                thumbnail = thumbnail.takeIf { it.isNotEmpty() } ?: existing?.thumbnail ?: "",
                artist = artist.takeIf { it.isNotEmpty() } ?: existing?.artist ?: "",
                // Propaga el flag: si el backfill ya barrió todo sin resolver artista para esta
                // entrada, re-buscarla no debe reiniciarlo y re-pagar el escaneo completo.
                artistResolveAttempted = existing?.artistResolveAttempted ?: false
            )
            entries.removeAll {
                it.query.equals(norm, ignoreCase = true) || (merged.videoId.isNotEmpty() && it.videoId == merged.videoId)
            }
            entries.add(0, merged)
            // The freshly typed query is protected: with 20 thumbnailed recents stored, the plain
            // "thumbnail-less first" eviction used to throw out exactly the entry just added.
            trimToLimit(setOf(normKey(norm)))
            notifyListeners()
            persistLocal()
        }
    }

    /**
     * Stamps the top search result's artwork/artist onto this query's recent entry so it surfaces
     * in the images carousel. Only query-only entries (no videoId) or the same track are stamped —
     * an entry pointing at a DIFFERENT played track keeps its own cover/title/playback intact.
     */
    fun stampArtwork(query: String, topVideoId: String, thumbnail: String, artist: String) {
        val norm = query.trim()
        if (norm.isEmpty()) return
        runOnMain {
            val idx = entries.indexOfFirst { it.query.equals(norm, ignoreCase = true) }
            if (idx < 0) return@runOnMain
            val cur = entries[idx]
            if (cur.videoId.isNotEmpty() && cur.videoId != topVideoId) return@runOnMain
            val newThumb = thumbnail.ifEmpty { cur.thumbnail }
            val newArtist = artist.ifEmpty { cur.artist }
            if (newThumb != cur.thumbnail || newArtist != cur.artist) {
                entries[idx] = cur.copy(thumbnail = newThumb, artist = newArtist)
                notifyListeners()
                persistLocal()
            }
        }
    }

    /** Backfills a late-resolved artist onto the entry for [videoId] (images-carousel click path). */
    fun updateArtistForVideo(videoId: String, artist: String) {
        if (videoId.isEmpty() || artist.isEmpty()) return
        runOnMain {
            val idx = entries.indexOfFirst { it.videoId == videoId }
            if (idx >= 0 && entries[idx].artist.isEmpty()) {
                entries[idx] = entries[idx].copy(artist = artist, artistResolveAttempted = true)
                notifyListeners()
                persistLocal()
            }
        }
    }

    fun delete(query: String) {
        runOnMain {
            if (entries.removeAll { it.query == query }) {
                notifyListeners()
                persistLocal()
            }
        }
    }

    /**
     * Re-kicks the Firestore GET when every earlier attempt failed (e.g. the user signed in while
     * offline and the bounded retries ran out). No-op once hydrated or while a fetch is in flight.
     */
    fun retryCloudHydrateIfNeeded() {
        runOnMain {
            val uid = try {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            } catch (_: Exception) { null } ?: return@runOnMain
            if (uid != currentBucket) return@runOnMain // auth listener hasn't settled yet — it will fetch
            if (uid == cloudHydratedForUid || hydrateInFlightForUid != null) return@runOnMain
            hydrateAttempts = 0
            fetchCloudRecents(uid)
        }
    }

    /**
     * Scans local sources (playback history → favorites → custom playlists → cached playlist
     * blobs) for the artist of [videoId]. Heavy on cache miss (JSON-parses every
     * playlist_tracks_data_* blob) — call from a background thread only.
     */
    @JvmStatic
    fun resolveArtistForVideoId(context: Context, videoId: String): String {
        val ctx = context.applicationContext
        // 1. Playback history (most likely to have full metadata)
        try {
            PlaybackHistoryStore.load(ctx).queue.firstOrNull { it.videoId == videoId }?.let {
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
            val allEntries = try { cache.all.toMap() } catch (e: Exception) { emptyMap() }
            for ((key, value) in allEntries) {
                if (key.startsWith("playlist_tracks_data_") && value is String) {
                    val arr = JSONArray(value)
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

    // ------------------------------------------------------------------- auth / bucket handling

    private fun onAuthChanged(uid: String?) {
        val bucket = uid ?: BUCKET_ANON
        if (bucket != currentBucket) {
            val previous = currentBucket
            currentBucket = bucket
            loadGeneration++
            if (previous == BUCKET_ANON && uid != null) {
                // Guest → account promotion: the guest list stays on screen and MERGES into the
                // account bucket. Replacing here is how guest searches used to vanish on login.
                promoteGuestIntoAccount(uid)
            } else {
                // Account switch or sign-out: per-account buckets just swap. The other account's
                // pref copy stays intact (no pre-load wipe, no cross-account grafting).
                entries.clear()
                loadedBucket = null
                notifyListeners()
                loadBucket(bucket)
            }
        }
        if (uid != null && uid != cloudHydratedForUid && uid != hydrateInFlightForUid) {
            hydrateAttempts = 0
            fetchCloudRecents(uid)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)

    private fun scopedKey(bucket: String) = "${PREF_RECENT_SEARCH_DATA}_$bucket"

    private fun loadBucket(bucket: String) {
        val ctx = appContext ?: return
        val generation = loadGeneration
        ioExecutor.execute {
            val p = prefs(ctx)
            var json = p.getString(scopedKey(bucket), null)
            if (json == null) {
                // One-time migration of the pre-refactor UNSCOPED key into the first bucket seen
                // (that bucket is whoever the unscoped list belonged to).
                val unscoped = p.getString(PREF_RECENT_SEARCH_DATA, null)
                if (unscoped != null) {
                    json = unscoped
                    p.edit().putString(scopedKey(bucket), unscoped).remove(PREF_RECENT_SEARCH_DATA).apply()
                }
            }
            var loaded = parseEntries(json)
            if (loaded.isEmpty() && json == null) {
                // Legacy plain-queries key (still cloud-synced by CloudSyncManager's streaming
                // whitelist) — read as a migration source only, NEVER written back.
                try {
                    val arr = JSONArray(p.getString(PREF_RECENT_SEARCH_QUERIES_LEGACY, "[]") ?: "[]")
                    val fromLegacy = mutableListOf<RecentSearch>()
                    for (i in 0 until arr.length()) {
                        val q = arr.optString(i, "")
                        if (q.isNotEmpty()) fromLegacy.add(RecentSearch(query = q))
                    }
                    loaded = fromLegacy
                } catch (_: Exception) {
                }
            }
            // Artist backfill OFF the main thread; unresolved entries get flagged so they never
            // re-trigger the full scan (the old code re-paid it on every module entry).
            var dirty = false
            loaded = loaded.map { e ->
                if (e.videoId.isNotEmpty() && e.artist.isEmpty() && !e.artistResolveAttempted) {
                    dirty = true
                    e.copy(artist = resolveArtistForVideoId(ctx, e.videoId), artistResolveAttempted = true)
                } else e
            }
            val result = loaded
            val backfillDirty = dirty
            mainHandler.post {
                if (generation != loadGeneration || currentBucket != bucket) return@post
                // Searches typed while the disk load was in flight stay at the head.
                val pending = entries.toList()
                for (e in result) mergeAtTail(e)
                trimToLimit(pending.mapTo(mutableSetOf()) { normKey(it.query) })
                loadedBucket = bucket
                notifyListeners()
                if (backfillDirty || pending.isNotEmpty()) persistLocal()
            }
        }
    }

    private fun promoteGuestIntoAccount(uid: String) {
        val ctx = appContext ?: return
        val generation = loadGeneration
        ioExecutor.execute {
            val p = prefs(ctx)
            val account = parseEntries(p.getString(scopedKey(uid), null))
            mainHandler.post {
                if (generation != loadGeneration || currentBucket != uid) return@post
                // Guest entries (the freshest) stay at the head; the account's saved list appends.
                val protectedKeys = entries.mapTo(mutableSetOf()) { normKey(it.query) }
                for (e in account) mergeAtTail(e)
                trimToLimit(protectedKeys)
                loadedBucket = uid
                notifyListeners()
                persistLocal()
                // The guest bucket has been promoted — clear it so it can't re-merge into the
                // NEXT account that signs in on this device.
                ioExecutor.execute {
                    try { p.edit().remove(scopedKey(BUCKET_ANON)).apply() } catch (_: Exception) {}
                }
            }
        }
    }

    // -------------------------------------------------------------------------- cloud hydrate

    /** GET the cloud doc with bounded exponential retry; success opens the upload gate for [uid]. */
    private fun fetchCloudRecents(uid: String) {
        hydrateInFlightForUid = uid
        val db = try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            hydrateInFlightForUid = null
            return
        }
        db.collection("users").document(uid)
            .collection("sleppify").document("recent_searches")
            .get()
            .addOnSuccessListener { doc -> runOnMain { onCloudRecentsFetched(uid, doc) } }
            .addOnFailureListener { e ->
                runOnMain {
                    hydrateInFlightForUid = null
                    // The old code had NO failure listener: a failed GET was silent, never retried,
                    // and the next local save full-replaced the cloud doc.
                    if (currentBucket != uid || cloudHydratedForUid == uid) return@runOnMain
                    if (hydrateAttempts >= MAX_HYDRATE_ATTEMPTS) {
                        Log.w(TAG, "recent_searches GET gave up after $hydrateAttempts retries", e)
                        return@runOnMain
                    }
                    val delayMs = HYDRATE_BASE_DELAY_MS shl hydrateAttempts
                    hydrateAttempts++
                    Log.w(TAG, "recent_searches GET failed — retry #$hydrateAttempts in ${delayMs}ms", e)
                    mainHandler.postDelayed({
                        if (currentBucket == uid && cloudHydratedForUid != uid && hydrateInFlightForUid == null) {
                            fetchCloudRecents(uid)
                        }
                    }, delayMs)
                }
            }
    }

    private fun onCloudRecentsFetched(uid: String, doc: com.google.firebase.firestore.DocumentSnapshot?) {
        hydrateInFlightForUid = null
        if (currentBucket != uid) return // account switched while the GET was in flight
        // Gate opens even for a missing doc: the cloud state is now KNOWN for this uid, so local
        // saves may upload without risk of clobbering unseen history.
        cloudHydratedForUid = uid
        val list = if (doc != null && doc.exists()) doc.get("searches") as? List<*> else null
        val parsed = list?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            RecentSearch(
                query = map["query"] as? String ?: return@mapNotNull null,
                videoId = map["videoId"] as? String ?: "",
                title = map["title"] as? String ?: "",
                thumbnail = map["thumbnail"] as? String ?: "",
                artist = map["artist"] as? String ?: ""
            )
        }.orEmpty()

        // MERGE local-first instead of replacing: cloud entries only append at the tail or
        // backfill missing artwork on matching queries, so searches typed this session survive.
        var changed = false
        val protectedKeys = entries.take(PROTECTED_LOCAL_ON_MERGE).mapTo(mutableSetOf()) { normKey(it.query) }
        for (cloud in parsed) {
            if (mergeAtTail(cloud)) changed = true
        }
        if (entries.size > STORE_LIMIT) {
            trimToLimit(protectedKeys)
            changed = true
        }
        if (changed) {
            notifyListeners()
            persistLocal() // gate is open now — this also pushes the merged state to the cloud
        } else if (entries.isNotEmpty()) {
            // Nothing changed locally but the cloud may be missing entries we hold (or the doc
            // doesn't exist yet) — seed/refresh it once per hydrate.
            maybeUploadToFirestore(entries.toList())
        }
    }

    // ----------------------------------------------------------------------------- persistence

    /** Persists the in-memory list to the current bucket's pref and (gated) to Firestore. */
    private fun persistLocal() {
        val ctx = appContext ?: return
        val bucket = currentBucket ?: return
        // Initial disk load still in flight: memory is ahead of disk, and its post-back merge will
        // persist the union — writing now could shrink the stored list in a crash window.
        if (loadedBucket != bucket) return
        val snapshotList = entries.toList()
        ioExecutor.execute {
            try {
                prefs(ctx).edit().putString(scopedKey(bucket), serializeEntries(snapshotList)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "persist failed", e)
            }
        }
        maybeUploadToFirestore(snapshotList)
    }

    private fun maybeUploadToFirestore(snapshotList: List<RecentSearch>) {
        val uid = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) { null } ?: return
        // GATE: never .set() before one successful GET for this uid. The doc is a single array
        // field — SetOptions.merge() would NOT help (full array replace either way) — so a
        // pre-hydrate upload means permanent loss of the cloud history.
        if (uid != cloudHydratedForUid || uid != currentBucket) return
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("sleppify").document("recent_searches")
                .set(hashMapOf("searches" to snapshotList.map { s ->
                    hashMapOf(
                        "query" to s.query,
                        "videoId" to s.videoId,
                        "title" to s.title,
                        "thumbnail" to s.thumbnail,
                        "artist" to s.artist
                    )
                }))
                .addOnFailureListener { Log.e(TAG, "Failed to save recent searches to Firebase", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore upload unavailable", e)
        }
    }

    // -------------------------------------------------------------------------------- helpers

    /**
     * Appends [incoming] at the tail unless an entry with the same query (or videoId) exists;
     * a matching query entry only backfills missing artwork/metadata. Returns true when changed.
     */
    private fun mergeAtTail(incoming: RecentSearch): Boolean {
        val idx = entries.indexOfFirst { it.query.equals(incoming.query, ignoreCase = true) }
        if (idx >= 0) {
            val local = entries[idx]
            if (local.thumbnail.isEmpty() && incoming.thumbnail.isNotEmpty()) {
                entries[idx] = local.copy(
                    videoId = local.videoId.ifEmpty { incoming.videoId },
                    title = local.title.ifEmpty { incoming.title },
                    thumbnail = incoming.thumbnail,
                    artist = local.artist.ifEmpty { incoming.artist }
                )
                return true
            }
            return false
        }
        if (incoming.videoId.isNotEmpty() && entries.any { it.videoId == incoming.videoId }) {
            // videoId dedup keeps the images carousel from showing the same song twice under two
            // different query strings.
            return false
        }
        entries.add(incoming)
        return true
    }

    /**
     * Trims to [STORE_LIMIT]. Thumbnail-less entries are evicted first (they don't feed the
     * images carousel) — but [protectedQueries] (freshly typed queries, or the most-recent local
     * entries during a cloud merge) are never victims: the old trim evicted exactly the user's
     * newest plain-text searches right after hydration because they had no artwork yet.
     */
    private fun trimToLimit(protectedQueries: Set<String> = emptySet()) {
        while (entries.size > STORE_LIMIT) {
            var victim = entries.indexOfLast { it.thumbnail.isEmpty() && normKey(it.query) !in protectedQueries }
            if (victim < 0) victim = entries.indexOfLast { normKey(it.query) !in protectedQueries }
            if (victim < 0) victim = entries.size - 1
            entries.removeAt(victim)
        }
    }

    private fun normKey(query: String) = query.trim().lowercase(Locale.ROOT)

    private fun parseEntries(json: String?): List<RecentSearch> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<RecentSearch>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val query = obj.optString("query", "")
                if (query.isEmpty()) continue
                out.add(
                    RecentSearch(
                        query = query,
                        videoId = obj.optString("videoId", ""),
                        title = obj.optString("title", ""),
                        thumbnail = obj.optString("thumbnail", ""),
                        artist = obj.optString("artist", ""),
                        artistResolveAttempted = obj.optBoolean("artistResolved", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeEntries(list: List<RecentSearch>): String {
        val array = JSONArray()
        for (s in list) {
            array.put(JSONObject().apply {
                put("query", s.query)
                put("videoId", s.videoId)
                put("title", s.title)
                put("thumbnail", s.thumbnail)
                put("artist", s.artist)
                put("artistResolved", s.artistResolveAttempted)
            })
        }
        return array.toString()
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try { l.onRecentSearchesChanged() } catch (e: Exception) {
                Log.w(TAG, "listener failed", e)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
