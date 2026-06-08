package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEventType;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnInterruptedException;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexProgressObserver;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexAppServerSessionRepository implements CodexSessionRepository {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexProgressProperties progressProperties;
    @Nullable
    private final CodexProgressObserver progressObserver;
    private final Map<String, SessionHandle> sessions = new LinkedHashMap<>();

    @Override
    public CodexSession openSession(final CodexSessionStartCommand command) {
        final String sessionId = UUID.randomUUID().toString();
        final StartedCodexAppServer started = this.processStarter.start();
        final CodexJsonRpcClient client = new CodexJsonRpcClient(
                this.objectMapper,
                started.process(),
                started.command(),
                started.codexVersion(),
                this.progressObserver,
                this.progressProperties,
                new CodexJsonRpcClient.ProgressContext(
                        command.executionId(),
                        command.ticketId(),
                        command.laneId(),
                        command.agentId(),
                        command.scope(),
                        sessionId
                )
        );
        this.emit(this.progressObserver, CodexProgressEvent.builder()
                .executionId(command.executionId())
                .ticketId(command.ticketId())
                .laneId(command.laneId())
                .agentId(command.agentId())
                .scope(command.scope())
                .sessionId(sessionId)
                .processPid(started.process().pid())
                .command(String.join(" ", started.command()))
                .cwd(this.workspaceRoot(command))
                .codexVersion(started.codexVersion())
                .eventType(CodexProgressEventType.PROCESS_STARTED)
                .status("stop=POST /api/v1/forge-ai/operator/executions/" + command.executionId() + "/interrupt")
                .occurredAt(started.startedAt())
                .build());
        try {
            this.initialize(client);
            final JsonNode threadStartResult = this.startOrResumeThread(client, command);
            final String threadId = threadStartResult.path("thread").path("id").asText(null);
            if (threadId == null || threadId.isBlank()) {
                throw new IllegalStateException("Codex app-server did not return thread id");
            }
            client.setThreadId(threadId);
            this.sessions.put(sessionId, new SessionHandle(client, threadId));
            this.emit(this.progressObserver, CodexProgressEvent.builder()
                    .executionId(command.executionId())
                    .ticketId(command.ticketId())
                    .laneId(command.laneId())
                    .agentId(command.agentId())
                    .scope(command.scope())
                    .sessionId(sessionId)
                    .threadId(threadId)
                    .processPid(started.process().pid())
                    .eventType(CodexProgressEventType.SESSION_STARTED)
                    .occurredAt(Instant.now())
                    .build());
            return CodexSession.builder()
                    .id(sessionId)
                    .threadId(threadId)
                    .processPid(started.process().pid())
                    .command(List.copyOf(started.command()))
                    .cwd(this.workspaceRoot(command))
                    .startedAt(started.startedAt())
                    .codexVersion(started.codexVersion())
                    .build();
        } catch (final RuntimeException ex) {
            client.close();
            throw ex;
        }
    }

    @Override
    public CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
        final SessionHandle handle = this.requireSession(sessionId);
        final Duration timeout = command.timeout() == null ? Duration.ofMinutes(10) : command.timeout();
        handle.client().setActiveStepContext(command.stepId(), command.stepOrder(), command.stepTitle());
        final JsonNode turnStartResult = handle.client().request("turn/start", this.turnStartParams(handle.threadId(), command), timeout);
        final JsonNode turnNode = turnStartResult.path("turn");
        final String turnId = turnNode.path("id").asText(null);
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalStateException("Codex app-server did not return turn id");
        }
        final CodexTurnEventCollector.CompletedTurn completedTurn =
                "completed".equalsIgnoreCase(turnNode.path("status").asText())
                        ? new CodexTurnEventCollector.CompletedTurn(turnNode, "", Instant.now())
                        : handle.client().awaitCompletedTurn(
                                handle.threadId(),
                                turnId,
                                command.stepId(),
                                command.stepOrder(),
                                command.stepTitle(),
                                timeout
                        );
        if ("interrupted".equalsIgnoreCase(completedTurn.turn().path("status").asText())) {
            throw new CodexTurnInterruptedException("Codex turn interrupted threadId=" + handle.threadId() + ", turnId=" + turnId);
        }
        final String assistantResponse = this.extractAssistantResponse(completedTurn.assistantResponse());
        return CodexTurnResponse.builder()
                .sessionId(sessionId)
                .threadId(handle.threadId())
                .turnId(turnId)
                .assistantResponse(assistantResponse)
                .build();
    }

    @Override
    public void interruptTurn(final String sessionId, final String turnId, final Duration timeout) {
        final SessionHandle handle = this.requireSession(sessionId);
        handle.client().interruptTurn(handle.threadId(), turnId, timeout);
    }

    @Override
    public void closeSession(final String sessionId) {
        final SessionHandle handle = this.sessions.remove(sessionId);
        if (handle != null) {
            handle.client().close();
        }
    }

    private void initialize(final CodexJsonRpcClient client) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        final ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", this.properties.getClientName());
        clientInfo.put("title", this.properties.getClientTitle());
        clientInfo.put("version", this.properties.getClientVersion());
        final ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", this.properties.isExperimentalApi());
        capabilities.put("requestAttestation", this.properties.isRequestAttestation());
        client.request("initialize", params, STARTUP_TIMEOUT);
        client.markInitializeSucceeded();
        client.notify("initialized", this.objectMapper.createObjectNode());
        client.markInitializedSent();
    }

    private JsonNode threadStartParams(final CodexSessionStartCommand command) {
        final String workspaceRoot = this.workspaceRoot(command);
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("cwd", workspaceRoot);
        params.set("runtimeWorkspaceRoots", this.runtimeWorkspaceRoots(command, workspaceRoot));
        params.put("approvalPolicy", this.properties.getApprovalPolicy());
        params.put("sandbox", this.properties.getSandbox());
        params.put("serviceName", this.properties.getServiceName());
        if (this.hasText(this.properties.getModel())) {
            params.put("model", this.properties.getModel());
        }
        if (this.hasText(this.properties.getModelProvider())) {
            params.put("modelProvider", this.properties.getModelProvider());
        }
        return params;
    }

    private JsonNode startOrResumeThread(final CodexJsonRpcClient client, final CodexSessionStartCommand command) {
        if (this.hasText(command.resumeThreadId())) {
            return client.request("thread/resume", this.threadResumeParams(command), STARTUP_TIMEOUT);
        }
        return client.request("thread/start", this.threadStartParams(command), STARTUP_TIMEOUT);
    }

    private JsonNode threadResumeParams(final CodexSessionStartCommand command) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", command.resumeThreadId());
        params.put("cwd", this.workspaceRoot(command));
        params.put("approvalPolicy", this.properties.getApprovalPolicy());
        params.put("sandbox", this.properties.getSandbox());
        if (this.hasText(this.properties.getModel())) {
            params.put("model", this.properties.getModel());
        }
        if (this.hasText(this.properties.getModelProvider())) {
            params.put("modelProvider", this.properties.getModelProvider());
        }
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

    private String extractAssistantResponse(final String assistantResponse) {
        if (assistantResponse == null || assistantResponse.isBlank()) {
            throw new IllegalStateException("Codex app-server turn completed without assistant response text");
        }
        return assistantResponse;
    }

    private String workspaceRoot(final CodexSessionStartCommand command) {
        final String candidate = command != null && this.hasText(command.workspaceRoot())
                ? command.workspaceRoot()
                : Path.of("").toAbsolutePath().normalize().toString();
        final Path path = Path.of(candidate).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalStateException("Invalid Codex app-server cwd: path does not exist: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Invalid Codex app-server cwd: path is not a directory: " + path);
        }
        return path.toString();
    }

    private ArrayNode runtimeWorkspaceRoots(final CodexSessionStartCommand command, final String workspaceRoot) {
        final ArrayNode roots = this.objectMapper.createArrayNode();
        final List<String> candidates = command == null || command.runtimeWorkspaceRoots() == null || command.runtimeWorkspaceRoots().isEmpty()
                ? List.of(workspaceRoot)
                : command.runtimeWorkspaceRoots();
        for (final String candidate : candidates) {
            final String root = this.workspaceRoot(command == null ? null : command.toBuilder().workspaceRoot(candidate).build());
            if (!this.contains(roots, root)) {
                roots.add(root);
            }
        }
        if (!this.contains(roots, workspaceRoot)) {
            roots.insert(0, workspaceRoot);
        }
        return roots;
    }

    private boolean contains(final ArrayNode values, final String expected) {
        for (final JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private SessionHandle requireSession(final String sessionId) {
        final SessionHandle handle = this.sessions.get(sessionId);
        if (handle == null) {
            throw new IllegalStateException("Unknown Codex sessionId=" + sessionId);
        }
        return handle;
    }

    private void emit(final CodexProgressObserver observer, final CodexProgressEvent event) {
        if (observer != null) {
            observer.onEvent(event);
        }
    }

    private record SessionHandle(CodexJsonRpcClient client, String threadId) {
    }
}
