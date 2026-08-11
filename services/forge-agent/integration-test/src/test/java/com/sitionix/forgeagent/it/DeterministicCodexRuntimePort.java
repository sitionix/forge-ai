package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.domain.model.CodexRuntimeEffort;
import com.sitionix.forgeagent.domain.model.CodexRuntimeModel;
import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import com.sitionix.forgeagent.domain.port.CodexRuntimePort;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DeterministicCodexRuntimePort implements CodexRuntimePort {

    private final AtomicReference<CodexRuntimeProvider> provider = new AtomicReference<>(readyProvider());

    @Override
    public CodexRuntimeProvider getModels() {
        return this.provider.get();
    }

    public void ready() {
        this.provider.set(readyProvider());
    }

    public void unavailable() {
        this.provider.set(new CodexRuntimeProvider("codex", "Codex", RuntimeProviderStatus.UNAVAILABLE, null, List.of()));
    }

    private static CodexRuntimeProvider readyProvider() {
        return new CodexRuntimeProvider(
                "codex",
                "Codex",
                RuntimeProviderStatus.READY,
                "codex-it/1.0.0",
                List.of(
                        new CodexRuntimeModel(
                                "discovered-model",
                                "Discovered Model",
                                "Deterministic model",
                                List.of(new CodexRuntimeEffort("medium", "Balanced reasoning"))
                        ),
                        new CodexRuntimeModel(
                                "model-b",
                                "Model B",
                                "Second deterministic model",
                                List.of(new CodexRuntimeEffort("xhigh", "Maximum reasoning"))
                        ),
                        new CodexRuntimeModel(
                                "no-effort-model",
                                "No Effort Model",
                                "No effort model",
                                List.of()
                        )
                )
        );
    }
}
