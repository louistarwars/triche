package fr.triche.maths.logic

import java.util.Random

enum class Mode {
    /** Temps de réflexion variés sur les 5 questions, qui s'ajoutent au temps visé. */
    NATURAL,

    /** Les 4 premières questions très vite, puis on attend le bon temps avant la dernière réponse. */
    FAST_THEN_WAIT,
}

/**
 * Répartit le temps final visé entre les 5 questions.
 * Mesuré sur la vidéo : le chrono du jeu court de l'affichage de la question 1 jusqu'au dernier tap, et il y a
 * toujours 0,40 s entre un tap et l'affichage de la question suivante.
 */
object Planner {
    const val QUESTIONS = 5
    const val TRANSITION = 0.40
    const val MIN_THINK = 0.30

    fun minTotal() = (QUESTIONS - 1) * TRANSITION + QUESTIONS * MIN_THINK

    /** Temps de réflexion (affichage de la question -> tap) de chaque question ; la somme + les transitions = [total]. */
    fun plan(total: Double, mode: Mode, rnd: Random): DoubleArray {
        val free = maxOf(total, minTotal()) - (QUESTIONS - 1) * TRANSITION
        val d = DoubleArray(QUESTIONS) { MIN_THINK }
        when (mode) {
            Mode.NATURAL -> {
                val extra = free - QUESTIONS * MIN_THINK
                val w = DoubleArray(QUESTIONS) { 0.7 + 0.6 * rnd.nextDouble() }
                w[0] *= 1.15 // on met un peu plus de temps à "lire" la première
                val sum = w.sum()
                for (k in 0 until QUESTIONS) d[k] = MIN_THINK + extra * w[k] / sum
            }
            Mode.FAST_THEN_WAIT -> {
                // 4 premières réponses juste au-dessus du minimum, tout le reste du temps avant la dernière
                val budget = free - QUESTIONS * MIN_THINK
                val extra = DoubleArray(QUESTIONS - 1) { 0.05 + 0.25 * rnd.nextDouble() }
                val sumExtra = extra.sum()
                val scale = if (budget >= sumExtra) 1.0 else budget / sumExtra
                for (k in 0 until QUESTIONS - 1) d[k] = MIN_THINK + extra[k] * scale
                d[QUESTIONS - 1] = MIN_THINK + (budget - sumExtra * scale)
            }
        }
        return d
    }
}
