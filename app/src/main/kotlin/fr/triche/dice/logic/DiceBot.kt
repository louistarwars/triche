package fr.triche.dice.logic

import kotlin.math.abs
import kotlin.math.max

/** Un toucher à envoyer à l'instant [at] (horloge de nanoTime, en secondes) sur la touche [key] ('0'..'9', 'B' = effacer). */
class Tap(val key: Char, @Volatile var at: Double, val final: Boolean) {
    @Volatile var sentAt = Double.NaN
    val sent get() = !sentAt.isNaN()
}

/**
 * [total] : temps total voulu (secondes) pour les [rounds] manches ; [latency] : délai (s) entre l'envoi du dernier toucher
 * d'une manche et l'arrêt du chronomètre du jeu, vu depuis nos images (se règle avec le score obtenu, voir MainActivity).
 */
class Settings(val total: Double, val latency: Double, val rounds: Int = 3)

/**
 * Le jeu : 3 manches, à chaque manche on additionne 4 dés et on tape la somme ; le chronomètre (0,1 s par pas) court de
 * l'apparition des dés au dernier chiffre, et le score est la somme des durées des manches.
 *
 * Le bot ne cherche donc pas à aller vite : il lit les dés, puis tape le dernier chiffre à l'instant qui donnera la
 * durée voulue pour cette manche. Le début de la manche se déduit des pas du chronomètre (dont on lit les chiffres) : le
 * premier pas a lieu au démarrage du chronomètre, les suivants toutes les 0,1 s. La durée de chaque manche est
 * [total restant] / [manches restantes], ce qui rattrape les écarts des manches précédentes sur la dernière.
 */
class DiceBot(private val settings: Settings) {
    var log: (String) -> Unit = {}
    @Volatile var status = "En attente du jeu"
    @Volatile var finished = false
        private set
    val roundsDone get() = done.size

    private enum class Phase { WAIT, PLANNED, SENT, OVER }

    private var phase = Phase.WAIT
    private var lastV: Int? = null
    private var roundBase = 0
    private var baseSure = false
    private var roundStartV = 0
    private val phaseSums = ArrayList<Double>()      // s_k = o_k − 0,1·(k−1), par pas du chronomètre vu dans la manche
    private var firstTickT = Double.NaN
    private var lastTickT = Double.NaN
    private var lastRead: DiceReading? = null
    private var stable = 0
    private var stableSince = 0.0
    private var sum = 0
    private var keys = ""
    private var target = 0.0
    private var retries = 0
    private var needClear = false
    private var asap = false
    private val done = ArrayList<Double>()            // durées estimées des manches terminées
    private var lastTapSent = Double.NaN
    private var sObs = Double.NaN
    @Volatile private var taps: List<Tap> = emptyList()
    private var numpad: Numpad? = null
    private var frameW = 0
    private var frameH = 0

    private var lastDiceLog = 0.0

    /** Prochain toucher à envoyer (lu par le fil des touchers, sans verrou pour ne jamais attendre l'analyse d'une image). */
    fun nextTap(): Tap? = taps.firstOrNull { !it.sent }

    /** Appelé par le fil qui touche l'écran dès que le toucher est parti. */
    fun onSent(tap: Tap, t: Double) {
        tap.sentAt = t
        log("toucher '%s' envoyé (%.0f ms après l'heure prévue)%s".format(tap.key, (t - tap.at) * 1000, if (tap.final) " [FINAL]" else ""))
    }

    /** Position (en pixels de l'image) d'une touche du pavé. */
    fun keyPos(key: Char): Pair<Double, Double> = (numpad ?: Numpad.default(frameW, frameH)).center(key)

    fun summary(): String {
        val s = done.sum()
        return "manches %d/%d, durées estimées %s = %.2f s (objectif %.2f s)".format(
            done.size, settings.rounds, done.joinToString(" + ") { "%.2f".format(it) }, s, settings.total)
    }

    fun onFrame(t: Double, fr: Frame) {
        frameW = fr.w
        frameH = fr.h
        step(t, TimerReader.read(fr), { DiceReader.read(fr) }, { Numpad.detect(fr) })
    }

