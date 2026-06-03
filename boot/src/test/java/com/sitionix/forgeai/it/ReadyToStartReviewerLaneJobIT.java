package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReadyToStartReviewerLaneJobIT {

    @Autowired
    private TestManager testManager;
    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Test
    @DisplayName("Should execute reviewer lane by scheduler job")
    void givenReadyReviewerLane_whenSchedulerRuns_thenExecuteReviewerLane() {
        //given
        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("readyToStartReviewerJobSeedTicket.json");

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1);

        //when
        this.readyToStartLaneJob.run();

        //then
        final TicketDocument actual = this.awaitResolvedTicket(Duration.ofSeconds(15));

        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .containsWithJsonsStrict("expectedReadyToStartReviewerLaneJobTicket.json");
        assertThat(actual.getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    private TicketDocument awaitResolvedTicket(final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final TicketDocument actual = this.testManager.mongo()
                    .get(TicketDocument.class)
                    .hasSize(1)
                    .singleElement()
                    .assertEntity();
            if (TicketStatus.RESOLVED.equals(actual.getStatus())) {
                return actual;
            }
            sleepBriefly();
        }
        fail("Reviewer lane did not resolve ticket within %s".formatted(timeout));
        return null;
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for reviewer lane completion");
        }
    }
}
