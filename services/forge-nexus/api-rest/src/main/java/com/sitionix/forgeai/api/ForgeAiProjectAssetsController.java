package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentAssetCapabilitiesResponse;
import com.sitionix.forgeai.api.agentproxy.AgentAssetMetricsResponse;
import com.sitionix.forgeai.api.agentproxy.AgentAssetMonitoringRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectAssetRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectAssetResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectAssets;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/assets")
public class ForgeAiProjectAssetsController {
    private final ManageAgentProjectAssets assets;
    private final AgentProxyApiMapper mapper;

    @GetMapping
    public List<AgentProjectAssetResponse> list(@PathVariable final UUID projectId) {
        return this.assets.list(projectId).stream().map(this.mapper::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<AgentProjectAssetResponse> create(
            @PathVariable final UUID projectId,
            @Valid @RequestBody final AgentProjectAssetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.mapper.toResponse(this.assets.create(projectId, this.mapper.toCommand(request))));
    }

    @GetMapping("/{assetId}")
    public AgentProjectAssetResponse get(@PathVariable final UUID projectId, @PathVariable final UUID assetId) {
        return this.mapper.toResponse(this.assets.get(projectId, assetId));
    }

    @GetMapping("/{assetId}/metrics")
    public AgentAssetMetricsResponse metrics(@PathVariable final UUID projectId, @PathVariable final UUID assetId) {
        return this.mapper.toResponse(this.assets.metrics(projectId, assetId));
    }

    @GetMapping("/{assetId}/capabilities")
    public AgentAssetCapabilitiesResponse capabilities(
            @PathVariable final UUID projectId, @PathVariable final UUID assetId) {
        return this.mapper.toResponse(this.assets.capabilities(projectId, assetId));
    }

    @GetMapping("/{assetId}/monitoring")
    public List<AgentLogSourceResponse> monitoring(
            @PathVariable final UUID projectId, @PathVariable final UUID assetId) {
        return this.assets.monitoring(projectId, assetId).stream().map(this.mapper::toResponse).toList();
    }

    @PostMapping("/{assetId}/monitoring")
    public ResponseEntity<AgentLogSourceResponse> monitor(
            @PathVariable final UUID projectId,
            @PathVariable final UUID assetId,
            @Valid @RequestBody final AgentAssetMonitoringRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.mapper.toResponse(
                        this.assets.monitor(projectId, assetId, this.mapper.toCommand(request))));
    }

    @DeleteMapping("/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID projectId, @PathVariable final UUID assetId) {
        this.assets.delete(projectId, assetId);
    }
}
