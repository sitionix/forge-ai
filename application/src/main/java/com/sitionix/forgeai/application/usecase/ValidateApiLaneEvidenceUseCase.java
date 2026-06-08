package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import com.sitionix.forgeai.domain.props.AgentConfigView;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.props.ContractRefView;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ValidateApiLaneEvidenceUseCase implements ValidateApiLaneEvidence {
    private static final Pattern GITHUB_REPOSITORY_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern GITHUB_PULL_REQUEST_URL_PATTERN = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/[0-9]+(?:[/?#].*)?$"
    );

    private final TicketRepository ticketRepository;
    private final GithubEvidencePort githubEvidencePort;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final AgentPropertiesProvider agentPropertiesProvider;

    public ValidateApiLaneEvidenceUseCase(final TicketRepository ticketRepository,
                                          final GithubEvidencePort githubEvidencePort,
                                          final ServicePropertiesProvider servicePropertiesProvider,
                                          final AgentPropertiesProvider agentPropertiesProvider) {
        this.ticketRepository = ticketRepository;
        this.githubEvidencePort = githubEvidencePort;
        this.servicePropertiesProvider = servicePropertiesProvider;
        this.agentPropertiesProvider = agentPropertiesProvider;
    }

    @Override
    public void validate(final UUID laneId, final Set<String> contractScopes, final ApiLaneEvidencePayload evidencePayload) {
        final Lane lane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new ApiLaneEvidenceValidationException(
                        "api_evidence_lane_not_found",
                        "API evidence validation failed: lane not found for laneId=" + laneId,
                        "Retry lane execution after lane is created and in progress."
                ));
        final UUID resolvedLaneId = lane.getId();
        if (resolvedLaneId == null) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_lane_not_found",
                    "API evidence validation failed: lane is invalid for laneId=" + laneId,
                    "Retry lane execution after lane is created and in progress."
            );
        }
        final Set<String> normalizedContractScopes = contractScopes == null
                ? Set.of()
                : contractScopes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> requiredScopes = normalizedContractScopes;
        if (requiredScopes.isEmpty()) {
            return;
        }
        final Set<String> expectedRepositories = this.expectedContractRepositories(requiredScopes);

        final String prUrl = evidencePayload == null ? null : evidencePayload.prUrl();
        if (prUrl == null || prUrl.isBlank()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_pr_missing",
                    "API evidence validation failed: PR URL is missing for required API scopes=" + requiredScopes,
                    this.expectedRepositoryHint("Create/update API contract PR", expectedRepositories)
            );
        }
        this.validatePullRequestRepository(prUrl.trim(), expectedRepositories);
        final GithubCheckStatus prCheckStatus = this.githubEvidencePort.checkPullRequest(prUrl).status();
        if (GithubCheckStatus.NOT_FOUND.equals(prCheckStatus)) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_pr_not_found",
                    "API evidence validation failed: pull request not found by URL=" + prUrl,
                    this.expectedRepositoryHint("Create PR and provide valid prUrl", expectedRepositories)
            );
        }

        final String repository = evidencePayload == null ? null : evidencePayload.repo();
        if (repository == null || repository.isBlank()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_missing",
                    "API evidence validation failed: repository is missing for required API scopes=" + requiredScopes,
                    this.expectedRepositoryHint("Set repo in owner/repo format", expectedRepositories)
            );
        }
        final String normalizedRepository = repository.trim();
        if (!GITHUB_REPOSITORY_PATTERN.matcher(normalizedRepository).matches()) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_format_invalid",
                    "API evidence validation failed: repository has invalid format=" + normalizedRepository,
                    this.expectedRepositoryHint("Set repo in owner/repo format", expectedRepositories)
            );
        }
        if (!expectedRepositories.isEmpty() && !this.containsRepository(expectedRepositories, normalizedRepository)) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_repo_unexpected",
                    "API evidence validation failed: repository=" + normalizedRepository
                            + " is not configured for required API scopes=" + requiredScopes
                            + ", expectedRepositories=" + expectedRepositories,
                    this.expectedRepositoryHint("Use the API contract repository configured in services.yaml", expectedRepositories)
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

    private void validatePullRequestRepository(final String prUrl, final Set<String> expectedRepositories) {
        if (expectedRepositories.isEmpty()) {
            return;
        }
        final Matcher matcher = GITHUB_PULL_REQUEST_URL_PATTERN.matcher(prUrl);
        if (!matcher.matches()) {
            return;
        }
        final String prRepository = matcher.group(1) + "/" + matcher.group(2);
        if (!this.containsRepository(expectedRepositories, prRepository)) {
            throw new ApiLaneEvidenceValidationException(
                    "api_evidence_pr_repo_unexpected",
                    "API evidence validation failed: PR repository=" + prRepository
                            + " is not configured for API evidence, expectedRepositories=" + expectedRepositories,
                    this.expectedRepositoryHint("Use PR from the API contract repository configured in services.yaml", expectedRepositories)
            );
        }
    }

    private Set<String> expectedContractRepositories(final Set<String> requiredScopes) {
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            return Set.of();
        }
        final Set<String> repositories = new LinkedHashSet<>();
        this.services().forEach((serviceId, service) -> {
            if (service == null || !this.scopeMatches(requiredScopes, serviceId, service)) {
                return;
            }
            this.apiContractRef(service)
                    .flatMap(ref -> this.expectedRepository(service, ref))
                    .ifPresent(repositories::add);
        });
        return repositories;
    }

    private boolean scopeMatches(final Set<String> requiredScopes,
                                 final String serviceId,
                                 final ServiceConfigView service) {
        return requiredScopes.contains(serviceId) || requiredScopes.contains(service.getPath());
    }

    private Optional<ContractRefView> apiContractRef(
            final ServiceConfigView service
    ) {
        if (service.getContractRefs() == null) {
            return Optional.empty();
        }
        return this.workspaceContractRef(Agent.API)
                .flatMap(refKey -> Optional.ofNullable(service.getContractRefs().get(refKey)));
    }

    private Optional<String> workspaceContractRef(final Agent agent) {
        if (agent == null || this.agentPropertiesProvider.getAgents() == null) {
            return Optional.empty();
        }
        return this.agentPropertiesProvider.getAgents().stream()
                .filter(config -> config != null && Objects.equals(config.getId(), agent.getId()))
                .findFirst()
                .flatMap(AgentConfigView::getWorkspaceContractRef)
                .filter(this::hasText);
    }

    private Optional<String> expectedRepository(final ServiceConfigView service,
                                                final ContractRefView ref) {
        if (ref == null || !this.hasText(ref.getSourceRepo())) {
            return Optional.empty();
        }
        final String sourceRepo = ref.getSourceRepo().trim();
        if (GITHUB_REPOSITORY_PATTERN.matcher(sourceRepo).matches()) {
            return Optional.of(sourceRepo);
        }
        return this.deployOwner(service)
                .map(owner -> owner + "/" + this.repoName(sourceRepo));
    }

    private Optional<String> deployOwner(final ServiceConfigView service) {
        if (service == null || service.getDeploy() == null || !this.hasText(service.getDeploy().getRepo())) {
            return Optional.empty();
        }
        final String repo = service.getDeploy().getRepo().trim();
        final int separator = repo.indexOf('/');
        return separator > 0 ? Optional.of(repo.substring(0, separator)) : Optional.empty();
    }

    private String repoName(final String sourceRepo) {
        final Path path = Path.of(sourceRepo);
        final Path fileName = path.getFileName();
        return fileName == null ? sourceRepo : fileName.toString();
    }

    private String expectedRepositoryHint(final String action, final Set<String> expectedRepositories) {
        if (expectedRepositories == null || expectedRepositories.isEmpty()) {
            return action + ".";
        }
        return action + ": " + expectedRepositories + ".";
    }

    private boolean containsRepository(final Set<String> expectedRepositories, final String actualRepository) {
        final String normalizedActualRepository = this.normalizeRepository(actualRepository);
        return expectedRepositories.stream()
                .map(this::normalizeRepository)
                .anyMatch(expected -> Objects.equals(expected, normalizedActualRepository));
    }

    private String normalizeRepository(final String repository) {
        return repository == null ? "" : repository.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, ServiceConfigView> services() {
        final Map<String, ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        return services == null ? Collections.emptyMap() : services;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
