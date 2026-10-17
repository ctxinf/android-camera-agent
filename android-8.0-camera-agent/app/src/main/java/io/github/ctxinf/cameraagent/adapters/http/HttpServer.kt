package io.github.ctxinf.cameraagent.adapters.http

interface HttpServer {
    suspend fun start(port: Int = 8080)
    suspend fun stop()
    val isRunning: Boolean
}
