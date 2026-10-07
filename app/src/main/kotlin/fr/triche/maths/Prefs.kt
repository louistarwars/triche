package fr.triche.maths

import android.content.Context
import fr.triche.maths.logic.Mode
import fr.triche.maths.logic.Planner
import fr.triche.maths.logic.Settings

/** Réglages de l'app, gardés d'une partie à l'autre. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_maths", Context.MODE_PRIVATE)

    var targetSec: Float
        get() = sp.getFloat("target", 8.5f)
        set(v) = sp.edit().putFloat("target", v).apply()

    /** Décalage des taps en ms (négatif = plus tôt), corrigé automatiquement après chaque partie. */
    var offsetMs: Int
        get() = sp.getInt("offset", -80)
        set(v) = sp.edit().putInt("offset", v).apply()

    var mode: Mode
        get() = if (sp.getInt("mode", 0) == 1) Mode.FAST_THEN_WAIT else Mode.NATURAL
        set(v) = sp.edit().putInt("mode", if (v == Mode.FAST_THEN_WAIT) 1 else 0).apply()

    var autoCalibrate: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()

    fun settings() = Settings(
        targetSec = maxOf(targetSec.toDouble(), Planner.minTotal()),
        offsetSec = offsetMs / 1000.0,
        mode = mode,
    )
}
