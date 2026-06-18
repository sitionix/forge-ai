package com.sitionix.forgeai.application.laneexecution.validation;

import com.sitionix.forgeai.domain.port.GitRepositoryPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@LaneStepValidator(value = "gitPreparation", evidence = GitPreparationEvidence.class)
public class GitPreparationEvidenceValidator implements LaneStepEvidenceValidator<GitPreparationEvidence> {

    private static final String BASE_BRANCH = "develop";
    private static final String FEATURE_BRANCH_PREFIX = "feature/";

    private final GitRepositoryPort gitRepositoryPort;

    @Override
    public void validate(final LaneStepValidationContext context, final GitPreparationEvidence evidence) {
        this.requireText(evidence.repository(), "repository");
        this.requireText(evidence.branch(), "branch");
        this.requireText(evidence.baseBranch(), "baseBranch");
        this.requireText(evidence.headCommit(), "headCommit");
        if (!evidence.headCommit().matches("[0-9a-fA-F]{7,40}")) {
            throw new IllegalArgumentException("Preparation evidence headCommit must be a git commit hash");
        }
        if (!Boolean.TRUE.equals(evidence.clean())) {
            throw new IllegalArgumentException("Preparation evidence must report a clean repository");
        }

        final Path expectedRepository = this.normalizeExistingDirectory(context.workspace().cwd(), "workspace cwd");
        final Path evidenceRepository = this.normalizeExistingDirectory(evidence.repository(), "evidence repository");
        if (!Objects.equals(expectedRepository, evidenceRepository)) {
            throw new IllegalArgumentException("Preparation repository mismatch: expected="
                    + expectedRepository + ", actual=" + evidenceRepository);
        }

        final String expectedBranch = this.expectedBranch(context);
        if (!Objects.equals(expectedBranch, evidence.branch())) {
            throw new IllegalArgumentException("Preparation branch mismatch: expected="
                    + expectedBranch + ", actual=" + evidence.branch());
        }
        if (!Objects.equals(BASE_BRANCH, evidence.baseBranch())) {
            throw new IllegalArgumentException("Preparation base branch must be " + BASE_BRANCH);
        }

        this.validateGitState(expectedRepository, evidence);
    }

    private void validateGitState(final Path repository, final GitPreparationEvidence evidence) {
        final String currentBranch = this.gitRepositoryPort.currentBranch(repository).trim();
        if (!Objects.equals(evidence.branch(), currentBranch)) {
            throw new IllegalArgumentException("Actual git branch mismatch: expected="
                    + evidence.branch() + ", actual=" + currentBranch);
        }

        final String actualHead = this.gitRepositoryPort.headCommit(repository).trim();
        if (actualHead.isBlank() || !actualHead.startsWith(evidence.headCommit())) {
            throw new IllegalArgumentException("Actual git HEAD mismatch for preparation evidence");
        }

        final String status = this.gitRepositoryPort.statusPorcelain(repository).trim();
        if (!status.isBlank()) {
            throw new IllegalArgumentException("Preparation repository is not clean");
        }

        final String baseRef = this.baseRef(repository);
        if (!this.gitRepositoryPort.isAncestor(repository, baseRef, "HEAD")) {
            throw new IllegalArgumentException("Preparation branch does not contain latest available " + baseRef);
        }
    }

    private String baseRef(final Path repository) {
        if (this.gitRepositoryPort.refExists(repository, "origin/" + BASE_BRANCH + "^{commit}")) {
            return "origin/" + BASE_BRANCH;
        }
        if (this.gitRepositoryPort.refExists(repository, BASE_BRANCH + "^{commit}")) {
            return BASE_BRANCH;
        }
        throw new IllegalArgumentException("Preparation base branch is unavailable: " + BASE_BRANCH);
    }

    private String expectedBranch(final LaneStepValidationContext context) {
        final String ticketKey = context.lane().getTicketKey();
        if (ticketKey == null || ticketKey.isBlank()) {
            return FEATURE_BRANCH_PREFIX + context.lane().getTicketId();
        }
        return FEATURE_BRANCH_PREFIX + ticketKey;
    }

    private Path normalizeExistingDirectory(final String rawPath, final String label) {
        final Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Preparation " + label + " is not a directory: " + path);
        }
        try {
            return path.toRealPath();
        } catch (final IOException ex) {
            throw new IllegalArgumentException("Preparation " + label + " cannot be resolved: " + path, ex);
        }
    }

    private void requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Preparation evidence field is required: " + field);
        }
    }

}
