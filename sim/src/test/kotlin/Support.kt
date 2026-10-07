import fr.triche.maths.logic.Frame
import fr.triche.maths.logic.Glyphs
import fr.triche.maths.logic.QuizReader
import fr.triche.maths.logic.TemplateBank
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

object Support {
    fun frameOf(img: java.awt.image.BufferedImage): Frame {
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

    private val cache = HashMap<String, Frame>()
    fun frame(name: String): Frame = cache.getOrPut(name) {
        frameOf(ImageIO.read(Support::class.java.getResourceAsStream("/frames/$name.png")))
    }

    fun load(f: File): Frame = frameOf(ImageIO.read(f))

    /** Vérité terrain, relevée à la main sur la vidéo : image -> (équation, 4 boutons haut-gauche, haut-droite, bas-gauche, bas-droite). */
    class Truth(val frames: List<String>, val equation: String, val buttons: List<String>, val answer: Int)

    val truths = listOf(
        Truth(listOf("m0280"), "8+82=?", listOf("85", "101", "95", "90"), 3),
        Truth(listOf("m0400"), "46+2=?", listOf("42", "48", "50", "54"), 1),
        Truth(listOf("m0470"), "7x2=?", listOf("19", "14", "12", "13"), 1),
        Truth(listOf("m0650"), "64-8=?", listOf("57", "53", "49", "56"), 3),
        Truth(listOf("m0900"), "15-7=?", listOf("5", "3", "8", "6"), 2),
    )

    /** Construit la banque de gabarits depuis la vérité terrain (en excluant éventuellement une question). */
    fun buildBank(exclude: Int = -1, withScore: Boolean = true): TemplateBank {
        val bank = TemplateBank()
        val reader = QuizReader(TemplateBank())
        for ((qi, t) in truths.withIndex()) {
            if (qi == exclude) continue
            for (fn in t.frames) {
                val f = frame(fn)
                val eq = reader.equationGlyphs(f)
                check(eq.size == t.equation.length) { "$fn équation : ${eq.size} glyphes pour '${t.equation}'" }
                for ((i, g) in eq.withIndex()) bank.add(t.equation[i].toString(), g)
                for (b in 0 until 4) {
                    val gl = reader.buttonGlyphs(f, b)
                    check(gl.size == t.buttons[b].length) { "$fn bouton $b : ${gl.size} glyphes pour '${t.buttons[b]}'" }
                    for ((i, g) in gl.withIndex()) bank.add(t.buttons[b][i].toString(), g)
                }
            }
        }
        if (withScore) {
            val gl = reader.scoreGlyphs(frame("m1285"))
            val s = "14,31"
            check(gl.size == s.length) { "score : ${gl.size} glyphes pour '$s'" }
            for ((i, g) in gl.withIndex()) bank.add(s[i].toString(), g)
        }
        bank.add(divisionTemplate())
        return bank
    }

    /** Gabarit synthétique du signe ÷ (point, barre, point) : il n'apparaît pas dans la vidéo. */
    fun divisionTemplate(): fr.triche.maths.logic.Template {
        val feat = FloatArray(Glyphs.GW * Glyphs.GH)
        for (x in 0 until Glyphs.GW) { feat[6 * Glyphs.GW + x] = 1f; feat[7 * Glyphs.GW + x] = 1f }
        for (y in intArrayOf(0, 1, 12, 13)) for (x in 4..5) feat[y * Glyphs.GW + x] = 1f
        return fr.triche.maths.logic.Template("/", 1.0f, 3, feat)
    }
}
