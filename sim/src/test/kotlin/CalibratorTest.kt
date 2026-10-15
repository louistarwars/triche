import fr.triche.timesup.logic.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CalibratorTest {
    @Test
    fun retrouveLatenceEtBas() {
        // vérité : latence 95 ms, bas de la barre 2 px plus loin (la fin arrive 2/pente plus tard) ; réglages utilisés : 70 ms, 0 px
        val invs = listOf(0.00605, 0.0158, 0.0185, 0.0102)
        val obs = invs.map { inv ->
            val e = (0.095 - 0.070) - (2.0 - 0.0) * inv        // décalage exact affiché
            Calibrator.obs(inv, e, 0.070, 0.0)
        }
        val (l, b) = Calibrator.solve(obs)!!
        assertEquals(0.095, l, 0.008)
        assertEquals(2.0, b, 1.0)
    }

    @Test
    fun uneSeuleVitesseNeTouchePasAuBas() {
        // deux manches de même vitesse : on ne peut pas séparer latence et bas de la barre, B reste à 0
        val obs = listOf(Calibrator.obs(0.0158, -0.04, 0.085, 0.0), Calibrator.obs(0.0158, -0.05, 0.085, 0.0))
        val (l, b) = Calibrator.solve(obs)!!
        assertEquals(0.0, b, 1e-6)
        assertEquals(0.085 - 0.045, l, 1e-6)
    }
}
