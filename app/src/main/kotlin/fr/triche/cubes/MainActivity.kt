package fr.triche.cubes

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.triche.cubes.logic.CubeCounter

/** Écran unique : aperçu de la caméra (à pointer vers l'écran du jeu) et nombre de boîtes vues à chaque passage. */
class MainActivity : Activity() {
    private lateinit var texture: AutoFitTextureView
    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var history: TextView
    private lateinit var engine: CameraEngine
    private val counter = CubeCounter()
    private val results = ArrayList<Int>()          // un nombre par passage (corrigeable à la main)
    private var stats = ""

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Journal.reset()
        counter.log = { Journal.add(it) }
        engine = CameraEngine(this, counter, { r -> runOnUiThread { onPass(r.count, r.toString()) } }, { s -> stats = s; Journal.add("IMAGES $s") })

        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = AutoFitTextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC101020.toInt())
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        big = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            text = "En attente…"
        }
        sub = TextView(this).apply { textSize = 15f; setTextColor(0xFFB0C4FF.toInt()); gravity = Gravity.CENTER_HORIZONTAL }
        history = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(big)
        panel.addView(sub)
        panel.addView(history)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("−1") { adjust(-1) })
        row.addView(btn("+1") { adjust(+1) })
        row.addView(btn("Remise à zéro") { results.clear(); refresh() })
        row.addView(btn("Journal") { showJournal() })
        panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCamera(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { engine.stop(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        refresh()
        handler.postDelayed(ticker, 150)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 150)
        }
    }

    private fun startCamera(st: SurfaceTexture) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            pendingTexture = st
            return
        }
        val size = engine.chooseSize()
        texture.setAspectRatio(size.height, size.width)      // écran en portrait : largeur/hauteur échangées
        engine.start(st)
    }

    private var pendingTexture: SurfaceTexture? = null

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingTexture?.let { startCamera(it) }
        } else {
            Toast.makeText(this, "La caméra est nécessaire pour compter les boîtes", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPass(count: Int, detail: String) {
        results.add(count)
        Journal.add("PASSAGE ${results.size} : $detail")
        refresh()
    }

    private fun adjust(d: Int) {
        if (results.isEmpty()) return
        results[results.size - 1] = (results.last() + d).coerceAtLeast(0)
        refresh()
    }

    private fun refresh() {
        if (!::big.isInitialized) return
        val live = counter.active
        big.text = when {
            live -> "${counter.liveCount} …"
            results.isEmpty() -> "En attente…"
            else -> "${results.last()} boîtes"
        }
        sub.text = (if (live) "Plateforme en cours de passage" else if (results.isEmpty()) "Filme l'écran du jeu, plateforme comprise"
        else "Niveau ${results.size} : réponse à taper") + (if (stats.isNotEmpty()) "\n$stats" else "")
        history.text = if (results.isEmpty()) "" else "Niveaux : " + results.withIndex().joinToString(" · ") { "${it.index + 1}→${it.value}" } +
            "\nTotal : ${results.sum()} boîtes"
    }

    private fun showJournal() {
        val txt = Journal.text().ifEmpty { "(journal vide)" }
        val tv = TextView(this).apply {
            text = txt
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("journal", txt))
                Toast.makeText(this, "Journal copié : colle-le dans la conversation", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        engine.stop()
        Journal.save(this)
        super.onDestroy()
    }
}

/** Vue qui garde le rapport largeur/hauteur de l'image de la caméra. */
class AutoFitTextureView(ctx: android.content.Context) : TextureView(ctx) {
    private var rw = 9
    private var rh = 16

    fun setAspectRatio(w: Int, h: Int) {
        rw = w
        rh = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w.toLong() * rh / rw).toInt())
    }
}
