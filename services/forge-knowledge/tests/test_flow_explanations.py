from __future__ import annotations

import json
import inspect
import re
import time
from types import SimpleNamespace
from dataclasses import replace

from knowledge_service import answer_language
from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_explanations import (
    DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS,
    FlowExplanationProviderResult,
    FlowProjectionBuilder,
    HumanAnswerGenerationFailed,
    HumanAnswerContextBudgetExceeded,
    HumanAnswerPromptRenderer,
    HumanFlowAnswerService,
    LocalOllamaFlowExplanationClient,
    PromptBudgetEstimator,
)
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import QueryRetrievalPlan


SOURCE = "flow-explanation-source"
REVISION = "flow-explanation-revision"


def node(node_id: str, *, entrypoint: bool = False) -> FlowGraphNode:
    return FlowGraphNode(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        node_id=node_id,
        stable_key=node_id,
        node_kind="CALLABLE",
        label=node_id,
        qualified_name=node_id,
        entrypoint=entrypoint,
    )


def edge(edge_id: str, source: str, target: str | None, *, status: str = "RESOLVED") -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        edge_id=edge_id,
        edge_type="CALLS",
        from_node_id=source,
        to_node_id=target,
        resolution_status=status,
        external=status == "EXTERNAL_TARGET",
        unresolved_target={"name": f"Boundary{edge_id}"} if target is None else None,
    )


def flow(nodes: list[FlowGraphNode], transitions: list[FlowGraphEdge], boundaries: list[FlowGraphEdge] | None = None) -> EntrypointFlow:
    root = nodes[0]
    return EntrypointFlow(
        key=EntrypointFlowKey(SOURCE, REVISION, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=tuple(nodes),
        transitions=tuple(transitions),
        boundary_transitions=tuple(boundaries or ()),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(transitions), len(boundaries or ()), 1, 1),
        diagnostics=(),
        relevance_score=1.0,
    )


def evidence(evidence_id: str, edge_id: str | None, node_id: str | None, line_start: int, text: str) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        evidence_id=evidence_id,
        node_id=node_id,
        edge_id=edge_id,
        relative_path="src/CreateSite.java",
        line_start=line_start,
        line_end=line_start,
        text=text,
    )


def technical_create_site_flow(*, with_trigger: bool = True) -> EntrypointFlow:
    root = FlowGraphNode(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        node_id="controller",
        stable_key="controller",
        node_kind="CALLABLE",
        label="SiteController.createSite",
        qualified_name="com.sitionix.stsssox.api.SiteController.createSite",
        relative_path="api/SiteController.java",
        line_start=23,
        line_end=30,
        summary="Maps the create request DTO to a command, executes the use case, and returns HTTP 201 with the response DTO.",
        entrypoint=True,
        entrypoint_kind="HTTP" if with_trigger else None,
        entrypoint_http_method="POST" if with_trigger else None,
        entrypoint_route="/api/v1/sites" if with_trigger else None,
        entrypoint_interface_method="com.app_afesox.stsssox.api_first.api.SiteApi.createSite" if with_trigger else None,
    )
    nodes = [
        root,
        node("SiteApiMapper.asCreateSiteCommand"),
        node("CreateSiteImpl.execute"),
        node("CreateSiteImpl.getUserId"),
        node("CreateSiteImpl.normalizeAndValidateName"),
        node("CreateSiteImpl.buildSite"),
        node("SiteRepositoryImpl.save"),
        node("ForgeOutbox.send"),
        node("SiteApiMapper.asCreateSiteResponseDTO"),
    ]
    transitions = [
        edge("controller-execute", "controller", "CreateSiteImpl.execute"),
        edge("controller-command", "controller", "SiteApiMapper.asCreateSiteCommand"),
        edge("controller-response", "controller", "SiteApiMapper.asCreateSiteResponseDTO"),
        edge("execute-outbox", "CreateSiteImpl.execute", "ForgeOutbox.send"),
        edge("execute-user", "CreateSiteImpl.execute", "CreateSiteImpl.getUserId"),
        edge("execute-build", "CreateSiteImpl.execute", "CreateSiteImpl.buildSite"),
        edge("execute-save", "CreateSiteImpl.execute", "SiteRepositoryImpl.save"),
        edge("execute-validate", "CreateSiteImpl.execute", "CreateSiteImpl.normalizeAndValidateName"),
    ]
    return EntrypointFlow(
        key=EntrypointFlowKey(SOURCE, REVISION, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=tuple(nodes),
        transitions=tuple(transitions),
        boundary_transitions=(),
        evidence=(
            evidence("n-controller", None, "controller", 23, "public ResponseEntity<CreateSiteResponseDTO> createSite(...)"),
            evidence("e-controller-command", "controller-command", None, 25, "this.siteApiMapper.asCreateSiteCommand(createSiteRequestDTO)"),
            evidence("e-controller-execute", "controller-execute", None, 26, "this.createSite.execute(command)"),
            evidence("e-controller-response", "controller-response", None, 29, "this.siteApiMapper.asCreateSiteResponseDTO(site)"),
            evidence("e-execute-user", "execute-user", None, 32, "this.getUserId()"),
            evidence("e-execute-validate", "execute-validate", None, 33, "this.normalizeAndValidateName(command.name())"),
            evidence("e-execute-build", "execute-build", None, 36, "this.buildSite(command, userId, normalizedName, now)"),
            evidence("e-execute-save", "execute-save", None, 37, "this.siteRepository.save(site)"),
            evidence("e-execute-outbox", "execute-outbox", None, 38, "this.forgeOutbox.send(new SiteCreatedPayload(savedSite))"),
            evidence("n-validate", None, "CreateSiteImpl.normalizeAndValidateName", 66, "trim; reject null or empty; reject length > 60"),
            evidence("n-build", None, "CreateSiteImpl.buildSite", 42, "Site.builder().status(SiteStatus.DRAFT).createdAt(now).updatedAt(now)"),
            evidence("n-save", None, "SiteRepositoryImpl.save", 21, "siteJpaRepository.save(siteInfraMapper.asSiteEntity(site))"),
        ),
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(transitions), 0, 1, 3),
        diagnostics=(),
        relevance_score=1.0,
    )


