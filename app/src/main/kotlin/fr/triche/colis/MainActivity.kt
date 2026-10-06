package fr.triche.colis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
            text = "Triche Colis"
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
        col.addView(Button(this).apply {
            text = "2. Autoriser le bouton STOP flottant"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        start = Button(this).apply {
            text = "3. Démarrer le bot"
            setOnClickListener { askCapture() }
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

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche Colis ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Étape 2 : autorise l'affichage par-dessus les autres applis (bouton STOP).\n" +
                "• Étape 3 : « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre Parcel Panic et lance la partie : dès que le tapis apparaît, le bot trie les colis " +
                "(rouge ← gauche, jaune ↑ haut, bleu → droite).\n" +
                "• Le bouton rouge « ■ STOP n » (déplaçable) affiche le nombre de colis triés. " +
                "Appuie dessus quand tu as le score voulu : le bot arrête de glisser (« ▶ GO » pour reprendre). " +
                "Les colis suivants ne seront pas triés et la partie se termine sur ce score.\n" +
                "• Arrêt complet : bouton « Arrêter » de la notification."
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
        val over = Settings.canDrawOverlays(this)
        val run = BotService.running
        status.text = (if (acc) "✅ Service d'accessibilité actif" else "❌ Service d'accessibilité inactif") + "\n" +
            (if (over) "✅ Bouton STOP flottant autorisé" else "⚠️ Bouton STOP flottant non autorisé (l'arrêt reste possible via la notification)") + "\n" +
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
