package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.*;
import java.util.*;
import java.util.function.UnaryOperator;

public interface McpConnectionRepository {
    /** Global storage signal for fail-closed downgrade; never reads credential bytes. */
    boolean hasRetainedCredentials();
    Optional<McpConnection> findById(UUID installationId, UUID id);
    List<McpConnection> findAll(UUID installationId);
    Optional<McpEncryptedCredential> credential(UUID installationId, UUID id);
    /** Inserts a new connection; never updates an existing identity. */
    void insert(McpConnectionState state);
    /** Locks the owner-scoped row, reads one state, then writes it atomically. Null result deletes it. */
    Optional<McpConnectionState> change(UUID installationId, UUID id, UnaryOperator<McpConnectionState> mutation);
    void delete(UUID installationId, UUID id);
}
