package fr.triche.stack

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

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var target: EditText
    private lateinit var endOnTarget: CheckBox
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
            text = "Triche Stack"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 15f, pad / 2)
        col.addView(status)

        col.addView(label("Score visé (nombre de blocs, 0 = jamais s'arrêter)", 16f, pad))
        target = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.target.toString())
        }
        col.addView(target)
        endOnTarget = CheckBox(this).apply {
            text = "Une fois le score atteint, rater volontairement pour terminer la partie"
            isChecked = prefs.endOnTarget
        }
        col.addView(endOnTarget)
        col.addView(label(
            "Décoché : le bot arrête simplement de jouer au score visé (la partie reste en cours). " +
                "Coché : il fait tomber les blocs à côté ; selon la largeur restante, le score final peut dépasser la cible d'un ou deux blocs.",
            12f,
        ))

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
            text = "Oublier la latence apprise"
            setOnClickListener { prefs.resetLatency(); refresh() }
        })

        col.addView(label(
            "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche Stack ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Étape 2 : « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre le jeu et touche l'écran pour lancer la partie : le bot prend le relais dès qu'il voit le bloc glisser.\n" +
                "• Les premiers blocs servent à calibrer le délai de réaction du téléphone (un peu de décalage au début est normal) ; " +
                "il est mémorisé pour les parties suivantes.\n" +
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

    private fun refresh() {
        val acc = TapService.instance != null
        val run = BotService.running
        status.text = (if (acc) "✅ Service d'accessibilité actif" else "❌ Service d'accessibilité inactif") + "\n" +
            (if (run) "✅ Bot en marche" else "⏸ Bot arrêté") + "\n" +
            "Latence mémorisée : ${(prefs.latLeft * 1000).toInt()} / ${(prefs.latRight * 1000).toInt()} ms" +
            (prefs.lastResult.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "")
        start.isEnabled = acc && !run
        stop.isEnabled = run
    }

    private fun saveAndAskCapture() {
        prefs.target = (target.text.toString().toIntOrNull() ?: prefs.target).coerceAtLeast(0)
        prefs.endOnTarget = endOnTarget.isChecked
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
