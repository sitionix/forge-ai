package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;

/** Serializes a local provider request write against recovery ownership transfer, without a DB transaction. */
public interface AgentExecutionDispatchGuard {
    void dispatch(AgentSessionExecutionClaim claim, Runnable writeRequest);
}
