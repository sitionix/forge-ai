package com.sitionix.forgeai.domain.model.codex;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContractRefContext {

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
