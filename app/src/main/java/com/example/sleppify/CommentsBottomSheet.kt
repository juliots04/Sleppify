package com.example.sleppify

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommentsBottomSheet(
    private val context: Context,
    private val videoId: String,
    private val commentCountLabel: String
) {

    data class ReplyItem(
        val authorName: String,
        val authorInitial: String,
        val authorProfileUrl: String,
        val text: String,
        val likeCount: String,
        val publishedAt: String,
        val isVerified: Boolean = false,
        val isArtist: Boolean = false,
        val isChannelOwner: Boolean = false,
        val isHearted: Boolean = false,
        val creatorHeartAvatarUrl: String = "",
        val heartTooltip: String = "",
        val memberBadgeUrl: String = "",
        val memberBadgeLabel: String = ""
    )

    data class CommentItem(
        val authorName: String,
        val authorInitial: String,
        val authorProfileUrl: String,
        val text: String,
        val likeCount: String,
        val publishedAt: String,
        var replies: List<ReplyItem> = emptyList(),
        var repliesExpanded: Boolean = false,
        val replyCount: Int = 0,
        val replyContinuationToken: String? = null,
        val isPinned: Boolean = false,
        val pinnedLabel: String = "",
        val isVerified: Boolean = false,
        val isArtist: Boolean = false,
        val isChannelOwner: Boolean = false,
        val isHearted: Boolean = false,
        val creatorHeartAvatarUrl: String = "",
        val heartTooltip: String = "",
        val memberBadgeUrl: String = "",
        val memberBadgeLabel: String = ""
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val comments = mutableListOf<CommentItem>()
    private var nextPageToken: String? = null
    private var isLoading = false
    @Volatile private var commentsDisabledMessage: String? = null

    private val dialog = BottomSheetDialog(context)
    private val bsv: View = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_comments, null)
    private val rvComments: RecyclerView = bsv.findViewById(R.id.rvComments)
    private val pbLoading: ProgressBar = bsv.findViewById(R.id.pbCommentsLoading)
    private val tvEmpty: TextView = bsv.findViewById(R.id.tvCommentsEmpty)
    private val tvCount: TextView = bsv.findViewById(R.id.tvCommentsCount)
    private val flPagingLoader: FrameLayout = bsv.findViewById(R.id.flCommentsPagingLoader)
    private val adapter = CommentsAdapter(comments)

    companion object {
        private const val INNERTUBE_NEXT = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
        private const val CLIENT_NAME = "WEB"
        private const val CLIENT_VERSION = "2.20241111.01.00"

        // A first-page cache younger than this renders as-is with NO network refresh.
        private const val CACHE_FRESH_MS = 15 * 60 * 1000L

        // Shared client: connection/TLS reuse across first page, pagination, replies and
        // prefetch (a fresh HttpURLConnection handshake per call cost ~200-500ms each).
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        // Prefetch + cache writes live on a shared executor: the per-sheet executor is
        // shutdownNow()'d on dismiss, which would kill an in-flight disk write mid-file.
        private val sharedIo = Executors.newSingleThreadExecutor { r ->
            Thread(r, "comments-io").apply { isDaemon = true }
        }

        @Volatile private var prefetchingVideoId: String? = null

        @JvmStatic
        fun newInstance(videoId: String, commentCount: String): CommentsBottomSheet {
            throw UnsupportedOperationException("Use CommentsBottomSheet(context, videoId, commentCount) directly")
        }

        @JvmStatic
        fun show(context: Context, videoId: String, commentCountLabel: String) {
            CommentsBottomSheet(context, videoId, commentCountLabel).show()
        }

        /**
         * Warms the first-page comments cache in the background so opening the sheet later
         * hits the instant cache path. Safe to call on every track bind: no-ops when a fresh
         * cache exists or a prefetch for this video is already in flight.
         */
        @JvmStatic
        fun prefetch(context: Context, videoId: String) {
            if (videoId.isEmpty()) return
            val appCtx = context.applicationContext
            if (prefetchingVideoId == videoId) return
            sharedIo.execute {
                if (prefetchingVideoId == videoId) return@execute
                prefetchingVideoId = videoId
                try {
                    if (CommentsCacheManager.isFresh(appCtx, videoId, CACHE_FRESH_MS)) return@execute
                    val payload = JSONObject().apply {
                        put("context", buildInnertubeContext())
                        put("continuation", buildCommentsToken(videoId))
                    }
                    val body = postInnertube(INNERTUBE_NEXT, payload) ?: return@execute
                    // Only cache bodies that actually carry comment data; anything else would
                    // just make the sheet parse-and-discard on open.
                    if (body.contains("commentThreadRenderer") || body.contains("commentEntityPayload")) {
                        CommentsCacheManager.saveFirstPageCache(appCtx, videoId, body)
                    }
                } catch (_: Throwable) {
                } finally {
                    prefetchingVideoId = null
                }
            }
        }

        /**
         * Synthesizes the first-page comments continuation token locally (yt-dlp's
         * _generate_comment_continuation byte layout). This skips the watch-next round trip
         * that only existed to discover this token — that call downloads and parses
         * 300KB-1.5MB of JSON and was the single biggest cold-load cost.
         */
        private fun buildCommentsToken(videoId: String): String {
            val raw = StringBuilder()
                .append(0x12.toChar()).append(0x0D.toChar()).append(0x12.toChar()).append(0x0B.toChar())
                .append(videoId)
                .append(0x18.toChar()).append(0x06.toChar()).append('2').append(0x27.toChar()).append('"')
                .append(0x11.toChar()).append('"').append(0x0B.toChar())
                .append(videoId)
                .append('0').append(0x00.toChar()).append('x').append(0x02.toChar())
                .append('0').append(0x00.toChar()).append('B').append(0x10.toChar())
                .append("comments-section")
                .toString()
            val bytes = raw.toByteArray(Charsets.ISO_8859_1)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        private fun buildInnertubeContext(): JSONObject {
            return JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "es")
                    put("gl", "US")
                })
            }
        }

        private fun postInnertube(endpoint: String, payload: JSONObject): String? {
            return try {
                val builder = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                val cookie = StreamResolver.getAuthCookieHeader()
                if (cookie.isNotBlank()) {
                    builder.header("Cookie", cookie)
                    val sapisidHash = generateSapisidHash(cookie)
                    if (sapisidHash.isNotBlank()) {
                        builder.header("Authorization", sapisidHash)
                    }
                }
                httpClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = try { resp.body?.string()?.take(500) ?: "" } catch (_: Exception) { "" }
                        android.util.Log.w("CommentsSheet", "InnerTube ${resp.code}: $err")
                        return null
                    }
                    resp.body?.string()
                }
            } catch (e: Exception) {
                android.util.Log.e("CommentsSheet", "postInnertube network error", e)
                null
            }
        }

        private fun generateSapisidHash(cookieHeader: String): String {
            val sapisid = extractCookieValue(cookieHeader, "SAPISID")
                ?: extractCookieValue(cookieHeader, "__Secure-3PAPISID")
                ?: return ""
            val timestamp = System.currentTimeMillis() / 1000
            val origin = "https://www.youtube.com"
            val input = "$timestamp $sapisid $origin"
            return try {
                val digest = java.security.MessageDigest.getInstance("SHA-1")
                val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                "SAPISIDHASH ${timestamp}_${hash}"
            } catch (_: Exception) { "" }
        }

        private fun extractCookieValue(cookieHeader: String, name: String): String? {
            if (cookieHeader.isBlank()) return null
            return cookieHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=")
                ?.trim()
        }
    }

    init {
        dialog.setContentView(bsv)

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            sheet.setBackgroundResource(android.R.color.transparent)
            val behavior = BottomSheetBehavior.from(sheet)

            val screenHeight = context.resources.displayMetrics.heightPixels
            sheet.layoutParams.height = screenHeight

            behavior.isFitToContents = false
            behavior.halfExpandedRatio = 0.55f
            behavior.skipCollapsed = true
            // Explicit peek: the default PEEK_HEIGHT_AUTO (parentHeight − parentWidth·9/16,
            // ~70% of a portrait screen) parked the COLLAPSED stop ABOVE half, so a swipe
            // down from EXPANDED settled there first and the callback's bounce to half
            // played as a visible second slide. peekHeight=0 keeps COLLAPSED at the very
            // bottom and releases from full settle at HALF_EXPANDED in one motion.
            behavior.setPeekHeight(0, false)
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED

            // Symmetric collapse: opening goes half → full, so swiping down from full must
            // first settle at half (and only dismiss from half). With skipCollapsed=true a
            // fling from EXPANDED targets STATE_HIDDEN directly, so while EXPANDED we make
            // the sheet non-hideable; the release then settles at HALF_EXPANDED instead.
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_EXPANDED -> behavior.isHideable = false
                        BottomSheetBehavior.STATE_HALF_EXPANDED -> behavior.isHideable = true
                        // With peekHeight=0 the collapsed stop is a bottom sliver that only
                        // a drag released far below half can reach (or a few dp higher on
                        // gesture-nav inset clamping); bounce back to half so the sheet
                        // never strands down there.
                        BottomSheetBehavior.STATE_COLLAPSED ->
                            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
        }

        if (commentCountLabel.isNotEmpty() && commentCountLabel != "0") {
            tvCount.text = commentCountLabel
        }

        rvComments.layoutManager = LinearLayoutManager(context)
        rvComments.adapter = adapter
        rvComments.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1) && !isLoading && nextPageToken != null) {
                    loadComments(nextPageToken)
                }
            }
        })

        dialog.setOnDismissListener { executor.shutdownNow() }
    }

    fun show() {
        dialog.show()
        if (videoId.isNotEmpty()) {
            showLoading(true)
            loadComments(null)
        } else {
            showEmpty()
        }
    }

    private fun loadComments(pageToken: String?) {
        if (isLoading) return
        isLoading = true
        if (pageToken != null) flPagingLoader.visibility = View.VISIBLE

        executor.execute {
            var cacheLoaded = false
            if (pageToken == null && comments.isEmpty()) {
                val cachedBody = CommentsCacheManager.getFirstPageCache(context, videoId)
                if (cachedBody != null) {
                    // A truncated/corrupted cache file must not kill the whole load.
                    val parsed = try {
                        parseInnertubeComments(cachedBody)
                    } catch (e: Exception) {
                        CommentsCacheManager.deleteCache(context, videoId)
                        null
                    }
                    if (parsed != null && parsed.first.isNotEmpty()) {
                        bsv.post {
                            if (!dialog.isShowing) return@post
                            nextPageToken = parsed.second
                            comments.addAll(parsed.first)
                            adapter.rebuildRows()
                            adapter.notifyDataSetChanged()
                            showLoading(false)
                        }
                        cacheLoaded = true
                        // Fresh cache (e.g. just prefetched): render it and skip the refresh.
                        if (CommentsCacheManager.isFresh(context, videoId, CACHE_FRESH_MS)) {
                            bsv.post { isLoading = false }
                            return@execute
                        }
                    }
                }
            }

            val result: Pair<List<CommentItem>, String?>?
            val rawBody: String?
            if (pageToken == null) {
                // Fast path: locally synthesized first-page token — ONE round trip. Falls back
                // to the old watch-next token discovery when YouTube rejects the synthesized
                // format or the page comes back empty (the fallback is also what distinguishes
                // "no comments" from "comments disabled" via messageRenderer).
                var fetched = fetchInnertubeComments(buildCommentsToken(videoId))
                if (fetched == null || fetched.first.first.isEmpty()) {
                    val contToken = fetchCommentsContinuationToken(videoId)
                    if (contToken != null) fetched = fetchInnertubeComments(contToken)
                }
                result = fetched?.first
                rawBody = fetched?.second
            } else {
                val fetched = fetchInnertubeComments(pageToken)
                result = fetched?.first
                rawBody = fetched?.second
            }

            // Persist the first page here, off-main — this used to write hundreds of KB to
            // disk on the UI thread inside the render post.
            if (pageToken == null && rawBody != null && result != null && result.first.isNotEmpty()) {
                CommentsCacheManager.saveFirstPageCache(context, videoId, rawBody)
            }

            bsv.post {
                if (!dialog.isShowing) return@post
                isLoading = false
                flPagingLoader.visibility = View.GONE

                if (result == null) {
                    if (comments.isEmpty()) {
                        showLoading(false)
                        val disabledMsg = commentsDisabledMessage
                        if (disabledMsg != null && disabledMsg.isNotBlank()) {
                            showMessage(disabledMsg)
                        } else {
                            showError()
                        }
                    }
                } else {
                    showLoading(false)
                    if (pageToken == null && cacheLoaded) {
                        // The cached page is already on screen. Repaint only if the fresh page
                        // actually differs — repainting identical content reset the scroll and
                        // re-fired every visible avatar load (the flicker on every open).
                        nextPageToken = result.second
                        if (result.first != comments) {
                            comments.clear()
                            comments.addAll(result.first)
                            adapter.rebuildRows()
                            adapter.notifyDataSetChanged()
                        }
                    } else if (pageToken == null) {
                        nextPageToken = result.second
                        comments.addAll(result.first)
                        adapter.rebuildRows()
                        adapter.notifyDataSetChanged()
                    } else {
                        // Pagination appends rows strictly at the end: range-notify instead of
                        // notifyDataSetChanged so visible rows don't rebind/reload avatars.
                        nextPageToken = result.second
                        val oldRowCount = adapter.itemCount
                        comments.addAll(result.first)
                        adapter.rebuildRows()
                        adapter.notifyItemRangeInserted(oldRowCount, adapter.itemCount - oldRowCount)
                    }
                    if (comments.isEmpty()) showEmpty()
                }
            }
        }
    }

    // ── InnerTube helpers ──────────────────────────────────────────────
    // (network + context/auth helpers live in the companion so prefetch can use them)

    private fun fetchCommentsContinuationToken(videoId: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("context", buildInnertubeContext())
                put("videoId", videoId)
            }
            val body = postInnertube(INNERTUBE_NEXT, payload) ?: run {
                android.util.Log.w("CommentsSheet", "fetchContinuationToken: /next returned null for $videoId")
                return null
            }
            val root = JSONObject(body)

            // Strategy 1: engagementPanels (standard YouTube web)
            val panels = root.optJSONArray("engagementPanels")
            if (panels != null) {
                for (i in 0 until panels.length()) {
                    val panel = panels.optJSONObject(i)
                        ?.optJSONObject("engagementPanelSectionListRenderer") ?: continue
                    val panelId = panel.optString("panelIdentifier", "")
                    if (panelId != "comment-item-section") continue
                    val sectionContents = panel.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                    val token = extractContinuationFromSection(sectionContents)
                    if (token != null) {
                        return token
                    }
                }
            }

            // Strategy 2: contents.twoColumnWatchNextResults.results (alternate layout)
            val resultsContents = root.optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("results")
                ?.optJSONObject("results")
                ?.optJSONArray("contents")
            if (resultsContents != null) {
                for (i in 0 until resultsContents.length()) {
                    val section = resultsContents.optJSONObject(i)
                        ?.optJSONObject("itemSectionRenderer") ?: continue
                    val sectionId = section.optString("sectionIdentifier", "")
                    if (sectionId != "comment-item-section") continue
                    val sectionContent = section.optJSONArray("contents")
                    val token = extractContinuationFromContents(sectionContent)
                    if (token != null) {
                        return token
                    }
                    // Check if comments are disabled (messageRenderer present)
                    if (sectionContent != null) {
                        for (j in 0 until sectionContent.length()) {
                            val msg = sectionContent.optJSONObject(j)
                                ?.optJSONObject("messageRenderer")
                            if (msg != null) {
                                commentsDisabledMessage = extractText(msg.optJSONObject("text"))
                                return null
                            }
                        }
                    }
                }
            }

            // Strategy 3: onResponseReceivedEndpoints (sometimes returned inline)
            val endpoints = root.optJSONArray("onResponseReceivedEndpoints")
            if (endpoints != null) {
                for (i in 0 until endpoints.length()) {
                    val ep = endpoints.optJSONObject(i) ?: continue
                    val action = ep.optJSONObject("reloadContinuationItemsAction")
                        ?: ep.optJSONObject("reloadContinuationItemsCommand")
                        ?: ep.optJSONObject("appendContinuationItemsAction")
                        ?: ep.optJSONObject("appendContinuationItemsCommand") ?: continue
                    val items = action.optJSONArray("continuationItems") ?: continue
                    val token = extractContinuationTokenFromItems(items)
                    if (token != null) {
                        return token
                    }
                }
            }

            android.util.Log.w("CommentsSheet", "No comments continuation found for videoId=$videoId")
            null
        } catch (e: Exception) {
            android.util.Log.w("CommentsSheet", "fetchContinuationToken failed", e)
            null
        }
    }

    private fun extractContinuationFromSection(contents: org.json.JSONArray?): String? {
        if (contents == null) return null
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            // itemSectionRenderer path
            val token = extractContinuationFromContents(
                item.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
            )
            if (token != null) return token
            // Direct continuationItemRenderer
            val directToken = extractTokenFromContinuationItem(item)
            if (directToken != null) return directToken
        }
        return null
    }

    private fun extractContinuationFromContents(contents: org.json.JSONArray?): String? {
        if (contents == null) return null
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val token = extractTokenFromContinuationItem(item)
            if (token != null) return token
        }
        return null
    }

    private fun extractTokenFromContinuationItem(item: JSONObject): String? {
        val renderer = item.optJSONObject("continuationItemRenderer") ?: return null
        // Path 1: continuationEndpoint.continuationCommand.token
        renderer.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token", "")?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // Path 2: button.buttonRenderer.command.continuationCommand.token
        renderer.optJSONObject("button")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("command")
            ?.optJSONObject("continuationCommand")
            ?.optString("token", "")?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // Path 3: continuationEndpoint directly has token
        renderer.optJSONObject("continuationEndpoint")
            ?.optString("token", "")?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return null
    }

    private fun extractContinuationTokenFromItems(items: org.json.JSONArray): String? {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val token = extractTokenFromContinuationItem(item)
            if (token != null) return token
        }
        return null
    }

    private fun fetchInnertubeComments(continuationToken: String): Pair<Pair<List<CommentItem>, String?>, String>? {
        return try {
            val payload = JSONObject().apply {
                put("context", buildInnertubeContext())
                put("continuation", continuationToken)
            }
            val body = postInnertube(INNERTUBE_NEXT, payload) ?: return null
            val parsed = parseInnertubeComments(body)
            Pair(parsed, body)
        } catch (e: Exception) {
            android.util.Log.w("CommentsSheet", "fetchInnertubeComments failed", e)
            null
        }
    }

    private fun parseInnertubeComments(body: String): Pair<List<CommentItem>, String?> {
        val result = mutableListOf<CommentItem>()
        val root = JSONObject(body)
        var nextCont: String? = null

        // Build a map of commentEntityPayload from frameworkUpdates (2025+ format).
        // Sibling engagementToolbarStateEntityPayload mutations carry the creator-heart
        // state; each comment joins to its entry via properties.toolbarStateKey.
        val entityMap = mutableMapOf<String, JSONObject>()
        val toolbarStateMap = mutableMapOf<String, JSONObject>()
        val mutations = root.optJSONObject("frameworkUpdates")
            ?.optJSONObject("entityBatchUpdate")
            ?.optJSONArray("mutations")
        if (mutations != null) {
            for (m in 0 until mutations.length()) {
                val mutation = mutations.optJSONObject(m) ?: continue
                val key = mutation.optString("entityKey", "")
                if (key.isEmpty()) continue
                val payloadObj = mutation.optJSONObject("payload") ?: continue
                payloadObj.optJSONObject("commentEntityPayload")
                    ?.let { entityMap[key] = it }
                payloadObj.optJSONObject("engagementToolbarStateEntityPayload")
                    ?.let { toolbarStateMap[key] = it }
            }
        }

        val endpoints = root.optJSONArray("onResponseReceivedEndpoints")
        if (endpoints == null) {
            android.util.Log.w("CommentsSheet", "parseComments: no onResponseReceivedEndpoints")
            return Pair(result, null)
        }

        for (ep in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(ep) ?: continue
            val action = endpoint.optJSONObject("reloadContinuationItemsAction")
                ?: endpoint.optJSONObject("reloadContinuationItemsCommand")
                ?: endpoint.optJSONObject("appendContinuationItemsAction")
                ?: endpoint.optJSONObject("appendContinuationItemsCommand")
                ?: continue
            val items = action.optJSONArray("continuationItems") ?: continue

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue

                // Pagination continuation token
                val contRenderer = item.optJSONObject("continuationItemRenderer")
                if (contRenderer != null) {
                    nextCont = contRenderer.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token", "")?.takeIf { it.isNotEmpty() }
                    continue
                }

                val threadRenderer = item.optJSONObject("commentThreadRenderer") ?: continue

                // --- New format (2025+): commentViewModel + entityPayload ---
                val vmWrapper = threadRenderer.optJSONObject("commentViewModel")
                if (vmWrapper != null) {
                    // commentViewModel can be nested: commentThreadRenderer.commentViewModel.commentViewModel
                    val viewModel = vmWrapper.optJSONObject("commentViewModel") ?: vmWrapper
                    val commentKey = viewModel.optString("commentKey", "")
                    val commentId = viewModel.optString("commentId", "")
                    val entityPayload = entityMap[commentKey]
                        ?: entityMap[commentId]
                    if (entityPayload != null) {
                        val props = entityPayload.optJSONObject("properties")
                        val toolbar = entityPayload.optJSONObject("toolbar")
                        val authorObj = entityPayload.optJSONObject("author")
                        val avatarObj = entityPayload.optJSONObject("avatar")

                        val commentText = props?.optJSONObject("content")?.optString("content", "") ?: ""
                        val author = authorObj?.optString("displayName", "")
                            ?: props?.optString("authorButtonA11y", "") ?: ""
                        val publishedTime = props?.optString("publishedTime", "") ?: ""
                        val likeCount = toolbar?.optString("likeCountNotliked", "")
                            ?: toolbar?.optString("likeCountLiked", "") ?: ""
                        val profileUrl = avatarObj?.optJSONObject("image")
                            ?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "")
                            ?: avatarObj?.optString("thumbnailUrl", "") ?: ""

                        // Badges (new format): flags live on author; the creator heart joins
                        // through properties.toolbarStateKey; pinnedText sits on the viewModel.
                        val isVerified = authorObj?.optBoolean("isVerified", false) ?: false
                        val isArtist = authorObj?.optBoolean("isArtist", false) ?: false
                        val isChannelOwner = authorObj?.optBoolean("isCreator", false) ?: false
                        val memberBadgeUrl = authorObj?.optString("sponsorBadgeUrl", "") ?: ""
                        val memberBadgeLabel = authorObj?.optString("sponsorBadgeA11y", "") ?: ""
                        val toolbarStateKey = props?.optString("toolbarStateKey", "") ?: ""
                        val isHearted = toolbarStateMap[toolbarStateKey]
                            ?.optString("heartState", "") == "TOOLBAR_HEART_STATE_HEARTED"
                        val creatorHeartAvatarUrl = toolbar?.optString("creatorThumbnailUrl", "") ?: ""
                        val heartTooltip = toolbar?.optString("heartActiveTooltip", "") ?: ""
                        // pinnedText is usually a plain string; key presence alone marks pinned
                        // (object-shaped variants fall back to the default "Fijado" label).
                        val pinnedRaw = viewModel.opt("pinnedText")
                        val isPinned = pinnedRaw != null && pinnedRaw != JSONObject.NULL
                        val pinnedLabel = (pinnedRaw as? String) ?: ""

                        // Extract reply continuation token for lazy loading
                        var replyCont: String? = null
                        var replyCount = 0
                        val repliesRenderer = threadRenderer.optJSONObject("replies")
                            ?.optJSONObject("commentRepliesRenderer")
                        if (repliesRenderer != null) {
                            val rcContents = repliesRenderer.optJSONArray("contents")
                            if (rcContents != null) {
                                for (rc in 0 until rcContents.length()) {
                                    replyCont = rcContents.optJSONObject(rc)
                                        ?.optJSONObject("continuationItemRenderer")
                                        ?.optJSONObject("continuationEndpoint")
                                        ?.optJSONObject("continuationCommand")
                                        ?.optString("token", "")?.takeIf { it.isNotEmpty() }
                                    if (replyCont != null) break
                                }
                            }
                            val viewRepliesObj = repliesRenderer.optJSONObject("viewReplies")
                                ?.optJSONObject("buttonRenderer")
                                ?.optJSONObject("text")
                            replyCount = extractText(viewRepliesObj)
                                .replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                        }

                        result.add(CommentItem(
                            authorName = author,
                            authorInitial = author.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            authorProfileUrl = profileUrl,
                            text = commentText,
                            likeCount = likeCount,
                            publishedAt = publishedTime,
                            replyCount = replyCount,
                            replyContinuationToken = replyCont,
                            isPinned = isPinned,
                            pinnedLabel = pinnedLabel,
                            isVerified = isVerified,
                            isArtist = isArtist,
                            isChannelOwner = isChannelOwner,
                            isHearted = isHearted,
                            creatorHeartAvatarUrl = creatorHeartAvatarUrl,
                            heartTooltip = heartTooltip,
                            memberBadgeUrl = memberBadgeUrl,
                            memberBadgeLabel = memberBadgeLabel
                        ))
                        continue
                    }
                }

                // --- Legacy format: comment -> commentRenderer ---
                val commentRenderer = threadRenderer.optJSONObject("comment")
                    ?.optJSONObject("commentRenderer") ?: continue

                val author = extractText(commentRenderer.optJSONObject("authorText"))
                val text = extractText(commentRenderer.optJSONObject("contentText"))
                val profileUrl = commentRenderer.optJSONArray("authorThumbnail")
                    ?.optJSONObject(0)?.optString("url", "")
                    ?: commentRenderer.optJSONObject("authorThumbnail")
                        ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "")
                    ?: ""
                val likeCountText = extractText(commentRenderer.optJSONObject("voteCount"))
                val publishedText = extractText(commentRenderer.optJSONObject("publishedTimeText"))

                // Badges (legacy format): dedicated renderers on the commentRenderer itself
                val legacyOwner = commentRenderer.optBoolean("authorIsChannelOwner", false)
                val pinnedBadge = commentRenderer.optJSONObject("pinnedCommentBadge")
                    ?.optJSONObject("pinnedCommentBadgeRenderer")
                val legacyPinnedLabel = extractText(pinnedBadge?.optJSONObject("label"))
                val badgeIcon = commentRenderer.optJSONObject("authorCommentBadge")
                    ?.optJSONObject("authorCommentBadgeRenderer")
                    ?.optJSONObject("icon")?.optString("iconType", "") ?: ""
                val legacyVerified = badgeIcon == "CHECK" || badgeIcon == "CHECK_CIRCLE_THICK"
                val legacyArtist = badgeIcon == "OFFICIAL_ARTIST_BADGE" || badgeIcon == "AUDIO_BADGE"
                val sponsorBadge = commentRenderer.optJSONObject("sponsorCommentBadge")
                    ?.optJSONObject("sponsorCommentBadgeRenderer")
                val legacyMemberUrl = sponsorBadge?.optJSONObject("customBadge")
                    ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: ""
                val legacyMemberLabel = sponsorBadge?.optString("tooltip", "") ?: ""
                val heartRenderer = commentRenderer.optJSONObject("actionButtons")
                    ?.optJSONObject("commentActionButtonsRenderer")
                    ?.optJSONObject("creatorHeart")
                    ?.optJSONObject("creatorHeartRenderer")
                val legacyHearted = heartRenderer?.optBoolean("isHearted", false) ?: false
                val legacyHeartAvatar = heartRenderer?.optJSONObject("creatorThumbnail")
                    ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: ""
                val legacyHeartTooltip = heartRenderer?.optString("heartedTooltip", "") ?: ""

                // Extract reply continuation token for lazy loading (legacy)
                var legacyReplyCont: String? = null
                var legacyReplyCount = 0
                val legacyRepliesRenderer = threadRenderer.optJSONObject("replies")
                    ?.optJSONObject("commentRepliesRenderer")
                if (legacyRepliesRenderer != null) {
                    val rcItems = legacyRepliesRenderer.optJSONArray("contents")
                    if (rcItems != null) {
                        for (rc in 0 until rcItems.length()) {
                            legacyReplyCont = rcItems.optJSONObject(rc)
                                ?.optJSONObject("continuationItemRenderer")
                                ?.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token", "")?.takeIf { it.isNotEmpty() }
                            if (legacyReplyCont != null) break
                        }
                    }
                    val replyCountText = extractText(
                        legacyRepliesRenderer.optJSONObject("viewReplies")
                            ?.optJSONObject("buttonRenderer")
                            ?.optJSONObject("text")
                    )
                    legacyReplyCount = replyCountText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                }

                result.add(CommentItem(
                    authorName = author,
                    authorInitial = author.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    authorProfileUrl = profileUrl,
                    text = text,
                    likeCount = likeCountText,
                    publishedAt = publishedText,
                    replyCount = legacyReplyCount,
                    replyContinuationToken = legacyReplyCont,
                    isPinned = pinnedBadge != null,
                    pinnedLabel = legacyPinnedLabel,
                    isVerified = legacyVerified,
                    isArtist = legacyArtist,
                    isChannelOwner = legacyOwner,
                    isHearted = legacyHearted,
                    creatorHeartAvatarUrl = legacyHeartAvatar,
                    heartTooltip = legacyHeartTooltip,
                    memberBadgeUrl = legacyMemberUrl,
                    memberBadgeLabel = legacyMemberLabel
                ))
            }
        }
        return Pair(result, nextCont)
    }

    private fun fetchRepliesForThread(replyCont: String): List<ReplyItem> {
        val result = mutableListOf<ReplyItem>()
        try {
            val payload = JSONObject().apply {
                put("context", buildInnertubeContext())
                put("continuation", replyCont)
            }
            val body = postInnertube(INNERTUBE_NEXT, payload) ?: return result
            val root = JSONObject(body)

            // Build entity map for replies from mutations, plus the sibling
            // engagementToolbarStateEntityPayload map for the creator-heart state
            val replyEntityMap = mutableMapOf<String, JSONObject>()
            val replyToolbarStateMap = mutableMapOf<String, JSONObject>()
            val mutations = root.optJSONObject("frameworkUpdates")
                ?.optJSONObject("entityBatchUpdate")
                ?.optJSONArray("mutations")
            if (mutations != null) {
                for (m in 0 until mutations.length()) {
                    val mutation = mutations.optJSONObject(m) ?: continue
                    val key = mutation.optString("entityKey", "")
                    if (key.isEmpty()) continue
                    val payloadObj = mutation.optJSONObject("payload") ?: continue
                    payloadObj.optJSONObject("commentEntityPayload")
                        ?.let { replyEntityMap[key] = it }
                    payloadObj.optJSONObject("engagementToolbarStateEntityPayload")
                        ?.let { replyToolbarStateMap[key] = it }
                }
            }

            // Parse reply items from onResponseReceivedEndpoints
            val endpoints = root.optJSONArray("onResponseReceivedEndpoints")
            if (endpoints != null) {
                for (ep in 0 until endpoints.length()) {
                    val endpoint = endpoints.optJSONObject(ep) ?: continue
                    val action = endpoint.optJSONObject("reloadContinuationItemsAction")
                        ?: endpoint.optJSONObject("reloadContinuationItemsCommand")
                        ?: endpoint.optJSONObject("appendContinuationItemsAction")
                        ?: endpoint.optJSONObject("appendContinuationItemsCommand")
                        ?: continue
                    val items = action.optJSONArray("continuationItems") ?: continue
                    for (i in 0 until items.length()) {
                        val replyItem = items.optJSONObject(i) ?: continue
                        // New format: commentViewModel wrapper
                        val vmWrapper = replyItem.optJSONObject("commentViewModel")
                        if (vmWrapper != null) {
                            val vm = vmWrapper.optJSONObject("commentViewModel") ?: vmWrapper
                            val replyKey = vm.optString("commentKey", "")
                            val replyId = vm.optString("commentId", "")
                            val payload2 = replyEntityMap[replyKey] ?: replyEntityMap[replyId] ?: continue
                            val props = payload2.optJSONObject("properties")
                            val authorObj = payload2.optJSONObject("author")
                            val rToolbar = payload2.optJSONObject("toolbar")
                            val rAuthor = authorObj?.optString("displayName", "")
                                ?: props?.optString("authorButtonA11y", "") ?: ""
                            // Badges: same author flags/toolbar join as top-level comments
                            val rTsKey = props?.optString("toolbarStateKey", "") ?: ""
                            val rHearted = replyToolbarStateMap[rTsKey]
                                ?.optString("heartState", "") == "TOOLBAR_HEART_STATE_HEARTED"
                            result.add(ReplyItem(
                                authorName = rAuthor,
                                authorInitial = rAuthor.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                authorProfileUrl = payload2.optJSONObject("avatar")
                                    ?.optJSONObject("image")?.optJSONArray("sources")
                                    ?.optJSONObject(0)?.optString("url", "") ?: "",
                                text = props?.optJSONObject("content")?.optString("content", "") ?: "",
                                likeCount = rToolbar?.optString("likeCountNotliked", "") ?: "",
                                publishedAt = props?.optString("publishedTime", "") ?: "",
                                isVerified = authorObj?.optBoolean("isVerified", false) ?: false,
                                isArtist = authorObj?.optBoolean("isArtist", false) ?: false,
                                isChannelOwner = authorObj?.optBoolean("isCreator", false) ?: false,
                                isHearted = rHearted,
                                creatorHeartAvatarUrl = rToolbar?.optString("creatorThumbnailUrl", "") ?: "",
                                heartTooltip = rToolbar?.optString("heartActiveTooltip", "") ?: "",
                                memberBadgeUrl = authorObj?.optString("sponsorBadgeUrl", "") ?: "",
                                memberBadgeLabel = authorObj?.optString("sponsorBadgeA11y", "") ?: ""
                            ))
                            continue
                        }
                        // Legacy format: commentRenderer
                        val cr = replyItem.optJSONObject("commentRenderer") ?: continue
                        val rAuthor = extractText(cr.optJSONObject("authorText"))
                        val rBadgeIcon = cr.optJSONObject("authorCommentBadge")
                            ?.optJSONObject("authorCommentBadgeRenderer")
                            ?.optJSONObject("icon")?.optString("iconType", "") ?: ""
                        val rSponsor = cr.optJSONObject("sponsorCommentBadge")
                            ?.optJSONObject("sponsorCommentBadgeRenderer")
                        val rHeart = cr.optJSONObject("actionButtons")
                            ?.optJSONObject("commentActionButtonsRenderer")
                            ?.optJSONObject("creatorHeart")
                            ?.optJSONObject("creatorHeartRenderer")
                        result.add(ReplyItem(
                            authorName = rAuthor,
                            authorInitial = rAuthor.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            authorProfileUrl = cr.optJSONObject("authorThumbnail")
                                ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: "",
                            text = extractText(cr.optJSONObject("contentText")),
                            likeCount = extractText(cr.optJSONObject("voteCount")),
                            publishedAt = extractText(cr.optJSONObject("publishedTimeText")),
                            isVerified = rBadgeIcon == "CHECK" || rBadgeIcon == "CHECK_CIRCLE_THICK",
                            isArtist = rBadgeIcon == "OFFICIAL_ARTIST_BADGE" || rBadgeIcon == "AUDIO_BADGE",
                            isChannelOwner = cr.optBoolean("authorIsChannelOwner", false),
                            isHearted = rHeart?.optBoolean("isHearted", false) ?: false,
                            creatorHeartAvatarUrl = rHeart?.optJSONObject("creatorThumbnail")
                                ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: "",
                            heartTooltip = rHeart?.optString("heartedTooltip", "") ?: "",
                            memberBadgeUrl = rSponsor?.optJSONObject("customBadge")
                                ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: "",
                            memberBadgeLabel = rSponsor?.optString("tooltip", "") ?: ""
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CommentsSheet", "fetchRepliesForThread failed", e)
        }
        return result
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText", "")
        if (simple.isNotEmpty()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString()
    }

    private fun showLoading(show: Boolean) {
        pbLoading.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { rvComments.visibility = View.GONE; tvEmpty.visibility = View.GONE }
        else rvComments.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        pbLoading.visibility = View.GONE
        rvComments.visibility = View.GONE
        tvEmpty.text = "No hay comentarios"
        tvEmpty.visibility = View.VISIBLE
    }

    private fun showMessage(msg: String) {
        pbLoading.visibility = View.GONE
        rvComments.visibility = View.GONE
        tvEmpty.text = msg
        tvEmpty.visibility = View.VISIBLE
    }

    private fun showError() {
        pbLoading.visibility = View.GONE
        rvComments.visibility = View.GONE
        tvEmpty.text = "No se pudieron cargar los comentarios"
        tvEmpty.visibility = View.VISIBLE
    }

    private fun loadAvatarInto(profileUrl: String, ivAvatar: ImageView) {
        if (profileUrl.isNotEmpty()) {
            ivAvatar.visibility = View.VISIBLE
            try {
                // Avatars render at 36dp: decoding at a bounded size + caching the transformed
                // result makes scrolling through pages dramatically cheaper.
                Glide.with(context)
                    .load(profileUrl)
                    .transform(CircleCrop())
                    .override(96, 96)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(ivAvatar)
            } catch (_: Exception) {
                ivAvatar.visibility = View.GONE
            }
        } else {
            try { Glide.with(context).clear(ivAvatar) } catch (_: Exception) {}
            ivAvatar.visibility = View.GONE
        }
    }

    /**
     * Author-row badges shared by comments and replies. Holders recycle, so every badge
     * sets BOTH branches — including resetting the owner chip background/padding.
     */
    private fun bindAuthorBadges(
        tvAuthor: TextView, ivBadge: ImageView, ivMemberBadge: ImageView,
        isVerified: Boolean, isArtist: Boolean, isChannelOwner: Boolean,
        memberBadgeUrl: String, memberBadgeLabel: String
    ) {
        if (isVerified || isArtist) {
            ivBadge.setImageResource(
                if (isArtist) R.drawable.ic_comment_artist else R.drawable.ic_comment_verified
            )
            ivBadge.contentDescription = if (isArtist) "Artista oficial" else "Verificado"
            ivBadge.visibility = View.VISIBLE
        } else {
            ivBadge.visibility = View.GONE
        }
        if (memberBadgeUrl.isNotEmpty()) {
            ivMemberBadge.contentDescription = memberBadgeLabel.ifEmpty { "Miembro" }
            ivMemberBadge.visibility = View.VISIBLE
            try {
                Glide.with(context)
                    .load(memberBadgeUrl)
                    .override(28, 28)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(ivMemberBadge)
            } catch (_: Exception) {
                ivMemberBadge.visibility = View.GONE
            }
        } else {
            try { Glide.with(context).clear(ivMemberBadge) } catch (_: Exception) {}
            ivMemberBadge.visibility = View.GONE
        }
        if (isChannelOwner) {
            val density = context.resources.displayMetrics.density
            val padH = (6 * density).toInt()
            val padV = (1 * density).toInt()
            tvAuthor.setBackgroundResource(R.drawable.bg_comment_owner_chip)
            tvAuthor.setPadding(padH, padV, padH, padV)
        } else {
            tvAuthor.background = null
            tvAuthor.setPadding(0, 0, 0, 0)
        }
    }

    /** Hearted-by-creator marker: creator avatar with a small red heart overlaid. */
    private fun bindCreatorHeart(
        flHeart: View, ivHeartAvatar: ImageView,
        isHearted: Boolean, creatorHeartAvatarUrl: String, heartTooltip: String
    ) {
        if (isHearted) {
            flHeart.contentDescription = heartTooltip.ifEmpty { "Le encantó al creador" }
            flHeart.visibility = View.VISIBLE
            loadAvatarInto(creatorHeartAvatarUrl, ivHeartAvatar)
        } else {
            flHeart.visibility = View.GONE
        }
    }

    private class AdapterRow(val type: Int, val commentIdx: Int, val replyIdx: Int = -1)

    // Flat list of rows: each CommentItem expands to comment row + reply rows + toggle row
    private inner class CommentsAdapter(private val data: List<CommentItem>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_COMMENT = 0
        private val TYPE_REPLY = 1
        private val TYPE_TOGGLE = 2

        private val rows = mutableListOf<AdapterRow>()

        init { rebuildRows() }

        fun rebuildRows() {
            rows.clear()
            for (ci in data.indices) {
                val c = data[ci]
                rows.add(AdapterRow(TYPE_COMMENT, ci))
                val hasReplies = c.replies.isNotEmpty() || c.replyCount > 0 || c.replyContinuationToken != null
                if (hasReplies) {
                    if (c.repliesExpanded && c.replies.isNotEmpty()) {
                        for (ri in c.replies.indices) rows.add(AdapterRow(TYPE_REPLY, ci, ri))
                    }
                    rows.add(AdapterRow(TYPE_TOGGLE, ci))
                }
            }
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = rows[position].type

        /**
         * Range-notify one comment's reply expand/collapse (rows must already be rebuilt).
         * notifyDataSetChanged here rebound every visible row and re-fired all avatar loads.
         */
        fun notifyRepliesToggled(commentIdx: Int, replyCount: Int) {
            var togglePos = -1
            for (i in rows.indices) {
                if (rows[i].type == TYPE_TOGGLE && rows[i].commentIdx == commentIdx) {
                    togglePos = i
                    break
                }
            }
            if (togglePos < 0 || replyCount <= 0) {
                notifyDataSetChanged()
                return
            }
            if (data[commentIdx].repliesExpanded) {
                // New rows sit right above the toggle; the toggle itself changed text/chevron.
                notifyItemRangeInserted(togglePos - replyCount, replyCount)
                notifyItemChanged(togglePos)
            } else {
                // The removed rows occupied [togglePos, togglePos+replyCount-1] pre-rebuild.
                notifyItemRangeRemoved(togglePos, replyCount)
                notifyItemChanged(togglePos)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_COMMENT -> CommentVH(inf.inflate(R.layout.item_comment, parent, false))
                TYPE_REPLY   -> ReplyVH(inf.inflate(R.layout.item_comment_reply, parent, false))
                else         -> ToggleVH(inf.inflate(R.layout.item_comment_replies_toggle, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            when (row.type) {
                TYPE_COMMENT -> (holder as CommentVH).bind(data[row.commentIdx])
                TYPE_REPLY   -> (holder as ReplyVH).bind(data[row.commentIdx].replies[row.replyIdx])
                TYPE_TOGGLE  -> (holder as ToggleVH).bind(data[row.commentIdx], row.commentIdx)
            }
        }

        inner class CommentVH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivAvatar: ImageView = view.findViewById(R.id.ivCommentAvatar)
            private val tvAuthor: TextView = view.findViewById(R.id.tvCommentAuthor)
            private val tvTime: TextView = view.findViewById(R.id.tvCommentTime)
            private val tvText: TextView = view.findViewById(R.id.tvCommentText)
            private val tvLikes: TextView = view.findViewById(R.id.tvCommentLikes)
            private val llPinned: View = view.findViewById(R.id.llCommentPinned)
            private val tvPinned: TextView = view.findViewById(R.id.tvCommentPinned)
            private val ivBadge: ImageView = view.findViewById(R.id.ivCommentBadge)
            private val ivMemberBadge: ImageView = view.findViewById(R.id.ivCommentMemberBadge)
            private val flHeart: View = view.findViewById(R.id.flCommentHeart)
            private val ivHeartAvatar: ImageView = view.findViewById(R.id.ivHeartCreatorAvatar)
            fun bind(item: CommentItem) {
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar)
                if (item.isPinned) {
                    tvPinned.text = item.pinnedLabel.ifEmpty { "Fijado" }
                    llPinned.visibility = View.VISIBLE
                } else {
                    llPinned.visibility = View.GONE
                }
                bindAuthorBadges(
                    tvAuthor, ivBadge, ivMemberBadge, item.isVerified, item.isArtist,
                    item.isChannelOwner, item.memberBadgeUrl, item.memberBadgeLabel
                )
                bindCreatorHeart(
                    flHeart, ivHeartAvatar, item.isHearted,
                    item.creatorHeartAvatarUrl, item.heartTooltip
                )
            }
        }

        inner class ReplyVH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivAvatar: ImageView = view.findViewById(R.id.ivReplyAvatar)
            private val tvAuthor: TextView = view.findViewById(R.id.tvReplyAuthor)
            private val tvTime: TextView = view.findViewById(R.id.tvReplyTime)
            private val tvText: TextView = view.findViewById(R.id.tvReplyText)
            private val tvLikes: TextView = view.findViewById(R.id.tvReplyLikes)
            private val ivBadge: ImageView = view.findViewById(R.id.ivReplyBadge)
            private val ivMemberBadge: ImageView = view.findViewById(R.id.ivReplyMemberBadge)
            private val flHeart: View = view.findViewById(R.id.flReplyHeart)
            private val ivHeartAvatar: ImageView = view.findViewById(R.id.ivReplyHeartCreatorAvatar)
            fun bind(item: ReplyItem) {
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar)
                bindAuthorBadges(
                    tvAuthor, ivBadge, ivMemberBadge, item.isVerified, item.isArtist,
                    item.isChannelOwner, item.memberBadgeUrl, item.memberBadgeLabel
                )
                bindCreatorHeart(
                    flHeart, ivHeartAvatar, item.isHearted,
                    item.creatorHeartAvatarUrl, item.heartTooltip
                )
            }
        }

        inner class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvToggle: TextView = view.findViewById(R.id.tvRepliesToggle)
            private val ivChevron: android.widget.ImageView = view.findViewById(R.id.ivRepliesChevron)
            fun bind(item: CommentItem, commentIdx: Int) {
                val count = if (item.replies.isNotEmpty()) item.replies.size else item.replyCount
                if (item.repliesExpanded) {
                    tvToggle.text = "Ocultar respuestas"
                } else {
                    tvToggle.text = if (count > 0)
                        "$count ${if (count == 1) "respuesta" else "respuestas"}"
                    else "Ver respuestas"
                }
                ivChevron.rotation = if (item.repliesExpanded) 180f else 0f
                itemView.setOnClickListener {
                    if (!item.repliesExpanded && item.replies.isEmpty() && item.replyContinuationToken != null) {
                        tvToggle.text = "Cargando…"
                        executor.execute {
                            val fetched = fetchRepliesForThread(item.replyContinuationToken)
                            bsv.post {
                                if (!dialog.isShowing) return@post
                                item.replies = fetched
                                item.repliesExpanded = true
                                rebuildRows()
                                notifyRepliesToggled(commentIdx, fetched.size)
                            }
                        }
                    } else {
                        item.repliesExpanded = !item.repliesExpanded
                        rebuildRows()
                        notifyRepliesToggled(commentIdx, item.replies.size)
                    }
                }
            }
        }
    }
}
