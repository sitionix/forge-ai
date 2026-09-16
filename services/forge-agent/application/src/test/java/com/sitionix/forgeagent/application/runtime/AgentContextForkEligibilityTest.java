package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AgentContextForkEligibilityTest {
    @Test void acceptsOnlyLatestSuccessfulTurnAndIdleCurrentContext() {
        var session = session(AgentExecutionSessionStatus.IDLE, null);
        assertThat(AgentContextForkEligibility.reason(session, turn(AgentExecutionTurnStatus.SUCCEEDED), false, false)).isNull();
        for (var state : AgentExecutionTurnStatus.values()) {
            if (state == AgentExecutionTurnStatus.SUCCEEDED) continue;
            assertThat(AgentContextForkEligibility.reason(session, turn(state), false, false)).isEqualTo(AgentContextForkEligibility.NOT_ALLOWED);
        }
        assertThat(AgentContextForkEligibility.reason(session, null, false, false)).isEqualTo(AgentContextForkEligibility.NOT_ALLOWED);
        assertThat(AgentContextForkEligibility.reason(session, turn(AgentExecutionTurnStatus.SUCCEEDED), true, false)).isEqualTo(AgentContextForkEligibility.BUSY);
        assertThat(AgentContextForkEligibility.reason(session, turn(AgentExecutionTurnStatus.SUCCEEDED), false, true)).isEqualTo(AgentContextForkEligibility.NOT_ALLOWED);
        assertThat(AgentContextForkEligibility.reason(session(AgentExecutionSessionStatus.FORKING, null), turn(AgentExecutionTurnStatus.SUCCEEDED), false, false)).isEqualTo(AgentContextForkEligibility.BUSY);
        assertThat(AgentContextResetEligibility.reason(session(AgentExecutionSessionStatus.FORKING, null), false)).isEqualTo(AgentContextResetEligibility.BUSY);
        assertThat(AgentContextResetEligibility.reason(session(AgentExecutionSessionStatus.IDLE, Instant.now()), false)).isEqualTo(AgentContextResetEligibility.NOT_ALLOWED);
    }
    private AgentExecutionSession session(AgentExecutionSessionStatus status, Instant forkedAt) {
        var now=Instant.now();
        return new AgentExecutionSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                "codex","thread","0.154.0",NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,status,null,null,null,1,null,null,null,
                now,now,null,null,null,null,forkedAt,null,null);
    }
    private AgentExecutionTurn turn(AgentExecutionTurnStatus status) {
        return new AgentExecutionTurn(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"provider-turn",1,status,
                null,null,null,null,null,null,null,Instant.now(),Instant.now());
    }
}
