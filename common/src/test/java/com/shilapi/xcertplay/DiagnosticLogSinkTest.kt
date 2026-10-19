package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogSinkTest {
    @After fun detach() = DiagnosticLogSink.attach(null)

    @Test fun deliversToTheAttachedWriter() {
        val seen = mutableListOf<String>()
        DiagnosticLogSink.attach { seen += it }
        DiagnosticLogSink.append("WebCodecs failed: decoder unavailable")
        assertEquals(
            listOf("WebCodecs failed: decoder unavailable"),
            seen,
        )
    }

    @Test fun appendingWithoutAWriterIsSilent() {
        DiagnosticLogSink.attach(null)
        DiagnosticLogSink.append("no host activity is alive")
    }

    @Test fun detachingStopsDeliverySoALaterSessionCannotReceiveStaleEntries() {
        val seen = mutableListOf<String>()
        DiagnosticLogSink.attach { seen += it }
        DiagnosticLogSink.append("first")
        DiagnosticLogSink.attach(null)
        DiagnosticLogSink.append("second")
        assertEquals(listOf("first"), seen)
    }
}
