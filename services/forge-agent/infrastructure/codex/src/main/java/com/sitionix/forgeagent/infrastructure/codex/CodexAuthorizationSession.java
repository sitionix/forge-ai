package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Account;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Login;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Event;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Codex 0.160.0 managed ChatGPT account protocol; never exposes raw JSON or provider errors. */
final class CodexAuthorizationSession implements LlmAuthorizationGateway.Session {
    private static final Set<String> LOGIN_HOSTS = Set.of("auth.openai.com", "auth0.openai.com");
    private static final Set<String> PLANS = Set.of("free", "go", "plus", "pro", "prolite", "promax", "team",
            "self_serve_business_prolite", "self_serve_business_usage_based", "business", "ent26",
            "enterprise_cbp_automation", "enterprise_cbp_usage_based", "enterprise", "edu", "edu_plus", "edu_pro", "unknown");
    private final ObjectMapper mapper;
    private final CodexAppServerProperties properties;
    private final CodexJsonRpcTransport transport;
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean closing;

    CodexAuthorizationSession(ObjectMapper mapper, StartedCodexAppServer server, CodexAppServerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.transport = new CodexJsonRpcTransport(mapper, server, properties,
                CodexServerRequestHandler.unsupported(), new CodexTransportEventHandler() {
                    @Override public void handleNotification(String method, JsonNode params) { notification(method, params); }
                    @Override public void transportFailed(RuntimeException exception) { if (!closing) failed.set(true); }
                });
    }

    void initialize() {
        var params = mapper.createObjectNode();
        params.putObject("clientInfo").put("name", "forge_agent").put("version", "0.0.1");
        var result = request(CodexProtocol.INITIALIZE, params);
        text(result, "userAgent", 1024);
        transport.notify(CodexProtocol.INITIALIZED, mapper.createObjectNode());
    }

    @Override public Account readAccount(boolean refreshToken) {
        var result = request(CodexProtocol.ACCOUNT_READ, mapper.createObjectNode().put("refreshToken", refreshToken));
        if (!result.isObject() || !result.path("requiresOpenaiAuth").isBoolean()) throw protocolError();
        var account = result.path("account");
        if (account.isMissingNode() || account.isNull()) return new Account(false, null, null);
        if (!account.isObject() || !"chatgpt".equals(text(account, "type", 32))) throw protocolError();
        String plan = text(account, "planType", 64);
        if (!PLANS.contains(plan) || !account.has("email")) throw protocolError();
        String email = account.path("email").isNull() ? null : text(account, "email", 320);
        return new Account(true, email, plan);
    }

    @Override public Login startLogin() {
        var result = request(CodexProtocol.ACCOUNT_LOGIN_START, mapper.createObjectNode().put("type", "chatgpt"));
        if (!"chatgpt".equals(text(result, "type", 32))) throw protocolError();
        String id = text(result, "loginId", 256);
        String url = text(result, "authUrl", 8192);
        try {
            var uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !LOGIN_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) throw protocolError();
        } catch (IllegalArgumentException exception) { throw protocolError(); }
        return new Login(id, url);
    }

    @Override public void cancelLogin(String providerLoginId) {
        var result = request(CodexProtocol.ACCOUNT_LOGIN_CANCEL, mapper.createObjectNode().put("loginId", providerLoginId));
        if (!Set.of("canceled", "notFound").contains(text(result, "status", 32))) throw protocolError();
    }

    @Override public void logout() {
        if (!request(CodexProtocol.ACCOUNT_LOGOUT, mapper.createObjectNode()).isObject()) throw protocolError();
    }

    @Override public List<Event> drainEvents() {
        if (failed.get()) { events.clear(); return List.of(Event.failed()); }
        List<Event> result = new ArrayList<>();
        events.drainTo(result);
        return List.copyOf(result);
    }

    @Override public boolean healthy() { return !closing && !failed.get() && transport.healthy(); }

    @Override public void close() {
        closing = true;
        events.clear();
        try { transport.close(); }
        catch (RuntimeException exception) { throw new IllegalStateException("CODEX_AUTH_CLEANUP_FAILED"); }
    }

    private JsonNode request(String method, JsonNode params) {
        try { return transport.request(method, params, properties.getRequestTimeout()); }
        catch (RuntimeException exception) { throw new IllegalStateException("CODEX_AUTH_PROVIDER_ERROR"); }
    }

    private void notification(String method, JsonNode params) {
        if (closing) return;
        try {
            Event event;
            if (CodexProtocol.ACCOUNT_LOGIN_COMPLETED.equals(method)) {
                if (!params.isObject() || !params.path("success").isBoolean()) throw protocolError();
                // Managed ChatGPT completion must identify the login. Other login methods are unsupported.
                event = Event.completed(text(params, "loginId", 256), params.path("success").asBoolean());
            } else if (CodexProtocol.ACCOUNT_UPDATED.equals(method)) {
                if (!params.isObject() || params.hasNonNull("authMode") && !params.path("authMode").isTextual()
                        || params.hasNonNull("planType") && !params.path("planType").isTextual()) throw protocolError();
                event = Event.updated();
            } else return;
            if (!events.offer(event)) failed.set(true);
        } catch (RuntimeException exception) { failed.set(true); }
    }

    private static String text(JsonNode node, String field, int limit) {
        var value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit
                || value.asText().chars().anyMatch(Character::isISOControl)) throw protocolError();
        return value.asText();
    }

    private static IllegalStateException protocolError() { return new IllegalStateException("CODEX_AUTH_PROTOCOL_ERROR"); }
}
