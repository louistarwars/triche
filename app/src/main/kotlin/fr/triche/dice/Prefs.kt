package fr.triche.dice

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_dice", Context.MODE_PRIVATE)

    /** Score total voulu, en secondes (somme des trois manches). */
    var total: Float
        get() = sp.getFloat("total", 9f)
        set(v) = sp.edit().putFloat("total", v).apply()

    /**
     * Latence (s) entre l'envoi du dernier toucher et l'arrêt du chronomètre, vue depuis nos images : elle s'apprend avec
     * le score obtenu (« Corriger la latence »).
     */
    var latency: Float
        get() = sp.getFloat("latency", 0.12f)
        set(v) = sp.edit().putFloat("latency", v).apply()

    /** Objectif et latence de la dernière partie jouée par le bot, pour corriger la latence d'après le score réellement obtenu. */
    var lastTotal: Float
        get() = sp.getFloat("last_total", 0f)
        set(v) = sp.edit().putFloat("last_total", v).apply()

    var lastLatency: Float
        get() = sp.getFloat("last_latency", 0f)
        set(v) = sp.edit().putFloat("last_latency", v).apply()

    var lastRounds: Int
        get() = sp.getInt("last_rounds", 0)
        set(v) = sp.edit().putInt("last_rounds", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()
}
