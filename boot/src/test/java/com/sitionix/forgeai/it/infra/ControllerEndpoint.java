package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public class ControllerEndpoint {

    public static Endpoint<StartForgeRequestDTO, StartForgeResponseDTO> startForge() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/start",
                HttpMethod.POST,
                StartForgeRequestDTO.class,
                StartForgeResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestStartForge.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .header("X-Terminal-TTY", "/dev/ttys999")
        );
    }

    private ControllerEndpoint() {
    }
}
