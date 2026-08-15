package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NodeRunCompletionWorker {

    private final NodeRunRepository nodeRunRepository;
    private final NodeRunCompletionProcessor processor;

    public void poll() {
        for (final UUID nodeRunId : this.nodeRunRepository.findSuccessfulUnroutedIds()) {
            this.processor.process(nodeRunId);
        }
    }
}