def unrelated_listener_flow() -> EntrypointFlow:
    root = FlowGraphNode(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        node_id="listener",
        stable_key="listener",
        node_kind="CALLABLE",
        label="PaymentListener.handle",
        qualified_name="com.example.payments.PaymentListener.handle",
        relative_path="events/PaymentListener.java",
        line_start=12,
        line_end=20,
        summary="Receives a payment event, validates it, stores a ledger row, and acknowledges processing.",
        entrypoint=True,
        entrypoint_kind="KAFKA",
        entrypoint_topic="payments.created",
    )
    nodes = [
        root,
        node("PaymentValidator.requireValid"),
        node("LedgerRepository.save"),
        node("PaymentAcknowledgement.publish"),
    ]
    transitions = [
        edge("listener-validate", "listener", "PaymentValidator.requireValid"),
        edge("listener-save", "listener", "LedgerRepository.save"),
        edge("listener-publish", "listener", "PaymentAcknowledgement.publish"),
    ]
    return EntrypointFlow(
        key=EntrypointFlowKey(SOURCE, REVISION, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=tuple(nodes),
        transitions=tuple(transitions),
        boundary_transitions=(),
        evidence=(
            evidence("n-listener", None, "listener", 12, "@KafkaListener(topics = \"payments.created\")"),
            evidence("e-listener-validate", "listener-validate", None, 14, "paymentValidator.requireValid(event)"),
            evidence("e-listener-save", "listener-save", None, 16, "ledgerRepository.save(row)"),
            evidence("e-listener-publish", "listener-publish", None, 18, "acknowledgement.publish(event.id())"),
        ),
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(transitions), 0, 1, 2),
        diagnostics=(),
        relevance_score=1.0,
    )


class SequenceHumanAnswerProvider:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "llmInput": dict(llm_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        if isinstance(response, str):
            raw = json.dumps(structured_answer(llm_input, response), ensure_ascii=False)
        elif isinstance(response, dict) and "text" in response and "steps" not in response:
            raw = json.dumps(structured_answer(llm_input, str(response["text"])), ensure_ascii=False)
        else:
            raw = json.dumps(response, ensure_ascii=False)
        return FlowExplanationProviderResult(raw_text=raw, prompt_char_length=100)


class RecordingOllamaHttpClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.posts = []

    def post(self, url, *, json, timeout):
        self.posts.append({"url": url, "json": json, "timeout": timeout})
        return RecordingOllamaResponse(self.responses.pop(0))

    def close(self):
        return None


class RecordingOllamaResponse:
    def __init__(self, response_text: str):
        self.response_text = response_text

    def raise_for_status(self):
        return None

    def json(self):
        return {"response": self.response_text}


def structured_answer(llm_input, text: str, *, refs: list[str] | None = None, result: str | None = None):
    coverage = llm_input.get("coverageContract") or {}
    fact_refs = refs if refs is not None else list(coverage.get("canonicalFactRefs") or [])
    return {
        "steps": [{"factRefs": fact_refs, "text": text}],
        "result": result or text,
    }


def human_execution(graph_flow: EntrypointFlow):
    return SimpleNamespace(flows=(graph_flow,))


def retrieval_plan(query: str, *, detected_language: str = "en", response_language: str = "en") -> QueryRetrievalPlan:
    return QueryRetrievalPlan(
        original_query=query,
        normalized_query=query,
        search_queries=(query,),
        code_identifiers=(),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language=detected_language,
        response_language=response_language,
    )


def contains_key(value, key: str) -> bool:
    if isinstance(value, dict):
        return key in value or any(contains_key(item, key) for item in value.values())
    if isinstance(value, list):
        return any(contains_key(item, key) for item in value)
    return False


def private_identity_flow() -> EntrypointFlow:
    root = FlowGraphNode(
        source_id="public-source-id",
        graph_id="secret-graph-db-id",
        graph_revision="secret-graph-revision-db-id",
        node_id="secret-node-db-id",
        stable_key="secret-stable-key",
        node_kind="CALLABLE",
        label="Public Entry",
        qualified_name="PublicEntry",
        relative_path="src/PublicEntry.java",
        line_start=1,
        line_end=5,
        summary="Public entry summary.",
        entrypoint=True,
        entrypoint_kind="HTTP",
        entrypoint_http_method="POST",
        entrypoint_route="/public",
    )
    worker = FlowGraphNode(
        source_id="public-source-id",
        graph_id="secret-graph-db-id",
        graph_revision="secret-graph-revision-db-id",
        node_id="secret-target-node-db-id",
        stable_key="secret-target-stable-key",
        node_kind="CALLABLE",
        label="Public Worker",
        qualified_name="PublicWorker",
        relative_path="src/PublicWorker.java",
        line_start=10,
        line_end=15,
        summary="Public worker summary.",
    )
    transition = FlowGraphEdge(
        source_id="public-source-id",
        graph_id="secret-graph-db-id",
        graph_revision="secret-graph-revision-db-id",
        edge_id="secret-edge-db-id",
        edge_type="CALLS",
        from_node_id="secret-node-db-id",
        to_node_id="secret-target-node-db-id",
        resolution_status="RESOLVED",
    )
    return EntrypointFlow(
        key=EntrypointFlowKey("public-source-id", "secret-graph-revision-db-id", "secret-node-db-id"),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=(root, worker),
        transitions=(transition,),
        boundary_transitions=(),
        evidence=(
            FlowGraphEvidence(
                "public-source-id",
                "secret-graph-db-id",
                "secret-graph-revision-db-id",
                "secret-evidence-db-id",
                None,
                "secret-edge-db-id",
                "src/PublicEntry.java",
                3,
                3,
                "public worker call evidence",
            ),
        ),
        complete=True,
        coverage=EntrypointFlowCoverage(2, 1, 0, 1, 1),
        diagnostics=(),
        relevance_score=1.0,
    )


def cross_source_identical_symbol_flow() -> EntrypointFlow:
    source_a = FlowGraphNode(
        source_id="source-a",
        graph_id="revision-a",
        graph_revision="revision-a",
        node_id="a-controller",
        stable_key="stable-a-controller",
        node_kind="CALLABLE",
        label="SiteController.createSite",
        qualified_name="com.example.a.SiteController.createSite",
        entrypoint=True,
    )
    source_b = FlowGraphNode(
        source_id="source-b",
        graph_id="revision-b",
        graph_revision="revision-b",
        node_id="b-controller",
        stable_key="stable-b-controller",
        node_kind="CALLABLE",
        label="SiteController.createSite",
        qualified_name="com.example.b.SiteController.createSite",
    )
    transition = FlowGraphEdge(
        source_id="source-a",
        graph_id="revision-a",
        graph_revision="revision-a",
        edge_id="a-to-b",
        edge_type="CALLS",
        from_node_id="a-controller",
        to_node_id="b-controller",
        resolution_status="RESOLVED",
        to_source_id="source-b",
        to_graph_id="revision-b",
        to_graph_revision="revision-b",
    )
    return EntrypointFlow(
        key=EntrypointFlowKey("source-a", "revision-a", "a-controller"),
        entrypoint=source_a,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(source_a.node_id, source_a.label, 1.0, ("TEST",), 0),),
        nodes=(source_a, source_b),
        transitions=(transition,),
        boundary_transitions=(),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(2, 1, 0, 1, 1),
        diagnostics=(),
        relevance_score=1.0,
    )


