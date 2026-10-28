package io.github.finbridge.demo;

import io.github.finbridge.host.FinBridgePrincipal;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class DemoOidcUserService extends OidcUserService {
    private final DemoIdentityMembershipRepository memberships;
    private final Environment environment;
    private final OidcUserService delegate;

    DemoOidcUserService(DemoIdentityMembershipRepository memberships, Environment environment,
            OidcUserService delegate) {
        super();
        this.memberships = memberships;
        this.environment = environment;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        String expectedIssuer = Objects.requireNonNull(environment.getProperty("finbridge.demo.oidc-issuer"),
                "finbridge.demo.oidc-issuer must be configured");
        if (!expectedIssuer.equals(user.getIssuer().toString())) {
            throw new OAuth2AuthenticationException("OIDC issuer does not match configured issuer");
        }
        var key = new DemoIdentityMembershipRepository.MembershipKey(user.getIssuer().toString(), user.getSubject());
        var membership = memberships.findEnabled(key.issuer(), key.subject())
                .orElseThrow(() -> new OAuth2AuthenticationException("No enabled FinBridge membership"));
        var principal = new FinBridgePrincipal(membership.principalSubject(), membership.tenantId());
        return new DemoOidcUser(user, principal, key, membership.admin());
    }

}
