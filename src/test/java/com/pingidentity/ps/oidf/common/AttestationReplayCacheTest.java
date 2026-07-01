package com.pingidentity.ps.oidf.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttestationReplayCacheTest {

    @Test
    void firstUseAcceptedReplayRejected() {
        AttestationReplayCache cache = new AttestationReplayCache();
        assertTrue(cache.firstSeen("client-a", "jti-1", 300L));
        assertFalse(cache.firstSeen("client-a", "jti-1", 300L));
    }

    @Test
    void differentJtiAndClientAreIndependent() {
        AttestationReplayCache cache = new AttestationReplayCache();
        assertTrue(cache.firstSeen("client-a", "jti-1", 300L));
        assertTrue(cache.firstSeen("client-a", "jti-2", 300L));
        assertTrue(cache.firstSeen("client-b", "jti-1", 300L));
    }

    @Test
    void blankJtiRejected() {
        AttestationReplayCache cache = new AttestationReplayCache();
        assertThrows(IllegalArgumentException.class, () -> cache.firstSeen("client-a", "  ", 300L));
    }

    @Test
    void boundedCacheEvictsButStaysUsable() {
        AttestationReplayCache cache = new AttestationReplayCache(2);
        assertTrue(cache.firstSeen("c", "a", 300L));
        assertTrue(cache.firstSeen("c", "b", 300L));
        assertTrue(cache.firstSeen("c", "c", 300L)); // evicts eldest ("a")
        assertTrue(cache.size() <= 2);
        // "a" was evicted, so it is accepted again (acceptable trade-off documented on the cache)
        assertTrue(cache.firstSeen("c", "a", 300L));
    }
}
