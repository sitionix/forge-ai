package com.sitionix.forgeai.application.laneexecution.validation;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.GitRepositoryPort;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPreparationEvidenceValidatorTest {

    @TempDir
    private Path repository;

    private final FakeGitRepositoryPort gitRepositoryPort = new FakeGitRepositoryPort();
    private final GitPreparationEvidenceValidator validator = new GitPreparationEvidenceValidator(this.gitRepositoryPort);

    @Test
    void givenValidPreparationEvidence_whenValidate_thenPass() {
        this.validator.validate(this.context(), new GitPreparationEvidence(
                this.repository.toString(),
                "feature/SITIONIX-1",
                "develop",
                this.gitRepositoryPort.headCommit(this.repository).trim(),
                true
        ));
    }

    @Test
    void givenDirtyRepository_whenValidate_thenReject() {
        this.gitRepositoryPort.statusPorcelain = " M README.md\n";

        assertThatThrownBy(() -> this.validator.validate(this.context(), new GitPreparationEvidence(
                this.repository.toString(),
                "feature/SITIONIX-1",
                "develop",
                this.gitRepositoryPort.headCommit(this.repository).trim(),
                true
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not clean");
    }

    @Test
    void givenWrongBranchEvidence_whenValidate_thenReject() {
        assertThatThrownBy(() -> this.validator.validate(this.context(), new GitPreparationEvidence(
                this.repository.toString(),
                "feature/OTHER",
                "develop",
                this.gitRepositoryPort.headCommit(this.repository).trim(),
                true
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch mismatch");
    }

    private LaneStepValidationContext context() {
        final LaneStrategyStep step = LaneStrategyStep.builder()
                .id("preparation")
                .title("Preparation")
                .order(1)
                .validator("gitPreparation")
                .instructionRefs(List.of())
                .build();
        return new LaneStepValidationContext(
                ReadyToStartLane.builder()
                        .ticketId(UUID.randomUUID())
                        .ticketKey("SITIONIX-1")
                        .laneId(UUID.randomUUID())
                        .agent(Agent.API)
                        .scope("GLOBAL")
                        .serviceId("global")
                        .attempt(1)
                        .build(),
                LaneStrategy.builder()
                        .agentId("api")
                        .version(1)
                        .sessionMode("single_session")
                        .steps(List.of(step))
                        .build(),
                step,
                new CodexLaneWorkspace(this.repository.toString(), List.of(this.repository.toString())),
                UUID.randomUUID(),
                "session"
        );
    }

    private static final class FakeGitRepositoryPort implements GitRepositoryPort {

        private String statusPorcelain = "";

        @Override
        public boolean isInsideWorkTree(final Path repository) {
            return true;
        }

        @Override
        public String currentBranch(final Path repository) {
            return "feature/SITIONIX-1\n";
        }

        @Override
        public String headCommit(final Path repository) {
            return "1234567890abcdef1234567890abcdef12345678\n";
        }

        @Override
        public String statusPorcelain(final Path repository) {
            return this.statusPorcelain;
        }

        @Override
        public String defaultBranch(final Path repository, final List<String> branchCandidates) {
            return "develop";
        }

        @Override
        public boolean refExists(final Path repository, final String ref) {
            return "develop^{commit}".equals(ref);
        }

        @Override
        public boolean isAncestor(final Path repository, final String ancestorRef, final String descendantRef) {
            return "develop".equals(ancestorRef) && "HEAD".equals(descendantRef);
        }

        @Override
        public void clone(final String cloneUrl, final Path targetDirectory) {
        }

        @Override
        public void addAll(final Path repository) {
        }

        @Override
        public void commit(final Path repository, final String userName, final String userEmail, final String message) {
        }

        @Override
        public void stash(final Path repository, final String message) {
        }

        @Override
        public void fetch(final Path repository, final String remote, final String branch) {
        }

        @Override
        public void checkout(final Path repository, final String branch) {
        }

        @Override
        public void pullFastForwardOnly(final Path repository, final String remote, final String branch) {
        }
    }
}
