package fr.triche.stack

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
import fr.triche.stack.logic.Frame
import fr.triche.stack.logic.Segmenter
import fr.triche.stack.logic.StackBot

/**
 * Regarde l'écran (MediaProjection), suit le bloc qui glisse au-dessus de la tour et touche l'écran à l'instant
 * calculé pour qu'il se pose pile sur la tour.
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.stack.STOP"
        private const val CHANNEL = "triche_stack"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheStack"
        private const val MAX_WIDTH = 720

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var tapThread: HandlerThread? = null
    private var tapHandler: Handler? = null

    private lateinit var prefs: Prefs
    private val segmenter = Segmenter()
    private lateinit var bot: StackBot

    private var realW = 0
    private var realH = 0
    private var scale = 1f
    private var lastNotif = 0L
    private var lastStatus = ""
    private var lastSave = 0L
    private var scheduledAt = Double.NaN
    @Volatile
    private var lastTap = -10.0

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
        val (w0, h0) = realSize()
        val sc = if (w0 > MAX_WIDTH) MAX_WIDTH.toFloat() / w0 else 1f
        bot = StackBot((w0 * sc).toInt(), (h0 * sc).toInt(), prefs.settings()) // avant la capture : les images arrivent dès qu'elle démarre
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche Stack", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche Stack")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification() {
        val text = bot.status
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave > 5000) {
            lastSave = now
            saveLatency()
        }
        if (text == lastStatus || now - lastNotif < 800) return
        lastNotif = now
        lastStatus = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun saveLatency() {
        prefs.latLeft = bot.latLeft.toFloat()
        prefs.latRight = bot.latRight.toFloat()
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

        val t = HandlerThread("triche-capture", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        // le toucher part depuis un autre fil : il ne doit pas attendre la fin de l'analyse d'une image
        val tt = HandlerThread("triche-tap", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        tapThread = tt
        tapHandler = Handler(tt.looper)

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
            "triche-stack", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
    }

    private fun now() = System.nanoTime() / 1e9

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val t = now()
            val plane = image.planes[0]
            val frame = Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            val tapAt = bot.onFrame(t, segmenter.segment(frame))
            schedule(tapAt, t)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
        } finally {
            image.close()
        }
    }

    private val tapRunnable = Runnable {
        val t = now()
        scheduledAt = Double.NaN
        val svc = TapService.instance
        if (svc != null) {
            lastTap = t
            svc.tap(realW * 0.5f, realH * 0.70f)
            bot.onTapped(t)
        }
    }

    /** Programme (ou déplace) le toucher à l'instant [tapAt] ; null : plus de toucher prévu. */
    private fun schedule(tapAt: Double?, t: Double) {
        val h = tapHandler ?: return
        if (t - lastTap < 0.9) return                                    // un toucher vient de partir : on attend la pose
        if (tapAt == null) {
            // un toucher imminent n'est pas annulé par une image douteuse
            if (!scheduledAt.isNaN() && scheduledAt - t > 0.06) {
                h.removeCallbacks(tapRunnable)
                scheduledAt = Double.NaN
            }
            return
        }
        if (!scheduledAt.isNaN() && scheduledAt - t < 0.012) return   // trop tard pour le déplacer : il part
        h.removeCallbacks(tapRunnable)
        scheduledAt = tapAt
        h.postDelayed(tapRunnable, ((tapAt - t) * 1000).toLong().coerceAtLeast(0))
    }

    override fun onDestroy() {
        running = false
        tapHandler?.removeCallbacksAndMessages(null)
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        if (::bot.isInitialized) {
            saveLatency()
            prefs.lastResult = "Dernière partie : ${bot.score} blocs (latence apprise ${(bot.latLeft * 1000).toInt()}/${(bot.latRight * 1000).toInt()} ms)"
        }
        thread?.quitSafely()
        tapThread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
        tapThread = null
        tapHandler = null
        super.onDestroy()
    }
}
