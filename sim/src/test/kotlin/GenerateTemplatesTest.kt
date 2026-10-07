import java.io.File
import kotlin.test.Test

/** Régénère app/.../logic/TemplateData.kt depuis la vidéo (GEN_TEMPLATES=1). */
class GenerateTemplatesTest {
    @Test fun generer() {
        if (System.getenv("GEN_TEMPLATES") == null) return
        val bank = Support.buildBank()
        val sb = StringBuilder()
        sb.append("package fr.triche.maths.logic\n\n")
        sb.append("/** Gabarits de caractères mesurés sur la vidéo du jeu (généré par sim/GenerateTemplatesTest). */\n")
        sb.append("object TemplateData {\n    private val LINES = listOf(\n")
        for (l in bank.serialize()) sb.append("        \"").append(l).append("\",\n")
        sb.append("    )\n\n    fun bank() = TemplateBank.parse(LINES)\n}\n")
        File("../app/src/main/kotlin/fr/triche/maths/logic/TemplateData.kt").writeText(sb.toString())
        println("GENERE ${bank.items.size} gabarits")
    }
}
