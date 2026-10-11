import fr.triche.react.logic.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Détection sur de vraies images de la vidéo du jeu. */
class ReactTest {
    private fun find(name: String) = ReactDetector.find(Support.frame(name))

    @Test
    fun cercleAllumeTrouveAuBonEndroit() {
        val a = assertNotNull(find("r271")); assertEquals(8, a.index)
        val b = assertNotNull(find("r419")); assertEquals(4, b.index)
        val c = assertNotNull(find("r1012")); assertEquals(5, c.index)
        val d = assertNotNull(find("r598")); assertEquals(8, d.index)
        // cible = centre du cercle (à 2 px près sur 720×1594)
        assertEquals(542.0, a.fx * 720, 2.0); assertEquals(1158.0, a.fy * 1594, 2.0)
        assertEquals(360.0, b.fx * 720, 2.0); assertEquals(975.0, b.fy * 1594, 2.0)
    }

    @Test
    fun rienAilleurs() {
        assertNull(find("r213"), "grille au repos")
        assertNull(find("r7"), "menu d'accueil")
        assertNull(find("r113"), "écran de démarrage")
        assertNull(find("r1152"), "fin de partie")
        assertNull(find("r1046"), "animation de fin (deux cercles verts, fond sombre)")
    }

    @Test
    fun unToucherParAllumagePuisMancheSuivante() {
        val bot = ReactBot()
        val lit = Support.frame("r271")
        val idle = Support.frame("r213")
        var taps = 0
        var t = 0.0
        repeat(30) { bot.onFrame(t, idle); t += 1 / 60.0 }
        // le cercle reste vert 25 images (0,4 s) : un seul toucher
        repeat(25) { if (bot.onFrame(t, lit) != null) taps++; t += 1 / 60.0 }
        assertEquals(1, taps)
        // il s'éteint puis un nouveau s'allume : un deuxième toucher
        repeat(30) { bot.onFrame(t, idle); t += 1 / 60.0 }
        repeat(10) { if (bot.onFrame(t, Support.frame("r419")) != null) taps++; t += 1 / 60.0 }
        assertEquals(2, taps)
        assertEquals(2, bot.rounds)
    }

    @Test
    fun toucherPerduRecommence() {
        val bot = ReactBot()
        val lit = Support.frame("r271")
        var taps = 0
        var t = 0.0
        repeat(120) { if (bot.onFrame(t, lit) != null) taps++; t += 1 / 60.0 }   // 2 s toujours vert
        assertEquals(4, taps.coerceAtMost(4))
        assert(taps in 2..4) { "taps=$taps" }
    }
}
