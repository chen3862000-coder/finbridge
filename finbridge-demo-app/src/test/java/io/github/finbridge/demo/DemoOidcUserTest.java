package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.finbridge.host.FinBridgePrincipal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class DemoOidcUserTest {
    private static final String ISSUER = "https://issuer.test/realm";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void mapsEnabledMembershipToTrustedBorrowerAndAdminAuthorities() {
        var repository = mock(DemoIdentityMembershipRepository.class);
        var environment = environment(ISSUER);
        when(repository.findEnabled(ISSUER, "synthetic-subject")).thenReturn(Optional.of(
                new DemoIdentityMembershipRepository.Membership("borrower-subject", TENANT_ID, false)));
        var service = service(repository, environment, oidcUser(ISSUER));

        DemoOidcUser user = (DemoOidcUser) service.loadUser(mock(OidcUserRequest.class));

        assertEquals(new FinBridgePrincipal("borrower-subject", TENANT_ID), user.finBridgePrincipal());
        assertEquals(new DemoIdentityMembershipRepository.MembershipKey(ISSUER, "synthetic-subject"),
                user.membershipKey());
        assertEquals("borrower-subject", user.getName());
        assertTrue(user.getAuthorities().stream().anyMatch(a -> "ROLE_FINBRIDGE_AUTHENTICATED"
                .equals(a.getAuthority())));
        assertFalse(user.getAuthorities().stream().anyMatch(a -> "ROLE_FINBRIDGE_ADMIN".equals(a.getAuthority())));
        OidcUser delegate = oidcUser(ISSUER);
        assertEquals(delegate.getClaims(), user.getClaims());
        assertEquals(delegate.getAttributes(), user.getAttributes());
        assertEquals(delegate.getIdToken(), user.getIdToken());
        assertEquals(delegate.getUserInfo(), user.getUserInfo());
    }

    @Test
    void rejectsMismatchedIssuerAndDisabledOrMissingMembership() {
        var repository = mock(DemoIdentityMembershipRepository.class);
        var mismatchedIssuerService = service(repository, environment(ISSUER),
                oidcUser("https://other.test/realm"));
        assertThrows(OAuth2AuthenticationException.class,
                () -> mismatchedIssuerService.loadUser(mock(OidcUserRequest.class)));

        when(repository.findEnabled(ISSUER, "synthetic-subject")).thenReturn(Optional.empty());
        var expectedService = service(repository, environment(ISSUER), oidcUser(ISSUER));
        assertThrows(OAuth2AuthenticationException.class,
                () -> expectedService.loadUser(mock(OidcUserRequest.class)));
    }

    @Test
    void mapsAdministratorMembershipWithoutBorrowerAuthority() {
        var repository = mock(DemoIdentityMembershipRepository.class);
        when(repository.findEnabled(ISSUER, "synthetic-subject")).thenReturn(Optional.of(
                new DemoIdentityMembershipRepository.Membership("demo-admin", null, true)));
        var service = service(repository, environment(ISSUER), oidcUser(ISSUER));

        DemoOidcUser user = (DemoOidcUser) service.loadUser(mock(OidcUserRequest.class));

        assertEquals(new FinBridgePrincipal("demo-admin", null), user.finBridgePrincipal());
        assertTrue(user.getAuthorities().stream().anyMatch(a -> "ROLE_FINBRIDGE_ADMIN"
                .equals(a.getAuthority())));
        assertFalse(user.getAuthorities().stream().anyMatch(a -> "ROLE_FINBRIDGE_AUTHENTICATED"
                .equals(a.getAuthority())));
    }

    private static DemoOidcUserService service(DemoIdentityMembershipRepository repository,
            Environment environment, OidcUser user) {
        OidcUserService delegate = mock(OidcUserService.class);
        when(delegate.loadUser(org.mockito.ArgumentMatchers.any(OidcUserRequest.class))).thenReturn(user);
        return new DemoOidcUserService(repository, environment, delegate);
    }

    private static Environment environment(String issuer) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("finbridge.demo.oidc-issuer")).thenReturn(issuer);
        return environment;
    }

    private static OidcUser oidcUser(String issuer) {
        return new DefaultOidcUser(java.util.List.of(), idToken(issuer), new OidcUserInfo(userInfo()), "sub");
    }

    private static OidcIdToken idToken(String issuer) {
        return OidcIdToken.withTokenValue("synthetic-token").issuer(issuer).subject("synthetic-subject")
                .issuedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(java.time.Instant.parse("2026-01-01T00:02:00Z")).build();
    }

    private static Map<String, Object> userInfo() {
        return Map.of("sub", "synthetic-subject", "name", "Synthetic User");
    }
}
