package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDispatchQueueTest {
    @Test
    fun coalescesIntermediateMovesWhilePreservingInitialDownAndUp() {
        val scheduled = ArrayDeque<Runnable>()
        val delivered = mutableListOf<List<AirPlayContact>>()
        val queue = TouchDispatchQueue(
            scheduleDrain = scheduled::addLast,
            deliver = delivered::add,
        )

        val down = listOf(AirPlayContact(id = 0, x = 0.1, y = 0.1, down = true))
        val move1 = listOf(AirPlayContact(id = 0, x = 0.2, y = 0.2, down = true))
        val move2 = listOf(AirPlayContact(id = 0, x = 0.3, y = 0.3, down = true))
        val move3 = listOf(AirPlayContact(id = 0, x = 0.4, y = 0.4, down = true))
        val up = listOf(AirPlayContact(id = 0, x = 0.4, y = 0.4, down = false))

        assertTrue(queue.enqueue(down))
        assertTrue(queue.enqueue(move1))
        assertTrue(queue.enqueue(move2))
        assertTrue(queue.enqueue(move3))
        assertTrue(queue.enqueue(up))
        assertEquals(1, scheduled.size)

        scheduled.removeFirst().run()
        assertEquals(listOf(down, move3, up), delivered)
    }

    @Test
    fun coalescesMovesWhenDownWasAlreadyDispatched() {
        val scheduled = ArrayDeque<Runnable>()
        val delivered = mutableListOf<List<AirPlayContact>>()
        val queue = TouchDispatchQueue(
            scheduleDrain = scheduled::addLast,
            deliver = delivered::add,
        )

        val down = listOf(AirPlayContact(id = 0, x = 0.1, y = 0.1, down = true))
        val move1 = listOf(AirPlayContact(id = 0, x = 0.2, y = 0.2, down = true))
        val move2 = listOf(AirPlayContact(id = 0, x = 0.5, y = 0.5, down = true))

        queue.enqueue(down)
        scheduled.removeFirst().run()
        assertEquals(listOf(down), delivered)

        queue.enqueue(move1)
        queue.enqueue(move2)
        scheduled.removeFirst().run()
        assertEquals(listOf(down, move2), delivered)
    }

    @Test
    fun preservesSecondFingerTransitionAndCloseRejectsFurtherInput() {
        val scheduled = ArrayDeque<Runnable>()
        val delivered = mutableListOf<List<AirPlayContact>>()
        val queue = TouchDispatchQueue(
            scheduleDrain = scheduled::addLast,
            deliver = delivered::add,
        )

        val oneDown = listOf(AirPlayContact(id = 0, x = 0.1, y = 0.1, down = true))
        val twoDown = listOf(
            AirPlayContact(id = 0, x = 0.1, y = 0.1, down = true),
            AirPlayContact(id = 1, x = 0.8, y = 0.8, down = true),
        )
        val twoMove1 = listOf(
            AirPlayContact(id = 0, x = 0.2, y = 0.2, down = true),
            AirPlayContact(id = 1, x = 0.7, y = 0.7, down = true),
        )
        val twoMove2 = listOf(
            AirPlayContact(id = 0, x = 0.3, y = 0.3, down = true),
            AirPlayContact(id = 1, x = 0.6, y = 0.6, down = true),
        )

        queue.enqueue(oneDown)
        queue.enqueue(twoDown)
        queue.enqueue(twoMove1)
        queue.enqueue(twoMove2)
        scheduled.removeFirst().run()

        assertEquals(listOf(oneDown, twoDown, twoMove2), delivered)

        queue.close()
        assertFalse(queue.enqueue(oneDown))
    }
}
