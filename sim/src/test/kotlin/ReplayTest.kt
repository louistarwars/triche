import fr.triche.colis.logic.Detector
import fr.triche.colis.logic.Sorter
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Rejoue une vidéo extraite en PNG (dossier donné par PARCEL_FRAMES) ; ignoré sinon. */
class ReplayTest {
    @Test fun rejoue() {
        val dir = System.getenv("PARCEL_FRAMES")?.let { File(it) } ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val det = Detector()
        val sorter = Sorter()
        var inGame = false
        var distinct = 0
        var retries = 0
        val sb = StringBuilder()
        var prevCount = 0
        for ((i, f) in files.withIndex()) {
            val scene = det.detect(DetectorTest.frameOf(ImageIO.read(f)))
            if (scene != null && !inGame) { inGame = true; sorter.reset(); prevCount = 0; sb.append("== début de partie à ${"%.2f".format(i / 60.0)} s\n") }
            if (scene == null && inGame) { inGame = false; sb.append("== fin de partie à ${"%.2f".format(i / 60.0)} s (${sorter.count} colis)\n") }
            val d = sorter.step(i / 60.0, scene)
            if (d != null) {
                val front = scene!!.parcels.maxByOrNull { it.bottom }!!
                if (sorter.count > prevCount) { distinct++; prevCount = sorter.count } else retries++
                sb.append("  t=%.2f %-5s c%d bas=%d n=%d %s\n".format(i / 60.0, d, front.color, front.bottom, sorter.count, scene.parcels.joinToString(",") { "c${it.color}:${it.bottom}" }))
            }
        }
        println("REPLAY colis distincts=$distinct relances=$retries\n$sb")
    }
}
