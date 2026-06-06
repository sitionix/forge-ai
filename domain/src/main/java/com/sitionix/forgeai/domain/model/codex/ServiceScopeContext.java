package com.sitionix.forgeai.domain.model.codex;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServiceScopeContext {

    private String serviceId;
    private String scope;
    private String label;
    private String path;
    private ServiceGroup group;
    private List<String> tags;
    private List<String> tests;
    private List<String> domainKeywords;
    private List<String> ownBusinessAreas;
    private List<String> architectureRefs;
    private Map<String, ContractRefContext> contractRefs;
    private DeployContext deploy;
    private DbContext db;
}
