/*
 * Tries a list of issuance-config resolvers in order; first to yield a config wins.
 */
package com.pingidentity.ps.oidf.common;

import java.util.List;

/**
 * Composes several {@link IssuanceClientResolver}s and tries them in order, returning the first that yields
 * a config. Wire them in <b>assurance order</b> — federation (chain-vouched) → CIMD (unsigned, self-hosted)
 * → PingFederate extended properties (locally provisioned) — so a chain-vouched source is always preferred.
 *
 * <p>If every resolver fails, the <em>first</em> resolver's exception is rethrown (the highest-assurance
 * source's diagnostic is the most informative). Note the tradeoff: a genuinely-broken high-assurance client
 * falls through to lower-assurance resolvers, which then also fail on the same {@code client_id} — so the
 * net effect is "unresolved," not a silent downgrade, but the first error is what surfaces.
 */
public final class CompositeIssuanceClientResolver implements IssuanceClientResolver {

    private final List<IssuanceClientResolver> resolvers;

    public CompositeIssuanceClientResolver(List<IssuanceClientResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public static CompositeIssuanceClientResolver of(IssuanceClientResolver... resolvers) {
        return new CompositeIssuanceClientResolver(List.of(resolvers));
    }

    @Override
    public AttestationIssuanceConfig resolve(String clientId) throws IssuanceException {
        IssuanceException first = null;
        for (IssuanceClientResolver resolver : this.resolvers) {
            try {
                return resolver.resolve(clientId);
            } catch (IssuanceException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
        throw IssuanceException.invalidClient("no resolver is configured to source config for: " + clientId);
    }
}
