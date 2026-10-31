package io.github.finbridge.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import io.github.finbridge.host.FinBridgePrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@SpringBootTest(properties = {
        "finbridge.demo.oidc-issuer=https://issuer.test/realm"
})
@AutoConfigureMockMvc
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class DemoSecurityHttpTest {
    private static final UUID TENANT_ALPHA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_BETA = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String ISSUER = "https://issuer.test/realm";
    private static final UUID ALPHA_APPLICATION = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired private MockMvc mvc;

    @Autowired private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
            clientRegistrations;

    @org.testcontainers.junit.jupiter.Container
    private static final org.testcontainers.containers.PostgreSQLContainer<?> DATABASE =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @org.springframework.test.context.DynamicPropertySource
    static void databaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Test
    void borrowerRequiresAuthenticationAndCannotAccessAdminRoutes() throws Exception {
        var anonymousSession = mvc.perform(get("/api/session"))
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(anonymousSession.getResponse().getContentAsString()
                .contains("\"authenticated\":false"));
        var borrower = mvc.perform(get("/api/borrower/applications")).andExpect(status().is3xxRedirection())
                .andReturn();
        var admin = mvc.perform(get("/api/admin/plugins").with(mappedLogin("alpha-subject", TENANT_ALPHA, false)))
                .andExpect(status().isForbidden()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(borrower);
        org.junit.jupiter.api.Assertions.assertNotNull(admin);
    }

    @Test
    void unknownOidcSubjectCannotObtainFinBridgeMembership() throws Exception {
        var result = mvc.perform(get("/api/session").with(mappedLogin("unmapped-subject", TENANT_ALPHA, false)))
                .andExpect(status().isForbidden()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void stateChangingRequestRequiresCsrfWithValidOidcIdentity() throws Exception {
        var result = mvc.perform(post("/api/borrower/applications")
                        .contentType("application/json")
                        .content("{\"productCode\":\"CASH_LOAN_DEMO\",\"amount\":\"1200.00\","
                                + "\"currency\":\"CNY\",\"termMonths\":3}")
                        .header("Idempotency-Key", "manual-test-application")
                        .with(mappedLogin("alpha-subject", TENANT_ALPHA, false)))
                .andExpect(status().isForbidden()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void borrowerCannotReadAnotherTenantsApplicationByObjectId() throws Exception {
        var result = mvc.perform(get("/api/borrower/applications/" + ALPHA_APPLICATION)
                        .with(mappedLogin("beta-subject", TENANT_BETA, false)))
                .andExpect(status().isNotFound()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void borrowerRoutesRequireTenantRoleAndRetainSensitiveAdminSeparation() throws Exception {
        var product = mvc.perform(get("/api/borrower/products")
                        .with(mappedLogin("alpha-subject", TENANT_ALPHA, false)))
                .andExpect(status().isOk()).andReturn();
        var borrowerAdminRoute = mvc.perform(get("/api/admin/plugins")
                        .with(mappedLogin("alpha-subject", TENANT_ALPHA, false)))
                .andExpect(status().isForbidden()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(product);
        org.junit.jupiter.api.Assertions.assertNotNull(borrowerAdminRoute);
    }

    @Test
    void administratorCannotUseBorrowerApiAndBorrowerCannotUseAdminApi() throws Exception {
        var adminBorrowerRoute = mvc.perform(get("/api/borrower/products")
                        .with(mappedLogin("admin-subject", null, true)))
                .andExpect(status().isForbidden()).andReturn();
        var adminRoute = mvc.perform(get("/api/admin/plugins")
                        .with(mappedLogin("admin-subject", null, true)))
                .andExpect(status().isOk()).andReturn();
        var borrowerAdminRoute = mvc.perform(get("/api/admin/plugins")
                        .with(mappedLogin("alpha-subject", TENANT_ALPHA, false)))
                .andExpect(status().isForbidden()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(adminBorrowerRoute);
        org.junit.jupiter.api.Assertions.assertNotNull(adminRoute);
        org.junit.jupiter.api.Assertions.assertNotNull(borrowerAdminRoute);
    }

    @Test
    void oidcLoginRequestsCredentialsAndRetainsPkce() throws Exception {
        var response = mvc.perform(get("/oauth2/authorization/finbridge"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        var authorization = java.net.URI.create(response.getRedirectedUrl());
        var parameters = java.util.Arrays.stream(authorization.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(parts -> parts[0],
                        parts -> parts.length == 2 ? parts[1] : ""));

        org.junit.jupiter.api.Assertions.assertEquals("login", parameters.get("prompt"));
        org.junit.jupiter.api.Assertions.assertFalse(parameters.containsKey("max_age"));
        org.junit.jupiter.api.Assertions.assertEquals("S256", parameters.get("code_challenge_method"));
        org.junit.jupiter.api.Assertions.assertNotNull(parameters.get("code_challenge"));
    }

    @Test
    void oidcAuthenticationFailureReturnsToBorrowerPage() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        request.setContextPath("");
        DemoSecurityConfiguration.redirectAfterOidcFailure(request, response,
                new org.springframework.security.authentication.BadCredentialsException("invalid credentials"));

        org.junit.jupiter.api.Assertions.assertEquals("/borrower?loginError", response.getRedirectedUrl());
    }

    @Test
    void logoutRedirectsThroughOidcEndSessionAndStartsAccountSelection() throws Exception {
        var registrations = new InMemoryClientRegistrationRepository(testRegistration());
        var handler = DemoSecurityConfiguration.oidcLogoutSuccessHandler(registrations);
        var idToken = OidcIdToken.withTokenValue("id-token")
                .issuer(ISSUER).subject("alpha-subject")
                .issuedAt(java.time.Instant.now().minusSeconds(10))
                .expiresAt(java.time.Instant.now().plusSeconds(120)).build();
        var user = new DefaultOidcUser(java.util.List.of(), idToken,
                new OidcUserInfo(java.util.Map.of("sub", "alpha-subject")), "sub");
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/logout");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        request.setScheme("https");
        request.setServerName("localhost");
        request.setServerPort(8443);
        var trustedAuthentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(new FinBridgePrincipal("borrower-alpha", TENANT_ALPHA), "", user.getAuthorities());
        trustedAuthentication.setDetails(new OAuth2AuthenticationToken(user, user.getAuthorities(), "finbridge"));

        handler.onLogoutSuccess(request, response, trustedAuthentication);

        org.junit.jupiter.api.Assertions.assertTrue(response.getRedirectedUrl().startsWith(
                ISSUER + "/protocol/openid-connect/logout?"), response.getRedirectedUrl());
        var parameters = java.util.Arrays.stream(java.net.URI.create(response.getRedirectedUrl()).getRawQuery()
                        .split("&"))
                .map(value -> value.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(parts -> parts[0],
                        parts -> java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "",
                                java.nio.charset.StandardCharsets.UTF_8)));
        org.junit.jupiter.api.Assertions.assertTrue(parameters.containsKey("id_token_hint"), parameters.toString());
        org.junit.jupiter.api.Assertions.assertEquals("https://localhost:8443/oauth2/authorization/finbridge",
                parameters.get("post_logout_redirect_uri"));
    }

    @Test
    void clientRegistrationHasKeycloakEndSessionEndpointForLogout() {
        ClientRegistration registration = clientRegistrations.findByRegistrationId("finbridge");
        org.junit.jupiter.api.Assertions.assertEquals(
                ISSUER + "/protocol/openid-connect/logout",
                registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint"));
    }

    private static ClientRegistration testRegistration() {
        return ClientRegistration.withRegistrationId("finbridge")
                .clientId("finbridge-demo")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost:8443/login/oauth2/code/finbridge")
                .scope("openid", "profile")
                .authorizationUri("https://localhost:8443/auth/realms/finbridge/protocol/openid-connect/auth")
                .tokenUri("http://keycloak/auth/realms/finbridge/protocol/openid-connect/token")
                .jwkSetUri("http://keycloak/auth/realms/finbridge/protocol/openid-connect/certs")
                .userInfoUri("http://keycloak/auth/realms/finbridge/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .issuerUri(ISSUER)
                .providerConfigurationMetadata(java.util.Map.of("end_session_endpoint",
                        ISSUER + "/protocol/openid-connect/logout"))
                .clientName("FinBridge")
                .build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor mappedLogin(
            String subject, UUID tenant, boolean admin) {
        var idToken = OidcIdToken.withTokenValue("test-token")
                .issuer(ISSUER).subject(subject).issuedAt(java.time.Instant.now().minusSeconds(1))
                .expiresAt(java.time.Instant.now().plusSeconds(120)).build();
        var userInfo = new OidcUserInfo(java.util.Map.of("sub", subject));
        OidcUser delegate = new DefaultOidcUser(java.util.List.of(), idToken, userInfo, "sub");
        var membership = new DemoIdentityMembershipRepository.MembershipKey(ISSUER, subject);
        String principalSubject = switch (subject) {
            case "alpha-subject" -> "borrower-alpha";
            case "beta-subject" -> "borrower-beta";
            case "admin-subject" -> "demo-admin";
            default -> "borrower-" + subject;
        };
        var user = new DemoOidcUser(delegate,
                new FinBridgePrincipal(principalSubject, tenant), membership, admin);
        var auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "finbridge");
        return authentication(auth);
    }

    @TestConfiguration
    static class Memberships {
        @Bean
        @Primary
        DemoIdentityMembershipRepository memberships(javax.sql.DataSource dataSource) {
            return new DemoIdentityMembershipRepository(dataSource) {
                @Override
                public Optional<Membership> findEnabled(String issuer, String subject) {
                    if (!ISSUER.equals(issuer)) {
                        return Optional.empty();
                    }
                    return super.findEnabled(issuer, subject);
                }
            };
        }

        @Bean
        org.springframework.boot.ApplicationRunner seedTestMemberships(DemoIdentityMembershipRepository memberships,
                org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return arguments -> {
                memberships.assignDemoMembership(ISSUER, "alpha-subject", "borrower-alpha", TENANT_ALPHA, false);
                memberships.assignDemoMembership(ISSUER, "beta-subject", "borrower-beta", TENANT_BETA, false);
                memberships.assignDemoMembership(ISSUER, "admin-subject", "demo-admin", null, true);
                var now = java.sql.Timestamp.from(java.time.Instant.now());
                jdbc.update("INSERT INTO loan_applications (application_id, tenant_id, applicant_id, product_code, "
                                + "product_version, requested_amount, currency, term_months, status, idempotency_key, "
                                + "request_fingerprint, created_at, updated_at, version) "
                                + "VALUES (?, ?, ?, 'CASH_LOAN_DEMO', 1, 1200.00, 'CNY', 3, 'SUBMITTED', ?, ?, ?, ?, 0)",
                        ALPHA_APPLICATION, TENANT_ALPHA, "synthetic-alpha", "test-alpha-application",
                        "test-fingerprint", now, now);
            };
        }
    }
}
