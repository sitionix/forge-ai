package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import java.util.List;

public interface WorkflowEntrySelector {

    List<RunNode> selectEntries(WorkflowRunGraph graph);
}
