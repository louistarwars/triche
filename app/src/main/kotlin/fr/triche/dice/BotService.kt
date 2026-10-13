package fr.triche.dice

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
import fr.triche.dice.logic.DiceBot
import fr.triche.dice.logic.Frame
import fr.triche.dice.logic.Settings
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

/**
 * Regarde l'écran (MediaProjection), lit le chronomètre et les dés de DICE ROLL, puis tape la somme sur le pavé de façon que
 * le dernier chiffre tombe à l'instant qui donne à la manche la durée voulue (voir [DiceBot]).
 */
class BotService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.triche.dice.STOP"
        private const val CHANNEL = "triche_dice"
        private const val NOTIF_ID = 1
        private const val TAG = "TricheDice"
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
    private lateinit var bot: DiceBot
    private lateinit var settings: Settings
    private var realW = 0
    private var realH = 0
    private var frameW = 0
    private var frameH = 0
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
        settings = Settings(prefs.total.toDouble(), prefs.latency.toDouble())
        bot = DiceBot(settings)
        prefs.lastTotal = prefs.total
        prefs.lastLatency = prefs.latency
        prefs.lastRounds = settings.rounds
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Triche Dice", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Triche Dice")
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
        frameW = cw
        frameH = ch
        val ratio = ch.toDouble() / cw
        Journal.add("DÉMARRAGE écran ${w}x$h -> image ${cw}x$ch (hauteur/largeur %.3f, la vidéo de référence : 2.214%s), Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, ".format(
            ratio, if (kotlin.math.abs(ratio - 2.214) > 0.07) " — ÉCRAN DIFFÉRENT : positions à vérifier" else "") +
            "objectif %.2f s, latence %.0f ms, accessibilité=${TapService.instance != null}".format(settings.total, settings.latency * 1000))

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
            "triche-dice", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        tapLoop = TapLoop().also { it.start() }
    }

    private fun now() = System.nanoTime() / 1e9

    // L'heure d'une image = son horodatage système (régulier), recalé sur l'horloge de nanoTime par le plus petit écart
    // « arrivée − horodatage » observé : l'heure d'arrivée dans notre fil fluctue de plusieurs ms.
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
            if (t - lastStat > 10.0) {
                if (lastStat > 0) Journal.add("IMAGES %.0f/s, analyse moyenne %.1f ms, manches terminées : %d".format(
                    frames / (t - lastStat), 1000 * analysis / max(1, frames), bot.roundsDone))
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
     * Fil des touchers : il attend l'heure du prochain toucher (réveils de 1 ms, puis attente « à vif » les 25 dernières ms,
     * car un fil endormi se réveille avec 10 ms de retard) et l'envoie sans jamais attendre l'analyse d'une image.
     */
    private inner class TapLoop : Thread("triche-taps") {
        @Volatile var stopped = false
        private var lastSend = 0.0
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
                    if (now() >= max(n.at, lastSend + 0.07)) { fire(n); break }
                    Thread.onSpinWait()
                }
            }
        }

        private fun fire(n: fr.triche.dice.logic.Tap) {
            val svc = TapService.instance
            val (px, py) = bot.keyPos(n.key)
            val t = now()
            lastSend = t
            if (svc == null) {
                if (!warned) { warned = true; Journal.add("TOUCHER impossible : service d'accessibilité inactif") }
            } else {
                val ok = svc.tap((px * realW / frameW).toFloat(), (py * realH / frameH).toFloat())
                if (!ok) Journal.add("dispatchGesture a refusé le toucher '${n.key}'")
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
            Journal.add("ARRÊT : " + bot.summary())
            Journal.save(this)
            prefs.lastResult = "Dernière partie du bot : " + bot.summary()
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
