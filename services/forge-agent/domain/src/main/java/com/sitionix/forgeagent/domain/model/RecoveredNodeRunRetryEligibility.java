package com.sitionix.forgeagent.domain.model;

public record RecoveredNodeRunRetryEligibility(
        RecoveredNodeRunRetryAction action,
        String reasonCode
) {
    public static RecoveredNodeRunRetryEligibility retry() {
        return new RecoveredNodeRunRetryEligibility(RecoveredNodeRunRetryAction.RETRY, null);
    }

    public static RecoveredNodeRunRetryEligibility resume() {
        return new RecoveredNodeRunRetryEligibility(RecoveredNodeRunRetryAction.RESUME, null);
    }

    public static RecoveredNodeRunRetryEligibility none(final String reasonCode) {
        return new RecoveredNodeRunRetryEligibility(RecoveredNodeRunRetryAction.NONE, reasonCode);
    }

    public boolean eligible() {
        return this.action != RecoveredNodeRunRetryAction.NONE;
    }
}
