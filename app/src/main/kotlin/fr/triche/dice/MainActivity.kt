package fr.triche.dice

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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var total: EditText
    private lateinit var latency: EditText
    private lateinit var obtained: EditText
    private lateinit var calib: TextView
    private lateinit var start: Button
    private lateinit var stop: Button

    private fun label(s: String, sz: Float = 14f, top: Int = 0) = TextView(this).apply {
        text = s
        textSize = sz
        setPadding(0, top, 0, 0)
    }

    private fun num(v: String, decimal: Boolean) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
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
            text = "Triche Dice"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 15f, pad / 2)
        col.addView(status)

        col.addView(label("Score voulu : temps total des 3 manches (secondes)", 16f, pad))
        total = num("%.2f".format(prefs.total).replace(',', '.'), true)
        col.addView(total)
        col.addView(label("Le bot lit les dés puis attend, pour que le dernier chiffre tombe au bon moment : chaque manche dure " +
            "(temps restant ÷ manches restantes). En dessous d'environ 1,5 s par manche, il tape simplement au plus vite.", 12f))

        col.addView(label("Latence (ms) — réglée automatiquement par « Corriger la latence » ci-dessous", 14f, pad / 2))
        latency = num("%.0f".format(prefs.latency * 1000), false)
        col.addView(latency)

        col.addView(Button(this).apply {
            text = "1. Activer le service d'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
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

        col.addView(label("Corriger la latence (à faire après chaque partie, surtout la première)", 16f, pad))
        calib = label("", 13f)
        col.addView(calib)
        obtained = num("", true)
        obtained.hint = "Score obtenu au jeu (ex. 9,12)"
        col.addView(obtained)
        col.addView(Button(this).apply {
            text = "Corriger la latence"
            setOnClickListener { correctLatency() }
        })

        col.addView(label(
            "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche Dice ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Choisis le score voulu, puis « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre DICE ROLL et appuie sur Start : le bot lit les dés et tape la somme au bon moment, 3 manches.\n" +
                "• 1re partie = réglage : le temps obtenu peut être décalé de quelques dixièmes. Note le score affiché par le jeu, " +
                "tape-le ci-dessus et appuie sur « Corriger la latence » : les parties suivantes tomberont pile sur l'objectif.\n" +
                "• Arrêt : bouton « Arrêter » de la notification.\n" +
                "• « Voir / copier le journal » : colle-le dans la conversation si quelque chose ne va pas.",
            14f, pad,
        ))
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val acc = TapService.instance != null
        val run = BotService.running
        status.text = (if (acc) "✅ Service d'accessibilité actif" else "❌ Service d'accessibilité inactif") + "\n" +
            (if (run) "✅ Bot en marche" else "⏸ Bot arrêté") +
            (prefs.lastResult.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "")
        start.isEnabled = acc && !run
        stop.isEnabled = run
        calib.text = if (prefs.lastTotal > 0f)
            "Dernière partie du bot : objectif %.2f s avec une latence de %.0f ms.".format(prefs.lastTotal, prefs.lastLatency * 1000)
        else "Pas encore de partie jouée par le bot."
    }

    private fun correctLatency() {
        val score = parse(obtained)
        if (prefs.lastTotal <= 0f || score == null || score <= 0.0) {
            Toast.makeText(this, "Joue d'abord une partie avec le bot, puis tape le score obtenu", Toast.LENGTH_LONG).show()
            return
        }
        // score = objectif + manches × (vraie latence − latence réglée)
        val fresh = (prefs.lastLatency + (score - prefs.lastTotal) / prefs.lastRounds).coerceIn(0.0, 0.8)
        prefs.latency = fresh.toFloat()
        latency.setText("%.0f".format(fresh * 1000))
        obtained.setText("")
        Toast.makeText(this, "Latence réglée à %.0f ms (était %.0f ms)".format(fresh * 1000, prefs.lastLatency * 1000), Toast.LENGTH_LONG).show()
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
        prefs.total = (parse(total) ?: 9.0).coerceIn(1.0, 120.0).toFloat()
        prefs.latency = ((parse(latency) ?: 120.0) / 1000.0).coerceIn(0.0, 0.8).toFloat()
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
