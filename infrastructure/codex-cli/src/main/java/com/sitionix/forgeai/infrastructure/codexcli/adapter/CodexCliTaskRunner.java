package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import com.sitionix.forgeai.domain.port.ForgeAiTaskRunner;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexCliTaskRunner implements ForgeAiTaskRunner {

    private final CodexCliJsonClient codexCliJsonClient;

    @Override
    public ForgeAiStartTask run(final ForgeAiStartCommand command) {
        this.codexCliJsonClient.submit(command, command.getSourceTerminalTty());

        return ForgeAiStartTask.builder()
                .id(UUID.randomUUID().toString())
                .ticket(command.getTicket())
                .task(command.getTask())
                .scope(command.getScope())
                .status("SUBMITTED")
                .createdAt(Instant.now())
                .build();
    }
}
