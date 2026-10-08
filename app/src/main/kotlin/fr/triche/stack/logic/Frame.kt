package fr.triche.stack.logic

import java.nio.ByteBuffer

/** Image RGBA_8888 (comme fournie par ImageReader), lue sans copie. */
class Frame(
    private val buf: ByteBuffer,
    val w: Int,
    val h: Int,
    private val rowStride: Int,
    private val pixelStride: Int,
) {
    // octets R, G, B, A en mémoire : lus d'un coup comme un entier (ordre « gros-boutiste », R en poids fort)
    private val ints = if (pixelStride == 4 && rowStride % 4 == 0) {
        buf.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN).asIntBuffer()
    } else null
    private val rowInts = rowStride / 4

    /** Pixel packé 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int {
        val ib = ints
        if (ib != null) return ib.get(y * rowInts + x) ushr 8
        val o = y * rowStride + x * pixelStride
        return ((buf.get(o).toInt() and 0xFF) shl 16) or
            ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
            (buf.get(o + 2).toInt() and 0xFF)
    }
}

fun red(rgb: Int) = (rgb shr 16) and 0xFF
fun green(rgb: Int) = (rgb shr 8) and 0xFF
fun blue(rgb: Int) = rgb and 0xFF
