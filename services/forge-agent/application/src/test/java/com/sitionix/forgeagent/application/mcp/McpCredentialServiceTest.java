package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;

class McpCredentialServiceTest {
    private final UUID owner=UUID.randomUUID(), id=UUID.randomUUID(), authorization=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-09-29T12:00:00Z");
    private final McpConnectionRepository repository=mock(McpConnectionRepository.class);
    private final McpCredentialCipher raw=mock(McpCredentialCipher.class);
    private final McpOAuthCredentialCipher cipher=mock(McpOAuthCredentialCipher.class);
    private final McpOAuthClient client=mock(McpOAuthClient.class);
    private final McpRuntimeGrantRepository grants=mock(McpRuntimeGrantRepository.class);
    private final McpRuntimeToolView views=mock(McpRuntimeToolView.class);
    private final Map<McpEncryptedCredential,McpOAuthCredentials> envelopes=new HashMap<>();
    private final McpOAuthConfiguration config=new McpOAuthConfiguration(URI.create("https://oauth.example"),URI.create("https://oauth.example/authorize"),
            URI.create("https://oauth.example/token"),null,"client","none",Set.of("tools"),URI.create("https://mcp.example/mcp"));
    private McpConnectionState state;
    private McpCredentialService service;
    @BeforeEach void setup() {
        when(cipher.encrypt(eq(owner),eq(id),any())).thenAnswer(i->{var e=new McpEncryptedCredential(UUID.randomUUID().toString(),new byte[]{1});envelopes.put(e,i.getArgument(2));return e;});
        when(cipher.decrypt(eq(owner),eq(id),any())).thenAnswer(i->envelopes.get(i.getArgument(2)));
        when(repository.change(eq(owner),eq(id),any())).thenAnswer(i->{UnaryOperator<McpConnectionState> update=i.getArgument(2);state=update.apply(state);return Optional.of(state);});
        service=new McpCredentialService(repository,()->owner,raw,cipher,client,grants,views,Clock.fixed(now,ZoneOffset.UTC));
        tokens(new McpOAuthTokens("access-canary","refresh-canary",now.minusSeconds(1),null,Set.of("tools")));
        when(client.refresh(any(),any())).thenReturn(new McpOAuthTokens("rotated-canary","rotated-refresh-canary",now.plusSeconds(60),null,Set.of("tools")));
    }
    @Test void twoSequentialCallsRefreshOnlyOnceAndKeepLogicalIdentity() {
        var observed=state.connection(); var previous=state.credential();
        assertThat(new String(service.resolve(observed),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("rotated-canary");
        service.resolve(observed);
        verify(client,times(1)).refresh(eq(config),any());
        assertThat(state.credential()).isNotEqualTo(previous);
        assertThat(state.connection().oauthAuthorizationId()).isEqualTo(authorization);
        verifyNoInteractions(grants,views);
    }
    @Test void missingExpiryDoesNotTriggerInventedRefresh() {
        tokens(new McpOAuthTokens("unknown-expiry",null,null,null,null));
        assertThat(service.resolve(state.connection())).isEqualTo("unknown-expiry".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verifyNoInteractions(client);
    }
    @Test void expiredWithoutRefreshRequiresReconnectAndRevokesAccess() {
        tokens(new McpOAuthTokens("expired",null,now.minusSeconds(1),null,null));
        assertThatThrownBy(()->service.resolve(state.connection())).isInstanceOf(McpOAuthException.class);
        assertThat(state.connection().credentialConfigured()).isFalse();
        verify(grants).revokeConnection(id); verify(views).revokeConnection(id); verifyNoInteractions(client);
    }
    @Test void invalidGrantMakesConnectionUnusableWithoutRetry() {
        when(client.refresh(any(),any())).thenThrow(McpOAuthException.reconnect());
        assertThatThrownBy(()->service.resolve(state.connection())).isInstanceOf(McpOAuthException.class);
        assertThat(state.connection().credentialConfigured()).isFalse();
        verify(client,times(1)).refresh(any(),any());
    }
    @Test void scopeChangeRequiresConsent() {
        when(client.refresh(any(),any())).thenReturn(new McpOAuthTokens("access","refresh",now.plusSeconds(60),null,Set.of("extra","tools")));
        assertThatThrownBy(()->service.resolve(state.connection())).isInstanceOf(McpOAuthException.class);
        assertThat(state.connection().credentialConfigured()).isFalse();
    }
    @Test void disabledAfterAdmissionDoesNotRefresh() {
        var observed=state.connection(); var c=observed;
        state=new McpConnectionState(new McpConnection(id,owner,c.displayName(),c.endpoint(),c.authType(),false,c.projectAccess(),c.allowedTools(),true,
                c.createdAt(),c.updatedAt(),c.checkedAt(),c.safeDiagnostic(),config,authorization),state.credential());
        assertThatThrownBy(()->service.resolve(observed)).isInstanceOf(McpOAuthException.class);
        verifyNoInteractions(client);
    }
    @Test void providerUnavailableDoesNotDestroyExistingAuthorization() {
        when(client.refresh(any(),any())).thenThrow(McpOAuthException.unavailable());
        assertThatThrownBy(()->service.resolve(state.connection())).isInstanceOf(McpOAuthException.class);
        assertThat(state.connection().credentialConfigured()).isTrue(); verifyNoInteractions(grants,views);
    }
    private void tokens(McpOAuthTokens tokens) {
        var c=new McpConnection(id,owner,"OAuth",config.resource(),McpAuthType.OAUTH,true,McpProjectAccess.all(),Set.of(),true,
                now,now,null,null,config,authorization);
        state=new McpConnectionState(c,cipher.encrypt(owner,id,new McpOAuthCredentials(null,tokens)));
    }
}
