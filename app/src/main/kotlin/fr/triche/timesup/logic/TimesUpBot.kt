package fr.triche.timesup.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** [latency] : délai (s) du toucher ; [shift] : correction (px) du bas de la barre ; voir [Calibrator]. */
class Settings(val latency: Double, val shift: Double, val autoNext: Boolean, val autoLatency: Boolean, val maxAuto: Int = 3)

/** Un toucher à envoyer à l'instant [at] (horloge de nanoTime, en secondes), à la position (x, y) en pixels de l'image. */
class Tap(val main: Boolean, val x: Double, val y: Double, @Volatile var at: Double) {
    @Volatile var sentAt = Double.NaN
    val sent get() = !sentAt.isNaN()
}

/** Ce qui s'est passé à une manche (pour le journal et l'apprentissage). */
class LevelRecord(
    val index: Int,
    val slope: Double,             // px par seconde
    val latencyUsed: Double,
    val shiftUsed: Double,
    val samples: Int,
    val residual: Double,
    val plannedAt: Double,
    val sentAt: Double,
    val resultAt: Double,
) {
    val inv get() = 1.0 / slope
    /** Délai entre l'envoi du toucher et l'apparition du résultat à l'écran. */
    val delay get() = resultAt - sentAt
    override fun toString() = "manche %d : pente %.2f px/s, %d mesures (écart %.2f px), latence %.0f ms, toucher %+.1f ms de l'heure prévue, résultat %.0f ms après l'envoi".format(
        index, slope, samples, residual, latencyUsed * 1000, (sentAt - plannedAt) * 1000, delay * 1000)
}

/**
 * Jeu « TIME'S UP » : une barre se vide à vitesse constante puis s'efface à mi-course ; il faut toucher l'écran quand elle serait
 * vide. On mesure la position du bord haut de la partie pleine à chaque image (au 1/10 de pixel), on ajuste une droite, on en
 * déduit l'instant où le bord atteint le bas de la barre, et on touche à cet instant moins la latence du système.
 */
class TimesUpBot(var settings: Settings) {
    var log: (String) -> Unit = {}
    var onLevel: (LevelRecord) -> Unit = {}
    @Volatile var status = "En attente de la barre"
    val levels = ArrayList<LevelRecord>()
    /** Latence utilisée pour la manche en cours (peut être recalée d'après le délai du résultat, si [Settings.autoLatency]). */
    @Volatile var latency = settings.latency
        private set

    private enum class Phase { WAIT_BAR, TRACKING, SENT, RESULT }

    private var phase = Phase.WAIT_BAR
    @Volatile private var tap: Tap? = null
    private var lastT = 0.0
    private var fw = 720
    private var fh = 1594

    // suivi de la barre
    private val ts = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val bottoms = ArrayList<Double>()
    private var topFull = 0.0
    private var slope = 0.0
    private var intercept = 0.0     // y = slope·(t − tRef) + intercept
    private var tRef = 0.0
    private var resid = 0.0
    private var fitted = false
    private var endTime = Double.NaN
    private var lastFitLog = 0.0
    private var plannedAt = Double.NaN
    private var levelIndex = 0
    private var latencyAtPlan = 0.0

    // détection du résultat
    private var prevProbe: IntArray? = null
    private var probe: IntArray? = null
    private var changeSince = Double.NaN
    private var tResult = Double.NaN
    private var tResultSeen = 0.0
    private var nextTapped = false
    private var buttonSince = Double.NaN

    fun nextTap(): Tap? = tap?.takeIf { !it.sent }

    fun onSent(t: Tap, now: Double) {
        t.sentAt = now
        if (t.main) {
            log("TOUCHER envoyé à t=%.4f (%+.1f ms de l'heure prévue)".format(now, (now - t.at) * 1000))
        } else {
            log("TOUCHER « niveau suivant » envoyé")
        }
    }

