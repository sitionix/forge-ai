package com.sitionix.forgeai.domain.props;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import java.util.List;
import java.util.Map;

public interface ServiceConfigView {

    String getLabel();

    String getPath();

    String getRepo();

    ServiceGroup getGroup();

    List<String> getTags();

    List<String> getTests();

    List<String> getDomainKeywords();

    List<String> getOwnsBusinessAreas();

    List<String> getArchitectureRefs();

    Map<String, ContractRefView> getContractRefs();

    DeployConfigView getDeploy();

    DbConfigView getDb();
}
