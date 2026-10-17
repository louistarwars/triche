package fr.triche.piano

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_piano", Context.MODE_PRIVATE)

    /** Délai (s) entre l'image où l'on voit une touche et l'arrivée du toucher dans le jeu ; appris pendant la partie. */
    var lead: Float
        get() = sp.getFloat("lead", 0.06f)
        set(v) = sp.edit().putFloat("lead", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()
}
