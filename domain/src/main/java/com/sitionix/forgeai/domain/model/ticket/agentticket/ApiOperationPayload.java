package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiOperationPayload implements AgentTicketPayload {

    private String intent;
    private String method;
    private String path;
    private Set<ApiParameterPayload> parameters;
    private ApiPayloadShape request;
    private ApiPayloadShape response;
}
