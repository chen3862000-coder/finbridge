package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.host.FinBridgePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class DemoSessionControllerTest {
    private final DemoSessionController controller = new DemoSessionController();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sessionViewDistinguishesAnonymousBorrowerAndAdministrator() {
        assertFalse(controller.session().authenticated());

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new FinBridgePrincipal("borrower", UUID.randomUUID()), "",
                List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_AUTHENTICATED"))));
        var borrower = controller.session();
        assertTrue(borrower.authenticated());
        assertEquals("borrower", borrower.subject());
        assertFalse(borrower.administrator());

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new FinBridgePrincipal("admin", null), "",
                List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));
        assertTrue(controller.session().administrator());
    }

    @Test
    void csrfViewReturnsTheConfiguredHeaderAndToken() {
        HttpServletRequest request = new MockHttpServletRequest();
        CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-token");
        request.setAttribute(CsrfToken.class.getName(), token);

        var view = controller.csrf(request);

        assertEquals("X-CSRF-TOKEN", view.headerName());
        assertEquals("synthetic-token", view.token());
    }
}
