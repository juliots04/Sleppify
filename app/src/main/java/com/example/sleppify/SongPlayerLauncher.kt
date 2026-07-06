package com.example.sleppify

import android.os.SystemClock
import androidx.fragment.app.FragmentActivity

/**
 * Single entry point for "tap a song → play it in the full player".
 *
 * The find-or-create + replace-queue + transaction pattern used to be hand-rolled at ~20 call
 * sites across the app, each with its own subset of guards. This launcher centralizes it:
 * - reuses the existing SongPlayerFragment (replacing its queue) or creates it under the
 *   canonical tag,
 * - runs the standard show/add transaction with the enter animation,
 * - skips commits after state save (crash guard),
 * - debounces double-taps so a second tap can't add a duplicate fragment under the same tag.
 */
object SongPlayerLauncher {

    private const val LAUNCH_DEBOUNCE_MS = 400L

    @Volatile
    private var lastLaunchAtMs = 0L

    /**
     * @param openPlayerUi true: slide the full player in (mini player animates out).
     *                     false: start playback but stay on the current screen — the fragment is
     *                     added hidden and only the mini player reflects it (search-style flow).
     * @param fromStart when reusing an existing player, use externalReplaceQueueFromStart
     *                  (forces the selected track to restart from 0) instead of externalReplaceQueue.
     */
    @JvmStatic
    @JvmOverloads
    fun open(
        activity: FragmentActivity?,
        ids: ArrayList<String>,
        titles: ArrayList<String>,
        artists: ArrayList<String>,
        durations: ArrayList<String>,
        images: ArrayList<String>,
        startIndex: Int,
        startPlaying: Boolean,
        returnTargetTag: String?,
        openPlayerUi: Boolean,
        fromStart: Boolean = false
    ): SongPlayerFragment? {
        if (activity == null || activity.isFinishing || ids.isEmpty()) return null
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return null

        val existing = fm.findFragmentByTag(AppConstants.TAG_SONG_PLAYER) as? SongPlayerFragment

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAtMs < LAUNCH_DEBOUNCE_MS) return existing
        lastLaunchAtMs = now

        val mini = (activity as? MainActivity)?.getGlobalMiniPlayer()
        if (openPlayerUi) mini?.animateOut()

        val safeIndex = startIndex.coerceIn(0, ids.size - 1)

        if (existing != null && existing.isAdded) {
            if (returnTargetTag != null) existing.externalSetReturnTargetTag(returnTargetTag)
            if (fromStart) {
                existing.externalReplaceQueueFromStart(ids, titles, artists, durations, images, safeIndex, startPlaying)
            } else {
                existing.externalReplaceQueue(ids, titles, artists, durations, images, safeIndex, startPlaying)
            }
            if (openPlayerUi) {
                fm.beginTransaction()
                    .setReorderingAllowed(true)
                    .show(existing)
                    .runOnCommit { existing.externalAnimateEnterSlide() }
                    .commit()
            } else {
                mini?.updateUi()
            }
            return existing
        }

        val player = SongPlayerFragment.newInstance(
            ids, titles, artists, durations, images, safeIndex, startPlaying
        )
        if (returnTargetTag != null) player.externalSetReturnTargetTag(returnTargetTag)
        val tx = fm.beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.playerContainer, player, AppConstants.TAG_SONG_PLAYER)
        if (openPlayerUi) {
            tx.runOnCommit { player.externalAnimateEnterSlide() }
        } else {
            tx.hide(player)
            tx.runOnCommit { mini?.updateUi() }
        }
        tx.commit()
        return player
    }
}
