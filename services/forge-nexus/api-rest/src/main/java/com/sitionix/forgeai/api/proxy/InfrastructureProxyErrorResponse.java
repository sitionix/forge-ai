package com.sitionix.forgeai.api.proxy;

record InfrastructureProxyErrorResponse(
        String code,
        String message,
        String correlationId,
        Integer upstreamStatus,
        String route
) {
}
