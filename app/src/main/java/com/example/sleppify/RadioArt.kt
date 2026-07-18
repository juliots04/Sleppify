package com.example.sleppify

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * Punto único para "cómo se ve una radio" fuera del compositor: el arte Frost ([RadioArtComposer])
 * — UNA carátula central + (en tarjetas grandes) el panel esmerilado con "RADIO" + título. La MISMA
 * receta en todas las superficies (carruseles del home, header de detalle, filas de biblioteca /
 * Descargas, bottom sheets). Espejo del patrón de [FavoritesArt].
 *
 * El diseño anterior (3 círculos + barras + campo de color, con resolución de laterales y color de
 * Palette) fue eliminado por completo: esta clase ya no resuelve lados ni color.
 */
object RadioArt {

    /** True para ids de radio/mix de YouTube Music ("RD…"), EXCLUYENDO las playlists autogeneradas
     *  ("RDCLAK…": portada fija + tracklist fijo, nunca una tarjeta de radio). */
    @JvmStatic
    fun isRadioId(playlistId: String?): Boolean {
        val pid = playlistId?.trim() ?: return false
        return pid.startsWith("RD") && !pid.startsWith("RDCLAK")
    }

    /**
     * Bind del arte de radio en una fila/sheet/thumb: carátula Frost vía [RadioArtComposer.load].
     * En thumbs pequeños el compositor omite el panel automáticamente (solo carátula). Las ramas
     * NO-radio del mismo método DEBEN llamar a [unbind].
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
        // PlaylistGridArtLoader firman tag_artwork_signature), cancelarlo para que una petición en
        // vuelo no pise el composite después.
        if (iv.getTag(R.id.tag_artwork_signature) != null) {
            iv.setTag(R.id.tag_artwork_signature, null)
            try { Glide.with(iv).clear(iv) } catch (_: Throwable) {}
            // Glide.clear vació el drawable: invalidar la firma del composite para que
            // RadioArtComposer.load no haga early-return dejando la vista en blanco.
            iv.setTag(R.id.tag_radio_art_sig, null)
        }
        // El composite es un cuadrado opaco full-bleed; fitXY llena el thumb y el fondo neutro hace
        // de placeholder mientras se construye.
        iv.scaleType = ImageView.ScaleType.FIT_XY
        iv.setBackgroundColor(0xFF282A30.toInt())

        // Mismo center que compone el home (entry.songThumbnail) para acertar el disk cache del
        // compositor; solo sin entrada guardada se cae al thumbnail que trae el caller.
        val entry = findEntry(ctx, radioId)
        val center = entry?.songThumbnail?.takeIf { it.isNotEmpty() }
            ?: fallbackCenterUrl?.trim().orEmpty()
        val title = entry?.songTitle?.takeIf { it.isNotEmpty() }.orEmpty()

        // MISMO diseño Frost que el home (carátula + panel "RADIO" + título) también aquí; el
        // compositor omite el panel solo si el tamaño es diminuto (< LABEL_MIN_PX).
        RadioArtComposer.load(iv, radioId, center, title, sizePx, showLabel = true)
        badge?.visibility = View.GONE
    }

    /**
     * Reset para binds NO-radio de una vista que pudo mostrar una radio: invalida la firma del
     * composite para que un compose en vuelo no pinte una vista ya rebindada y oculta la insignia.
     * El caller pinta su propio arte/fondo después.
     */
    @JvmStatic
    @JvmOverloads
    fun unbind(iv: ImageView, badge: View? = null) {
        iv.setTag(R.id.tag_radio_art_sig, null)
        badge?.visibility = View.GONE
    }

    private fun findEntry(ctx: Context, radioId: String): RadioHistoryStore.RadioEntry? =
        try {
            RadioHistoryStore.getRadios(ctx).firstOrNull { it.radioPlaylistId == radioId }
        } catch (_: Throwable) {
            null
        }
}
