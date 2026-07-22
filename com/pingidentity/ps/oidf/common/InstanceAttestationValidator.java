/*
 * Validates a workload's instance-attestation of one format into a format-neutral InstanceIdentity.
 */
package com.pingidentity.ps.oidf.common;

/**
 * Validates a workload's instance attestation of one <em>format</em> and returns a validated
 * {@link InstanceIdentity}. SPIFFE JWT-SVIDs ({@link SpiffeInstanceAttestationValidator}) and digital
 * wallet WIAs ({@link WalletInstanceAttestationValidator}) are two implementations; device attestations
 * (Android Key Attestation, Apple App Attest) could be more. The issuance endpoint selects an
 * implementation per request — by declared or sniffed format — via {@link InstanceAttestationValidators},
 * so the instance-identity layer is pluggable without touching the downstream binding, entitlement and
 * minting steps.
 *
 * <p>The <em>client</em> layer (which client, and what it may be granted) is resolved separately by an
 * {@link IssuanceClientResolver}; this interface is only the <em>instance</em> layer (which running
 * instance is asking). The two meet in the servlet.
 */
public interface InstanceAttestationValidator {

    /** The instance-attestation format this validator handles ({@code "spiffe"}, {@code "wallet"}, …). */
    String format();

    /**
     * Validates the presented instance attestation against the resolved client config.
     *
     * @param presented the compact instance attestation (a JWT-SVID, a WIA, …)
     * @param config    the resolved client issuance config (trust bundle / expected audience / trust-domain pin)
     * @return the validated, format-neutral instance identity
     * @throws IssuanceException on any validation failure
     */
    InstanceIdentity validate(String presented, AttestationIssuanceConfig config) throws IssuanceException;
}
