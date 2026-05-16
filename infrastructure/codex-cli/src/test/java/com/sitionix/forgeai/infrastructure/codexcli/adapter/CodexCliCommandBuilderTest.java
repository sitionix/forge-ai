package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexCliCommandBuilderTest {

    private CodexCliCommandBuilder codexCliCommandBuilder;

    @BeforeEach
    void setUp() {
        this.codexCliCommandBuilder = new CodexCliCommandBuilder();
    }

    @Test
    void givenPromptWithSingleQuote_whenBuild_thenReturnShellQuotedCommand() {
        //given
        final String promptFilePath = "/tmp/it's-test.json";

        //when
        final String actual = this.codexCliCommandBuilder.buildFromPromptFile(promptFilePath);

        //then
        assertThat(actual).contains("bash ");
        assertThat(actual).contains("run-codex-with-prompt-file.sh");
        assertThat(actual).contains("'/tmp/it'\"'\"'s-test.json'");
    }

    @Test
    void givenForgeAiModulePath_whenNormalizeWorkspaceRoot_thenReturnSamePath() {
        //given
        final String workspaceRoot = "/Users/test/sitionix/forge-ai";

        //when
        final String actual = this.codexCliCommandBuilder.normalizeWorkspaceRoot(workspaceRoot);

        //then
        assertThat(actual).isEqualTo("/Users/test/sitionix/forge-ai");
    }

    @Test
    void givenRepoRootWithForgeAiModule_whenResolveScriptPath_thenReturnRepoLevelPath() throws Exception {
        //given
        final Path root = Files.createTempDirectory("codex-builder-root");
        final Path script = root.resolve("forge-ai/infrastructure/codex-cli/src/main/resources/scripts/run-codex-with-prompt-file.sh");
        Files.createDirectories(script.getParent());
        Files.createFile(script);

        //when
        final String actual = this.codexCliCommandBuilder.resolveScriptPath(root.toString());

        //then
        assertThat(actual).isEqualTo(script.toAbsolutePath().normalize().toString());
    }

    @Test
    void givenForgeAiModuleRoot_whenResolveScriptPath_thenReturnModuleLevelPath() throws Exception {
        //given
        final Path root = Files.createTempDirectory("codex-builder-module-root");
        final Path script = root.resolve("infrastructure/codex-cli/src/main/resources/scripts/run-codex-with-prompt-file.sh");
        Files.createDirectories(script.getParent());
        Files.createFile(script);

        //when
        final String actual = this.codexCliCommandBuilder.resolveScriptPath(root.toString());

        //then
        assertThat(actual).isEqualTo(script.toAbsolutePath().normalize().toString());
    }
}
