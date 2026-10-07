package fr.triche.maths.logic

import java.util.Random

class Settings(
    /** Temps final visé, en secondes (chrono du jeu). */
    val targetSec: Double,
    /** Décalage appliqué à tous les taps, en secondes (négatif = plus tôt). */
    val offsetSec: Double,
    val mode: Mode,
)

/** Ordre donné au service : toucher [button] (0..3) à l'instant [tapAt] (horloge des frames, en secondes). */
class Decision(val button: Int, val tapAt: Double, val dots: Int, val question: Int)

class GameResult(val measured: Double, val target: Double)

/**
 * Cerveau du jeu : lit chaque image, résout les 5 équations et planifie les taps pour que le chrono
 * du jeu s'arrête au temps visé.
 */
class QuizBot(
    private val reader: QuizReader,
    var settings: Settings,
    private val rnd: Random = Random(),
) {
    companion object {
        const val STABLE_FRAMES = 2
        const val MIN_AFTER_APPEAR = Planner.MIN_THINK
        const val RETRY_AFTER = 1.2
        const val GAME_GONE_AFTER = 0.8
        const val SCORE_TIMEOUT = 8.0
        const val SCORE_PERIOD = 0.2
    }

    private var dots = -1
    private var appearAt = 0.0
    private var stable = 0
    private var handled = -1
    private var gameStart = 0.0
    private var delays = DoubleArray(Planner.QUESTIONS)
    private var effectiveTarget = 0.0
    private var lastButton = -1
    private var tapFiredAt = -1.0
    private var retries = 0
    private var readTries = 0
    private var goneSince = -1.0
    private var inGame = false
    private var finalTapped = false
    private var scoreSince = -1.0
    private var lastScoreRead = -1.0
    private var lastScore = -1.0
    private var scoreRepeat = 0

    /** Dernier texte d'état à afficher. */
    var status = "En attente d'une partie…"
        private set

    /** Numéro (points) de la question actuellement affichée, pour vérifier avant un tap planifié. */
    val currentDots get() = dots

    /** Résultat de la dernière partie (une seule fois), si le score de fin a pu être lu. */
    var result: GameResult? = null
        private set

    fun takeResult(): GameResult? = result.also { result = null }

    fun onTapFired(t: Double) {
        tapFiredAt = t
        if (handled == Planner.QUESTIONS - 1) finalTapped = true
    }

    fun step(t: Double, f: Frame): Decision? {
        val s = reader.screen(f)
        if (s == null) {
            noScreen(t, f)
            return null
        }
        goneSince = -1.0
        scoreSince = -1.0
        if (!s.normal) {
            stable = 0
            return null
        }
        if (s.dots != dots) {
            dots = s.dots
            appearAt = t
            stable = 1
            readTries = 0
        } else {
            stable++
        }
        if (stable < STABLE_FRAMES) return null

        if (dots != handled) return newQuestion(t, f)

        // même question encore à l'écran longtemps après le tap : il n'a pas été pris en compte
        if (tapFiredAt > 0 && t - tapFiredAt > RETRY_AFTER && retries < 3 && lastButton >= 0) {
            retries++
            tapFiredAt = -1.0
            return Decision(lastButton, t, dots, dots + 1)
        }
        return null
    }

    private fun newQuestion(t: Double, f: Frame): Decision? {
        if (dots == 0) {
            inGame = true
            finalTapped = false
            gameStart = appearAt
            effectiveTarget = maxOf(settings.targetSec, Planner.minTotal())
            delays = Planner.plan(effectiveTarget, settings.mode, rnd)
        } else if (!inGame) {
            // partie en cours au moment du démarrage du bot : on ne peut pas tenir le temps visé
            inGame = true
            gameStart = appearAt - dots * (Planner.TRANSITION + Planner.MIN_THINK)
            effectiveTarget = maxOf(settings.targetSec, Planner.minTotal())
            delays = Planner.plan(effectiveTarget, settings.mode, rnd)
        }
        val q = reader.read(f)
        val idx = q.answerIndex
        if (idx == null) {
            // lecture douteuse (animation en cours ?) : on réessaie sur les images suivantes
            readTries++
            status = "Question ${dots + 1} : lecture incertaine « ${q.equation} » ${q.answers}"
            if (readTries >= 8) handled = dots // on abandonne cette question
            stable = 0
            return null
        }
        handled = dots
        retries = 0
        tapFiredAt = -1.0
        lastButton = idx

        var acc = 0.0
        for (j in 0..dots) acc += delays[j]
        val planned = gameStart + acc + dots * Planner.TRANSITION + settings.offsetSec
        val tapAt = maxOf(planned, appearAt + MIN_AFTER_APPEAR, t)
        status = "Question ${dots + 1}/${Planner.QUESTIONS} : ${q.equation} → bouton ${idx + 1} dans ${"%.2f".format(tapAt - t)} s"
        return Decision(idx, tapAt, dots, dots + 1)
    }

    private fun noScreen(t: Double, f: Frame) {
        if (inGame) {
            if (goneSince < 0) goneSince = t
            if (t - goneSince > GAME_GONE_AFTER) {
                inGame = false
                dots = -1
                handled = -1
                stable = 0
                if (finalTapped) {
                    scoreSince = t
                    lastScoreRead = -1.0
                    lastScore = -1.0
                    scoreRepeat = 0
                    status = "Partie terminée, lecture du temps…"
                } else {
                    status = "En attente d'une partie…"
                }
                finalTapped = false
            }
            return
        }
        if (scoreSince >= 0) {
            if (t - scoreSince > SCORE_TIMEOUT) {
                scoreSince = -1.0
                status = "En attente d'une partie…"
                return
            }
            if (t - lastScoreRead < SCORE_PERIOD) return
            lastScoreRead = t
            val v = reader.readScore(f)
            if (v != null && v > 1.0) {
                if (lastScore >= 0 && Math.abs(v - lastScore) < 1e-9) scoreRepeat++ else { lastScore = v; scoreRepeat = 1 }
                if (scoreRepeat >= 3) {
                    result = GameResult(v, effectiveTarget)
                    status = "Temps obtenu : ${"%.2f".format(v)} s (visé ${"%.2f".format(effectiveTarget)} s)"
                    scoreSince = -1.0
                }
            }
        }
    }

    fun reset() {
        dots = -1; handled = -1; stable = 0; inGame = false; finalTapped = false
        scoreSince = -1.0; goneSince = -1.0; tapFiredAt = -1.0
        status = "En attente d'une partie…"
    }
}
