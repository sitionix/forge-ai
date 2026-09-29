package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;

class McpOAuthServiceTest {
    private final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-29T12:00:00Z");
    private final URI callback = URI.create("http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback");
    private final String browser = "synthetic-browser-binding-32-characters";
    private final McpConnectionRepository connections = mock(McpConnectionRepository.class);
    private final McpOAuthTransactionRepository transactions = mock(McpOAuthTransactionRepository.class);
    private final McpCredentialCipher rawCipher = mock(McpCredentialCipher.class);
    private final McpOAuthCredentialCipher cipher = mock(McpOAuthCredentialCipher.class);
    private final McpOAuthClient client = mock(McpOAuthClient.class);
    private final McpGatewayService gateway = mock(McpGatewayService.class);
    private final Map<UUID, McpConnectionState> states = Collections.synchronizedMap(new HashMap<>());
    private final Map<UUID, McpOAuthTransaction> attempts = Collections.synchronizedMap(new HashMap<>());
    private final Map<McpEncryptedCredential, McpOAuthCredentials> credentials = Collections.synchronizedMap(new HashMap<>());
    private final McpOAuthConfiguration config = new McpOAuthConfiguration(URI.create("https://oauth.example"), URI.create("https://oauth.example/authorize"),
            URI.create("https://oauth.example/token"), null, "client", "client_secret_post", Set.of("tools"), URI.create("https://mcp.example/mcp"));
    private McpOAuthService service;

