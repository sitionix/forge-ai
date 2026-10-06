package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.llm.ForgeCodexAuthorizationGate;
import com.sitionix.forgeagent.application.llm.LlmAuthorizationService;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import java.time.Clock;
import java.util.List;

final class ForgeAuthorizationFixture implements LlmAuthorizationGateway.Session {
    boolean connected;
    final LlmAuthorizationService service = new LlmAuthorizationService(() -> this, Clock.systemUTC());
    final ForgeCodexAuthorizationGate gate = new ForgeCodexAuthorizationGate(service);
    ForgeAuthorizationFixture(boolean connected) { this.connected = connected; }
    public LlmAuthorizationGateway.Account readAccount(boolean refresh) {
        return new LlmAuthorizationGateway.Account(connected, connected ? "forge@example.test" : null, null);
    }
    public LlmAuthorizationGateway.Login startLogin() { throw new UnsupportedOperationException(); }
    public void cancelLogin(String id) { }
    public void logout() { connected = false; }
    public List<LlmAuthorizationGateway.Event> drainEvents() { return List.of(); }
    public boolean healthy() { return true; }
    public void close() { }
}
