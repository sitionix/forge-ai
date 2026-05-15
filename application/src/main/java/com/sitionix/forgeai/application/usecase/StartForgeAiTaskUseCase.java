package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import com.sitionix.forgeai.domain.port.ForgeAiTaskRunner;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StartForgeAiTaskUseCase implements StartForgeAiTask {

    private final ForgeAiTaskRunner forgeAiTaskRunner;

    @Override
    public ForgeAiStartTask execute(final ForgeAiStartCommand command) {
        return this.forgeAiTaskRunner.run(command);
    }
}
