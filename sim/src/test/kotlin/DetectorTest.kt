import fr.triche.dangerwall.logic.Detector
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectorTest {
    private fun near(a: Number, b: Number, tol: Double) = abs(a.toDouble() - b.toDouble()) <= tol

    @Test fun ecransHorsPartie() {
        for (n in listOf("d0020", "d0100", "d0150", "d2000", "d2100")) assertNull(Detector().detect(Support.frame(n)), n)
    }

    /** (image, y de la balle, bord avant de la balle, pics gauche, pics droite) : mesures de référence prises à part. */
    private class Case(val name: String, val box: IntArray, val left: List<Double>, val right: List<Double>)

    // box = xMin, xMax, yMin, yMax de la tache blanche
    private val cases = listOf(
        Case("d0300", intArrayOf(403, 532, 1139, 1276), listOf(833.0), listOf(731.5)),
        Case("d0400", intArrayOf(26, 131, 1170, 1261), listOf(832.5), listOf(731.5)),
        Case("d0980", intArrayOf(589, 712, 539, 631), listOf(629.5, 937.5), listOf(887.5)),
        Case("d1300", intArrayOf(112, 244, 727, 833), listOf(621.5, 1079.5), listOf(541.5, 1047.5)),
        Case("d1500", intArrayOf(291, 383, 938, 1044), listOf(613.5, 741.5, 932.0), listOf(593.5, 1043.5)),
        Case("d1926", intArrayOf(155, 293, 1208, 1337), listOf(583.0, 945.5, 1203.5), listOf(591.5, 727.5, 1043.5)),
    )

    @Test fun balleEtPics() {
        for (c in cases) {
            val s = assertNotNull(Detector().detect(Support.frame(c.name)), c.name)
            val b = assertNotNull(s.ball, "${c.name} balle")
            println("DET ${c.name} balle x=${b.xMin}..${b.xMax} y=${b.yMin}..${b.yMax} | gauche=${s.left} droite=${s.right} | terrain ${s.fieldTop}-${s.fieldBottom}")
            assertTrue(near(b.xMin, c.box[0], 8.0) && near(b.xMax, c.box[1], 8.0), "${c.name} x ${b.xMin}..${b.xMax}")
            assertTrue(near(b.yMin, c.box[2], 8.0) && near(b.yMax, c.box[3], 8.0), "${c.name} y ${b.yMin}..${b.yMax}")
            assertTrue(s.left.size == c.left.size && s.right.size == c.right.size, "${c.name} nombre de pics ${s.left} ${s.right}")
            for (i in c.left.indices) assertTrue(near(s.left[i], c.left[i], 6.0), "${c.name} gauche $i ${s.left[i]}")
            for (i in c.right.indices) assertTrue(near(s.right[i], c.right[i], 6.0), "${c.name} droite $i ${s.right[i]}")
        }
    }

    @Test fun suiviAvecIndice() {
        // avec la position précédente en indice, on retrouve la même balle
        val det = Detector()
        val first = assertNotNull(det.detect(Support.frame("d0300"))?.ball)
        val again = assertNotNull(det.detect(Support.frame("d0300"), first)?.ball)
        assertTrue(near(again.cy, first.cy, 0.5) && near(again.xMin, first.xMin, 0.5))
    }
}
