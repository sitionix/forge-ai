package com.sitionix.forgeai.infrastructure.resources.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceOperatorConfigResourceRepositoryTest {

    @TempDir
    private Path repositoryRoot;

    private String originalUserDir;
    private ResourceOperatorConfigResourceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        this.originalUserDir = System.getProperty("user.dir");
        this.createRepositoryFiles();
        System.setProperty("user.dir", this.repositoryRoot.toString());
        this.repository = new ResourceOperatorConfigResourceRepository(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", this.originalUserDir);
    }

    @Test
    void givenConfiguredResources_whenContracts_thenListContractResources() {
        assertThat(this.repository.contracts()).singleElement()
                .satisfies(resource -> {
                    assertThat(resource.resourceKey()).isEqualTo("contract:ArchitectPayload");
                    assertThat(resource.label()).isEqualTo("ArchitectPayload");
                    assertThat(resource.resourceType()).isEqualTo("json");
                    assertThat(resource.content()).contains("ArchitectPayload");
                });
    }

    @Test
    void givenValidYamlResource_whenSave_thenWriteAllowlistedSourceResource() throws Exception {
        final var actual = this.repository.save("agent-yml", "agents: []\n");

        assertThat(actual.resourceKey()).isEqualTo("agent-yml");
        assertThat(Files.readString(this.repositoryRoot.resolve("boot/src/main/resources/agent.yml"), StandardCharsets.UTF_8))
                .isEqualTo("agents: []\n");
    }

    @Test
    void givenInstructionPathTraversal_whenSave_thenRejectResourceKey() {
        assertThatThrownBy(() -> this.repository.save("instruction:../agent.yml", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported instruction ref");
    }

    @Test
    void givenInvalidJsonContract_whenSave_thenRejectContent() {
        assertThatThrownBy(() -> this.repository.save("contract:ArchitectPayload", "{broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON content");
    }

    private void createRepositoryFiles() throws Exception {
        Files.writeString(this.repositoryRoot.resolve("pom.xml"), "<project />", StandardCharsets.UTF_8);
        Files.createDirectories(this.repositoryRoot.resolve("boot/src/main/resources"));
        Files.writeString(this.repositoryRoot.resolve("boot/src/main/resources/agent.yml"), "agents: []\n", StandardCharsets.UTF_8);
        Files.writeString(this.repositoryRoot.resolve("boot/src/main/resources/lane-strategies.yml"), "strategies: []\n", StandardCharsets.UTF_8);
        Files.createDirectories(this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/instructions/shared"));
        Files.writeString(
                this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/instructions/shared/common-rules.md"),
                "shared instruction",
                StandardCharsets.UTF_8
        );
        Files.createDirectories(this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/completion-payload-contracts"));
        Files.writeString(
                this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/completion-payload-contracts/ArchitectPayload.json"),
                "{\"payloadType\":\"ArchitectPayload\",\"description\":\"Architect task input.\",\"fields\":[]}",
                StandardCharsets.UTF_8
        );
    }
}
