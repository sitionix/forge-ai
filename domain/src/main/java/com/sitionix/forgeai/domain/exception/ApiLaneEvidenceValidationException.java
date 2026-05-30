package com.sitionix.forgeai.domain.exception;

import lombok.Getter;

@Getter
public class ApiLaneEvidenceValidationException extends RuntimeException {

    private final String code;
    private final String hint;

    public ApiLaneEvidenceValidationException(final String code, final String message, final String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }
}
