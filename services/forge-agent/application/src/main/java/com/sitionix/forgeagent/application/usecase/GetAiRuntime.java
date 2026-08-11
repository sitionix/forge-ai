package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AiRuntimeCatalog;
import com.sitionix.forgeagent.domain.port.CodexRuntimePort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetAiRuntime {

    private final CodexRuntimePort codexRuntimePort;

    @Transactional(readOnly = true)
    public AiRuntimeCatalog execute() {
        return new AiRuntimeCatalog(List.of(this.codexRuntimePort.getModels()));
    }
}
