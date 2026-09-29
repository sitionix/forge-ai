package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpOAuthClient;
import com.sitionix.forgeagent.infrastructure.local.mcp.McpEndpointPolicy;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.*;

/** OAuth protocol is provided by Spring; only Forge metadata and safe boundaries live here. */
public final class SpringMcpOAuthClient implements McpOAuthClient {
    private static final String RAW_EXPIRY = "forge.observed-expires-in";
    private static final String RAW_REFRESH_EXPIRY = "forge.observed-refresh-expires-in";
    private static final String SCOPE_OBSERVED = "forge.scope-observed";
    private final RestTemplate rest;
    private final McpEndpointPolicy endpoints;
    private final URI callback;

    public SpringMcpOAuthClient(RestTemplate rest, McpEndpointPolicy endpoints, URI callback) {
        this.rest = rest; this.endpoints = endpoints; this.callback = callback;
    }

    public McpOAuthAuthorization authorization(McpOAuthConfiguration config, URI redirectUri, String state) {
        if (!callback.equals(redirectUri) || state == null || state.isBlank()) throw McpOAuthException.invalidTransaction();
        var builder = OAuth2AuthorizationRequest.authorizationCode().authorizationUri(config.authorizationEndpoint().toString())
                .clientId(config.clientId()).redirectUri(callback.toString()).scopes(config.scopes()).state(state)
                .additionalParameters(Map.of("resource", config.resource().toString()));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        var request = builder.build();
        return new McpOAuthAuthorization(URI.create(request.getAuthorizationRequestUri()), request.getAttribute("code_verifier"));
    }

