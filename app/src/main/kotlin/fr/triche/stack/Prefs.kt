package fr.triche.stack

import android.content.Context
import fr.triche.stack.logic.StackSettings

/** Réglages de l'app, gardés d'une partie à l'autre. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_stack", Context.MODE_PRIVATE)

    /** Score visé (nombre de blocs posés) ; 0 = jamais s'arrêter. */
    var target: Int
        get() = sp.getInt("target", 100)
        set(v) = sp.edit().putInt("target", v).apply()

    /** Une fois le score atteint : rater volontairement les blocs pour terminer la partie (sinon on arrête de jouer). */
    var endOnTarget: Boolean
        get() = sp.getBoolean("end", true)
        set(v) = sp.edit().putBoolean("end", v).apply()

    /** Délais appris (secondes) entre l'ordre de toucher et la pose du bloc, par sens de déplacement. */
    var latLeft: Float
        get() = sp.getFloat("latL", 0.11f)
        set(v) = sp.edit().putFloat("latL", v).apply()
    var latRight: Float
        get() = sp.getFloat("latR", 0.11f)
        set(v) = sp.edit().putFloat("latR", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()

    fun resetLatency() {
        latLeft = 0.11f
        latRight = 0.11f
    }

    /** La latence est physiquement la même dans les deux sens : on repart de leur moyenne (valeurs apprises dans [0,03 ; 0,3] s). */
    fun settings(): StackSettings {
        val m = ((latLeft + latRight) / 2).toDouble().coerceIn(0.03, 0.3)
        return StackSettings(targetScore = target, endOnTarget = endOnTarget, latencyLeft = m, latencyRight = m)
    }
}
