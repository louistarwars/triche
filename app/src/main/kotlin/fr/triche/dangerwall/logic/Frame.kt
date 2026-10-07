package fr.triche.dangerwall.logic

import java.nio.ByteBuffer

/** Image RGBA_8888 (comme fournie par ImageReader), lue sans copie. */
class Frame(
    private val buf: ByteBuffer,
    val w: Int,
    val h: Int,
    private val rowStride: Int,
    private val pixelStride: Int,
) {
    /** Pixel packé 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int {
        val o = y * rowStride + x * pixelStride
        return ((buf.get(o).toInt() and 0xFF) shl 16) or
            ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
            (buf.get(o + 2).toInt() and 0xFF)
    }
}

fun red(rgb: Int) = (rgb shr 16) and 0xFF
fun green(rgb: Int) = (rgb shr 8) and 0xFF
fun blue(rgb: Int) = rgb and 0xFF
