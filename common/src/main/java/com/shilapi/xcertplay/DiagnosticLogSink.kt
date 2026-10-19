package com.shilapi.xcertplay

/**
 * Process-wide receiver for diagnostics produced outside the CarPlay host, such as the web video
 * transport, so they reach the saved diagnostic report and not only logcat.
 *
 * [CarPlayHostActivity] owns the log file and attaches its writer while it is alive. Every producer
 * still logs to logcat on its own, so nothing is lost while no writer is attached.
 */
internal object DiagnosticLogSink {
    private val lock = Any()
    private var writer: ((String) -> Unit)? = null

    fun attach(target: ((String) -> Unit)?) = synchronized(lock) { writer = target }

    fun append(message: String) {
        val current = synchronized(lock) { writer }
        current?.invoke(message)
    }
}
