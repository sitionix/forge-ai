package com.sitionix.forgeai.domain.port;

public interface CorrelationIdProvider {

    String currentOrCreate();

    String preserveOrCurrent(String supplied);
}
