package io.github.finbridge.host;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

final class HostAuthorization {
    private HostAuthorization() {
    }

    static FinBridgePrincipal tenantPrincipal() {
        Authentication authentication = authentication();
        if (!(authentication.getPrincipal() instanceof FinBridgePrincipal principal)
                || principal.tenantId() == null) {
            throw new AccessDeniedException("Authenticated tenant identity is required");
        }
        return principal;
    }

    static String requireAdmin() {
        Authentication authentication = authentication();
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> Objects.equals(authority.getAuthority(), "ROLE_FINBRIDGE_ADMIN"));
        if (!admin) {
            throw new AccessDeniedException("FinBridge administrator role is required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof FinBridgePrincipal finBridgePrincipal) {
            return finBridgePrincipal.subject();
        }
        if (principal instanceof String subject && !subject.isBlank()) {
            return subject;
        }
        throw new AccessDeniedException("Authenticated administrator identity is required");
    }

    static void requireAdministrator() {
        requireAdmin();
    }

    static void requireOwnTenant(UUID tenantId) {
        if (!tenantPrincipal().tenantId().equals(Objects.requireNonNull(tenantId, "tenantId"))) {
            throw new AccessDeniedException("Tenant access denied");
        }
    }

    static <T> T asTenant(FinBridgePrincipal principal, Supplier<T> operation) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(operation, "operation");
        FinBridgePrincipal authenticated = tenantPrincipal();
        if (!authenticated.subject().equals(principal.subject())
                || !authenticated.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException("Tenant access denied");
        }
        return operation.get();
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return authentication;
    }
}
