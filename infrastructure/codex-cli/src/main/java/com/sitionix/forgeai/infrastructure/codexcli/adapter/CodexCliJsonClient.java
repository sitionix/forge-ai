package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.port.CodexClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexCliJsonClient implements CodexClient {

    private final ObjectMapper objectMapper;
    private final CodexCliCommandBuilder codexCliCommandBuilder;
    private final TerminalTabLauncher terminalTabLauncher;

    @Override
    public void submit(final Object payload, final String sourceTerminalTty) {
        final String jsonPayload = this.toJson(payload);
        final String promptFilePath = this.writePromptToTempFile(jsonPayload);
        this.terminalTabLauncher.launch(this.codexCliCommandBuilder.buildFromPromptFile(promptFilePath), sourceTerminalTty);
    }

    private String toJson(final Object payload) {
        try {
            return this.objectMapper.writeValueAsString(payload);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payload for Codex CLI", e);
        }
    }

    private String writePromptToTempFile(final String prompt) {
        try {
            final Path file = Files.createTempFile("forge-ai-codex-prompt-", ".json");
            Files.writeString(file, prompt, StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to write Codex prompt file", e);
        }
    }
}
