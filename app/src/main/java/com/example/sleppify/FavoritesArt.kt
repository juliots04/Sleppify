package com.example.sleppify

import android.graphics.Color
import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * Portada personalizada de la playlist Favoritos: degradado cálido + gato blanco, la MISMA en
 * todos los lugares donde aparece la playlist (biblioteca, header de detalle, sheets de guardar,
 * carruseles del home). Espejo del patrón que ya usa "Música que te gustó"
 * (bg_music_liked_gradient + ic_thumb_up_liked).
 */
object FavoritesArt {

    @JvmStatic
    fun isFavorites(playlistId: String?): Boolean =
        FavoritesPlaylistStore.PLAYLIST_ID == playlistId?.trim()

    /** Thumb pequeño (filas/sheets, ~48-56dp): icono a tamaño intrínseco centrado. */
    @JvmStatic
    fun bindCover(iv: ImageView) {
        try {
            Glide.with(iv).clear(iv)
        } catch (_: Throwable) {
        }
        iv.setBackgroundResource(R.drawable.bg_music_favorites_gradient)
        iv.setImageResource(R.drawable.ic_cat_white)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setColorFilter(Color.WHITE)
    }

}
