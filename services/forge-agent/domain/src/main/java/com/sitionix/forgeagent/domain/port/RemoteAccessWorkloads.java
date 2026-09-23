package com.sitionix.forgeagent.domain.port;

import java.util.UUID;

/** Narrow trusted supervisor operations. None are authorized by peer-provided session IDs. */
public interface RemoteAccessWorkloads {
    void reconcile(UUID authorityEpoch);
    void heartbeat(UUID authorityEpoch);
    void start(UUID sessionId, UUID attachmentId, UUID authorityEpoch);
    void stop(UUID sessionId);
}
