package com.sitionix.forgeai.domain.port;

/**
 * Port for submitting execution payloads to Codex.
 */
public interface CodexClient {

    /**
     * Submits payload to Codex execution channel.
     *
     * @param payload serializable request payload
     * @param sourceTerminalTty source terminal tty for tab/session routing
     */
    void submit(Object payload, String sourceTerminalTty);
}
