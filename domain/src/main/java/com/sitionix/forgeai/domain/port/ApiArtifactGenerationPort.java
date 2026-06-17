package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;

public interface ApiArtifactGenerationPort {

    GeneratedApiArtifact generate(ApiArtifactGenerationRequest request);
}
