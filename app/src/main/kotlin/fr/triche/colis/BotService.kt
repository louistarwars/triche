package fr.triche.colis

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import fr.triche.colis.logic.Detector
import fr.triche.colis.logic.Dir
import fr.triche.colis.logic.Frame
import fr.triche.colis.logic.Sorter
import kotlin.math.abs

/**
 * Regarde l'écran (MediaProjection), repère les colis sur le tapis et demande à [TapService]
 * de glisser dans le bon sens. Un bouton flottant STOP/GO (avec le compteur de colis triés)
 * permet de s'arrêter au score voulu.
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.colis.STOP"
        private const val CHANNEL = "triche_colis"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheColis"
        private const val MAX_WIDTH = 720
        private const val SWIPE_MS = 70L

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val main = Handler(Looper.getMainLooper())

    private val detector = Detector()
    private val sorter = Sorter()

    @Volatile private var paused = false
    @Volatile private var resetRequested = false
    private var inGame = false
    private var score = 0
    private var lastCount = 0
    private var lastNotif = 0L

    private var realW = 0
    private var realH = 0
    private var scale = 1f

    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground("Démarrage…")
        try {
            startCapture(code, data)
            running = true
            main.post { addOverlay() }
        } catch (e: Exception) {
            Log.e(TAG, "Capture impossible", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- notification ----------

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche Colis", NotificationManager.IMPORTANCE_LOW))
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Arrêter", stop,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Triche Colis")
            .setContentText(text)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif < 1000) return
        lastNotif = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ---------- bouton flottant ----------

    private fun overlayLabel(): String = if (paused) "▶ GO  $score" else "■ STOP  $score"

    private fun refreshOverlay() {
        val label = overlayLabel()
        val colour = if (paused) 0xE62E7D32.toInt() else 0xE6C62828.toInt()
        main.post {
            val v = overlay ?: return@post
            v.text = label
            (v.background as? GradientDrawable)?.setColor(colour)
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            val ph = (14 * dp).toInt()
            val pv = (12 * dp).toInt()
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply { cornerRadius = 30 * dp }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (6 * dp).toInt()
            y = (realH * 0.74f).toInt() // marge gauche, à côté du tapis
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 12 * dp || abs(e.rawY - downY) > 12 * dp) moved = true
                    if (moved) {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePause()
            }
            true
        }
        try {
            wm.addView(view, lp)
            overlay = view
            overlayParams = lp
            refreshOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "Bouton flottant impossible", e)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun togglePause() {
        paused = !paused
        if (!paused) resetRequested = true // le thread de capture remet le décideur à zéro
        refreshOverlay()
        updateNotification(if (paused) "En pause • $score colis triés" else "Reprise")
    }

    // ---------- capture ----------

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun startCapture(code: Int, data: Intent) {
        val (w, h) = realSize()
        realW = w
        realH = h
        scale = if (w > MAX_WIDTH) MAX_WIDTH.toFloat() / w else 1f
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()

        val t = HandlerThread("triche-capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        // Obligatoire depuis Android 14 avant createVirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        display = mp.createVirtualDisplay(
            "triche-colis", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
    }

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            if (paused) return
            val plane = image.planes[0]
            process(Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
        } finally {
            image.close()
        }
    }

    private fun process(frame: Frame) {
        if (resetRequested) {
            resetRequested = false
            sorter.reset()
            lastCount = 0
        }
        val scene = detector.detect(frame)
        if (scene == null) {
            if (inGame) {
                inGame = false
                updateNotification("En attente d'une partie… (dernier score : $score)")
            }
            return
        }
        if (!inGame) {
            inGame = true
            sorter.reset()
            lastCount = 0
            score = 0
            refreshOverlay()
        }

        val dir = sorter.step(System.nanoTime() / 1e9, scene) ?: return
        val svc = TapService.instance
        if (svc == null) {
            updateNotification("Service d'accessibilité inactif : active-le dans les réglages")
            return
        }
        val x0 = realW * 0.5f
        val y0 = realH * 0.66f
        val dx = realW * 0.30f
        val dy = realH * 0.16f
        when (dir) {
            Dir.LEFT -> svc.swipe(x0, y0, x0 - dx, y0, SWIPE_MS)
            Dir.RIGHT -> svc.swipe(x0, y0, x0 + dx, y0, SWIPE_MS)
            Dir.UP -> svc.swipe(x0, y0, x0, y0 - dy, SWIPE_MS)
        }
        if (sorter.count != lastCount) {
            score += sorter.count - lastCount
            lastCount = sorter.count
            refreshOverlay()
        }
        updateNotification("En jeu • $score colis triés")
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
        main.post { removeOverlay() }
        super.onDestroy()
    }
}
