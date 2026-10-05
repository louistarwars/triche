import fr.triche.bot.logic.Detector
import fr.triche.bot.logic.Frame
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vraies images de la vidéo d'écran (720 x 1594), mesures de référence prises à part. */
class DetectorTest {
    private fun frame(name: String): Frame {
        val img = ImageIO.read(javaClass.getResourceAsStream("/frames/$name.png"))
        val w = img.width
        val h = img.height
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val o = (y * w + x) * 4
            bytes[o] = (p shr 16).toByte()
            bytes[o + 1] = (p shr 8).toByte()
            bytes[o + 2] = p.toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        return Frame(ByteBuffer.wrap(bytes), w, h, w * 4, 4)
    }

    private fun near(a: Number, b: Number, tol: Int) = abs(a.toDouble() - b.toDouble()) <= tol

    @Test fun ecranDeDemarrageEtGameOverIgnores() {
        assertNull(Detector().detect(frame("f0001")))
        assertNull(Detector().detect(frame("f1000")))
    }

    @Test fun balleSeule() {
        val o = assertNotNull(Detector().detect(frame("f0100")))
        assertTrue(near(o.ballX, 312, 3) && near(o.ballY, 958, 3), "balle ${o.ballX},${o.ballY}")
        assertTrue(near(o.ballHalf, 28, 4), "demi-taille ${o.ballHalf}")
        assertTrue(o.fieldTop in 402..420 && o.fieldBottom in 1530..1551, "champ ${o.fieldTop}-${o.fieldBottom}")
    }

    @Test fun pilierEtTrou() {
        val o = assertNotNull(Detector().detect(frame("f0260")))
        assertTrue(near(o.ballY, 862, 3), "balle ${o.ballY}")
        val p = o.pillars.first { it.left in 380..420 }
        assertTrue(near(p.right, 493, 4), "droite ${p.right}")
        assertTrue(near(p.gapTop, 746, 4) && near(p.gapBottom, 1102, 4), "trou ${p.gapTop}-${p.gapBottom}")
    }

    @Test fun pilierCoupeAGauche() {
        val o = assertNotNull(Detector().detect(frame("f0330")))
        assertTrue(near(o.ballY, 1180, 3), "balle ${o.ballY}")
        val p = o.pillars.first { it.left <= 2 }
        assertTrue(near(p.gapTop, 746, 4) && near(p.gapBottom, 1102, 4), "trou ${p.gapTop}-${p.gapBottom}")
        val q = o.pillars.first { it.left in 470..490 }
        assertTrue(near(q.gapTop, 910, 80) || q.gapBottom - q.gapTop > 250, "trou 2 ${q.gapTop}-${q.gapBottom}")
    }

    @Test fun deuxPiliersEtScoreSurPilier() {
        val o = assertNotNull(Detector().detect(frame("f0480")))
        assertEquals(2, o.pillars.size, "piliers ${o.pillars.map { it.left }}")
        val a = o.pillars[0]
        val b = o.pillars[1]
        assertTrue(near(a.gapTop, 761, 4) && near(a.gapBottom, 1106, 4), "trou A ${a.gapTop}-${a.gapBottom}")
        assertTrue(near(b.gapTop, 640, 4) && near(b.gapBottom, 978, 4), "trou B ${b.gapTop}-${b.gapBottom}")
    }

    @Test fun pilierSousLeChiffreDuScore() {
        // f0540 : un pilier passe sous le "6" ; son bord haut ne doit pas s'arrêter au chiffre
        val o = assertNotNull(Detector().detect(frame("f0540")))
        val p = o.pillars.first { it.left in 150..200 }
        assertTrue(near(p.gapTop, 640, 4) && near(p.gapBottom, 978, 4), "trou ${p.gapTop}-${p.gapBottom}")
    }
}
