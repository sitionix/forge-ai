package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("it")
public class ItCodexSessionRepositoryStub implements CodexSessionRepository {

    private static final Pattern STEP_ID_PATTERN = Pattern.compile("(?m)^- stepId:\\s*([^\\r\\n]+)\\s*$");
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("(?m)^- agentId:\\s*([^\\r\\n]+)\\s*$");
    private static final Pattern SCOPE_PATTERN = Pattern.compile("(?m)^- scope:\\s*([^\\r\\n]+)\\s*$");
    private static final Pattern JSON_SCOPE_PATTERN = Pattern.compile("\"scope\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SAMPLE_SCOPE_PATTERN = Pattern.compile("(\"scope\"\\s*:\\s*)\"\\.\\.\\.\"");

    private final Function<CodexTurnCommand, String> responsePlanner = this::defaultResponseFor;
    private final Map<String, List<String>> history = new ConcurrentHashMap<>();
    private final List<String> submittedPrompts = new CopyOnWriteArrayList<>();
    private final List<String> interruptedTurns = new CopyOnWriteArrayList<>();

    @Override
    public CodexSession openSession(final CodexSessionStartCommand command) {
        final String sessionId = UUID.randomUUID().toString();
        this.history.put(sessionId, new CopyOnWriteArrayList<>());
        return CodexSession.builder()
                .id(sessionId)
                .threadId("thread-" + sessionId)
                .processPid(91342L)
                .command(List.of("codex", "app-server", "--stdio"))
                .cwd(System.getProperty("user.dir"))
                .startedAt(Instant.now())
                .codexVersion("fake")
                .build();
    }

    @Override
    public CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
        this.ensureSession(sessionId);
        this.history.get(sessionId).add("service:" + command.prompt());
        this.submittedPrompts.add(command.prompt());
        final String response = this.responsePlanner.apply(command);
        this.history.get(sessionId).add("assistant:" + response);
        return CodexTurnResponse.builder()
                .sessionId(sessionId)
                .threadId("thread-" + sessionId)
                .turnId(UUID.randomUUID().toString())
                .assistantResponse(response)
                .build();
    }

    @Override
    public void closeSession(final String sessionId) {
        this.history.remove(sessionId);
    }

    @Override
    public void interruptTurn(final String sessionId, final String turnId, final Duration timeout) {
        this.history.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>());
        this.interruptedTurns.add(turnId);
    }

    public List<String> history(final String sessionId) {
        return List.copyOf(this.history.getOrDefault(sessionId, List.of()));
    }

    public List<String> submittedPrompts() {
        return List.copyOf(this.submittedPrompts);
    }

    public List<String> sentMessages() {
        return this.submittedPrompts();
    }

    public void clearSubmittedPrompts() {
        this.submittedPrompts.clear();
    }

    public void clearSentMessages() {
        this.clearSubmittedPrompts();
    }

    public List<String> startedMessages() {
        return List.of();
    }

    public void clearStartedMessages() {
    }

    public List<String> interruptedTurns() {
        return List.copyOf(this.interruptedTurns);
    }

    private String defaultResponseFor(final CodexTurnCommand command) {
        final String prompt = command == null ? "" : command.prompt();
        final String stepId = this.matchValue(STEP_ID_PATTERN, prompt, "unknown");
        final String agentId = this.matchValue(AGENT_ID_PATTERN, prompt, "unknown");
        final String scope = this.matchValue(SCOPE_PATTERN, prompt, "GLOBAL");
        final String evidence = "completion".equals(stepId)
                ? "\"completionPayload\":" + this.completionPayload(agentId, scope, prompt)
                : "\"detail\":\"ok\"";
        return """
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "%s",
                  "summary": "done",
                  "evidence": { %s }
                }
                """.formatted(stepId, evidence);
    }

    private String completionPayload(final String agentId, final String scope, final String prompt) {
        final List<String> outputContracts = this.outputContracts(prompt);
        final StringBuilder builder = new StringBuilder()
                .append("{\n")
                .append("  \"outputs\": [");
        if (!outputContracts.isEmpty()) {
            builder.append('\n')
                    .append(String.join(",\n", outputContracts))
                    .append('\n');
        }
        builder.append("  ]");
        if (prompt.contains("\"apiEvidence\"")) {
            builder.append(",\n")
                    .append("  \"apiEvidence\": {\n")
                    .append("    \"summary\": \"Prepared API artifacts\",\n")
                    .append("    \"prUrl\": \"https://github.com/sitionix/example/pull/1\",\n")
                    .append("    \"repo\": \"sitionix/example\",\n")
                    .append("    \"contracts\": []\n")
                    .append("  }");
        }
        if (prompt.contains("\"report\"")) {
            builder.append(",\n")
                    .append("  \"report\": ")
                    .append(this.reportPayload(agentId, scope));
        }
        return builder.append("\n}").toString();
    }

    private List<String> outputContracts(final String prompt) {
        final List<String> outputContracts = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            final int agentPosition = prompt.indexOf("\"agent\"", searchFrom);
            if (agentPosition < 0) {
                return outputContracts;
            }
            final int objectStart = prompt.lastIndexOf('{', agentPosition);
            final int objectEnd = this.matchingBrace(prompt, objectStart);
            if (objectStart < 0 || objectEnd < 0) {
                return outputContracts;
            }
            final String contract = prompt.substring(objectStart, objectEnd + 1).trim();
            final String outputScope = this.matchValue(JSON_SCOPE_PATTERN, contract, "GLOBAL");
            outputContracts.add(this.withConcretePayloadScope(contract, outputScope));
            searchFrom = objectEnd + 1;
        }
    }

    private String withConcretePayloadScope(final String contract, final String scope) {
        return SAMPLE_SCOPE_PATTERN.matcher(contract)
                .replaceAll(match -> match.group(1) + "\"" + scope.replace("\"", "\\\"") + "\"");
    }

    private int matchingBrace(final String source, final int objectStart) {
        if (objectStart < 0) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = objectStart; i < source.length(); i++) {
            final char current = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = inString;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String reportPayload(final String agentId, final String scope) {
        return """
                {
                  "scope": "%s",
                  "summary": "%s lane complete",
                  "coveredCases": []
                }""".formatted(scope, agentId).trim();
    }

    private String matchValue(final Pattern pattern, final String source, final String fallback) {
        final Matcher matcher = pattern.matcher(source == null ? "" : source);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private void ensureSession(final String sessionId) {
        if (!this.history.containsKey(sessionId)) {
            throw new IllegalStateException("Unknown fake sessionId=" + sessionId);
        }
    }
}
