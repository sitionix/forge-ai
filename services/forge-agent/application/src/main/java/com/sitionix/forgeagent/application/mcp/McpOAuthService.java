package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;

/** Browser authorization only; protocol calls and runtime policy keep their existing owners. */
public class McpOAuthService {
    private final McpConnectionRepository connections;
    private final McpOAuthTransactionRepository transactions;
    private final ForgeInstanceIdentityRepository identity;
    private final McpOAuthClient client;
    private final McpCredentialCipher rawCipher;
    private final McpOAuthCredentialCipher cipher;
    private final McpGatewayService gateway;
    private final URI callback;
    private final Duration ttl;
    private final Clock clock;
    private final Base64StringKeyGenerator nonce = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    public McpOAuthService(McpConnectionRepository connections, McpOAuthTransactionRepository transactions,
            ForgeInstanceIdentityRepository identity, McpOAuthClient client, McpCredentialCipher rawCipher,
            McpOAuthCredentialCipher cipher, McpGatewayService gateway, URI callback, Duration ttl, Clock clock) {
        this.connections = connections; this.transactions = transactions; this.identity = identity; this.client = client;
        this.rawCipher = rawCipher; this.cipher = cipher; this.gateway = gateway; this.callback = callback; this.ttl = ttl; this.clock = clock;
    }

    public McpOAuthStart start(UUID id, String browserBinding) {
        validateBinding(browserBinding);
        UUID owner = identity.getOrCreate(), transactionId = UUID.randomUUID();
        String state = transactionId + "." + nonce.generateKey();
        var initial = connections.findById(owner, id).orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
        if (initial.enabled() || initial.authType() != McpAuthType.OAUTH) throw McpOAuthException.invalidTransaction();
        var authorization = client.authorization(initial.oauthConfiguration(), callback, state);
        connections.change(owner, id, current -> {
            var connection = current.connection();
            if (!connection.equals(initial) || connection.enabled() || current.credential() == null)
                throw McpOAuthException.invalidTransaction();
            var credentials = cipher.decrypt(owner, id, current.credential());
            if (!"none".equals(connection.oauthConfiguration().clientAuthenticationMethod()) && credentials.clientSecret() == null)
                throw McpOAuthException.reconnect();
            var pending = new McpConnection(id, owner, connection.displayName(), connection.endpoint(), connection.authType(), false,
                    connection.projectAccess(), Set.of(), false, connection.createdAt(), clock.instant().truncatedTo(ChronoUnit.MICROS), null, null,
                    connection.oauthConfiguration(), UUID.randomUUID());
            byte[] verifier = authorization.verifier().getBytes(StandardCharsets.US_ASCII);
            try {
                transactions.insert(new McpOAuthTransaction(transactionId, owner, id, hash(state), hash(browserBinding), pending,
                        rawCipher.encrypt(owner, transactionId, "oauth-verifier", verifier), clock.instant().plus(ttl), null));
            } finally { Arrays.fill(verifier, (byte) 0); }
            return new McpConnectionState(pending, cipher.encrypt(owner, id, new McpOAuthCredentials(credentials.clientSecret(), null)));
        }).orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
        gateway.revokeConnection(id);
        return new McpOAuthStart(transactionId, id, authorization.authorizationUrl());
    }

    public McpOAuthCompletion complete(McpOAuthCallback response) {
        if (response == null || (blank(response.code()) == blank(response.error()))) throw McpOAuthException.invalidTransaction();
        UUID transactionId = transactionId(response.state());
        validateBinding(response.browserBinding());
        UUID owner = identity.getOrCreate();
        var transaction = transactions.claim(owner, transactionId, hash(response.state()), hash(response.browserBinding()), clock.instant())
                .orElseThrow(McpOAuthException::invalidTransaction);
        UUID id = transaction.connectionId();
        try {
            if (response.issuer() != null && !transaction.snapshot().oauthConfiguration().issuer().toString().equals(response.issuer()))
                throw McpOAuthException.invalidTransaction();
            if (!blank(response.error())) throw McpOAuthException.denied();
            var current = connections.change(owner, id, state -> { requireCurrent(state.connection(), transaction); return state; })
                    .orElseThrow(McpOAuthException::invalidTransaction);
            var credentials = cipher.decrypt(owner, id, current.credential());
            byte[] verifier = rawCipher.decrypt(owner, transactionId, "oauth-verifier", transaction.verifier());
            McpOAuthTokens tokens;
            try { tokens = client.exchange(transaction.snapshot().oauthConfiguration(), credentials, response.code(), new String(verifier, StandardCharsets.US_ASCII)); }
            finally { Arrays.fill(verifier, (byte) 0); }
            connections.change(owner, id, state -> {
                requireCurrent(state.connection(), transaction);
                var claimed = transactions.find(owner, id, transactionId).filter(t -> t.claimedAt() != null && t.expiresAt().isAfter(clock.instant()))
                        .orElseThrow(McpOAuthException::invalidTransaction);
                if (!claimed.id().equals(transaction.id())) throw McpOAuthException.invalidTransaction();
                var c = state.connection();
                var authorized = new McpConnection(id, owner, c.displayName(), c.endpoint(), c.authType(), false, c.projectAccess(), Set.of(), true,
                        c.createdAt(), clock.instant().truncatedTo(ChronoUnit.MICROS), null, null, c.oauthConfiguration(), c.oauthAuthorizationId());
                transactions.delete(owner, id, transactionId);
                return new McpConnectionState(authorized, cipher.encrypt(owner, id, new McpOAuthCredentials(credentials.clientSecret(), tokens)));
            }).orElseThrow(McpOAuthException::invalidTransaction);
            return new McpOAuthCompletion(id);
        } finally { transactions.delete(owner, id, transactionId); }
    }

    public void cancel(UUID id, UUID transactionId, String browserBinding) {
        validateBinding(browserBinding);
        UUID owner = identity.getOrCreate();
        connections.change(owner, id, state -> {
            var transaction = transactions.find(owner, id, transactionId);
            if (transaction.isEmpty()) return state;
            if (!transaction.get().browserHash().equals(hash(browserBinding))) throw McpOAuthException.invalidTransaction();
            transactions.delete(owner, id, transactionId); return state;
        });
    }

    public static UUID transactionId(String state) {
        try {
            if (state == null || state.indexOf('.') < 1 || state.endsWith(".")) throw McpOAuthException.invalidTransaction();
            return UUID.fromString(state.substring(0, state.indexOf('.')));
        } catch (IllegalArgumentException exception) { throw McpOAuthException.invalidTransaction(); }
    }
    private void requireCurrent(McpConnection current, McpOAuthTransaction transaction) {
        if (!current.equals(transaction.snapshot()) || current.enabled() || !transaction.expiresAt().isAfter(clock.instant()))
            throw McpOAuthException.invalidTransaction();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validateBinding(String binding) { if (binding == null || binding.length() < 32) throw McpOAuthException.invalidTransaction(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("OAuth digest unavailable"); }
    }
}
