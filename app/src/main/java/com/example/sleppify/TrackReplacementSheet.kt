package com.example.sleppify

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TrackReplacementSheet : BottomSheetDialogFragment() {

    fun interface OnReplacementConfirmedListener {
        fun onReplacementConfirmed(
            playlistId: String,
            playlistType: String,
            originalVideoId: String,
            candidate: YouTubeMusicService.ReplacementCandidate
        )
    }

    fun interface OnReplacementUndoneListener {
        fun onReplacementUndone(playlistId: String, originalVideoId: String)
    }

    private var replacementListener: OnReplacementConfirmedListener? = null
    private var undoListener: OnReplacementUndoneListener? = null

    private var llLoadingState: LinearLayout? = null
    private var rvCandidates: RecyclerView? = null
    private var llEmptyState: LinearLayout? = null
    private var btnRetry: TextView? = null
    private var tvOriginalTrackInfo: TextView? = null

    override fun getTheme(): Int = com.google.android.material.R.style.Theme_Design_BottomSheetDialog

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val parent = parentFragment
        if (parent is OnReplacementConfirmedListener) {
            replacementListener = parent
        } else if (context is OnReplacementConfirmedListener) {
            replacementListener = context
        }
        if (parent is OnReplacementUndoneListener) {
            undoListener = parent
        } else if (context is OnReplacementUndoneListener) {
            undoListener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_track_replacement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val shapeModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
            .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 56f)
            .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 56f)
            .build()
        val materialShape = com.google.android.material.shape.MaterialShapeDrawable(shapeModel).apply {
            fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
        }
        view.background = materialShape

        llLoadingState = view.findViewById(R.id.llLoadingState)
        rvCandidates = view.findViewById(R.id.rvReplacementCandidates)
        llEmptyState = view.findViewById(R.id.llEmptyState)
        btnRetry = view.findViewById(R.id.btnRetry)
        tvOriginalTrackInfo = view.findViewById(R.id.tvOriginalTrackInfo)
        val ivOriginalTrackThumbnail: ImageView? = view.findViewById(R.id.ivOriginalTrackThumbnail)
        val btnUndo: TextView? = view.findViewById(R.id.btnUndoReplacement)

        val args = arguments ?: return
        val title = args.getString(ARG_TITLE, "")
        val artist = args.getString(ARG_ARTIST, "")
        val originalVideoId = args.getString(ARG_VIDEO_ID, "")
        val imageUrl = args.getString(ARG_IMAGE_URL, "")
        val hasOverride = args.getBoolean(ARG_HAS_OVERRIDE, false)

        val infoText = buildString {
            if (title.isNotEmpty()) append(title)
            if (artist.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append(artist)
            }
        }
        tvOriginalTrackInfo?.text = infoText

        ivOriginalTrackThumbnail?.let { iv ->
            val finalImageUrl = if (imageUrl.isNotEmpty()) imageUrl else "https://img.youtube.com/vi/$originalVideoId/hqdefault.jpg"
            try {
                Glide.with(iv)
                    .load(finalImageUrl)
                    .centerCrop()
                    .into(iv)
            } catch (_: Exception) {}
        }

        rvCandidates?.layoutManager = LinearLayoutManager(requireContext())

        if (hasOverride && btnUndo != null) {
            btnUndo.visibility = View.VISIBLE
            btnUndo.setOnClickListener {
                val a = arguments ?: return@setOnClickListener
                val pid = a.getString(ARG_PLAYLIST_ID, "")
                val oid = a.getString(ARG_VIDEO_ID, "")
                undoListener?.onReplacementUndone(pid, oid)
                dismissAllowingStateLoss()
            }
        }

        btnRetry?.setOnClickListener { searchCandidates() }
        searchCandidates()
    }

    private fun searchCandidates() {
        showLoading()
        val args = arguments ?: return
        val title = args.getString(ARG_TITLE, "").trim()
        val artist = args.getString(ARG_ARTIST, "").trim()
        val originalVideoId = args.getString(ARG_VIDEO_ID, "").trim()

        val query = buildString {
            append(title)
            if (artist.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(artist)
            }
        }

        if (query.isEmpty() || originalVideoId.isEmpty()) {
            showEmpty()
            return
        }

        val service = YouTubeMusicService()
        service.searchReplacementCandidates(
            requireContext(),
            query,
            originalVideoId,
            MAX_CANDIDATES,
            object : YouTubeMusicService.ReplacementCandidatesCallback {
                override fun onSuccess(candidates: List<YouTubeMusicService.ReplacementCandidate>) {
                    if (!isAdded) return
                    if (candidates.isEmpty()) {
                        showEmpty()
                    } else {
                        showResults(candidates)
                    }
                }

                override fun onError(error: String) {
                    if (!isAdded) return
                    Log.w(TAG, "searchReplacementCandidates error: $error")
                    showEmpty()
                }
            }
        )
    }

    private fun showLoading() {
        llLoadingState?.visibility = View.VISIBLE
        rvCandidates?.visibility = View.GONE
        llEmptyState?.visibility = View.GONE
    }

    private fun showResults(candidates: List<YouTubeMusicService.ReplacementCandidate>) {
        llLoadingState?.visibility = View.GONE
        rvCandidates?.visibility = View.VISIBLE
        llEmptyState?.visibility = View.GONE
        rvCandidates?.adapter = CandidateAdapter(candidates) { candidate ->
            onCandidateSelected(candidate)
        }
    }

    private fun showEmpty() {
        llLoadingState?.visibility = View.GONE
        rvCandidates?.visibility = View.GONE
        llEmptyState?.visibility = View.VISIBLE
    }

    private fun onCandidateSelected(candidate: YouTubeMusicService.ReplacementCandidate) {
        val args = arguments ?: return
        val playlistId = args.getString(ARG_PLAYLIST_ID, "")
        val playlistType = args.getString(ARG_PLAYLIST_TYPE, "")
        val originalVideoId = args.getString(ARG_VIDEO_ID, "")

        replacementListener?.onReplacementConfirmed(
            playlistId, playlistType, originalVideoId, candidate
        )
        dismissAllowingStateLoss()
    }

    // ----- Adapter -----

    private class CandidateAdapter(
        private val items: List<YouTubeMusicService.ReplacementCandidate>,
        private val onReplace: (YouTubeMusicService.ReplacementCandidate) -> Unit
    ) : RecyclerView.Adapter<CandidateAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_replacement_candidate, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvArtist.text = item.artist
            holder.tvDuration.text = item.duration

            try {
                Glide.with(holder.ivThumbnail)
                    .load(item.thumbnailUrl)
                    .centerCrop()
                    .into(holder.ivThumbnail)
            } catch (_: Exception) {}

            holder.btnReplace.setOnClickListener { onReplace(item) }
            holder.itemView.setOnClickListener { onReplace(item) }
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivThumbnail: ImageView = itemView.findViewById(R.id.ivCandidateThumbnail)
            val tvTitle: TextView = itemView.findViewById(R.id.tvCandidateTitle)
            val tvArtist: TextView = itemView.findViewById(R.id.tvCandidateArtist)
            val tvDuration: TextView = itemView.findViewById(R.id.tvCandidateDuration)
            val btnReplace: TextView = itemView.findViewById(R.id.btnReplace)
        }
    }

    companion object {
        private const val TAG = "TrackReplacementSheet"
        private const val MAX_CANDIDATES = 5

        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_TYPE = "playlist_type"
        private const val ARG_VIDEO_ID = "video_id"
        private const val ARG_TITLE = "title"
        private const val ARG_ARTIST = "artist"
        private const val ARG_DURATION = "duration"
        private const val ARG_IMAGE_URL = "image_url"
        private const val ARG_DOWNLOAD_SUBSCRIBED = "download_subscribed"
        private const val ARG_HAS_OVERRIDE = "has_override"

        @JvmStatic
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            playlistId: String,
            playlistType: String,
            videoId: String,
            title: String,
            artist: String,
            duration: String,
            imageUrl: String,
            downloadSubscribed: Boolean,
            hasOverride: Boolean = false
        ) {
            val sheet = TrackReplacementSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_PLAYLIST_ID, playlistId)
                putString(ARG_PLAYLIST_TYPE, playlistType)
                putString(ARG_VIDEO_ID, videoId)
                putString(ARG_TITLE, title)
                putString(ARG_ARTIST, artist)
                putString(ARG_DURATION, duration)
                putString(ARG_IMAGE_URL, imageUrl)
                putBoolean(ARG_DOWNLOAD_SUBSCRIBED, downloadSubscribed)
                putBoolean(ARG_HAS_OVERRIDE, hasOverride)
            }
            sheet.show(fragmentManager, "track_replacement")
        }
    }
}
