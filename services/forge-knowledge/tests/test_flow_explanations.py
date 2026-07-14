from __future__ import annotations

import json
from types import SimpleNamespace
from dataclasses import replace

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_explanations import (
    CompactFlowProjector,
    FlowExplanationContextPacker,
    FlowExplanationProviderResult,
    FlowExplanationValidator,
    HumanAnswerGenerationFailed,
    HumanAnswerPromptRenderer,
    HumanFlowAnswerService,
)
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
            raw = json.dumps({"text": response}, ensure_ascii=False)
        else:
            raw = json.dumps(response, ensure_ascii=False)
        return FlowExplanationProviderResult(raw_text=raw, prompt_char_length=100)


def human_execution(graph_flow: EntrypointFlow):
    return SimpleNamespace(flows=(graph_flow,))


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
        "technical walkthrough",
        "HTTP method and route",
        "trigger and entrypoint",
        "Use numbered steps",
        "exact class or method symbol",
        "validation, persistence, or side effect",
        "Use only the requested responseLanguage",
        "exception classes, or error messages",
        "observable result",
        "escaped plain text",
        "Do not use Markdown",
        "Do not use repeated audit labels",
        "Do not collapse the flow into a generic summary",
        "Do not invent validation",
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


def test_auto_language_resolves_ukrainian_and_accepts_ukrainian_prose():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites входить у SiteController.createSite і передає запит далі.\n2. Наприкінці повертається підтверджена відповідь."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
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
    )

    assert response.answerLanguage == "en"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "en"
    assert "enters SiteController.createSite" in response.answers[0].text


def test_explicit_language_override_keeps_english_for_ukrainian_question():
    provider = SequenceHumanAnswerProvider([
        "1. POST /api/v1/sites enters SiteController.createSite and maps the request.\n2. The final result is returned to the caller."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION", answerLanguage="en"),
        human_execution(technical_create_site_flow()),
    )

    assert response.answerLanguage == "en"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "en"


def test_mixed_technical_query_resolves_to_surrounding_ukrainian_language():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.createSite приймає запит і передає його в наступний крок.\n2. Результат повертається клієнту."
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
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
    )

    assert response.answerLanguage == "uk"
    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]
    assert "Ukrainian" in provider.calls[1]["validationErrors"][0]
    assert "передає запит" in response.answers[0].text


