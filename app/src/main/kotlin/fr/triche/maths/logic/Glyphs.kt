package fr.triche.maths.logic

import kotlin.math.ln
import kotlin.math.min

/** Un caractère isolé dans l'image, résumé en une petite grille de niveaux de remplissage. */
class Glyph(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val parts: Int, val feat: FloatArray) {
    val w get() = x1 - x0 + 1
    val h get() = y1 - y0 + 1
    val aspect get() = w.toFloat() / h
}

private class IntList {
    var a = IntArray(256)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
}

private class Blob(var x0: Int, var y0: Int, var x1: Int, var y1: Int) {
    val px = IntList()
}

object Glyphs {
    const val GW = 10
    const val GH = 14

    /**
     * Isole les caractères de [r] : composantes connexes des pixels "encre", puis fusion des morceaux
     * superposés en x (le "=" et le "?" sont faits de deux morceaux, le "÷" de trois).
     */
    fun extract(f: Frame, r: Rect, ink: (Int) -> Boolean): List<Glyph> {
        val rw = r.w
        val rh = r.h
        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) mask[y * rw + x] = ink(f.rgb(r.x0 + x, r.y0 + y))

        val seen = BooleanArray(rw * rh)
        val stack = IntArray(rw * rh)
        val blobs = ArrayList<Blob>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val b = Blob(rw, rh, 0, 0)
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % rw
                val y = p / rw
                b.px.add(p)
                if (x < b.x0) b.x0 = x
                if (x > b.x1) b.x1 = x
                if (y < b.y0) b.y0 = y
                if (y > b.y1) b.y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                    val q = ny * rw + nx
                    if (mask[q] && !seen[q]) {
                        seen[q] = true
                        stack[sp++] = q
                    }
                }
            }
            if (b.px.n >= 8) blobs.add(b)
        }
        blobs.sortBy { it.x0 }

        val groups = ArrayList<ArrayList<Blob>>()
        var gx0 = 0
        var gx1 = 0
        for (b in blobs) {
            val g = groups.lastOrNull()
            if (g != null) {
                val overlap = min(gx1, b.x1) - maxOf(gx0, b.x0) + 1
                val minW = min(gx1 - gx0 + 1, b.x1 - b.x0 + 1)
                if (overlap * 2 >= minW) {
                    g.add(b)
                    gx0 = min(gx0, b.x0)
                    gx1 = maxOf(gx1, b.x1)
                    continue
                }
            }
            groups.add(arrayListOf(b))
            gx0 = b.x0
            gx1 = b.x1
        }

        return groups.map { g ->
            val x0 = g.minOf { it.x0 }
            val y0 = g.minOf { it.y0 }
            val x1 = g.maxOf { it.x1 }
            val y1 = g.maxOf { it.y1 }
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            val cnt = FloatArray(GW * GH)
            for (b in g) for (i in 0 until b.px.n) {
                val p = b.px.a[i]
                val cx = min(GW - 1, ((p % rw) - x0) * GW / w)
                val cy = min(GH - 1, ((p / rw) - y0) * GH / h)
                cnt[cy * GW + cx] += 1f
            }
            val cell = maxOf(1f, w.toFloat() / GW * h / GH)
            for (i in cnt.indices) cnt[i] = min(1f, cnt[i] / cell)
            Glyph(r.x0 + x0, r.y0 + y0, r.x0 + x1, r.y0 + y1, g.size, cnt)
        }
    }
}

class Template(val label: String, val aspect: Float, val parts: Int, val feat: FloatArray)

class Match(val label: String, val dist: Float, val second: Float)

/** Banque de gabarits : reconnaît un caractère par le plus proche voisin (forme, proportions, nombre de morceaux). */
class TemplateBank {
    val items = ArrayList<Template>()

    fun add(label: String, g: Glyph) {
        val t = Template(label, g.aspect, g.parts, g.feat)
        if (items.none { it.label == label && distance(it, g.feat, g.aspect, g.parts) < 0.002f }) items.add(t)
    }

    fun add(t: Template) {
        items.add(t)
    }

    fun classify(g: Glyph): Match {
        var best = Float.MAX_VALUE
        var bestLabel = "?"
        for (t in items) {
            val d = distance(t, g.feat, g.aspect, g.parts)
            if (d < best) { best = d; bestLabel = t.label }
        }
        var second = Float.MAX_VALUE
        for (t in items) {
            if (t.label == bestLabel) continue
            val d = distance(t, g.feat, g.aspect, g.parts)
            if (d < second) second = d
        }
        return Match(bestLabel, best, second)
    }

    private fun distance(t: Template, feat: FloatArray, aspect: Float, parts: Int): Float {
        var d = 0f
        for (i in feat.indices) {
            val x = t.feat[i] - feat[i]
            d += x * x
        }
        d /= feat.size
        val la = ln(t.aspect / aspect)
        d += 0.3f * la * la
        if (t.parts != parts) d += 0.3f
        return d
    }

    /** Une ligne par gabarit : `label|aspect|parts|v0 v1 ...` (valeurs en pour-cent). */
    fun serialize(): List<String> = items.map { t ->
        "${t.label}|${"%.3f".format(java.util.Locale.ROOT, t.aspect)}|${t.parts}|" +
            t.feat.joinToString(" ") { (it * 100f + 0.5f).toInt().toString() }
    }

    companion object {
        fun parse(lines: List<String>): TemplateBank {
            val b = TemplateBank()
            for (line in lines) {
                val p = line.split('|')
                b.add(Template(p[0], p[1].toFloat(), p[2].toInt(), p[3].split(' ').map { it.toInt() / 100f }.toFloatArray()))
            }
            return b
        }
    }
}
