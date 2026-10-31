package io.github.finbridge.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates OIDC membership on requests and persists only a trusted FinBridge principal. */
public class DemoIdentityFilter extends OncePerRequestFilter {
    private final DemoIdentityMembershipRepository memberships;
    private final SecurityContextRepository contexts;
    private final int sessionTimeoutSeconds;

    public DemoIdentityFilter(DemoIdentityMembershipRepository memberships,
            SecurityContextRepository contexts,
            @org.springframework.beans.factory.annotation.Value("${server.servlet.session.timeout:30m}")
            java.time.Duration sessionTimeout) {
        super();
        this.memberships = memberships;
        this.contexts = contexts;
        this.sessionTimeoutSeconds = Math.toIntExact(sessionTimeout.toSeconds());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof OAuth2AuthenticationToken oauth) {
            if (!(oauth.getPrincipal() instanceof DemoOidcUser user)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            if (!trust(user.finBridgePrincipal(), user, user.getAuthorities(), request, response)) {
                return;
            }
        } else if (current != null
                && current.getPrincipal() instanceof io.github.finbridge.host.FinBridgePrincipal principal
                && current.getDetails() instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal() instanceof DemoOidcUser user) {
            if (!trust(principal, user, current.getAuthorities(), request, response)) {
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean trust(io.github.finbridge.host.FinBridgePrincipal principal,
            DemoOidcUser user,
            java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> authorities,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) {
            session.setMaxInactiveInterval(sessionTimeoutSeconds);
        }
        var key = user.membershipKey();
        var membership = memberships.findEnabled(key.issuer(), key.subject());
        if (membership.isEmpty() || !membership.orElseThrow().principalSubject().equals(principal.subject())
                || !java.util.Objects.equals(membership.orElseThrow().tenantId(), principal.tenantId())
                || membership.orElseThrow().admin() != authorities.stream()
                        .anyMatch(authority -> "ROLE_FINBRIDGE_ADMIN".equals(authority.getAuthority()))) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "No enabled FinBridge membership");
            return false;
        }
        var trusted = UsernamePasswordAuthenticationToken.authenticated(principal, "", authorities);
        trusted.setDetails(new OAuth2AuthenticationToken(user, user.getAuthorities(), "finbridge"));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(trusted);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return !(authentication instanceof OAuth2AuthenticationToken)
                && (authentication == null || !(authentication.getPrincipal()
                        instanceof io.github.finbridge.host.FinBridgePrincipal));
    }
}
