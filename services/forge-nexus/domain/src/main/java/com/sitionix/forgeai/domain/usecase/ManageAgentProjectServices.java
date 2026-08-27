package com.sitionix.forgeai.domain.usecase;
import com.sitionix.forgeai.domain.model.agentproxy.*; import java.util.*;
public interface ManageAgentProjectServices { List<AgentProjectService> list(UUID p); AgentProjectService create(UUID p,SaveAgentProjectServiceCommand c); AgentProjectService get(UUID p,UUID s); AgentProjectService update(UUID p,UUID s,SaveAgentProjectServiceCommand c); void delete(UUID p,UUID s); AgentServiceRuntime runtime(UUID p,UUID s); List<AgentLogSource> logs(UUID p,UUID s); }