    fun onFrame(t: Double, fr: Frame) {
        lastT = t
        fw = fr.w
        fh = fr.h
        val bar = BarReader.read(fr)
        val pr = probeOf(fr)
        prevProbe = probe
        probe = pr
        when (phase) {
            Phase.WAIT_BAR, Phase.TRACKING -> track(t, bar)
            Phase.SENT -> watchResult(t)
            Phase.RESULT -> afterResult(t, fr, bar)
        }
    }

    // ---------- suivi de la barre ----------

    private fun resetLevel() {
        ts.clear(); ys.clear(); bottoms.clear()
        fitted = false
        endTime = Double.NaN
        plannedAt = Double.NaN
        tap = null
        lastFitLog = 0.0
    }

    private fun track(t: Double, bar: BarSample?) {
        val sent = tap?.sent ?: false
        if (bar != null) {
            if (phase == Phase.WAIT_BAR || (ts.isNotEmpty() && bar.top < ys.last() - 12 && bar.top < topFull + 25)) {
                // nouvelle barre pleine : nouvelle manche
                if (phase == Phase.TRACKING && !sent) log("NOUVELLE BARRE avant le toucher : manche précédente abandonnée")
                resetLevel()
                phase = Phase.TRACKING
                topFull = bar.top
                levelIndex = levels.size + 1
                latencyAtPlan = latency
                log("BARRE vue (manche %d) : haut %.1f, bas %.1f, largeur %.0f px, niveau %.0f".format(levelIndex, bar.top, bar.bottom, bar.width, bar.level))
                status = "Manche $levelIndex : mesure de la barre"
            }
            if (ts.isEmpty() || t > ts.last()) { ts.add(t); ys.add(bar.top); bottoms.add(bar.bottom) }
            if (!(tap?.sent ?: false)) refit(t)
        }
        val tp = tap
        if (phase == Phase.TRACKING && tp != null && !tp.sent && t > tp.at + 2.0) {
            log("ABANDON : l'heure du toucher est passée de plus de 2 s sans envoi")
            tap = null
            phase = Phase.WAIT_BAR
        }
        if (tp != null && tp.sent) {
            phase = Phase.SENT
            tResult = Double.NaN
            changeSince = Double.NaN
            status = "Manche $levelIndex jouée"
        }
    }

