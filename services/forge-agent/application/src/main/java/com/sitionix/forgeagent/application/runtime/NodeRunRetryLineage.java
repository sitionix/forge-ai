package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class NodeRunRetryLineage {

    private NodeRunRetryLineage() {
    }

    public static List<NodeRun> currentLeaves(final List<NodeRun> nodeRuns) {
        final Set<UUID> superseded = new HashSet<>();
        nodeRuns.stream().map(NodeRun::retryOfNodeRunId).filter(Objects::nonNull).forEach(superseded::add);
        return nodeRuns.stream().filter(nodeRun -> !superseded.contains(nodeRun.id())).toList();
    }
}
