package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectServices;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/services")
public class ForgeAiProjectServicesController {
    private final ManageAgentProjectServices services;
    private final AgentProxyApiMapper mapper;

    @GetMapping
    public List<AgentProjectServiceResponse> list(@PathVariable final UUID projectId) {
        return this.services.list(projectId).stream().map(this.mapper::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<AgentProjectServiceResponse> create(
            @PathVariable final UUID projectId,
            @Valid @RequestBody final AgentProjectServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.mapper.toResponse(
                        this.services.create(projectId, this.mapper.toCommand(request))));
    }

    @GetMapping("/{serviceId}")
    public AgentProjectServiceResponse get(
            @PathVariable final UUID projectId, @PathVariable final UUID serviceId) {
        return this.mapper.toResponse(this.services.get(projectId, serviceId));
    }

    @PutMapping("/{serviceId}")
    public AgentProjectServiceResponse update(
            @PathVariable final UUID projectId,
            @PathVariable final UUID serviceId,
            @Valid @RequestBody final AgentProjectServiceRequest request) {
        return this.mapper.toResponse(
                this.services.update(projectId, serviceId, this.mapper.toCommand(request)));
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID projectId, @PathVariable final UUID serviceId) {
        this.services.delete(projectId, serviceId);
    }

    @GetMapping("/{serviceId}/runtime")
    public AgentServiceRuntimeResponse runtime(
            @PathVariable final UUID projectId, @PathVariable final UUID serviceId) {
        return this.mapper.toResponse(this.services.runtime(projectId, serviceId));
    }

    @GetMapping("/{serviceId}/log-sources")
    public List<AgentLogSourceResponse> logs(
            @PathVariable final UUID projectId, @PathVariable final UUID serviceId) {
        return this.services.logs(projectId, serviceId).stream().map(this.mapper::toResponse).toList();
    }
}
