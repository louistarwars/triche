package fr.triche.dangerwall

import android.content.Context
import fr.triche.dangerwall.logic.PilotSettings

/** Réglages de l'app, gardés d'une partie à l'autre. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_dangerwall", Context.MODE_PRIVATE)

    /** Marge de sécurité autour de la balle (px). Plus grande = plus prudent. */
    var margin: Int
        get() = sp.getInt("margin", 26)
        set(v) = sp.edit().putInt("margin", v).apply()

    /** S'arrêter après ce nombre de points (0 = jamais). */
    var stopAt: Int
        get() = sp.getInt("stop", 0)
        set(v) = sp.edit().putInt("stop", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()

    fun settings() = PilotSettings(margin = margin.toDouble(), stopAt = stopAt)
}
