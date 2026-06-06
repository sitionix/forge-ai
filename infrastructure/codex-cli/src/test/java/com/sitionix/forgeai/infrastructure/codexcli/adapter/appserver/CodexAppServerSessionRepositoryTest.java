package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEventType;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.repository.CodexProgressObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexAppServerSessionRepositoryTest {

    @Test
    void givenSuccessfulFakeServer_whenOpenSession_thenInitializeInitializedAndThreadStartSucceed() {
        final CodexAppServerSessionRepository repository = this.repositoryFor("success");

        final CodexSession session = repository.openSession(this.startCommand());

        assertThat(session.threadId()).isEqualTo("thr_success");
        repository.closeSession(session.id());
    }

    @Test
    void givenThreadStartError_whenOpenSession_thenExceptionContainsFullDiagnostics() {
        final CodexAppServerSessionRepository repository = this.repositoryFor("thread_start_error");

        assertThatThrownBy(() -> repository.openSession(this.startCommand()))
                .isInstanceOf(CodexAppServerRequestException.class)
                .hasMessageContaining("method=thread/start")
                .hasMessageContaining("jsonRpcError.code=-32602")
                .hasMessageContaining("unknown field sandbox")
                .hasMessageContaining("jsonRpcError.data=")
                .hasMessageContaining("field")
                .hasMessageContaining("sandbox")
                .hasMessageContaining("initializedSent=true")
                .hasMessageContaining("initializeSucceeded=true")
                .hasMessageContaining("fake stderr tail");
    }

    @Test
    void givenExperimentalApiDisabled_whenOpenSession_thenInitializeFailsExplicitly() {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setExperimentalApi(false);
        final CodexAppServerSessionRepository repository = this.repositoryFor("success", properties);

        assertThatThrownBy(() -> repository.openSession(this.startCommand()))
                .isInstanceOf(CodexAppServerRequestException.class)
                .hasMessageContaining("method=initialize")
                .hasMessageContaining("experimentalApi capability required")
                .hasMessageContaining("initializeSucceeded=false")
                .hasMessageContaining("initializedSent=false");
    }

    @Test
    void givenMultilinePrompt_whenSubmitTurn_thenSentAsSingleInputTextAndReturnedAsSingleAssistantTurn() {
        final CodexAppServerSessionRepository repository = this.repositoryFor("success");
        final CodexSession session = repository.openSession(this.startCommand());
        final String prompt = "line 1\n\nline 2\n```json\n{\"a\":1}\n```\n\nукраїнський текст";

        final CodexTurnResponse response = repository.submitTurn(session.id(), CodexTurnCommand.builder()
                .prompt(prompt)
                .timeout(Duration.ofSeconds(5))
                .promptType("STEP_PROMPT")
                .stepId("scope_slicing")
                .build());

        assertThat(response.assistantResponse()).isEqualTo(prompt);
        repository.closeSession(session.id());
    }

    @Test
    void givenNoisyForeignEvents_whenSubmitTurn_thenIgnoreThemAndReturnOnlyActiveTurnResponse() {
        final CodexAppServerSessionRepository repository = this.repositoryFor("noisy_events");
        final CodexSession session = repository.openSession(this.startCommand());

        final CodexTurnResponse response = repository.submitTurn(session.id(), CodexTurnCommand.builder()
                .prompt("ping")
                .timeout(Duration.ofSeconds(5))
                .promptType("STEP_PROMPT")
                .stepId("scope_slicing")
                .build());

        assertThat(response.assistantResponse()).isEqualTo("ping");
        assertThat(response.turnId()).isEqualTo("turn_noisy_events");
        repository.closeSession(session.id());
    }

    @Test
    void givenProgressEventsScenario_whenSubmitTurn_thenEmitHumanReadableProgressEvents() {
        final CapturingObserver observer = new CapturingObserver();
        final CodexAppServerSessionRepository repository = this.repositoryFor("progress_events", new CodexAppServerProperties(), new CodexProgressProperties(), observer);
        final CodexSession session = repository.openSession(this.startCommand());

        repository.submitTurn(session.id(), CodexTurnCommand.builder()
                .prompt("inspect")
                .timeout(Duration.ofSeconds(5))
                .promptType("STEP_PROMPT")
                .stepId("scope_slicing")
                .stepOrder(1)
                .stepTitle("Scope slicing")
                .build());

        assertThat(observer.eventTypes()).contains(
                CodexProgressEventType.PROCESS_STARTED,
                CodexProgressEventType.SESSION_STARTED,
                CodexProgressEventType.TURN_STARTED,
                CodexProgressEventType.TURN_PLAN_UPDATED,
                CodexProgressEventType.COMMAND_STARTED,
                CodexProgressEventType.COMMAND_OUTPUT,
                CodexProgressEventType.COMMAND_COMPLETED,
                CodexProgressEventType.TURN_COMPLETED
        );
        assertThat(observer.events()).allMatch(event -> event.text() == null || !event.text().trim().startsWith("{"));
        repository.closeSession(session.id());
    }

    @Test
    void givenHeartbeatScenario_whenTurnWaits_thenHeartbeatEventIsEmitted() {
        final CapturingObserver observer = new CapturingObserver();
        final CodexProgressProperties progressProperties = new CodexProgressProperties();
        progressProperties.setHeartbeatInterval(Duration.ofMillis(100));
        final CodexAppServerSessionRepository repository = this.repositoryFor("heartbeat", new CodexAppServerProperties(), progressProperties, observer);
        final CodexSession session = repository.openSession(this.startCommand());

        repository.submitTurn(session.id(), CodexTurnCommand.builder()
                .prompt("slow")
                .timeout(Duration.ofSeconds(5))
                .promptType("STEP_PROMPT")
                .stepId("scope_slicing")
                .stepOrder(1)
                .stepTitle("Scope slicing")
                .build());

        assertThat(observer.eventTypes()).contains(CodexProgressEventType.HEARTBEAT);
        repository.closeSession(session.id());
    }

    @Test
    void givenInterruptScenario_whenInterruptTurn_thenSendInterruptAndReportInterrupted() throws Exception {
        final CapturingObserver observer = new CapturingObserver();
        final CodexAppServerSessionRepository repository = this.repositoryFor("interrupt", new CodexAppServerProperties(), new CodexProgressProperties(), observer);
        final CodexSession session = repository.openSession(this.startCommand());

        final Thread submitThread = Thread.ofVirtual().start(() -> assertThatThrownBy(() ->
                repository.submitTurn(session.id(), CodexTurnCommand.builder()
                        .prompt("wait")
                        .timeout(Duration.ofSeconds(5))
                        .promptType("STEP_PROMPT")
                        .stepId("scope_slicing")
                        .stepOrder(1)
                        .stepTitle("Scope slicing")
                        .build())).isInstanceOf(RuntimeException.class));

        Thread.sleep(200L);
        repository.interruptTurn(session.id(), "turn_interrupt", Duration.ofSeconds(5));
        submitThread.join();

        assertThat(observer.eventTypes()).contains(CodexProgressEventType.TURN_INTERRUPT_SENT, CodexProgressEventType.TURN_INTERRUPTED);
        repository.closeSession(session.id());
    }

    private CodexAppServerSessionRepository repositoryFor(final String scenario) {
        return this.repositoryFor(scenario, new CodexAppServerProperties(), new CodexProgressProperties(), null);
    }

    private CodexAppServerSessionRepository repositoryFor(final String scenario, final CodexAppServerProperties properties) {
        return this.repositoryFor(scenario, properties, new CodexProgressProperties(), null);
    }

    private CodexAppServerSessionRepository repositoryFor(final String scenario,
                                                          final CodexAppServerProperties properties,
                                                          final CodexProgressProperties progressProperties,
                                                          final CodexProgressObserver observer) {
        final ObjectMapper objectMapper = new ObjectMapper();
        final CodexAppServerProcessStarter starter = () -> new StartedCodexAppServer(
                this.startFakeServer(scenario),
                this.fakeServerCommand(scenario),
                "fake-codex-cli 1.0",
                Instant.now()
        );
        return new CodexAppServerSessionRepository(
                objectMapper,
                starter,
                properties,
                progressProperties,
                observer
        );
    }

    private Process startFakeServer(final String scenario) {
        try {
            return new ProcessBuilder(this.fakeServerCommand(scenario)).start();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> fakeServerCommand(final String scenario) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FakeCodexAppServerMain.class.getName(),
                scenario
        );
    }

    private CodexSessionStartCommand startCommand() {
        return CodexSessionStartCommand.builder()
                .executionId(UUID.randomUUID())
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .workspaceRoot(Path.of("").toAbsolutePath().normalize().toString())
                .ticketKey("SITIONIX-1")
                .agentId("analyzer")
                .scope("automationservice-sox")
                .build();
    }

    private static final class CapturingObserver implements CodexProgressObserver {

        private final List<CodexProgressEvent> events = new ArrayList<>();

        @Override
        public void onEvent(final CodexProgressEvent event) {
            this.events.add(event);
        }

        List<CodexProgressEvent> events() {
            return List.copyOf(this.events);
        }

        List<CodexProgressEventType> eventTypes() {
            return this.events.stream().map(CodexProgressEvent::eventType).toList();
        }
    }
}
