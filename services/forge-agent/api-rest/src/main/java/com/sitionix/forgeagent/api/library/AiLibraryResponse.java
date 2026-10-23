package com.sitionix.forgeagent.api.library;
import com.sitionix.forgeagent.domain.model.library.*;
import java.util.*;
import java.util.function.Function;
public record AiLibraryResponse(UUID projectId, Long profileRevision, LibraryProviderContext provider, Categories categories) {
 public record Item(String id,String name,String description,String source) {}
 public record Category(InventoryStatus status,List<Item> items,List<InventoryDiagnostic> diagnostics) {}
 public record Categories(Category skills,Category agents,Category flows,Category tools) {}
 public static AiLibraryResponse from(LibrarySnapshot value) {
  var c=value.categories();
  return new AiLibraryResponse(value.projectId(),value.provider()==null?null:value.provider().profileRevision(),value.provider(),new Categories(
   category(c.skills(),s->new Item(s.id(),s.name(),s.description(),source(s.provenance()))),
   category(c.agents(),s->new Item(s.id(),s.name(),s.description(),source(s.provenance()))),
   category(c.flows(),s->new Item(s.id(),s.name(),s.description(),source(s.provenance()))),
   category(c.tools(),s->new Item(s.id(),s.name(),s.description(),source(s.provenance())))));
 }
 private static String source(LibraryProvenance p){return p==null?null:p.source();}
 private static <T> Category category(InventoryResult<T> result,Function<T,Item> map){return new Category(result.status(),result.items().stream().map(map).toList(),result.diagnostics());}
}
