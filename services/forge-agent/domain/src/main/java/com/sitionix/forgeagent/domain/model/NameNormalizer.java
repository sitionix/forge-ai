package com.sitionix.forgeagent.domain.model;

import java.util.Locale;

public final class NameNormalizer {

    private NameNormalizer() {
    }

    public static String normalize(final String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
