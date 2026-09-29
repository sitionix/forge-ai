package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.application.mcp.McpCredentialService;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.it.infra.AgentManagementFixture;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class McpOAuthRefreshIT extends AgentManagementFixture {
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired McpConnectionRepository connections;
    @Autowired McpOAuthCredentialCipher cipher;
    @Autowired ForgeInstanceIdentityRepository identity;
    @Autowired McpCredentialService credentials;
    @Autowired JdbcTemplate jdbc;
    @MockBean McpOAuthClient provider;

    @Test void twoConcurrentCallsRefreshOnlyOnceUsingRealRowLock() throws Exception {
        var c=connection();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        blockRefresh(entered,release);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->credentials.resolve(c));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->credentials.resolve(c));awaitConnectionLock();release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo("rotated-access".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(second.get(5,TimeUnit.SECONDS)).isEqualTo("rotated-access".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            verify(provider,times(1)).refresh(eq(c.oauthConfiguration()),any());
            assertThat(connections.findById(c.installationId(),c.id()).orElseThrow().oauthAuthorizationId()).isEqualTo(c.oauthAuthorizationId());
        } finally {release.countDown();connections.delete(c.installationId(),c.id());}
    }
    @Test void deleteWaitsForRefreshThenRemovesNewestCredential() throws Exception {
        var c=connection();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);blockRefresh(entered,release);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh=executor.submit(()->credentials.resolve(c));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=executor.submit(()->connections.delete(c.installationId(),c.id()));awaitConnectionLock();release.countDown();
            refresh.get(5,TimeUnit.SECONDS);var removed=deletion.get(5,TimeUnit.SECONDS).orElseThrow();
            assertThat(cipher.decrypt(c.installationId(),c.id(),removed.credential()).tokens().refreshToken()).isEqualTo("rotated-refresh");
            assertThat(connections.findById(c.installationId(),c.id())).isEmpty();
            assertThatThrownBy(()->credentials.resolve(c)).isInstanceOf(com.sitionix.forgeagent.domain.exception.McpOAuthException.class);
        } finally {release.countDown();connections.delete(c.installationId(),c.id());}
    }
    @Test void reconnectAfterRefreshCannotBeOverwrittenByOldGeneration() throws Exception {
        var c=connection();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);blockRefresh(entered,release);
        UUID generation=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh=executor.submit(()->credentials.resolve(c));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var reconnect=executor.submit(()->connections.change(c.installationId(),c.id(),state->{
                var old=state.connection();var pending=new McpConnection(old.id(),old.installationId(),old.displayName(),old.endpoint(),old.authType(),false,
                        old.projectAccess(),Set.of(),false,old.createdAt(),Instant.now(),null,null,old.oauthConfiguration(),generation);
                return new McpConnectionState(pending,cipher.encrypt(old.installationId(),old.id(),new McpOAuthCredentials("setup",null)));
            }));
            awaitConnectionLock();release.countDown();refresh.get(5,TimeUnit.SECONDS);reconnect.get(5,TimeUnit.SECONDS);
            var current=connections.findById(c.installationId(),c.id()).orElseThrow();
            assertThat(current.oauthAuthorizationId()).isEqualTo(generation);assertThat(current.credentialConfigured()).isFalse();
            assertThatThrownBy(()->credentials.resolve(c)).isInstanceOf(com.sitionix.forgeagent.domain.exception.McpOAuthException.class);
        } finally {release.countDown();connections.delete(c.installationId(),c.id());}
    }
    private void blockRefresh(CountDownLatch entered,CountDownLatch release) {
        when(provider.refresh(any(),any())).thenAnswer(invocation->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            return new McpOAuthTokens("rotated-access","rotated-refresh",Instant.now().plusSeconds(600),null,Set.of("tools"));});
    }
    private void awaitConnectionLock() {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).untilAsserted(()->assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM mcp_connections%'",Integer.class)).isPositive());
    }
    private McpConnection connection() {
        var now=Instant.now();var config=new McpOAuthConfiguration(URI.create("https://oauth.example"),URI.create("https://oauth.example/auth"),
                URI.create("https://oauth.example/token"),null,"client","none",Set.of("tools"),URI.create("https://mcp.example/mcp"));
        var c=new McpConnection(UUID.randomUUID(),identity.getOrCreate(),"OAuth",config.resource(),McpAuthType.OAUTH,false,McpProjectAccess.all(),Set.of(),true,
                now,now,null,null,config,UUID.randomUUID());
        connections.insert(new McpConnectionState(c,cipher.encrypt(c.installationId(),c.id(),new McpOAuthCredentials("setup",
                new McpOAuthTokens("expired-access","old-refresh",now.minusSeconds(1),null,Set.of("tools"))))));return c;
    }
}
