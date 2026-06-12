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
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
        val publishedAt: String
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
        val replyContinuationToken: String? = null
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

        @JvmStatic
        fun newInstance(videoId: String, commentCount: String): CommentsBottomSheet {
            throw UnsupportedOperationException("Use CommentsBottomSheet(context, videoId, commentCount) directly")
        }

        @JvmStatic
        fun show(context: Context, videoId: String, commentCountLabel: String) {
            CommentsBottomSheet(context, videoId, commentCountLabel).show()
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
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
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
                    val parsed = parseInnertubeComments(cachedBody)
                    if (parsed.first.isNotEmpty()) {
                        bsv.post {
                            if (!dialog.isShowing) return@post
                            nextPageToken = parsed.second
                            comments.addAll(parsed.first)
                            adapter.rebuildRows()
                            adapter.notifyDataSetChanged()
                            showLoading(false)
                        }
                        cacheLoaded = true
                    }
                }
            }

            val result: Pair<List<CommentItem>, String?>?
            val rawBody: String?
            if (pageToken == null) {
                // First load: get continuation token from /next, then fetch comments
                val contToken = fetchCommentsContinuationToken(videoId)
                if (contToken != null) {
                    val fetched = fetchInnertubeComments(contToken)
                    result = fetched?.first
                    rawBody = fetched?.second
                } else {
                    result = null
                    rawBody = null
                }
            } else {
                val fetched = fetchInnertubeComments(pageToken)
                result = fetched?.first
                rawBody = fetched?.second
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
                    if (pageToken == null && rawBody != null) {
                        CommentsCacheManager.saveFirstPageCache(context, videoId, rawBody)
                        if (cacheLoaded) comments.clear()
                    }
                    if (!cacheLoaded || pageToken == null) {
                        nextPageToken = result.second
                        comments.addAll(result.first)
                        preloadAvatars(result.first)
                        adapter.rebuildRows()
                        adapter.notifyDataSetChanged()
                    }
                    if (comments.isEmpty()) showEmpty()
                }
            }
        }
    }

    // ── InnerTube helpers ──────────────────────────────────────────────

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
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Origin", "https://www.youtube.com")
        conn.setRequestProperty("Referer", "https://www.youtube.com/")
        val cookie = StreamResolver.getAuthCookieHeader()
        if (cookie.isNotBlank()) {
            conn.setRequestProperty("Cookie", cookie)
            val sapisidHash = generateSapisidHash(cookie)
            if (sapisidHash.isNotBlank()) {
                conn.setRequestProperty("Authorization", sapisidHash)
            }
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(500) ?: "" } catch (_: Exception) { "" }
                android.util.Log.w("CommentsSheet", "InnerTube $code: $err")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            return body
        } catch (e: Exception) {
            android.util.Log.e("CommentsSheet", "postInnertube network error", e)
            return null
        } finally { conn.disconnect() }
    }

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

        // Build a map of commentEntityPayload from frameworkUpdates (2025+ format)
        val entityMap = mutableMapOf<String, JSONObject>()
        val mutations = root.optJSONObject("frameworkUpdates")
            ?.optJSONObject("entityBatchUpdate")
            ?.optJSONArray("mutations")
        if (mutations != null) {
            for (m in 0 until mutations.length()) {
                val mutation = mutations.optJSONObject(m) ?: continue
                val payload = mutation.optJSONObject("payload")
                    ?.optJSONObject("commentEntityPayload") ?: continue
                val key = mutation.optString("entityKey", "")
                if (key.isNotEmpty()) entityMap[key] = payload
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
                            replyContinuationToken = replyCont
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
                    replyContinuationToken = legacyReplyCont
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

            // Build entity map for replies from mutations
            val replyEntityMap = mutableMapOf<String, JSONObject>()
            val mutations = root.optJSONObject("frameworkUpdates")
                ?.optJSONObject("entityBatchUpdate")
                ?.optJSONArray("mutations")
            if (mutations != null) {
                for (m in 0 until mutations.length()) {
                    val mutation = mutations.optJSONObject(m) ?: continue
                    val ep = mutation.optJSONObject("payload")
                        ?.optJSONObject("commentEntityPayload") ?: continue
                    val key = mutation.optString("entityKey", "")
                    if (key.isNotEmpty()) replyEntityMap[key] = ep
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
                            result.add(ReplyItem(
                                authorName = rAuthor,
                                authorInitial = rAuthor.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                authorProfileUrl = payload2.optJSONObject("avatar")
                                    ?.optJSONObject("image")?.optJSONArray("sources")
                                    ?.optJSONObject(0)?.optString("url", "") ?: "",
                                text = props?.optJSONObject("content")?.optString("content", "") ?: "",
                                likeCount = rToolbar?.optString("likeCountNotliked", "") ?: "",
                                publishedAt = props?.optString("publishedTime", "") ?: ""
                            ))
                            continue
                        }
                        // Legacy format: commentRenderer
                        val cr = replyItem.optJSONObject("commentRenderer") ?: continue
                        val rAuthor = extractText(cr.optJSONObject("authorText"))
                        result.add(ReplyItem(
                            authorName = rAuthor,
                            authorInitial = rAuthor.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            authorProfileUrl = cr.optJSONObject("authorThumbnail")
                                ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url", "") ?: "",
                            text = extractText(cr.optJSONObject("contentText")),
                            likeCount = extractText(cr.optJSONObject("voteCount")),
                            publishedAt = extractText(cr.optJSONObject("publishedTimeText"))
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

    private fun preloadAvatars(items: List<CommentItem>) {
        for (item in items) {
            if (item.authorProfileUrl.isNotEmpty()) {
                try { Glide.with(context).load(item.authorProfileUrl).transform(CircleCrop()).preload() } catch (_: Exception) {}
            }
            for (reply in item.replies) {
                if (reply.authorProfileUrl.isNotEmpty()) {
                    try { Glide.with(context).load(reply.authorProfileUrl).transform(CircleCrop()).preload() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun loadAvatarInto(profileUrl: String, ivAvatar: ImageView) {
        if (profileUrl.isNotEmpty()) {
            ivAvatar.visibility = View.VISIBLE
            try {
                Glide.with(context)
                    .load(profileUrl)
                    .transform(CircleCrop())
                    .into(ivAvatar)
            } catch (_: Exception) {
                ivAvatar.visibility = View.GONE
            }
        } else {
            try { Glide.with(context).clear(ivAvatar) } catch (_: Exception) {}
            ivAvatar.visibility = View.GONE
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
            fun bind(item: CommentItem) {
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar)
            }
        }

        inner class ReplyVH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivAvatar: ImageView = view.findViewById(R.id.ivReplyAvatar)
            private val tvAuthor: TextView = view.findViewById(R.id.tvReplyAuthor)
            private val tvTime: TextView = view.findViewById(R.id.tvReplyTime)
            private val tvText: TextView = view.findViewById(R.id.tvReplyText)
            private val tvLikes: TextView = view.findViewById(R.id.tvReplyLikes)
            fun bind(item: ReplyItem) {
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar)
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
                                notifyDataSetChanged()
                            }
                        }
                    } else {
                        item.repliesExpanded = !item.repliesExpanded
                        rebuildRows()
                        notifyDataSetChanged()
                    }
                }
            }
        }
    }
}
