package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.runtime.AgentContextForkProvider;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class CodexContextForkProvider implements AgentContextForkProvider {
    private final CodexAppServerClient client;

    @Override public boolean supports(final String providerId, final String providerVersion) {
        return "codex".equals(providerId) && CodexAppServerClient.SUPPORTED_DURABLE_VERSION.equals(providerVersion);
    }

    @Override public void validateSupport(final String providerId, final String providerVersion) {
        if (!supports(providerId, providerVersion)) throw unsupported();
        try {
            if (!providerVersion.equals(client.version())) throw unsupported();
        } catch (final ConflictException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw unsupported();
        }
    }

    @Override public String fork(final String providerId, final String providerVersion,
                                 final String providerConversationId, final String providerTurnId) {
        if (!supports(providerId, providerVersion)) throw unsupported();
        try {
            return client.forkDurableContext(providerConversationId, providerTurnId, providerVersion);
        } catch (final RuntimeException exception) {
            throw new ConflictException("AGENT_CONTEXT_FORK_FAILED", "Provider context fork failed; the source context remains authoritative.");
        }
    }

    private ConflictException unsupported() {
        return new ConflictException("AGENT_CONTEXT_FORK_UNSUPPORTED", "Native context fork requires Codex CLI 0.154.0 and a matching persisted provider version.");
    }
}
