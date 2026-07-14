package com.example.sleppify

import java.util.Locale

/**
 * Única fuente de verdad para el texto secundario de una canción en TODA la app.
 *
 * Los subtítulos crudos de YouTube Music llegan como un solo string con segmentos
 * separados por "•" en orden y composición variables: a veces "Canción • Artista •
 * Álbum • 3:22 • 500 M de reproducciones", a veces "Artista • 3:22", a veces solo
 * "3:22", y a veces el nombre de la canción repetido como si fuera el álbum. Cada
 * pantalla tenía su propio parser ad-hoc, lo que producía filas sin duración, players
 * mostrando "Artista • 3:22 • 500 M…" y subtítulos con el título repetido.
 *
 * Contrato de presentación:
 *  - Fila de canción (cualquier lista): [forRow] → "artista • álbum • duración • vistas"
 *    (álbum/vistas solo si existen; siempre en ese orden; el título NUNCA se repite).
 *  - Player y miniplayer: [artistOnly] → solo el nombre del artista.
 */
object SongSubtitle {

    /** Segmentos estructurados extraídos de un subtítulo crudo de YTM. */
    class Parts(
        @JvmField val artist: String,
        @JvmField val album: String,
        @JvmField val duration: String,
        @JvmField val views: String
    )

    const val SEPARATOR = " • "

    private val DURATION_REGEX = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")
    // Etiquetas de tipo que YTM antepone al subtítulo ("Canción • …"). Nunca son artista/álbum.
    private val TYPE_LABELS = setOf(
        "song", "video", "album", "playlist", "single", "ep",
        "canción", "cancion", "álbum", "artist", "artista", "sencillo"
    )
    // Conteo con palabra de magnitud ("1.2M", "500 mil", "3,570 M"). Anclado al final (sin
    // ".*" final): la magnitud debe ser lo último del segmento, para no tragarse un álbum
    // como "24K Magic" (número + palabra magnitud + palabra real) ni un artista numérico
    // ("311", "112").
    private val COUNT_SEGMENT_REGEX = Regex("\\d[\\d.,]*\\s*(mil|millones|mill|k|m|b)\\.?", RegexOption.IGNORE_CASE)
    // Año suelto ("2019"). Un año-de-lanzamiento aparece DESPUÉS del álbum, así que solo se
    // descarta cuando ya hay artista + álbum; el primer segmento tras el artista se respeta
    // como álbum aunque sea un año (álbumes titulados "1989", "2001", "1984").
    private val YEAR_REGEX = Regex("(19|20)\\d{2}")
    private val VIEW_WORDS = listOf(
        "reproduc", "plays", "views", "vistas", "visualizaciones", "oyentes",
        "suscriptores", "subscribers"
    )

