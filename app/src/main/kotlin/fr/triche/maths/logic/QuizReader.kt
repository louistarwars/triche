package fr.triche.maths.logic

/** État de l'écran de question (ou null quand ce n'est pas un écran de question). */
class ScreenInfo(
    /** Nombre de points foncés en haut = nombre de questions déjà répondues (0 à 4). */
    val dots: Int,
    /** Les 4 boutons sont à leur couleur normale (pas de flash vert après une réponse). */
    val normal: Boolean,
)

/** Question lue : équation, résultat attendu, valeurs des 4 boutons et indice du bon bouton. */
class Question(val equation: String, val value: Double?, val answers: List<Long?>, val answerIndex: Int?)

/**
 * Lit l'écran du jeu "Quick Maths". Mise en page mesurée sur l'écran 720 x 1594 de la vidéo
 * (carte blanche avec l'équation, 4 boutons rose / vert / bleu / jaune dans cet ordre, points de progression).
 */
class QuizReader(private val bank: TemplateBank) {
    companion object {
        const val REF_W = 720f
        const val REF_H = 1594f

        // centres des boutons : haut-gauche (rose), haut-droite (vert), bas-gauche (bleu), bas-droite (jaune)
        val BUTTON_CENTERS = arrayOf(floatArrayOf(190f, 975f), floatArrayOf(530f, 975f), floatArrayOf(190f, 1170f), floatArrayOf(530f, 1170f))
        // zone du texte : au milieu de chaque bouton, loin des bouts arrondis où le fond crème serait pris pour du blanc
        private val BUTTON_TEXT = arrayOf(intArrayOf(80, 925, 300, 1025), intArrayOf(420, 925, 640, 1025), intArrayOf(80, 1120, 300, 1220), intArrayOf(420, 1120, 640, 1220))
        private val BUTTON_PROBE = arrayOf(intArrayOf(100, 910), intArrayOf(440, 910), intArrayOf(100, 1110), intArrayOf(440, 1110))
        private val DOT_X = intArrayOf(301, 331, 360, 389, 417)
        const val DOT_Y = 508

        private fun isPinkBtn(c: Int) = red(c) > 230 && green(c) in 70..150 && blue(c) in 110..200
        private fun isGreenBtn(c: Int) = green(c) > 190 && red(c) < 120 && blue(c) in 90..170
        private fun isBlueBtn(c: Int) = blue(c) > 220 && green(c) in 150..215 && red(c) < 110
        private fun isYellowBtn(c: Int) = red(c) > 230 && green(c) in 160..210 && blue(c) < 90
        private val PROBES = arrayOf(::isPinkBtn, ::isGreenBtn, ::isBlueBtn, ::isYellowBtn)

        // couleurs exactes des boutons au repos ; le flash après une réponse est un vert plus sombre (33,196,93)
        private val NORMAL = intArrayOf(0xFE6B9D, 0x49DC7F, 0x37BCF8, 0xF9BD22)
        private fun near(c: Int, ref: Int, tol: Int) =
            Math.abs(red(c) - red(ref)) <= tol && Math.abs(green(c) - green(ref)) <= tol && Math.abs(blue(c) - blue(ref)) <= tol

        private fun isDarkInk(c: Int) = red(c) + green(c) + blue(c) <= 420
        private fun isWhiteInk(c: Int) = minOf(red(c), green(c), blue(c)) >= 190
    }

    private fun sx(f: Frame) = f.w / REF_W
    private fun sy(f: Frame) = f.h / REF_H

    private fun rect(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int) =
        Rect((x0 * sx(f)).toInt(), (y0 * sy(f)).toInt(), (x1 * sx(f)).toInt(), (y1 * sy(f)).toInt())

    private fun px(f: Frame, x: Int, y: Int) = f.rgb((x * sx(f)).toInt().coerceIn(0, f.w - 1), (y * sy(f)).toInt().coerceIn(0, f.h - 1))

    fun screen(f: Frame): ScreenInfo? {
        // carte blanche
        var white = 0
        var tot = 0
        var y = 560
        while (y < 790) {
            var x = 60
            while (x < 660) {
                val c = px(f, x, y)
                if (minOf(red(c), green(c), blue(c)) > 240) white++
                tot++
                x += 12
            }
            y += 12
        }
        if (white * 100 < tot * 85) return null
        // boutons
        var ok = 0
        var exact = 0
        for (i in 0 until 4) {
            val p = BUTTON_PROBE[i]
            val c = px(f, p[0], p[1])
            if (PROBES[i](c) || near(c, 0x21C45D, 25)) ok++
            if (near(c, NORMAL[i], 22)) exact++
        }
        if (ok < 3) return null
        var dots = 0
        for (x in DOT_X) {
            val c = px(f, x, DOT_Y)
            if (red(c) + green(c) + blue(c) < 300) dots++
        }
        return ScreenInfo(dots, exact == 4)
    }

    fun equationGlyphs(f: Frame): List<Glyph> = Glyphs.extract(f, rect(f, 50, 590, 670, 770), ::isDarkInk)

    fun buttonGlyphs(f: Frame, i: Int): List<Glyph> {
        val b = BUTTON_TEXT[i]
        return Glyphs.extract(f, rect(f, b[0], b[1], b[2], b[3]), ::isWhiteInk)
    }

    fun scoreGlyphs(f: Frame): List<Glyph> = Glyphs.extract(f, rect(f, 150, 505, 570, 585), ::isWhiteInk)

    private fun text(gl: List<Glyph>) = gl.joinToString("") { bank.classify(it).label }

    fun read(f: Frame): Question {
        val eq = text(equationGlyphs(f))
        val left = eq.substringBefore('=', "")
        val value = if (eq.contains('=')) MathExpr.eval(left) else null
        val answers = (0 until 4).map { i ->
            parseInt(text(buttonGlyphs(f, i)))
        }
        var idx: Int? = null
        if (value != null) {
            val hits = answers.indices.filter { answers[it] != null && MathExpr.same(value, answers[it]!!) }
            if (hits.size == 1) idx = hits[0]
        }
        return Question(eq, value, answers, idx)
    }

    private fun parseInt(s: String): Long? {
        if (s.isEmpty() || s.length > 9) return null
        val neg = s.startsWith("-")
        val d = if (neg) s.substring(1) else s
        if (d.isEmpty() || !d.all { it.isDigit() }) return null
        return d.toLong() * if (neg) -1 else 1
    }

    /** Score affiché à l'écran de fin ("14,31" secondes), ou null s'il n'est pas (encore) lisible. */
    fun readScore(f: Frame): Double? {
        val s = text(scoreGlyphs(f))
        if (!Regex("^\\d{1,3},\\d\\d$").matches(s)) return null
        return s.replace(',', '.').toDouble()
    }
}
