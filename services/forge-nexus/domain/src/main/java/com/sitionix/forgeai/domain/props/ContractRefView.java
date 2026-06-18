package com.sitionix.forgeai.domain.props;

import java.util.List;

public interface ContractRefView {

    String getSourceRepo();

    String getApiFamily();

    String getEventFamily();

    String getServiceCode();

    String getRoot();

    List<String> getSchemas();

    List<String> getOperations();

    List<String> getTopics();

    List<String> getPayloads();

    List<String> getGeneratedArtifacts();

    List<String> getConsumerArtifacts();

    List<String> getFrontendPackages();
}
