package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentExecutionEventAppendResult;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventPage;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionEventRepository {
    boolean activate(AgentSessionExecutionClaim claim);

    AgentExecutionEventAppendResult append(AgentSessionExecutionClaim claim, AgentExecutionEventCandidate candidate);

    boolean markComplete(AgentSessionExecutionClaim claim);

    boolean markDegraded(AgentSessionExecutionClaim claim);

    Optional<AgentExecutionEventPage> findPage(UUID turnId, long afterSequence, int limit);
}
