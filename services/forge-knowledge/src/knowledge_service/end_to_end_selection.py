from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from knowledge_service.end_to_end_flow import EndToEndFlowGraph


@dataclass(frozen=True)
class EndToEndGraphSelectionResult:
    selected_graphs: tuple[EndToEndFlowGraph, ...]
    selected_graph_ids: tuple[str, ...]
    omitted_graph_ids: tuple[str, ...]
    score_by_graph_id: Mapping[str, float]
    diagnostics: tuple[Mapping[str, Any], ...] = ()
    truncated: bool = False


class EndToEndGraphSelector:
    def select(
        self,
        graphs: Sequence[EndToEndFlowGraph],
        *,
        score_by_unit_id: Mapping[str, float],
        selected_initial_unit_ids: Sequence[str],
        max_graphs: int,
    ) -> EndToEndGraphSelectionResult:
        limit = max(1, int(max_graphs or 1))
        initial_ids = {str(item or "") for item in selected_initial_unit_ids if str(item or "")}
        ordered = tuple(sorted({graph.stable_graph_id: graph for graph in graphs or ()}.values(), key=lambda item: item.stable_graph_id))
        scored = [(self._score(graph, score_by_unit_id, initial_ids), graph) for graph in ordered]
        ranked = tuple(graph for _score, graph in sorted(scored, key=lambda item: (-item[0][0], -item[0][1], item[0][2])))
        selected = ranked[:limit]
        omitted = ranked[limit:]
        score_map = {graph.stable_graph_id: score[0] for score, graph in scored}
        diagnostics = [
            {
                "code": "END_TO_END_GRAPH_SELECTION_DIAGNOSTICS",
                "discoveredGraphCount": len(ordered),
                "returnedGraphCount": len(selected),
                "omittedGraphCount": len(omitted),
                "maxFlows": limit,
            }
        ]
        if omitted:
            diagnostics.append(
                {
                    "code": "END_TO_END_GRAPH_MAX_FLOWS_REACHED",
                    "discoveredGraphCount": len(ordered),
                    "returnedGraphCount": len(selected),
                    "omittedGraphCount": len(omitted),
                    "maxFlows": limit,
                }
            )
        return EndToEndGraphSelectionResult(
            selected_graphs=tuple(selected),
            selected_graph_ids=tuple(graph.stable_graph_id for graph in selected),
            omitted_graph_ids=tuple(graph.stable_graph_id for graph in omitted),
            score_by_graph_id=dict(sorted(score_map.items())),
            diagnostics=tuple(diagnostics),
            truncated=bool(omitted),
        )

    def _score(
        self,
        graph: EndToEndFlowGraph,
        score_by_unit_id: Mapping[str, float],
        selected_initial_unit_ids: set[str],
    ) -> tuple[float, int, str]:
        graph_initial_ids = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id in selected_initial_unit_ids)
        highest = max((float(score_by_unit_id.get(unit_id, 0.0)) for unit_id in graph_initial_ids), default=0.0)
        return highest, len(graph_initial_ids), graph.stable_graph_id
