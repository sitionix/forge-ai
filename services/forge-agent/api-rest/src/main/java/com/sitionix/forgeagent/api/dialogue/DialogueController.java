package com.sitionix.forgeagent.api.dialogue;

import com.sitionix.forgeagent.application.dialogue.DialogueCommands;
import com.sitionix.forgeagent.application.dialogue.DialogueQueries;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workflow-runs/{runId}/node-runs/{nodeRunId}/dialogue")
public class DialogueController {
    private final DialogueCommands commands;
    private final DialogueQueries queries;
    private final DialogueApiMapper mapper;

    @GetMapping
    public DialogueApiDtos.State get(@PathVariable UUID runId, @PathVariable UUID nodeRunId) {
        return mapper.response(queries.read(runId,nodeRunId));
    }
    @GetMapping("/messages")
    public DialogueApiDtos.MessagePage messages(@PathVariable UUID runId, @PathVariable UUID nodeRunId,
                                              @RequestParam(defaultValue="0") long afterSequence,
                                              @RequestParam(defaultValue="100") int limit) {
        return mapper.page(commands.messages(runId,nodeRunId,afterSequence,limit));
    }
    @PostMapping("/messages")
    public ResponseEntity<DialogueApiDtos.State> send(@PathVariable UUID runId, @PathVariable UUID nodeRunId,
                                                    @Valid @RequestBody DialogueApiDtos.SendRequest request) {
        var receipt = commands.sendReceipt(runId,nodeRunId,request.requestId(),request.expectedRevision(),request.text());
        return ResponseEntity.status(receipt.duplicate() ? 200 : 202).body(mapper.response(queries.read(runId,nodeRunId)));
    }
    @PostMapping("/summary")
    public ResponseEntity<DialogueApiDtos.State> summary(@PathVariable UUID runId, @PathVariable UUID nodeRunId,
                                                       @Valid @RequestBody DialogueApiDtos.SummaryRequest request) {
        var receipt = commands.summarizeReceipt(runId,nodeRunId,request.requestId(),request.expectedRevision());
        return ResponseEntity.status(receipt.duplicate() ? 200 : 202).body(mapper.response(queries.read(runId,nodeRunId)));
    }
    @PostMapping("/complete")
    public DialogueApiDtos.State complete(@PathVariable UUID runId, @PathVariable UUID nodeRunId,
                                         @Valid @RequestBody DialogueApiDtos.CompleteRequest request) {
        commands.complete(runId,nodeRunId,request.requestId(),request.expectedRevision(),request.summaryRevisionId(),request.outputPortId());
        return mapper.response(queries.read(runId,nodeRunId));
    }
}
