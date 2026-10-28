package io.github.finbridge.demo;

import io.github.finbridge.host.FinBridgePrincipal;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public class DemoOidcUser implements OidcUser {
    private final OidcUser delegate;
    private final FinBridgePrincipal principal;
    private final Collection<GrantedAuthority> authorities;
    private final DemoIdentityMembershipRepository.MembershipKey membershipKey;

    public DemoOidcUser(OidcUser delegate, FinBridgePrincipal principal,
            DemoIdentityMembershipRepository.MembershipKey membershipKey, boolean admin) {
        this.delegate = delegate;
        this.principal = principal;
        this.membershipKey = membershipKey;
        java.util.List<GrantedAuthority> granted = new java.util.ArrayList<>(delegate.getAuthorities());
        if (admin) {
            granted.add(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"));
        } else {
            granted.add(new SimpleGrantedAuthority("ROLE_FINBRIDGE_AUTHENTICATED"));
        }
        authorities = java.util.List.copyOf(granted);
    }

    public FinBridgePrincipal finBridgePrincipal() {
        return principal;
    }

    public DemoIdentityMembershipRepository.MembershipKey membershipKey() { return membershipKey; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public String getName() { return principal.subject(); }
    @Override public org.springframework.security.oauth2.core.oidc.OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public org.springframework.security.oauth2.core.oidc.OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }

}
