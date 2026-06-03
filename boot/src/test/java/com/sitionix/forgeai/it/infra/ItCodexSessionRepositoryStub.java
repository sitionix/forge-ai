package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.time.Duration;
import java.time.Instant;
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
                ? "\"completionPayload\":" + this.completionPayload(agentId, scope)
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

    private String completionPayload(final String agentId, final String scope) {
        return switch (agentId) {
            case "analyzer" -> """
                    {
                      "architectHandoff": {
                        "scope": "%s",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "risks": [],
                        "dependencies": []
                      },
                      "qaLeadHandoff": {
                        "scope": "%s",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "risks": [],
                        "dependencies": [],
                        "qualityFocus": [],
                        "edgeConsiderations": []
                      }
                    }
                    """.formatted(scope, scope).trim();
            case "architect" -> """
                    {
                      "implementationScope": "%s",
                      "implementationHandoff": {
                        "scope": "%s",
                        "task": "Implement scoped changes",
                        "summary": "Prepared implementation handoff",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "architectureDecision": "Follow scoped architecture",
                        "dependencies": [],
                        "acceptanceNotes": [],
                        "risks": []
                      },
                      "apiRequired": false,
                      "eventRequired": false
                    }
                    """.formatted(scope, scope).trim();
            case "api" -> """
                    {
                      "summary": "Prepared API artifacts",
                      "prUrl": "https://github.com/sitionix/example/pull/1",
                      "repo": "sitionix/example",
                      "contracts": []
                    }
                    """.trim();
            case "qa_lead" -> """
                    {
                      "scope": "%s",
                      "unitTestRequired": true,
                      "testUnitPayload": {
                        "task": "Cover scoped unit cases",
                        "scope": "%s",
                        "summary": "Unit test focus",
                        "unitTestNotes": []
                      },
                      "integrationTestRequired": true,
                      "testItPayload": {
                        "task": "Cover scoped integration cases",
                        "scope": "%s",
                        "summary": "Integration test focus",
                        "integrationTestCases": [],
                        "unitTestNotes": []
                      },
                      "uiTestRequired": true,
                      "testUiPayload": {
                        "task": "Cover scoped UI cases",
                        "scope": "%s",
                        "summary": "UI test focus",
                        "unitTestNotes": []
                      }
                    }
                    """.formatted(scope, scope, scope, scope).trim();
            case "implement_be" -> """
                    {
                      "task": "Backend implementation complete",
                      "scope": "%s",
                      "summary": "Prepared backend test handoff",
                      "changedFiles": [],
                      "integrationFlows": [],
                      "persistenceChanges": [],
                      "sonar": {},
                      "unitTestNotes": []
                    }
                    """.formatted(scope).trim();
            case "implement_fe" -> """
                    {
                      "task": "Frontend implementation complete",
                      "scope": "%s",
                      "summary": "Prepared frontend test handoff",
                      "changedFiles": [],
                      "affectedSurfaces": [],
                      "uiBehavior": [],
                      "sonar": {},
                      "unitTestNotes": []
                    }
                    """.formatted(scope).trim();
            case "test_unit" -> """
                    {
                      "task": "Review scoped changes",
                      "scope": "%s",
                      "summary": "Prepared reviewer handoff",
                      "affectedFiles": [],
                      "sonar": {}
                    }
                    """.formatted(scope).trim();
            case "test_it" -> """
                    {
                      "scope": "%s",
                      "summary": "Integration test run complete",
                      "coveredCases": []
                    }
                    """.formatted(scope).trim();
            case "test_ui" -> """
                    {
                      "scope": "%s",
                      "summary": "UI test run complete"
                    }
                    """.formatted(scope).trim();
            case "reviewer" -> """
                    {
                      "scope": "%s",
                      "summary": "Reviewer lane complete"
                    }
                    """.formatted(scope).trim();
            case "event" -> """
                    {
                      "scope": "%s",
                      "summary": "Event lane complete"
                    }
                    """.formatted(scope).trim();
            default -> """
                    {
                      "scope": "%s",
                      "summary": "Completion payload"
                    }
                    """.formatted(scope).trim();
        };
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
