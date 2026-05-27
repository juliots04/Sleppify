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
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.os.Build
import android.text.Html
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
        val replies: List<ReplyItem>,
        var repliesExpanded: Boolean = false
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val comments = mutableListOf<CommentItem>()
    private var nextPageToken: String? = null
    private var isLoading = false

    private val dialog = BottomSheetDialog(context)
    private val bsv: View = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_comments, null)
    private val rvComments: RecyclerView = bsv.findViewById(R.id.rvComments)
    private val pbLoading: ProgressBar = bsv.findViewById(R.id.pbCommentsLoading)
    private val tvEmpty: TextView = bsv.findViewById(R.id.tvCommentsEmpty)
    private val tvCount: TextView = bsv.findViewById(R.id.tvCommentsCount)
    private val flPagingLoader: FrameLayout = bsv.findViewById(R.id.flCommentsPagingLoader)
    private val adapter = CommentsAdapter(comments)

    companion object {
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

        dialog.setOnShowListener { d ->
            val bsd = d as BottomSheetDialog
            val sheet = bsd.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                sheet.alpha = 0f
                sheet.visibility = View.INVISIBLE
                sheet.post {
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    sheet.post {
                        sheet.visibility = View.VISIBLE
                        sheet.animate().alpha(1f).setDuration(120L).start()
                    }
                }
            }
        }

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

        val apiKey = try { (BuildConfig.YOUTUBE_DATA_API_KEY ?: "").trim() } catch (e: Exception) { "" }
        if (apiKey.isEmpty()) { showEmpty(); isLoading = false; return }

        executor.execute {
            val result = fetchComments(videoId, apiKey, pageToken)
            bsv.post {
                if (!dialog.isShowing) return@post
                isLoading = false
                flPagingLoader.visibility = View.GONE
                showLoading(false)
                if (result == null) {
                    if (comments.isEmpty()) showError()
                } else {
                    nextPageToken = result.second
                    comments.addAll(result.first)
                    adapter.rebuildRows()
                    adapter.notifyDataSetChanged()
                    if (comments.isEmpty()) showEmpty()
                }
            }
        }
    }

    private fun fetchComments(videoId: String, apiKey: String, pageToken: String?): Pair<List<CommentItem>, String?>? {
        return try {
            val url = StringBuilder()
                .append("https://www.googleapis.com/youtube/v3/commentThreads")
                .append("?part=snippet,replies")
                .append("&videoId=").append(android.net.Uri.encode(videoId))
                .append("&maxResults=50")
                .append("&order=relevance")
                .append("&key=").append(android.net.Uri.encode(apiKey))
                .also { if (pageToken != null) it.append("&pageToken=").append(android.net.Uri.encode(pageToken)) }
                .toString()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                parseCommentsResponse(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
        } catch (e: Exception) { null }
    }

    private fun parseCommentsResponse(body: String): Pair<List<CommentItem>, String?> {
        val result = mutableListOf<CommentItem>()
        val root = JSONObject(body)
        val nextToken = root.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
        val arr = root.optJSONArray("items") ?: return Pair(result, nextToken)
        for (i in 0 until arr.length()) {
            try {
                val thread = arr.optJSONObject(i) ?: continue
                val topSnippet = thread.optJSONObject("snippet")
                    ?.optJSONObject("topLevelComment")
                    ?.optJSONObject("snippet") ?: continue
                val replies = mutableListOf<ReplyItem>()
                val repliesArr = thread.optJSONObject("replies")?.optJSONArray("comments")
                if (repliesArr != null) {
                    for (j in repliesArr.length() - 1 downTo 0) {
                        val rs = repliesArr.optJSONObject(j)?.optJSONObject("snippet") ?: continue
                        val rAuthor = rs.optString("authorDisplayName", "")
                        replies.add(ReplyItem(
                            authorName = rAuthor,
                            authorInitial = rAuthor.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            authorProfileUrl = rs.optString("authorProfileImageUrl", ""),
                            text = decodeHtml(rs.optString("textOriginal", "").ifEmpty { rs.optString("textDisplay", "") }),
                            likeCount = rs.optLong("likeCount", 0).let { if (it > 0) formatCount(it) else "" },
                            publishedAt = formatRelativeTime(rs.optString("publishedAt", ""))
                        ))
                    }
                }
                val author = topSnippet.optString("authorDisplayName", "")
                result.add(CommentItem(
                    authorName = author,
                    authorInitial = author.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    authorProfileUrl = topSnippet.optString("authorProfileImageUrl", ""),
                    text = decodeHtml(topSnippet.optString("textOriginal", "").ifEmpty { topSnippet.optString("textDisplay", "") }),
                    likeCount = topSnippet.optLong("likeCount", 0).let { if (it > 0) formatCount(it) else "" },
                    publishedAt = formatRelativeTime(topSnippet.optString("publishedAt", "")),
                    replies = replies
                ))
            } catch (e: Exception) { continue }
        }
        return Pair(result, nextToken)
    }

    @Suppress("DEPRECATION")
    private fun decodeHtml(raw: String): String {
        if (raw.isEmpty()) return raw
        val normalized = raw
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString().trimEnd()
        } else {
            Html.fromHtml(normalized).toString().trimEnd()
        }
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    private fun formatRelativeTime(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val then = sdf.parse(iso)?.time ?: return ""
            val diffDays = (System.currentTimeMillis() - then) / 86_400_000L
            when {
                diffDays < 1 -> "hoy"
                diffDays < 7 -> "hace ${diffDays}d"
                diffDays < 30 -> "hace ${diffDays / 7}sem"
                diffDays < 365 -> "hace ${diffDays / 30}mes"
                else -> "hace ${diffDays / 365}a"
            }
        } catch (e: Exception) { "" }
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

    private fun showError() {
        pbLoading.visibility = View.GONE
        rvComments.visibility = View.GONE
        tvEmpty.text = "No se pudieron cargar los comentarios"
        tvEmpty.visibility = View.VISIBLE
    }

    private fun loadAvatarInto(profileUrl: String, ivAvatar: ImageView, itemView: View) {
        if (profileUrl.isNotEmpty()) {
            try {
                Glide.with(context)
                    .load(profileUrl)
                    .transform(CircleCrop())
                    .into(object : DrawableImageViewTarget(ivAvatar) {
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                        ) {
                            super.onResourceReady(resource, transition)
                            ivAvatar.visibility = View.VISIBLE
                        }
                        override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                            ivAvatar.visibility = View.GONE
                        }
                    })
            } catch (e: Exception) {
                ivAvatar.visibility = View.GONE
            }
        } else {
            try { Glide.with(context).clear(ivAvatar) } catch (e: Exception) {}
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
                if (c.replies.isNotEmpty()) {
                    if (c.repliesExpanded) {
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
            private val tvAvatar: TextView = view.findViewById(R.id.tvCommentAvatar)
            private val ivAvatar: ImageView = view.findViewById(R.id.ivCommentAvatar)
            private val tvAuthor: TextView = view.findViewById(R.id.tvCommentAuthor)
            private val tvTime: TextView = view.findViewById(R.id.tvCommentTime)
            private val tvText: TextView = view.findViewById(R.id.tvCommentText)
            private val tvLikes: TextView = view.findViewById(R.id.tvCommentLikes)
            fun bind(item: CommentItem) {
                tvAvatar.text = item.authorInitial
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar, itemView)
            }
        }

        inner class ReplyVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvAvatar: TextView = view.findViewById(R.id.tvReplyAvatar)
            private val ivAvatar: ImageView = view.findViewById(R.id.ivReplyAvatar)
            private val tvAuthor: TextView = view.findViewById(R.id.tvReplyAuthor)
            private val tvTime: TextView = view.findViewById(R.id.tvReplyTime)
            private val tvText: TextView = view.findViewById(R.id.tvReplyText)
            private val tvLikes: TextView = view.findViewById(R.id.tvReplyLikes)
            fun bind(item: ReplyItem) {
                tvAvatar.text = item.authorInitial
                tvAuthor.text = item.authorName
                tvTime.text = item.publishedAt
                tvText.text = item.text
                tvLikes.text = item.likeCount
                tvLikes.visibility = if (item.likeCount.isEmpty()) View.GONE else View.VISIBLE
                loadAvatarInto(item.authorProfileUrl, ivAvatar, itemView)
            }
        }

        inner class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvToggle: TextView = view.findViewById(R.id.tvRepliesToggle)
            private val ivChevron: android.widget.ImageView = view.findViewById(R.id.ivRepliesChevron)
            fun bind(item: CommentItem, commentIdx: Int) {
                val count = item.replies.size
                tvToggle.text = if (item.repliesExpanded)
                    "Ocultar respuestas"
                else
                    "$count ${if (count == 1) "respuesta" else "respuestas"}"
                ivChevron.rotation = if (item.repliesExpanded) 180f else 0f
                itemView.setOnClickListener {
                    item.repliesExpanded = !item.repliesExpanded
                    rebuildRows()
                    notifyDataSetChanged()
                }
            }
        }
    }
}
