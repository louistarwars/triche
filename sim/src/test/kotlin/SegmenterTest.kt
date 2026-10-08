import fr.triche.stack.logic.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Segmentation sur de vraies images du jeu. */
class SegmenterTest {
    private val seg = Segmenter()
    private fun comps(name: String) = seg.segment(Support.frame(name))
    private fun Comp.near(r: Int, g: Int, b: Int) = colorDist(r, g, b) <= 6

    private fun find(cs: List<Comp>, r: Int, g: Int, b: Int, what: String) =
        assertNotNull(cs.filter { it.near(r, g, b) }.maxByOrNull { it.area }, "$what introuvable : ${cs.map { "${it.xmin}-${it.xmax} (${it.r},${it.g},${it.b})" }}")

    @Test
    fun socleGrisEtPremierBloc() {
        val cs = comps("f0060")
        val socle = cs.first { it.gray && it.area > 10000 }
        assertTrue(abs(socle.xmin - 122) <= 4 && abs(socle.xmax - 599) <= 4, "socle ${socle.xmin}-${socle.xmax}")
        val slab = find(cs, 217, 122, 75, "premier bloc")
        assertTrue(slab.xmin <= 1 && abs(slab.xmax - 201) <= 4, "bloc ${slab.xmin}-${slab.xmax}")
    }

    @Test
    fun deuxBlocsVoisinsDeCouleursProchesRestentSeparés() {
        val cs = comps("f0400")
        val slab = find(cs, 216, 181, 76, "bloc mobile")
        val tour = find(cs, 216, 170, 76, "sommet de la tour")
        assertTrue(slab.colorDist(tour) >= 9)
        assertTrue(abs(slab.xmin - 18) <= 4 && abs(slab.xmax - 399) <= 4, "bloc mobile ${slab.xmin}-${slab.xmax}")
        assertTrue(abs(tour.xmax - 587) <= 4, "tour ${tour.xmin}-${tour.xmax}")
    }

    @Test
    fun blocAligneSurLaTour() {
        val cs = comps("f0484")
        val slab = find(cs, 217, 192, 75, "bloc")
        assertTrue(abs(slab.xmin - 178) <= 4 && abs(slab.xmax - 577) <= 4, "bloc ${slab.xmin}-${slab.xmax}")
        // la saillie du bloc précédent est une autre couleur
        find(cs, 216, 170, 78, "saillie")
    }

    @Test
    fun blocsFinsEnFinDePartie() {
        val a = find(comps("f2000"), 74, 180, 216, "bloc bleu")
        assertTrue(abs(a.xmin - 250) <= 4 && abs(a.xmax - 435) <= 4)
        val b = comps("f2900")
        find(b, 216, 75, 215, "bloc magenta")
        find(b, 205, 75, 216, "sommet violet")
    }

    @Test
    fun poseDetecteeSurImagesReelles() {
        // juste après la pose : un seul grand bloc, toujours de la couleur du premier bloc
        val cs = comps("f0129")
        assertEquals(1, cs.count { it.near(217, 122, 75) && it.area > 40000 })
    }
}
