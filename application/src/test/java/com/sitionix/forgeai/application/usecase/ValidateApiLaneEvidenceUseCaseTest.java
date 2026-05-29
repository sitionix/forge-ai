package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateApiLaneEvidenceUseCaseTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private GithubEvidencePort githubEvidencePort;

    private ValidateApiLaneEvidenceUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new ValidateApiLaneEvidenceUseCase(
                this.ticketRepository,
                this.githubEvidencePort
        );
        lenient().when(this.githubEvidencePort.checkPullRequest(anyString())).thenReturn(
                GithubPullRequestCheckResult.builder().status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
        lenient().when(this.githubEvidencePort.checkWorkflowRun(anyString(), anyLong())).thenReturn(
                GithubWorkflowRunCheckResult.builder().runId(1L).status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
        lenient().when(this.githubEvidencePort.checkRepository(anyString())).thenReturn(
                GithubRepositoryCheckResult.builder().status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
    }

    @Test
    void givenMissingRequiredScopeEvidence_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("automationservice-sox").runId(1L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("missing generated dependency evidence");
    }

    @Test
    void givenRequiredScopeEvidencePresentWithExtraScopes_whenValidate_thenPass() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(
                        ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build(),
                        ApiLaneEvidenceDependency.builder().scope("automationservice-sox").runId(12L).build()
                ))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatCode(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence)).doesNotThrowAnyException();
    }

    @Test
    void givenArchitectRequiredPresentButCallbackScopeMissingInEvidence_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(
                        ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()
                ))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("automationservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("missing generated dependency evidence");
    }

    @Test
    void givenArchitectScopeIsGlobal_whenValidate_thenGlobalIsNotRequiredDependency() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatCode(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .doesNotThrowAnyException();
    }

    @Test
    void givenRepositoryWithoutOwner_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("repository has invalid format");
    }

    @Test
    void givenRepositoryDoesNotExist_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/not-existing-repo")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.githubEvidencePort.checkRepository("sitionix/not-existing-repo"))
                .thenReturn(GithubRepositoryCheckResult.builder().status(GithubCheckStatus.NOT_FOUND).details("not found").build());

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("repository not found");
    }
}
