package com.sitionix.forgeagent.api.library;
import com.fasterxml.jackson.databind.*;
import com.sitionix.forgeagent.application.library.GetAiLibraryUseCase;
import com.sitionix.forgeagent.domain.model.library.LibraryProviderContext;
import com.sitionix.forgeagent.api.security.AgentManagementRoutePolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public class AiLibraryController {
 private final GetAiLibraryUseCase query; private final ObjectMapper json;
 public AiLibraryController(GetAiLibraryUseCase query,ObjectMapper json){this.query=query;this.json=json;}
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse> rejected(ResponseStatusException failure) {
  boolean denied=failure.getStatusCode().value()==403;
  return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
   .body(new com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse(denied?"LIBRARY_SERVICE_DENIED":"INVALID_LIBRARY_QUERY",denied?"Service authentication required.":"Invalid library query.",null));
 }
 public record QueryRequest(LibraryProviderContext provider) {}
 @PostMapping(value="/api/v1/projects/{projectId}/ai-library/query",consumes="application/json")
 public ResponseEntity<AiLibraryResponse> query(@PathVariable UUID projectId,HttpServletRequest request) {
  if(!Boolean.TRUE.equals(request.getAttribute(AgentManagementRoutePolicy.LIBRARY_AUTHENTICATED))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service authentication required");
  try {
   var body=request.getInputStream().readNBytes(4097);
   if(body.length>4096)throw new IllegalArgumentException();
   var input=json.readerFor(QueryRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
     .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).<QueryRequest>readValue(body);
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AiLibraryResponse.from(query.execute(projectId,input.provider())));
  }catch(java.io.IOException|IllegalArgumentException bad){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid library query");}
 }
}
