package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentDialogueDtos;
import com.sitionix.forgeai.api.agentproxy.DialogueApiMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue;
import com.sitionix.forgeai.domain.usecase.AgentDialogueUseCases;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(
    "/api/v1/infrastructure/agents/workflow-runs/{runId}/node-runs/{nodeRunId}/dialogue")
public class ForgeAiDialogueController {
  private final AgentDialogueUseCases dialogue;

  @GetMapping
  public AgentDialogueDtos.State get(@PathVariable UUID runId, @PathVariable UUID nodeRunId) {
    return DialogueApiMapper.map(dialogue.getDialogue(runId, nodeRunId));
  }

  @GetMapping("/messages")
  public AgentDialogueDtos.MessagePage messages(
      @PathVariable UUID runId,
      @PathVariable UUID nodeRunId,
      @RequestParam(defaultValue = "0") long afterSequence,
      @RequestParam(defaultValue = "100") int limit) {
    return DialogueApiMapper.map(
        dialogue.listDialogueMessages(runId, nodeRunId, afterSequence, limit));
  }

  @PostMapping("/messages")
  public ResponseEntity<AgentDialogueDtos.State> sendDialogueMessage(
      @PathVariable UUID runId,
      @PathVariable UUID nodeRunId,
      @Valid @RequestBody AgentDialogueDtos.SendRequest request) {
    var response =
        dialogue.sendDialogueMessage(
            runId,
            nodeRunId,
            new AgentDialogue.SendRequest(
                request.requestId(), request.expectedRevision(), request.text()));
    return ResponseEntity.status(response.accepted() ? 202 : 200)
        .body(DialogueApiMapper.map(response.state()));
  }

  @PostMapping("/summary")
  public ResponseEntity<AgentDialogueDtos.State> summarizeDialogue(
      @PathVariable UUID runId,
      @PathVariable UUID nodeRunId,
      @Valid @RequestBody AgentDialogueDtos.SummaryRequest request) {
    var response =
        dialogue.summarizeDialogue(
            runId,
            nodeRunId,
            new AgentDialogue.SummaryRequest(request.requestId(), request.expectedRevision()));
    return ResponseEntity.status(response.accepted() ? 202 : 200)
        .body(DialogueApiMapper.map(response.state()));
  }

  @PostMapping("/complete")
  public ResponseEntity<AgentDialogueDtos.State> completeDialogue(
      @PathVariable UUID runId,
      @PathVariable UUID nodeRunId,
      @Valid @RequestBody AgentDialogueDtos.CompleteRequest request) {
    var response =
        dialogue.completeDialogue(
            runId,
            nodeRunId,
            new AgentDialogue.CompleteRequest(
                request.requestId(),
                request.expectedRevision(),
                request.summaryRevisionId(),
                request.outputPortId()));
    return ResponseEntity.ok(DialogueApiMapper.map(response));
  }
}
