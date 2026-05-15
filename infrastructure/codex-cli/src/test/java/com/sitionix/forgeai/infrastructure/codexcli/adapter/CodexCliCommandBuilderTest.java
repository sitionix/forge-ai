package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        final String prompt = "it's test";

        //when
        final String actual = this.codexCliCommandBuilder.build(prompt);

        //then
        assertThat(actual).contains("exec codex --no-alt-screen -C");
        assertThat(actual).contains("'it'\"'\"'s test'");
        assertThat(actual).contains("[forge-ai] starting interactive codex");
    }
}
