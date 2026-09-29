package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpOAuthTransaction;
import java.time.Instant;
import java.util.*;

public interface McpOAuthTransactionRepository {
    void insert(McpOAuthTransaction transaction);
    Optional<McpOAuthTransaction> claim(UUID owner, UUID transaction, String stateHash, String browserHash, Instant now);
    Optional<McpOAuthTransaction> findClaimed(UUID owner, UUID connection, UUID transaction);
    void delete(UUID owner, UUID connection, UUID transaction);
}
