package com.notiregx.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun newerVersionsAreDetected() {
        assertTrue(UpdatePolicy.isNewer("v1.1.0", "1.0.0"))
        assertTrue(UpdatePolicy.isNewer("1.0.10", "1.0.9"))
        assertTrue(UpdatePolicy.isNewer("v2", "1.9.9"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(UpdatePolicy.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("v1.0", "1.0.1"))
        assertFalse(UpdatePolicy.isNewer("v1.0", "1.0.0"))
    }

    @Test fun garbageIsNotNewer() {
        assertFalse(UpdatePolicy.isNewer("nightly", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("v2.0.0", ""))
    }

    @Test fun checksOnlyAfterTheWait() {
        assertTrue(UpdatePolicy.shouldCheck(0, UpdatePolicy.CHECK_EVERY_MS))
        assertFalse(UpdatePolicy.shouldCheck(1_000, UpdatePolicy.CHECK_EVERY_MS))
    }
}
