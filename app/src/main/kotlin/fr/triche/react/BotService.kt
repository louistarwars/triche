package fr.triche.react

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
import fr.triche.react.logic.Frame
import fr.triche.react.logic.ReactBot

/**
 * Regarde l'écran (MediaProjection, en petite définition : on ne lit que le centre des 9 cercles) et touche le cercle
 * dès qu'il s'allume en vert.
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.react.STOP"
        private const val CHANNEL = "triche_react"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheReact"
        private const val CAPTURE_WIDTH = 360     // petite image : moins de travail pour le système, les cercles font encore ~80 px

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    private lateinit var prefs: Prefs
    private val bot = ReactBot()
    private var realW = 0
    private var realH = 0
    private var lastNotif = 0L
    private var lastStatus = ""
    private var frames = 0
    private var lastStat = 0L
    private var extraDelay = 0

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
        extraDelay = prefs.extraDelay
        Journal.reset()
        bot.log = { Journal.add(it) }
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche React", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche React")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification() {
        val text = bot.status
        val now = SystemClock.elapsedRealtime()
        if (text == lastStatus || now - lastNotif < 1500) return
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
        val scale = if (w > CAPTURE_WIDTH) CAPTURE_WIDTH.toFloat() / w else 1f
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()
        Journal.add("DÉMARRAGE écran ${w}x$h -> image ${cw}x$ch, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "délai ajouté ${extraDelay} ms, accessibilité=${TapService.instance != null}")

        val t = HandlerThread("triche-capture", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
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

        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        display = mp.createVirtualDisplay(
            "triche-react", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
    }

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val arrival = System.nanoTime()
            val plane = image.planes[0]
            val frame = Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            val target = bot.onFrame(arrival / 1e9, frame)
            if (target != null) {
                val done = System.nanoTime()
                if (extraDelay > 0) Thread.sleep(extraDelay.toLong())
                val svc = TapService.instance
                val sent = System.nanoTime()
                val ok = svc?.tap((target.fx * realW).toFloat(), (target.fy * realH).toFloat()) { completed ->
                    Journal.add("   geste terminé (%s) %.0f ms après l'envoi".format(if (completed) "ok" else "annulé", (System.nanoTime() - sent) / 1e6))
                }
                // âge de l'image à son arrivée (si l'horodatage est sur la même horloge) et durée de notre traitement
                val age = (arrival - image.timestamp) / 1e6
                Journal.add("TOUCHER cercle %d : analyse %.1f ms, image arrivée %s après son horodatage, envoi=%s%s".format(
                    target.index, (done - arrival) / 1e6,
                    if (age in 0.0..500.0) "%.0f ms".format(age) else "(horloge différente)",
                    ok, if (svc == null) " — SERVICE D'ACCESSIBILITÉ INACTIF" else ""))
            }
            frames++
            val now = SystemClock.elapsedRealtime()
            if (now - lastStat > 10000) {
                if (lastStat > 0) Journal.add("IMAGES %.0f/s, manches jouées : %d".format(frames * 1000.0 / (now - lastStat), bot.rounds))
                lastStat = now
                frames = 0
            }
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        Journal.add("ARRÊT, manches jouées : ${bot.rounds}")
        Journal.save(applicationContext)
        if (::prefs.isInitialized) prefs.lastResult = "Dernière session : ${bot.rounds} manches jouées"
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
        super.onDestroy()
    }
}
