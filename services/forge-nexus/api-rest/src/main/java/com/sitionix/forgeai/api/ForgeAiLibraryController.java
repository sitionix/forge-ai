package com.sitionix.forgeai.api;
import com.sitionix.forgeai.domain.usecase.GetAiLibrary;
import com.sitionix.forgeai.api.library.AiLibraryResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.UUID;
@RestController
public class ForgeAiLibraryController {
 private final GetAiLibrary query;
 public ForgeAiLibraryController(GetAiLibrary query){this.query=query;}
 @GetMapping("/api/v1/infrastructure/agents/projects/{projectId}/ai-library")
 public ResponseEntity<AiLibraryResponse> get(@PathVariable UUID projectId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AiLibraryResponse.from(query.execute(projectId)));}
}
