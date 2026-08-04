package com.sitionix.forgeagent.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForgeAgentApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("forge_agent")
            .withUsername("forge_agent")
            .withPassword("forge_agent");

    @DynamicPropertySource
    static void configure(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createListGetAndUpdateAgentFlow() {
        final ProjectResponse project = this.createProject("Sitionix Flow");

        final AgentResponse architect = this.createAgent(project.id(), "Architect", List.of());

        final AgentResponse backend = this.post(
                "/api/v1/projects/" + project.id() + "/agents",
                this.agentRequest(
                        "name", "Backend Implementer",
                        "instructions", "Implement backend changes.",
                        "outputSchema", Map.of("type", "object", "properties", Map.of("summary", Map.of("type", "string"))),
                        "dependsOnAgentIds", List.of(architect.id())
                ),
                AgentResponse.class
        ).getBody();
        assertThat(backend.dependsOn()).extracting(DependencyResponse::name).containsExactly("Architect");

        final ResponseEntity<List<AgentListResponse>> listResponse = this.restTemplate.exchange(
                this.url("/api/v1/projects/" + project.id() + "/agents"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(2);

        final AgentResponse loaded = this.restTemplate.getForObject(this.url("/api/v1/agents/" + backend.id()), AgentResponse.class);
        assertThat(loaded.instructions()).isEqualTo("Implement backend changes.");

        final AgentResponse updated = this.restTemplate.exchange(
                this.url("/api/v1/agents/" + backend.id()),
                HttpMethod.PUT,
                new HttpEntity<>(this.agentRequest(
                        "name", "Backend Implementer",
                        "instructions", "Updated instructions.",
                        "outputSchema", Map.of("type", "object", "properties", Map.of()),
                        "dependsOnAgentIds", List.of()
                )),
                AgentResponse.class
        ).getBody();
        assertThat(updated.dependsOn()).isEmpty();
    }

    @Test
    void returnsControlledErrorForDuplicateProjectName() {
        final String name = this.uniqueName("Sitionix Duplicate");
        this.post("/api/v1/projects", Map.of("name", name), ProjectResponse.class);

        final ResponseEntity<ErrorResponse> duplicate = this.post(
                "/api/v1/projects",
                Map.of("name", name.toLowerCase()),
                ErrorResponse.class
        );

        this.assertError(duplicate, HttpStatus.CONFLICT, "DUPLICATE_PROJECT_NAME");
    }

    @Test
    void outputSchemaRoundTripsThroughPostgresJsonb() {
        final ProjectResponse project = this.createProject("Jsonb Round Trip");

        final AgentResponse saved = this.post(
                "/api/v1/projects/" + project.id() + "/agents",
                this.agentRequest(
                        "name", "Json Agent",
                        "instructions", "Persist JSONB.",
                        "outputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("summary", Map.of("type", "string"))
                        ),
                        "dependsOnAgentIds", List.of()
                ),
                AgentResponse.class
        ).getBody();

        final AgentResponse loaded = this.restTemplate.getForObject(this.url("/api/v1/agents/" + saved.id()), AgentResponse.class);
        assertThat(loaded.outputSchema()).containsEntry("type", "object");
        assertThat(loaded.outputSchema()).containsKey("properties");
    }

    @Test
    void duplicateAgentNameIsProjectScoped() {
        final ProjectResponse firstProject = this.createProject("Duplicate Agent First");
        final ProjectResponse secondProject = this.createProject("Duplicate Agent Second");
        this.createAgent(firstProject.id(), "Reusable Agent", List.of());

        final ResponseEntity<ErrorResponse> duplicateInSameProject = this.post(
                "/api/v1/projects/" + firstProject.id() + "/agents",
                this.agentRequest("name", "reusable agent", "instructions", "Duplicate.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of()),
                ErrorResponse.class
        );
        final ResponseEntity<AgentResponse> sameNameInDifferentProject = this.post(
                "/api/v1/projects/" + secondProject.id() + "/agents",
                this.agentRequest("name", "Reusable Agent", "instructions", "Allowed.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of()),
                AgentResponse.class
        );

        this.assertError(duplicateInSameProject, HttpStatus.CONFLICT, "DUPLICATE_AGENT_NAME");
        assertThat(sameNameInDifferentProject.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rejectsInvalidDependenciesAtHttpBoundary() {
        final ProjectResponse firstProject = this.createProject("Dependency Validation First");
        final ProjectResponse secondProject = this.createProject("Dependency Validation Second");
        final AgentResponse firstProjectAgent = this.createAgent(firstProject.id(), "First Project Agent", List.of());
        final AgentResponse secondProjectAgent = this.createAgent(secondProject.id(), "Second Project Agent", List.of());

        final ResponseEntity<ErrorResponse> selfDependency = this.put(
                "/api/v1/agents/" + firstProjectAgent.id(),
                this.agentRequest("name", "First Project Agent", "instructions", "Self.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of(firstProjectAgent.id())),
                ErrorResponse.class
        );
        final ResponseEntity<ErrorResponse> unknownDependency = this.post(
                "/api/v1/projects/" + firstProject.id() + "/agents",
                this.agentRequest("name", "Unknown Dependency", "instructions", "Unknown.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of(UUID.randomUUID())),
                ErrorResponse.class
        );
        final ResponseEntity<ErrorResponse> crossProjectDependency = this.post(
                "/api/v1/projects/" + firstProject.id() + "/agents",
                this.agentRequest("name", "Cross Project Dependency", "instructions", "Cross.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of(secondProjectAgent.id())),
                ErrorResponse.class
        );

        this.assertError(selfDependency, HttpStatus.BAD_REQUEST, "SELF_DEPENDENCY");
        this.assertError(unknownDependency, HttpStatus.BAD_REQUEST, "UNKNOWN_DEPENDENCY");
        this.assertError(crossProjectDependency, HttpStatus.CONFLICT, "CROSS_PROJECT_DEPENDENCY");
    }

    @Test
    void rejectsIndirectCycle() {
        final ProjectResponse project = this.createProject("Indirect Cycle");
        final AgentResponse agentA = this.createAgent(project.id(), "Cycle A", List.of());
        final AgentResponse agentB = this.createAgent(project.id(), "Cycle B", List.of(agentA.id()));
        final AgentResponse agentC = this.createAgent(project.id(), "Cycle C", List.of(agentB.id()));

        final ResponseEntity<ErrorResponse> cycle = this.put(
                "/api/v1/agents/" + agentA.id(),
                this.agentRequest("name", "Cycle A", "instructions", "Create cycle.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of(agentC.id())),
                ErrorResponse.class
        );

        this.assertError(cycle, HttpStatus.CONFLICT, "DEPENDENCY_GRAPH_CYCLE");
    }

    @Test
    void dependencyReplacementIsTransactionalAndFailedCyclicUpdateRollsBack() {
        final ProjectResponse project = this.createProject("Dependency Rollback");
        final AgentResponse dependencyA = this.createAgent(project.id(), "Dependency A", List.of());
        final AgentResponse dependencyB = this.createAgent(project.id(), "Dependency B", List.of());
        final AgentResponse target = this.createAgent(project.id(), "Target", List.of(dependencyA.id()));

        final AgentResponse replaced = this.put(
                "/api/v1/agents/" + target.id(),
                this.agentRequest("name", "Target", "instructions", "Now depends on B.", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", List.of(dependencyB.id())),
                AgentResponse.class
        ).getBody();
        assertThat(replaced.dependsOn()).extracting(DependencyResponse::id).containsExactly(dependencyB.id());

        final ResponseEntity<ErrorResponse> failedCycle = this.put(
                "/api/v1/agents/" + dependencyB.id(),
                this.agentRequest(
                        "name", "Dependency B Changed",
                        "instructions", "This must roll back.",
                        "outputSchema", Map.of("type", "object", "properties", Map.of("changed", Map.of("type", "boolean"))),
                        "dependsOnAgentIds", List.of(target.id())
                ),
                ErrorResponse.class
        );
        this.assertError(failedCycle, HttpStatus.CONFLICT, "DEPENDENCY_GRAPH_CYCLE");

        final AgentResponse reloadedDependencyB = this.restTemplate.getForObject(this.url("/api/v1/agents/" + dependencyB.id()), AgentResponse.class);
        final AgentResponse reloadedTarget = this.restTemplate.getForObject(this.url("/api/v1/agents/" + target.id()), AgentResponse.class);
        assertThat(reloadedDependencyB.name()).isEqualTo("Dependency B");
        assertThat(reloadedDependencyB.instructions()).isEqualTo("Do work for Dependency B.");
        assertThat(reloadedDependencyB.dependsOn()).isEmpty();
        assertThat(reloadedDependencyB.outputSchema()).doesNotContainKey("properties");
        assertThat(reloadedTarget.dependsOn()).extracting(DependencyResponse::id).containsExactly(dependencyB.id());
    }

    @Test
    void concurrentInverseDependencyUpdatesCannotBothCommit() throws Exception {
        final ProjectResponse project = this.createProject("Concurrent Graph Mutation");
        final AgentResponse agentA = this.createAgent(project.id(), "Concurrent A", List.of());
        final AgentResponse agentB = this.createAgent(project.id(), "Concurrent B", List.of());
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(3);

        final Callable<ResponseEntity<String>> updateAToDependOnB = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return this.putString(
                    "/api/v1/agents/" + agentA.id(),
                    this.agentRequest(
                            "name", "Concurrent A",
                            "instructions", "A depends on B.",
                            "outputSchema", Map.of("type", "object"),
                            "dependsOnAgentIds", List.of(agentB.id())
                    )
            );
        };
        final Callable<ResponseEntity<String>> updateBToDependOnA = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return this.putString(
                    "/api/v1/agents/" + agentB.id(),
                    this.agentRequest(
                            "name", "Concurrent B",
                            "instructions", "B depends on A.",
                            "outputSchema", Map.of("type", "object"),
                            "dependsOnAgentIds", List.of(agentA.id())
                    )
            );
        };

        try {
            final Future<ResponseEntity<String>> first = executor.submit(updateAToDependOnB);
            final Future<ResponseEntity<String>> second = executor.submit(updateBToDependOnA);
            barrier.await(10, TimeUnit.SECONDS);

            final List<ResponseEntity<String>> responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(ResponseEntity::getStatusCode)
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            final ResponseEntity<String> conflict = responses.stream()
                    .filter(response -> response.getStatusCode().equals(HttpStatus.CONFLICT))
                    .findFirst()
                    .orElseThrow();
            final ErrorResponse error = this.objectMapper.readValue(conflict.getBody(), ErrorResponse.class);
            assertThat(error.code()).isEqualTo("DEPENDENCY_GRAPH_CYCLE");
        } finally {
            executor.shutdownNow();
        }

        final AgentResponse reloadedA = this.restTemplate.getForObject(this.url("/api/v1/agents/" + agentA.id()), AgentResponse.class);
        final AgentResponse reloadedB = this.restTemplate.getForObject(this.url("/api/v1/agents/" + agentB.id()), AgentResponse.class);
        final boolean aDependsOnB = reloadedA.dependsOn().stream().anyMatch(dependency -> dependency.id().equals(agentB.id()));
        final boolean bDependsOnA = reloadedB.dependsOn().stream().anyMatch(dependency -> dependency.id().equals(agentA.id()));
        assertThat(aDependsOnB && bDependsOnA).isFalse();
        assertThat(List.of(aDependsOnB, bDependsOnA).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);

        final Integer aToBCount = this.jdbcTemplate.queryForObject(
                "select count(*) from agent_dependencies where agent_id = ? and depends_on_agent_id = ?",
                Integer.class,
                agentA.id(),
                agentB.id()
        );
        final Integer bToACount = this.jdbcTemplate.queryForObject(
                "select count(*) from agent_dependencies where agent_id = ? and depends_on_agent_id = ?",
                Integer.class,
                agentB.id(),
                agentA.id()
        );
        assertThat(aToBCount).isNotNull();
        assertThat(bToACount).isNotNull();
        assertThat(aToBCount + bToACount).isEqualTo(1);
    }

    private <T> ResponseEntity<T> post(final String path, final Object request, final Class<T> responseType) {
        return this.restTemplate.postForEntity(this.url(path), request, responseType);
    }

    private <T> ResponseEntity<T> put(final String path, final Object request, final Class<T> responseType) {
        return this.restTemplate.exchange(this.url(path), HttpMethod.PUT, new HttpEntity<>(request), responseType);
    }

    private ResponseEntity<String> putString(final String path, final Object request) {
        return this.restTemplate.exchange(this.url(path), HttpMethod.PUT, new HttpEntity<>(request), String.class);
    }

    private ProjectResponse createProject(final String prefix) {
        final ProjectResponse project = this.post("/api/v1/projects", Map.of("name", this.uniqueName(prefix)), ProjectResponse.class).getBody();
        assertThat(project).isNotNull();
        return project;
    }

    private AgentResponse createAgent(final UUID projectId, final String name, final List<UUID> dependsOnAgentIds) {
        final AgentResponse agent = this.post(
                "/api/v1/projects/" + projectId + "/agents",
                this.agentRequest("name", name, "instructions", "Do work for " + name + ".", "outputSchema", Map.of("type", "object"), "dependsOnAgentIds", dependsOnAgentIds),
                AgentResponse.class
        ).getBody();
        assertThat(agent).isNotNull();
        return agent;
    }

    private Map<String, Object> agentRequest(final Object... values) {
        return Map.of(
                values[0].toString(), values[1],
                values[2].toString(), values[3],
                values[4].toString(), values[5],
                values[6].toString(), values[7]
        );
    }

    private void assertError(final ResponseEntity<ErrorResponse> response, final HttpStatus status, final String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    private String uniqueName(final String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    private String url(final String path) {
        return "http://localhost:" + this.port + path;
    }

    record ProjectResponse(UUID id, String name, String createdAt, String updatedAt) {
    }

    record DependencyResponse(UUID id, String name) {
    }

    record AgentListResponse(UUID id, UUID projectId, String name, List<DependencyResponse> dependsOn, String createdAt, String updatedAt) {
    }

    record AgentResponse(UUID id,
                         UUID projectId,
                         String name,
                         String instructions,
                         Map<String, Object> outputSchema,
                         List<DependencyResponse> dependsOn,
                         String createdAt,
                         String updatedAt) {
    }

    record ErrorResponse(String code, String message, String correlationId) {
    }
}
