package com.pingidentity.ps.oidf.servlet.clientregistration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingidentity.ps.oidf.common.ClientStore;
import com.pingidentity.ps.oidf.common.TrustChainValidationResult;
import com.pingidentity.ps.oidf.common.TrustChainValidator;
import java.util.List;
import java.util.Map;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sourceid.oauth20.domain.Client;

/** Unit tests for OpenID Federation §12.1 automatic (transparent) client registration. */
class RegistrationServiceAutomaticRegisterTest {

    private static final String CLIENT_ID = "https://rp.example.com/e/agent-42";
    private static final String OP_ISSUER = "https://as.example.com";
    private static final List<String> TRUST_CHAIN = List.of("leafJwt", "anchorJwt");

    private RegistrationService service(TrustChainValidator validator, ClientStore store) {
        return new RegistrationService(new RegistrationConfiguration("https://tc.example", false), validator, store);
    }

    private TrustChainValidationResult resultWith(Map<String, Object> leafMetadata) {
        JwtClaims leaf = new JwtClaims();
        leaf.setClaim("jwks", Map.of("keys", List.of(
                Map.of("kty", "EC", "crv", "P-256", "x", "abc", "y", "def", "kid", "k1"))));
        return new TrustChainValidationResult("https://tc.example", CLIENT_ID, leafMetadata, TRUST_CHAIN, leaf);
    }

    @Test
    void autoRegistersWhenClientAdvertisesAutomatic() throws Exception {
        TrustChainValidator validator = mock(TrustChainValidator.class);
        ClientStore store = mock(ClientStore.class);
        when(store.get(CLIENT_ID)).thenReturn(null);
        Map<String, Object> meta = Map.of(
                "client_registration_types", List.of("automatic"),
                "grant_types", List.of("client_credentials"),
                "token_endpoint_auth_method", "private_key_jwt",
                "scope", "read_accounts create_opportunity",
                "client_name", "Agent 42");
        when(validator.validate(anyList(), eq(CLIENT_ID), eq(OP_ISSUER), anyLong(), anyLong(), anyLong()))
                .thenReturn(resultWith(meta));

        RegisteredClient registered = service(validator, store).automaticRegister(TRUST_CHAIN, CLIENT_ID, OP_ISSUER);

        assertNotNull(registered);
        assertEquals("auto_registered", registered.toMap().get("status"));
        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(store).add(captor.capture());
        Client provisioned = captor.getValue();
        assertEquals(CLIENT_ID, provisioned.getClientId());
        assertEquals("auto_registered", provisioned.getExtendedParams().get("status").getElements().get(0));
    }

    @Test
    void isIdempotentForAnAlreadyRegisteredClient() throws Exception {
        TrustChainValidator validator = mock(TrustChainValidator.class);
        ClientStore store = mock(ClientStore.class);
        when(store.get(CLIENT_ID)).thenReturn(new Client());

        assertNull(service(validator, store).automaticRegister(TRUST_CHAIN, CLIENT_ID, OP_ISSUER));

        verify(store, never()).add(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(validator);
    }

    @Test
    void refusesClientThatDoesNotAdvertiseAutomatic() throws Exception {
        TrustChainValidator validator = mock(TrustChainValidator.class);
        ClientStore store = mock(ClientStore.class);
        when(store.get(CLIENT_ID)).thenReturn(null);
        Map<String, Object> meta = Map.of(
                "grant_types", List.of("client_credentials"), "scope", "read_accounts");
        when(validator.validate(anyList(), eq(CLIENT_ID), eq(OP_ISSUER), anyLong(), anyLong(), anyLong()))
                .thenReturn(resultWith(meta));

        assertThrows(IllegalStateException.class,
                () -> service(validator, store).automaticRegister(TRUST_CHAIN, CLIENT_ID, OP_ISSUER));
        verify(store, never()).add(org.mockito.ArgumentMatchers.any());
    }
}
