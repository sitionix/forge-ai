package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("codex-app-server-smoke")
@EnabledIfEnvironmentVariable(named = "FORGE_CODEX_APP_SERVER_SMOKE", matches = "true")
class CodexAppServerSmokeTest {

    @Test
    void givenRealCodexAppServer_whenSubmittingSingleTurn_thenReceiveAssistantResponse() {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        final CodexAppServerSessionRepository repository = new CodexAppServerSessionRepository(
                new ObjectMapper(),
                new DefaultCodexAppServerProcessStarter(properties),
                properties,
                new CodexProgressProperties(),
                null
        );
        final CodexSession session = repository.openSession(CodexSessionStartCommand.builder()
                .executionId(UUID.randomUUID())
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .workspaceRoot(Path.of("").toAbsolutePath().normalize().toString())
                .ticketKey("SMOKE-1")
                .agentId("analyzer")
                .scope("automationservice-sox")
                .build());
        try {
            final CodexTurnResponse response = repository.submitTurn(session.id(), CodexTurnCommand.builder()
                    .prompt("Reply with exactly one word: pong")
                    .timeout(Duration.ofSeconds(60))
                    .promptType("STEP_PROMPT")
                    .stepId("smoke")
                    .build());
            assertThat(response.assistantResponse()).isNotBlank();
        } finally {
            repository.closeSession(session.id());
        }
    }
}
