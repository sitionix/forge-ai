package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexCliJsonClientTest {

    private CodexCliJsonClient codexCliJsonClient;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Mock
    private TerminalTabLauncher terminalTabLauncher;

    @BeforeEach
    void setUp() {
        this.codexCliJsonClient = new CodexCliJsonClient(this.objectMapper, this.codexCliCommandBuilder, this.terminalTabLauncher);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.objectMapper, this.codexCliCommandBuilder, this.terminalTabLauncher);
    }

    @Test
    void givenPayloadAndTerminalTty_whenSubmit_thenSerializeBuildAndLaunch() throws JsonProcessingException {
        //given
        final Map<String, String> payload = Map.of("ticket", "SITIONIX-1");
        when(this.objectMapper.writeValueAsString(payload)).thenReturn("{\"ticket\":\"SITIONIX-1\"}");
        when(this.codexCliCommandBuilder.buildFromPromptFile(anyString()))
                .thenReturn("codex-cmd");

        //when
        this.codexCliJsonClient.submit(payload, "/dev/ttys008");

        //then
        verify(this.objectMapper).writeValueAsString(payload);
        verify(this.codexCliCommandBuilder).buildFromPromptFile(anyString());
        verify(this.terminalTabLauncher).launch("codex-cmd", "/dev/ttys008");
    }

    @Test
    void givenSerializationFails_whenSubmit_thenThrowIllegalStateException() throws JsonProcessingException {
        //given
        final Object payload = new Object();
        when(this.objectMapper.writeValueAsString(payload)).thenThrow(new JsonProcessingException("boom") { });

        //when
        //then
        assertThatThrownBy(() -> this.codexCliJsonClient.submit(payload, "/dev/ttys008"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize payload for Codex CLI");
        verify(this.objectMapper).writeValueAsString(payload);
    }
}