    /** Un pas du bot : [timer] = chronomètre lu (dixièmes), [dice] et [pad] sont évalués seulement si besoin. */
    fun step(t: Double, timer: Int?, dice: () -> DiceReading?, pad: () -> Numpad?) {
        if (phase == Phase.OVER) {
            watchRestart(timer)
            return
        }
        val last = taps.lastOrNull()
        if (phase == Phase.PLANNED && last != null && last.sent) {
            phase = Phase.SENT
            lastTapSent = last.sentAt
            val d = lastTapSent + settings.latency - sObs
            log("DERNIER TOUCHER parti : durée estimée de la manche %.2f s".format(d))
            status = "Manche ${done.size + 1} jouée (≈ %.2f s)".format(d)
        }
        if (timer != null) onTimer(t, timer)
        when (phase) {
            Phase.WAIT -> if (!firstTickT.isNaN() || !baseSure) tryPlan(t, dice, pad)
            Phase.PLANNED -> {
                replanIfNeeded(t)
            }
            Phase.SENT -> checkOutcome(t)
            else -> {}
        }
    }

    // ---------- chronomètre ----------

    private var candV = -1
    private var candT = 0.0
    private var candN = 0

    /** Une valeur n'est retenue qu'après deux images identiques (une image mal lue ne doit pas fausser le décompte). */
    private fun onTimer(t0: Double, v0: Int) {
        if (v0 == lastV) { candN = 0; return }
        if (v0 != candV) { candV = v0; candT = t0; candN = 1 } else candN++
        if (candN < 2) return
        onTimerChange(candT, v0)
    }

    private fun onTimerChange(t: Double, v: Int) {
        val prev = lastV
        lastV = v
        candN = 0
        if (prev == null) {
            baseSure = v <= 1                       // 0,0 avant le premier pas, 0,1 si on n'a vu que le premier pas
            roundBase = if (baseSure) 0 else v
            log("CHRONOMÈTRE lu pour la première fois : %.1f%s".format(v / 10.0, if (baseSure) "" else " (début de manche manqué : tir au plus vite)"))
            if (v == 1) { phaseSums.add(t); firstTickT = t; lastTickT = t }
            return
        }
        if (v < prev) {
            if (v > 1) { log("CHRONOMÈTRE mal lu (%.1f après %.1f) : ignoré".format(v / 10.0, prev / 10.0)); lastV = prev; return }
            log("CHRONOMÈTRE revenu à %.1f : nouvelle partie".format(v / 10.0))
            newGame(v)
            return
        }
        if (v == prev) return
        val delta = v - prev
        if (phase == Phase.SENT) {
            val sent = lastTapSent
            val sinceTap = t - sent
            if (sinceTap > settings.latency + 0.25 && sinceTap < 1.0) {
                // le chronomètre court encore : la réponse n'a pas été prise
                log("CHRONOMÈTRE toujours en marche %.0f ms après notre dernier toucher : réponse refusée ?".format(sinceTap * 1000))
                retryRound(t)
            }
            return
        }
        if (delta == 1) {
            val k = v - roundBase
            if (k >= 1 && baseSure) {
                phaseSums.add(t - 0.1 * (k - 1))
                if (firstTickT.isNaN()) {
                    firstTickT = t
                    log("PAS 1 du chronomètre vu (manche %d, valeur de départ %.1f)".format(done.size + 1, roundBase / 10.0))
                }
                lastTickT = t
            }
        } else if (delta > 5) {
            log("CHRONOMÈTRE mal lu (%.1f après %.1f) : ignoré".format(v / 10.0, prev / 10.0))
            lastV = prev
        } else {
            log("CHRONOMÈTRE a sauté de %.1f à %.1f (images manquantes)".format(prev / 10.0, v / 10.0))
        }
    }

    private fun newGame(v: Int) {
        done.clear()
        taps = emptyList()
        phase = Phase.WAIT
        roundBase = v
        baseSure = v == 0
        resetRound()
        finished = false
        status = "Nouvelle partie"
    }

    private fun resetRound() {
        phaseSums.clear()
        firstTickT = Double.NaN
        lastTickT = Double.NaN
        lastRead = null
        stable = 0
        retries = 0
        needClear = false
        sObs = Double.NaN
        taps = emptyList()
        asap = false
    }

    // ---------- dés et plan ----------

