package com.sitionix.forgeai.domain.port;

import java.util.regex.Pattern;

public interface CorrelationIdProvider {

    String HEADER_NAME = "X-Correlation-Id";
    Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    String currentOrCreate();

    static boolean isValid(final String value) {
        return value != null && VALID_CORRELATION_ID.matcher(value).matches();
    }
}
