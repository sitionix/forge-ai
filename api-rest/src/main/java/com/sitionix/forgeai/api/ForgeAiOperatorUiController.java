package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketListResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentConfigResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/forge-ai/operator/ui")
public class ForgeAiOperatorUiController {

    private final GetOperatorUiReadModel getOperatorUiReadModel;
    private final ManageOperatorAgentConfig manageOperatorAgentConfig;

    @GetMapping("/tickets")
    public OperatorUiTicketListResponse tickets(@RequestParam(name = "limit", required = false) final Integer limit) {
        return this.getOperatorUiReadModel.tickets(limit);
    }

    @GetMapping("/tickets/{ticketId}/graph")
    public OperatorUiTicketGraphResponse graph(@PathVariable final UUID ticketId) {
        return this.getOperatorUiReadModel.graph(ticketId);
    }

    @GetMapping("/agents/config")
    public OperatorAgentConfigResponse agentConfig() {
        return this.manageOperatorAgentConfig.config();
    }

    @PutMapping("/agents/config/resources")
    public OperatorConfigResourceView saveAgentConfigResource(@RequestBody final OperatorConfigResourceSaveRequest request) {
        return this.manageOperatorAgentConfig.saveResource(request);
    }
}
