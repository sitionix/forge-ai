package com.sitionix.forgeai.application.usecase;
import com.sitionix.forgeai.domain.model.library.*;
import com.sitionix.forgeai.domain.port.*;
import com.sitionix.forgeai.domain.usecase.GetAiLibrary;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service
public class GetAiLibraryUseCase implements GetAiLibrary {
 private final KnowledgeActiveProfileClient knowledge;private final AiLibraryClient client;
 public GetAiLibraryUseCase(KnowledgeActiveProfileClient knowledge,AiLibraryClient client){this.knowledge=knowledge;this.client=client;}
 @Override public LibrarySnapshot execute(UUID projectId){
  LibraryProviderContext context=null;
  try {var active=knowledge.getActiveProfile();if(active!=null&&active.llmProfile()!=null){var p=active.llmProfile();if(p.providerId()!=null&&!p.providerId().isBlank()&&p.modelId()!=null&&!p.modelId().isBlank()) context=new LibraryProviderContext(active.revision(),p.providerId(),p.modelId(),p.effort()==null?null:p.effort().effortId(),p.providerDisplayName(),p.modelDisplayName());}}
  catch(RuntimeException unavailable){/* Project validation still occurs at Agent. */}
  return client.query(projectId,context);
 }
}
