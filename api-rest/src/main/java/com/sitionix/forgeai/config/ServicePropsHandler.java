package com.sitionix.forgeai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServicePropsHandler {

    private final ServiceProps serviceProps;

    public ServiceProps.ServiceConfig getRequiredService(final String serviceId) {
        if (this.serviceProps.getServices() == null || !this.serviceProps.getServices().containsKey(serviceId)) {
            throw new ServicePropertyMissingException("Service config is missing for serviceId: " + serviceId);
        }
        final ServiceProps.ServiceConfig serviceConfig = this.serviceProps.getServices().get(serviceId);
        if (serviceConfig == null) {
            throw new ServicePropertyMissingException("Service config is null for serviceId: " + serviceId);
        }
        return serviceConfig;
    }

    public String getRequiredDeployRepo(final String serviceId) {
        final ServiceProps.ServiceConfig serviceConfig = this.getRequiredService(serviceId);
        if (serviceConfig.getDeploy() == null) {
            throw new ServicePropertyMissingException("Property is missing for serviceId " + serviceId + ": deploy");
        }
        if (serviceConfig.getDeploy().getRepo() == null || serviceConfig.getDeploy().getRepo().isBlank()) {
            throw new ServicePropertyMissingException("Property is missing for serviceId " + serviceId + ": deploy.repo");
        }
        return serviceConfig.getDeploy().getRepo();
    }
}