def test_markdown_and_language_violation_gets_bounded_plain_text_repair():
    provider = SequenceHumanAnswerProvider([
        {"text": "**Flow**\n1. `SiteController.createSite` receives the request."},
        "1. SiteController.createSite приймає запит і передає його в наступний крок.\n2. Відповідь повертається клієнту.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("Markdown" in error for error in provider.calls[1]["validationErrors"])
    assert "**" not in response.answers[0].text
    assert "`" not in response.answers[0].text


def test_paragraph_walkthrough_gets_repaired_to_numbered_steps():
    provider = SequenceHumanAnswerProvider([
        "SiteController.createSite приймає HTTP POST запит на /api/v1/sites і передає його в CreateSiteImpl.execute. Наприкінці контролер повертає створену відповідь.",
        "1. SiteController.createSite приймає HTTP POST запит на /api/v1/sites і передає його в CreateSiteImpl.execute.\n2. Наприкінці контролер повертає створену відповідь.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("numbered walkthrough" in error for error in provider.calls[1]["validationErrors"])
    assert response.answers[0].text.startswith("1.")


def test_observable_result_label_gets_repaired_to_plain_numbered_prose():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.createSite приймає HTTP POST запит на /api/v1/sites.\n2. Observable result: контролер повертає створену відповідь.",
        "1. SiteController.createSite приймає HTTP POST запит на /api/v1/sites.\n2. Наприкінці контролер повертає створену відповідь.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("Observable result" in error for error in provider.calls[1]["validationErrors"])
    assert "Observable result:" not in response.answers[0].text


def test_lettered_substeps_get_repaired_to_numbered_walkthrough_only():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.createSite приймає запит.\n   а) CreateSiteImpl.execute валідує назву.\n2. Контролер повертає відповідь.",
        "1. SiteController.createSite приймає запит і передає його до CreateSiteImpl.execute.\n2. CreateSiteImpl.execute валідує назву, після чого контролер повертає відповідь.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити сайт", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("lettered" in error for error in provider.calls[1]["validationErrors"])
    assert "а)" not in response.answers[0].text


def test_graph_edge_terms_get_repaired_out_of_human_answer():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.getSiteOverview викликає UNRESOLVED_CALL GetSiteOverview.execute.\n2. Контролер повертає DTO.",
        "1. SiteController.getSiteOverview викликає GetSiteOverview.execute.\n2. Контролер повертає DTO.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як працює SiteController.getSiteOverview", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("graph terminology" in error for error in provider.calls[1]["validationErrors"])
    assert "UNRESOLVED_CALL" not in response.answers[0].text


def test_unresolved_call_wording_gets_repaired_out_of_human_answer():
    provider = SequenceHumanAnswerProvider([
        "1. SiteController.getSiteOverview reaches an unresolved call to GetSiteOverview.execute.\n2. The controller returns the DTO.",
        "1. SiteController.getSiteOverview calls GetSiteOverview.execute.\n2. The controller returns the DTO.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how does SiteController.getSiteOverview work", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("graph terminology" in error for error in provider.calls[1]["validationErrors"])
    assert "unresolved call" not in response.answers[0].text


def test_unnumbered_paragraph_after_steps_gets_repaired():
    provider = SequenceHumanAnswerProvider([
        "1. AgentProjectController.createAgentProject приймає запит.\n2. Контролер повертає DTO.\nVerified facts do not provide persistence details.",
        "1. AgentProjectController.createAgentProject приймає запит.\n2. Контролер повертає DTO, а перевірені факти не надають деталей збереження.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити агентський проект", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("numbered walkthrough step" in error for error in provider.calls[1]["validationErrors"])
    assert "Verified facts" not in response.answers[0].text


def test_graph_boundary_wording_gets_repaired_out_of_human_answer():
    provider = SequenceHumanAnswerProvider([
        "1. AgentController.createAgent доходить до external client boundary.\n2. Контролер повертає DTO.",
        "1. AgentController.createAgent готує відповідь через ResponseEntity.status(HttpStatus.CREATED).\n2. Контролер повертає DTO.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="як створити агента", intent="FLOW_EXPLANATION"),
        human_execution(technical_create_site_flow()),
    )

    assert len(provider.calls) == 2
    assert any("graph terminology" in error for error in provider.calls[1]["validationErrors"])
    assert "boundary" not in response.answers[0].text


def test_speculative_framework_behavior_gets_repaired():
    provider = SequenceHumanAnswerProvider([
        "1. PaymentListener.handle receives the payment event.\n2. The observable result is likely a default Spring Boot response.",
        "1. PaymentListener.handle receives the payment event and passes it to PaymentService.record.\n2. PaymentService.record saves through LedgerRepository.save and PaymentAcknowledgement.publish emits the acknowledgement.",
    ])
    service = HumanFlowAnswerService(provider)

    response = service.answer(
        KnowledgeQueryRequest(queryText="how does payment handling work", intent="FLOW_EXPLANATION"),
        human_execution(unrelated_listener_flow()),
    )

    assert len(provider.calls) == 2
    assert any("speculate" in error for error in provider.calls[1]["validationErrors"])
    assert "likely" not in response.answers[0].text
    assert "default Spring Boot" not in response.answers[0].text


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
    )
    prompt = HumanAnswerPromptRenderer().render(provider.calls[0]["llmInput"])

    assert response.answers[0].entrypoint == "PaymentListener.handle"
    assert "PaymentListener.handle" in response.answers[0].text
    forbidden = ("SiteController", "CreateSiteImpl", "SiteRepository", "SiteCreatedPayload", "/api/v1/sites", "stsssox")
    assert not any(token in prompt for token in forbidden)
    assert not any(token in response.answers[0].text for token in forbidden)
