import fr.triche.stack.logic.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BotSimTest {
    class Result(val levels: Int, val errors: List<Double>, val widthEnd: Double, val over: Boolean, val bot: StackBot)

    /** Fait jouer le bot contre le simulateur à 60 images/s (avec un peu de gigue) et renvoie ce qui s'est passé. */
    fun play(
        seed: Long,
        settings: StackSettings,
        inputLatency: Double = 0.05,
        jitter: Double = 0.008,
        captureLag: Double = 0.045,
        maxLevels: Int = 400,
        verbose: Boolean = false,
        fps: Double = 60.0,
        startLevel: Int = 0,
        speedScale: Double = 1.0,
        dropFrames: Double = 0.0,
    ): Result {
        val sim = GameSim(seed, inputLatency, jitter, captureLag, startLevel = startLevel, speedScale = speedScale)
        val bot = StackBot(720, 1594, settings)
        if (verbose) bot.log = { println(it) }
        var tdbg = 0.0
        if (verbose) bot.debug = { if (tdbg in 6.1..7.2 && tdbg.toInt().toDouble() >= 0) println("   d t=%.3f %s".format(tdbg, it)) }
        val rnd = java.util.Random(seed * 31 + 7)
        var t = 0.0
        var nextFrame = 0.0
        var tapAt = Double.NaN
        val dt = 0.001
        while (t < 600.0 && !sim.over && sim.level < maxLevels && bot.state != StackBot.State.DONE) {
            sim.advance(t)
            if (!tapAt.isNaN() && t >= tapAt) {
                sim.tap(t)
                bot.onTapped(t)
                tapAt = Double.NaN
            }
            if (t >= nextFrame) {
                if (dropFrames > 0 && rnd.nextDouble() < dropFrames) { nextFrame += 1.0 / fps; t += dt; continue }
                val ft = t + (rnd.nextDouble() - 0.5) * 0.004
                tdbg = t
                val r = bot.onFrame(ft, sim.comps(t))
                if (r != null && tapAt.isNaN()) tapAt = r else if (r != null) tapAt = r
                nextFrame += 1.0 / fps
            }
            t += dt
        }
        if (verbose) println("fin t=$t state=${bot.state} status=${bot.status} over=${sim.over} level=${sim.level}")
        return Result(sim.level, sim.errors, sim.width, sim.over, bot)
    }

    @Test
    fun jouePlusieursCentainesDeBlocsAvecLatenceInconnue() {
        for (jit in listOf(0.0, 0.008)) for (seed in 1L..4L) {
            val r = play(seed, StackSettings(targetScore = 0, latencyLeft = 0.11, latencyRight = 0.11), jitter = jit, maxLevels = 60, verbose = false)
            val tail = r.errors.drop(10)
            val big = tail.count { abs(it) > 8 }
            println("jit=$jit seed=$seed niveaux=${r.levels} largeurFin=${"%.0f".format(r.widthEnd)} perdu=${r.over} " +
                "premières=${r.errors.take(6).joinToString { "%.0f".format(it) }} |e|moy(10+)=${"%.1f".format(tail.map { abs(it) }.average())} >8px: $big/${tail.size} " +
                "lat=${"%.0f".format(r.bot.latLeft * 1000)}/${"%.0f".format(r.bot.latRight * 1000)}ms")
            assertTrue(r.levels >= 55, "seed $seed : seulement ${r.levels} niveaux")
        }
    }

    private fun report(name: String, r: Result) {
        val tail = r.errors.drop(8)
        println("$name: niveaux=${r.levels} largeurFin=${"%.0f".format(r.widthEnd)} perdu=${r.over} |e|moy=${"%.1f".format(tail.map { abs(it) }.average())} max=${"%.0f".format(tail.maxOfOrNull { abs(it) } ?: 0.0)} lat=${"%.0f".format(r.bot.latLeft * 1000)}/${"%.0f".format(r.bot.latRight * 1000)}ms")
    }

    @Test
    fun rapidePuisLent() {
        for (sc in listOf(0.7, 1.0, 1.4)) {
            val r = play(11, StackSettings(), speedScale = sc, maxLevels = 50)
            report("vitesse x$sc", r)
            assertTrue(r.levels >= 45 && !r.over, "vitesse x$sc : ${r.levels} niveaux")
        }
    }

    @Test
    fun imagesRares() {
        // 30 images/s, et 20 % d'images perdues en plus
        val a = play(12, StackSettings(), fps = 30.0, maxLevels = 50)
        report("30 fps", a)
        assertTrue(a.levels >= 45 && !a.over, "30 fps : ${a.levels} niveaux")
        val b = play(13, StackSettings(), dropFrames = 0.2, maxLevels = 50)
        report("20% images perdues", b)
        assertTrue(b.levels >= 45 && !b.over, "images perdues : ${b.levels} niveaux")
    }

    @Test
    fun demarrageEnCoursDePartie() {
        val r = play(14, StackSettings(), startLevel = 12, maxLevels = 40)
        report("démarrage au niveau 12", r)
        assertTrue(r.levels >= 35 && !r.over, "démarrage en cours : ${r.levels}")
    }

    @Test
    fun latenceTresDifferenteDeLInitiale() {
        for ((inp, cap) in listOf(0.02 to 0.02, 0.12 to 0.08)) {
            val r = play(15, StackSettings(), inputLatency = inp, captureLag = cap, maxLevels = 40)
            report("latence réelle ${((inp + cap) * 1000).toInt()} ms", r)
            assertTrue(r.levels >= 35 && !r.over, "latence ${inp + cap} : ${r.levels}")
        }
    }

    @Test
    fun sArreteAuScoreVise() {
        val r = play(16, StackSettings(targetScore = 30, endOnTarget = true), maxLevels = 100)
        report("objectif 30 (fin volontaire)", r)
        assertTrue(r.over, "la partie devait se terminer")
        assertTrue(r.levels in 30..33, "score final ${r.levels}")
        val r2 = play(17, StackSettings(targetScore = 30, endOnTarget = false), maxLevels = 100)
        report("objectif 30 (je m'arrête)", r2)
        assertTrue(!r2.over && r2.levels == 30, "score ${r2.levels}")
    }
}