def test_complete_technical_flow_prompt_contains_grounded_trigger_and_steps():
    request = KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="uk")
    builder = FlowProjectionBuilder()
    graph_flow = technical_create_site_flow()
    llm_input = builder.human_llm_input(
        request,
        graph_flow,
        retrieval_plan(request.queryText, detected_language="uk", response_language="uk"),
    )
    tool_tree = builder.to_tool_response(request, human_execution(graph_flow)).trees[0].dict(exclude_none=True)["entrypoint"]
    prompt = HumanAnswerPromptRenderer().render(llm_input)
    rendered = json.dumps(llm_input, ensure_ascii=False)

    assert "tree" not in llm_input
    assert llm_input["orderedFacts"][0]["trigger"] == {
        "kind": "HTTP",
        "method": "POST",
        "route": "/api/v1/sites",
        "interfaceMethod": "com.app_afesox.stsssox.api_first.api.SiteApi.createSite",
    }
    for expected in (
        "SiteController.createSite",
        "SiteApiMapper.asCreateSiteCommand",
        "CreateSiteImpl.execute",
        "CreateSiteImpl.getUserId",
        "CreateSiteImpl.normalizeAndValidateName",
        "CreateSiteImpl.buildSite",
        "SiteRepositoryImpl.save",
        "ForgeOutbox.send",
        "SiteApiMapper.asCreateSiteResponseDTO",
        "POST",
        "/api/v1/sites",
        "reject length > 60",
        "SiteStatus.DRAFT",
        "siteJpaRepository.save",
        "SiteCreatedPayload",
    ):
        assert expected in rendered
        assert expected in prompt

    for item in graph_flow.evidence:
        assert prompt.count(item.text) == 1

    root_children = [item["symbol"] for item in tool_tree["children"]]
    assert root_children == [
        "SiteApiMapper.asCreateSiteCommand",
        "CreateSiteImpl.execute",
        "SiteApiMapper.asCreateSiteResponseDTO",
    ]
    execute_children = tool_tree["children"][1]["children"]
    assert [item["symbol"] for item in execute_children] == [
        "CreateSiteImpl.getUserId",
        "CreateSiteImpl.normalizeAndValidateName",
        "CreateSiteImpl.buildSite",
        "SiteRepositoryImpl.save",
        "ForgeOutbox.send",
    ]


def test_compact_projector_orders_resolved_and_boundary_children_by_callsite_line():
    mapper_boundary = replace(
        edge("boundary-earlier", "Root", None, status="UNRESOLVED"),
        unresolved_target={
            "name": "asCreateSiteCommand",
            "qualifiedName": "com.sitionix.stsssox.api.mapper.SiteApiMapper.asCreateSiteCommand",
        },
    )
    base = flow(
        [node("Root", entrypoint=True), node("ResolvedLater")],
        [edge("resolved-later", "Root", "ResolvedLater")],
        [mapper_boundary],
    )
    request = KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION", answerLanguage="en")
    projected_flow = replace(
        base,
        evidence=(
            evidence("e-boundary", "boundary-earlier", None, 10, "mapper.asCommand(request)"),
            evidence("e-resolved", "resolved-later", None, 20, "service.execute(command)"),
        ),
    )
    projected = FlowProjectionBuilder().to_tool_response(
        request,
        human_execution(projected_flow),
    ).trees[0].dict(exclude_none=True)["entrypoint"]

    assert [child["symbol"] for child in projected["children"]] == ["SiteApiMapper.asCreateSiteCommand", "ResolvedLater"]
    llm_input = FlowProjectionBuilder().human_llm_input(
        request,
        projected_flow,
        retrieval_plan("Alpha", detected_language="en", response_language="en"),
    )
    boundary_fact = next(fact for fact in llm_input["orderedFacts"] if fact["type"] == "boundary")
    assert boundary_fact["fromSource"] == SOURCE


def test_human_prompt_distinguishes_identical_symbols_by_public_source():
    request = KnowledgeQueryRequest(queryText="explain create site", intent="FLOW_EXPLANATION", answerLanguage="en")
    llm_input = FlowProjectionBuilder().human_llm_input(
        request,
        cross_source_identical_symbol_flow(),
        retrieval_plan("explain create site", detected_language="en", response_language="en"),
    )
    prompt = HumanAnswerPromptRenderer().render(llm_input)
    facts = llm_input["orderedFacts"]
    node_facts = {fact["source"]: fact for fact in facts if fact["type"] == "node"}
    transition_fact = next(fact for fact in facts if fact["type"] == "transition")

    assert [fact["ref"] for fact in facts] == ["n1", "t1", "n2"]
    assert node_facts["source-a"]["displaySymbol"] == "SiteController.createSite"
    assert node_facts["source-b"]["displaySymbol"] == "SiteController.createSite"
    assert transition_fact["fromSource"] == "source-a"
    assert transition_fact["toSource"] == "source-b"
    assert transition_fact["crossSource"] is True
    assert re.search(r'\{[^{}]*"displaySymbol":"SiteController\.createSite"[^{}]*"source":"source-a"[^{}]*\}', prompt)
    assert re.search(r'\{[^{}]*"displaySymbol":"SiteController\.createSite"[^{}]*"source":"source-b"[^{}]*\}', prompt)
    assert '"fromSource":"source-a"' in prompt
    assert '"toSource":"source-b"' in prompt
    assert '"crossSource":true' in prompt


