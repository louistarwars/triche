package fr.triche.timesup

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
import fr.triche.timesup.logic.Frame
import fr.triche.timesup.logic.Settings
import fr.triche.timesup.logic.Tap
import fr.triche.timesup.logic.TimesUpBot
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

/**
 * Regarde l'écran (MediaProjection, 720 px de large), mesure la barre du jeu TIME'S UP et touche l'écran à l'instant où elle
 * serait vide (voir [TimesUpBot]).
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.timesup.STOP"
        private const val CHANNEL = "triche_timesup"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheTimesUp"
        private const val CAPTURE_WIDTH = 720

        @Volatile
        var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var tapLoop: TapLoop? = null

    private lateinit var prefs: Prefs
    private lateinit var bot: TimesUpBot
    private var realW = 0
    private var realH = 0
    private var frameW = 720
    private var frameH = 1594
    private var lastNotif = 0L
    private var lastStatus = ""
    private var lastSave = 0L

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
        val calibrated = prefs.obs.isNotEmpty()
        bot = TimesUpBot(Settings(prefs.latency.toDouble(), prefs.shift.toDouble(), prefs.autoNext, autoLatency = !calibrated))
        prefs.lastRun = ""
        Journal.reset()
        bot.log = { Journal.add(it) }
        bot.onLevel = { rec ->
            // de quoi relier plus tard les décalages saisis à chaque manche
            prefs.lastRun = (prefs.lastRun.takeIf { it.isNotEmpty() }?.plus(";") ?: "") +
                "%.8f,%.5f,%.4f".format(Locale.US, rec.inv, rec.latencyUsed, rec.shiftUsed)
        }
        Journal.add("DÉMARRAGE Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, latence %.0f ms, correction du bas %+.2f px, %s, enchaînement auto=%s, accessibilité=%s".format(
            prefs.latency * 1000, prefs.shift, if (calibrated) "calibré (${prefs.obs.size} mesures)" else "pas encore calibré : latence recalée d'après le résultat", prefs.autoNext, TapService.instance != null))
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche Time's Up", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche Time's Up")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification() {
        val text = bot.status
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave > 20000) {
            lastSave = now
            val ctx = applicationContext
            Thread { Journal.save(ctx) }.also { it.priority = Thread.MIN_PRIORITY; it.isDaemon = true }.start()
        }
        // pas de mise à jour de la notification pendant qu'un toucher est attendu : elle ne doit pas gêner le fil des images
        if (text == lastStatus || now - lastNotif < 3000 || bot.nextTap() != null) return
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
        frameW = cw
        frameH = ch
        val ratio = ch.toDouble() / cw
        Journal.add("ÉCRAN ${w}x$h -> image ${cw}x$ch (hauteur/largeur %.3f ; vidéo de référence 2.214%s)".format(
            ratio, if (kotlin.math.abs(ratio - 2.214) > 0.07) " — ÉCRAN DIFFÉRENT : positions à vérifier" else ""))

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
            "triche-timesup", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        tapLoop = TapLoop().also { it.start() }
    }

    private fun now() = System.nanoTime() / 1e9

    // L'heure d'une image = son horodatage système (régulier, une image par rafraîchissement), recalé sur l'horloge de
    // nanoTime par le plus petit écart « arrivée − horodatage » : l'heure d'arrivée dans notre fil fluctue de plusieurs ms.
    private var offMin = Long.MAX_VALUE
    private var lastTs = 0L

    private fun frameTime(ts: Long, arrival: Long): Double {
        if (ts <= 0L || ts <= lastTs) return arrival / 1e9
        lastTs = ts
        val o = arrival - ts
        offMin = if (offMin == Long.MAX_VALUE) o else minOf(o, offMin + 5_000L)
        return (ts + offMin) / 1e9
    }

    private var frames = 0
    private var analysis = 0.0
    private var lastStat = 0.0

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val arrival = System.nanoTime()
            val t = frameTime(image.timestamp, arrival)
            val plane = image.planes[0]
            val frame = Frame(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            frameW = image.width
            frameH = image.height
            val t0 = now()
            bot.onFrame(t, frame)
            analysis += now() - t0
            frames++
            if (t - lastStat > 5.0) {
                if (lastStat > 0) Journal.add("IMAGES %.0f/s, analyse moyenne %.2f ms, manches terminées : %d".format(
                    frames / (t - lastStat), 1000 * analysis / max(1, frames), bot.levels.size))
                lastStat = t
                frames = 0
                analysis = 0.0
            }
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'analyse", e)
            Journal.add("ERREUR d'analyse : $e")
        } finally {
            image.close()
        }
    }

    /**
     * Fil des touchers : réveils de 1 ms jusqu'à 25 ms avant l'heure, puis attente « à vif » (un fil endormi se réveille avec
     * 10 ms de retard), puis envoi sans jamais attendre l'analyse d'une image. L'heure peut encore être corrigée par le
     * fil des images pendant l'attente.
     */
    private inner class TapLoop : Thread("triche-taps") {
        @Volatile var stopped = false
        private var warned = false

        override fun run() {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) { }
            while (!stopped) {
                val tap = bot.nextTap()
                if (tap == null) { LockSupport.parkNanos(1_000_000); continue }
                val wait = tap.at - now()
                if (wait > 0.03) { LockSupport.parkNanos(minOf((wait - 0.025) * 1e9, 5e6).toLong()); continue }
                while (!stopped) {
                    val n = bot.nextTap() ?: break
                    if (now() >= n.at) { fire(n); break }
                    Thread.onSpinWait()
                }
            }
        }

        private fun fire(n: Tap) {
            val svc = TapService.instance
            val t = now()
            if (svc == null) {
                if (!warned) { warned = true; Journal.add("TOUCHER impossible : service d'accessibilité inactif") }
            } else {
                val ok = svc.tap((n.x * realW / frameW).toFloat(), (n.y * realH / frameH).toFloat())
                if (!ok) Journal.add("dispatchGesture a refusé le toucher")
            }
            bot.onSent(n, t)
        }
    }

    override fun onDestroy() {
        running = false
        tapLoop?.stopped = true
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        if (::bot.isInitialized) {
            Journal.add("ARRÊT : ${bot.levels.size} manches jouées")
            for (l in bot.levels) Journal.add("  $l")
            Journal.save(this)
            prefs.lastResult = "Dernière partie : ${bot.levels.size} manches jouées" +
                (if (bot.levels.isNotEmpty()) " — saisis les décalages affichés par le jeu pour régler la latence" else "")
        }
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
        tapLoop = null
        super.onDestroy()
    }
}
