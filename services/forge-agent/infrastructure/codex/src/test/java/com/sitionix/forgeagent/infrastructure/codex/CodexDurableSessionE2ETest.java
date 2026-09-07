package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "forge.codex.live-session-e2e", matches = "true")
class CodexDurableSessionE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void freshProcessResumesDurableThreadAndRecallsFirstTurnOnlyFact() throws Exception {
        final String fact = "forge-session-fact-" + UUID.randomUUID();
        final var workspacePath = Files.createTempDirectory("forge-codex-live-session-");
        final var workspace = new ExecutionWorkspace(workspacePath, List.of(workspacePath));
        final String model = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        final var schema = this.objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """);
        final AtomicReference<String> threadOne = new AtomicReference<>();
        final AtomicReference<String> turnOne = new AtomicReference<>();
        final AtomicReference<String> versionOne = new AtomicReference<>();
        final CodexAppServerProperties firstProperties = this.properties(workspacePath.toString());
        final CodexAppServerClient firstClient = this.client(firstProperties);
        final String firstOutput;
        try {
            firstOutput = firstClient.executeDurable(new CodexTurnRequest(
                    "Remember this exact private fact for a later turn: " + fact + ". Reply with JSON acknowledging it.",
                    "Return only JSON matching the supplied schema.", model, null, schema, workspace
            ), null, new RecordingCallbacks(threadOne, turnOne, versionOne));
        } finally {
            firstClient.close();
        }

        assertThat(firstOutput).contains("answer");
        assertThat(versionOne.get()).isEqualTo("0.153.2");
        assertThat(threadOne.get()).isNotBlank();
        assertThat(turnOne.get()).isNotBlank();

        final AtomicReference<String> turnTwo = new AtomicReference<>();
        final CodexAppServerProperties secondProperties = this.properties(workspacePath.toString());
        final CodexAppServerClient secondClient = this.client(secondProperties);
        final String secondOutput;
        try {
            secondOutput = secondClient.executeDurable(new CodexTurnRequest(
                    "Return the exact private fact supplied in the previous turn in the answer field.",
                    "Return only JSON matching the supplied schema.", model, null, schema, workspace
            ), threadOne.get(), versionOne.get(), new CodexExecutionIdentityCallbacks() {
                @Override
                public void conversationStarted(final String threadId, final String providerVersion) {
                    throw new AssertionError("Resume must not create a replacement conversation");
                }

                @Override
                public void turnStarted(final String turnId) {
                    turnTwo.set(turnId);
                }
            });
        } finally {
            secondClient.close();
        }

        assertThat(secondOutput).contains(fact);
        assertThat(turnTwo.get()).isNotBlank().isNotEqualTo(turnOne.get());
    }

    private CodexAppServerClient client(final CodexAppServerProperties properties) {
        return new CodexAppServerClient(
                this.objectMapper,
                new DefaultCodexAppServerProcessStarter(properties),
                properties,
                new CodexRuntimeWorkspace(properties)
        );
    }

    private CodexAppServerProperties properties(final String workspace) {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(workspace);
        properties.setRequestTimeout(Duration.ofSeconds(30));
        properties.setTurnTimeout(Duration.ofMinutes(5));
        return properties;
    }

    private record RecordingCallbacks(
            AtomicReference<String> thread,
            AtomicReference<String> turn,
            AtomicReference<String> version
    ) implements CodexExecutionIdentityCallbacks {
        @Override
        public void conversationStarted(final String threadId, final String providerVersion) {
            this.thread.set(threadId);
            this.version.set(providerVersion);
        }

        @Override
        public void turnStarted(final String turnId) {
            this.turn.set(turnId);
        }
    }
}
