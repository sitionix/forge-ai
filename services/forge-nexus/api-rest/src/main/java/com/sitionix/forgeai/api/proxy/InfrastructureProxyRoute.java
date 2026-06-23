package com.sitionix.forgeai.api.proxy;

import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpMethod;

record InfrastructureProxyRoute(
        String key,
        InfrastructureProxyService service,
        HttpMethod method,
        Function<Map<String, String>, String> upstreamPath,
        boolean requestBodyAllowed,
        boolean jsonExpected
) {
}
