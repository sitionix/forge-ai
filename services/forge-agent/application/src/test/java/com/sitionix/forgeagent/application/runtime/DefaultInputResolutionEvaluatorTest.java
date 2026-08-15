package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultInputResolutionEvaluatorTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    private final DefaultInputResolutionEvaluator evaluator = new DefaultInputResolutionEvaluator(new InputResolutionRuleRegistry(List.of(
            new OpenParticipationWaitRule(),
            new ResolvedEmptyCloseRule(),
            new ResolvedDeliveredActivateRule()
    )));

    @Test
    void waitsWhileAnyRelevantPathCanStillContribute() {
        final ActivationDecision decision = this.evaluator.evaluate(new InputParticipation(RUN_ID, FRAME_ID, INPUT_ID, true, List.of(this.delivered())));

        assertThat(decision).isInstanceOf(WaitActivationDecision.class);
    }

    @Test
    void closesResolvedEmptyActivation() {
        final ActivationDecision decision = this.evaluator.evaluate(new InputParticipation(RUN_ID, FRAME_ID, INPUT_ID, false, List.of()));

        assertThat(decision)
                .isInstanceOfSatisfying(CloseActivationDecision.class, close -> {
                    assertThat(close.workflowRunId()).isEqualTo(RUN_ID);
                    assertThat(close.activationFrameId()).isEqualTo(FRAME_ID);
                    assertThat(close.targetInputPortId()).isEqualTo(INPUT_ID);
                });
    }

    @Test
    void activatesResolvedDeliveredContributionsTogether() {
        final ConnectionResolution first = this.delivered();
        final ConnectionResolution second = this.delivered();

        final ActivationDecision decision = this.evaluator.evaluate(new InputParticipation(RUN_ID, FRAME_ID, INPUT_ID, false, List.of(first, second)));

        assertThat(decision)
                .isInstanceOfSatisfying(ActivateNodeDecision.class, activate -> {
                    assertThat(activate.workflowRunId()).isEqualTo(RUN_ID);
                    assertThat(activate.activationFrameId()).isEqualTo(FRAME_ID);
                    assertThat(activate.targetInputPortId()).isEqualTo(INPUT_ID);
                    assertThat(activate.delivered()).containsExactly(first, second);
                });
    }

    private ConnectionResolution delivered() {
        return new ConnectionResolution(
                UUID.randomUUID(),
                RUN_ID,
                FRAME_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                INPUT_ID,
                ConnectionResolutionType.DELIVERED,
                new NodeRunOutput("{\"feedback\":\"review\"}"),
                null,
                Instant.parse("2026-08-15T00:00:00Z")
        );
    }
}
