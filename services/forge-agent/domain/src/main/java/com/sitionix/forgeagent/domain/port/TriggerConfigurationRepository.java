package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.TriggerConfiguration;
import com.sitionix.forgeagent.domain.model.TriggerConfigurationDocument;
import java.util.*;

public interface TriggerConfigurationRepository {
    boolean lockConnection(UUID installationId, UUID connectionId);
    List<TriggerConfiguration> findSummaries(UUID connectionId);
    Optional<TriggerConfigurationDocument> findById(UUID connectionId, UUID id);
    void save(TriggerConfigurationDocument document);
}
