package com.sitionix.forgeagent.domain.model.library;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryResultTest {
 @Test void distinguishesEmptyUnsupportedAndIncompleteResults() {
  assertEquals(InventoryStatus.EMPTY, InventoryResult.available(List.of()).status());
  assertEquals(InventoryStatus.UNSUPPORTED, InventoryResult.unsupported().status());
  var items = new ArrayList<>(List.of("a"));
  var result = new InventoryResult<>(InventoryStatus.FAILED, items, List.of(InventoryDiagnostic.INCOMPLETE));
  items.clear(); assertEquals(List.of("a"), result.items());
  assertThrows(IllegalArgumentException.class, () -> new InventoryResult<>(InventoryStatus.EMPTY,List.of("a"),List.of()));
  assertThrows(IllegalArgumentException.class, () -> new InventoryResult<>(InventoryStatus.AVAILABLE,List.of(),List.of()));
  assertThrows(IllegalArgumentException.class, () -> new InventoryResult<>(InventoryStatus.UNSUPPORTED,List.of("a"),List.of()));
 }
}
