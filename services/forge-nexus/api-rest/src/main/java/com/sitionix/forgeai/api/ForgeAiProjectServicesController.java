package com.sitionix.forgeai.api;
import com.sitionix.forgeai.api.agentproxy.*; import com.sitionix.forgeai.domain.model.agentproxy.*; import com.sitionix.forgeai.domain.usecase.ManageAgentProjectServices; import jakarta.validation.Valid; import java.util.*; import lombok.RequiredArgsConstructor; import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/services")
public class ForgeAiProjectServicesController { private final ManageAgentProjectServices services; private final AgentProxyApiMapper mapper;
 @GetMapping public List<AgentProjectServiceResponse> list(@PathVariable UUID projectId){return services.list(projectId).stream().map(this::response).toList();}
 @PostMapping public ResponseEntity<AgentProjectServiceResponse> create(@PathVariable UUID projectId,@Valid @RequestBody AgentProjectServiceRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(response(services.create(projectId,command(r))));}
 @GetMapping("/{serviceId}") public AgentProjectServiceResponse get(@PathVariable UUID projectId,@PathVariable UUID serviceId){return response(services.get(projectId,serviceId));}
 @PutMapping("/{serviceId}") public AgentProjectServiceResponse update(@PathVariable UUID projectId,@PathVariable UUID serviceId,@Valid @RequestBody AgentProjectServiceRequest r){return response(services.update(projectId,serviceId,command(r)));}
 @DeleteMapping("/{serviceId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID projectId,@PathVariable UUID serviceId){services.delete(projectId,serviceId);}
 @GetMapping("/{serviceId}/runtime") public AgentServiceRuntimeResponse runtime(@PathVariable UUID projectId,@PathVariable UUID serviceId){var r=services.runtime(projectId,serviceId);return new AgentServiceRuntimeResponse(r.status(),r.provider(),r.connection(),r.targetIdentity(),r.startedAt(),r.uptime(),r.metadata(),r.health());}
 @GetMapping("/{serviceId}/log-sources") public List<AgentLogSourceResponse> logs(@PathVariable UUID projectId,@PathVariable UUID serviceId){return services.logs(projectId,serviceId).stream().map(mapper::toResponse).toList();}
 private SaveAgentProjectServiceCommand command(AgentProjectServiceRequest r){var t=r.runtimeTarget();return new SaveAgentProjectServiceCommand(r.name(),r.repositoryId(),new AgentServiceRuntimeTarget(t.connection(),t.sshConnectionId(),t.provider(),t.container(),t.unit()));}
 private AgentProjectServiceResponse response(AgentProjectService s){return new AgentProjectServiceResponse(s.id(),s.projectId(),s.name(),s.repositoryId(),s.runtimeTarget(),s.createdAt(),s.updatedAt());}
}
