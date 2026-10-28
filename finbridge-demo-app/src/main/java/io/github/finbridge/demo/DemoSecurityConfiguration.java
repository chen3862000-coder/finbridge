package io.github.finbridge.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@Configuration
public class DemoSecurityConfiguration {
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @org.springframework.beans.factory.annotation.Value("${finbridge.demo.oidc-issuer}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${FINBRIDGE_OIDC_AUTHORIZATION_URI:https://localhost:8443/auth/realms/finbridge/protocol/openid-connect/auth}")
            String authorizationUri,
            @org.springframework.beans.factory.annotation.Value("${FINBRIDGE_OIDC_TOKEN_URI:http://keycloak:8080/auth/realms/finbridge/protocol/openid-connect/token}")
            String tokenUri,
            @org.springframework.beans.factory.annotation.Value("${FINBRIDGE_OIDC_JWK_SET_URI:http://keycloak:8080/auth/realms/finbridge/protocol/openid-connect/certs}")
            String jwkSetUri,
            @org.springframework.beans.factory.annotation.Value("${FINBRIDGE_OIDC_USERINFO_URI:http://keycloak:8080/auth/realms/finbridge/protocol/openid-connect/userinfo}")
            String userInfoUri,
            @org.springframework.beans.factory.annotation.Value("${FINBRIDGE_OIDC_CLIENT_ID:finbridge-demo}") String clientId) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("finbridge")
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .jwkSetUri(jwkSetUri)
                .userInfoUri(userInfoUri)
                .userNameAttributeName("sub")
                .issuerUri(issuer)
                .clientName("FinBridge Local OIDC")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public DemoIdentityFilter demoIdentityFilter(DemoIdentityMembershipRepository memberships,
            SecurityContextRepository contexts) {
        return new DemoIdentityFilter(memberships, contexts);
    }

    @Bean
    public OidcUserService oidcUserService(DemoIdentityMembershipRepository memberships,
            org.springframework.core.env.Environment environment) {
        return new DemoOidcUserService(memberships, environment, new OidcUserService());
    }

    @Bean
    public SecurityFilterChain demoSecurityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            DemoIdentityFilter identityFilter, OidcUserService oidcUserService,
            ClientRegistrationRepository registrations) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax").path("/"));
        DefaultOAuth2AuthorizationRequestResolver requests = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        requests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http.securityContext(context -> context.securityContextRepository(contexts))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/borrower", "/console", "/src/**", "/@vite/**", "/@react-refresh",
                                "/node_modules/**",
                                "/assets/**", "/favicon.ico", "/actuator/health/**",
                                "/api/csrf", "/oauth2/**", "/login/**", "/error").permitAll()
                .requestMatchers("/api/session").authenticated()
                        .requestMatchers("/api/console/**").hasRole("FINBRIDGE_AUTHENTICATED")
                        .requestMatchers("/api/borrower/**").hasRole("FINBRIDGE_AUTHENTICATED")
                        .requestMatchers("/api/admin/**").hasRole("FINBRIDGE_ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(login -> login.authorizationEndpoint(endpoint ->
                        endpoint.authorizationRequestResolver(requests)).userInfoEndpoint(userInfo ->
                        userInfo.oidcUserService(oidcUserService)))
                .oauth2Client(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .csrf(configurer -> configurer.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(csrfTokenRequestHandler()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(1))
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(policy -> policy.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterAfter(identityFilter,
                        org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter.class);
        return http.build();
    }

    private static CsrfTokenRequestHandler csrfTokenRequestHandler() {
        XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
        delegate.setCsrfRequestAttributeName(null);
        return delegate;
    }
}
