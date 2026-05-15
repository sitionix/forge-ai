package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiApi;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ForgeAiController implements ForgeAiApi {

    private final StartForgeAiTask startForgeAiTask;
    private final ForgeAiApiMapper forgeAiApiMapper;
    private final TerminalTtyResolver terminalTtyResolver;

    @Override
    public ResponseEntity<StartForgeResponseDTO> startForge(@Valid final StartForgeRequestDTO startForgeRequestDTO) {

        final ForgeAiStartCommand command = this.forgeAiApiMapper
                .asForgeAiStartCommand(startForgeRequestDTO, this.terminalTtyResolver.resolve());
        final Ticket startedTask = this.startForgeAiTask.execute(command);
        final StartForgeResponseDTO response = this.forgeAiApiMapper.asStartForgeResponseDto(startedTask);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
