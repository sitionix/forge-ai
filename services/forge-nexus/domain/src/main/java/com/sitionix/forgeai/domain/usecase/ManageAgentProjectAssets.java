package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetCapabilities;
import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectAssetCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentAssetMonitoringCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ReplaceAgentAssetMonitoringCommand;
import java.util.List;
import java.util.UUID;

public interface ManageAgentProjectAssets {
    List<AgentProjectAsset> list(UUID projectId);

    AgentProjectAsset create(UUID projectId, CreateAgentProjectAssetCommand command);

    AgentProjectAsset get(UUID projectId, UUID assetId);

    AgentAssetMetrics metrics(UUID projectId, UUID assetId);

    AgentAssetCapabilities capabilities(UUID projectId, UUID assetId);

    List<AgentLogSource> monitoring(UUID projectId, UUID assetId);

    AgentLogSource monitor(UUID projectId, UUID assetId, SaveAgentAssetMonitoringCommand command);

    List<AgentLogSource> replaceMonitoring(UUID projectId, UUID assetId, ReplaceAgentAssetMonitoringCommand command);

    void delete(UUID projectId, UUID assetId);
}
