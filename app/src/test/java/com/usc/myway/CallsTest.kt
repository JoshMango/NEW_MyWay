package com.usc.myway

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure duration formatting for call-log messages — no Firestore, runs on the JVM.
class CallsTest {
    @Test fun formatsSecondsMinutesHours() {
        assertEquals("0s", Calls.formatDuration(0))
        assertEquals("20s", Calls.formatDuration(20_000))
        assertEquals("1m", Calls.formatDuration(60_000))
        assertEquals("5m 12s", Calls.formatDuration(5 * 60_000 + 12_000))
        assertEquals("1h 3m", Calls.formatDuration(63 * 60_000L))
    }

    @Test fun clampsNegative() {
        assertEquals("0s", Calls.formatDuration(-5_000))
    }
}
