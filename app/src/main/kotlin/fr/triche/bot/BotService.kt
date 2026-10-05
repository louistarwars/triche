package fr.triche.bot

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
import fr.triche.bot.logic.Controller
import fr.triche.bot.logic.Detector
import fr.triche.bot.logic.Frame

/**
 * Regarde l'écran (MediaProjection), repère la balle et les piliers, et demande à [TapService]
 * de toucher l'écran au bon moment.
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.bot.STOP"
        private const val CHANNEL = "triche"
        private const val NOTIF_ID = 1
        private const val TAG = "Triche"
        private const val MAX_WIDTH = 720

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    private val detector = Detector()
    private val controller = Controller()

    private var scale = 1f
    private var inGame = false
    private var idleFrames = 0
    private var taps = 0
    private var lastNotif = 0L

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
        } catch (e: Exception) {
            Log.e(TAG, "Capture impossible", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche")
            .setContentText(text)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

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
        val (realW, realH) = realSize()
        scale = if (realW > MAX_WIDTH) MAX_WIDTH.toFloat() / realW else 1f
        val cw = (realW * scale).toInt()
        val ch = (realH * scale).toInt()

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
            "triche", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
    }

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            // Hors partie, on n'analyse qu'une image sur quatre pour ménager la batterie.
            if (!inGame && (idleFrames++ % 4) != 0) return
            val plane = image.planes[0]
            val frame = Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            process(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
        } finally {
            image.close()
        }
    }

    private fun process(frame: Frame) {
        val obs = detector.detect(frame)
        if (obs == null) {
            if (inGame) {
                inGame = false
                controller.reset()
                updateNotification("En attente d'une partie…")
            }
            return
        }
        inGame = true
        val now = System.nanoTime() / 1e9
        if (controller.step(now, obs, frame.w)) {
            val svc = TapService.instance
            if (svc == null) {
                updateNotification("Service d'accessibilité inactif : active-le dans les réglages")
            } else {
                val x = frame.w * 0.5f / scale
                val y = (obs.fieldTop + obs.fieldBottom) * 0.5f / scale
                if (svc.tap(x, y)) taps++
            }
        }
        updateNotification("En jeu • $taps touchers • latence ${(controller.latency * 1000).toInt()} ms")
    }

    private fun updateNotification(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif < 1000) return
        lastNotif = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
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
        super.onDestroy()
    }
}
