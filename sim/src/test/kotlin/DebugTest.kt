import fr.triche.maths.logic.QuizReader
import fr.triche.maths.logic.TemplateBank
import kotlin.test.Test

class DebugTest {
    @Test fun dump() {
        val r = QuizReader(TemplateBank())
        for (n in listOf("m0280", "m0650")) {
            val f = Support.frame(n)
            for (b in 0 until 4) println("DBG $n btn$b " + r.buttonGlyphs(f, b).joinToString { "[${it.x0},${it.y0} ${it.w}x${it.h} p${it.parts}]" })
            println("DBG $n eq " + r.equationGlyphs(f).joinToString { "[${it.x0},${it.y0} ${it.w}x${it.h} p${it.parts}]" })
        }
    }
}
