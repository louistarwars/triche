import fr.triche.maths.logic.MathExpr
import fr.triche.maths.logic.QuizReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderTest {
    private val reader = QuizReader(Support.buildBank())

    @Test fun glyphesParQuestion() {
        // construire la banque vérifie déjà que chaque image donne le bon nombre de glyphes
        val bank = Support.buildBank()
        println("GABARITS ${bank.items.size} : ${bank.items.map { it.label }.distinct().sorted().joinToString("")}")
    }

    @Test fun ecranDeQuestionEtPoints() {
        for ((i, n) in listOf("m0280", "m0400", "m0470", "m0650", "m0900").withIndex()) {
            val s = assertNotNull(reader.screen(Support.frame(n)), n)
            assertEquals(i, s.dots, "$n points")
            assertTrue(s.normal, "$n normal")
        }
    }

    @Test fun flashApresReponse() {
        for (n in listOf("m0320", "m0430", "m0515", "m0800")) {
            val s = reader.screen(Support.frame(n))
            println("FLASH $n -> ${s?.dots} normal=${s?.normal}")
            assertNotNull(s, n)
            assertTrue(!s.normal, "$n doit être un flash")
        }
    }

    @Test fun autresEcransIgnores() {
        for (n in listOf("m0020", "m0100", "m0200", "m1285")) assertNull(reader.screen(Support.frame(n)), n)
    }

    @Test fun lectureDesCinqQuestionsAvecToutesLesDonnees() {
        for ((i, t) in Support.truths.withIndex()) {
            val q = reader.read(Support.frame(t.frames[0]))
            println("Q${i + 1}: ${q.equation} = ${q.value} réponses=${q.answers} -> ${q.answerIndex}")
            assertEquals(t.equation, q.equation)
            assertEquals(t.answer, q.answerIndex)
        }
    }

    /** Chaque question est lue avec des gabarits issus des AUTRES questions seulement. */
    @Test fun lectureSansTricher() {
        for ((i, t) in Support.truths.withIndex()) {
            if (i == 2) continue // le "x" n'apparaît que dans cette question : pas de gabarit sans elle
            val r = QuizReader(Support.buildBank(exclude = i))
            val q = r.read(Support.frame(t.frames[0]))
            println("SANS-TRICHE Q${i + 1}: lu '${q.equation}' boutons=${q.answers} -> ${q.answerIndex}")
            assertEquals(t.answer, q.answerIndex, "Q${i + 1}")
        }
    }

    @Test fun scoreDeFin() {
        assertEquals(14.31, reader.readScore(Support.frame("m1285")))
    }

    @Test fun calcul() {
        assertEquals(90.0, MathExpr.eval("8+82"))
        assertEquals(14.0, MathExpr.eval("7x2"))
        assertEquals(56.0, MathExpr.eval("64-8"))
        assertEquals(11.0, MathExpr.eval("3+4x2"))
        assertEquals(5.0, MathExpr.eval("20/4"))
        assertNull(MathExpr.eval("8+"))
        assertNull(MathExpr.eval("+8"))
    }
}
