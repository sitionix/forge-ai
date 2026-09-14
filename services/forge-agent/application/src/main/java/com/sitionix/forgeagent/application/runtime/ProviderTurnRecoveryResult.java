package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.util.Objects;

public record ProviderTurnRecoveryResult(
        ProviderTurnRecoveryState state,
        ProviderTurnRecoveryTerminalOutcome terminalOutcome,
        String diagnostic
) {
    public ProviderTurnRecoveryResult {
        state = Objects.requireNonNull(state, "state");
        terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static ProviderTurnRecoveryResult terminal(final ProviderTurnRecoveryTerminalOutcome terminalOutcome,
                                                      final String diagnostic) {
        return new ProviderTurnRecoveryResult(ProviderTurnRecoveryState.TERMINAL, terminalOutcome, diagnostic);
    }

    public static ProviderTurnRecoveryResult active(final String diagnostic) {
        return new ProviderTurnRecoveryResult(
                ProviderTurnRecoveryState.ACTIVE, ProviderTurnRecoveryTerminalOutcome.UNKNOWN, diagnostic);
    }

    public static ProviderTurnRecoveryResult unknown(final String diagnostic) {
        return new ProviderTurnRecoveryResult(
                ProviderTurnRecoveryState.UNKNOWN, ProviderTurnRecoveryTerminalOutcome.UNKNOWN, diagnostic);
    }
}
