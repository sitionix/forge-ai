package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.InputParticipation;
import com.sitionix.forgeagent.application.runtime.InputParticipationResolver;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.usecase.CreateProjectTaskCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.ProjectTaskUseCases;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.InputActivationResolutionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

@IntegrationTest
class ForgeAgentScopedExecutionIT {

    private static final UUID A = uuid(1);
    private static final UUID B = uuid(2);
    private static final UUID C = uuid(3);
    private static final UUID D = uuid(4);
    private static final UUID X = uuid(5);
    private static final UUID IMPLEMENTER = uuid(6);
    private static final UUID REVIEWER = uuid(7);
    private static final UUID FINAL = uuid(8);

    private static final UUID A_IN = input(1);
    private static final UUID A_REVIEW_IN = input(9);
    private static final UUID A_OUT = output(1);
    private static final UUID A_OTHER = output(9);
    private static final UUID B_IN = input(2);
    private static final UUID B_OUT = output(2);
    private static final UUID B_OTHER = output(12);
    private static final UUID C_IN = input(3);
    private static final UUID C_OUT = output(3);
    private static final UUID D_IN = input(4);
    private static final UUID D_OUT = output(4);
    private static final UUID X_IN = input(5);
    private static final UUID X_OUT = output(5);
    private static final UUID IMPLEMENTER_INITIAL_IN = input(6);
    private static final UUID IMPLEMENTER_REVIEW_IN = input(16);
    private static final UUID IMPLEMENTER_OUT = output(6);
    private static final UUID REVIEWER_IN = input(7);
    private static final UUID REVIEWER_PASS = output(7);
    private static final UUID REVIEWER_RETURN = output(17);
    private static final UUID FINAL_IN = input(8);
    private static final UUID FINAL_OUT = output(8);

    private static final UUID REPOSITORY_A = repository(1);
    private static final UUID REPOSITORY_B = repository(2);
    private static final UUID REPOSITORY_C = repository(3);

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private WorkflowUseCases workflowUseCases;
    @Autowired
    private ProjectTaskUseCases projectTaskUseCases;
    @Autowired
    private WorkflowRunUseCases workflowRunUseCases;
    @Autowired
    private NodeRunLifecycle lifecycle;
    @Autowired
    private NodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowRunRepository workflowRunRepository;
    @Autowired
    private ConnectionResolutionRepository resolutionRepository;
    @Autowired
    private ExecutionFrameRepository frameRepository;
    @Autowired
    private InputActivationResolutionRepository activationResolutionRepository;
    @Autowired
    private InputParticipationResolver participationResolver;

    @MockBean
    private OutputSelector outputSelector;

