package com.sitionix.forgeai.api.proxy;

class InfrastructureProxyBodyTooLargeException extends RuntimeException {

    InfrastructureProxyBodyTooLargeException() {
        super("Upstream response body exceeded the configured proxy limit.");
    }
}
