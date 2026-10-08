import fr.triche.stack.logic.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rejoue la vidéo d'une partie humaine (images extraites à 60/s dans le dossier STACK_FRAMES, hors dépôt) :
 * le bot ne joue pas, il doit seulement suivre la partie, compter les poses et mesurer les décalages.
 */
class VideoReplayTest {
    @Test
    fun rejoueUnePartieHumaine() {
        val dir = Support.videoDir() ?: return
        val seg = Segmenter()
        val bot = StackBot(720, 1594, StackSettings())
        var fi = 0
        val poses = ArrayList<Double>()
        bot.log = { if (it.startsWith("POSE")) poses.add(bot.lastError); println("[f$fi] $it") }
        var lastSig = ""
        for (i in 1..3000) {
            fi = i
            val comps = seg.segment(Support.load(java.io.File(dir, "f%04d.png".format(i))))
            val sig = comps.sortedBy { it.xmin * 10000 + it.ymin }.joinToString { "${it.xmin},${it.xmax},${it.ymin},${it.ymax},${it.area}" }
            if (sig == lastSig) continue      // la vidéo répète parfois une image ; l'appareil, lui, n'en livre pas deux fois la même
            lastSig = sig
            bot.onFrame(i / 60.0, comps)
        }
        println("poses comptées : ${bot.score} (le jeu affiche 59 à l'image 3000)")
        assertTrue(bot.score in 55..60, "score compté ${bot.score}")
    }
}
