package com.sitionix.forgeagent.domain.port.library;

import com.sitionix.forgeagent.domain.model.library.*;
public interface ToolInventoryPort { InventoryResult<ToolDescriptor> discover(LibraryContext context); }
