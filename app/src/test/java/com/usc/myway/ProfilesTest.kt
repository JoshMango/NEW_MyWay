package com.usc.myway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure tag validation — normalize()/formatError() don't touch Firestore, so they run on the JVM.
class ProfilesTest {
    @Test fun normalizeStripsAtAndCase() {
        assertEquals("josh", Profiles.normalize("  @Josh "))
        assertEquals("a_b1", Profiles.normalize("A_B1"))
    }

    @Test fun validTagHasNoError() {
        assertNull(Profiles.formatError("josh_23"))
    }

    @Test fun invalidTagsRejected() {
        assert(Profiles.formatError("ab") != null)              // too short
        assert(Profiles.formatError("a".repeat(21)) != null)    // too long
        assert(Profiles.formatError("bad-tag") != null)         // illegal char
    }
}
