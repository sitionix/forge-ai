package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class ScopeContext {

    private String scope;
    private String label;
    private Set<String> tags;
    private Set<String> domainKeywords;
    private Set<String> ownBusinessAreas;
}
