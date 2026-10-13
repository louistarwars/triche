import fr.triche.dice.logic.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Lecture sur de vraies images de la vidéo du jeu (720×1594). */
class RealFramesTest {
    @Test
    fun chronometre() {
        // image -> valeur affichée (en dixièmes) vérifiée à l'œil sur la vidéo
        val expected = mapOf("t4.0" to 0, "t8.7" to 26, "t8.9" to 28, "t11.3" to 49, "t13.7" to 55, "t13.9" to 57, "t14.6" to 63, "t5.11" to 11, "t7.0" to 20)
        for ((n, v) in expected) assertEquals(v, TimerReader.read(Support.frame(n)), n)
    }

    @Test
    fun des() {
        assertEquals("5+1+4+6=16", DiceReader.read(Support.frame("t4.0")).toString())   // dés encore en train d'arriver
        assertEquals("5+1+4+6=16", DiceReader.read(Support.frame("t5.11")).toString())
        assertEquals("6+3+6+4=19", DiceReader.read(Support.frame("t8.9")).toString())
        assertEquals("2+3+2+3=10", DiceReader.read(Support.frame("t13.9")).toString())
        assertNull(DiceReader.read(Support.frame("t7.0")), "écran sans dés lisibles")
    }

    @Test
    fun pavéNumérique() {
        val p = assertNotNull(Numpad.detect(Support.frame("t8.9")))
        val (x0, y0) = p.center('0')
        assertEquals(360.0, x0, 3.0); assertEquals(1448.0, y0, 3.0)
        val (x1, y1) = p.center('1')
        assertEquals(151.0, x1, 3.0); assertEquals(1126.0, y1, 3.0)
        val (x9, y9) = p.center('9')
        assertEquals(567.0, x9, 3.0); assertEquals(1341.0, y9, 3.0)
        val (xb, yb) = p.center('B')
        assertEquals(567.0, xb, 3.0); assertEquals(1448.0, yb, 3.0)
    }
}
