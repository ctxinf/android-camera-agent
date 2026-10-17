package io.github.ctxinf.cameraagent.adapters.http

interface HttpServer {
    fun closeDisabledConnections()
    val httpConnections: Int get() = 0
    val mcpConnections: Int get() = 0
    suspend fun start(port: Int = 8080)
    suspend fun stop()
    val isRunning: Boolean
}
