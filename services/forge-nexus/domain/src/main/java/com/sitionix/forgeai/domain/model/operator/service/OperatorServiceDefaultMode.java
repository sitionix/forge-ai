package com.sitionix.forgeai.domain.model.operator.service;

import java.util.Locale;

public enum OperatorServiceDefaultMode {
    CHECKOUT,
    COMMIT,
    STASH;

    public static OperatorServiceDefaultMode from(final String value) {
        if (value == null || value.isBlank()) {
            return CHECKOUT;
        }
        return OperatorServiceDefaultMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
