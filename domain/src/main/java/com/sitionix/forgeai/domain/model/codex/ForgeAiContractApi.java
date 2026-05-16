package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ForgeAiContractApi {

    final String path;
    final String endpoint;
}
