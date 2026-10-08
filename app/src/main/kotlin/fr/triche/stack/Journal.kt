package fr.triche.stack

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Journal de diagnostic : comme on ne peut pas filmer l'écran pendant que le bot le capture, le bot écrit ce qu'il voit
 * et ce qu'il fait. On garde le début de la partie et les derniers événements ; le tout est copiable depuis l'appli.
 */
object Journal {
    private const val HEAD = 100
    private const val TAIL = 300
    private val head = ArrayList<String>()
    private val tail = ArrayDeque<String>()
    private var dropped = 0
    private var t0 = SystemClock.elapsedRealtime()

    @Synchronized
    fun reset() {
        head.clear()
        tail.clear()
        dropped = 0
        t0 = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun add(line: String) {
        val l = "%7.2f %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, line)
        if (head.size < HEAD) {
            head.add(l)
            return
        }
        tail.addLast(l)
        if (tail.size > TAIL) {
            tail.removeFirst()
            dropped++
        }
    }

    @Synchronized
    fun text(): String {
        val sb = StringBuilder()
        head.forEach { sb.append(it).append('\n') }
        if (dropped > 0) sb.append("… ($dropped lignes omises) …\n")
        tail.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    fun save(ctx: Context) {
        try {
            File(ctx.filesDir, "journal.txt").writeText(text())
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context): String = try {
        File(ctx.filesDir, "journal.txt").takeIf { it.exists() }?.readText() ?: ""
    } catch (_: Exception) {
        ""
    }
}
