package com.sitionix.forgeagent.domain.port.library;

import com.sitionix.forgeagent.domain.model.library.*;
public interface FlowInventoryPort { InventoryResult<FlowDescriptor> discover(LibraryContext context); }
