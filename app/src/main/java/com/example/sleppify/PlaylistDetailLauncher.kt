package com.example.sleppify

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * The single, normalized entry point for opening [PlaylistDetailFragment].
 *
 * Before this existed, every caller (home carousels, library rows, artist albums, the player's
 * "view queue" + "start radio" buttons, cold-start restore) hand-rolled its own newInstance +
 * FragmentTransaction, and each drifted: some skipped the loading overlay, some skipped hiding the
 * top bar, one skipped remove-existing (leaving two live detail instances under one tag), and the
 * liked-id normalization was copy-pasted in four places with a title-substring heuristic that could
 * hijack a user playlist literally named "Me gusta el pop". This object centralizes all of that:
 *
 *  - id normalization: id-based liked collapse (LL/LM/VLLL…/VLLM… → the special liked id) and a
 *    generic leading-'VL' strip for browse-derived ids (VLPL… → PL…). No title heuristic.
 *  - radio canonicalization: builds the RDAMVM id from a seed videoId and strips the three title
 *    conventions ("Radio: X" / "X Radio" / "X") down to a clean seed title.
 *  - the transaction: remove-existing + add + addToBackStack under the one "playlist_detail" tag,
 *    with an opt-in reuse-if-alive branch for the player's "view queue playlist" button.
 *  - the chrome: MainActivity.showModuleLoadingOverlay() + hideTopAppBarForPlaylistDetail() on
 *    EVERY path.
 *
 * The OAuth token arg is dropped — PlaylistDetailFragment.resolveYoutubeAccessToken("") already
 * recovers it from prefs. HD-thumbnail derivation is intentionally NOT done here: the header binder
 * derives HD from whatever URL it gets AND keeps the raw URL as its Glide .error() fallback, so
 * pre-rewriting the URL in the launcher would poison that fallback chain.
 */
object PlaylistDetailLauncher {

    private const val TAG = "playlist_detail"
    // Not a const val: R.id fields aren't Kotlin compile-time constants.
    private val CONTAINER_ID = R.id.fragmentContainer

    /**
     * Opens (or reuses) the playlist/radio detail screen.
     *
     * @param fragmentManager the SAME manager the caller previously used (all detail fragments live
     *   in the activity's manager at [CONTAINER_ID]); passed explicitly so the launcher can never
     *   target the wrong container.
     * @param seedVideoId when non-blank, forces RADIO handling: the RDAMVM id and a clean title are
     *   derived internally (used by the "start radio for this track" call sites).
     * @param reuseIfSameId when true and a live detail already exists, it is shown instead of
     *   recreated (the player's "view queue playlist" semantics). Every other caller recreates.
     * @param removeExisting normally true (recreate: remove the old detail, add the new one). Pass
     *   false when the CALLER is itself the current detail (starting a radio from a track row): the
     *   old detail must stay alive so the caller's in-flight work — e.g. the radio-save callback that
     *   still holds its context — doesn't crash, and back returns to the live screen instantly.
     */
    @JvmStatic
    @JvmOverloads
    fun open(
        activity: FragmentActivity?,
        fragmentManager: FragmentManager,
        id: String?,
        title: String? = null,
        subtitle: String? = null,
        thumbnailUrl: String? = null,
        seedVideoId: String? = null,
        reuseIfSameId: Boolean = false,
        removeExisting: Boolean = true,
        // navigationEndpoint token from the opening home card. Passed straight through to the
        // fragment so YT-generated mixes/recaps can be resolved (empty for every other caller).
        // Added LAST so @JvmOverloads keeps every existing Java call arity valid.
        params: String? = null,
        // Art hint: when [PlaylistDetailFragment.ART_HINT_SINGLE] the header clones this card's ONE
        // cover instead of guessing (no 2x2, no 3-circle radio) — used by the single-cover carousels
        // (recomendadas/Recaps) and recaps in "recientes". Empty for every id-based caller. Added
        // LAST (after params) so @JvmOverloads keeps every existing Java call arity valid.
        artHint: String? = null,
        // Vista "Descargas": el detalle filtra a solo canciones descargadas y suma "N descargadas"
        // al header. Añadido ÚLTIMO por @JvmOverloads; los callers Java usan [openDownloadedOnly].
        downloadedOnly: Boolean = false
    ) {
        if (activity == null || activity.isFinishing) return
        if (fragmentManager.isStateSaved) return

        val rawId = id?.trim().orEmpty()
        val seed = seedVideoId?.trim().orEmpty()
        if (rawId.isEmpty() && seed.isEmpty()) return

        val isRadio = seed.isNotEmpty() || rawId.startsWith("RD")

        val normId: String
        val normTitle: String
        if (isRadio) {
            normId = when {
                rawId.startsWith("RD") -> rawId
                seed.isNotEmpty() -> "RDAMVM$seed"
                else -> rawId
            }
            normTitle = canonicalRadioTitle(title?.trim().orEmpty())
        } else {
            normId = normalizeOpenId(rawId)
            normTitle = title?.trim().orEmpty()
        }
        // Thumbnail is passed through raw on purpose (see class doc): the header derives HD itself
        // and needs the raw URL as its .error() fallback.
        val normThumb = thumbnailUrl?.trim().orEmpty()
        val normSubtitle = subtitle?.trim().orEmpty()

        val existing = fragmentManager.findFragmentByTag(TAG)

        showChrome(activity)

        if (reuseIfSameId && existing is PlaylistDetailFragment && existing.isAdded) {
            // Reuse the live instance (the player button reuses rather than rebuilds the queue's
            // detail). No id check: the caller only triggers this when the showing detail IS the
            // queue's playlist, matching the pre-refactor behavior.
            fragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .show(existing)
                .commit()
            return
        }

        // token dropped: resolveYoutubeAccessToken("") falls back to prefs.
        val fragment = PlaylistDetailFragment.newInstance(
            normId, normTitle, normSubtitle, normThumb, "",
            params?.trim().orEmpty(), artHint?.trim().orEmpty(), downloadedOnly
        )
        val tx = fragmentManager.beginTransaction().setReorderingAllowed(true)
        if (removeExisting && existing != null && existing.isAdded) {
            tx.remove(existing)
        }
        tx.add(CONTAINER_ID, fragment, TAG)
            .addToBackStack(TAG)
            .commit()
    }

