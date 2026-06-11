from __future__ import annotations

from typing import Dict, List

from knowledge_service.context_schema import ContextBudget, ContextDiagnostic, ContextItem, ContextResponse, ContextSource


class ContextBuilder:
    def empty_inventory(self, query: str, max_chars: int) -> Dict:
        return ContextResponse(
            query=query,
            context=[],
            sourcesUsed=[],
            budget=ContextBudget(maxChars=max_chars, usedChars=0, truncated=False),
            diagnostics=[ContextDiagnostic(code="INVENTORY_EMPTY", message="Inventory is empty. Build inventory first.")],
        ).dict()

    def build(self, query: str, items: List[ContextItem], max_chars: int, used_chars: int, truncated: bool) -> Dict:
        sources: dict[str, ContextSource] = {}
        for item in items:
            if item.sourceId not in sources:
                sources[item.sourceId] = ContextSource(
                    sourceId=item.sourceId,
                    displayName=item.displayName,
                    reason="Matched query terms and source metadata",
                )
        return ContextResponse(
            query=query,
            context=items,
            sourcesUsed=list(sources.values()),
            budget=ContextBudget(maxChars=max_chars, usedChars=used_chars, truncated=truncated),
            diagnostics=[],
        ).dict()
