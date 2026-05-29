package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ValidateApiLaneEvidenceUseCase implements ValidateApiLaneEvidence {
    private static final Pattern GITHUB_REPOSITORY_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    private final TicketRepository ticketRepository;
    private final AgentTicketRepository agentTicketRepository;
    private final GithubEvidencePort githubEvidencePort;

    public ValidateApiLaneEvidenceUseCase(final TicketRepository ticketRepository,
                                          final AgentTicketRepository agentTicketRepository,
                                          final GithubEvidencePort githubEvidencePort) {
        this.ticketRepository = ticketRepository;
        this.agentTicketRepository = agentTicketRepository;
        this.githubEvidencePort = githubEvidencePort;
    }

    @Override
    public void validate(final UUID laneId, final Set<String> callbackContractScopes, final ApiLaneEvidencePayload evidencePayload) {
        final Lane lane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new ApiLaneEvidenceValidationException(
                        "api_evidence_lane_not_found",
                        "API evidence validation failed: lane not found for laneId=" + laneId,
                        "Retry lane execution after lane is created and in progress."
                ));
        final Set<String> architectRequiredScopes = this.requiredScopesFromArchitectApiTasks(lane);
        final Set<String> callbackScopes = callbackContractScopes == null
                ? Set.of()
                : callbackContractScopes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> requiredScopes = new LinkedHashSet<>();
        requiredScopes.addAll(architectRequiredScopes);
        requiredScopes.addAll(callbackScopes);
        if (requiredScopes.isEmpty()) {
            return;
        }

        final String prUrl = evidencePayload == null ? null : evidencePayload.prUrl();
        if (prUrl == null || prUrl.isBlank()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_pr_missing",
                    "API evidence validation failed: PR URL is missing for required API scopes=" + requiredScopes,
                    "Create/update app-afesox PR and provide prUrl from that PR."
            );
        }
        final GithubCheckStatus prCheckStatus = this.githubEvidencePort.checkPullRequest(prUrl).status();
        if (GithubCheckStatus.NOT_FOUND.equals(prCheckStatus)) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_pr_not_found",
                    "API evidence validation failed: pull request not found by URL=" + prUrl,
                    "Create PR in app-afesox and provide valid prUrl."
            );
        }

        final String repository = evidencePayload == null ? null : evidencePayload.repo();
        if (repository == null || repository.isBlank()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_missing",
                    "API evidence validation failed: repository is missing for required API scopes=" + requiredScopes,
                    "Set repo in owner/repo format (for example: sitionix/app-afesox)."
            );
        }
        final String normalizedRepository = repository.trim();
        if (!GITHUB_REPOSITORY_PATTERN.matcher(normalizedRepository).matches()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_format_invalid",
                    "API evidence validation failed: repository has invalid format=" + normalizedRepository,
                    "Set repo in owner/repo format (for example: sitionix/app-afesox)."
            );
        }
        final GithubCheckStatus repositoryCheckStatus = this.githubEvidencePort.checkRepository(normalizedRepository).status();
        if (GithubCheckStatus.NOT_FOUND.equals(repositoryCheckStatus)) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_not_found",
                    "API evidence validation failed: repository not found=" + normalizedRepository,
                    "Create or use an existing GitHub repository and set repo to that owner/repo value."
            );
        }

        final List<ApiLaneEvidenceDependency> dependencies = evidencePayload.dependencies() == null
                ? List.of()
                : evidencePayload.dependencies();
        final Set<String> providedScopes = dependencies.stream()
                .filter(Objects::nonNull)
                .map(ApiLaneEvidenceDependency::scope)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final Set<String> missingScopes = requiredScopes.stream()
                .filter(scope -> !providedScopes.contains(scope))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingScopes.isEmpty()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_dependency_missing",
                    "API evidence validation failed: missing generated dependency evidence for required scopes="
                            + missingScopes + ", requiredScopes=" + requiredScopes,
                    "Run /generate for required contract targets and include runId in contracts[].artifacts[] for each required scope."
            );
        }

        final Set<String> scopesWithInvalidRunId = dependencies.stream()
                .filter(Objects::nonNull)
                .filter(value -> requiredScopes.contains(value.scope()))
                .filter(value -> value.runId() == null || value.runId() <= 0L)
                .map(ApiLaneEvidenceDependency::scope)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!scopesWithInvalidRunId.isEmpty()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_run_id_missing",
                    "API evidence validation failed: invalid or missing runId for required scopes=" + scopesWithInvalidRunId,
                    "Use GitHub Actions run ID from successful /generate workflow and set contracts[].artifacts[].runId."
            );
        }
        final Set<String> unknownRuns = dependencies.stream()
                .filter(Objects::nonNull)
                .filter(value -> requiredScopes.contains(value.scope()))
                .filter(value -> value.runId() != null && value.runId() > 0L)
                .filter(value -> GithubCheckStatus.NOT_FOUND.equals(this.githubEvidencePort.checkWorkflowRun(normalizedRepository, value.runId()).status()))
                .map(ApiLaneEvidenceDependency::scope)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unknownRuns.isEmpty()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_run_not_found",
                    "API evidence validation failed: workflow run not found for required scopes=" + unknownRuns,
                    "Run /generate and provide existing GitHub Actions runId for each required scope."
            );
        }
    }

    private Set<String> requiredScopesFromArchitectApiTasks(final Lane lane) {
        if (lane.getInputTaskIds() == null || lane.getInputTaskIds().isEmpty()) {
            return Set.of();
        }
        return lane.getInputTaskIds().stream()
                .map(inputTaskId -> this.agentTicketRepository.findById(inputTaskId, ApiPayload.class))
                .flatMap(Optional::stream)
                .map(AgentTicket::getPayload)
                .filter(Objects::nonNull)
                .filter(payload -> Boolean.TRUE.equals(payload.getRequired()))
                .map(ApiPayload::getScope)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .filter(scope -> !"GLOBAL".equalsIgnoreCase(scope))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
