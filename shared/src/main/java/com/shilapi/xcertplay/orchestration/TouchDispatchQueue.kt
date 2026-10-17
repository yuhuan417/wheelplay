package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact

/**
 * Coalesces intermediate touch move reports while preserving initial press-down coordinates,
 * multi-touch contact set changes, and lift-up reports in strict order.
 */
internal class TouchDispatchQueue(
    private val scheduleDrain: (Runnable) -> Unit,
    private val deliver: (List<AirPlayContact>) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayList<List<AirPlayContact>>()
    private var draining = false
    private var closed = false
    private var establishedSignature: List<Int>? = null
    private val drainTask = Runnable(::drain)

    fun enqueue(contacts: List<AirPlayContact>): Boolean {
        val shouldSchedule: Boolean
        synchronized(lock) {
            if (closed) return false
            val sig = activeSignature(contacts)
            val tailIndex = pending.lastIndex
            if (sig != null && tailIndex >= 0 && activeSignature(pending[tailIndex]) == sig) {
                val priorSignature = if (tailIndex >= 1) {
                    activeSignature(pending[tailIndex - 1])
                } else {
                    establishedSignature
                }
                if (priorSignature == sig) {
                    pending[tailIndex] = contacts
                } else {
                    pending.add(contacts)
                }
            } else {
                pending.add(contacts)
            }
            shouldSchedule = !draining
            if (shouldSchedule) draining = true
        }
        if (shouldSchedule) {
            try {
                scheduleDrain(drainTask)
            } catch (_: Exception) {
                synchronized(lock) {
                    draining = false
                    pending.clear()
                }
                return false
            }
        }
        return true
    }

    fun reset() = synchronized(lock) {
        pending.clear()
        establishedSignature = null
    }

    fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        establishedSignature = null
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (closed || pending.isEmpty()) {
                    draining = false
                    return
                }
                val item = pending.removeAt(0)
                establishedSignature = activeSignature(item)
                item
            }
            deliver(next)
        }
    }

    private fun activeSignature(contacts: List<AirPlayContact>): List<Int>? {
        if (contacts.isEmpty() || contacts.any { !it.down }) return null
        return contacts.map { it.id }
    }
}
