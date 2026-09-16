package com.sitionix.forgeagent.application.runtime;

/** Native durable context control; never creates an execution turn. */
public interface AgentContextForkProvider {
    boolean supports(String providerId, String providerVersion);
    void validateSupport(String providerId, String providerVersion);
    String fork(String providerId, String providerVersion, String providerConversationId, String providerTurnId);
}
