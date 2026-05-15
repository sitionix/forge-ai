package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServicePropertiesTest {

    private ServiceProperties serviceProperties;

    @BeforeEach
    void setUp() {
        this.serviceProperties = new ServiceProperties();
    }

    @Test
    void givenServicesConfigured_whenGetServices_thenReturnFacadeMap() {
        //given
        final ServiceProperties.ServiceConfig serviceConfig = new ServiceProperties.ServiceConfig();
        serviceConfig.setLabel("Forge AI");
        this.serviceProperties.setServices(Map.of("forgeai", serviceConfig));

        //when
        final Map<String, ServicePropertiesProvider.ServiceConfigView> actual = this.serviceProperties.getServices();

        //then
        assertThat(actual).hasSize(1);
        assertThat(actual.get("forgeai").getLabel()).isEqualTo("Forge AI");
    }
}