    /**
     * Abre el detalle en modo "solo descargadas" (rows de la pastilla Descargas). Helper dedicado
     * porque los callers Java no pueden alcanzar el último default de [open] sin pasar todos los
     * anteriores. El subtítulo va neutro a propósito: el "10 descargadas" de la row NO debe viajar
     * como subtítulo (parseMeta lo usaría como ownerLabel del header).
     */
    @JvmStatic
    fun openDownloadedOnly(
        activity: FragmentActivity?,
        fragmentManager: FragmentManager,
        id: String?,
        title: String? = null,
        thumbnailUrl: String? = null
    ) {
        open(
            activity, fragmentManager, id, title,
            subtitle = "",
            thumbnailUrl = thumbnailUrl,
            downloadedOnly = true
        )
    }

    private fun showChrome(activity: FragmentActivity) {
        (activity as? MainActivity)?.let {
            it.showModuleLoadingOverlay()
            it.hideTopAppBarForPlaylistDetail()
        }
    }

    /**
     * Id-based type normalization ONLY (no title heuristic). Collapses the liked-collection id
     * variants to the special liked id and strips a generic leading 'VL' from browse-derived ids.
     */
    @JvmStatic
    fun normalizeOpenId(rawId: String): String {
        val id = rawId.trim()
        if (id == YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
            || id == "LL" || id == "LM"
            || id.startsWith("VLLL") || id.startsWith("VLLM")
        ) {
            return YouTubeMusicService.SPECIAL_LIKED_VIDEOS_ID
        }
        // Browse ids arrive 'VL'-prefixed (VLPL…→PL…); the OAuth Data API wants the bare playlist id.
        if (id.length > 2 && id.startsWith("VL")) {
            return id.substring(2)
        }
        return id
    }

    /** Strips the three radio title conventions down to the bare seed title. */
    @JvmStatic
    fun canonicalRadioTitle(rawTitle: String): String {
        var s = rawTitle.trim()
        if (s.startsWith("Radio: ")) s = s.substring(7).trim()
        if (s.endsWith(" Radio")) s = s.substring(0, s.length - 6).trim()
        return s
    }
}
