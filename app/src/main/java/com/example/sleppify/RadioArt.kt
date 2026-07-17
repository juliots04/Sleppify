package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Punto único para "cómo se ve una radio" fuera del compositor: el diseño completo es el composite
 * de 3 círculos ([RadioArtComposer]) sobre el campo fluorescente plano
 * ([RadioArtComposer.cardBackgroundColor]) más la insignia "RADIO" como TextView overlay — la MISMA
 * receta en todos los lugares donde aparece una radio (carruseles del home, header de detalle,
 * filas de biblioteca/Descargas, bottom sheets). Espejo del patrón de [FavoritesArt].
 *
 * Centraliza lo que antes estaba triplicado entre PrincipalFragment y PlaylistDetailFragment:
 * el predicado RD…, la resolución de los thumbnails laterales y el color raw de Palette, todo
 * compartido vía las MISMAS claves de prefs ("sleppify_radio_ui_cache": sides_/color_/colorurl_)
 * para que cada superficie pinte el campo y las caras idénticos.
 */
object RadioArt {

    /** Prefs file compartido con PrincipalFragment/PlaylistDetailFragment (estado visual por radio). */
    private const val PREFS = "sleppify_radio_ui_cache"

    /** Fallback raw Palette color for a radio rendered before any surface has cached one
     *  (e.g. straight from the player). Violet so cardBackgroundColor lands on the liked-violet
     *  field instead of a jarring grey; replaced in-place once the async Palette resolves. */
    const val DEFAULT_RAW = 0xFF6C4CF5.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Peticiones Palette en vuelo, por radioId → callbacks esperando el resultado. Garantiza UNA
     * sola petición concurrente por radio (reemplaza el guard radioHeaderColorRequested que antes
     * vivía por-fragment): binds simultáneos (fila + header) se cuelgan de la misma petición y
     * todos reciben su callback al resolverse.
     */
    private val pendingColor = HashMap<String, MutableList<Runnable>>()

    /** True for YouTube Music radio/mix ids ("RD…"), EXCLUDING auto-generated playlists
     *  ("RDCLAK…": static cover + fixed tracklist, never a radio card). Single source of the
     *  predicate that used to diverge between fragments. */
    @JvmStatic
    fun isRadioId(playlistId: String?): Boolean {
        val pid = playlistId?.trim() ?: return false
        return pid.startsWith("RD") && !pid.startsWith("RDCLAK")
    }

    /**
     * Two side-thumbnail URLs for the radio composite, as [left, right] (either may be ""). Prefers
     * the pair the home carousel already computed + persisted (so every surface shows the same
     * faces); otherwise picks the first two other tracks of the saved radio and persists the pair
     * in the same "sides_<id>" format PrincipalFragment writes.
     */
    @JvmStatic
    fun resolveSides(ctx: Context, radioId: String, centerUrl: String?): Array<String> =
        resolveSides(ctx, radioId, findEntry(ctx, radioId), centerUrl)

    private fun resolveSides(
        ctx: Context,
        radioId: String,
        entry: RadioHistoryStore.RadioEntry?,
        centerUrl: String?
    ): Array<String> {
        val cached = prefs(ctx).getString("sides_$radioId", "") ?: ""
        if (cached.isNotEmpty()) {
            val parts = cached.split("\n")
            val left = parts.getOrNull(0)?.trim().orEmpty()
            val right = parts.getOrNull(1)?.trim().orEmpty()
            if (left.isNotEmpty()) return arrayOf(left, right)
        }
        var left = ""
        var right = ""
        if (entry != null) {
            for (t in entry.tracks) {
                val u = t.thumbnailUrl
                if (u.isEmpty() || u == centerUrl) continue
                if (left.isEmpty()) {
                    left = u
                } else {
                    right = u
                    break
                }
            }
        }
        if (left.isNotEmpty()) {
            // Mismo formato \n-joined que persistSideUrls en el home.
            prefs(ctx).edit().putString("sides_$radioId", "$left\n$right").apply()
        }
        return arrayOf(left, right)
    }

    /** Raw Palette color persisted for this radio (shared with home/header), or 0 = not yet
     *  resolved — callers substitute [DEFAULT_RAW] and may kick [ensureRawColor]. */
    @JvmStatic
    fun rawColor(ctx: Context, radioId: String): Int =
        prefs(ctx).getInt("color_$radioId", 0)

