import kotlin.test.Test
import kotlin.test.assertTrue

class PilotSimTest {
    /** Test de cohérence exécuté à chaque build : un jeu à un seul pic par mur doit être passé sans mourir. */
    @Test fun jeuFacileSansMourir() {
        var reached = 0
        for (seed in 1L..6L) {
            val r = runPilot(GameSim(seed, maxSpikes = 1), target = 30)
            if (r.dead == null) reached++
            println("SIM facile seed $seed -> ${r.bounces} rebonds ${r.dead ?: "OK"}")
        }
        assertTrue(reached >= 4, "seulement $reached parties sur 6 ont atteint 30 rebonds")
    }

    @Test fun detail() {
        if (System.getenv("SIMSEED") == null) return
        val seed = System.getenv("SIMSEED").toLong()
        val sim = if (System.getenv("SIMCAP") != null) GameSim(seed, vxCap = 8.0, maxSpikes = 3) else if (System.getenv("SIMPERFECT") != null) GameSim(seed, noise = 0.0, captureLag = 0.0, dispatch = 5.0..5.0) else GameSim(seed)
        val r = runPilot(sim, target = 40, verbose = true)
        val lines = r.log.lines()
        println("TRACE fin: ${r.dead}")
        for (l in lines.takeLast(System.getenv("SIMLINES")?.toInt() ?: 60)) println("TRACE $l")
    }

    @Test fun apercu() {
        if (System.getenv("APERCU") == null) return
        for (seed in 1L..10L) {
            val r = runPilot(GameSim(seed), target = 40)
            println("SIM seed $seed -> ${r.bounces} rebonds, taps ${r.taps}, latence apprise ${"%.1f".format(r.latency)} ${r.dead ?: "OK"}")
        }
    }
}

class Experiments {
    private fun avg(label: String, make: (Long) -> GameSim, target: Int = 60, seeds: Int = 12) {
        var sum = 0
        val causes = HashMap<String, Int>()
        for (s in 1..seeds) {
            val r = runPilot(make(s.toLong()), target = target)
            sum += r.bounces
            val c = (r.dead ?: "OK").substringBefore(' ')
            causes[c] = (causes[c] ?: 0) + 1
        }
        println("EXP $label : moyenne ${"%.1f".format(sum / seeds.toDouble())} rebonds  $causes")
    }

    @Test fun causes() {
        if (System.getenv("CAUSES") == null) return
        for (s in 1..16) {
            val r = runPilot(GameSim(s.toLong()), target = 60)
            // coût dur du plan sur les 40 dernières images : combien d'images "infaisables" (>1000) ?
            val infeasible = r.hardHist.count { it > 900 }
            val firstInf = r.hardHist.indexOfFirst { it > 900 }
            println("CAUSE seed $s : ${r.bounces} rebonds, ${r.dead?.substringBefore('(')} | plans infaisables sur les 40 dernières images : $infeasible (dès l'image ${if (firstInf < 0) "-" else firstInf.toString()}, arrivée dans ${"%.0f".format(r.arrHist.last())} ticks)")
        }
    }

    @Test fun precision() {
        if (System.getenv("PRECISION") == null) return
        val all = ArrayList<Double>()
        for (s in 1..12) {
            val r = runPilot(GameSim(s.toLong()), target = 60)
            all.addAll(r.predErrors)
            println("PREC seed $s : ${r.bounces} rebonds ${r.dead?.substringBefore('(')} ${r.deathInfo}")
        }
        val abs = all.map { Math.abs(it) }.sorted()
        println("PREC erreur de prédiction d'arrivée (12 ticks avant), n=${abs.size} : médiane ${"%.0f".format(abs[abs.size / 2])} px, 90e centile ${"%.0f".format(abs[(abs.size * 0.9).toInt()])} px, max ${"%.0f".format(abs.last())} px")
    }

    @Test fun experiences() {
        if (System.getenv("EXPER") == null) return
        if (System.getenv("EXPER") == "quick") {
            avg("tout", { GameSim(it) }, seeds = 16)
            avg("parfait", { GameSim(it, noise = 0.0, captureLag = 0.0, dispatch = 5.0..5.0) }, seeds = 16)
            avg("un seul pic par mur", { GameSim(it, maxSpikes = 1) }, seeds = 16)
            avg("un seul pic, vitesse x physique constante (jeu facile)", { GameSim(it, maxSpikes = 1, gExp = 0.0, jExp = 0.0, vxCap = 8.0) }, seeds = 16)
            return
        }
        avg("parfait (sans bruit, sans retard de capture, délai fixe)", { GameSim(it, noise = 0.0, captureLag = 0.0, dispatch = 5.0..5.0) })
        avg("+ bruit 1.5 px", { GameSim(it, noise = 1.5, captureLag = 0.0, dispatch = 5.0..5.0) })
        avg("+ retard de capture 2", { GameSim(it, noise = 0.0, captureLag = 2.0, dispatch = 5.0..5.0) })
        avg("+ délai variable 3-6", { GameSim(it, noise = 0.0, captureLag = 0.0, dispatch = 3.0..6.0) })
        avg("tout", { GameSim(it) })
        avg("latence minimale (1 tick), sans bruit", { GameSim(it, noise = 0.0, captureLag = 0.0, dispatch = 1.0..1.0) })
        avg("trous larges (gapMin 260)", { GameSim(it, gapMin = 260.0) })
        avg("max 3 pics", { GameSim(it, maxSpikes = 3) })
        avg("vitesse qui plafonne à 8 px/tick", { GameSim(it, vxCap = 8.0) })
    }
}

class Timing {
    @Test fun tempsParImage() {
        if (System.getenv("TIMING") == null) return
        val sim = GameSim(3L)
        val pilot = fr.triche.dangerwall.logic.Pilot(GameSim.W)
        val times = ArrayList<Long>()
        var n = 0
        while (sim.dead == null && sim.bounces < 40 && n < 60 * 300) {
            sim.step(); n++
            val scene = sim.scene() ?: continue
            val t0 = System.nanoTime()
            val tap = pilot.step(sim.tick / 60.0, scene)
            times.add(System.nanoTime() - t0)
            if (tap) sim.tap(sim.tick)
        }
        times.sort()
        println("TIMING ${times.size} images : médiane ${times[times.size / 2] / 1000} µs, 90e centile ${times[(times.size * 0.9).toInt()] / 1000} µs, 99e ${times[(times.size * 0.99).toInt()] / 1000} µs, max ${times.last() / 1000} µs (rebonds ${sim.bounces})")
    }
}
