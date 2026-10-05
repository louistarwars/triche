package fr.triche.bot.logic

import kotlin.math.min

/**
 * Décide quand toucher l'écran. Tout est calculé en "largeurs d'écran" (L) et en secondes,
 * valeurs mesurées sur la vidéo (écran 720 px de large) :
 *   gravité ≈ 3,4 L/s², saut ≈ -0,6 L/s, défilement ≈ 0,48 L/s, trou ≈ 0,5 L.
 * La latence réelle (capture + injection du toucher) est apprise en direct.
 */
class Controller {
    companion object {
        const val GRAVITY = 3.4f
        const val JUMP = -0.60f
        const val RISE = JUMP * JUMP / (2 * GRAVITY)          // hauteur d'un saut ≈ 0,053 L
        const val AIM_BIAS = RISE / 2                         // centre l'oscillation sur le milieu du trou
        const val SAFETY = 0.02f
        const val DEFAULT_LATENCY = 0.09
    }

    var latency = DEFAULT_LATENCY
        private set

    private val ts = DoubleArray(6)
    private val ys = FloatArray(6)
    private var n = 0
    private var tapSentAt = Double.NaN
    private var vyAtTap = 0f

    fun reset() {
        n = 0
        tapSentAt = Double.NaN
    }

    /** Appelé à chaque image. Renvoie true s'il faut toucher l'écran maintenant. */
    fun step(t: Double, obs: Observation, w: Int): Boolean {
        val unit = w.toFloat()
        val y = obs.ballY / unit
        val half = obs.ballHalf / unit

        if (n > 0 && t - ts[n - 1] > 0.25) reset()
        if (n == ts.size) {
            System.arraycopy(ts, 1, ts, 0, n - 1)
            System.arraycopy(ys, 1, ys, 0, n - 1)
            n--
        }
        ts[n] = t
        ys[n] = y
        n++
        if (n < 3) return false

        val vy = velocity()
        learnLatency(t)
        if (!tapSentAt.isNaN()) return false // un toucher est déjà en vol

        // Trou visé : celui du premier pilier pas encore dépassé.
        val ballLeft = obs.ballX - obs.ballHalf
        val pillar = obs.pillars.firstOrNull { it.right > ballLeft - obs.ballHalf * 0.5f }
        val gapTop: Float
        val gapBottom: Float
        if (pillar != null) {
            gapTop = pillar.gapTop / unit
            gapBottom = pillar.gapBottom / unit
        } else {
            gapTop = obs.fieldTop / unit
            gapBottom = obs.fieldBottom / unit
        }
        val aim = (gapTop + gapBottom) / 2 + AIM_BIAS

        val h = latency.toFloat()
        val yPred = y + vy * h + 0.5f * GRAVITY * h * h
        val vPred = vy + GRAVITY * h
        val safeTop = gapTop + half + SAFETY
        val safeBottom = gapBottom - half - SAFETY

        var want = yPred >= aim && vPred > -0.15f
        if (want && yPred - RISE < safeTop && yPred < safeBottom) want = false // le saut taperait le plafond
        if (!want) return false
        tapSentAt = t
        vyAtTap = vy
        return true
    }

    private fun velocity(): Float {
        val k = min(n, 3)
        val i0 = n - k
        var sx = 0.0
        var sy = 0.0
        for (i in i0 until n) { sx += ts[i]; sy += ys[i] }
        val mx = sx / k
        val my = sy / k
        var num = 0.0
        var den = 0.0
        for (i in i0 until n) {
            num += (ts[i] - mx) * (ys[i] - my)
            den += (ts[i] - mx) * (ts[i] - mx)
        }
        return if (den > 1e-9) (num / den).toFloat() else 0f
    }

    /** Mesure le délai entre l'envoi d'un toucher et le moment où la balle remonte vraiment. */
    private fun learnLatency(t: Double) {
        if (tapSentAt.isNaN()) return
        val dt = ts[n - 1] - ts[n - 2]
        val inst = if (dt > 1e-4) (ys[n - 1] - ys[n - 2]) / dt else 0.0
        // Mesure fiable seulement si la balle descendait au moment du toucher (sinon elle "monte" déjà).
        if (vyAtTap > 0f && inst <= -0.25) {
            val measured = t - tapSentAt
            latency = (0.7 * latency + 0.3 * measured).coerceIn(0.03, 0.20)
            tapSentAt = Double.NaN
        } else if (inst <= -0.25 || t - tapSentAt > 0.30) {
            tapSentAt = Double.NaN // effet vu sans mesure fiable, ou toucher perdu
        }
    }
}
