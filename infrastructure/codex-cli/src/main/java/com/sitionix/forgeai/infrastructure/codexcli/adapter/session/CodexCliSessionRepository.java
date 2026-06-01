package com.sitionix.forgeai.infrastructure.codexcli.adapter.session;

import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component
@RequiredArgsConstructor
public class CodexCliSessionRepository implements CodexSessionRepository {

    private final CodexCliCommandBuilder commandBuilder;
    private final TerminalTabLauncher terminalTabLauncher;
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();

    @Override
    public String start(final String initialPrompt, final String sourceTerminalTty) {
        final String sessionId = UUID.randomUUID().toString();
        this.send(sessionId, initialPrompt, sourceTerminalTty);
        this.sessions.put(sessionId, true);
        return sessionId;
    }

    @Override
    public void send(final String sessionId, final String message, final String sourceTerminalTty) {
        try {
            final Path file = Files.createTempFile("forge-ai-codex-step-", ".txt");
            Files.writeString(file, message, StandardCharsets.UTF_8);
            this.terminalTabLauncher.launch(this.commandBuilder.buildFromPromptFile(file.toAbsolutePath().toString()), sourceTerminalTty);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send message to Codex session", e);
        }
    }

    @Override
    public String waitForOutput(final String sessionId, final long timeoutMs) {
        throw new UnsupportedOperationException("Current Codex CLI adapter does not support interactive output polling in same session. "
                + "TODO: replace with interactive session adapter (PTY/streaming).");
    }

    @Override
    public boolean isAlive(final String sessionId) {
        return this.sessions.getOrDefault(sessionId, false);
    }

    @Override
    public void close(final String sessionId) {
        this.sessions.remove(sessionId);
    }
}
