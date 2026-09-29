package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import com.sitionix.forgeagent.it.infra.AgentManagementFixture;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class McpOAuthPersistenceIT extends AgentManagementFixture {
    @Autowired com.sitionix.forgeagent.it.infra.ForgeAgentTestManager forgeIt;
    @Autowired McpConnectionRepository connections;
    @Autowired McpOAuthTransactionRepository transactions;
    @Autowired McpOAuthCredentialCipher cipher;
    @Autowired McpCredentialCipher rawCipher;
    @Autowired McpToolInventoryRepository inventory;
    @Autowired ForgeInstanceIdentityRepository identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test void setupSecretIsEncryptedButConnectionNotUsable() {
        var connection = connection();
        var encrypted = cipher.encrypt(connection.installationId(), connection.id(), new McpOAuthCredentials("setup-canary", null));
        connections.insert(new McpConnectionState(connection, encrypted));
        try {
            var restored = connections.findById(connection.installationId(), connection.id()).orElseThrow();
            assertThat(restored.oauthConfiguration()).isEqualTo(connection.oauthConfiguration());
            assertThat(restored.enabled()).isFalse();
            assertThat(restored.credentialConfigured()).isFalse();
            assertThat(cipher.decrypt(connection.installationId(), connection.id(), connections.credential(connection.installationId(), connection.id()).orElseThrow()).clientSecret())
                    .isEqualTo("setup-canary");
            assertThat(jdbc.queryForObject("SELECT oauth_configuration::text FROM mcp_connections WHERE id=?", String.class, connection.id())).doesNotContain("setup-canary");
            assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).isEmpty();
        } finally { connections.delete(connection.installationId(), connection.id()); }
    }

    @Test void wrongBindingDoesNotConsumeLegitimateState() {
        var transaction = transaction();
        try {
            assertThat(transactions.claim(UUID.randomUUID(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now())).isEmpty();
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), "c".repeat(64), transaction.browserHash(), Instant.now())).isEmpty();
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), "c".repeat(64), Instant.now())).isEmpty();
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now())).isPresent();
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now())).isEmpty();
        } finally { connections.delete(transaction.installationId(), transaction.connectionId()); }
    }

    @Test void stateClaimIsSingleUseAndOwnerScoped() throws Exception {
        var transaction = transaction(); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> claim = () -> { ready.countDown(); assertThat(start.await(3, TimeUnit.SECONDS)).isTrue();
                return transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now()).isPresent(); };
            var first = executor.submit(claim); var second = executor.submit(claim);
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { start.countDown(); connections.delete(transaction.installationId(), transaction.connectionId()); }
    }

    @Test void cancelDeletesClaimedTransaction() {
        var transaction = transaction();
        try {
            transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now()).orElseThrow();
            transactions.delete(UUID.randomUUID(), transaction.connectionId(), transaction.id());
            assertThat(transactions.findClaimed(transaction.installationId(), transaction.connectionId(), transaction.id())).isPresent();
            transactions.delete(transaction.installationId(), transaction.connectionId(), transaction.id());
            assertThat(transactions.findClaimed(transaction.installationId(), transaction.connectionId(), transaction.id())).isEmpty();
        } finally { connections.delete(transaction.installationId(), transaction.connectionId()); }
    }

    @Test void deletedConnectionCascadesTransaction() {
        var transaction = transaction();
        connections.delete(transaction.installationId(), transaction.connectionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_transactions WHERE id=?", Integer.class, transaction.id())).isZero();
        assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now())).isEmpty();
    }

    @Test void expiredOrReplacedTransactionCannotBeClaimed() {
        var transaction = transaction();
        try {
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), transaction.expiresAt().plusSeconds(1))).isEmpty();
            var replacement = new McpOAuthTransaction(UUID.randomUUID(), transaction.installationId(), transaction.connectionId(), "d".repeat(64),
                    transaction.browserHash(), transaction.snapshot(), transaction.verifier(), Instant.now().plusSeconds(600), null);
            transactions.insert(replacement);
            assertThat(transactions.claim(transaction.installationId(), transaction.id(), transaction.stateHash(), transaction.browserHash(), Instant.now())).isEmpty();
            assertThat(transactions.claim(replacement.installationId(), replacement.id(), replacement.stateHash(), replacement.browserHash(), Instant.now())).isPresent();
        } finally { connections.delete(transaction.installationId(), transaction.connectionId()); }
    }

    @Test void refreshKeepsAuthorizationIdentityAndInventory() {
        var setup = connection(); var generation = UUID.randomUUID();
        var connection = new McpConnection(setup.id(), setup.installationId(), setup.displayName(), setup.endpoint(), setup.authType(), false,
                setup.projectAccess(), Set.of(), true, setup.createdAt(), setup.updatedAt(), null, null, setup.oauthConfiguration(), generation);
        var original = cipher.encrypt(connection.installationId(), connection.id(), new McpOAuthCredentials("setup-canary",
                new McpOAuthTokens("old-access", "old-refresh", Instant.now().plusSeconds(600), null, null)));
        connections.insert(new McpConnectionState(connection, original));
        try {
            connections.change(connection.installationId(), connection.id(), state -> new McpConnectionState(state.connection(),
                    cipher.encrypt(connection.installationId(), connection.id(), new McpOAuthCredentials("setup-canary",
                            new McpOAuthTokens("rotated-access", "rotated-refresh", Instant.now().plusSeconds(600), null, null)))));
            assertThat(connections.credential(connection.installationId(), connection.id()).orElseThrow()).isNotEqualTo(original);
            var tool = new McpToolSummary("read", "Read", "sha256:" + "e".repeat(64));
            inventory.replace(connection.installationId(), connection.id(), connection.endpoint(), McpAuthType.OAUTH, original, List.of(tool), generation);
            assertThat(inventory.list(connection.installationId(), connection.id())).containsExactly(tool);
            assertThat(connections.findById(connection.installationId(), connection.id()).orElseThrow().oauthAuthorizationId()).isEqualTo(generation);
            assertThatThrownBy(() -> inventory.replace(connection.installationId(), connection.id(), connection.endpoint(), McpAuthType.OAUTH,
                    original, List.of(tool), UUID.randomUUID())).hasRootCauseInstanceOf(IllegalStateException.class);
        } finally { connections.delete(connection.installationId(), connection.id()); }
    }

    private McpOAuthTransaction transaction() {
        var connection = connection();
        connections.insert(new McpConnectionState(connection, cipher.encrypt(connection.installationId(), connection.id(), new McpOAuthCredentials("setup-canary", null))));
        connection = connections.findById(connection.installationId(), connection.id()).orElseThrow();
        var id = UUID.randomUUID();
        var encryptedVerifier = rawCipher.encrypt(connection.installationId(), id, "oauth-verifier", "synthetic-verifier".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var transaction = new McpOAuthTransaction(id, connection.installationId(), connection.id(), "a".repeat(64), "b".repeat(64), connection,
                encryptedVerifier, Instant.now().plusSeconds(600), null);
        transactions.insert(transaction); return transaction;
    }
    private McpConnection connection() {
        var now = Instant.now();
        var config = new McpOAuthConfiguration(URI.create("https://oauth.example"), URI.create("https://oauth.example/authorize"),
                URI.create("https://oauth.example/token"), null, "fixture-client", "client_secret_post", Set.of("tools"), URI.create("https://mcp.example/mcp"));
        return new McpConnection(UUID.randomUUID(), identity.getOrCreate(), "OAuth fixture", config.resource(), McpAuthType.OAUTH,
                false, new McpProjectAccess(McpProjectAccess.Scope.SELECTED, Set.of()), Set.of(), false, now, now, null, null, config, null);
    }
}
