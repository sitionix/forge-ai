package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class ScopeContext {

    private String scope;
    private ServiceScopeContext service;
    private Set<ServiceScopeContext> relatedServices;
}
