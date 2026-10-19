package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
import java.util.*;
public interface TriggerBindingRepository {
 Optional<TriggerBinding> findByNodeId(UUID workflowId, UUID nodeId);
 List<TriggerConfiguration> findConfigurations(UUID connectionId);
 Optional<TriggerConfiguration> findConfiguration(UUID id);
 void save(UUID workflowId, TriggerBinding binding);
 void delete(UUID workflowId, UUID nodeId);
}