def test_human_llm_input_uses_ordered_facts_without_nested_tree():
    llm_input = FlowProjectionBuilder().human_llm_input(
        KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION", answerLanguage="en"),
        technical_create_site_flow(),
        retrieval_plan("Alpha", detected_language="en", response_language="en"),
    )

    assert "tree" not in llm_input
    assert "orderedFacts" in llm_input
    assert llm_input["suggestedStepPlan"]
    assert all(set(item) == {"factRefs"} for item in llm_input["suggestedStepPlan"])


def test_human_prompt_contract_allows_natural_grounded_output():
    prompt = HumanAnswerPromptRenderer().render(
        FlowProjectionBuilder().human_llm_input(
            KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION", answerLanguage="en"),
            technical_create_site_flow(),
            retrieval_plan("Alpha", detected_language="en", response_language="en"),
        )
    )

    for required in (
        "technical walkthrough",
        "HTTP method and route",
        "trigger and entrypoint",
        "orderedFacts and coverageContract",
        "Cover every required node, transition, and boundary exactly once",
        "exact class or method symbol",
        "validation, persistence, or side effect",
        "Write all natural-language prose in the supplied responseLanguage",
        "Preserve code identifiers",
        "exception classes, or error messages",
        "observable result",
        "escaped plain text",
        "Return strict JSON only",
        "Do not collapse the flow into a generic summary",
        "Do not invent validation",
        "Do not mention retrieval mechanics",
    ):
        assert required in prompt


def test_missing_trigger_metadata_is_not_invented():
    request = KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="uk")
    llm_input = FlowProjectionBuilder().human_llm_input(
        request,
        technical_create_site_flow(with_trigger=False),
        retrieval_plan(request.queryText, detected_language="uk", response_language="uk"),
    )

    assert "trigger" not in llm_input["orderedFacts"][0]
    rendered = json.dumps(llm_input, ensure_ascii=False)
    assert "POST" not in rendered
    assert "/api/v1/sites" not in rendered


def test_human_prompt_and_validation_do_not_expose_persisted_internal_ids():
    graph_flow = private_identity_flow()
    llm_input = FlowProjectionBuilder().human_llm_input(
        KnowledgeQueryRequest(queryText="explain public entry", intent="FLOW_EXPLANATION", answerLanguage="en"),
        graph_flow,
        retrieval_plan("explain public entry", detected_language="en", response_language="en"),
    )
    prompt = HumanAnswerPromptRenderer().render(llm_input)
    secrets = (
        "secret-graph-db-id",
        "secret-graph-revision-db-id",
        "secret-node-db-id",
        "secret-target-node-db-id",
        "secret-edge-db-id",
        "secret-evidence-db-id",
        "secret-stable-key",
        "secret-target-stable-key",
    )
    rendered_input = json.dumps(llm_input, ensure_ascii=False)

    for forbidden_key in ("graphId", "graphRevision", "nodeId", "edgeId", "evidenceId", "nodeIdentity", "edgeIdentity"):
        assert not contains_key(llm_input, forbidden_key)
    assert "public-source-id" in rendered_input
    assert "public-source-id" in prompt
    for secret in secrets:
        assert secret not in rendered_input
        assert secret not in prompt
    assert prompt.count("public worker call evidence") == 1

    class ForeignRefThenValidProvider:
        def __init__(self):
            self.calls = []

        def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
            self.calls.append({"llmInput": dict(llm_input), "validationErrors": list(validation_errors or [])})
            if len(self.calls) == 1:
                payload = {
                    "steps": [{"factRefs": ["secret-node-db-id"], "text": "PublicEntry calls PublicWorker."}],
                    "result": "PublicWorker returns the result.",
                }
            else:
                payload = structured_answer(
                    llm_input,
                    "PublicEntry calls PublicWorker using the supplied public evidence.",
                    result="PublicWorker returns the grounded result.",
                )
            return FlowExplanationProviderResult(raw_text=json.dumps(payload), prompt_char_length=100)

    provider = ForeignRefThenValidProvider()
    service = HumanFlowAnswerService(provider)
    response = service.answer(
        KnowledgeQueryRequest(queryText="explain public entry", intent="FLOW_EXPLANATION"),
        human_execution(graph_flow),
        plan=retrieval_plan("explain public entry", detected_language="en", response_language="en"),
    )

    public_response = response.answers[0].text
    validation_errors = json.dumps(provider.calls[1]["validationErrors"], ensure_ascii=False)
    for secret in secrets:
        assert secret not in public_response
        assert secret not in validation_errors
    assert "foreign factRef" in validation_errors


def test_full_human_prompt_preserves_all_evidence_without_compaction():
    long_a = "alpha-" + ("A" * 1200)
    long_b = "beta-" + ("B" * 1300)
    evidence_items = [
        evidence(f"ev-{index}", None, "Root.run", 10 + index, long_a if index % 2 == 0 else long_b)
        for index in range(10)
    ]
    graph_flow = replace(
        flow([node("Root.run", entrypoint=True)], []),
        evidence=tuple(evidence_items),
    )

    llm_input = FlowProjectionBuilder().human_llm_input(
        KnowledgeQueryRequest(queryText="explain root", intent="FLOW_EXPLANATION", answerLanguage="en"),
        graph_flow,
        retrieval_plan("explain root", detected_language="en", response_language="en"),
    )
    prompt = HumanAnswerPromptRenderer().render(llm_input)

    for item in evidence_items:
        assert item.text in prompt
        assert f'"lineStart":{item.line_start}' in prompt
        assert f'"lineEnd":{item.line_end}' in prompt
    assert prompt.count(long_a) >= 5
    assert prompt.count(long_b) >= 5
    assert "..." not in long_a


