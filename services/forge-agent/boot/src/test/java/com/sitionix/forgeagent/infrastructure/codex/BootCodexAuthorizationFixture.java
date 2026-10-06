package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.llm.ForgeCodexAuthorizationGate;
import com.sitionix.forgeagent.application.llm.LlmAuthorizationService;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import java.time.Clock;
import java.util.List;

/** Independent synthetic auth authority for execution-wire fixtures; admission remains real. */
final class BootCodexAuthorizationFixture implements LlmAuthorizationGateway.Session {
    private boolean connected = true;

    static ForgeCodexAuthorizationGate approvedGate() {
        var account = new BootCodexAuthorizationFixture();
        return new ForgeCodexAuthorizationGate(new LlmAuthorizationService(() -> account, Clock.systemUTC()));
    }

    public LlmAuthorizationGateway.Account readAccount(boolean refreshToken) {
        return new LlmAuthorizationGateway.Account(connected, connected ? "fixture@example.test" : null,
                connected ? "plus" : null);
    }
    public LlmAuthorizationGateway.Login startLogin() { throw new UnsupportedOperationException(); }
    public void cancelLogin(String id) { }
    public void logout() { connected = false; }
    public List<LlmAuthorizationGateway.Event> drainEvents() { return List.of(); }
    public boolean healthy() { return true; }
    public void close() { }
}
