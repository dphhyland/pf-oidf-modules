/*
 * The SPIFFE implementation of InstanceAttestationValidator.
 */
package com.pingidentity.ps.oidf.common;

/**
 * The SPIFFE implementation of {@link InstanceAttestationValidator}: it validates a SPIFFE JWT-SVID against
 * the client's SPIFFE trust bundle (delegating to {@link SpiffeSvidValidator}) and adapts the result to a
 * format-neutral {@link InstanceIdentity}. This is the original instance-identity path — infrastructure
 * workloads that receive an SVID from a SPIFFE runtime (SPIRE) — now expressed as one pluggable format
 * rather than the only one.
 */
public final class SpiffeInstanceAttestationValidator implements InstanceAttestationValidator {

    /** The format label for SPIFFE, used in selection and as {@code workload.attested_by}. */
    public static final String FORMAT = "spiffe";

    private final SpiffeSvidValidator delegate;

    public SpiffeInstanceAttestationValidator() {
        this(new SpiffeSvidValidator());
    }

    public SpiffeInstanceAttestationValidator(SpiffeSvidValidator delegate) {
        this.delegate = delegate;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public InstanceIdentity validate(String presented, AttestationIssuanceConfig config) throws IssuanceException {
        SpiffeSvid svid = this.delegate.validate(
                presented, config.bundleKeys(), config.issuer(), config.expectedTrustDomain());
        return InstanceIdentity.ofSpiffe(svid);
    }
}
