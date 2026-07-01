/*
 * Process-wide singletons shared between the attestation runtime hook and the challenge endpoint.
 */
package com.pingidentity.ps.oidf.common;

/**
 * Holds the per-process {@link AttestationChallengeService} and {@link AttestationReplayCache} so the
 * challenge endpoint (which issues challenges) and the issuance-criteria hook (which consumes them and
 * detects replay) share the same state. Lazily initialized with defaults; the challenge servlet may
 * override sizing/TTL during {@code init()}.
 *
 * <p>State is per-node; clustered deployments should replace these with shared (cluster-aware) stores.
 */
public final class AttestationSupport {
    private static final Object LOCK = new Object();
    private static volatile AttestationChallengeService challengeService;
    private static volatile AttestationReplayCache replayCache;

    private AttestationSupport() {
    }

    public static AttestationChallengeService challengeService() {
        AttestationChallengeService local = challengeService;
        if (local == null) {
            synchronized (LOCK) {
                local = challengeService;
                if (local == null) {
                    local = challengeService = new AttestationChallengeService();
                }
            }
        }
        return local;
    }

    public static AttestationReplayCache replayCache() {
        AttestationReplayCache local = replayCache;
        if (local == null) {
            synchronized (LOCK) {
                local = replayCache;
                if (local == null) {
                    local = replayCache = new AttestationReplayCache();
                }
            }
        }
        return local;
    }

    public static void configureChallengeService(int maxEntries, long ttlSeconds) {
        synchronized (LOCK) {
            challengeService = new AttestationChallengeService(maxEntries, ttlSeconds);
        }
    }

    public static void configureReplayCache(int maxEntries) {
        synchronized (LOCK) {
            replayCache = new AttestationReplayCache(maxEntries);
        }
    }
}
