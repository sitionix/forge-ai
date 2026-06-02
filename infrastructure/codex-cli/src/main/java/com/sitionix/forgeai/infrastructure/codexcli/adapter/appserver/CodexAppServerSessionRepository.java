package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexAppServerSessionRepository implements CodexSessionRepository {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final Map<String, SessionHandle> sessions = new LinkedHashMap<>();

    @Override
    public synchronized CodexSession openSession(final CodexSessionStartCommand command) {
        final Process process = this.startProcess();
        final CodexJsonRpcClient client = new CodexJsonRpcClient(this.objectMapper, process);
        this.initialize(client);
        final JsonNode threadStartResult = client.request("thread/start", this.threadStartParams(command), STARTUP_TIMEOUT);
        final String threadId = threadStartResult.path("thread").path("id").asText(null);
        if (threadId == null || threadId.isBlank()) {
            client.close();
            throw new IllegalStateException("Codex app-server did not return thread id");
        }
        final String sessionId = UUID.randomUUID().toString();
        this.sessions.put(sessionId, new SessionHandle(client, threadId));
        return CodexSession.builder()
                .id(sessionId)
                .threadId(threadId)
                .build();
    }

    @Override
    public synchronized CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
        final SessionHandle handle = this.requireSession(sessionId);
        final Duration timeout = command.timeout() == null ? Duration.ofMinutes(10) : command.timeout();
        final JsonNode turnStartResult = handle.client().request("turn/start", this.turnStartParams(handle.threadId(), command), timeout);
        final JsonNode turnNode = turnStartResult.path("turn");
        final String turnId = turnNode.path("id").asText(null);
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalStateException("Codex app-server did not return turn id");
        }
        final JsonNode completedTurn = "completed".equalsIgnoreCase(turnNode.path("status").asText())
                ? turnNode
                : handle.client().awaitCompletedTurn(turnId, timeout);
        final String assistantResponse = this.extractAssistantResponse(completedTurn);
        return CodexTurnResponse.builder()
                .sessionId(sessionId)
                .threadId(handle.threadId())
                .turnId(turnId)
                .assistantResponse(assistantResponse)
                .build();
    }

    @Override
    public synchronized void closeSession(final String sessionId) {
        final SessionHandle handle = this.sessions.remove(sessionId);
        if (handle != null) {
            handle.client().close();
        }
    }

    private Process startProcess() {
        try {
            return new ProcessBuilder("codex", "app-server", "--stdio")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to start Codex app-server. Ensure `codex app-server --stdio` is available.", e);
        }
    }

    private void initialize(final CodexJsonRpcClient client) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        final ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "forge-ai");
        clientInfo.put("version", "0.0.1");
        params.putObject("capabilities");
        client.request("initialize", params, STARTUP_TIMEOUT);
    }

    private JsonNode threadStartParams(final CodexSessionStartCommand command) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("cwd", this.workspaceRoot(command));
        params.put("approvalPolicy", "never");
        final ObjectNode sandbox = params.putObject("sandboxPolicy");
        sandbox.put("type", "workspaceWrite");
        final ArrayNode writableRoots = sandbox.putArray("writableRoots");
        writableRoots.add(this.workspaceRoot(command));
        sandbox.put("networkAccess", false);
        sandbox.put("excludeTmpdirEnvVar", false);
        sandbox.put("excludeSlashTmp", false);
        params.put("threadSource", "forge-ai");
        return params;
    }

    private JsonNode turnStartParams(final String threadId, final CodexTurnCommand command) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", threadId);
        final ArrayNode input = params.putArray("input");
        final ObjectNode message = input.addObject();
        message.put("type", "text");
        message.put("text", command.prompt());
        message.putArray("text_elements");
        return params;
    }

    private String extractAssistantResponse(final JsonNode turnNode) {
        final StringBuilder response = new StringBuilder();
        for (final JsonNode item : turnNode.path("items")) {
            if (!"agentMessage".equals(item.path("type").asText())) {
                continue;
            }
            final String text = item.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            if (!response.isEmpty()) {
                response.append("\n\n");
            }
            response.append(text);
        }
        if (response.isEmpty()) {
            throw new IllegalStateException("Codex app-server turn completed without assistant response text");
        }
        return response.toString();
    }

    private String workspaceRoot(final CodexSessionStartCommand command) {
        if (command != null && command.workspaceRoot() != null && !command.workspaceRoot().isBlank()) {
            return command.workspaceRoot();
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private SessionHandle requireSession(final String sessionId) {
        final SessionHandle handle = this.sessions.get(sessionId);
        if (handle == null) {
            throw new IllegalStateException("Unknown Codex sessionId=" + sessionId);
        }
        return handle;
    }

    private record SessionHandle(CodexJsonRpcClient client, String threadId) {
    }
}
