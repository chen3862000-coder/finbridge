package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class OidcIdTokenValidationTest {
    private static final String ISSUER = "https://issuer.test/realm";
    private static final String CLIENT_ID = "finbridge-demo";
    private static final Instant NOW = Instant.now();

    @Test
    void rejectsExpiredWrongIssuerAndWrongAudienceIdTokens() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("validation-key").generate();
        var registration = registration();
        var expired = provider(registration, key, claims(ISSUER, CLIENT_ID, NOW.minusSeconds(120), NOW.minusSeconds(60),
                "valid-nonce"));
        assertInvalidToken(expired, "invalid_id_token");

        var notYetValid = provider(registration, key, claims(ISSUER, CLIENT_ID, NOW.plusSeconds(60),
                NOW.plusSeconds(120), "valid-nonce"));
        assertTokenRejected(notYetValid);

        var wrongIssuer = provider(registration, key, claims("https://other.test/realm", CLIENT_ID,
                NOW.minusSeconds(60), NOW.plusSeconds(60), "valid-nonce"));
        assertInvalidToken(wrongIssuer, "invalid_id_token");

        var wrongAudience = provider(registration, key, claims(ISSUER, "another-client", NOW.minusSeconds(60),
                NOW.plusSeconds(60), "valid-nonce"));
        assertInvalidToken(wrongAudience, "invalid_id_token");
    }

    @Test
    void rejectsIdTokenSignedByAnUntrustedKey() throws Exception {
        var trustedKey = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
        var untrustedKey = new RSAKeyGenerator(2048).keyID("untrusted-key").generate();
        var claims = claims(ISSUER, CLIENT_ID, NOW.minusSeconds(60), NOW.plusSeconds(60), "valid-nonce");
        var provider = provider(registration(), trustedKey, claims, untrustedKey);

        assertInvalidToken(provider, "invalid_id_token");
    }

    @Test
    void rejectsMalformedIdToken() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("malformed-key").generate();
        assertInvalidToken(provider(registration(), key, "not-a-jwt"), "invalid_id_token");
    }

    @Test
    void rejectsNonceMismatchAfterVerifyingTheSignedIdToken() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("nonce-key").generate();
        var claims = claims(ISSUER, CLIENT_ID, NOW.minusSeconds(60), NOW.plusSeconds(60), "wrong-nonce-hash");
        var provider = provider(registration(), key, claims);
        var authentication = login("expected-raw-nonce");

        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> provider.authenticate(authentication));

        assertEquals("invalid_nonce", failure.getError().getErrorCode(), failure.getMessage());
    }

    private static void assertInvalidToken(OidcAuthorizationCodeAuthenticationProvider provider, String code) {
        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> provider.authenticate(login("expected-raw-nonce")));
        assertEquals(code, failure.getError().getErrorCode());
    }

    private static void assertTokenRejected(OidcAuthorizationCodeAuthenticationProvider provider) {
        assertThrows(OAuth2AuthenticationException.class, () -> provider.authenticate(login("expected-raw-nonce")));
    }

    private static OidcAuthorizationCodeAuthenticationProvider provider(ClientRegistration registration,
            com.nimbusds.jose.jwk.RSAKey trustedKey, JwtClaimsSet claims) throws Exception {
        return provider(registration, trustedKey, claims, trustedKey);
    }

    private static OidcAuthorizationCodeAuthenticationProvider provider(ClientRegistration registration,
            com.nimbusds.jose.jwk.RSAKey trustedKey, JwtClaimsSet claims,
            com.nimbusds.jose.jwk.RSAKey signingKey) throws Exception {
        JwtEncoder encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                (selector, context) -> List.of(signingKey));
        String idToken = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return provider(registration, trustedKey, idToken);
    }

    private static OidcAuthorizationCodeAuthenticationProvider provider(ClientRegistration registration,
            com.nimbusds.jose.jwk.RSAKey trustedKey, String idToken) {
        OAuth2AccessTokenResponse tokenResponse = OAuth2AccessTokenResponse.withToken("synthetic-access-token")
                .tokenType(org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(60)
                .scopes(Set.of("openid"))
                .additionalParameters(Map.of("id_token", idToken))
                .build();
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens = request -> tokenResponse;
        OidcAuthorizationCodeAuthenticationProvider provider = new OidcAuthorizationCodeAuthenticationProvider(
                tokens, mock(org.springframework.security.oauth2.client.userinfo.OAuth2UserService.class));
        provider.setJwtDecoderFactory(ignored -> decoder(trustedKey, registration));
        return provider;
    }

    private static NimbusJwtDecoder decoder(com.nimbusds.jose.jwk.RSAKey key,
            ClientRegistration registration) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                    .createDefaultWithValidators(new org.springframework.security.oauth2.client.oidc.authentication
                            .OidcIdTokenValidator(registration)));
            return decoder;
        } catch (com.nimbusds.jose.JOSEException invalidKey) {
            throw new IllegalStateException("Test RSA key was invalid", invalidKey);
        }
    }

    private static OAuth2LoginAuthenticationToken login(String expectedNonce) {
        var authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.test/authorize")
                .clientId(CLIENT_ID)
                .redirectUri("https://localhost:8443/login/oauth2/code/finbridge")
                .scopes(Set.of("openid"))
                .state("test-state")
                .attributes(Map.of("nonce", expectedNonce))
                .build();
        var authorizationResponse = OAuth2AuthorizationResponse.success("test-code")
                .redirectUri(authorizationRequest.getRedirectUri())
                .state(authorizationRequest.getState())
                .build();
        return new OAuth2LoginAuthenticationToken(registration(),
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("finbridge")
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri("https://issuer.test/authorize")
                .tokenUri("https://issuer.test/token")
                .jwkSetUri("https://issuer.test/jwks")
                .issuerUri(ISSUER)
                .userNameAttributeName("sub")
                .clientName("FinBridge Test")
                .build();
    }

    private static JwtClaimsSet claims(String issuer, String audience, Instant issuedAt,
            Instant expiresAt, String nonce) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("synthetic-subject")
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("nonce", nonce)
                .build();
    }
}
