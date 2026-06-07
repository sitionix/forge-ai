package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;

public interface ResolveCodexLaneWorkspace {

    CodexLaneWorkspace resolve(ReadyToStartLane lane);
}
