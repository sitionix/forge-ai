package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.dialogue.DialogueExecutionContext;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueTurnKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodexDialogueExecutionTest {
    @Test void allDialogueTurnsUseExistingDurableTransportWithoutOutputSelection() {
        var client = mock(CodexClient.class);
        when(client.executeDurable(any(),any(),any(),any())).thenReturn("{}");
        var executor = new CodexAgentExecutor(new ObjectMapper(),client);
        UUID nodeId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        for (DialogueTurnKind kind : DialogueTurnKind.values()) {
            var session = new AgentSessionExecutionClaim(sessionId,UUID.randomUUID(),nodeId,"worker",1,
                    Instant.now().plusSeconds(30),kind==DialogueTurnKind.INITIAL ? null : "same-thread","codex",
                    NodeContextMode.DIALOGUE_WITHIN_NODE_RUN,"version");
            var claim = new NodeExecutionClaim(UUID.randomUUID(),nodeId,UUID.randomUUID(),"task","groomer","Clarify requirements",
                    new AgentOutputSchema("{}"),new NodeRunExecutionModel("codex","model",null),
                    new NodeInputEnvelope("task",null,List.of()),List.of(),null,session,
                    new DialogueExecutionContext(UUID.randomUUID(),kind,1,"{\"turnKind\":\""+kind+"\"}"));
            executor.execute(claim);
        }
        var requests = ArgumentCaptor.forClass(CodexTurnRequest.class);
        var threads = ArgumentCaptor.forClass(String.class);
        verify(client,times(3)).executeDurable(requests.capture(),threads.capture(),any(),any());
        assertThat(threads.getAllValues()).containsExactly(null,"same-thread","same-thread");
        for (var request : requests.getAllValues()) {
            assertThat(request.developerInstructions()).contains("Dialogue").doesNotContain("outputPortId");
            assertThat(request.userInput()).contains("turnKind").doesNotContain("availableOutputs");
            assertThat(request.outputSchema().has("__forge")).isFalse();
        }
        verify(client,never()).execute(any());
        verify(client,never()).executeTrackedFresh(any(),any());
    }
}
