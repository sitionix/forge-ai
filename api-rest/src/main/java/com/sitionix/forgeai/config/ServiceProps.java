package com.sitionix.forgeai.config;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "")
public class ServiceProps {

    private Map<String, ServiceConfig> services;

    @Getter
    @Setter
    public static class ServiceConfig {
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
    }

    @Getter
    @Setter
    public static class DeployConfig {
        private String type;
        private String repo;
        private DeployUnitConfig service;
        private DeployUnitConfig db;
    }

    @Getter
    @Setter
    public static class DbConfig {
        private Boolean required;
        private DbType type;
        private String mode;
        private String key;
    }

    @Getter
    @Setter
    public static class DeployUnitConfig {
        private String name;
        private String workflowName;
        private String workflowEvent;
    }

    public enum ServiceGroup {
        @JsonProperty("backend")
        BACKEND,
        @JsonProperty("frontend")
        FRONTEND,
        @JsonProperty("tool")
        TOOL
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
