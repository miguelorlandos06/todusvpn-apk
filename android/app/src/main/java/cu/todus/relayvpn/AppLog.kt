package cu.todus.relayvpn

import java.util.ArrayDeque

/// Ring-buffer de log del núcleo, expuesto a la UI vía getStatus.
object AppLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            if (lines.size >= MAX) lines.pollFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { ArrayList(lines) }

    fun clear() = synchronized(lock) { lines.clear() }
}
