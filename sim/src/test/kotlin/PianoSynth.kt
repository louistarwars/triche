import fr.triche.piano.logic.Frame
import java.nio.ByteBuffer
import java.util.Random

/**
 * Jeu PIANO simulé d'après les mesures de la vidéo (grille de 300 px sur 720, touche de 296,3 px, zone de jeu à partir de
 * y = 19,2 % de la hauteur) : défilement à vitesse croissante, une touche touchée devient grise, toucher le vide ou laisser
 * une touche blanche sortir en bas termine la partie. L'image est dessinée à l'échelle [s] (1 = 720×1594).
 */
class PianoSynth(val s: Double, seed: Long, val v0: Double, val accel: Double) {
    val w = (720 * s).toInt()
    val h = (1594 * s).toInt()
    val bytes = ByteArray(w * h * 4)
    val frame = Frame(ByteBuffer.wrap(bytes), w, h, w * 4, 4)
    val pitch = 299.86 * s
    val tileH = 296.3 * s
    val clip = 0.192 * h
    val bottom = 0.965 * h
    private val rnd = Random(seed)
    private val cells = HashMap<Long, Int>()        // 1 = blanche, 2 = touchée
    private val rowsDone = HashSet<Int>()
    var phi = 1000.0 * s                            // haut de la rangée R = 0 (px de l'image)
    var gameOver: String? = null
    var tapsOk = 0
    var emptyTaps = 0
    var spawned = 0
    var missed = 0
    private var t = 0.0
    private val colX = doubleArrayOf(90.0, 270.0, 450.0, 630.0)

    private fun key(r: Int, c: Int) = r.toLong() * 4 + c
    private fun speed(time: Double) = (v0 + accel * time) * s

    /** Avance le jeu jusqu'au temps de jeu [time]. */
    fun advance(time: Double) {
        if (time <= t) return
        val steps = ((time - t) / 0.001).toInt().coerceAtLeast(1)
        val dt = (time - t) / steps
        for (i in 0 until steps) {
            t += dt
            phi += speed(t) * dt
            spawn()
            checkMissed()
        }
    }

    private fun spawn() {
        // rangées qui deviennent visibles (au-dessus du cadre, donc cachées) : on les crée à l'avance
        val rTop = Math.floor((0.0 - phi) / pitch).toInt() - 1
        for (r in rTop..rTop + 2) {
            if (rowsDone.add(r)) {
                var any = false
                for (c in 0..3) if (rnd.nextDouble() < 0.30) { cells[key(r, c)] = 1; any = true; spawned++ }
                if (!any) { cells[key(r, rnd.nextInt(4))] = 1; spawned++ }
            }
        }
    }

    private fun checkMissed() {
        if (gameOver != null) return
        for ((k, st) in cells) {
            if (st != 1) continue
            val r = Math.floorDiv(k, 4L).toInt()
            if (phi + pitch * r > bottom) { gameOver = "touche blanche sortie en bas (rangée $r) à t=%.2f".format(t); missed++; return }
        }
    }

    /** Un toucher arrive dans le jeu en (x, y) (pixels de l'image). */
    fun touch(x: Double, y: Double) {
        if (gameOver != null) return
        if (y < clip) { emptyTaps++; gameOver = "toucher hors du cadre"; return }
        val c = (x / s / 180.0).toInt().coerceIn(0, 3)
        for ((k, st) in cells) {
            if (k % 4 != c.toLong() && Math.floorMod(k, 4L) != c.toLong()) continue
            val r = Math.floorDiv(k, 4L).toInt()
            val top = phi + pitch * r
            if (y >= top && y <= top + tileH) {
                if (st == 1) { cells[k] = 2; tapsOk++; return }
                else { emptyTaps++; gameOver = "toucher d'une touche déjà touchée (rangée $r) à t=%.2f".format(t); return }
            }
        }
        emptyTaps++
        gameOver = "toucher dans le vide en (%.0f, %.0f) à t=%.2f (vitesse %.0f)".format(x / s, y / s, t, speed(t) / s)
    }

    private fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
        val o = (y * w + x) * 4
        bytes[o] = r.toByte(); bytes[o + 1] = g.toByte(); bytes[o + 2] = b.toByte(); bytes[o + 3] = 0xFF.toByte()
    }

    /** Dessine l'écran tel que le montre une image affichée à l'instant [shown] : touches blanches ou touchées (grises). */
    fun render(whiteAfterTouchDelay: Double = 0.0) {
        for (y in 0 until h) for (x in 0 until w) put(x, y, 27, 7, 56)
        val rMin = Math.ceil((clip - tileH - phi) / pitch).toInt()
        val rMax = Math.floor((h - phi) / pitch).toInt()
        for (r in rMin..rMax) for (c in 0..3) {
            val st = cells[key(r, c)] ?: continue
            val top = phi + pitch * r
            val x0 = ((colX[c] - 88) * s).toInt(); val x1 = ((colX[c] + 88) * s).toInt()
            val y0 = Math.max(top, clip).toInt(); val y1 = Math.min(top + tileH, h.toDouble() - 1).toInt()
            val col = if (st == 1) intArrayOf(238, 237, 245) else intArrayOf(40, 30, 66)
            for (y in y0 until y1) for (x in x0 until x1) {
                // anticrénelage des bords haut et bas
                put(x, y, col[0], col[1], col[2])
            }
        }
    }
}
