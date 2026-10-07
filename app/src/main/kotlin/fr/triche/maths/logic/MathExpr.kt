package fr.triche.maths.logic

import kotlin.math.abs

/** Évalue "46+2", "7x2", "64-8", "12/4"... (précédence habituelle). Renvoie null si l'expression est invalide. */
object MathExpr {
    fun eval(s: String): Double? {
        val nums = ArrayList<Double>()
        val ops = ArrayList<Char>()
        var i = 0
        while (i < s.length) {
            val st = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == st) return null
            nums.add(s.substring(st, i).toDoubleOrNull() ?: return null)
            if (i < s.length) {
                val c = s[i]
                if (c != '+' && c != '-' && c != 'x' && c != '/') return null
                ops.add(c)
                i++
                if (i >= s.length) return null
            }
        }
        if (nums.isEmpty()) return null
        // d'abord x et /
        var k = 0
        while (k < ops.size) {
            if (ops[k] == 'x' || ops[k] == '/') {
                val a = nums[k]
                val b = nums[k + 1]
                if (ops[k] == '/' && b == 0.0) return null
                nums[k] = if (ops[k] == 'x') a * b else a / b
                nums.removeAt(k + 1)
                ops.removeAt(k)
            } else k++
        }
        var v = nums[0]
        for (j in ops.indices) v = if (ops[j] == '+') v + nums[j + 1] else v - nums[j + 1]
        return v
    }

    fun same(a: Double, b: Long) = abs(a - b) < 1e-6
}