def test_context_overflow_fails_before_final_provider_call():
    huge = "payload-" + ("X" * 5000)
    graph_flow = replace(
        flow([node("Root.run", entrypoint=True)], []),
        evidence=(evidence("ev-huge", None, "Root.run", 10, huge),),
    )
    provider = SequenceHumanAnswerProvider(["Root.run returns a result."])
    service = HumanFlowAnswerService(
        provider,
        budget_estimator=PromptBudgetEstimator(
            context_tokens=128,
            reserved_output_tokens=0,
            repair_prompt_overhead_tokens=0,
            multilingual_prose_overhead_tokens=0,
            json_formatting_overhead_tokens=0,
        ),
    )

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="explain root", intent="FLOW_EXPLANATION"),
            human_execution(graph_flow),
            plan=retrieval_plan("explain root", detected_language="en", response_language="en"),
        )
    except HumanAnswerContextBudgetExceeded:
        pass
    else:
        raise AssertionError("Expected complete context overflow to fail explicitly")

    assert provider.calls == []


def test_ollama_requests_use_reserved_output_budget_for_first_and_repair_attempts():
    bad_response = json.dumps(
        {
            "steps": [{"factRefs": ["n1"], "text": "**Root.run** starts from the supplied fact."}],
            "result": "Root.run returns the verified result.",
        }
    )
    good_response = json.dumps(
        {
            "steps": [{"factRefs": ["n1"], "text": "Root.run starts from the supplied fact."}],
            "result": "Root.run returns the verified result.",
        }
    )
    recorder = RecordingOllamaHttpClient([bad_response, good_response])
    context_tokens = 32768
    reserved_output_tokens = DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS
    client = LocalOllamaFlowExplanationClient(
        "http://127.0.0.1:11434",
        "qwen2.5-coder:14b",
        120,
        context_tokens,
        http_client=recorder,
        reserved_output_tokens=reserved_output_tokens,
    )
    service = HumanFlowAnswerService(
        client,
        context_tokens=context_tokens,
        budget_estimator=PromptBudgetEstimator(
            context_tokens=context_tokens,
            reserved_output_tokens=reserved_output_tokens,
        ),
        reserved_output_tokens=reserved_output_tokens,
    )

    try:
        response = service.answer(
            KnowledgeQueryRequest(queryText="explain root", intent="FLOW_EXPLANATION"),
            human_execution(flow([node("Root.run", entrypoint=True)], [])),
            plan=retrieval_plan("explain root", detected_language="en", response_language="en"),
        )
    finally:
        client.close()

    assert "Root.run starts from the supplied fact" in response.answers[0].text
    assert len(recorder.posts) == 2
    for post in recorder.posts:
        assert post["json"]["options"]["num_ctx"] == context_tokens
        assert post["json"]["options"]["num_predict"] == reserved_output_tokens


def test_oversized_full_prompt_fails_before_ollama_provider_request():
    huge = "payload-" + ("X" * 5000)
    graph_flow = replace(
        flow([node("Root.run", entrypoint=True)], []),
        evidence=(evidence("ev-huge", None, "Root.run", 10, huge),),
    )
    recorder = RecordingOllamaHttpClient([json.dumps(structured_answer({"coverageContract": {"canonicalFactRefs": ["n1"]}}, "Root.run returns."))])
    reserved_output_tokens = 512
    client = LocalOllamaFlowExplanationClient(
        "http://127.0.0.1:11434",
        "qwen2.5-coder:14b",
        120,
        32768,
        http_client=recorder,
        reserved_output_tokens=reserved_output_tokens,
    )
    service = HumanFlowAnswerService(
        client,
        budget_estimator=PromptBudgetEstimator(
            context_tokens=128,
            reserved_output_tokens=reserved_output_tokens,
            repair_prompt_overhead_tokens=0,
            multilingual_prose_overhead_tokens=0,
            json_formatting_overhead_tokens=0,
        ),
        reserved_output_tokens=reserved_output_tokens,
    )

    try:
        try:
            service.answer(
                KnowledgeQueryRequest(queryText="explain root", intent="FLOW_EXPLANATION"),
                human_execution(graph_flow),
                plan=retrieval_plan("explain root", detected_language="en", response_language="en"),
            )
        except HumanAnswerContextBudgetExceeded:
            pass
        else:
            raise AssertionError("Expected complete context overflow to fail before Ollama request")
    finally:
        client.close()

    assert recorder.posts == []


def test_context_overflow_preflights_all_flows_without_partial_answer():
    small_flow = flow([node("Small.run", entrypoint=True)], [])
    huge_flow = replace(
        flow([node("Huge.run", entrypoint=True)], []),
        evidence=(evidence("ev-huge", None, "Huge.run", 10, "payload-" + ("X" * 30000)),),
    )
    provider = SequenceHumanAnswerProvider(["Small.run returns.", "Huge.run returns."])
    service = HumanFlowAnswerService(
        provider,
        budget_estimator=PromptBudgetEstimator(
            context_tokens=20000,
            reserved_output_tokens=0,
            repair_prompt_overhead_tokens=0,
            multilingual_prose_overhead_tokens=0,
            json_formatting_overhead_tokens=0,
        ),
    )

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="explain all", intent="FLOW_EXPLANATION"),
            SimpleNamespace(flows=(small_flow, huge_flow)),
            plan=retrieval_plan("explain all", detected_language="en", response_language="en"),
        )
    except HumanAnswerContextBudgetExceeded:
        pass
    else:
        raise AssertionError("Expected complete context overflow to fail before any flow answer")

    assert provider.calls == []


class FactListingProvider:
    def __init__(self):
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "validationErrors": list(validation_errors or [])})
        symbols = [
            str(fact.get("displaySymbol") or fact.get("toSymbol") or fact.get("target") or "")
            for fact in llm_input["orderedFacts"]
            if fact.get("type") == "node"
        ]
        return FlowExplanationProviderResult(
            raw_text=json.dumps(structured_answer(llm_input, "The flow covers " + ", ".join(symbols), result="The verified flow is fully covered.")),
            prompt_char_length=100,
        )


def test_deep_sequential_flow_keeps_all_nodes_and_transitions_in_llm_input():
    nodes = [node("Root.run", entrypoint=True), *[node(f"Step{i}.run") for i in range(1, 16)]]
    transitions = [edge("edge-0", "Root.run", "Step1.run")]
    transitions.extend(edge(f"edge-{index}", f"Step{index}.run", f"Step{index + 1}.run") for index in range(1, 15))
    graph_flow = flow(nodes, transitions)
    provider = FactListingProvider()
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="explain deep flow", intent="FLOW_EXPLANATION"),
        human_execution(graph_flow),
        plan=retrieval_plan("explain deep flow", detected_language="en", response_language="en"),
    )

    facts = provider.calls[0]["llmInput"]["orderedFacts"]
    assert len([fact for fact in facts if fact["type"] == "node"]) == 16
    assert len([fact for fact in facts if fact["type"] == "transition"]) == 15
    for item in nodes:
        assert item.node_id in response.answers[0].text


