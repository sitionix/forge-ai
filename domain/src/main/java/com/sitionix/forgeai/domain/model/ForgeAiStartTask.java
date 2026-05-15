package com.sitionix.forgeai.domain.model;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ForgeAiStartTask {
    String id;
    String ticket;
    String task;
    String scope;
    String status;
    Instant createdAt;
}
