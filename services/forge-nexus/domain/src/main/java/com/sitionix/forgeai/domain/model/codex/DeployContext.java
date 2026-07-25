package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeployContext {

    private String type;
    private String repo;
    private DeployUnitContext service;
    private DeployUnitContext db;
}
