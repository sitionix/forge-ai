package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexCliJsonClient {

    private final ObjectMapper objectMapper;
    private final CodexCliCommandBuilder codexCliCommandBuilder;
    private final TerminalTabLauncher terminalTabLauncher;

    public void submit(final Object payload, final String sourceTerminalTty) {
        final String jsonPayload = this.toJson(payload);
        final String prompt = "Привіт. Оброби цей запит: " + jsonPayload;
        this.terminalTabLauncher.launch(this.codexCliCommandBuilder.build(prompt), sourceTerminalTty);
    }

    private String toJson(final Object payload) {
        try {
            return this.objectMapper.writeValueAsString(payload);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payload for Codex CLI", e);
        }
    }
}
