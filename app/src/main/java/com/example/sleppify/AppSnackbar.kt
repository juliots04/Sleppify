package com.example.sleppify

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Unified transient snackbar for the whole app: a solid dark bar (Spotify-style — #2A2A2A,
 * 8dp corners), white message text and a light-blue "Cambiar"/"Deshacer" action that reads as
 * tappable. One look, one spring animation, one dedupe tag.
 *
 * Replaces every ad-hoc mechanism that existed before (android.widget.Toast, the five
 * hand-rolled `saved_bar` LinearLayout builders in Search/PlaylistDetail/Scanner/SongPlayer).
 *
 * Hosting:
 *  - [show]/[showAction] attach to the Activity content root, floating above the bottom
 *    nav + mini player (same inset logic the old bars used).
 *  - [showInView] attaches to an explicit root (the full player uses its own root so the
 *    toast renders inside the player surface).
 *
 * The view keeps the legacy tag "saved_bar" so MainActivity.transferPlayerSavedBarToActivity
 * can still re-host a live toast when the player collapses.
 */
object AppSnackbar {

    const val TAG_BAR = "saved_bar"

    private const val DURATION_PLAIN_MS = 2800L
    private const val DURATION_ACTION_MS = 4000L

    private const val COLOR_MESSAGE = 0xFFFFFFFF.toInt()
    private const val COLOR_ACTION = 0xFF58A6FF.toInt() // celeste — reads as clickable

    /** Plain message toast on the activity content root. */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity?, message: String, durationMs: Long = DURATION_PLAIN_MS) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // view.post makes this safe from any thread (old Toast.makeText tolerated that).
        root.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val density = activity.resources.displayMetrics.density
            val bar = buildBar(activity, message, null, null)
            attach(root, bar, hostLayoutParams(root, computeBottomInset(activity, density), density), durationMs)
        }
    }

    /** Message + one action button (e.g. "Cambiar"/"Deshacer") on the activity content root. */
    @JvmStatic
    @JvmOverloads
    fun showAction(
        activity: Activity?,
        message: String,
        actionLabel: String,
        onAction: Runnable,
        durationMs: Long = DURATION_ACTION_MS
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val density = activity.resources.displayMetrics.density
            val bar = buildBar(activity, message, actionLabel, onAction)
            attach(root, bar, hostLayoutParams(root, computeBottomInset(activity, density), density), durationMs)
        }
    }

    /**
     * Toast hosted inside an arbitrary root (the full player passes its own ConstraintLayout).
     * [bottomMarginPx] positions it above whatever footer the host has.
     */
    @JvmStatic
    @JvmOverloads
    fun showInView(
        root: ViewGroup?,
        message: String,
        actionLabel: String? = null,
        onAction: Runnable? = null,
        bottomMarginPx: Int = -1,
        durationMs: Long = if (actionLabel != null) DURATION_ACTION_MS else DURATION_PLAIN_MS
    ) {
        if (root == null || !root.isAttachedToWindow) return
        root.post {
            if (!root.isAttachedToWindow) return@post
            val ctx = root.context ?: return@post
            val density = ctx.resources.displayMetrics.density
            val margin = if (bottomMarginPx >= 0) bottomMarginPx else (24 * density).toInt()
            val bar = buildBar(ctx, message, actionLabel, onAction)
            attach(root, bar, hostLayoutParams(root, margin, density), durationMs)
        }
    }

    // ---------------------------------------------------------------------------------------
    // View construction
    // ---------------------------------------------------------------------------------------

    private fun buildBar(
        ctx: Context,
        message: String,
        actionLabel: String?,
        onAction: Runnable?
    ): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val interFont = try {
            ResourcesCompat.getFont(ctx, R.font.inter_variable)
        } catch (_: Exception) {
            null
        }

        val bar = LinearLayout(ctx).apply {
            tag = TAG_BAR
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_snackbar)
            val hPad = (16 * density).toInt()
            val vPad = (11 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = 8 * density
        }

        val tvMsg = TextView(ctx).apply {
            text = message
            setTextColor(COLOR_MESSAGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = interFont ?: typeface
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvMsg)

        if (actionLabel != null && onAction != null) {
            val btnAction = TextView(ctx).apply {
                text = actionLabel
                setTextColor(COLOR_ACTION)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = interFont ?: typeface
                setTypeface(typeface, Typeface.BOLD)
                // Borderless ripple so the blue label also reacts to touch.
                val tv = android.util.TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
                    setBackgroundResource(tv.resourceId)
                }
                val hPad = (14 * density).toInt()
                val vPad = (6 * density).toInt()
                setPadding(hPad, vPad, (6 * density).toInt(), vPad)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (8 * density).toInt()
                layoutParams = lp
                setOnClickListener {
                    TransientBottomBarAnimator.dismiss(bar) { onAction.run() }
                }
            }
            bar.addView(btnAction)
        }

        return bar
    }

    // ---------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------

    private fun attach(root: ViewGroup, bar: View, lp: ViewGroup.LayoutParams, durationMs: Long) {
        TransientBottomBarAnimator.show(root, bar, lp, TAG_BAR, durationMs)
    }

    private fun hostLayoutParams(root: ViewGroup, bottomMarginPx: Int, density: Float): ViewGroup.LayoutParams {
        val sideMargin = (10 * density).toInt()
        return if (root is ConstraintLayout) {
            ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomMargin = bottomMarginPx
                marginStart = sideMargin
                marginEnd = sideMargin
            }
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = bottomMarginPx
                marginStart = sideMargin
                marginEnd = sideMargin
            }
        }
    }

    /** Floats the toast above the bottom nav + global mini player when they are visible. */
    @JvmStatic
    fun computeBottomInset(activity: Activity, density: Float): Int {
        var margin = (14 * density).toInt()
        val bottomNav = activity.findViewById<View>(R.id.bottomNavigation)
        if (bottomNav != null && bottomNav.visibility == View.VISIBLE) margin += bottomNav.height
        val miniPlayer = activity.findViewById<View>(R.id.llGlobalMiniPlayer)
        if (miniPlayer != null && miniPlayer.visibility == View.VISIBLE) margin += miniPlayer.height
        return margin
    }
}
