package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("it")
public class ItCodexSessionRepositoryStub implements CodexSessionRepository {

    private final Map<String, ConcurrentLinkedQueue<String>> outputs = new ConcurrentHashMap<>();
    private final List<String> sentMessages = new CopyOnWriteArrayList<>();
    private final List<String> startedMessages = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> history = new ConcurrentHashMap<>();

    @Override
    public String start(final String initialPrompt, final String sourceTerminalTty) {
        final String sessionId = UUID.randomUUID().toString();
        this.outputs.put(sessionId, new ConcurrentLinkedQueue<>());
        this.history.put(sessionId, new CopyOnWriteArrayList<>());
        this.history.get(sessionId).add("service:" + initialPrompt);
        this.startedMessages.add(initialPrompt);
        final String stepId = this.extractStepId(initialPrompt);
        if (stepId != null) {
            final String output = "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"" + stepId + "\",\"summary\":\"done\",\"evidence\":{}}";
            this.outputs.get(sessionId).add(output);
            this.history.get(sessionId).add("codex:" + output);
        }
        return sessionId;
    }

    @Override
    public void send(final String sessionId, final String message, final String sourceTerminalTty) {
        this.sentMessages.add(message);
        this.history.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add("service:" + message);
        final String stepId = this.extractStepId(message);
        if (stepId != null) {
            final String output = "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"" + stepId + "\",\"summary\":\"done\",\"evidence\":{}}";
            this.outputs.get(sessionId).add(output);
            this.history.get(sessionId).add("codex:" + output);
        }
    }

    @Override
    public String waitForOutput(final String sessionId, final long timeoutMs) {
        final String output = this.outputs.get(sessionId).poll();
        if (output == null) {
            throw new IllegalStateException("No output queued for sessionId=" + sessionId);
        }
        this.history.get(sessionId).add("wait:" + output);
        return output;
    }

    @Override
    public boolean isAlive(final String sessionId) {
        return this.outputs.containsKey(sessionId);
    }

    @Override
    public void close(final String sessionId) {
        this.outputs.remove(sessionId);
    }

    public List<String> history(final String sessionId) {
        return List.copyOf(this.history.getOrDefault(sessionId, List.of()));
    }

    public List<String> sentMessages() {
        return List.copyOf(this.sentMessages);
    }

    public void clearSentMessages() {
        this.sentMessages.clear();
    }

    public List<String> startedMessages() {
        return List.copyOf(this.startedMessages);
    }

    public void clearStartedMessages() {
        this.startedMessages.clear();
    }

    private String extractStepId(final String message) {
        final String fromBlock = this.extractFromMultilineLabel(message, "stepId:");
        if (fromBlock != null) {
            return fromBlock;
        }
        final String fromFallbackBlock = this.extractFromMultilineLabel(message, "Step id:");
        if (fromFallbackBlock != null) {
            return fromFallbackBlock;
        }
        final String jsonMarker = "\"stepId\":\"";
        final int jsonIndex = message.indexOf(jsonMarker);
        if (jsonIndex >= 0) {
            final int start = jsonIndex + jsonMarker.length();
            final int end = message.indexOf('"', start);
            if (end > start) {
                return message.substring(start, end);
            }
        }
        return null;
    }

    private String extractFromMultilineLabel(final String message, final String label) {
        final int labelIndex = message.indexOf(label);
        if (labelIndex < 0) {
            return null;
        }
        int cursor = labelIndex + label.length();
        while (cursor < message.length()) {
            final char current = message.charAt(cursor);
            if (current == '\n' || current == '\r') {
                cursor++;
                continue;
            }
            break;
        }
        final int lineEnd = this.findLineEnd(message, cursor);
        if (cursor >= lineEnd) {
            return null;
        }
        return message.substring(cursor, lineEnd).trim();
    }

    private int findLineEnd(final String message, final int startIndex) {
        int index = startIndex;
        while (index < message.length()) {
            final char current = message.charAt(index);
            if (current == '\n' || current == '\r') {
                break;
            }
            index++;
        }
        return index;
    }
}
