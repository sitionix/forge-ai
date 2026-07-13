from __future__ import annotations

import json
from dataclasses import replace

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_explanations import CompactFlowProjector, FlowExplanationContextPacker, FlowExplanationValidator, HumanAnswerPromptRenderer
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest


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


def valid_response_for(context):
    steps = context.llm_input["steps"]
    transitions = context.llm_input["transitions"]
    boundaries = context.llm_input["boundaries"]
    return {
        "title": "Graph flow explanation",
        "narrative": [
            {
                "text": "This graph slice is described through node references, transition references, and boundary references without adding a path order.",
                "nodeRefs": [item["nodeRef"] for item in steps],
                "transitionRefs": [item["transitionRef"] for item in transitions],
                "boundaryRefs": [item["boundaryRef"] for item in boundaries],
            },
            {
                "text": "The second grounded block explains that branches, shared descendants, and cycles remain graph structure rather than sequential steps.",
                "nodeRefs": [item["nodeRef"] for item in steps],
                "transitionRefs": [item["transitionRef"] for item in transitions],
                "boundaryRefs": [item["boundaryRef"] for item in boundaries],
            },
        ],
        "steps": [
            {
                "nodeRef": step["nodeRef"],
                "explanation": f"`{step['symbol']}` is a node in the entrypoint-rooted graph.",
                "transitionRefs": [
                    item["transitionRef"]
                    for item in transitions
                    if item["fromNodeRef"] == step["nodeRef"]
                ],
                "evidenceRefs": [],
            }
            for step in steps
        ],
        "transitions": [
            {
                "transitionRef": item["transitionRef"],
                "explanation": f"`{item['fromSymbol']}` has a CALLS transition to `{item['toSymbol']}`.",
                "evidenceRefs": [],
            }
            for item in transitions
        ],
        "boundaries": [
            {
                "boundaryRef": item["boundaryRef"],
                "kind": item["kind"],
                "explanation": f"{item['kind']} remains a boundary in this graph slice.",
                "evidenceRefs": [],
            }
            for item in boundaries
        ],
    }


def assert_validator_accepts_graph(graph_flow: EntrypointFlow) -> None:
    context = FlowExplanationContextPacker().pack(
        request=KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION"),
        flow=graph_flow,
        flow_index=1,
        source_display_name=SOURCE,
    )
    explanation, errors, code = FlowExplanationValidator().validate(json.dumps(valid_response_for(context)), context)
    assert explanation is not None, (code, errors)


def test_validator_accepts_sibling_branch_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma"), node("Delta")],
            [edge("ab", "Alpha", "Beta"), edge("ag", "Alpha", "Gamma"), edge("ad", "Alpha", "Delta")],
        )
    )


def test_validator_accepts_diamond_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma"), node("Delta")],
            [edge("ab", "Alpha", "Beta"), edge("ag", "Alpha", "Gamma"), edge("bd", "Beta", "Delta"), edge("gd", "Gamma", "Delta")],
        )
    )


def test_validator_accepts_cycle_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma")],
            [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma"), edge("gb", "Gamma", "Beta")],
        )
    )


def test_validator_accepts_external_boundary_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True)],
            [],
            [edge("outside", "Alpha", None, status="EXTERNAL_TARGET")],
        )
    )


def test_complete_technical_flow_prompt_contains_grounded_trigger_and_steps():
    request = KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="uk")
    llm_input = CompactFlowProjector().human_llm_input(request, technical_create_site_flow())
    prompt = HumanAnswerPromptRenderer().render(llm_input)
    rendered = json.dumps(llm_input, ensure_ascii=False)

    assert llm_input["tree"]["trigger"] == {
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

    root_children = [item["symbol"] for item in llm_input["tree"]["children"]]
    assert root_children == [
        "SiteApiMapper.asCreateSiteCommand",
        "CreateSiteImpl.execute",
        "SiteApiMapper.asCreateSiteResponseDTO",
    ]
    execute_children = llm_input["tree"]["children"][1]["children"]
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
    projected = CompactFlowProjector().human_llm_input(
        KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION", answerLanguage="en"),
        replace(
            base,
            evidence=(
                evidence("e-boundary", "boundary-earlier", None, 10, "mapper.asCommand(request)"),
                evidence("e-resolved", "resolved-later", None, 20, "service.execute(command)"),
            ),
        ),
    )

    assert [child["symbol"] for child in projected["tree"]["children"]] == ["SiteApiMapper.asCreateSiteCommand", "ResolvedLater"]


def test_human_prompt_contract_requires_grounded_step_by_step_output():
    prompt = HumanAnswerPromptRenderer().render(
        CompactFlowProjector().human_llm_input(
            KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION", answerLanguage="en"),
            technical_create_site_flow(),
        )
    )

    for required in (
        "detailed technical step-by-step",
        "HTTP method and route",
        "entrypoint symbol",
        "CALLS-tree order",
        "exact class or method symbol",
        "validation, persistence, or side effect",
        "Use only the requested answerLanguage",
        "plain numbered steps",
        "exception classes, or error messages",
        "observable result",
        "Keep the answer plain text",
        "Do not collapse the flow into a generic summary",
        "Do not invent validation",
        "Do not call an outbox write a direct Kafka publish",
        "Do not mention graph terminology",
    ):
        assert required in prompt


def test_missing_trigger_metadata_is_not_invented():
    request = KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="uk")
    llm_input = CompactFlowProjector().human_llm_input(request, technical_create_site_flow(with_trigger=False))

    assert "trigger" not in llm_input["tree"]
    rendered = json.dumps(llm_input, ensure_ascii=False)
    assert "POST" not in rendered
    assert "/api/v1/sites" not in rendered
