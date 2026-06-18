package com.sitionix.forgeai.domain.model.ticket.agentticket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImplementFeChangedFile {
    private String path;
    private String reason;
}
