package fr.triche.cubes.logic

/** Classes de couleur d'un pixel de l'image réduite. */
object Pix {
    const val NONE: Byte = 0
    const val PLAT: Byte = 1      // plateforme orange
    const val TOP: Byte = 2       // dessus clair d'une boîte
    const val TAN: Byte = 3       // autres faces des boîtes (marron-jaune)

    /** Classe du pixel (r, g, b entre 0 et 255). */
    fun classify(r: Int, g: Int, b: Int): Byte {
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        if (b >= g || b >= r || mx < 110) return NONE          // fond bleu du jeu, ombres : écarté vite
        val d = (mx - mn).toFloat()
        val s = d / mx
        if (s < 0.45f) return NONE
        // teinte en degrés (le rouge ou le vert domine ici)
        val h = if (mx == r) 60f * (g - b) / d else 60f * (2f + (b - r) / d)
        val hue = if (h < 0f) h + 360f else h
        val v = mx / 255f
        if (hue >= 3f && hue < 23f) return if (s > 0.6f && v > 0.55f) PLAT else NONE
        if (hue >= 23f && hue < 52f) {
            if (v > 0.80f && s > 0.5f && hue >= 28f && hue < 48f) return TOP
            if (v > 0.5f) return TAN
        }
        return NONE
    }
}

/** Image réduite où chaque pixel est remplacé par sa classe ([Pix]). */
class ClassGrid(val w: Int, val h: Int) {
    val c = ByteArray(w * h)
}
