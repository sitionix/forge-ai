package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import java.util.List;
import java.util.Map;

/**
 * Facade port for YAML service properties.
 */
public interface ServicePropertiesProvider {

    Map<String, ServiceConfigView> getServices();

    interface ServiceConfigView {
        String getLabel();

        String getPath();

        ServiceGroup getGroup();

        List<String> getTags();

        List<String> getTests();

        List<String> getDomainKeywords();

        List<String> getOwnsBusinessAreas();

        List<String> getArchitectureRefs();

        Map<String, Object> getContractRefs();

        DeployConfigView getDeploy();

        DbConfigView getDb();
    }

    interface DeployConfigView {
        String getType();

        String getRepo();

        DeployUnitConfigView getService();

        DeployUnitConfigView getDb();
    }

    interface DbConfigView {
        Boolean getRequired();

        String getType();

        String getMode();

        String getKey();
    }

    interface DeployUnitConfigView {
        String getName();

        String getWorkflowName();

        String getWorkflowEvent();
    }
}
