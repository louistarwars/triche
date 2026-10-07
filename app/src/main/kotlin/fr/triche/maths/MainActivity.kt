package fr.triche.maths

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import fr.triche.maths.logic.Mode
import fr.triche.maths.logic.Planner

class MainActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var target: EditText
    private lateinit var offset: EditText
    private lateinit var natural: RadioButton
    private lateinit var fast: RadioButton
    private lateinit var auto: CheckBox
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
            text = "Triche Maths"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 15f, pad / 2)
        col.addView(status)

        col.addView(label("Temps final visé (en secondes)", 16f, pad))
        target = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(prefs.targetSec).replace(',', '.'))
            textSize = 24f
        }
        col.addView(target)
        col.addView(label("Minimum possible : %.1f s. Le chrono du jeu va de l'affichage de la 1re équation à la dernière réponse.".format(Planner.minTotal()), 12f))

        col.addView(label("Répartition du temps", 16f, pad))
        val group = RadioGroup(this)
        natural = RadioButton(this).apply { text = "Naturelle : temps de réflexion variés sur les 5 questions"; id = 1001 }
        fast = RadioButton(this).apply { text = "Rapide puis attente : 4 réponses très vite, puis on attend avant la dernière"; id = 1002 }
        group.addView(natural)
        group.addView(fast)
        group.check(if (prefs.mode == Mode.FAST_THEN_WAIT) 1002 else 1001)
        col.addView(group)

        col.addView(label("Réglage fin (ms, négatif = taper plus tôt)", 16f, pad / 2))
        offset = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(prefs.offsetMs.toString())
        }
        col.addView(offset)
        auto = CheckBox(this).apply {
            text = "Corriger automatiquement après chaque partie (lit le temps affiché à la fin)"
            isChecked = prefs.autoCalibrate
        }
        col.addView(auto)

        col.addView(Button(this).apply {
            text = "1. Activer le service d'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
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

        col.addView(label(
            "Mode d'emploi\n" +
                "• Étape 1 : dans Accessibilité > Applications installées, active « Triche Maths ». " +
                "Si Android l'interdit (réglage restreint), ouvre Infos de l'appli > ⋮ > « Autoriser les paramètres restreints ».\n" +
                "• Étape 2 : « Démarrer le bot » et accepte la capture d'écran.\n" +
                "• Ouvre Quick Maths et lance la partie : le bot lit chaque équation, trouve la bonne réponse " +
                "et la touche au bon moment pour finir pile au temps visé.\n" +
                "• Arrêt : bouton « Arrêter » de la notification.",
            14f, pad,
        ))
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        offset.setText(prefs.offsetMs.toString()) // peut avoir été corrigé par la dernière partie
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

    private fun saveSettings(): Boolean {
        val t = target.text.toString().replace(',', '.').toFloatOrNull()
        if (t == null || t <= 0f) {
            Toast.makeText(this, "Temps visé invalide", Toast.LENGTH_SHORT).show()
            return false
        }
        var v = t
        if (v < Planner.minTotal()) {
            v = Planner.minTotal().toFloat()
            target.setText("%.2f".format(v).replace(',', '.'))
            Toast.makeText(this, "Trop court : minimum %.1f s".format(v), Toast.LENGTH_LONG).show()
        }
        prefs.targetSec = v
        prefs.offsetMs = offset.text.toString().toIntOrNull() ?: prefs.offsetMs
        prefs.mode = if (fast.isChecked) Mode.FAST_THEN_WAIT else Mode.NATURAL
        prefs.autoCalibrate = auto.isChecked
        return true
    }

    private fun saveAndAskCapture() {
        if (!saveSettings()) return
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
