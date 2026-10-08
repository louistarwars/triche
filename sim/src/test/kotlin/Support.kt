import fr.triche.stack.logic.Frame
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

object Support {
    fun frameOf(img: java.awt.image.BufferedImage): Frame {
        val w = img.width
        val h = img.height
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val o = (y * w + x) * 4
            bytes[o] = (p shr 16).toByte()
            bytes[o + 1] = (p shr 8).toByte()
            bytes[o + 2] = p.toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        return Frame(ByteBuffer.wrap(bytes), w, h, w * 4, 4)
    }

    private val cache = HashMap<String, Frame>()
    fun frame(name: String): Frame = cache.getOrPut(name) {
        frameOf(ImageIO.read(Support::class.java.getResourceAsStream("/frames/$name.png")))
    }

    fun load(f: File): Frame = frameOf(ImageIO.read(f))

    /** Dossier des images de la vidéo (f0001.png…), s'il est fourni. */
    fun videoDir(): File? = System.getProperty("stack.frames")?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
}
