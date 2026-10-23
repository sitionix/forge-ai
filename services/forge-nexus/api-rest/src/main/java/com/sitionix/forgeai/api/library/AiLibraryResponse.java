package com.sitionix.forgeai.api.library;
import com.sitionix.forgeai.domain.model.library.*;
import java.util.UUID;
public record AiLibraryResponse(UUID projectId,Long profileRevision,LibraryProviderContext provider,LibrarySnapshot.Categories categories) {
 public static AiLibraryResponse from(LibrarySnapshot snapshot){return new AiLibraryResponse(snapshot.projectId(),snapshot.profileRevision(),snapshot.provider(),snapshot.categories());}
}
