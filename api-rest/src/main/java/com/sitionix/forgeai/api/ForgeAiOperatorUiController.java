package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketListResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/forge-ai/operator/ui")
public class ForgeAiOperatorUiController {

    private final GetOperatorUiReadModel getOperatorUiReadModel;

    @GetMapping("/tickets")
    public OperatorUiTicketListResponse tickets(@RequestParam(name = "limit", required = false) final Integer limit) {
        return this.getOperatorUiReadModel.tickets(limit);
    }

    @GetMapping("/tickets/{ticketId}/graph")
    public OperatorUiTicketGraphResponse graph(@PathVariable final UUID ticketId) {
        return this.getOperatorUiReadModel.graph(ticketId);
    }
}