    /** Ajuste y = pente·t + c sur les mesures où la barre descend, puis place le toucher. */
    private fun refit(t: Double) {
        val idx = ArrayList<Int>()
        for (i in ts.indices) if (ys[i] > topFull + 4.0) idx.add(i)
        if (idx.size < 20 || ts[idx.last()] - ts[idx.first()] < 0.5) return
        tRef = ts[idx.first()]
        var use = idx
        var s = 0.0; var c = 0.0; var rs = 0.0
        for (pass in 0..1) {
            var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in use) { val x = ts[i] - tRef; n++; sx += x; sy += ys[i]; sxx += x * x; sxy += x * ys[i] }
            val det = n * sxx - sx * sx
            if (det <= 1e-9) return
            s = (n * sxy - sx * sy) / det
            c = (sy - s * sx) / n
            var ss = 0.0
            for (i in use) { val r = ys[i] - (s * (ts[i] - tRef) + c); ss += r * r }
            rs = sqrt(ss / n)
            if (pass == 0) {
                val keep = use.filter { abs(ys[it] - (s * (ts[it] - tRef) + c)) <= max(3.0 * rs, 1.5) }
                if (keep.size >= 20) use = ArrayList(keep) else break
            }
        }
        if (s < 10.0 || s > 900.0) return
        slope = s; intercept = c; resid = rs
        fitted = true
        val sorted = bottoms.sorted()
        val bottom = sorted[sorted.size / 2] + settings.shift
        val end = tRef + (bottom - c) / s
        if (end < t || end > t + 90.0) return
        endTime = end
        val at = end - latency
        val tp = tap
        if (tp == null) {
            tap = Tap(true, 0.5 * fw, 0.58 * fh, at)
            plannedAt = at
            log("PLAN manche %d : pente %.2f px/s sur %d mesures, fin de la barre à t=%.4f, toucher à t=%.4f (dans %.0f ms)".format(
                levelIndex, s, use.size, end, at, (at - t) * 1000))
            status = "Manche $levelIndex : toucher dans %.1f s".format(at - t)
        } else if (!tp.sent && abs(tp.at - at) > 0.0004) {
            tp.at = at
            plannedAt = at
        }
        if (t - lastFitLog > 0.7) {
            lastFitLog = t
            log("AJUSTEMENT manche %d : pente %.3f px/s (écart %.2f px, %d mesures), fin t=%.4f, toucher dans %.0f ms".format(
                levelIndex, s, rs, use.size, end, (at - t) * 1000))
        }
    }

    // ---------- résultat ----------

    /** Quelques pixels de la zone du texte du résultat (vide tant que la barre est effacée). */
    private fun probeOf(fr: Frame): IntArray {
        val xs = 17
        val yn = 10
        val out = IntArray(xs * yn)
        var k = 0
        for (j in 0 until yn) {
            val y = ((0.44 + 0.015 * j) * fr.h).toInt()
            for (i in 0 until xs) {
                val x = ((0.10 + 0.05 * i) * fr.w).toInt().coerceAtMost(fr.w - 1)
                val p = fr.rgb(x, y)
                out[k++] = (299 * red(p) + 587 * green(p) + 114 * blue(p)) / 1000
            }
        }
        return out
    }

    private fun probeDiff(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null) return 0.0
        var s = 0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    private fun watchResult(t: Double) {
        val tp = tap ?: return
        val d = probeDiff(probe, prevProbe)
        if (d > 0.6 && tResult.isNaN()) {
            if (changeSince.isNaN()) changeSince = t
            else if (t > changeSince) { tResult = changeSince }     // deux images de suite : ce n'est pas du bruit
        } else if (d <= 0.6) changeSince = Double.NaN
        val timeout = t - tp.sentAt > 2.5
        if (!tResult.isNaN() || timeout) {
            val rec = LevelRecord(levelIndex, slope, latencyAtPlan, settings.shift, ts.size, resid, plannedAt, tp.sentAt, tResult)
            levels.add(rec)
            log("RÉSULTAT " + rec + if (timeout && tResult.isNaN()) " (aucun résultat vu)" else "")
            onLevel(rec)
            if (settings.autoLatency && !tResult.isNaN()) {
                // le résultat apparaît ~8 ms (en moyenne, une demi-image) après l'arrivée du toucher dans le jeu : latence ≈ délai − 8 ms
                latency = (rec.delay - 0.008).coerceIn(0.02, 0.35)
                log("LATENCE recalée à %.0f ms d'après le délai du résultat".format(latency * 1000))
            }
            phase = Phase.RESULT
            tResultSeen = t
            nextTapped = false
            buttonSince = Double.NaN
            status = "Manche $levelIndex terminée : note le décalage affiché"
        }
    }

    private fun afterResult(t: Double, fr: Frame, bar: BarSample?) {
        if (bar != null && bar.top < topFull + 40 && t - tResultSeen > 0.3) {
            // nouvelle manche (le joueur ou le bot a appuyé sur « niveau suivant »)
            resetLevel()
            phase = Phase.WAIT_BAR
            track(t, bar)
            return
        }
        if (!settings.autoNext || levels.size >= settings.maxAuto || nextTapped) return
        val b = NextButton.find(fr)
        if (b == null) { buttonSince = Double.NaN; return }
        if (buttonSince.isNaN()) buttonSince = t
        if (t - buttonSince > 0.8 && t - tResultSeen > 1.2) {
            tap = Tap(false, b.first, b.second, t + 0.02)
            nextTapped = true
            log("BOUTON « niveau suivant » vu en (%.0f, %.0f)".format(b.first, b.second))
        }
    }
}
