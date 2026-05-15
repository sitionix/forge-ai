package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "")
public class ServiceProperties implements ServicePropertiesProvider {

    private Map<String, ServiceConfig> services;

    @Override
    public Map<String, ServiceConfigView> getServices() {
        return this.services == null ? null : Map.copyOf(this.services);
    }

    @Getter
    @Setter
    public static class ServiceConfig implements ServicePropertiesProvider.ServiceConfigView {
        private String label;
        private String path;
        private ServiceGroup group;
        private List<String> tags;
        private List<TestType> tests;
        private List<String> domainKeywords;
        private List<String> ownsBusinessAreas;
        private List<String> architectureRefs;
        private Map<String, Object> contractRefs;
        private DeployConfig deploy;
        private DbConfig db;

        @Override
        public List<String> getTests() {
            return this.tests == null ? null : this.tests.stream().map(value -> value.name().toLowerCase()).toList();
        }
    }

    @Getter
    @Setter
    public static class DeployConfig implements ServicePropertiesProvider.DeployConfigView {
        private String type;
        private String repo;
        private DeployUnitConfig service;
        private DeployUnitConfig db;
    }

    @Getter
    @Setter
    public static class DbConfig implements ServicePropertiesProvider.DbConfigView {
        private Boolean required;
        private DbType type;
        private String mode;
        private String key;

        @Override
        public String getType() {
            return this.type == null ? null : this.type.name().toLowerCase();
        }
    }

    @Getter
    @Setter
    public static class DeployUnitConfig implements ServicePropertiesProvider.DeployUnitConfigView {
        private String name;
        private String workflowName;
        private String workflowEvent;
    }

    public enum TestType {
        @JsonProperty("unit")
        UNIT,
        @JsonProperty("it")
        IT,
        @JsonProperty("e2e")
        E2E
    }

    public enum DbType {
        @JsonProperty("postgresql")
        POSTGRESQL,
        @JsonProperty("mongodb")
        MONGODB,
        @JsonProperty("none")
        NONE
    }
}
