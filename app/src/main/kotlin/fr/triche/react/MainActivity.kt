package fr.triche.react

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

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var delay: EditText
    private lateinit var start: Button
    private lateinit var stop: Button

    private fun label(s: String, sz: Float = 14f, top: Int = 0) = TextView(this).apply {
        text = s
        textSize = sz
        setPadding(0, top, 0, 0)
    }

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
            text = "Triche React"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 15f, pad / 2)
        col.addView(status)

        col.addView(label("Délai ajouté avant chaque toucher (ms)", 16f, pad))
        delay = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.extraDelay.toString())
        }
        col.addView(delay)
        col.addView(label("0 = le plus vite possible. Monte-le (par ex. 150) si tu veux un temps de réaction plus « humain ».", 12f))

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

        col.addView(label(
            "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche React ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Étape 2 : « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre le jeu REACT, lance la partie (Start) : dès qu'un cercle devient vert, le bot le touche.\n" +
                "• Arrêt : bouton « Arrêter » de la notification.\n" +
                "• Après une partie, « Voir / copier le journal » : colle-le ici si le temps de réaction n'est pas bon.",
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
                android.widget.Toast.makeText(this, "Journal copié : colle-le dans la conversation", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun saveAndAskCapture() {
        prefs.extraDelay = (delay.text.toString().toIntOrNull() ?: 0).coerceIn(0, 2000)
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
