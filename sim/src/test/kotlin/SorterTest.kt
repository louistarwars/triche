import fr.triche.colis.logic.Color
import fr.triche.colis.logic.Dir
import fr.triche.colis.logic.Parcel
import fr.triche.colis.logic.Scene
import fr.triche.colis.logic.Sorter
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mini-jeu fidèle à la vidéo : les colis sortent de la goulotte en haut (y≈842), descendent le tapis
 * (jusqu'à y≈1366 = perdu), un geste dans le bon sens envoie le colis du bas vers sa boîte (animation
 * ≈ 100 ms pendant laquelle il remonte vite), un mauvais geste termine la partie.
 */
class SorterTest {
    private class P(val color: Int, var bottom: Float)
    private class Flyer(val color: Int, var bottom: Float, var life: Int)

    private class Result(val score: Int, val over: String?)

    private fun play(
        seed: Long,
        latency: ClosedFloatingPointRange<Float>,
        lock: Float = 0f,
        speedStart: Float = 150f,
        speedMax: Float = 380f,
        targetScore: Int = 300,
        fps: Int = 60,
    ): Result {
        val rnd = Random(seed)
        val sorter = Sorter()
        val dt = 1f / fps
        val belt = ArrayList<P>()
        val flyers = ArrayList<Flyer>()
        val inputs = ArrayList<Pair<Float, Dir>>() // (instant d'effet, direction)
        var t = 0f
        var score = 0
        var lockedUntil = 0f
        var nextSpawn = 0.3f
        var advance = 0f

        while (score < targetScore && t < 600f) {
            t += dt
            val speed = (speedStart + score * 4f).coerceAtMost(speedMax)
            // le tapis avance (par à-coups après un tri)
            val step = speed * dt + advance.coerceAtMost(6f)
            advance = (advance - 6f).coerceAtLeast(0f)
            for (p in belt) p.bottom += step
            for (f in flyers) { f.bottom -= 22f; f.life-- }
            flyers.removeAll { it.life <= 0 }
            // apparition
            val lastTop = belt.minOfOrNull { it.bottom } ?: 9999f
            if (t >= nextSpawn && lastTop > 842f + 115f) {
                belt.add(P(1 + rnd.nextInt(3), 842f))
                nextSpawn = t
            }
            if (belt.any { it.bottom > 1366f }) { if (System.getenv("SIMDEBUG") != null) println("MISS t=$t score=$score belt=${belt.map { b -> "${b.color}@${b.bottom.toInt()}" }} flyers=${flyers.size} sorterCount=${sorter.count}"); return Result(score, "colis manqué") }

            // gestes qui arrivent
            val it = inputs.iterator()
            while (it.hasNext()) {
                val (at, dir) = it.next()
                if (at > t) continue
                it.remove()
                if (t < lockedUntil) continue
                val front = belt.maxByOrNull { p -> p.bottom } ?: continue
                val ok = when (dir) {
                    Dir.LEFT -> front.color == Color.RED
                    Dir.UP -> front.color == Color.YELLOW
                    Dir.RIGHT -> front.color == Color.BLUE
                }
                if (!ok) return Result(score, "mauvais geste $dir sur couleur ${front.color} (score $score)")
                belt.remove(front)
                flyers.add(Flyer(front.color, front.bottom, 6))
                score++
                advance += 17f
                lockedUntil = t + lock
            }

            // ce que voit le bot
            val parcels = ArrayList<Parcel>()
            val all = belt.map { Triple(it.color, (it.bottom - 80f).coerceAtLeast(842f), it.bottom) } +
                flyers.map { Triple(it.color, (it.bottom - 80f).coerceAtLeast(842f), it.bottom) }
            for ((c, top, bottom) in all.sortedBy { it.third }) {
                if (bottom - top < 24f || bottom > 1366f || bottom < 842f) continue
                val last = parcels.lastOrNull()
                if (last != null && last.color == c && top - last.bottom < 8) {
                    parcels[parcels.size - 1] = Parcel(c, last.top, bottom.toInt(), 1f)
                } else {
                    parcels.add(Parcel(c, top.toInt(), bottom.toInt(), 1f))
                }
            }
            val dir = sorter.step(t.toDouble(), Scene(263, 457, 842, 1371, parcels))
            if (dir != null) inputs.add((t + latency.start + rnd.nextFloat() * (latency.endInclusive - latency.start)) to dir)
        }
        return Result(score, null)
    }


    private fun check(name: String, runs: Int = 25, target: Int = 300, play: (Long) -> Result) {
        var bad = 0
        var worst = Int.MAX_VALUE
        var why = ""
        for (s in 1..runs) {
            val r = play(s.toLong())
            if (r.over != null) { bad++; why = r.over }
            worst = minOf(worst, r.score)
        }
        println("SIM $name: $bad parties perdues sur $runs (pire score $worst) $why")
        assertTrue(bad == 0, "$name : $why (pire score $worst)")
    }

    @Test fun nominal() = check("nominal latence 30-100 ms") { play(it, 0.03f..0.10f) }
    @Test fun latenceFaible() = check("latence 10-30 ms") { play(it, 0.01f..0.03f) }
    @Test fun latenceForte() = check("latence 100-200 ms") { play(it, 0.10f..0.20f, speedMax = 250f) }
    @Test fun entreeBloqueePendantAnimation() = check("entrée bloquée 300 ms après un tri") { play(it, 0.03f..0.10f, lock = 0.3f, speedMax = 200f) }
    @Test fun capture30fps() = check("capture 30 fps") { play(it, 0.03f..0.10f, fps = 30, speedMax = 300f) }
    @Test fun rapide() = check("tapis rapide 450 px/s", target = 300) { play(it, 0.03f..0.08f, speedStart = 300f, speedMax = 450f) }

    @Test fun aucuneActionSansColis() {
        val s = Sorter()
        for (i in 0 until 100) assertNull(s.step(i / 60.0, Scene(263, 457, 842, 1371, emptyList())))
        for (i in 0 until 100) assertNull(s.step(i / 60.0, null))
    }

    @Test fun couleurIncertaineIgnoree() {
        val s = Sorter()
        for (i in 0 until 30) {
            assertNull(s.step(i / 60.0, Scene(263, 457, 842, 1371, listOf(Parcel(Color.RED, 900, 980 + i, 0.5f)))))
        }
    }

    @Test fun bonSensPourChaqueCouleur() {
        val expected = mapOf(Color.RED to Dir.LEFT, Color.YELLOW to Dir.UP, Color.BLUE to Dir.RIGHT)
        for ((c, d) in expected) {
            val s = Sorter()
            var got: Dir? = null
            for (i in 0 until 10) got = got ?: s.step(i / 60.0, Scene(263, 457, 842, 1371, listOf(Parcel(c, 900 + 4 * i, 980 + 4 * i, 1f))))
            assertEquals(d, got)
        }
    }
}
