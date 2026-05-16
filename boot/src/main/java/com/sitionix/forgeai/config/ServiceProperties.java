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
        private Map<String, ContractRefConfig> contractRefs;
        private DeployConfig deploy;
        private DbConfig db;

        @Override
        public List<String> getTests() {
            return this.tests == null ? null : this.tests.stream().map(value -> value.name().toLowerCase()).toList();
        }

        @Override
        public Map<String, ServicePropertiesProvider.ContractRefView> getContractRefs() {
            if (this.contractRefs == null) {
                return null;
            }
            return this.contractRefs.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            value -> value.getValue(),
                            (left, right) -> right,
                            java.util.LinkedHashMap::new
                    ));
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

    @Getter
    @Setter
    public static class ContractRefConfig implements ServicePropertiesProvider.ContractRefView {
        private String sourceRepo;
        private String apiFamily;
        private String eventFamily;
        private String serviceCode;
        private String root;
        private List<String> schemas;
        private List<String> operations;
        private List<String> topics;
        private List<String> payloads;
        private List<String> generatedArtifacts;
        private List<String> consumerArtifacts;
        private List<String> frontendPackages;
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
