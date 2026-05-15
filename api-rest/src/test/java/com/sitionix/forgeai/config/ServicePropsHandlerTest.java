package com.sitionix.forgeai.config;

import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ServicePropsHandlerTest {

    private ServicePropsHandler servicePropsHandler;

    private ServiceProps serviceProps;

    @BeforeEach
    void setUp() {
        this.serviceProps = new ServiceProps();
        this.servicePropsHandler = new ServicePropsHandler(this.serviceProps);
    }

    @Test
    void givenServiceExists_whenGetRequiredService_thenReturnServiceConfig() {
        //given
        final ServiceProps.ServiceConfig serviceConfig = new ServiceProps.ServiceConfig();
        this.serviceProps.setServices(Map.of("forgeai", serviceConfig));

        //when
        final ServiceProps.ServiceConfig actual = this.servicePropsHandler.getRequiredService("forgeai");

        //then
        assertThat(actual).isEqualTo(serviceConfig);
    }

    @Test
    void givenServicesMapIsNull_whenGetRequiredService_thenThrowServicePropertyMissingException() {
        //given
        this.serviceProps.setServices(null);

        //when
        //then
        assertThatThrownBy(() -> this.servicePropsHandler.getRequiredService("forgeai"))
                .isInstanceOf(ServicePropertyMissingException.class)
                .hasMessage("Service config is missing for serviceId: forgeai");
    }

    @Test
    void givenServiceMissing_whenGetRequiredService_thenThrowServicePropertyMissingException() {
        //given
        this.serviceProps.setServices(Map.of("athssox", mock(ServiceProps.ServiceConfig.class)));

        //when
        //then
        assertThatThrownBy(() -> this.servicePropsHandler.getRequiredService("forgeai"))
                .isInstanceOf(ServicePropertyMissingException.class)
                .hasMessage("Service config is missing for serviceId: forgeai");
    }

    @Test
    void givenServiceConfigIsNull_whenGetRequiredService_thenThrowServicePropertyMissingException() {
        //given
        final Map<String, ServiceProps.ServiceConfig> services = new HashMap<>();
        services.put("forgeai", null);
        this.serviceProps.setServices(services);

        //when
        //then
        assertThatThrownBy(() -> this.servicePropsHandler.getRequiredService("forgeai"))
                .isInstanceOf(ServicePropertyMissingException.class)
                .hasMessage("Service config is null for serviceId: forgeai");
    }

    @Test
    void givenDeployRepoExists_whenGetRequiredDeployRepo_thenReturnDeployRepo() {
        //given
        final ServiceProps.DeployConfig deployConfig = new ServiceProps.DeployConfig();
        deployConfig.setRepo("Sitionix/forge-ai");
        final ServiceProps.ServiceConfig serviceConfig = new ServiceProps.ServiceConfig();
        serviceConfig.setDeploy(deployConfig);
        this.serviceProps.setServices(Map.of("forgeai", serviceConfig));

        //when
        final String actual = this.servicePropsHandler.getRequiredDeployRepo("forgeai");

        //then
        assertThat(actual).isEqualTo("Sitionix/forge-ai");
    }

    @Test
    void givenDeployMissing_whenGetRequiredDeployRepo_thenThrowServicePropertyMissingException() {
        //given
        final ServiceProps.ServiceConfig serviceConfig = new ServiceProps.ServiceConfig();
        this.serviceProps.setServices(Map.of("forgeai", serviceConfig));

        //when
        //then
        assertThatThrownBy(() -> this.servicePropsHandler.getRequiredDeployRepo("forgeai"))
                .isInstanceOf(ServicePropertyMissingException.class)
                .hasMessage("Property is missing for serviceId forgeai: deploy");
    }

    @Test
    void givenDeployRepoBlank_whenGetRequiredDeployRepo_thenThrowServicePropertyMissingException() {
        //given
        final ServiceProps.DeployConfig deployConfig = new ServiceProps.DeployConfig();
        deployConfig.setRepo(" ");
        final ServiceProps.ServiceConfig serviceConfig = new ServiceProps.ServiceConfig();
        serviceConfig.setDeploy(deployConfig);
        this.serviceProps.setServices(Map.of("forgeai", serviceConfig));

        //when
        //then
        assertThatThrownBy(() -> this.servicePropsHandler.getRequiredDeployRepo("forgeai"))
                .isInstanceOf(ServicePropertyMissingException.class)
                .hasMessage("Property is missing for serviceId forgeai: deploy.repo");
    }
}
