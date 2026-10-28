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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor mappedLogin(
            String subject, UUID tenant, boolean admin) {
        var idToken = org.springframework.security.oauth2.core.oidc.OidcIdToken.withTokenValue("test-token")
                .issuer(ISSUER).subject(subject).issuedAt(java.time.Instant.now().minusSeconds(1))
                .expiresAt(java.time.Instant.now().plusSeconds(120)).build();
        var userInfo = new org.springframework.security.oauth2.core.oidc.OidcUserInfo(
                java.util.Map.of("sub", subject));
        var delegate = new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                java.util.List.of(), idToken, userInfo, "sub");
        var membership = new DemoIdentityMembershipRepository.MembershipKey(ISSUER, subject);
        String principalSubject = switch (subject) {
            case "alpha-subject" -> "borrower-alpha";
            case "beta-subject" -> "borrower-beta";
            case "admin-subject" -> "demo-admin";
            default -> "borrower-" + subject;
        };
        var user = new DemoOidcUser(delegate,
                new io.github.finbridge.host.FinBridgePrincipal(principalSubject, tenant), membership, admin);
        var auth = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                user, user.getAuthorities(), "finbridge");
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