    public McpOAuthTokens exchange(McpOAuthConfiguration config, McpOAuthCredentials credentials, String code, String verifier) {
        validateEndpoint(config.tokenEndpoint());
        if (code == null || code.isBlank() || verifier == null || verifier.isBlank()) throw McpOAuthException.invalidTransaction();
        var registration = registration(config, credentials);
        var authorization = OAuth2AuthorizationRequest.authorizationCode().authorizationUri(config.authorizationEndpoint().toString())
                .clientId(config.clientId()).redirectUri(callback.toString()).scopes(config.scopes()).state("validated")
                .attributes(Map.of("code_verifier", verifier)).build();
        var response = OAuth2AuthorizationResponse.success(code).redirectUri(callback.toString()).state("validated").build();
        var converter = new OAuth2AuthorizationCodeGrantRequestEntityConverter();
        converter.addParametersConverter(request -> resource(config));
        var client = new DefaultAuthorizationCodeTokenResponseClient();
        client.setRestOperations(rest); client.setRequestEntityConverter(grant -> redacted(converter.convert(grant)));
        try {
            return tokens(client.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                    new OAuth2AuthorizationExchange(authorization, response))), null);
        } catch (OAuth2AuthorizationException exception) { throw safe(exception); }
        catch (RestClientException exception) { throw McpOAuthException.unavailable(); }
        catch (IllegalArgumentException exception) { throw McpOAuthException.invalidResponse(); }
    }

    public McpOAuthTokens refresh(McpOAuthConfiguration config, McpOAuthCredentials credentials) {
        validateEndpoint(config.tokenEndpoint());
        var previous = credentials.tokens();
        if (previous == null || previous.refreshToken() == null) throw McpOAuthException.reconnect();
        var registration = registration(config, credentials);
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, previous.accessToken(),
                null, null, previous.grantedScopes() == null ? config.scopes() : previous.grantedScopes());
        var refresh = new OAuth2RefreshToken(previous.refreshToken(), null, previous.refreshExpiresAt());
        var converter = new OAuth2RefreshTokenGrantRequestEntityConverter();
        converter.addParametersConverter(request -> resource(config));
        var client = new DefaultRefreshTokenTokenResponseClient();
        client.setRestOperations(rest); client.setRequestEntityConverter(grant -> redacted(converter.convert(grant)));
        try {
            return tokens(client.getTokenResponse(new OAuth2RefreshTokenGrantRequest(registration, access, refresh)), previous);
        } catch (OAuth2AuthorizationException exception) { throw safe(exception); }
        catch (RestClientException exception) { throw McpOAuthException.unavailable(); }
        catch (IllegalArgumentException exception) { throw McpOAuthException.invalidResponse(); }
    }

    public void revoke(McpOAuthConfiguration config, McpOAuthCredentials credentials) {
        if (config.revocationEndpoint() == null || credentials.tokens() == null) return;
        validateEndpoint(config.revocationEndpoint());
        var params = new RedactedForm();
        boolean refresh = credentials.tokens().refreshToken() != null;
        params.add("token", refresh ? credentials.tokens().refreshToken() : credentials.tokens().accessToken());
        params.add("token_type_hint", refresh ? "refresh_token" : "access_token");
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if ("client_secret_basic".equals(config.clientAuthenticationMethod())) {
            headers.setBasicAuth(org.springframework.web.util.UriUtils.encode(config.clientId(), java.nio.charset.StandardCharsets.UTF_8),
                    org.springframework.web.util.UriUtils.encode(requiredSecret(credentials), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            params.add("client_id", config.clientId());
            if ("client_secret_post".equals(config.clientAuthenticationMethod())) params.add("client_secret", requiredSecret(credentials));
        }
        try {
            var response = rest.exchange(config.revocationEndpoint(), HttpMethod.POST, new HttpEntity<>(params, headers), Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) throw McpOAuthException.unavailable();
        } catch (OAuth2AuthorizationException exception) { throw safe(exception); }
        catch (RestClientException exception) { throw McpOAuthException.unavailable(); }
    }

    static OAuth2AccessTokenResponseHttpMessageConverter tokenConverter() {
        var converter = new OAuth2AccessTokenResponseHttpMessageConverter();
        var delegate = new DefaultMapOAuth2AccessTokenResponseConverter();
        converter.setAccessTokenResponseConverter(parameters -> {
            Object type = parameters.get("token_type");
            Object token = parameters.get("access_token");
            if (!(type instanceof String s) || !"bearer".equalsIgnoreCase(s)
                    || !(token instanceof String value) || value.isBlank()) throw invalidToken();
            var response = delegate.convert(parameters);
            var extras = new HashMap<String, Object>(response.getAdditionalParameters());
            extras.remove(RAW_EXPIRY); extras.remove(RAW_REFRESH_EXPIRY); extras.remove(SCOPE_OBSERVED);
            if (parameters.containsKey("expires_in")) extras.put(RAW_EXPIRY, seconds(parameters.get("expires_in")));
            if (parameters.containsKey("refresh_token_expires_in")) extras.put(RAW_REFRESH_EXPIRY, seconds(parameters.get("refresh_token_expires_in")));
            extras.put(SCOPE_OBSERVED, parameters.containsKey("scope"));
            return OAuth2AccessTokenResponse.withResponse(response).additionalParameters(extras).build();
        });
        return converter;
    }

    private static McpOAuthTokens tokens(OAuth2AccessTokenResponse response, McpOAuthTokens previous) {
        var observed = response.getAdditionalParameters();
        Instant received = Instant.now();
        Instant expires = observed.containsKey(RAW_EXPIRY) ? received.plusSeconds((Long) observed.get(RAW_EXPIRY)) : null;
        boolean rotated = response.getRefreshToken() != null && (previous == null
                || !response.getRefreshToken().getTokenValue().equals(previous.refreshToken()));
        String refresh = response.getRefreshToken() == null ? (previous == null ? null : previous.refreshToken())
                : response.getRefreshToken().getTokenValue();
        Instant refreshExpiry = observed.containsKey(RAW_REFRESH_EXPIRY)
                ? received.plusSeconds((Long) observed.get(RAW_REFRESH_EXPIRY))
                : (previous != null && !rotated ? previous.refreshExpiresAt() : null);
        Set<String> scopes = Boolean.TRUE.equals(observed.get(SCOPE_OBSERVED)) ? response.getAccessToken().getScopes()
                : (previous == null ? null : previous.grantedScopes());
        try { return new McpOAuthTokens(response.getAccessToken().getTokenValue(), refresh, expires, refreshExpiry, scopes); }
        catch (IllegalArgumentException exception) { throw McpOAuthException.invalidResponse(); }
    }

    private ClientRegistration registration(McpOAuthConfiguration config, McpOAuthCredentials credentials) {
        var builder = ClientRegistration.withRegistrationId("mcp").clientId(config.clientId())
                .clientAuthenticationMethod(new ClientAuthenticationMethod(config.clientAuthenticationMethod()))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(callback.toString())
                .authorizationUri(config.authorizationEndpoint().toString()).tokenUri(config.tokenEndpoint().toString()).scope(config.scopes());
        if (!"none".equals(config.clientAuthenticationMethod())) builder.clientSecret(requiredSecret(credentials));
        return builder.build();
    }

    private void validateEndpoint(URI endpoint) {
        try { endpoints.validate(endpoint); }
        catch (McpProbeException exception) {
            if (exception.reason() == McpProbeException.Reason.ENDPOINT_DENIED) throw McpOAuthException.endpointDenied();
            throw McpOAuthException.unavailable();
        }
    }
    private static String requiredSecret(McpOAuthCredentials credentials) {
        if (credentials.clientSecret() == null) throw McpOAuthException.reconnect();
        return credentials.clientSecret();
    }
    private static LinkedMultiValueMap<String, String> resource(McpOAuthConfiguration config) {
        var params = new LinkedMultiValueMap<String, String>(); params.add("resource", config.resource().toString()); return params;
    }
    private static long seconds(Object value) {
        try {
            if (!(value instanceof Number || value instanceof String)) throw invalidToken();
            long seconds = Long.parseLong(value.toString());
            if (seconds < 0) throw invalidToken();
            return seconds;
        } catch (NumberFormatException exception) { throw invalidToken(); }
    }
    private static OAuth2AuthorizationException invalidToken() {
        return new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"));
    }
    private static McpOAuthException safe(OAuth2AuthorizationException exception) {
        if (exception.getCause() instanceof ResourceAccessException) return McpOAuthException.unavailable();
        return switch (exception.getError().getErrorCode()) {
            case "invalid_grant", "invalid_client" -> McpOAuthException.reconnect();
            case "access_denied" -> McpOAuthException.denied();
            case "invalid_token_response" -> McpOAuthException.invalidResponse();
            default -> McpOAuthException.unavailable();
        };
    }

    private static RequestEntity<MultiValueMap<String, String>> redacted(RequestEntity<?> request) {
        @SuppressWarnings("unchecked") var body = (MultiValueMap<String, String>) request.getBody();
        return new RequestEntity<>(new RedactedForm(body), request.getHeaders(), request.getMethod(), request.getUrl());
    }

    /** RestTemplate DEBUG formats the form before writing it. Keep wire values, redact that representation. */
    private static final class RedactedForm extends LinkedMultiValueMap<String, String> {
        RedactedForm() { }
        RedactedForm(MultiValueMap<String, String> values) { super(values); }
        @Override public String toString() { return "[OAuth form redacted]"; }
    }
}
