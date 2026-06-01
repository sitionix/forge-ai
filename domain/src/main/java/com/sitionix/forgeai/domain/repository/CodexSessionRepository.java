package com.sitionix.forgeai.domain.repository;

public interface CodexSessionRepository {

    String start(String initialPrompt, String sourceTerminalTty);

    void send(String sessionId, String message, String sourceTerminalTty);

    String waitForOutput(String sessionId, long timeoutMs);

    boolean isAlive(String sessionId);

    void close(String sessionId);
}
