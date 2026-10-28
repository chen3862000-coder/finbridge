package io.github.finbridge.demo;

import io.github.finbridge.host.FinBridgePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoSessionController {
    @GetMapping("/api/session")
    public SessionView session() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof FinBridgePrincipal principal)) {
            return new SessionView(false, null, false);
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_FINBRIDGE_ADMIN".equals(authority.getAuthority()));
        return new SessionView(true, principal.subject(), admin);
    }

    @GetMapping("/api/csrf")
    public CsrfView csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    public record SessionView(boolean authenticated, String subject, boolean administrator) { }

    public record CsrfView(String headerName, String token) { }
}