    @AfterEach
    void removeRepositoryWorkspaceFixtures() throws IOException {
        final Path projectWorkspace = this.projectWorkspace();
        if (!Files.exists(projectWorkspace)) {
            return;
        }
        try (var paths = Files.walk(projectWorkspace)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void globalToGlobalUsesOneRootFrameAndOneConsumedActivation() {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.GLOBAL));
        final UUID runId = this.createTask(List.of(REPOSITORY_A));
        final UUID frame = this.rootFrame(runId);

        final NodeRun a = this.onlyPending(runId, A);
        this.complete(a, "{\"stage\":\"a\"}");
        final NodeRun b = this.onlyPending(runId, B);
        assertThat(a.repositoryId()).isNull();
        assertThat(b.repositoryId()).isNull();
        assertThat(b.executionFrameId()).isEqualTo(frame);
        assertThat(b.activationFrameId()).isEqualTo(frame);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(b.id())).singleElement()
                .satisfies(resolution -> assertThat(resolution.targetRepositoryId()).isNull());
        this.complete(b, "{\"stage\":\"b\"}");

        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.runtimeGraph().nodes()).hasSize(2);
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(b.id());
        this.assertTerminalQuiescence(runId);
    }

    @ParameterizedTest(name = "repository count {0}")
    @ValueSource(ints = {1, 2})
    void globalFanOutCreatesOneInvocationPerSelectedRepository(final int repositoryCount) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(repositoryCount);
        final UUID runId = this.createTask(repositories);
        final UUID frame = this.rootFrame(runId);

        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final List<NodeRun> scoped = this.nodeRuns(runId, B);
        assertThat(scoped).extracting(NodeRun::repositoryId).containsExactlyInAnyOrderElementsOf(repositories);
        assertThat(scoped).extracting(NodeRun::executionFrameId).containsOnly(frame);
        assertThat(scoped).extracting(NodeRun::activationFrameId).containsOnly(frame);

        this.completeWithWorkspaceAssertion(scoped.getFirst(), "{\"repository\":\"first\"}");
        if (repositoryCount == 2) {
            assertThat(this.nodeRuns(runId, C)).isEmpty();
            this.completeWithWorkspaceAssertion(scoped.get(1), "{\"repository\":\"second\"}");
        }
        final NodeRun global = this.onlyPending(runId, C);
        final NodeExecutionClaim claim = this.lifecycle.tryStart(global.id()).orElseThrow();
        assertThat(claim.executionWorkspace().cwd()).isEqualTo(this.projectWorkspace());
        assertThat(claim.executionWorkspace().workspaceRoots())
                .containsExactlyElementsOf(repositories.stream().map(this::repositoryWorkspace).toList());
        assertThat(claim.executionWorkspace().cwd()).isNotIn(claim.executionWorkspace().workspaceRoots());
        assertThat(claim.inputEnvelope().contributions()).hasSize(repositoryCount)
                .extracting(NodeInputContribution::sourceRepositoryId)
                .containsExactlyInAnyOrderElementsOf(repositories);
        this.lifecycle.succeed(global.id(), new NodeRunOutput("{\"stage\":\"c\"}"));

        assertThat(this.nodeRuns(runId, A)).hasSize(1);
        assertThat(this.nodeRuns(runId, B)).hasSize(repositoryCount);
        assertThat(this.nodeRuns(runId, C)).hasSize(1);
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @ParameterizedTest(name = "repository count {0}")
    @ValueSource(ints = {2, 3})
    void perScopeZipNeverCrossesRepositories(final int repositoryCount) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(repositoryCount);
        final UUID runId = this.createTask(repositories);
        final UUID frame = this.rootFrame(runId);
        assertThat(this.workflowRunRepository.findById(runId).orElseThrow().repositoryIds()).containsExactlyElementsOf(repositories);

        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        for (final UUID repositoryId : repositories) {
            this.complete(this.onlyPending(runId, B, repositoryId), "{\"stage\":\"b\"}");
        }
        final List<NodeRun> cRuns = this.nodeRuns(runId, C);
        assertThat(cRuns).hasSize(repositoryCount).extracting(NodeRun::repositoryId)
                .containsExactlyInAnyOrderElementsOf(repositories);
        assertThat(cRuns).extracting(NodeRun::executionFrameId).containsOnly(frame);
        for (final NodeRun c : cRuns) {
            final NodeExecutionClaim claim = this.lifecycle.tryStart(c.id()).orElseThrow();
            assertThat(claim.inputEnvelope().contributions()).singleElement()
                    .satisfies(contribution -> assertThat(contribution.sourceRepositoryId()).isEqualTo(c.repositoryId()));
            assertThat(this.resolutionRepository.findConsumedByNodeRunId(c.id())).singleElement()
                    .satisfies(resolution -> assertThat(resolution.targetRepositoryId()).isEqualTo(c.repositoryId()));
            this.lifecycle.succeed(c.id(), new NodeRunOutput("{\"stage\":\"c\"}"));
        }
        final NodeRun d = this.onlyPending(runId, D);
        final NodeExecutionClaim dClaim = this.lifecycle.tryStart(d.id()).orElseThrow();
        assertThat(dClaim.inputEnvelope().contributions()).hasSize(repositoryCount)
                .extracting(NodeInputContribution::sourceRepositoryId)
                .containsExactlyInAnyOrderElementsOf(repositories);
        this.lifecycle.succeed(d.id(), new NodeRunOutput("{\"stage\":\"d\"}"));

        assertThat(this.nodeRunRepository.findByWorkflowRunId(runId)).hasSize(2 * repositoryCount + 2);
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void perScopeRootCreatesOneRootInvocationPerRepositoryInOneFrame() {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.PER_SCOPE, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(2);
        final UUID runId = this.createTask(repositories);
        final UUID frame = this.rootFrame(runId);

        final List<NodeRun> roots = this.nodeRuns(runId, A);
        assertThat(roots).hasSize(2).extracting(NodeRun::repositoryId).containsExactlyInAnyOrderElementsOf(repositories);
        assertThat(roots).extracting(NodeRun::activationFrameId).containsOnlyNulls();
        assertThat(roots).extracting(NodeRun::executionFrameId).containsOnly(frame);
        roots.forEach(root -> this.complete(root, "{\"stage\":\"a\"}"));
        final List<NodeRun> bRuns = this.nodeRuns(runId, B);
        for (final NodeRun b : bRuns) {
            final NodeExecutionClaim claim = this.lifecycle.tryStart(b.id()).orElseThrow();
            assertThat(claim.inputEnvelope().contributions()).singleElement()
                    .satisfies(contribution -> assertThat(contribution.sourceRepositoryId()).isEqualTo(b.repositoryId()));
            this.lifecycle.succeed(b.id(), new NodeRunOutput("{\"stage\":\"b\"}"));
        }
        final NodeRun c = this.onlyPending(runId, C);
        assertThat(this.lifecycle.tryStart(c.id()).orElseThrow().inputEnvelope().contributions()).hasSize(2);
        this.lifecycle.succeed(c.id(), new NodeRunOutput("{\"stage\":\"c\"}"));
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void globalFanInWaitsWhileScopedParticipantCanStillDeliver() {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final UUID runId = this.createTask(this.repositories(2));
        final UUID frame = this.rootFrame(runId);
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        this.complete(this.onlyPending(runId, B, REPOSITORY_A), "{\"repository\":\"a\"}");

        final InputParticipation waiting = this.participationResolver.resolve(runId, frame, C_IN, null);
        assertThat(waiting.open()).isTrue();
        assertThat(waiting.delivered()).hasSize(1);
        assertThat(this.nodeRuns(runId, C)).isEmpty();

        this.complete(this.onlyPending(runId, B, REPOSITORY_B), "{\"repository\":\"b\"}");
        final NodeRun c = this.onlyPending(runId, C);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(c.id())).hasSize(2);
        this.complete(c, "{\"stage\":\"c\"}");
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
    }

    @ParameterizedTest(name = "closed repository index {0}")
    @ValueSource(ints = {0, 1})
    void perScopeClosedAndDeliveredActivatesGlobalWithDeliveredSubset(final int closedRepositoryIndex) {
        this.seed();
        this.saveScopedRouteWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation ->
                invocation.<NodeRunOutput>getArgument(0).jsonValue().contains("close") ? B_OTHER : B_OUT);
        final List<UUID> repositories = this.repositories(2);
        final UUID runId = this.createTask(repositories);
        final UUID frame = this.rootFrame(runId);
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");

        final UUID closedRepository = repositories.get(closedRepositoryIndex);
        final UUID deliveredRepository = repositories.get(1 - closedRepositoryIndex);
        this.complete(this.onlyPending(runId, B, closedRepository), "{\"route\":\"close\"}");
        final InputParticipation openEmpty = this.participationResolver.resolve(runId, frame, C_IN, null);
        assertThat(openEmpty.open()).isTrue();
        assertThat(openEmpty.delivered()).isEmpty();
        assertThat(this.nodeRuns(runId, C)).isEmpty();

        this.complete(this.onlyPending(runId, B, deliveredRepository), "{\"route\":\"deliver\"}");
        final NodeRun c = this.onlyPending(runId, C);
        final List<ConnectionResolution> consumed = this.resolutionRepository.findConsumedByNodeRunId(c.id());
        assertThat(consumed).singleElement().satisfies(resolution -> {
            assertThat(resolution.type()).isEqualTo(ConnectionResolutionType.DELIVERED);
            assertThat(resolution.payload()).isNotNull();
            assertThat(resolution.targetRepositoryId()).isNull();
        });
        assertThat(this.lifecycle.tryStart(c.id()).orElseThrow().inputEnvelope().contributions()).singleElement()
                .satisfies(contribution -> assertThat(contribution.sourceRepositoryId()).isEqualTo(deliveredRepository));
        this.lifecycle.succeed(c.id(), new NodeRunOutput("{\"stage\":\"c\"}"));
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
    }

    @ParameterizedTest(name = "repository count {0}")
    @ValueSource(ints = {1, 2, 3})
    void globalClosedProjectsOneClosedResolutionPerRepositoryWithoutScopedRuns(final int repositoryCount) {
        this.seed();
        this.saveGlobalClosedToScopedWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(A_OTHER);
        final List<UUID> repositories = this.repositories(repositoryCount);
        final UUID runId = this.createTask(repositories);
        this.complete(this.onlyPending(runId, A), "{\"route\":\"other\"}");

        assertThat(this.nodeRuns(runId, B)).isEmpty();
        final List<ConnectionResolution> closed = this.resolutionRepository.findByWorkflowRunAndFrame(runId, this.rootFrame(runId));
        assertThat(closed).hasSize(repositoryCount).allSatisfy(resolution -> {
            assertThat(resolution.type()).isEqualTo(ConnectionResolutionType.CLOSED);
            assertThat(resolution.payload()).isNull();
        });
        assertThat(closed).extracting(ConnectionResolution::targetRepositoryId)
                .containsExactlyInAnyOrderElementsOf(repositories);
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void scopedReentryUsesOneFramePerLogicalGenerationWithoutFrameOrRepositoryMixing() {
        this.seed();
        this.saveScopedReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation ->
                invocation.<NodeRunOutput>getArgument(0).jsonValue().contains("pass") ? REVIEWER_PASS : REVIEWER_RETURN);
        final UUID runId = this.createTask(this.repositories(2));
        final UUID frameA = this.rootFrame(runId);

        this.completeGeneration(runId, 1, "return");
        final UUID frameB = this.generationFrame(runId, 2);
        this.completeGeneration(runId, 2, "return");
        final UUID frameC = this.generationFrame(runId, 3);
        this.completeGeneration(runId, 3, "pass");
        final NodeRun result = this.onlyPending(runId, FINAL);
        this.complete(result, "{\"result\":\"generation-3\"}");

        assertThat(frameA).isNotEqualTo(frameB).isNotEqualTo(frameC);
        assertThat(frameA).isNotEqualTo(frameC);
        for (final UUID frame : List.of(frameA, frameB, frameC)) {
            final List<NodeRun> generation = this.nodeRuns(runId, IMPLEMENTER).stream()
                    .filter(nodeRun -> frame.equals(nodeRun.executionFrameId())).toList();
            assertThat(generation).hasSize(2).extracting(NodeRun::repositoryId)
                    .containsExactlyInAnyOrder(REPOSITORY_A, REPOSITORY_B);
            assertThat(generation).extracting(NodeRun::executionFrameId).containsOnly(frame);
            generation.forEach(nodeRun -> this.resolutionRepository.findConsumedByNodeRunId(nodeRun.id()).forEach(resolution -> {
                assertThat(resolution.executionFrameId()).isNotEqualTo(frame).isEqualTo(nodeRun.activationFrameId());
                assertThat(resolution.targetRepositoryId()).isEqualTo(nodeRun.repositoryId());
            }));
        }
        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(result.id());
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void guardedSelfLoopWaitsForExternalContributionAndConsumesEachGenerationExactlyOnce() {
        this.seed();
        this.saveGuardedSelfLoopWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation ->
                invocation.<NodeRunOutput>getArgument(0).jsonValue().contains("exit") ? REVIEWER_PASS : REVIEWER_RETURN);
        final UUID runId = this.createTask(this.repositories(1));
        final UUID rootFrame = this.rootFrame(runId);

        final NodeRun reviewerOne = this.onlyPending(runId, REVIEWER, REPOSITORY_A);
        final NodeExecutionClaim reviewerOneClaim = this.lifecycle.tryStart(reviewerOne.id()).orElseThrow();
        assertThat(reviewerOneClaim.inputEnvelope().contributions()).isEmpty();
        assertThat(reviewerOne.repositoryId()).isEqualTo(REPOSITORY_A);
        this.lifecycle.succeed(reviewerOne.id(), new NodeRunOutput("{\"reviewer\":\"one-repeat\"}"));

        assertThat(this.nodeRuns(runId, REVIEWER)).extracting(NodeRun::id).containsExactly(reviewerOne.id());
        final InputParticipation waitingForImplementer = this.participationResolver.resolve(
                runId, rootFrame, REVIEWER_IN, REPOSITORY_A);
        assertThat(waitingForImplementer.open()).isTrue();
        assertThat(waitingForImplementer.delivered()).singleElement()
                .satisfies(resolution -> assertThat(resolution.sourceNodeRunId()).isEqualTo(reviewerOne.id()));
        final NodeRun implementerOne = this.onlyPending(runId, IMPLEMENTER, REPOSITORY_A);
        this.complete(implementerOne, "{\"implementer\":\"one\"}");

        final NodeRun reviewerTwo = this.onlyPending(runId, REVIEWER, REPOSITORY_A);
        assertThat(this.nodeRuns(runId, REVIEWER)).hasSize(2);
        assertThat(reviewerTwo.activationFrameId()).isEqualTo(rootFrame);
        assertThat(reviewerTwo.executionFrameId()).isNotEqualTo(rootFrame);
        assertThat(reviewerTwo.repositoryId()).isEqualTo(REPOSITORY_A);
        final NodeExecutionClaim reviewerTwoClaim = this.lifecycle.tryStart(reviewerTwo.id()).orElseThrow();
        assertThat(reviewerTwoClaim.executionWorkspace().cwd()).isEqualTo(this.repositoryWorkspace(REPOSITORY_A));
        assertThat(reviewerTwoClaim.inputEnvelope().contributions())
                .hasSize(2)
                .extracting(NodeInputContribution::sourceNodeRunId, NodeInputContribution::payload,
                        NodeInputContribution::sourceRepositoryId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(reviewerOne.id(), new NodeRunOutput("{\"reviewer\": \"one-repeat\"}"), REPOSITORY_A),
                        org.assertj.core.groups.Tuple.tuple(implementerOne.id(), new NodeRunOutput("{\"implementer\": \"one\"}"), REPOSITORY_A)
                );
        final List<ConnectionResolution> generationOne = this.resolutionRepository.findConsumedByNodeRunId(reviewerTwo.id());
        assertThat(generationOne).hasSize(2)
                .extracting(ConnectionResolution::sourceNodeRunId)
                .containsExactlyInAnyOrder(reviewerOne.id(), implementerOne.id());
        this.lifecycle.succeed(reviewerTwo.id(), new NodeRunOutput("{\"reviewer\":\"two-repeat\"}"));

        assertThat(this.nodeRuns(runId, REVIEWER)).hasSize(2);
        final NodeRun implementerTwo = this.onlyPending(runId, IMPLEMENTER, REPOSITORY_A);
        this.complete(implementerTwo, "{\"implementer\":\"two\"}");

        final NodeRun reviewerThree = this.onlyPending(runId, REVIEWER, REPOSITORY_A);
        assertThat(reviewerThree.activationFrameId()).isEqualTo(reviewerTwo.executionFrameId());
        assertThat(reviewerThree.executionFrameId()).isNotEqualTo(reviewerTwo.executionFrameId());
        final NodeExecutionClaim reviewerThreeClaim = this.lifecycle.tryStart(reviewerThree.id()).orElseThrow();
        assertThat(reviewerThreeClaim.inputEnvelope().contributions())
                .hasSize(2)
                .extracting(NodeInputContribution::sourceNodeRunId)
                .containsExactlyInAnyOrder(reviewerTwo.id(), implementerTwo.id());
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(reviewerThree.id()))
                .hasSize(2)
                .allSatisfy(resolution -> assertThat(resolution.executionFrameId()).isEqualTo(reviewerTwo.executionFrameId()));
        assertThat(generationOne).noneMatch(resolution -> reviewerThree.id().equals(resolution.consumedByNodeRunId()));

        this.lifecycle.succeed(reviewerThree.id(), new NodeRunOutput("{\"reviewer\":\"exit\"}"));
        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(reviewerThree.id());
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void childFrameActivationReevaluatesWaitingParentFanIn() {
        this.seed();
        this.saveAsymmetricScopedReentryWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation ->
                invocation.<NodeRunOutput>getArgument(0).jsonValue().contains("direct") ? B_OUT : B_OTHER);
        final UUID runId = this.createTask(this.repositories(2));
        final UUID frameA = this.rootFrame(runId);

        this.complete(this.onlyPending(runId, A, REPOSITORY_A), "{\"step\":\"root-a\"}");
        this.complete(this.onlyPending(runId, A, REPOSITORY_B), "{\"step\":\"root-b\"}");
        this.complete(this.onlyPending(runId, B, REPOSITORY_A), "{\"route\":\"direct\"}");
        this.complete(this.onlyPending(runId, B, REPOSITORY_B), "{\"route\":\"delay\"}");
        final NodeRun parentTarget = this.onlyPending(runId, IMPLEMENTER, REPOSITORY_A);
        this.complete(parentTarget, "{\"generation\":\"parent\"}");
        final ConnectionResolution parentContribution = this.resolutionRepository.findByWorkflowRunAndFrame(runId, frameA).stream()
                .filter(resolution -> resolution.sourceNodeRunId().equals(parentTarget.id())).findFirst().orElseThrow();
        final InputParticipation waiting = this.participationResolver.resolve(runId, frameA, FINAL_IN, null);
        assertThat(waiting.open()).isTrue();
        assertThat(waiting.delivered()).extracting(ConnectionResolution::id).containsExactly(parentContribution.id());

        this.complete(this.onlyPending(runId, C, REPOSITORY_B), "{\"step\":\"delay-one\"}");
        this.complete(this.onlyPending(runId, D, REPOSITORY_B), "{\"step\":\"delay-two\"}");
        this.complete(this.onlyPending(runId, X), "{\"step\":\"gate\"}");

        final List<NodeRun> childTargets = this.pending(runId, IMPLEMENTER);
        assertThat(childTargets).hasSize(2).extracting(NodeRun::repositoryId)
                .containsExactlyInAnyOrder(REPOSITORY_A, REPOSITORY_B);
        final UUID frameB = childTargets.getFirst().executionFrameId();
        assertThat(frameB).isNotEqualTo(frameA);
        assertThat(childTargets).extracting(NodeRun::executionFrameId).containsOnly(frameB);
        final NodeRun parentFinal = this.onlyPending(runId, FINAL);
        assertThat(parentFinal.activationFrameId()).isEqualTo(frameA);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(parentFinal.id()))
                .extracting(ConnectionResolution::id).containsExactly(parentContribution.id());
        this.complete(parentFinal, "{\"result\":\"parent\"}");

        childTargets.forEach(nodeRun -> this.complete(nodeRun, "{\"generation\":\"child\"}"));
        final NodeRun childFinal = this.onlyPending(runId, FINAL);
        assertThat(childFinal.activationFrameId()).isEqualTo(frameB);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(childFinal.id())).hasSize(2)
                .allSatisfy(resolution -> assertThat(resolution.executionFrameId()).isEqualTo(frameB));
        this.complete(childFinal, "{\"result\":\"child\"}");

        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.result()).isEqualTo(new NodeRunOutput("{\"result\": \"child\"}"));
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(childFinal.id());
        assertThat(this.resolutionRepository.findByWorkflowRunAndFrame(runId, frameA).stream()
                .filter(resolution -> resolution.type() == ConnectionResolutionType.DELIVERED)
                .filter(resolution -> resolution.consumedByNodeRunId() == null)).isEmpty();
        this.assertTerminalQuiescence(runId);
    }

    @ParameterizedTest(name = "failing repository index {0}")
    @ValueSource(ints = {0, 1})
    void oneScopedFailureFailsWorkflowWithoutGlobalSuccessor(final int failingRepositoryIndex) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(2);
        final UUID runId = this.createTask(repositories);
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final NodeRun failed = this.start(this.onlyPending(runId, B, repositories.get(failingRepositoryIndex)));
        final NodeRun sibling = this.onlyPending(runId, B, repositories.get(1 - failingRepositoryIndex));
        this.complete(sibling, "{\"stage\":\"b\"}");
        this.lifecycle.fail(failed.id(), new NodeRunFailure("DETERMINISTIC_FAILURE", "Scoped failure."));

        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.FAILED);
        assertThat(this.nodeRuns(runId, B)).extracting(NodeRun::status)
                .containsExactlyInAnyOrder(NodeRunStatus.SUCCEEDED, NodeRunStatus.FAILED);
        assertThat(this.nodeRuns(runId, C)).isEmpty();
        assertThat(finished.result()).isNull();
        assertThat(this.active(runId)).isEmpty();
    }

    @ParameterizedTest(name = "failure-first with pending sibling, failing repository index {0}")
    @ValueSource(ints = {0, 1})
    void scopedFailureCancelsPendingSibling(final int failingRepositoryIndex) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(2);
        final UUID runId = this.createTask(repositories);
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final NodeRun failed = this.start(this.onlyPending(runId, B, repositories.get(failingRepositoryIndex)));

        this.lifecycle.fail(failed.id(), new NodeRunFailure("DETERMINISTIC_FAILURE", "Scoped failure."));

        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.FAILED);
        assertThat(this.nodeRuns(runId, B)).filteredOn(run -> run.id().equals(failed.id()))
                .extracting(NodeRun::status).containsExactly(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns(runId, B)).filteredOn(run -> !run.id().equals(failed.id()))
                .extracting(NodeRun::status).containsExactly(NodeRunStatus.CANCELLED);
        assertThat(this.nodeRuns(runId, C)).isEmpty();
        assertThat(finished.result()).isNull();
    }

    @ParameterizedTest(name = "failure-first with running sibling, failing repository index {0}")
    @ValueSource(ints = {0, 1})
    void scopedFailureCancelsRunningSiblingAndIgnoresLateCallbacks(final int failingRepositoryIndex) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = this.repositories(2);
        final UUID runId = this.createTask(repositories);
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final NodeRun failed = this.start(this.onlyPending(runId, B, repositories.get(failingRepositoryIndex)));
        final NodeRun sibling = this.start(this.onlyPending(runId, B, repositories.get(1 - failingRepositoryIndex)));

        this.lifecycle.fail(failed.id(), new NodeRunFailure("DETERMINISTIC_FAILURE", "Scoped failure."));
        this.lifecycle.succeed(sibling.id(), new NodeRunOutput("{\"late\":true}"));
        this.lifecycle.fail(sibling.id(), new NodeRunFailure("LATE_FAILURE", "Late failure."));

        final WorkflowRun finished = this.terminal(runId, WorkflowRunStatus.FAILED);
        assertThat(this.nodeRunRepository.findById(failed.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRunRepository.findById(sibling.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.CANCELLED);
        assertThat(this.resolutionRepository.findByWorkflowRunAndFrame(runId, sibling.executionFrameId())).noneMatch(
                resolution -> resolution.sourceNodeRunId().equals(sibling.id()));
        assertThat(this.nodeRuns(runId, C)).isEmpty();
        assertThat(finished.result()).isNull();
    }

    @Test
    void concurrentMultiScopeFailureIsIdempotentAndCreatesNoGlobalSuccessor() throws Exception {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final UUID runId = this.createTask(this.repositories(2));
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final NodeRun first = this.start(this.onlyPending(runId, B, REPOSITORY_A));
        final NodeRun second = this.start(this.onlyPending(runId, B, REPOSITORY_B));
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var one = executor.submit(() -> this.failCapturingConflict(first.id(), new NodeRunFailure("FAIL_A", "A failed.")));
            final var two = executor.submit(() -> this.failCapturingConflict(second.id(), new NodeRunFailure("FAIL_B", "B failed.")));
            assertThat(Stream.of(one.get(), two.get()).filter(Objects::nonNull).toList())
                    .allSatisfy(conflict -> assertThat(conflict.code()).isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT))
                    .hasSizeLessThanOrEqualTo(1);
        }

        this.terminal(runId, WorkflowRunStatus.FAILED);
        assertThat(this.nodeRuns(runId, B)).extracting(NodeRun::status)
                .containsExactlyInAnyOrder(NodeRunStatus.FAILED, NodeRunStatus.CANCELLED);
        assertThat(this.nodeRuns(runId, C)).isEmpty();
        assertThat(this.active(runId)).isEmpty();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closedCycleStopsAtExistingActivationResolution() {
        this.seed();
        this.saveClosedCycleWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(A_OUT);
        final UUID runId = this.createTask(List.of(REPOSITORY_A));
        final UUID frame = this.rootFrame(runId);
        this.complete(this.onlyPending(runId, A), "{\"result\":\"done\"}");

        assertThat(this.nodeRuns(runId, A)).hasSize(1);
        assertThat(this.nodeRuns(runId, B)).isEmpty();
        assertThat(this.activationResolutionRepository.find(runId, frame, B_IN, null)).isPresent()
                .get().satisfies(resolution -> assertThat(resolution.activatedNodeRunId()).isNull());
        assertThat(this.activationResolutionRepository.find(runId, frame, A_REVIEW_IN, null)).isPresent()
                .get().satisfies(resolution -> assertThat(resolution.activatedNodeRunId()).isNull());
        assertThat(this.forgeIt.postgresql().get(InputActivationResolutionEntity.class).getAll()).hasSize(2);
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @Test
    void concurrentScopedCompletionsActivateGlobalTargetExactlyOnce() throws Exception {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL));
        final UUID runId = this.createTask(this.repositories(2));
        this.complete(this.onlyPending(runId, A), "{\"stage\":\"a\"}");
        final NodeRun first = this.start(this.onlyPending(runId, B, REPOSITORY_A));
        final NodeRun second = this.start(this.onlyPending(runId, B, REPOSITORY_B));
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var one = executor.submit(() -> this.lifecycle.succeed(first.id(), new NodeRunOutput("{\"repository\":\"a\"}")));
            final var two = executor.submit(() -> this.lifecycle.succeed(second.id(), new NodeRunOutput("{\"repository\":\"b\"}")));
            one.get();
            two.get();
        }

        final NodeRun c = this.onlyPending(runId, C);
        assertThat(this.nodeRuns(runId, C)).containsExactly(c);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(c.id())).hasSize(2)
                .extracting(ConnectionResolution::consumedByNodeRunId).containsOnly(c.id());
        assertThat(this.activationResolutionRepository.find(runId, this.rootFrame(runId), C_IN, null))
                .hasValueSatisfying(resolution -> assertThat(resolution.activatedNodeRunId()).isEqualTo(c.id()));
        this.complete(c, "{\"stage\":\"c\"}");
        this.terminal(runId, WorkflowRunStatus.SUCCEEDED);
        this.assertTerminalQuiescence(runId);
    }

    @ParameterizedTest(name = "invalid repository selection {0}")
    @ValueSource(strings = {"empty", "duplicate"})
    void invalidTaskRepositorySelectionLeavesNoPartialRuntime(final String invalidSelection) {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.GLOBAL));
        final List<UUID> repositories = invalidSelection.equals("empty")
                ? List.of() : List.of(REPOSITORY_A, REPOSITORY_A);

        assertThatThrownBy(() -> this.projectTaskUseCases.createProjectTask(PROJECT_ALPHA_ID, new CreateProjectTaskCommand(
                "Invalid repositories", "Must fail atomically.", WORKFLOW_ID, repositories)))
                .isInstanceOfSatisfying(ValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_PROJECT_TASK_REPOSITORIES"));
        this.assertNoTaskRuntimePersisted();
    }

    @Test
    void directRunContainingPerScopeWithoutRepositorySnapshotLeavesNoPartialRuntime() {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE));

        assertThatThrownBy(() -> this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Missing repository snapshot.")))
                .isInstanceOfSatisfying(ValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PER_SCOPE_RUN_REQUIRES_REPOSITORIES"));
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(ExecutionFrameEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    @Test
    void multiRepositoryPerScopeTaskOutputLeavesNoPartialTaskOrRuntime() {
        this.seed();
        this.saveChain(List.of(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE));

        assertThatThrownBy(() -> this.projectTaskUseCases.createProjectTask(PROJECT_ALPHA_ID, new CreateProjectTaskCommand(
                "Ambiguous output", "Must fail atomically.", WORKFLOW_ID, this.repositories(2))))
                .isInstanceOfSatisfying(ValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AMBIGUOUS_PER_SCOPE_TASK_OUTPUT"));
        this.assertNoTaskRuntimePersisted();
    }

    private void completeGeneration(final UUID runId, final int generation, final String route) {
        for (final UUID repositoryId : this.repositories(2)) {
            this.complete(this.onlyPending(runId, IMPLEMENTER, repositoryId), "{\"generation\":" + generation + "}");
        }
        for (final UUID repositoryId : this.repositories(2)) {
            this.complete(this.onlyPending(runId, REVIEWER, repositoryId), "{\"route\":\"" + route + "\"}");
        }
    }

    private UUID generationFrame(final UUID runId, final int generation) {
        final List<NodeRun> implementations = this.nodeRuns(runId, IMPLEMENTER);
        final List<NodeRun> current = implementations.subList((generation - 1) * 2, generation * 2);
        assertThat(current).extracting(NodeRun::executionFrameId).containsOnly(current.getFirst().executionFrameId());
        return current.getFirst().executionFrameId();
    }

    private void seed() {
        this.createRepositoryWorkspaceFixtures();
        this.forgeIt.postgresql().create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT_REPOSITORY.withEntity(this.repositoryEntity(REPOSITORY_A, "repository-a")))
                .to(PROJECT_REPOSITORY.withEntity(this.repositoryEntity(REPOSITORY_B, "repository-b")))
                .to(PROJECT_REPOSITORY.withEntity(this.repositoryEntity(REPOSITORY_C, "repository-c")))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(AGENT_DEFINITION.withJson("agent_c.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
    }

    private void createRepositoryWorkspaceFixtures() {
        try {
            Files.createDirectories(this.repositoryWorkspace(REPOSITORY_A).resolve(".git"));
            Files.createDirectories(this.repositoryWorkspace(REPOSITORY_B).resolve(".git"));
            Files.createDirectories(this.repositoryWorkspace(REPOSITORY_C).resolve(".git"));
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to create Forge repository workspace fixtures.", exception);
        }
    }

    private Path projectWorkspace() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve(".git"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Forge root could not be resolved for integration test.");
        }
        return current.resolve("forge-projects").resolve(PROJECT_ALPHA_ID.toString());
    }

    private Path repositoryWorkspace(final UUID repositoryId) {
        final String name = switch (this.repositories(3).indexOf(repositoryId)) {
            case 0 -> "repository-a";
            case 1 -> "repository-b";
            case 2 -> "repository-c";
            default -> throw new IllegalArgumentException("Unknown repository fixture.");
        };
        return this.projectWorkspace().resolve(name);
    }

    private ProjectRepositoryEntity repositoryEntity(final UUID id, final String name) {
        final ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(id);
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setRemoteUrl("https://example.com/forge/" + name + ".git");
        entity.setCreatedAt(Instant.parse("2026-08-10T10:00:00Z"));
        return entity;
    }

    private UUID createTask(final List<UUID> repositories) {
        final ProjectTaskDetails task = this.projectTaskUseCases.createProjectTask(PROJECT_ALPHA_ID, new CreateProjectTaskCommand(
                "Scoped execution", "Run scoped integration scenario.", WORKFLOW_ID, repositories));
        return task.runs().getFirst().id();
    }

    private void saveChain(final List<NodeScopeMode> scopes) {
        final List<UUID> nodeIds = List.of(A, B, C, D).subList(0, scopes.size());
        final List<UUID> inputIds = List.of(A_IN, B_IN, C_IN, D_IN).subList(0, scopes.size());
        final List<UUID> outputIds = List.of(A_OUT, B_OUT, C_OUT, D_OUT).subList(0, scopes.size());
        final List<UUID> agentIds = List.of(AGENT_A_ID, AGENT_B_ID, AGENT_C_ID, AGENT_A_ID).subList(0, scopes.size());
        final List<Node> nodes = java.util.stream.IntStream.range(0, scopes.size())
                .mapToObj(index -> this.node(nodeIds.get(index), agentIds.get(index), inputIds.get(index), outputIds.get(index), index, scopes.get(index)))
                .toList();
        final List<WorkflowConnection> connections = java.util.stream.IntStream.range(0, scopes.size() - 1)
                .mapToObj(index -> this.connection(index + 1, outputIds.get(index), inputIds.get(index + 1))).toList();
        this.save(nodes, connections, A_IN, outputIds.getLast());
    }

    private void saveScopedRouteWorkflow() {
        this.save(List.of(
                        this.node(A, AGENT_A_ID, A_IN, A_OUT, 0, NodeScopeMode.GLOBAL),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")),
                                List.of(this.port(B_OUT, "Deliver", 0), this.port(B_OTHER, "Other", 1)), 1, NodeScopeMode.PER_SCOPE),
                        this.node(C, AGENT_C_ID, C_IN, C_OUT, 2, NodeScopeMode.GLOBAL)),
                List.of(this.connection(1, A_OUT, B_IN), this.connection(2, B_OUT, C_IN)), A_IN, C_OUT);
    }

    private void saveGlobalClosedToScopedWorkflow() {
        this.save(List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")),
                                List.of(this.port(A_OUT, "Scoped", 0), this.port(A_OTHER, "Other", 1)), 0, NodeScopeMode.GLOBAL),
                        this.node(B, AGENT_B_ID, B_IN, B_OUT, 1, NodeScopeMode.PER_SCOPE)),
                List.of(this.connection(1, A_OUT, B_IN)), A_IN, A_OTHER);
    }

    private void saveScopedReviewerWorkflow() {
        this.save(List.of(
                        this.node(IMPLEMENTER, AGENT_A_ID,
                                List.of(this.port(IMPLEMENTER_INITIAL_IN, "Initial", 0), this.port(IMPLEMENTER_REVIEW_IN, "Review", 1)),
                                List.of(this.port(IMPLEMENTER_OUT, "Review")), 0, NodeScopeMode.PER_SCOPE),
                        this.node(REVIEWER, AGENT_B_ID, List.of(this.port(REVIEWER_IN, "Input")),
                                List.of(this.port(REVIEWER_PASS, "Pass", 0), this.port(REVIEWER_RETURN, "Return", 1)), 1,
                                NodeScopeMode.PER_SCOPE),
                        this.node(FINAL, AGENT_C_ID, FINAL_IN, FINAL_OUT, 2, NodeScopeMode.GLOBAL)),
                List.of(this.connection(1, IMPLEMENTER_OUT, REVIEWER_IN), this.connection(2, REVIEWER_PASS, FINAL_IN),
                        this.connection(3, REVIEWER_RETURN, IMPLEMENTER_REVIEW_IN)), IMPLEMENTER_INITIAL_IN, FINAL_OUT);
    }

    private void saveGuardedSelfLoopWorkflow() {
        this.save(List.of(
                        this.node(REVIEWER, AGENT_B_ID, List.of(this.port(REVIEWER_IN, "Input")),
                                List.of(this.port(REVIEWER_PASS, "Pass", 0), this.port(REVIEWER_RETURN, "Return", 1)), 0,
                                NodeScopeMode.PER_SCOPE),
                        this.node(IMPLEMENTER, AGENT_A_ID, List.of(this.port(IMPLEMENTER_INITIAL_IN, "Input")),
                                List.of(this.port(IMPLEMENTER_OUT, "Done")), 1, NodeScopeMode.PER_SCOPE)),
                List.of(this.connection(40, REVIEWER_RETURN, REVIEWER_IN),
                        this.connection(41, REVIEWER_RETURN, IMPLEMENTER_INITIAL_IN),
                        this.connection(42, IMPLEMENTER_OUT, REVIEWER_IN)), REVIEWER_IN, REVIEWER_PASS);
    }

    private void saveAsymmetricScopedReentryWorkflow() {
        this.save(List.of(
                        this.node(A, AGENT_A_ID, A_IN, A_OUT, 0, NodeScopeMode.PER_SCOPE),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")),
                                List.of(this.port(B_OUT, "Direct", 0), this.port(B_OTHER, "Delay", 1)), 1, NodeScopeMode.PER_SCOPE),
                        this.node(C, AGENT_C_ID, C_IN, C_OUT, 2, NodeScopeMode.PER_SCOPE),
                        this.node(D, AGENT_A_ID, D_IN, D_OUT, 3, NodeScopeMode.PER_SCOPE),
                        this.node(X, AGENT_B_ID, X_IN, X_OUT, 4, NodeScopeMode.GLOBAL),
                        this.node(IMPLEMENTER, AGENT_A_ID,
                                List.of(this.port(IMPLEMENTER_INITIAL_IN, "Direct", 0), this.port(IMPLEMENTER_REVIEW_IN, "Gate", 1)),
                                List.of(this.port(IMPLEMENTER_OUT, "Done")), 5, NodeScopeMode.PER_SCOPE),
                        this.node(FINAL, AGENT_C_ID, FINAL_IN, FINAL_OUT, 6, NodeScopeMode.GLOBAL)),
                List.of(this.connection(1, A_OUT, B_IN), this.connection(2, B_OUT, IMPLEMENTER_INITIAL_IN),
                        this.connection(3, B_OTHER, C_IN), this.connection(4, C_OUT, D_IN), this.connection(5, D_OUT, X_IN),
                        this.connection(6, X_OUT, IMPLEMENTER_REVIEW_IN), this.connection(7, IMPLEMENTER_OUT, FINAL_IN)), A_IN, FINAL_OUT);
    }

    private void saveClosedCycleWorkflow() {
        this.save(List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Initial", 0), this.port(A_REVIEW_IN, "Review", 1)),
                                List.of(this.port(A_OUT, "Finish", 0), this.port(A_OTHER, "To B", 1)), 0, NodeScopeMode.GLOBAL),
                        this.node(B, AGENT_B_ID, B_IN, B_OUT, 1, NodeScopeMode.GLOBAL)),
                List.of(this.connection(1, A_OTHER, B_IN), this.connection(2, B_OUT, A_REVIEW_IN)), A_IN, A_OUT);
    }

    private void save(final List<Node> nodes, final List<WorkflowConnection> connections, final UUID taskInput, final UUID taskOutput) {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand("Scoped Execution", nodes, connections, taskInput, taskOutput));
    }

    private Node node(final UUID id, final UUID agentId, final UUID inputId, final UUID outputId, final int x,
                      final NodeScopeMode scope) {
        return this.node(id, agentId, List.of(this.port(inputId, "Input")), List.of(this.port(outputId, "Output")), x, scope);
    }

    private Node node(final UUID id, final UUID agentId, final List<NodePort> inputs, final List<NodePort> outputs, final int x,
                      final NodeScopeMode scope) {
        return new Node(id, agentId, NodeInputMode.DEPENDENCIES_ONLY, inputs, outputs, new NodePosition(x * 100.0, 0.0), scope);
    }

    private NodePort port(final UUID id, final String name) {
        return this.port(id, name, 0);
    }

    private NodePort port(final UUID id, final String name, final int order) {
        return new NodePort(id, name, name + " description.", order);
    }

    private WorkflowConnection connection(final int index, final UUID source, final UUID target) {
        return new WorkflowConnection(UUID.fromString("93000000-0000-4000-8000-" + String.format("%012d", index)), source, target);
    }

    private NodeRun start(final NodeRun nodeRun) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow();
        return this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow();
    }

    private ConflictException failCapturingConflict(final UUID nodeRunId, final NodeRunFailure failure) {
        try {
            this.lifecycle.fail(nodeRunId, failure);
            return null;
        } catch (final ConflictException conflict) {
            return conflict;
        }
    }

    private void complete(final NodeRun nodeRun, final String output) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow();
        final NodeRunOutput businessOutput = new NodeRunOutput(output);
        final UUID selected = claim.availableOutputs().size() > 1
                ? this.outputSelector.selectOutput(businessOutput, claim.availableOutputs(), claim.executionModel())
                : null;
        this.lifecycle.succeed(claim.nodeRunId(), new AgentExecutionResult(businessOutput, selected));
    }

    private void completeWithWorkspaceAssertion(final NodeRun nodeRun, final String output) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow();
        final Path repositoryWorkspace = this.repositoryWorkspace(nodeRun.repositoryId());
        assertThat(claim.executionWorkspace().cwd()).isEqualTo(repositoryWorkspace);
        assertThat(claim.executionWorkspace().workspaceRoots()).containsExactly(repositoryWorkspace);
        final NodeRunOutput businessOutput = new NodeRunOutput(output);
        final UUID selected = claim.availableOutputs().size() > 1
                ? this.outputSelector.selectOutput(businessOutput, claim.availableOutputs(), claim.executionModel())
                : null;
        this.lifecycle.succeed(nodeRun.id(), new AgentExecutionResult(businessOutput, selected));
    }

    private interface OutputSelector {
        UUID selectOutput(NodeRunOutput output, List<com.sitionix.forgeagent.domain.model.RunPort> outputs,
                          com.sitionix.forgeagent.domain.model.NodeRunExecutionModel executionModel);
    }

    private NodeRun onlyPending(final UUID runId, final UUID sourceNodeId) {
        return this.pending(runId, sourceNodeId).stream().findFirst().orElseThrow();
    }

    private NodeRun onlyPending(final UUID runId, final UUID sourceNodeId, final UUID repositoryId) {
        return this.pending(runId, sourceNodeId).stream().filter(nodeRun -> repositoryId.equals(nodeRun.repositoryId())).findFirst().orElseThrow();
    }

    private List<NodeRun> pending(final UUID runId, final UUID sourceNodeId) {
        return this.nodeRuns(runId, sourceNodeId).stream().filter(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING).toList();
    }

    private List<NodeRun> active(final UUID runId) {
        return this.nodeRunRepository.findByWorkflowRunId(runId).stream()
                .filter(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING || nodeRun.status() == NodeRunStatus.RUNNING).toList();
    }

    private List<NodeRun> nodeRuns(final UUID runId, final UUID sourceNodeId) {
        return this.nodeRunRepository.findByWorkflowRunId(runId).stream().filter(nodeRun -> nodeRun.sourceNodeId().equals(sourceNodeId))
                .sorted(Comparator.comparing(NodeRun::createdAt).thenComparing(NodeRun::id)).toList();
    }

    private UUID rootFrame(final UUID runId) {
        return this.frameRepository.findByWorkflowRunId(runId).stream().filter(frame -> frame.parentFrameId() == null).findFirst().orElseThrow().id();
    }

    private WorkflowRun terminal(final UUID runId, final WorkflowRunStatus status) {
        final WorkflowRun run = this.workflowRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(status);
        assertThat(this.active(runId)).isEmpty();
        return run;
    }

    private void assertTerminalQuiescence(final UUID runId) {
        final WorkflowRun run = this.workflowRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(this.active(runId)).isEmpty();
        assertThat(run.connectionResolutions().stream()
                .filter(resolution -> resolution.type() == ConnectionResolutionType.DELIVERED)
                .filter(resolution -> resolution.consumedByNodeRunId() == null)).isEmpty();
    }

    private void assertNoTaskRuntimePersisted() {
        assertThat(this.forgeIt.postgresql().get(ProjectTaskEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(WorkflowRunEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(ExecutionFrameEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(NodeRunEntity.class).getAll()).isEmpty();
    }

    private List<UUID> repositories(final int count) {
        return List.of(REPOSITORY_A, REPOSITORY_B, REPOSITORY_C).subList(0, count);
    }

    private static UUID uuid(final int value) {
        return UUID.fromString("90000000-0000-4000-8000-" + String.format("%012d", value));
    }

    private static UUID input(final int value) {
        return UUID.fromString("92000000-0000-4000-8000-" + String.format("%012d", value));
    }

    private static UUID output(final int value) {
        return UUID.fromString("91000000-0000-4000-8000-" + String.format("%012d", value));
    }

    private static UUID repository(final int value) {
        return UUID.fromString("70000000-0000-4000-8000-" + String.format("%012d", value));
    }
}
