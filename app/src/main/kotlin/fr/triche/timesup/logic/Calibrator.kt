package fr.triche.timesup.logic

/**
 * Apprentissage de la latence et du bas de la barre à partir des décalages affichés par le jeu.
 *
 * Le bot touche à l'instant `fin_prévue − L` où `fin_prévue = (bas + B − c) / pente`. Si `e` est le décalage affiché par le jeu
 * (positif = trop tard) pour une manche jouée avec L_u et B_u, alors `e = (L_vrai − L_u) − (B_vrai − B_u) · inv` où
 * `inv = 1 / pente` (s par pixel). Donc `z = e + L_u − B_u · inv = L_vrai − B_vrai · inv` : chaque manche donne un point
 * (inv, z) sur une droite dont l'ordonnée est la latence et la pente le décalage du bas de la barre (en pixels). Les manches
 * lentes et rapides (inv différents) permettent de séparer les deux ; sans variété, B reste proche de 0.
 */
class Obs(val inv: Double, val z: Double)

object Calibrator {
    private const val RIDGE = 1e-5          // rappel de B vers 0 (s² par px²)

    /** Latence (s) et décalage du bas de la barre (px) ; null s'il n'y a aucune observation. */
    fun solve(obs: List<Obs>): Pair<Double, Double>? {
        if (obs.isEmpty()) return null
        val n = obs.size.toDouble()
        var sx = 0.0; var sz = 0.0; var sxx = 0.0; var sxz = 0.0
        for (o in obs) { sx += o.inv; sz += o.z; sxx += o.inv * o.inv; sxz += o.inv * o.z }
        // minimise Σ (z − L + B·inv)² + RIDGE·B²  (inconnues L, B)
        //   dL : n·L − sx·B = sz
        //   dB : −sx·L + (sxx + RIDGE)·B = −sxz
        val a = n; val b = -sx; val c = -sx; val d = sxx + RIDGE
        val det = a * d - b * c
        if (det < 1e-12) return (sz / n) to 0.0
        val l = (sz * d - b * (-sxz)) / det
        val bb = (a * (-sxz) - c * sz) / det
        return l.coerceIn(0.0, 0.5) to bb.coerceIn(-10.0, 10.0)
    }

    /** Observation à partir d'un décalage affiché (secondes, positif = trop tard). */
    fun obs(inv: Double, shown: Double, latencyUsed: Double, shiftUsed: Double) =
        Obs(inv, shown + latencyUsed - shiftUsed * inv)
}
