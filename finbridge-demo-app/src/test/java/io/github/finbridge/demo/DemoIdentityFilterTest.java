package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.host.FinBridgePrincipal;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.SecurityContextRepository;

class DemoIdentityFilterTest {
    private static final String ISSUER = "https://issuer.test/realm";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsOAuthLoginWithUnexpectedPrincipalType() throws Exception {
        var user = new DefaultOAuth2User(List.of(), java.util.Map.of("sub", "synthetic-subject"), "sub");
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "finbridge");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(mock(DemoIdentityMembershipRepository.class), mock(SecurityContextRepository.class))
                .invoke(new MockHttpServletRequest(), response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, org.mockito.Mockito.never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshesTrustedPrincipalMembershipFromSessionBeforeContinuing() throws Exception {
        var memberships = mock(DemoIdentityMembershipRepository.class);
        var contexts = mock(SecurityContextRepository.class);
        var key = new DemoIdentityMembershipRepository.MembershipKey(ISSUER, "synthetic-subject");
        var principal = new FinBridgePrincipal("borrower", TENANT_ID);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_AUTHENTICATED"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", authorities);
        authentication.setDetails(key);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(memberships.findEnabled(ISSUER, "synthetic-subject")).thenReturn(Optional.of(
                new DemoIdentityMembershipRepository.Membership("borrower", TENANT_ID, false)));
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(memberships, contexts).invoke(request, response, chain);

        verify(contexts).saveContext(SecurityContextHolder.getContext(), request, response);
        verify(chain).doFilter(request, response);
    }

    private static ExposedFilter filter(DemoIdentityMembershipRepository memberships,
            SecurityContextRepository contexts) {
        return new ExposedFilter(memberships, contexts);
    }

    private static final class ExposedFilter extends DemoIdentityFilter {
        ExposedFilter(DemoIdentityMembershipRepository memberships, SecurityContextRepository contexts) {
            super(memberships, contexts);
        }

        void invoke(MockHttpServletRequest request, MockHttpServletResponse response, FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            doFilterInternal(request, response, chain);
        }
    }
}
