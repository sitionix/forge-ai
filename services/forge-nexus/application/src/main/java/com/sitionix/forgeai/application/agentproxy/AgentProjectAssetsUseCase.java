package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetCapabilities;
import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectAssetCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentAssetMonitoringCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectAssets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentProjectAssetsUseCase implements ManageAgentProjectAssets {
    private final ForgeAgentClient client;

    @Override
    public List<AgentProjectAsset> list(final UUID projectId) {
        return this.client.listProjectAssets(projectId);
    }

    @Override
    public AgentProjectAsset create(final UUID projectId, final CreateAgentProjectAssetCommand command) {
        return this.client.createProjectAsset(projectId, command);
    }

    @Override
    public AgentProjectAsset get(final UUID projectId, final UUID assetId) {
        return this.client.getProjectAsset(projectId, assetId);
    }

    @Override
    public AgentAssetMetrics metrics(final UUID projectId, final UUID assetId) {
        return this.client.getProjectAssetMetrics(projectId, assetId);
    }

    @Override
    public AgentAssetCapabilities capabilities(final UUID projectId, final UUID assetId) {
        return this.client.getProjectAssetCapabilities(projectId, assetId);
    }

    @Override
    public List<AgentLogSource> monitoring(final UUID projectId, final UUID assetId) {
        return this.client.listProjectAssetMonitoring(projectId, assetId);
    }

    @Override
    public AgentLogSource monitor(
            final UUID projectId,
            final UUID assetId,
            final SaveAgentAssetMonitoringCommand command) {
        return this.client.createProjectAssetMonitoring(projectId, assetId, command);
    }

    @Override
    public void delete(final UUID projectId, final UUID assetId) {
        this.client.deleteProjectAsset(projectId, assetId);
    }
}