    @BeforeEach void setup() {
        when(cipher.encrypt(eq(owner), eq(id), any())).thenAnswer(invocation -> {
            var encrypted = new McpEncryptedCredential(UUID.randomUUID().toString(), new byte[]{1});
            credentials.put(encrypted, invocation.getArgument(2)); return encrypted;
        });
        when(cipher.decrypt(eq(owner), eq(id), any())).thenAnswer(invocation -> credentials.get(invocation.getArgument(2)));
        when(rawCipher.encrypt(eq(owner), any(), eq("oauth-verifier"), any())).thenReturn(new McpEncryptedCredential("fixture", new byte[]{2}));
        when(rawCipher.decrypt(eq(owner), any(), eq("oauth-verifier"), any())).thenAnswer(invocation -> "verifier-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(connections.findById(owner, id)).thenAnswer(invocation -> Optional.ofNullable(states.get(id)).map(McpConnectionState::connection));
        when(connections.credential(owner, id)).thenAnswer(invocation -> Optional.ofNullable(states.get(id)).map(McpConnectionState::credential));
        when(connections.change(eq(owner), eq(id), any())).thenAnswer(invocation -> {
            synchronized (states) {
                var current = states.get(id); if (current == null) return Optional.empty();
                UnaryOperator<McpConnectionState> change = invocation.getArgument(2);
                var next = change.apply(current); if (next == null) states.remove(id); else states.put(id, next);
                return Optional.ofNullable(next);
            }
        });
        doAnswer(invocation -> { var transaction = (McpOAuthTransaction) invocation.getArgument(0); attempts.clear(); attempts.put(transaction.id(), transaction); return null; })
                .when(transactions).insert(any());
        when(transactions.claim(eq(owner), any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            synchronized (attempts) {
                var transaction = attempts.get(invocation.getArgument(1));
                Instant at = invocation.getArgument(4);
                if (transaction == null || transaction.claimedAt() != null || !transaction.stateHash().equals(invocation.getArgument(2))
                        || !transaction.browserHash().equals(invocation.getArgument(3)) || !transaction.expiresAt().isAfter(at)) return Optional.empty();
                var claimed = new McpOAuthTransaction(transaction.id(), owner, id, transaction.stateHash(), transaction.browserHash(),
                        transaction.snapshot(), transaction.verifier(), transaction.expiresAt(), at);
                attempts.put(claimed.id(), claimed); return Optional.of(claimed);
            }
        });
        when(transactions.find(eq(owner), eq(id), any())).thenAnswer(invocation -> Optional.ofNullable(attempts.get(invocation.getArgument(2))));
        doAnswer(invocation -> { attempts.remove(invocation.getArgument(2)); return null; }).when(transactions).delete(eq(owner), eq(id), any());
        when(client.authorization(any(), eq(callback), anyString())).thenAnswer(invocation -> new McpOAuthAuthorization(
                URI.create("https://oauth.example/authorize?state=" + invocation.getArgument(2)), "verifier-canary"));
        when(client.exchange(any(), any(), anyString(), anyString())).thenReturn(new McpOAuthTokens("access-canary", "refresh-canary", now.plusSeconds(3600), null, Set.of("tools")));
        var connection = new McpConnection(id, owner, "Example", config.resource(), McpAuthType.OAUTH, false, McpProjectAccess.selected(Set.of()),
                Set.of(), false, now, now, null, null, config, null);
        states.put(id, new McpConnectionState(connection, cipher.encrypt(owner, id, new McpOAuthCredentials("client-canary", null))));
        service = service(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void callbackPersistsOnlyUsableEncryptedToken() {
        var start = service.start(id, browser);
        assertThat(states.get(id).connection().credentialConfigured()).isFalse();
        assertThat(service.complete(response(start, browser, "code", null, config.issuer().toString())).connectionId()).isEqualTo(id);
        var saved = states.get(id);
        assertThat(saved.connection().credentialConfigured()).isTrue(); assertThat(saved.connection().enabled()).isFalse();
        assertThat(saved.connection().allowedTools()).isEmpty();
        assertThat(cipher.decrypt(owner, id, saved.credential()).tokens().accessToken()).isEqualTo("access-canary");
        assertThat(attempts).isEmpty();
        assertThat(saved.connection().toString()).doesNotContain("canary");
    }

    @Test void connectRequiresDisabledConfiguredConnection() {
        var c = states.get(id).connection();
        states.put(id, new McpConnectionState(copy(c, true, c.displayName()), states.get(id).credential()));
        assertThatThrownBy(() -> service.start(id, browser)).isInstanceOf(McpOAuthException.class);
        assertThat(states.get(id).connection().enabled()).isTrue();
        verifyNoInteractions(client, transactions, gateway);
    }

    @Test void denialDoesNotExchange() {
        var start = service.start(id, browser);
        assertThatThrownBy(() -> service.complete(response(start, browser, null, "access_denied", null))).isInstanceOf(McpOAuthException.class);
        verify(client, never()).exchange(any(), any(), anyString(), anyString());
        assertThat(states.get(id).connection().credentialConfigured()).isFalse(); assertThat(attempts).isEmpty();
    }

    @Test void wrongBrowserDoesNotConsumeLegitimateState() {
        var start = service.start(id, browser);
        assertThatThrownBy(() -> service.complete(response(start, "wrong-browser", "code", null, null))).isInstanceOf(McpOAuthException.class);
        verify(client, never()).exchange(any(), any(), anyString(), anyString());
        assertThat(service.complete(response(start, browser, "code", null, null)).connectionId()).isEqualTo(id);
    }

    @Test void wrongIssuerIsRejectedBeforeExchange() {
        var start = service.start(id, browser);
        assertThatThrownBy(() -> service.complete(response(start, browser, "code", null, "https://wrong.example"))).isInstanceOf(McpOAuthException.class);
        verify(client, never()).exchange(any(), any(), anyString(), anyString());
    }

    @Test void changedConnectionIsRejectedBeforeExchange() {
        var start = service.start(id, browser); var state = states.get(id);
        states.put(id, new McpConnectionState(copy(state.connection(), false, "Changed"), state.credential()));
        assertThatThrownBy(() -> service.complete(response(start, browser, "code", null, null))).isInstanceOf(McpOAuthException.class);
        verify(client, never()).exchange(any(), any(), anyString(), anyString());
    }

    @Test void expiredAndRepeatedCallbacksDoNotExchange() {
        var expired = service.start(id, browser);
        assertThatThrownBy(() -> service(Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC)).complete(response(expired, browser, "code", null, null)))
                .isInstanceOf(McpOAuthException.class);
        verify(client, never()).exchange(any(), any(), anyString(), anyString());
        var valid = service.start(id, browser); service.complete(response(valid, browser, "code", null, null));
        assertThatThrownBy(() -> service.complete(response(valid, browser, "code", null, null))).isInstanceOf(McpOAuthException.class);
        verify(client, times(1)).exchange(any(), any(), anyString(), anyString());
    }

    @Test void lateExchangeAfterCancelCannotPersist() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.exchange(any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return new McpOAuthTokens("late-access-canary", null, null, null, null);
        });
        var start = service.start(id, browser);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = executor.submit(() -> service.complete(response(start, browser, "code", null, null)));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            service.cancel(id, start.transactionId(), browser); release.countDown();
            assertThatThrownBy(() -> completion.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(McpOAuthException.class);
            assertThat(states.get(id).connection().credentialConfigured()).isFalse();
            assertThat(states.get(id).connection().enabled()).isFalse();
        } finally { release.countDown(); }
    }

