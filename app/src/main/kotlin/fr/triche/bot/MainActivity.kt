package fr.triche.bot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Triche"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "1. Activer le service d'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        start = Button(this).apply {
            text = "2. Démarrer le bot"
            setOnClickListener { askCapture() }
        }
        col.addView(start)
        stop = Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                startService(Intent(this@MainActivity, BotService::class.java).setAction(BotService.ACTION_STOP))
                refresh()
            }
        }
        col.addView(stop)

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Étape 2 : appuie sur « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre le jeu, touche l'écran une fois pour lancer la partie : le bot prend le relais " +
                "et passe entre les piliers tout seul.\n" +
                "• Une fois la partie finie, relance toi-même la suivante (le bot se remet en attente).\n" +
                "• Pour arrêter : bouton « Arrêter » de la notification."
        })
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
            (if (run) "✅ Bot en marche" else "⏸ Bot arrêté")
        start.isEnabled = acc && !run
        stop.isEnabled = run
    }

    private fun askCapture() {
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
