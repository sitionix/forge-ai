package com.sitionix.forgeagent.application.runtime;

import java.util.List;

public interface NodeInputContentPolicy {

    boolean supports(NodeInputContentContext context);

    NodeExecutionInputContent assemble(NodeInputContentContext context);
}