def test_large_flow_projector_preserves_2000_transitions_and_uses_linear_duplicate_tracking():
    transition_count = 2000
    nodes = [node("Step0000.run", entrypoint=True)]
    nodes.extend(node(f"Step{index:04d}.run") for index in range(1, transition_count + 1))
    transitions = [
        edge(f"edge-{index:04d}", f"Step{index:04d}.run", f"Step{index + 1:04d}.run")
        for index in range(transition_count)
    ]
    evidence_items = tuple(
        evidence(f"ev-{index:04d}", f"edge-{index:04d}", None, index + 1, f"complete evidence excerpt {index:04d}")
        for index in range(transition_count)
    )
    graph_flow = replace(
        flow(nodes, transitions),
        evidence=evidence_items,
        coverage=EntrypointFlowCoverage(len(nodes), len(transitions), 0, 1, transition_count),
    )

    started = time.perf_counter()
    llm_input = FlowProjectionBuilder().human_llm_input(
        KnowledgeQueryRequest(queryText="explain large flow", intent="FLOW_EXPLANATION", answerLanguage="en"),
        graph_flow,
        retrieval_plan("explain large flow", detected_language="en", response_language="en"),
    )
    elapsed = time.perf_counter() - started

    facts = llm_input["orderedFacts"]
    node_facts = [fact for fact in facts if fact["type"] == "node"]
    transition_facts = [fact for fact in facts if fact["type"] == "transition"]
    projected_evidence = [
        evidence_item
        for fact in facts
        for evidence_item in fact.get("evidence", [])
    ]
    assert len(node_facts) == transition_count + 1
    assert len(transition_facts) == transition_count
    assert len(projected_evidence) == transition_count
    assert {item["excerpt"] for item in projected_evidence} == {item.text for item in evidence_items}
    assert len(llm_input["coverageContract"]["canonicalFactRefs"]) == len(facts)
    assert "truncated" not in json.dumps(llm_input, ensure_ascii=False).lower()
    projector_source = inspect.getsource(FlowProjectionBuilder._ordered_facts)
    assert "seen_fact_refs" in projector_source
    assert 'any(item.get("ref") == ref for item in facts)' not in projector_source
    assert elapsed < 5.0


class RepairingOrderProvider:
    def __init__(self):
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "validationErrors": list(validation_errors or [])})
        refs = list(llm_input["coverageContract"]["canonicalFactRefs"])
        if len(self.calls) == 1:
            payload = {
                "steps": [{"factRefs": [refs[-1], refs[0]], "text": "Branch facts are flattened out of order."}],
                "result": "The flow is flattened.",
            }
        else:
            payload = structured_answer(llm_input, "Root.run preserves the ordered branch facts.", refs=refs, result="The branches remain distinct.")
        return FlowExplanationProviderResult(raw_text=json.dumps(payload), prompt_char_length=100)


def test_branching_flow_preserves_callsite_order_and_repairs_flattened_order():
    root = node("Root.run", entrypoint=True)
    left = node("LeftBranch.run")
    right = node("RightBranch.run")
    nested = node("NestedRight.run")
    left_edge = edge("left", "Root.run", "LeftBranch.run")
    right_edge = edge("right", "Root.run", "RightBranch.run")
    nested_edge = edge("nested", "RightBranch.run", "NestedRight.run")
    graph_flow = replace(
        flow([root, left, right, nested], [right_edge, nested_edge, left_edge]),
        evidence=(
            evidence("ev-left", "left", None, 12, "leftBranch.run()"),
            evidence("ev-right", "right", None, 20, "rightBranch.run()"),
            evidence("ev-nested", "nested", None, 21, "nestedRight.run()"),
        ),
    )
    provider = RepairingOrderProvider()
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="explain branches", intent="FLOW_EXPLANATION"),
        human_execution(graph_flow),
        plan=retrieval_plan("explain branches", detected_language="en", response_language="en"),
    )

    facts = provider.calls[0]["llmInput"]["orderedFacts"]
    assert [fact.get("displaySymbol") or fact.get("toSymbol") for fact in facts] == [
        "Root.run",
        "LeftBranch.run",
        "LeftBranch.run",
        "RightBranch.run",
        "RightBranch.run",
        "NestedRight.run",
        "NestedRight.run",
    ]
    assert len(provider.calls) == 2
    assert any("canonical order" in error for error in provider.calls[1]["validationErrors"])
    assert "branches remain distinct" in response.answers[0].text.lower()


def test_auto_language_resolves_ukrainian_and_accepts_ukrainian_prose():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites входить у SiteController.createSite і передає запит далі.\n2. Наприкінці повертається підтверджена відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert response.answerLanguage == "uk"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "uk"
    assert "передає запит" in response.answers[0].text


def test_auto_language_resolves_english_and_accepts_english_prose():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites enters SiteController.createSite and maps the request.\n2. The final result is returned to the caller."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how is a site created", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("how is a site created", detected_language="en", response_language="en"),
    )

    assert response.answerLanguage == "en"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "en"
    assert "enters SiteController.createSite" in response.answers[0].text


def test_explicit_language_override_keeps_german_for_ukrainian_question():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites kommt in SiteController.createSite an und verarbeitet die Anfrage.\n2. Am Ende wird die bestätigte Antwort zurückgegeben."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="de"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="de"),
    )

    assert response.answerLanguage == "de"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "de"


def test_mixed_technical_query_resolves_to_surrounding_ukrainian_language():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.createSite приймає запит і передає його в наступний крок.\n2. Результат повертається клієнту."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як працює SiteController.createSite", detected_language="uk", response_language="uk"),
    )

    assert response.answerLanguage == "uk"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "uk"


def test_language_violation_gets_one_repair_attempt_for_same_flow():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites enters SiteController.createSite and maps the request.\n2. The controller returns the created response.",
        "1. POST /api/v1/sites входить у SiteController.createSite і передає запит далі.\n2. Результат повертається клієнту.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert response.answerLanguage == "uk"
    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]
    assert "language" in provider.calls[1]["validationErrors"][0].lower()
    assert "передає запит" in response.answers[0].text


