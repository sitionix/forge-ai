package com.sitionix.forgeagent.domain.port.library;

public interface LibraryProviderSession extends AutoCloseable {
 SkillInventoryPort skills(); AgentInventoryPort agents(); FlowInventoryPort flows(); ToolInventoryPort tools();
 @Override void close();
}
