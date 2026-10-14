package fr.triche.cubes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import fr.triche.cubes.logic.ClassGrid
import fr.triche.cubes.logic.CubeCounter
import fr.triche.cubes.logic.PassResult
import fr.triche.cubes.logic.Pix

/**
 * Caméra arrière (Camera2) : une image d'aperçu pour l'écran et une image YUV pour l'analyse. Chaque image d'analyse est
 * réduite de moitié et classée par couleur ([Pix]), puis confiée au [CubeCounter].
 */
class CameraEngine(
    private val ctx: Context,
    private val counter: CubeCounter,
    private val onPass: (PassResult) -> Unit,
    private val onStats: (String) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var grid: ClassGrid? = null

    var previewSize = Size(1280, 720)
        private set

    private var frames = 0
    private var analysis = 0.0
    private var lastStat = 0.0

    /** Choisit la taille d'image (16:9, ~720p) ; à appeler avant de dimensionner l'aperçu. */
    fun chooseSize(): Size {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCamera(mgr) ?: return previewSize
        val map = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return previewSize
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        val prev = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val both = yuv.filter { it in prev && it.width * 9 == it.height * 16 && it.width in 960..1920 }
        previewSize = both.minByOrNull { Math.abs(it.width - 1280) } ?: yuv.filter { it in prev }.minByOrNull { Math.abs(it.width - 1280) } ?: previewSize
        return previewSize
    }

    private fun backCamera(mgr: CameraManager): String? = mgr.cameraIdList.firstOrNull {
        mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: mgr.cameraIdList.firstOrNull()

    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture) {
        stop()
        val t = HandlerThread("cubes-camera", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCamera(mgr) ?: run { Journal.add("ERREUR : aucune caméra"); return }
        val ch = mgr.getCameraCharacteristics(id)
        val size = previewSize
        texture.setDefaultBufferSize(size.width, size.height)
        val previewSurface = Surface(texture)

        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        grid = ClassGrid(size.width / 2, size.height / 2)
        r.setOnImageAvailableListener({ analyse(it) }, h)

        // plus d'images par seconde = moins de flou et plus d'images par passage de plateforme
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fps: Range<Int>? = ranges.filter { it.upper <= 60 }.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        Journal.add("CAMÉRA $id, image ${size.width}x${size.height}, fps demandé $fps (disponibles ${ranges.joinToString()})")

        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(previewSurface, r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(r.surface)
                            if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                        }
                        try {
                            s.setRepeatingRequest(req.build(), null, h)
                            Journal.add("CAMÉRA démarrée")
                        } catch (e: Exception) {
                            Journal.add("ERREUR requête caméra : $e")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Journal.add("ERREUR : configuration de la caméra refusée")
                    }
                }, h)
            }

            override fun onDisconnected(cam: CameraDevice) {
                cam.close()
                device = null
            }

            override fun onError(cam: CameraDevice, error: Int) {
                Journal.add("ERREUR caméra $error")
                cam.close()
                device = null
            }
        }, h)
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        session = null
        device = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun analyse(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val g = grid ?: return
            val t0 = System.nanoTime()
            fill(image, g)
            val t = image.timestamp / 1e9
            val res = counter.onGrid(t, g)
            analysis += (System.nanoTime() - t0) / 1e6
            frames++
            if (t - lastStat > 5.0) {
                if (lastStat > 0) onStats("%.0f images/s, analyse %.1f ms".format(frames / (t - lastStat), analysis / frames))
                lastStat = t
                frames = 0
                analysis = 0.0
            }
            if (res != null) onPass(res)
        } catch (e: Exception) {
            Journal.add("ERREUR d'analyse : $e")
        } finally {
            image.close()
        }
    }

    /** YUV -> RGB un pixel sur deux (dans les deux sens), puis classe de couleur. */
    private fun fill(img: Image, g: ClassGrid) {
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        val yr = yp.rowStride; val yps = yp.pixelStride
        val ur = up.rowStride; val ups = up.pixelStride
        val vr = vp.rowStride; val vps = vp.pixelStride
        val c = g.c
        var o = 0
        for (gy in 0 until g.h) {
            val y = gy * 2
            val yRow = y * yr
            val uRow = (y shr 1) * ur
            val vRow = (y shr 1) * vr
            for (gx in 0 until g.w) {
                val x = gx * 2
                val yy = yb.get(yRow + x * yps).toInt() and 0xFF
                val uu = (ub.get(uRow + (x shr 1) * ups).toInt() and 0xFF) - 128
                val vv = (vb.get(vRow + (x shr 1) * vps).toInt() and 0xFF) - 128
                var rr = yy + ((1436 * vv) shr 10)
                var gg = yy - ((352 * uu + 731 * vv) shr 10)
                var bb = yy + ((1815 * uu) shr 10)
                if (rr < 0) rr = 0 else if (rr > 255) rr = 255
                if (gg < 0) gg = 0 else if (gg > 255) gg = 255
                if (bb < 0) bb = 0 else if (bb > 255) bb = 255
                c[o++] = Pix.classify(rr, gg, bb)
            }
        }
    }
}
