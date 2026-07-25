from __future__ import annotations

from knowledge_service.operation_facts import AvailableOperationFact, OperationFactEvidence, merge_semantic_operation_facts


def _fact(
    *,
    transport: str = "KAFKA",
    direction: str = "OUTBOUND",
    topic: str | None = None,
    schedule: str | None = None,
    operation_identity: str | None = None,
    source_channel: str = "ENTRYPOINT_HINT",
    owner_edge_id: str | None = None,
    evidence_id: str = "ev",
) -> AvailableOperationFact:
    return AvailableOperationFact(
        owner_source_id="source",
        owner_graph_id="source:graph",
        owner_graph_revision="source:revision",
        owner_node_id="owner",
        source_id="source",
        execution_role="CLIENT_OPERATION",
        transport_kind=transport,
        direction_role=direction,
        method=None,
        normalized_route=None,
        topic=topic,
        schedule=schedule,
        operation_identity=operation_identity,
        owner_edge_id=owner_edge_id,
        evidence=(
            OperationFactEvidence(
                source_id=evidence_id,
                relative_path=f"src/{evidence_id}.java",
                line_start=1,
                line_end=1,
                excerpt=evidence_id,
            ),
        ),
        source_channel=source_channel,
    )


def test_different_topics_for_same_owner_and_transport_remain_distinct():
    merged = merge_semantic_operation_facts(
        (
            _fact(topic="users.created", owner_edge_id="created"),
            _fact(topic="users.deleted", owner_edge_id="deleted"),
        )
    )

    assert len(merged) == 2
    assert {fact.topic for fact in merged} == {"users.created", "users.deleted"}


def test_duplicate_topic_operation_merges_and_combines_evidence():
    merged = merge_semantic_operation_facts(
        (
            _fact(topic="users.created", owner_edge_id="created-edge", evidence_id="edge-ev", source_channel="EDGE_METADATA"),
            _fact(topic="users.created", evidence_id="claim-ev", source_channel="ENTRYPOINT_HINT"),
        )
    )

    assert len(merged) == 1
    assert merged[0].topic == "users.created"
    assert {item.source_id for item in merged[0].evidence} == {"claim-ev", "edge-ev"}


def test_different_schedules_for_same_owner_remain_distinct():
    merged = merge_semantic_operation_facts(
        (
            _fact(transport="SCHEDULED", direction="SUPPORTING", schedule="0 0 * * *", owner_edge_id="midnight"),
            _fact(transport="SCHEDULED", direction="SUPPORTING", schedule="0 12 * * *", owner_edge_id="noon"),
        )
    )

    assert len(merged) == 2
    assert {fact.schedule for fact in merged} == {"0 0 * * *", "0 12 * * *"}


def test_identity_light_fact_does_not_arbitrarily_merge_multiple_topic_operations():
    merged = merge_semantic_operation_facts(
        (
            _fact(topic="users.created", owner_edge_id="created"),
            _fact(topic="users.deleted", owner_edge_id="deleted"),
            _fact(operation_identity="KafkaPublisher.publish", owner_edge_id="topicless", evidence_id="topicless-ev"),
        )
    )

    assert len(merged) == 3
    assert {fact.topic for fact in merged} == {"users.created", "users.deleted", None}


def test_missing_topic_merges_with_one_richer_compatible_operation():
    merged = merge_semantic_operation_facts(
        (
            _fact(topic="users.created", owner_edge_id="created", evidence_id="topic-ev"),
            _fact(owner_edge_id="topicless", evidence_id="topicless-ev"),
        )
    )

    assert len(merged) == 1
    assert merged[0].topic == "users.created"
    assert {item.source_id for item in merged[0].evidence} == {"topic-ev", "topicless-ev"}