    /**
     * Descompone un subtítulo crudo de YTM en partes estructuradas.
     *
     * @param rawSubtitle subtítulo crudo ("Canción • Artista • Álbum • 3:22 • 500 M…").
     *   Si ya es solo un nombre de artista, vuelve intacto en [Parts.artist].
     * @param title título de la canción; los segmentos idénticos a él se descartan
     *   (YTM suele repetir el título como pseudo-álbum).
     * @param explicitDuration duración de campo dedicado (fixedColumn); tiene prioridad
     *   sobre una duración embebida en el subtítulo.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(rawSubtitle: String?, title: String? = null, explicitDuration: String? = null): Parts {
        val raw = rawSubtitle?.trim().orEmpty()
        val normTitle = title?.trim().orEmpty()
        var duration = sanitizeDuration(explicitDuration)
        var views = ""
        val nameSegments = ArrayList<String>(2)

        val segments = if (raw.isEmpty()) emptyList()
            else raw.split("•").map { it.trim() }.filter { it.isNotEmpty() }

        // Single-segment fast path: a field with no "•" is almost always an already-clean
        // artist ("Bad Bunny", or even a one-word band like "Video"/"Album" that would
        // otherwise be nuked by the type-label filter). Keep it verbatim as the artist —
        // the ONLY thing still filtered here is a bare duration ("3:22"), so the player
        // never shows a length in place of an artist.
        if (segments.size == 1) {
            val only = segments[0]
            return when {
                DURATION_REGEX.matches(only) ->
                    Parts("", "", if (duration.isEmpty()) only else duration, "")
                else -> Parts(stripTopicSuffix(only), "", duration, "")
            }
        }

        for (seg in segments) {
            val lower = seg.lowercase(Locale.ROOT)
            when {
                DURATION_REGEX.matches(seg) -> if (duration.isEmpty()) duration = seg
                isViewsSegment(seg, lower) -> if (views.isEmpty()) views = seg
                lower in TYPE_LABELS -> { /* etiqueta de tipo: fuera */ }
                normTitle.isNotEmpty() && seg.equals(normTitle, ignoreCase = true) -> { /* título repetido: fuera */ }
                nameSegments.size >= 2 && YEAR_REGEX.matches(seg) -> { /* año de lanzamiento tras artista+álbum: fuera */ }
                else -> nameSegments.add(stripTopicSuffix(seg))
            }
        }

        val artist = nameSegments.getOrElse(0) { "" }
        // Todo lo que queda entre artista y duración es el álbum; si YTM lo partiera en
        // varios segmentos se reunifica para no perder información.
        val album = if (nameSegments.size > 1) {
            nameSegments.subList(1, nameSegments.size).joinToString(SEPARATOR)
        } else ""
        return Parts(artist, album, duration, views)
    }

    /**
     * Subtítulo estándar de fila: "artista • álbum • duración • vistas" (solo las partes
     * presentes, siempre en ese orden). Para CUALQUIER fila de canción de la app.
     */
    @JvmStatic
    @JvmOverloads
    fun forRow(rawSubtitle: String?, title: String? = null, explicitDuration: String? = null): String {
        return format(parse(rawSubtitle, title, explicitDuration))
    }

    /** Igual que [forRow] pero desde partes ya estructuradas (p. ej. store local). */
    @JvmStatic
    @JvmOverloads
    fun forRowParts(artist: String?, duration: String?, album: String? = null, views: String? = null): String {
        // El campo "artist" persistido puede venir contaminado de versiones anteriores
        // ("Artista • 3:22"): se re-parsea para que el orden final quede garantizado.
        val parts = parse(artist, null, duration)
        val album2 = album?.trim().orEmpty()
        val views2 = views?.trim().orEmpty()
        return format(
            Parts(
                parts.artist,
                if (album2.isNotEmpty()) album2 else parts.album,
                parts.duration,
                if (views2.isNotEmpty()) views2 else parts.views
            )
        )
    }

    /** Une las partes presentes en el orden canónico. */
    @JvmStatic
    fun format(parts: Parts): String {
        val out = StringBuilder()
        appendPart(out, parts.artist)
        appendPart(out, parts.album)
        appendPart(out, parts.duration)
        appendPart(out, parts.views)
        return out.toString()
    }

    /**
     * SOLO el nombre del artista — el único texto permitido bajo el título en el player
     * y el miniplayer. Limpia campos contaminados tipo "Artista • 3:22 • 500 M…".
     */
    @JvmStatic
    @JvmOverloads
    fun artistOnly(rawArtistOrSubtitle: String?, title: String? = null): String {
        return parse(rawArtistOrSubtitle, title).artist
    }

    private fun appendPart(out: StringBuilder, part: String) {
        if (part.isEmpty()) return
        if (out.isNotEmpty()) out.append(SEPARATOR)
        out.append(part)
    }

    private fun isViewsSegment(seg: String, lower: String): Boolean {
        // Palabra de conteo + dígito ("500 M de reproducciones"). El dígito es obligatorio
        // para no descartar una banda literalmente llamada "Vistas" o "Plays".
        val hasDigit = seg.any { it.isDigit() }
        if (hasDigit && VIEW_WORDS.any { lower.contains(it) }) return true
        return COUNT_SEGMENT_REGEX.matches(seg)
    }

    private fun stripTopicSuffix(value: String): String {
        return if (value.endsWith(" - Topic")) value.removeSuffix(" - Topic").trim() else value
    }

    private fun sanitizeDuration(value: String?): String {
        val v = value?.trim().orEmpty()
        // "--:--" es el placeholder de duración desconocida: jamás se muestra en una fila.
        if (v == "--:--" || v == "0:00") return ""
        return if (DURATION_REGEX.matches(v)) v else ""
    }
}
