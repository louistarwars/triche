import fr.triche.timesup.logic.Frame
import java.nio.ByteBuffer

/**
 * Écran synthétique du jeu (720×1594), construit d'après les mesures sur la vidéo : barre de 623 px dont la partie pleine
 * (dégradé cyan → bleu) descend à vitesse constante, halo au-dessus du bord, effacement progressif entre 25 % et 50 % de
 * la durée, puis texte du résultat qui apparaît en fondu et bouton « niveau suivant ».
 */
class Synth(val w: Int = 720, val h: Int = 1594) {
    val bytes = ByteArray(w * h * 4)
    val frame = Frame(ByteBuffer.wrap(bytes), w, h, w * 4, 4)
    val topFull = 617.45
    val bottom = 1240.5
    val x0 = 255
    val x1 = 464

    init { fillRect(0, 0, w, h, 25, 25, 64) }

    private fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
        val o = (y * w + x) * 4
        bytes[o] = r.toByte(); bytes[o + 1] = g.toByte(); bytes[o + 2] = b.toByte(); bytes[o + 3] = 0xFF.toByte()
    }

    fun fillRect(xa: Int, ya: Int, xb: Int, yb: Int, r: Int, g: Int, b: Int) {
        for (y in ya until yb) for (x in xa until xb) put(x, y, r, g, b)
    }

    /** Partie pleine : bord haut à [top] (px, fractionnaire), opacité [alpha]. */
    fun drawBar(top: Double, alpha: Double) {
        fillRect(x0 - 3, 600, x1 + 4, 1260, 25, 25, 64)
        fillRect(x0, topFull.toInt(), x1 + 1, bottom.toInt() + 1, 47, 46, 81)               // fond de la barre
        if (top >= bottom) return
        for (y in (topFull - 20).toInt() until bottom.toInt() + 2) {
            // couverture du pixel y par la partie pleine (le pixel y couvre [y − 0.5, y + 0.5])
            val cov = (minOf(y + 0.5, bottom) - maxOf(y - 0.5, top)).coerceIn(0.0, 1.0)
            val glow = if (y + 0.5 <= top && top - (y + 0.5) < 18) 30.0 * (1 - (top - (y + 0.5)) / 18) * alpha else 0.0
            val f = ((y - top) / (bottom - top)).coerceIn(0.0, 1.0)
            val fr = 7 + (65 - 7) * f; val fg = 178 + (122 - 178) * f; val fb = 214 + (230 - 214) * f
            for (x in x0..x1) {
                val base = if (y >= topFull.toInt() && y <= bottom.toInt()) intArrayOf(47, 46, 81) else intArrayOf(25, 25, 64)
                val a = cov * alpha
                val r = base[0] * (1 - a) + fr * a + glow * 0.3
                val g = base[1] * (1 - a) + fg * a + glow
                val b = base[2] * (1 - a) + fb * a + glow * 0.5
                put(x, y, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
        }
    }

    fun clearBar() = fillRect(x0 - 3, 600, x1 + 4, 1260, 25, 25, 64)

    /** Texte du résultat (bande orange) avec une opacité [a] entre 0 et 1. */
    fun drawResult(a: Double) {
        val y0 = (0.46 * h).toInt()
        val y1 = (0.52 * h).toInt()
        for (y in y0 until y1) for (x in (0.2 * w).toInt() until (0.8 * w).toInt()) {
            put(x, y, (25 + (250 - 25) * a).toInt(), (25 + (160 - 25) * a).toInt(), (64 + (20 - 64) * a).toInt())
        }
    }

    fun drawButton(show: Boolean) {
        val y0 = 1018; val y1 = 1128
        for (y in y0 until y1) for (x in 89..629) {
            if (show) put(x, y, 20, 165, 222) else put(x, y, 25, 25, 64)
        }
    }
}
