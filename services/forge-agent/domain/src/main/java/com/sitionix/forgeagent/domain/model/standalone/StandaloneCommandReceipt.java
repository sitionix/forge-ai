package com.sitionix.forgeagent.domain.model.standalone;
import java.util.UUID;
public record StandaloneCommandReceipt(UUID sessionId,UUID turnId,long revision,boolean accepted) { }
