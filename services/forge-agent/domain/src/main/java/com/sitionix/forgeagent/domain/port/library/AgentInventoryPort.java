package com.sitionix.forgeagent.domain.port.library;

import com.sitionix.forgeagent.domain.model.library.*;
public interface AgentInventoryPort { InventoryResult<AgentDescriptor> discover(LibraryContext context); }
