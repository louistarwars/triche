package fr.triche.timesup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import fr.triche.timesup.logic.Calibrator

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var latency: EditText
    private lateinit var shift: EditText
    private lateinit var auto: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var calib: TextView
    private val shown = ArrayList<EditText>()
    private val early = ArrayList<Button>()
    private val rows = ArrayList<LinearLayout>()

    private fun label(s: String, sz: Float = 14f, top: Int = 0) = TextView(this).apply {
        text = s
        textSize = sz
        setPadding(0, top, 0, 0)
    }

    private fun num(v: String, decimal: Boolean, signed: Boolean = false) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        setText(v)
    }

    private fun parse(e: EditText): Double? = e.text.toString().trim().replace(',', '.').toDoubleOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Triche Time's Up"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 15f, pad / 2)
        col.addView(status)

        col.addView(Button(this).apply {
            text = "1. Activer le service d'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        auto = CheckBox(this).apply {
            text = "Appuyer toute seule sur « NIVEAU SUIVANT »"
            isChecked = prefs.autoNext
        }
        col.addView(auto)
        start = Button(this).apply {
            text = "2. Démarrer le bot"
            setOnClickListener { saveAndAskCapture() }
        }
        col.addView(start)
        stop = Button(this).apply {
            text = "Arrêter le bot"
            setOnClickListener {
                startService(Intent(this@MainActivity, BotService::class.java).setAction(BotService.ACTION_STOP))
                refresh()
            }
        }
        col.addView(stop)
        col.addView(Button(this).apply {
            text = "Voir / copier le journal"
            setOnClickListener { showJournal() }
        })

        col.addView(label("Régler la précision (après une partie)", 18f, pad))
        calib = label("", 13f)
        col.addView(calib)
        for (i in 1..3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label("Manche $i : décalé de ", 14f).apply { setPadding(0, 0, 8, 0) })
            val e = num("", true)
            e.hint = "0.69"
            e.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val b = Button(this).apply {
                text = "Trop tôt"
                isAllCaps = false
                setOnClickListener { text = if (text == "Trop tôt") "Trop tard" else "Trop tôt" }
            }
            row.addView(e)
            row.addView(b)
            col.addView(row)
            shown.add(e); early.add(b); rows.add(row)
        }
        col.addView(Button(this).apply {
            text = "Régler d'après ces décalages"
            setOnClickListener { applyOffsets() }
        })
        col.addView(label("Valeurs actuelles", 14f, pad / 2))
        latency = num("%.1f".format(java.util.Locale.US, prefs.latency * 1000), true)
        col.addView(label("Latence (ms)", 12f))
        col.addView(latency)
        shift = num("%.2f".format(java.util.Locale.US, prefs.shift), true, signed = true)
        col.addView(label("Correction du bas de la barre (px)", 12f))
        col.addView(shift)
        col.addView(Button(this).apply {
            text = "Enregistrer ces valeurs à la main"
            setOnClickListener { saveManual() }
        })
        col.addView(Button(this).apply {
            text = "Oublier le réglage (repartir de zéro)"
            setOnClickListener {
                prefs.obs = emptyList(); prefs.latency = 0.085f; prefs.shift = 0f
                latency.setText("85.0"); shift.setText("0.00"); refresh()
            }
        })

        col.addView(label(
            "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche Time's Up ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• « Démarrer le bot », accepte la capture d'écran, puis ouvre le jeu TIME'S UP et appuie sur Start.\n" +
                "• Le bot suit la barre, calcule la fin et touche l'écran à ce moment-là, niveau après niveau.\n" +
                "• 1re partie : la latence est recalée toute seule d'après le niveau 1. Ensuite, note les décalages affichés par le " +
                "jeu (« 0.69 s Trop tôt ! »), saisis-les ici et appuie sur « Régler » : la précision s'améliore à chaque partie.\n" +
                "• Arrêt : bouton « Arrêter » de la notification.",
            14f, pad,
        ))
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun lastRunLevels(): List<Triple<Double, Double, Double>> = prefs.lastRun.split(';').mapNotNull {
        val p = it.split(',')
        if (p.size == 3) {
            val a = p[0].toDoubleOrNull(); val b = p[1].toDoubleOrNull(); val c = p[2].toDoubleOrNull()
            if (a != null && b != null && c != null) Triple(a, b, c) else null
        } else null
    }

    private fun refresh() {
        val acc = TapService.instance != null
        val run = BotService.running
        status.text = (if (acc) "✅ Service d'accessibilité actif" else "❌ Service d'accessibilité inactif") + "\n" +
            (if (run) "✅ Bot en marche" else "⏸ Bot arrêté") +
            (prefs.lastResult.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "")
        start.isEnabled = acc && !run
        stop.isEnabled = run
        val n = lastRunLevels().size
        for ((i, r) in rows.withIndex()) r.visibility = if (i < n) android.view.View.VISIBLE else android.view.View.GONE
        calib.text = if (n == 0) "Joue d'abord une partie avec le bot : les manches jouées apparaîtront ici." else
            "Saisis, pour chaque manche, le décalage affiché par le jeu et s'il était trop tôt ou trop tard (laisse vide une manche que tu ne veux pas utiliser). " +
                "Mesures déjà utilisées : ${prefs.obs.size}."
    }

    private fun applyOffsets() {
        val levels = lastRunLevels()
        if (levels.isEmpty()) {
            Toast.makeText(this, "Joue d'abord une partie avec le bot", Toast.LENGTH_LONG).show()
            return
        }
        val obs = ArrayList(prefs.obs)
        var added = 0
        for ((i, lv) in levels.withIndex()) {
            val v = parse(shown[i]) ?: continue
            val signed = if (early[i].text == "Trop tôt") -Math.abs(v) else Math.abs(v)
            obs.add(Calibrator.obs(lv.first, signed, lv.second, lv.third))
            added++
        }
        if (added == 0) {
            Toast.makeText(this, "Saisis au moins un décalage", Toast.LENGTH_LONG).show()
            return
        }
        prefs.obs = obs
        val (l, b) = Calibrator.solve(prefs.obs)!!
        prefs.latency = l.toFloat()
        prefs.shift = b.toFloat()
        prefs.lastRun = ""
        for (e in shown) e.setText("")
        latency.setText("%.1f".format(java.util.Locale.US, l * 1000))
        shift.setText("%.2f".format(java.util.Locale.US, b))
        refresh()
        Toast.makeText(this, "Réglé : latence %.1f ms, bas %+.2f px (%d mesures)".format(l * 1000, b, prefs.obs.size), Toast.LENGTH_LONG).show()
    }

    private fun saveManual() {
        parse(latency)?.let { prefs.latency = (it / 1000).coerceIn(0.0, 0.5).toFloat() }
        parse(shift)?.let { prefs.shift = it.coerceIn(-10.0, 10.0).toFloat() }
        Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show()
    }

    private fun showJournal() {
        val txt = (if (BotService.running) Journal.text() else Journal.text().ifEmpty { Journal.load(this) })
            .ifEmpty { "(journal vide : lance d'abord le bot)" }
        val tv = TextView(this).apply {
            text = txt
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal du bot")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("journal", txt))
                Toast.makeText(this, "Journal copié : colle-le dans la conversation", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun saveAndAskCapture() {
        saveManual()
        prefs.autoNext = auto.isChecked
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
                .putExtra(BotService.EXTRA_CODE, resultCode)
                .putExtra(BotService.EXTRA_DATA, data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
