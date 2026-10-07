import fr.triche.maths.logic.Mode
import fr.triche.maths.logic.QuizBot
import fr.triche.maths.logic.QuizReader
import fr.triche.maths.logic.Settings
import fr.triche.maths.logic.TemplateData
import java.io.File
import kotlin.test.Test

/** Rejoue la vidéo extraite en PNG (dossier donné par QUIZ_FRAMES) ; ignoré sinon. */
class ReplayTest {
    @Test fun rejoue() {
        val dir = System.getenv("QUIZ_FRAMES")?.let { File(it) } ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val bot = QuizBot(QuizReader(TemplateData.bank()), Settings(8.5, -0.08, Mode.NATURAL), java.util.Random(7))
        val sb = StringBuilder()
        var lastStatus = ""
        for ((i, f) in files.withIndex()) {
            val t = i / 60.0
            val d = bot.step(t, Support.load(f))
            if (d != null) sb.append("  t=%.2f décision: bouton %d (question %d) à t=%.2f\n".format(t, d.button + 1, d.question, d.tapAt))
            if (bot.status != lastStatus) { lastStatus = bot.status; sb.append("  t=%.2f [%s]\n".format(t, lastStatus)) }
            bot.takeResult()?.let { sb.append("  t=%.2f RÉSULTAT mesuré=%.2f cible=%.2f\n".format(t, it.measured, it.target)) }
        }
        println("REPLAY\n$sb")
    }
}
