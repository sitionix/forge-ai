package com.sitionix.forgeai.domain.model.codex;

public class CodexTurnInterruptedException extends IllegalStateException {

    public CodexTurnInterruptedException(final String message) {
        super(message);
    }
}
