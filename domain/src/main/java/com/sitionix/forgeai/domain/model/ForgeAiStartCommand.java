package com.sitionix.forgeai.domain.model;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ForgeAiStartCommand {
    private String scope;
    private String ticket;
    private String task;
    private List<String> serviceIds;
    private String sourceTerminalTty;
}
