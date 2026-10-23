package com.sitionix.forgeagent.domain.port.library;

import com.sitionix.forgeagent.domain.model.library.*;
public interface SkillInventoryPort { InventoryResult<SkillDescriptor> discover(LibraryContext context); }
