package com.sitionix.forgeagent.domain.model.library;

import java.util.List;
import java.util.Objects;
public record InventoryResult<T>(InventoryStatus status, List<T> items, List<InventoryDiagnostic> diagnostics) {
 public InventoryResult {
  Objects.requireNonNull(status); items=List.copyOf(items); diagnostics=List.copyOf(diagnostics);
  if ((status==InventoryStatus.EMPTY || status==InventoryStatus.UNSUPPORTED || status==InventoryStatus.UNAVAILABLE) && !items.isEmpty()
      || status==InventoryStatus.AVAILABLE && items.isEmpty()) throw new IllegalArgumentException("Invalid inventory state");
 }
 public static <T> InventoryResult<T> available(List<T> items) { return new InventoryResult<>(items.isEmpty()?InventoryStatus.EMPTY:InventoryStatus.AVAILABLE,items,List.of()); }
 public static <T> InventoryResult<T> unsupported() { return new InventoryResult<>(InventoryStatus.UNSUPPORTED,List.of(),List.of()); }
 public static <T> InventoryResult<T> unavailable(InventoryDiagnostic reason) { return new InventoryResult<>(InventoryStatus.UNAVAILABLE,List.of(),List.of(reason)); }
 public static <T> InventoryResult<T> failed() { return new InventoryResult<>(InventoryStatus.FAILED,List.of(),List.of(InventoryDiagnostic.DISCOVERY_FAILED)); }
}
