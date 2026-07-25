package com.sitionix.forgeai.api.proxy;

class InfrastructureProxyRouteException extends RuntimeException {

    private final String route;

    InfrastructureProxyRouteException(final String route) {
        super("Infrastructure proxy route is not allowlisted.");
        this.route = route;
    }

    String route() {
        return this.route;
    }
}