def test_plain_text_format_violation_gets_bounded_repair():
    provider = SequenceHumanAnswerProvider([
        {"text": "**Flow**\n1. `SiteController.createSite` receives the request."},
        "1. SiteController.createSite приймає запит і передає його в наступний крок.\n2. Відповідь повертається клієнту.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 2
    assert any("markdown" in error.lower() or "backticks" in error.lower() for error in provider.calls[1]["validationErrors"])
    assert "**" not in response.answers[0].text
    assert "`" not in response.answers[0].text


def test_final_forbidden_language_gets_one_repair_attempt_for_ukrainian_response():
    provider = SequenceHumanAnswerProvider([
        "Как работает контроллер: SiteController.createSite получает запрос. После этого метод возвращает ответ.",
        "SiteController.createSite приймає запит і повертає підтверджену відповідь.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="Как работает контроллер", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("Как работает контроллер", detected_language="ru", response_language="uk"),
    )

    assert response.answerLanguage == "uk"
    assert len(provider.calls) == 2
    assert any("not allowed" in error for error in provider.calls[1]["validationErrors"])
    assert "підтверджену відповідь" in response.answers[0].text


def test_final_forbidden_language_fails_after_second_violation():
    provider = SequenceHumanAnswerProvider([
        "Процесс начинается с проверки параметров. Затем код обращается к базе и формирует результат.",
        "Объект передается дальше для обработки. Приложение принимает сообщение и вызывает сервис.",
    ])
    service = HumanFlowAnswerService(provider)

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="як працює потік", intent="FLOW_EXPLANATION"),
            human_execution(technical_create_site_flow()),
            plan=retrieval_plan("як працює потік", detected_language="uk", response_language="uk"),
        )
    except HumanAnswerGenerationFailed:
        pass
    else:
        raise AssertionError("Expected forbidden final answer language to fail")

    assert len(provider.calls) == 2
    assert all(any("not allowed" in error for error in call["validationErrors"]) for call in provider.calls[1:])


def test_ukrainian_prose_with_few_language_specific_characters_is_accepted():
    examples = [
        "Запит проходить через контролер і сервіс.",
        "Код передає дані в сервіс та повертає результат.",
        "Система приймає запит, потім передає його далі.",
        "Контролер бере запит та викликає сервіс.",
        "Код має маршрут та статус.",
        "Запит має результат та опис.",
        "Сервіс бере дані та дає результат.",
    ]
    for example in examples:
        provider = SequenceHumanAnswerProvider([example])
        service = HumanFlowAnswerService(provider)

        response = service.answer(
            KnowledgeQueryRequest(queryText="як працює потік", intent="FLOW_EXPLANATION"),
            human_execution(technical_create_site_flow()),
            plan=retrieval_plan("як працює потік", detected_language="uk", response_language="uk"),
        )

        assert example in response.answers[0].text
        assert len(provider.calls) == 1


def test_final_english_prose_when_german_response_gets_repaired():
    provider = SequenceHumanAnswerProvider([
        "POST /api/v1/sites enters SiteController.createSite and maps the request. The final result is returned to the caller.",
        "POST /api/v1/sites kommt in SiteController.createSite an und verarbeitet die Anfrage. Am Ende wird die bestätigte Antwort zurückgegeben.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="wie wird eine site erstellt", intent="FLOW_EXPLANATION", answerLanguage="de"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("wie wird eine site erstellt", detected_language="de", response_language="de"),
    )

    assert response.answerLanguage == "de"
    assert len(provider.calls) == 2
    assert any("detected dominant prose language en" in error for error in provider.calls[1]["validationErrors"])
    assert "bestätigte Antwort" in response.answers[0].text


def test_correct_long_german_text_is_accepted_for_german_response_language():
    provider = SequenceHumanAnswerProvider([
        "POST /api/v1/sites kommt in SiteController.createSite an und verarbeitet die Anfrage. "
        "Danach ruft der Controller CreateSiteImpl.execute auf, speichert die geprüften Daten und gibt die bestätigte Antwort zurück."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="wie wird eine site erstellt", intent="FLOW_EXPLANATION", answerLanguage="de"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("wie wird eine site erstellt", detected_language="de", response_language="de"),
    )

    assert len(provider.calls) == 1
    assert response.answerLanguage == "de"
    assert "bestätigte Antwort" in response.answers[0].text


def test_final_english_prose_when_german_response_fails_after_repair():
    provider = SequenceHumanAnswerProvider([
        "POST /api/v1/sites enters SiteController.createSite and maps the request. The final result is returned to the caller.",
        "The controller receives the request and returns the confirmed result after processing.",
    ])
    service = HumanFlowAnswerService(provider)

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="wie wird eine site erstellt", intent="FLOW_EXPLANATION", answerLanguage="de"),
            human_execution(technical_create_site_flow()),
            plan=retrieval_plan("wie wird eine site erstellt", detected_language="de", response_language="de"),
        )
    except HumanAnswerGenerationFailed:
        pass
    else:
        raise AssertionError("Expected repeated wrong final answer language to fail")

    assert len(provider.calls) == 2
    assert any("detected dominant prose language en" in error for error in provider.calls[1]["validationErrors"])


def test_undetermined_short_output_gets_one_repair_attempt():
    provider = SequenceHumanAnswerProvider([
        "OK",
        "SiteController.createSite приймає запит і передає його в CreateSiteImpl.execute. Після обробки контролер повертає підтверджену відповідь.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 2
    assert any("could not be determined" in error for error in provider.calls[1]["validationErrors"])
    assert "підтверджену відповідь" in response.answers[0].text


def test_repeated_undetermined_short_output_fails_flow():
    provider = SequenceHumanAnswerProvider(["OK", "OK"])
    service = HumanFlowAnswerService(provider)

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
            human_execution(technical_create_site_flow()),
            plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
        )
    except HumanAnswerGenerationFailed:
        pass
    else:
        raise AssertionError("Expected repeated undetermined final answer language to fail")

    assert len(provider.calls) == 2
    assert any("could not be determined" in error for error in provider.calls[1]["validationErrors"])


def test_missing_language_detector_dependency_cannot_silently_accept_output(monkeypatch):
    monkeypatch.setattr(answer_language, "detect_langs", None)

    result = HumanAnswerTextValidator().validate(
        "SiteController.createSite приймає запит і повертає підтверджену відповідь після обробки.",
        "uk",
    )

    assert result.valid is False
    assert result.errors == ["Response prose language validator is unavailable."]


def test_code_identifiers_do_not_affect_prose_language_validation():
    provider = SequenceHumanAnswerProvider([
        "SiteController.createSite verarbeitet die Anfrage für /api/v1/sites und ruft CreateSiteImpl.execute auf. "
        "Danach speichert SiteRepositoryImpl.save den Stand SITE_STATUS und das Thema payments.created bleibt unverändert."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="wie wird eine site erstellt", intent="FLOW_EXPLANATION", answerLanguage="de"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("wie wird eine site erstellt", detected_language="de", response_language="de"),
    )

    assert len(provider.calls) == 1
    assert response.answerLanguage == "de"
    assert "SiteController.createSite" in response.answers[0].text


def test_valid_single_step_payload_is_rendered_as_numbered_plain_text():
    provider = SequenceHumanAnswerProvider([
        "SiteController.createSite приймає HTTP POST запит на /api/v1/sites і передає його в CreateSiteImpl.execute. Наприкінці контролер повертає створену відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 1
    assert not provider.calls[0]["validationErrors"]
    assert response.answers[0].text.startswith("1.")


def test_valid_single_step_answer_is_accepted():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.createSite приймає HTTP POST запит на /api/v1/sites і повертає створену відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 1
    assert response.answers[0].text.startswith("1.")


def test_grounded_route_with_sentence_punctuation_is_accepted():
    provider = SequenceHumanAnswerProvider([
        "SiteController.createSite приймає HTTP POST запит на /api/v1/sites. Контролер передає виконання в CreateSiteImpl.execute і повертає створену відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 1
    assert "/api/v1/sites." in response.answers[0].text


def test_valid_lettered_branch_explanation_is_accepted():
    provider = SequenceHumanAnswerProvider([
        "SiteController.createSite має дві гілки: а) SiteApiMapper.asCreateSiteCommand готує команду; б) CreateSiteImpl.execute валідує назву і зберігає результат. Після цього контролер повертає відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 1
    assert "а)" in response.answers[0].text


def test_internal_ref_leak_gets_repaired_out_of_human_answer():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.getSiteOverview викликає nodeRef n1.\n2. Контролер повертає DTO.",
        "1. SiteController.getSiteOverview викликає GetSiteOverview.execute.\n2. Контролер повертає DTO.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як працює SiteController.getSiteOverview", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як працює SiteController.getSiteOverview", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 2
    assert any("internal graph refs" in error for error in provider.calls[1]["validationErrors"])
    assert "nodeRef" not in response.answers[0].text


def test_bare_response_local_ref_leak_gets_repaired_out_of_human_answer():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.getSiteOverview проходить через transition t1.\n2. Контролер повертає DTO.",
        "1. SiteController.getSiteOverview викликає GetSiteOverview.execute.\n2. Контролер повертає DTO.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як працює SiteController.getSiteOverview", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як працює SiteController.getSiteOverview", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 2
    assert any("internal graph refs" in error for error in provider.calls[1]["validationErrors"])
    assert "t1" not in response.answers[0].text


def test_unresolved_call_wording_is_valid_when_not_internal_ref():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.getSiteOverview reaches an unresolved call to GetSiteOverview.execute.\n2. The controller returns the DTO."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how does SiteController.getSiteOverview work", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("how does SiteController.getSiteOverview work", detected_language="en", response_language="en"),
    )

    assert len(provider.calls) == 1
    assert "unresolved call" in response.answers[0].text


def test_natural_conclusion_after_steps_is_accepted():
    provider = SequenceHumanAnswerProvider([
        "1. AgentProjectController.createAgentProject приймає запит.\n2. Контролер повертає DTO.\nПеревірені факти не містять деталей збереження."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити агентський проект", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("як створити агентський проект", detected_language="uk", response_language="uk"),
    )

    assert len(provider.calls) == 1
    assert "не містять деталей збереження" in response.answers[0].text


def test_boundary_wording_is_valid_when_not_internal_ref():
    provider = SequenceHumanAnswerProvider([
        "1. AgentController.createAgent reaches an external client boundary.\n2. The controller returns the DTO."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how is an agent created", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
        plan=retrieval_plan("how is an agent created", detected_language="en", response_language="en"),
    )

    assert len(provider.calls) == 1
    assert "boundary" in response.answers[0].text


def test_repair_failure_fails_flow_without_projecting_compact_tree():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites enters SiteController.createSite and returns a response.",
        "1. POST /api/v1/sites still answers in English.",
    ])
    service = HumanFlowAnswerService(provider)

    try:
        service.answer(
            KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
            human_execution(technical_create_site_flow()),
            plan=retrieval_plan("як створити сайт", detected_language="uk", response_language="uk"),
        )
    except HumanAnswerGenerationFailed:
        pass
    else:
        raise AssertionError("Expected human answer generation to fail")

    assert len(provider.calls) == 2


def test_generic_prompt_and_projector_work_for_unrelated_flow_without_create_site_language():
    graph_flow = unrelated_listener_flow()
    provider = SequenceHumanAnswerProvider([
        "1. Topic payments.created enters PaymentListener.handle and the event is validated.\n2. LedgerRepository.save persists the ledger row, then PaymentAcknowledgement.publish emits the acknowledgement."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how is the payment event handled", intent="FLOW_EXPLANATION"),
        human_execution(graph_flow),
        plan=retrieval_plan("how is the payment event handled", detected_language="en", response_language="en"),
    )
    prompt = HumanAnswerPromptRenderer().render(provider.calls[0]["llmInput"])

    assert response.answers[0].entrypoint == "PaymentListener.handle"
    assert "PaymentListener.handle" in response.answers[0].text
    forbidden = ("SiteController", "CreateSiteImpl", "SiteRepository", "SiteCreatedPayload", "/api/v1/sites", "stsssox")
    assert not any(token in prompt for token in forbidden)
    assert not any(token in response.answers[0].text for token in forbidden)
