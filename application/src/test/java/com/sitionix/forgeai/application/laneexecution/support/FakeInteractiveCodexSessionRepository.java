package com.sitionix.forgeai.application.laneexecution.support;

import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class FakeInteractiveCodexSessionRepository implements CodexSessionRepository {

    private final Function<String, List<String>> responsePlanner;
    private final Map<String, Deque<String>> outputsBySession = new LinkedHashMap<>();
    private final Map<String, List<String>> historyBySession = new LinkedHashMap<>();

    public FakeInteractiveCodexSessionRepository(final Function<String, List<String>> responsePlanner) {
        this.responsePlanner = responsePlanner;
    }

    @Override
    public String start(final String initialPrompt, final String sourceTerminalTty) {
        final String sessionId = UUID.randomUUID().toString();
        this.outputsBySession.put(sessionId, new ArrayDeque<>());
        this.historyBySession.put(sessionId, new ArrayList<>());
        this.recordServiceMessage(sessionId, initialPrompt);
        this.enqueueResponses(sessionId, initialPrompt);
        return sessionId;
    }

    @Override
    public void send(final String sessionId, final String message, final String sourceTerminalTty) {
        this.ensureSession(sessionId);
        this.recordServiceMessage(sessionId, message);
        this.enqueueResponses(sessionId, message);
    }

    @Override
    public String waitForOutput(final String sessionId, final long timeoutMs) {
        this.ensureSession(sessionId);
        final String output = this.outputsBySession.get(sessionId).pollFirst();
        if (output == null) {
            throw new IllegalStateException("No Codex output queued for sessionId=" + sessionId);
        }
        this.historyBySession.get(sessionId).add("output:" + output);
        return output;
    }

    @Override
    public boolean isAlive(final String sessionId) {
        return this.outputsBySession.containsKey(sessionId);
    }

    @Override
    public void close(final String sessionId) {
        this.outputsBySession.remove(sessionId);
    }

    public List<String> history(final String sessionId) {
        return List.copyOf(this.historyBySession.getOrDefault(sessionId, List.of()));
    }

    public List<String> sessionIds() {
        return List.copyOf(this.historyBySession.keySet());
    }

    private void enqueueResponses(final String sessionId, final String message) {
        final List<String> responses = this.responsePlanner.apply(message);
        if (responses == null) {
            return;
        }
        responses.forEach(response -> {
            this.outputsBySession.get(sessionId).addLast(response);
            this.historyBySession.get(sessionId).add("codex:" + response);
        });
    }

    private void recordServiceMessage(final String sessionId, final String message) {
        this.historyBySession.get(sessionId).add("service:" + message);
    }

    private void ensureSession(final String sessionId) {
        if (!this.outputsBySession.containsKey(sessionId)) {
            throw new IllegalStateException("Unknown fake sessionId=" + sessionId);
        }
    }
}
