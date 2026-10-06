package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;

@Component
@RequiredArgsConstructor
final class CodexAuthorizationAdapter implements LlmAuthorizationGateway, DisposableBean {
    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexRuntimeWorkspace runtimeWorkspace;
    private CodexAuthorizationSession openingSession;

    @Override public synchronized Session openSession() {
        try {
            // Failed initialization may leave cleanup unconfirmed. Retain ownership and refuse replacement.
            closeOpeningSession();
            openingSession = new CodexAuthorizationSession(objectMapper,
                    processStarter.start(runtimeWorkspace.routingWorkspace().cwd()), properties);
            openingSession.initialize();
            var initialized = openingSession;
            openingSession = null;
            return initialized;
        } catch (RuntimeException exception) {
            try { closeOpeningSession(); } catch (RuntimeException ignored) { }
            // Provider messages/causes may contain secrets or authorization URLs.
            throw new IllegalStateException("CODEX_AUTH_UNAVAILABLE");
        }
    }

    private void closeOpeningSession() {
        if (openingSession != null) {
            openingSession.close();
            openingSession = null;
        }
    }

    @Override public synchronized void destroy() { closeOpeningSession(); }
}
