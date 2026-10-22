package com.sitionix.forgeagent.domain.model.standalone;
import java.time.Instant;
import java.util.UUID;
public record StandaloneExecutionClaim(StandaloneAgentSession session,UUID turnId,String input,String providerTurnId,
        String owner,long token,Instant deadline,boolean cancelRequested,boolean dispatchStarted) { }
