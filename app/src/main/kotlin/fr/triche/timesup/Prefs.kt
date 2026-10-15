package fr.triche.timesup

import android.content.Context
import fr.triche.timesup.logic.Obs

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_timesup", Context.MODE_PRIVATE)

    /** Latence (s) entre l'envoi du toucher et son arrivée dans le jeu, vue depuis nos images. */
    var latency: Float
        get() = sp.getFloat("latency", 0.085f)
        set(v) = sp.edit().putFloat("latency", v).apply()

    /** Correction (px) de la position du bas de la barre. */
    var shift: Float
        get() = sp.getFloat("shift", 0f)
        set(v) = sp.edit().putFloat("shift", v).apply()

    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    /** Observations (inv, z) des décalages saisis, dont on tire latence et correction du bas ; au plus 40 (les plus récentes). */
    var obs: List<Obs>
        get() = (sp.getString("obs", "") ?: "").split(';').mapNotNull {
            val p = it.split(',')
            if (p.size == 2) p[0].toDoubleOrNull()?.let { a -> p[1].toDoubleOrNull()?.let { b -> Obs(a, b) } } else null
        }
        set(v) = sp.edit().putString("obs", v.takeLast(40).joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, it.inv, it.z) }).apply()

    /** Manches de la dernière partie du bot : « inv,latence utilisée,correction utilisée » séparées par « ; ». */
    var lastRun: String
        get() = sp.getString("last_run", "") ?: ""
        set(v) = sp.edit().putString("last_run", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()
}
