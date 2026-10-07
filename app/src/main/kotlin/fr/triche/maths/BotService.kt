package fr.triche.maths

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import fr.triche.maths.logic.Decision
import fr.triche.maths.logic.Frame
import fr.triche.maths.logic.QuizBot
import fr.triche.maths.logic.QuizReader
import fr.triche.maths.logic.TemplateData

/**
 * Regarde l'écran (MediaProjection), lit et résout les équations, et touche la bonne réponse au moment prévu
 * pour que le chrono du jeu s'arrête au temps visé.
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.maths.STOP"
        private const val CHANNEL = "triche_maths"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheMaths"
        private const val MAX_WIDTH = 720

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private lateinit var prefs: Prefs
    private lateinit var bot: QuizBot

    private var realW = 0
    private var realH = 0
    private var lastNotif = 0L
    private var lastStatus = ""

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

        prefs = Prefs(this)
        bot = QuizBot(QuizReader(TemplateData.bank()), prefs.settings())

        startAsForeground("Démarrage…")
        try {
            startCapture(code, data)
            running = true
        } catch (e: Exception) {
            Log.e(TAG, "Capture impossible", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- notification ----------

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche Maths", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche Maths")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        val text = bot.status
        val now = SystemClock.elapsedRealtime()
        if (!force && (text == lastStatus || now - lastNotif < 700)) return
        lastNotif = now
        lastStatus = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
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
        val scale = if (w > MAX_WIDTH) MAX_WIDTH.toFloat() / w else 1f
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()

        val t = HandlerThread("triche-capture").also { it.start() }
        thread = t
        val hd = Handler(t.looper)
        handler = hd

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        // Obligatoire depuis Android 14 avant createVirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, hd)

        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, hd)
        display = mp.createVirtualDisplay(
            "triche-maths", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, hd,
        )
    }

    private fun now() = System.nanoTime() / 1e9

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val frame = Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            val decision = bot.step(now(), frame)
            if (decision != null) schedule(decision)
            val result = bot.takeResult()
            if (result != null) calibrate(result.measured, result.target)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
        } finally {
            image.close()
        }
    }

    /** Programme le tap à l'instant voulu (l'écran est souvent figé pendant l'attente : pas d'image pour nous réveiller). */
    private fun schedule(d: Decision) {
        val delayMs = ((d.tapAt - now()) * 1000).toLong().coerceAtLeast(0)
        handler?.postDelayed({ fire(d) }, delayMs)
    }

    private fun fire(d: Decision) {
        if (!running || bot.currentDots != d.dots) return // la question a changé entre-temps
        val svc = TapService.instance
        if (svc == null) {
            Log.w(TAG, "Service d'accessibilité inactif")
            return
        }
        val c = fr.triche.maths.logic.QuizReader.BUTTON_CENTERS[d.button]
        val x = c[0] / fr.triche.maths.logic.QuizReader.REF_W * realW
        val y = c[1] / fr.triche.maths.logic.QuizReader.REF_H * realH
        bot.onTapFired(now())
        svc.tap(x, y)
    }

    /** Après chaque partie, corrige le décalage pour que le prochain temps tombe plus près de la cible. */
    private fun calibrate(measured: Double, target: Double) {
        val err = measured - target
        var line = "Dernière partie : %.2f s (visé %.2f s)".format(measured, target)
        if (prefs.autoCalibrate && Math.abs(err) < 1.0) {
            val newOffset = (prefs.offsetMs - 0.6 * err * 1000).toInt().coerceIn(-500, 500)
            line += " → réglage %+d ms".format(newOffset)
            prefs.offsetMs = newOffset
            bot.settings = prefs.settings()
        }
        prefs.lastResult = line
        updateNotification(force = true)
    }

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
        super.onDestroy()
    }
}
