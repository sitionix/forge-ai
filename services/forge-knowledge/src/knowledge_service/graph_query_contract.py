from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Iterable, Mapping

from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.analysis_policy_loader import load_analysis_policy


@dataclass(frozen=True)
class GraphQueryContract:
    """Policy-derived graph values used by persistence/query SQL."""

    node_kinds: Mapping[str, object]
    edge_types: Mapping[str, object]
    claim_kinds: Mapping[str, object]
    statuses: Mapping[str, object]
    origins: Mapping[str, object]
    evidence_kinds: Mapping[str, object]
    resolution_statuses: Mapping[str, object]
    edge_relation_semantics: Mapping[str, tuple[str, ...]]
    semantic_node_kinds: tuple[str, ...]
    semantic_edge_types: tuple[str, ...]
    semantic_claim_kinds: tuple[str, ...]

    @classmethod
    def from_policy(cls, policy: AnalysisPolicy) -> "GraphQueryContract":
        return cls(
            node_kinds=policy.graph.nodes,
            edge_types=policy.graph.edges,
            claim_kinds=policy.graph.claims,
            statuses=policy.graph.statuses,
            origins=policy.graph.origins,
            evidence_kinds=policy.graph.evidence_kinds,
            resolution_statuses=policy.graph.resolution_statuses,
            edge_relation_semantics={
                kind: tuple(definition.relation_semantics)
                for kind, definition in policy.graph.edges.items()
            },
            semantic_node_kinds=tuple(policy.semantic.indexed_node_kinds),
            semantic_edge_types=tuple(policy.semantic.indexed_edge_types),
            semantic_claim_kinds=tuple(policy.semantic.indexed_claim_kinds),
        )

    def required_node_kind(self, name: str) -> str:
        return self._required(name, self.node_kinds, "node kind")

    def required_edge_type(self, name: str) -> str:
        return self._required(name, self.edge_types, "edge type")

    def required_claim_kind(self, name: str) -> str:
        return self._required(name, self.claim_kinds, "claim kind")

    def required_status(self, name: str) -> str:
        return self._required(name, self.statuses, "fact status")

    def required_resolution_status(self, name: str) -> str:
        return self._required(name, self.resolution_statuses, "resolution status")

    @property
    def file_node_kind(self) -> str:
        return self.required_node_kind("FILE")

    @property
    def type_node_kind(self) -> str:
        return self.required_node_kind("TYPE")

    @property
    def callable_node_kind(self) -> str:
        return self.required_node_kind("CALLABLE")

    @property
    def calls_edge_type(self) -> str:
        return self.required_edge_type("CALLS")

    @property
    def responsibility_claim_kind(self) -> str:
        return self.required_claim_kind("RESPONSIBILITY")

    @property
    def entrypoint_claim_kind(self) -> str:
        return self.required_claim_kind("ENTRYPOINT_HINT")

    @property
    def trusted_status(self) -> str:
        return self.required_status("TRUSTED")

    @property
    def candidate_status(self) -> str:
        return self.required_status("CANDIDATE")

    @property
    def derived_status(self) -> str:
        return self.required_status("DERIVED")

    @property
    def resolved_status(self) -> str:
        return self.required_resolution_status("RESOLVED")

    @property
    def multiple_candidates_status(self) -> str:
        return self.required_resolution_status("MULTIPLE_CANDIDATES")

    @property
    def unresolved_status(self) -> str:
        return self.required_resolution_status("UNRESOLVED")

    @property
    def dynamic_target_status(self) -> str:
        return self.required_resolution_status("DYNAMIC_TARGET")

    @property
    def external_target_status(self) -> str:
        return self.required_resolution_status("EXTERNAL_TARGET")

    def statuses_for_claim_text(self) -> tuple[str, ...]:
        return (
            self.trusted_status,
            self.derived_status,
            self.candidate_status,
        )

    def statuses_for_current_graph(self) -> tuple[str, ...]:
        return (
            self.trusted_status,
            self.derived_status,
        )

    def statuses_for_responsibility_summary(self) -> tuple[str, ...]:
        return (
            self.trusted_status,
            self.candidate_status,
        )

    def unresolved_resolution_statuses(self) -> tuple[str, ...]:
        return (
            self.unresolved_status,
            self.dynamic_target_status,
            self.external_target_status,
        )

    def hidden_unresolved_resolution_statuses(self) -> tuple[str, ...]:
        return (
            self.unresolved_status,
            self.dynamic_target_status,
            self.external_target_status,
        )

    def resolver_pending_statuses(self) -> tuple[str, ...]:
        return (
            self.unresolved_status,
            self.multiple_candidates_status,
        )

    def _required(self, name: str, values: Mapping[str, object], label: str) -> str:
        if name not in values:
            raise ValueError(f"Graph query contract is missing {label}: {name}")
        return name


@lru_cache(maxsize=1)
def graph_query_contract() -> GraphQueryContract:
    return GraphQueryContract.from_policy(load_analysis_policy())


def sql_in_clause(values: Iterable[str]) -> tuple[str, list[str]]:
    params = [str(value) for value in values]
    if not params:
        raise ValueError("SQL IN clause requires at least one policy value")
    return ",".join("?" for _ in params), params
