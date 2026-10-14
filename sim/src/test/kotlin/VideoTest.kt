import fr.triche.cubes.logic.*
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rejeu de la vraie vidéo (iPad filmé au téléphone) : niveau 1 = 3 boîtes, niveau 2 = 5 boîtes. */
class VideoTest {
    private fun grid(i: Int): ClassGrid {
        val img = ImageIO.read(VideoTest::class.java.getResourceAsStream("/frames/v%04d.jpg".format(i)))
        val g = ClassGrid(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            g.c[y * img.width + x] = Pix.classify((p shr 16) and 255, (p shr 8) and 255, p and 255)
        }
        return g
    }

    private fun play(step: Int, offset: Int = 0): List<PassResult> {
        val cc = CubeCounter()
        val results = ArrayList<PassResult>()
        var i = offset
        while (i < 330) {
            cc.onGrid(i / 30.0, grid(i))?.let { results.add(it); println("PASSAGE (1 image sur $step, départ $offset), image $i : $it") }
            i += step
        }
        return results
    }

    @Test
    fun deuxPassagesTroisPuisCinq() {
        assertEquals(listOf(3, 5), play(1).map { it.count })
    }

    /** Plateforme 2 à 3 fois plus rapide (on saute des images) : même résultat. */
    @Test
    fun plateformeRapide() {
        for (step in listOf(2, 3)) for (offset in 0 until step) {
            assertEquals(listOf(3, 5), play(step, offset).map { it.count }, "1 image sur $step, départ $offset")
        }
    }
}