    @Test void reconnectInvalidatesOlderTransactionWithoutEnabling() {
        var old = service.start(id, browser); var previousId = states.get(id).connection().oauthAuthorizationId();
        var replacement = service.start(id, browser);
        assertThat(states.get(id).connection().oauthAuthorizationId()).isNotEqualTo(previousId);
        assertThatThrownBy(() -> service.complete(response(old, browser, "code", null, null))).isInstanceOf(McpOAuthException.class);
        service.complete(response(replacement, browser, "code", null, null));
        assertThat(states.get(id).connection().enabled()).isFalse();
    }

    @Test void malformedStateDoesNotExchangeOrReadSecrets() {
        assertThatThrownBy(() -> service.complete(new McpOAuthCallback("state-canary", browser, "code-canary", null, null)))
                .isInstanceOf(McpOAuthException.class).hasMessageNotContaining("canary");
        verifyNoInteractions(client, transactions);
    }

    @Test void cancellationBeforeConsentPreventsExchange() {
        var start = service.start(id,browser);
        service.cancel(id,start.transactionId(),browser);
        assertThatThrownBy(() -> service.complete(response(start,browser,"code",null,null))).isInstanceOf(McpOAuthException.class);
        verify(client,never()).exchange(any(),any(),anyString(),anyString());
        assertThat(states.get(id).connection().enabled()).isFalse();
    }

    @Test void wrongBrowserCannotCancelLegitimateAttempt() {
        var start = service.start(id,browser);
        assertThatThrownBy(() -> service.cancel(id,start.transactionId(),"wrong-browser-binding-32-characters")).isInstanceOf(McpOAuthException.class);
        assertThat(service.complete(response(start,browser,"code",null,null)).connectionId()).isEqualTo(id);
    }

    @Test void reconnectClearsPreviouslyConfirmedApprovals() {
        var c = states.get(id).connection();
        var authorized = new McpConnection(id,owner,c.displayName(),c.endpoint(),c.authType(),false,c.projectAccess(),
                Set.of(new McpAllowedTool("search","a".repeat(64))),true,c.createdAt(),c.updatedAt(),now,"OK",config,UUID.randomUUID());
        states.put(id,new McpConnectionState(authorized,cipher.encrypt(owner,id,new McpOAuthCredentials("client-canary",
                new McpOAuthTokens("old-access-canary",null,null,null,null)))));
        service.start(id,browser);
        assertThat(states.get(id).connection().allowedTools()).isEmpty();
        assertThat(states.get(id).connection().credentialConfigured()).isFalse();
        assertThat(states.get(id).connection().checkedAt()).isNull();
        verify(gateway).revokeConnection(id);
    }

    private McpOAuthService service(Clock clock) { return new McpOAuthService(connections, transactions, () -> owner, client, rawCipher, cipher,
            gateway, callback, Duration.ofMinutes(10), clock); }
    private McpOAuthCallback response(McpOAuthStart start, String binding, String code, String error, String issuer) {
        String state = start.authorizationUrl().getRawQuery().substring("state=".length());
        return new McpOAuthCallback(state, binding, code, error, issuer);
    }
    private McpConnection copy(McpConnection c, boolean enabled, String name) {
        return new McpConnection(c.id(), owner, name, c.endpoint(), c.authType(), enabled, c.projectAccess(), c.allowedTools(), c.credentialConfigured(),
                c.createdAt(), c.updatedAt(), c.checkedAt(), c.safeDiagnostic(), c.oauthConfiguration(), c.oauthAuthorizationId());
    }
}
