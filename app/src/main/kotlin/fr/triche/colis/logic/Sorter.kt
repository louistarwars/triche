package fr.triche.colis.logic

enum class Dir { LEFT, UP, RIGHT }

/**
 * Décide du glissement à faire : on trie le colis le plus bas du tapis selon sa couleur
 * (rouge -> gauche, jaune -> haut, bleu -> droite).
 *
 * Garde-fous contre les mauvais tris (un seul faux geste termine la partie) :
 *  - la couleur doit être nette et stable sur 2 images ;
 *  - un colis qui s'envoie vers sa boîte (il monte vite) n'est jamais pris pour un colis du tapis ;
 *  - après un geste on attend la fin de l'animation, puis on ne retrie pas le même colis
 *    (sauf si le geste n'a visiblement pas été pris en compte).
 */
class Sorter {
    companion object {
        const val HOLD_OFF = 0.13        // s : durée d'animation d'envoi d'un colis
        const val RETRY_AFTER = 0.30     // s : le geste n'a pas pris, on recommence
        const val MIN_SHARE = 0.70f
        const val MAX_STEP_UP = 3        // px : un colis du tapis ne remonte pas
        const val MAX_STEP_DOWN = 28     // px par image (le tapis avance parfois par à-coups)
        const val NEW_PARCEL_GAP = 45    // px : un colis suivant est au moins ça plus haut
    }

    private class Pending(val color: Int, val bottom: Int, var at: Double)

    private var last: Parcel? = null
    private var stable = 0
    private var pending: Pending? = null
    private var holdUntil = 0.0
    private var lastT = 0.0
    private var speed = 200.0 // px/s, vitesse du tapis mesurée en direct

    /** Nombre de gestes demandés depuis le début de la partie. */
    var count = 0
        private set

    fun reset() {
        last = null
        stable = 0
        pending = null
        holdUntil = 0.0
        count = 0
    }

    fun step(t: Double, scene: Scene?): Dir? {
        val front = scene?.parcels?.maxByOrNull { it.bottom }
        if (front == null) {
            last = null
            stable = 0
            pending = null
            return null
        }
        val prev = last
        last = front
        val dy = if (prev != null) front.bottom - prev.bottom else 0
        val dt = t - lastT
        lastT = t
        if (prev != null && prev.color == front.color && dy in 0..MAX_STEP_DOWN && dt in 0.005..0.1) {
            speed = 0.9 * speed + 0.1 * (dy / dt)
        }
        stable = if (prev != null && prev.color == front.color && dy >= -MAX_STEP_UP && dy <= MAX_STEP_DOWN) stable + 1 else 1

        if (front.share < MIN_SHARE || stable < 2 || t < holdUntil) return null

        val p = pending
        var retry = false
        if (p != null) {
            // où serait l'ancien colis s'il était resté sur le tapis ?
            val expected = p.bottom + speed.coerceIn(0.0, 700.0) * (t - p.at)
            if (front.bottom < expected - NEW_PARCEL_GAP) {
                pending = null // le colis précédent est parti, c'est le suivant
            } else if (t - p.at < RETRY_AFTER) {
                return null // même colis : on attend qu'il parte
            } else {
                retry = true
            }
        }

        pending = Pending(front.color, front.bottom, t)
        holdUntil = t + HOLD_OFF
        if (!retry) count++
        return when (front.color) {
            Color.RED -> Dir.LEFT
            Color.YELLOW -> Dir.UP
            else -> Dir.RIGHT
        }
    }
}
