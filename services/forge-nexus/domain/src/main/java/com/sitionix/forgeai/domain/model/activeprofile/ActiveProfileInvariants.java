package com.sitionix.forgeai.domain.model.activeprofile;

final class ActiveProfileInvariants {

    static long positive(final long value, final String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    static String text(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static <T> T required(final T value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    private ActiveProfileInvariants() {
    }
}
