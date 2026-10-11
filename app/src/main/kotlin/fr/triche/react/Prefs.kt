package fr.triche.react

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("triche_react", Context.MODE_PRIVATE)

    /** Délai ajouté avant chaque toucher (ms) : 0 = le plus vite possible. */
    var extraDelay: Int
        get() = sp.getInt("delay", 0)
        set(v) = sp.edit().putInt("delay", v).apply()

    var lastResult: String
        get() = sp.getString("last", "") ?: ""
        set(v) = sp.edit().putString("last", v).apply()
}
