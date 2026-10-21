package io.github.ctxinf.cameraagent.adapters.ui

/** Show new connections immediately; allow brief disconnects before lowering the count. */
internal class ConnectionCountDisplay(private val graceMs: Long = 5_000L) {
    private var displayed = 0
    private var decreaseSince: Long? = null
    fun update(actual: Int, now: Long, reset: Boolean = false): Int {
        if (reset || actual >= displayed) {
            displayed = actual
            decreaseSince = null
        } else {
            val since = decreaseSince ?: now.also { decreaseSince = it }
            if (now - since >= graceMs) {
                displayed = actual
                decreaseSince = null
            }
        }
        return displayed
    }
}
