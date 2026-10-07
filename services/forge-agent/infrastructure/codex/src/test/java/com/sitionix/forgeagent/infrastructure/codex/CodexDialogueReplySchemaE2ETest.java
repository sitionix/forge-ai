package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.dialogue.DialogueTurnResultPolicy;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueTurnKind;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "forge.codex.live-dialogue-e2e", matches = "true")
class CodexDialogueReplySchemaE2ETest {
    @Test
    void realProviderAcceptsDialogueEnvelopeAndServerValidatesReply() throws Exception {
        var json = new ObjectMapper();
        var policy = new DialogueTurnResultPolicy(json);
        var business = new AgentOutputSchema("""
                {"type":"object","additionalProperties":false,"required":["title"],
                 "properties":{"title":{"type":"string"}}}
                """);
        var path = Files.createTempDirectory("forge-codex-live-dialogue-");
        var workspace = new ExecutionWorkspace(path, List.of(path));
        var properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(path.toString());
        properties.setRequestTimeout(Duration.ofSeconds(30));
        properties.setTurnTimeout(Duration.ofMinutes(5));
        var client = new CodexAppServerClient(json,
                new DefaultCodexAppServerProcessStarter(properties, CodexFixtureProcesses.launcher(properties)),
                properties, new CodexRuntimeWorkspace(properties), null, new ForgeAuthorizationFixture(true).gate);
        try {
            var output = client.executeDurable(new CodexTurnRequest(
                    "INITIAL grooming turn. Ask the audience and acceptance criteria as two blocking questions. "
                            + "Return draft null, no decisions or sources, readyForReview false. Do not use tools or access files.",
                    DialogueExecutionDeveloperInstructions.compose("Clarify the task before preparing a draft."),
                    System.getProperty("forge.codex.live-model", "gpt-6.1-sol"), null,
                    json.readTree(policy.replySchema(business).jsonObject()), workspace), null,
                    new CodexExecutionIdentityCallbacks() {
                        @Override public void conversationStarted(String threadId, String version) {
                            assertThat(threadId).isNotBlank();
                        }
                        @Override public void turnStarted(String turnId) {
                            assertThat(turnId).isNotBlank();
                        }
                    });
            var reply = policy.validate(business, DialogueTurnKind.INITIAL, output, Set.of());
            assertThat(reply.path("questions").size()).isEqualTo(2);
            assertThat(reply.path("readyForReview").asBoolean()).isFalse();
        } finally {
            client.close();
        }
    }
}