    /**
     * Resolves + persists the raw Palette color for [radioId] from [centerUrl] (async, 64x64
     * Glide decode → darkMuted/muted). [onResolved] runs on the main thread once the color is in
     * prefs; concurrent calls for the same radio share ONE request and all get their callback.
     * No-op sin URL (el caller se queda con el violeta por defecto, igual que antes).
     */
    @JvmStatic
    fun ensureRawColor(ctx: Context, radioId: String, centerUrl: String?, onResolved: Runnable?) {
        val appCtx = ctx.applicationContext
        if (rawColor(appCtx, radioId) != 0) {
            // Ya resuelto (posiblemente por otra superficie entre el rawColor() del caller y esta
            // llamada): notificar igualmente para que repinte con el color real.
            if (onResolved != null) mainHandler.post(onResolved)
            return
        }
        val url = centerUrl?.trim().orEmpty()
        if (url.isEmpty()) return
        synchronized(pendingColor) {
            val waiting = pendingColor[radioId]
            if (waiting != null) {
                // Petición ya en vuelo: colgarse de ella en vez de duplicar el Palette pass.
                if (onResolved != null) waiting.add(onResolved)
                return
            }
            pendingColor[radioId] = if (onResolved != null) mutableListOf(onResolved) else mutableListOf()
        }
        try {
            Glide.with(appCtx)
                .asBitmap()
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(64, 64)
                .centerCrop()
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        Palette.from(resource).generate { palette ->
                            val dominant = RadioArtComposer.pickRawColor(palette)
                            prefs(appCtx).edit()
                                .putInt("color_$radioId", dominant)
                                .putString("colorurl_$radioId", url)
                                .apply()
                            drainPending(radioId)
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        // Sin bitmap no hay color: soltar el guard para que un bind futuro reintente.
                        synchronized(pendingColor) { pendingColor.remove(radioId) }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        } catch (_: Exception) {
            synchronized(pendingColor) { pendingColor.remove(radioId) }
        }
    }

    private fun drainPending(radioId: String) {
        val waiting = synchronized(pendingColor) { pendingColor.remove(radioId) } ?: return
        for (r in waiting) r.run()
    }

    /**
     * One-call bind of the full radio design onto a row/sheet thumb: fluorescent field as the view
     * background, 3-circle composite via [RadioArtComposer.load], and the optional "RADIO" [badge]
     * overlay shown. Async color resolution repaints the field in place (guarded by
     * R.id.tag_radio_bg_color so a rebound view is never painted with a stale radio's color).
     * Non-radio branches of the same bind method MUST call [unbind].
     */
    @JvmStatic
    @JvmOverloads
    fun bindThumb(
        iv: ImageView,
        radioId: String,
        fallbackCenterUrl: String?,
        sizePx: Int,
        badge: View? = null
    ) {
        val ctx = iv.context ?: return
        // Higiene de tags: si la vista traía un load plano de Glide (loadArtworkInto /
        // PlaylistGridArtLoader firman tag_artwork_signature), cancelarlo para que una petición
        // en vuelo no pise el composite después.
        if (iv.getTag(R.id.tag_artwork_signature) != null) {
            iv.setTag(R.id.tag_artwork_signature, null)
            try {
                Glide.with(iv).clear(iv)
            } catch (_: Throwable) {
            }
            // Glide.clear vació el drawable: invalidar la firma del composite para que
            // RadioArtComposer.load no haga early-return dejando la vista en blanco.
            iv.setTag(R.id.tag_radio_art_sig, null)
        }
        // Sello para el repaint asíncrono del campo: solo repinta si la vista sigue en ESTA radio.
        iv.setTag(R.id.tag_radio_bg_color, radioId)
        // El composite es un cuadrado opaco full-bleed (diseño YT Music: degradado + barras +
        // círculos); fitXY llena el thumb y el campo degradado del fondo hace de placeholder
        // idéntico mientras el composite se construye.
        iv.scaleType = ImageView.ScaleType.FIT_XY

        // Mismo center que compone el home (entry.songThumbnail) para acertar el disk cache del
        // compositor; solo sin entrada guardada se cae al thumbnail que trae el caller.
        val entry = findEntry(ctx, radioId)
        val center = entry?.songThumbnail?.takeIf { it.isNotEmpty() }
            ?: fallbackCenterUrl?.trim().orEmpty()

        val raw = rawColor(ctx, radioId)
        val effective = if (raw != 0) raw else DEFAULT_RAW
        iv.background = RadioArtComposer.fieldDrawable(effective)

        val sides = resolveSides(ctx, radioId, entry, center)
        RadioArtComposer.load(iv, radioId, center, sides[0], sides[1], sizePx, effective)

        if (raw == 0) {
            ensureRawColor(ctx, radioId, center, Runnable {
                if (iv.getTag(R.id.tag_radio_bg_color) == radioId) {
                    val ivCtx = iv.context ?: return@Runnable
                    val resolved = rawColor(ivCtx, radioId)
                    if (resolved != 0) {
                        iv.background = RadioArtComposer.fieldDrawable(resolved)
                        // El color entra en la firma: recompone el arte con la paleta real.
                        RadioArtComposer.load(iv, radioId, center, sides[0], sides[1], sizePx, resolved)
                    }
                }
            })
        }
        // Diseño nuevo: sin insignia "RADIO" en ninguna superficie (la tarjeta ya se identifica
        // por su arte de onda + círculos).
        badge?.visibility = View.GONE
    }

    /**
     * Reset for NON-radio binds of a view that may previously have shown a radio: invalida la firma
     * del composite (RadioArtComposer.load solo compara tag_radio_art_sig, nada más lo limpia) para
     * que un compose en vuelo no pinte una vista ya rebindada, suelta el sello de color y oculta la
     * insignia. El caller pinta su propio arte/fondo después.
     */
    @JvmStatic
    @JvmOverloads
    fun unbind(iv: ImageView, badge: View? = null) {
        iv.setTag(R.id.tag_radio_art_sig, null)
        iv.setTag(R.id.tag_radio_bg_color, null)
        badge?.visibility = View.GONE
    }

    private fun findEntry(ctx: Context, radioId: String): RadioHistoryStore.RadioEntry? =
        try {
            RadioHistoryStore.getRadios(ctx).firstOrNull { it.radioPlaylistId == radioId }
        } catch (_: Throwable) {
            null
        }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