    private fun tryPlan(t: Double, dice: () -> DiceReading?, pad: () -> Numpad?) {
        val r = dice()
        if (r == null) {
            lastRead = null
            stable = 0
            return
        }
        val prev = lastRead
        if (prev != null && prev.sameAs(r, 2)) {
            stable++
        } else {
            stable = 1
            stableSince = t
        }
        lastRead = r
        if (t - lastDiceLog > 0.5 && stable == 1) {
            lastDiceLog = t
            log("DÉS vus %s".format(r))
        }
        // les dés doivent être immobiles depuis 3 images, et vus après le premier pas du chronomètre
        if (stable < 3 || t - stableSince < 0.025) return
        if (baseSure && (firstTickT.isNaN() || stableSince < firstTickT - 0.02)) return
        if (numpad == null || stable == 3) pad()?.let { numpad = it }
        sum = r.sum
        keys = sum.toString()
        log("DÉS lus %s (stables depuis %d images), pavé %s".format(r, stable, if (numpad != null) "détecté" else "position par défaut"))
        plan(t)
    }

    private fun roundNo() = done.size + 1

    private fun plan(t: Double) {
        val left = settings.rounds - done.size
        val remaining = settings.total - done.sum()
        target = remaining / left
        val list = ArrayList<Tap>()
        var at = t + 0.02
        if (needClear) {
            list.add(Tap('B', at, false)); at += 0.10
            list.add(Tap('B', at, false)); at += 0.10
        }
        for ((i, c) in keys.withIndex()) {
            val final = i == keys.length - 1
            if (!final) { list.add(Tap(c, at, false)); at += 0.12 } else list.add(Tap(c, at, true))
        }
        taps = list
        phase = Phase.PLANNED
        sObs = if (phaseSums.isEmpty()) Double.NaN else phaseSums.average()
        computeFinal(t)
        status = "Manche ${roundNo()} : somme $sum, durée visée %.2f s".format(target)
        log("PLAN manche %d : somme %d, durée visée %.2f s (reste %.2f s pour %d manches), début estimé %s, dernier toucher dans %.0f ms".format(
            roundNo(), sum, target, remaining, left, if (sObs.isNaN()) "inconnu" else "t=%.3f".format(sObs), (list.last().at - t) * 1000))
    }

    /** Heure du dernier toucher : début de manche + durée visée − latence (jamais avant les chiffres précédents). */
    private fun computeFinal(t: Double) {
        val list = taps
        val fin = list.last()
        if (fin.sent) return
        val earliest = max(t + 0.01, if (list.size > 1) list[list.size - 2].at + 0.12 else 0.0)
        val ideal = if (baseSure && !asap && !sObs.isNaN()) sObs + target - settings.latency else 0.0
        fin.at = max(ideal, earliest)
    }

    private fun replanIfNeeded(t: Double) {
        if (phaseSums.isNotEmpty()) {
            val s = phaseSums.average()
            if (sObs.isNaN() || abs(s - sObs) > 1e-6) {
                sObs = s
                computeFinal(t)
            }
        }
    }

    // ---------- après le dernier toucher ----------

    private fun checkOutcome(t: Double) {
        val sent = lastTapSent
        if (t - sent < 1.0) return
        // plus de pas depuis le toucher : la manche est finie
        val d = sent + settings.latency - sObs
        val remaining = settings.total - done.sum()
        done.add(if (!sObs.isNaN() && baseSure) d else remaining / (settings.rounds - done.size))
        log("MANCHE %d TERMINÉE : durée estimée %.2f s, chronomètre à %.1f".format(done.size, d, (lastV ?: 0) / 10.0))
        if (done.size >= settings.rounds) {
            phase = Phase.OVER
            finished = true
            log("PARTIE TERMINÉE : " + summary())
            status = "Partie terminée : %.2f s visé %.2f s".format(done.sum(), settings.total)
            return
        }
        roundBase = lastV ?: 0
        baseSure = true
        resetRound()
        phase = Phase.WAIT
        status = "Manche ${roundNo()} : attente des dés"
    }

    private fun retryRound(t: Double) {
        retries++
        needClear = true
        if (retries > 4) {
            log("TROP D'ÉCHECS sur cette manche : j'abandonne")
            phase = Phase.OVER
            finished = true
            return
        }
        taps = emptyList()
        phase = Phase.WAIT
        asap = true               // la manche a déjà trop duré : on tape au plus vite
        lastRead = null
        stable = 0
        log("NOUVEL ESSAI de la manche %d (essai %d)".format(roundNo(), retries + 1))
    }

    private fun watchRestart(timer: Int?) {
        val v = timer ?: return
        val prev = lastV
        if (prev != null && v < prev && v <= 1) {
            lastV = v
            log("CHRONOMÈTRE revenu à %.1f : nouvelle partie".format(v / 10.0))
            newGame(v)
        }
    }
}
