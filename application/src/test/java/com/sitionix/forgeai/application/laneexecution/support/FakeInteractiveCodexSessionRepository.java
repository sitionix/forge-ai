package com.sitionix.forgeai.application.laneexecution.support;

import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class FakeInteractiveCodexSessionRepository implements CodexSessionRepository {

    private final Function<CodexTurnCommand, String> responsePlanner;
    private final Map<String, List<String>> historyBySession = new LinkedHashMap<>();
    private final List<String> submittedPrompts = new ArrayList<>();
    private final List<CodexSessionStartCommand> openSessionCommands = new ArrayList<>();

    public FakeInteractiveCodexSessionRepository(final Function<CodexTurnCommand, String> responsePlanner) {
        this.responsePlanner = responsePlanner;
    }

    @Override
    public CodexSession openSession(final CodexSessionStartCommand command) {
        this.openSessionCommands.add(command);
        final String sessionId = UUID.randomUUID().toString();
        final String threadId = "thread-" + sessionId;
        this.historyBySession.put(sessionId, new ArrayList<>());
        return CodexSession.builder()
                .id(sessionId)
                .threadId(threadId)
                .processPid(12345L)
                .command(List.of("codex", "app-server", "--stdio"))
                .cwd(command.workspaceRoot())
                .startedAt(Instant.now())
                .codexVersion("fake")
                .build();
    }

    @Override
    public CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
        this.ensureSession(sessionId);
        this.historyBySession.get(sessionId).add("service:" + command.prompt());
        this.submittedPrompts.add(command.prompt());
        final String response = this.responsePlanner.apply(command);
        if (response != null) {
            this.historyBySession.get(sessionId).add("assistant:" + response);
        }
        return CodexTurnResponse.builder()
                .sessionId(sessionId)
                .threadId("thread-" + sessionId)
                .turnId(UUID.randomUUID().toString())
                .assistantResponse(response)
                .build();
    }

    @Override
    public void closeSession(final String sessionId) {
        this.historyBySession.remove(sessionId);
    }

    @Override
    public void interruptTurn(final String sessionId, final String turnId, final Duration timeout) {
        this.ensureSession(sessionId);
        this.historyBySession.get(sessionId).add("interrupt:" + turnId);
    }

    public List<String> history(final String sessionId) {
        return List.copyOf(this.historyBySession.getOrDefault(sessionId, List.of()));
    }

    public List<String> sessionIds() {
        return List.copyOf(this.historyBySession.keySet());
    }

    public List<String> submittedPrompts() {
        return List.copyOf(this.submittedPrompts);
    }

    public List<CodexSessionStartCommand> openSessionCommands() {
        return List.copyOf(this.openSessionCommands);
    }

    private void ensureSession(final String sessionId) {
        if (!this.historyBySession.containsKey(sessionId)) {
            throw new IllegalStateException("Unknown fake sessionId=" + sessionId);
        }
    }
}
