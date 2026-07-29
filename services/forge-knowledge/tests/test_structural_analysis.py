import hashlib

import pytest

from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.java_parser_adapter import JavaParserAdapter
from knowledge_service.structural_analysis import StaticGraphMaterializer, StructuralAnalysisEngine
from knowledge_service.structural_model import StructuralFileMetadata


JAVA_SAMPLE = """package example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/tickets")
class TicketController {
  private final TicketMapper mapper;

  @GetMapping("/{id}")
  public ResponseEntity<TicketDto> get(String id) {
    // helper(id);
    String ignored = "mapper.toApi(fake)";
    Ticket ticket = helper(id);
    return ResponseEntity.ok(mapper.toApi(ticket));
  }

  private Ticket helper(String id) {
    requireNonNull(id);
    return new Ticket();
  }
}

class TicketMapper {
  TicketDto toApi(Ticket ticket) {
    return new TicketDto();
  }
}
"""


def metadata(text=JAVA_SAMPLE):
    return StructuralFileMetadata(
        source_id="svc",
        inventory_file_id=7,
        relative_path="src/main/java/example/TicketController.java",
        language="java",
        flow_domain="CODE",
        content_hash=hashlib.sha256(text.encode("utf-8")).hexdigest(),
        line_count=len(text.splitlines()),
        decode_policy="utf-8:replace",
    )


def structural_row(relative_path, flow_domain=None):
    row = {
        "id": 7,
        "source_id": "svc",
        "relative_path": relative_path,
        "extension": "." + relative_path.rsplit(".", 1)[-1] if "." in relative_path.rsplit("/", 1)[-1] else "",
        "language": "unknown",
        "content_hash": "hash-1",
        "decode_policy": "utf-8:replace",
    }
    if flow_domain is not None:
        row["flow_domain"] = flow_domain
    return row


def parse_sample():
    return JavaParserAdapter().parse(JAVA_SAMPLE, metadata())


def test_structural_engine_flow_domain_uses_explicit_row_value():
    engine = StructuralAnalysisEngine()

    assert engine._flow_domain(structural_row("README.md", flow_domain="DOC")) == "DOC"
    assert engine._flow_domain(structural_row("src/main/java/Foo.java", flow_domain="test")) == "TEST"


def test_structural_engine_flow_domain_defaults_unknown_and_missing_to_code():
    engine = StructuralAnalysisEngine()

    assert engine._flow_domain(structural_row("config/service.yaml", flow_domain="UNKNOWN")) == "CODE"
    assert engine._flow_domain(structural_row("README.md")) == "CODE"


@pytest.mark.parametrize(
    "relative_path",
    [
        "src/test/java/FooTest.java",
        "config/service.yaml",
        "README.md",
        "data/file.json",
        ".github/workflows/build.yml",
        "pom.xml",
        "build.gradle",
    ],
)
def test_structural_engine_flow_domain_does_not_route_by_path_or_extension(relative_path):
    engine = StructuralAnalysisEngine()

    assert not hasattr(engine, "flow_domain")
    assert engine._flow_domain(structural_row(relative_path, flow_domain="UNKNOWN")) == "CODE"


def test_java_parser_extracts_package_imports_types_callables_fields_and_annotations():
    result = parse_sample()

    assert result.package_name == "example"
    assert {"ResponseEntity", "requireNonNull"} <= {item.imported_name.rsplit(".", 1)[-1] for item in result.imports}
    assert {item.name for item in result.types} == {"TicketController", "TicketMapper"}
    assert {item.name for item in result.callables} == {"get", "helper", "toApi"}
    assert {item.name for item in result.fields} == {"mapper"}
    assert any(annotation.name == "GetMapping" for item in result.callables for annotation in item.annotations)
    assert all(call.method_name != "fake" for call in result.callsites)
    get = next(item for item in result.callables if item.name == "get")
    assert get.line_start == 14
    assert get.line_end == 20
    assert get.body_line_start == 15
    assert get.body_line_end == 20


def test_java_parser_extracts_type_relations_controller_annotations_override_and_signatures():
    text = """package example;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
class GeneratedController implements GeneratedApi {
  @Override
  public ResponseEntity<ResponseDto> create(RequestDto request) {
    return null;
  }
}

interface GeneratedApi extends BaseApi {
  @PostMapping("/items")
  ResponseEntity<ResponseDto> create(RequestDto request);
}

interface BaseApi {}

class SpecializedController extends GeneratedController {}

class RequestDto {}
class ResponseDto {}
"""
    result = JavaParserAdapter().parse(text, metadata(text))
    types = {item.name: item for item in result.types}

    assert types["GeneratedController"].implemented_interfaces == ["GeneratedApi"]
    assert types["GeneratedApi"].extended_interfaces == ["BaseApi"]
    assert types["SpecializedController"].extended_classes == ["GeneratedController"]
    assert any(annotation.name == "RestController" for annotation in types["GeneratedController"].annotations)
    create = next(item for item in result.callables if item.qualified_name == "example.GeneratedController.create")
    assert create.visibility == "PUBLIC"
    assert create.signature == "create(RequestDto)"
    assert create.parameters == ["RequestDto"]
    assert any(annotation.name == "Override" for annotation in create.annotations)


def test_static_graph_materializer_persists_type_relations_without_controller_override_entrypoint():
    text = """package example;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
class GeneratedController implements GeneratedApi {
  @Override
  public ResponseEntity<ResponseDto> create(RequestDto request) {
    return null;
  }
}

interface GeneratedApi extends BaseApi {
  @PostMapping("/items")
  ResponseEntity<ResponseDto> create(RequestDto request);
}

interface BaseApi {}

class SpecializedController extends GeneratedController {}

class RequestDto {}
class ResponseDto {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    types = {node.name: node for node in graph.nodes if node.nodeKind == "TYPE"}

    implements_edge = next(edge for edge in graph.edges if edge.edgeType == "IMPLEMENTS")
    extends_edges = [edge for edge in graph.edges if edge.edgeType == "EXTENDS"]
    assert implements_edge.fromNodeLocalId == types["GeneratedController"].localId
    assert implements_edge.toNodeLocalId == types["GeneratedApi"].localId
    assert implements_edge.unresolvedTarget is None
    assert implements_edge.metadata["relationKind"] == "IMPLEMENTED_INTERFACE"
    assert [item.lineStart for item in implements_edge.evidence] == [7]
    assert {edge.metadata["relationKind"] for edge in extends_edges} == {"EXTENDED_INTERFACE", "EXTENDED_CLASS"}
    assert {edge.toNodeLocalId for edge in extends_edges} == {types["BaseApi"].localId, types["GeneratedController"].localId}

    interface_create = next(
        node
        for node in graph.nodes
        if node.nodeKind == "CALLABLE" and node.qualifiedName == "example.GeneratedApi.create"
    )
    entrypoint = next(
        claim
        for claim in graph.claims
        if claim.claimKind == "ENTRYPOINT_HINT" and claim.nodeLocalId == interface_create.localId
    )
    assert entrypoint.metadata["entrypointKind"] == "HTTP"
    assert entrypoint.metadata["origin"] == "STATIC"
    assert entrypoint.metadata["route"] == "/items"
    assert entrypoint.metadata["httpMethod"] == "POST"
    assert entrypoint.metadata["entrypointExecutionKind"] == "CONTRACT_DECLARATION"
    controller_create = next(
        node
        for node in graph.nodes
        if node.nodeKind == "CALLABLE" and node.qualifiedName == "example.GeneratedController.create"
    )
    assert not [
        claim
        for claim in graph.claims
        if claim.claimKind == "ENTRYPOINT_HINT" and claim.nodeLocalId == controller_create.localId
    ]


def test_static_graph_materializer_extracts_request_mapping_request_method_on_interface():
    text = """package example;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

interface SiteApi {
  @RequestMapping(
      method = RequestMethod.POST,
      value = "/api/v1/sites",
      produces = { "application/json" },
      consumes = { "application/json" }
  )
  ResponseEntity<ResponseDto> createSite(RequestDto request);
}

class RequestDto {}
class ResponseDto {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    interface_create = next(
        node
        for node in graph.nodes
        if node.nodeKind == "CALLABLE" and node.qualifiedName == "example.SiteApi.createSite"
    )
    entrypoint = next(
        claim
        for claim in graph.claims
        if claim.claimKind == "ENTRYPOINT_HINT" and claim.nodeLocalId == interface_create.localId
    )

    assert entrypoint.metadata["entrypointKind"] == "HTTP"
    assert entrypoint.metadata["httpMethod"] == "POST"
    assert entrypoint.metadata["route"] == "/api/v1/sites"
    assert entrypoint.metadata["entrypointExecutionKind"] == "CONTRACT_DECLARATION"
    assert entrypoint.metadata["interfaceMethod"] == "example.SiteApi.createSite"


def test_static_graph_materializer_does_not_guess_external_api_contract_from_package_path():
    text = """package example;

import com.app_afesox.stsssox.api_first.api.SiteApi;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
class SiteController implements SiteApi {
  @Override
  public ResponseEntity<CreateSiteResponseDTO> createSite(CreateSiteRequestDTO request) {
    return null;
  }
}

class CreateSiteRequestDTO {}
class CreateSiteResponseDTO {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    controller_create = next(
        node
        for node in graph.nodes
        if node.nodeKind == "CALLABLE" and node.qualifiedName == "example.SiteController.createSite"
    )
    assert not [
        claim
        for claim in graph.claims
        if claim.claimKind == "ENTRYPOINT_HINT" and claim.nodeLocalId == controller_create.localId
    ]


def test_java_parser_extracts_static_callsites_with_conservative_resolution():
    result = parse_sample()
    calls = {(call.method_name, call.receiver_text): call for call in result.callsites}

    assert calls[("helper", None)].resolution_status == "RESOLVED"
    assert calls[("toApi", "mapper")].receiver_type_hint == "TicketMapper"
    assert calls[("toApi", "mapper")].resolution_status == "RESOLVED"
    assert calls[("toApi", "mapper")].field_receiver_local_id == next(item.local_id for item in result.fields if item.name == "mapper")
    assert calls[("ok", "ResponseEntity")].resolution_status == "EXTERNAL_TARGET"
    assert calls[("requireNonNull", None)].resolution_status == "EXTERNAL_TARGET"
    assert next(call for call in result.callsites if call.method_name == "helper").line_start == 18


def test_static_graph_materializer_creates_static_nodes_edges_evidence_and_entrypoint():
    graph = StaticGraphMaterializer().to_graph(parse_sample())

    node_kinds = {node.nodeKind for node in graph.nodes}
    edge_types = {edge.edgeType for edge in graph.edges}
    assert {"FILE", "TYPE", "CALLABLE", "FIELD"} <= node_kinds
    assert "EXTERNAL" not in node_kinds
    assert {"DECLARES", "IMPORTS", "REFERENCES", "CALLS"} <= edge_types
    assert all(node.metadata["factOrigin"] == "STATIC" for node in graph.nodes)
    assert all(edge.evidence for edge in graph.edges)
    import_edges = [edge for edge in graph.edges if edge.edgeType == "IMPORTS"]
    assert import_edges
    assert all(edge.toNodeLocalId is None for edge in import_edges)
    assert all(edge.resolutionStatus == "EXTERNAL_TARGET" for edge in import_edges)
    assert all(edge.unresolvedTarget for edge in import_edges)
    call_edges = [edge for edge in graph.edges if edge.edgeType == "CALLS"]
    assert any(edge.resolutionStatus == "RESOLVED" for edge in call_edges)
    assert all("resolutionStatus" not in edge.metadata for edge in graph.edges)
    entrypoint = next(claim for claim in graph.claims if claim.claimKind == "ENTRYPOINT_HINT")
    assert entrypoint.nodeLocalId.endswith("|CALLABLE|example.TicketController|get|get(String)")
    assert entrypoint.metadata["httpMethod"] == "GET"
    assert entrypoint.metadata["route"] == "/tickets/{id}"
    assert entrypoint.metadata["entrypointExecutionKind"] == "EXECUTABLE"
    assert all("flowScore" not in edge.metadata for edge in call_edges)
    assert next(edge for edge in call_edges if edge.metadata["methodName"] == "toApi").metadata["callKind"] == "FIELD_RECEIVER"
    uses_field_edges = [edge for edge in graph.edges if edge.edgeType == "USES_FIELD"]
    mapper = next(node for node in graph.nodes if node.nodeKind == "FIELD" and node.name == "mapper")
    get = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "get")
    mapper_usage = next(edge for edge in uses_field_edges if edge.fromNodeLocalId == get.localId and edge.toNodeLocalId == mapper.localId)
    assert mapper_usage.resolutionStatus == "RESOLVED"
    assert mapper_usage.metadata["factOrigin"] == "STATIC"
    assert [item.lineStart for item in mapper_usage.evidence] == [19]
    provided_boundary = next(boundary for boundary in graph.boundaries if boundary.role == "PROVIDED")
    descriptor_values = {descriptor.path: descriptor.value for descriptor in provided_boundary.descriptors}
    assert descriptor_values["http.method"] == "GET"
    assert descriptor_values["http.route"] == "/tickets/{id}"
    assert descriptor_values["annotation.name"] == "GetMapping"


def test_static_graph_materializer_does_not_create_trusted_behavior_claims_from_names():
    text = """package example;

class ArbitraryRepository {
  void save(Object value) {}
  Object find(Object value) { return value; }
  void delete(Object value) {}
}

class KafkaTemplate {
  void send(String topic, Object value) {}
}

class WebClient {
  void get() {}
}

class Config {
  String getProperty(String name) { return name; }
}

class Handler {
  private final ArbitraryRepository repository;
  private final KafkaTemplate kafkaTemplate;
  private final WebClient webClient;
  private final Config config;

  Handler(ArbitraryRepository repository, KafkaTemplate kafkaTemplate, WebClient webClient, Config config) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
    this.webClient = webClient;
    this.config = config;
  }

  void handle(Object value) {
    repository.save(value);
    repository.find(value);
    repository.delete(value);
    kafkaTemplate.send("topic", value);
    webClient.get();
    config.getProperty("feature");
  }
}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))

    forbidden = {"DATA_ACCESS_HINT", "SIDE_EFFECT", "EXTERNAL_BOUNDARY_HINT", "CONFIG_REFERENCE"}
    assert not [claim for claim in graph.claims if claim.claimKind in forbidden]
    assert any(edge.edgeType == "CALLS" and edge.metadata.get("methodName") == "save" for edge in graph.edges)
    assert any(edge.edgeType == "CALLS" and edge.metadata.get("methodName") == "send" for edge in graph.edges)


def test_static_graph_materializer_materializes_uses_field_for_this_receiver_and_dedupes_lines():
    text = """package example;

class Controller {
  private final Dep service;
  private final Dep unused;
  private final Dep localShadow;

  void handle(Dep service) {
    Dep localShadow = new Dep();
    this.service.call();
    service.call();
    localShadow.call();
    verify(this.service).call();
  }
}

class Dep { void call() {} }
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))

    fields = {node.name: node for node in graph.nodes if node.nodeKind == "FIELD"}
    handle = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "handle")
    uses_field_edges = [edge for edge in graph.edges if edge.edgeType == "USES_FIELD" and edge.fromNodeLocalId == handle.localId]

    assert [(edge.toNodeLocalId, [item.lineStart for item in edge.evidence]) for edge in uses_field_edges] == [
        (fields["service"].localId, [10, 13])
    ]
    assert all(edge.toNodeLocalId != fields["unused"].localId for edge in uses_field_edges)
    assert all(edge.toNodeLocalId != fields["localShadow"].localId for edge in uses_field_edges)


def test_static_graph_materializer_materializes_uses_field_for_implicit_receiver_when_not_shadowed():
    text = """package example;

class Controller {
  private final Dep service;

  void handle() {
    service.call();
  }
}

class Dep { void call() {} }
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))

    field = next(node for node in graph.nodes if node.nodeKind == "FIELD" and node.name == "service")
    handle = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "handle")
    edge = next(edge for edge in graph.edges if edge.edgeType == "USES_FIELD")

    assert edge.fromNodeLocalId == handle.localId
    assert edge.toNodeLocalId == field.localId
    assert [item.lineStart for item in edge.evidence] == [7]


def test_java_method_reference_explicit_field_receiver_resolves_and_uses_field():
    text = """package example;

import java.util.Optional;
import java.util.UUID;

class Repo {
  private final Repository repository;
  private final SiteInfraMapper siteInfraMapper;

  Optional<Site> findById(UUID id) {
    return repository.findById(id).map(this.siteInfraMapper::asSite);
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

interface Repository {
  Optional<SiteEntity> findById(UUID id);
}

class Site {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    method_ref_line = next(index for index, line in enumerate(text.splitlines(), 1) if "this.siteInfraMapper::asSite" in line)
    find_by_id = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "findById")
    target = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "asSite")
    field = next(node for node in graph.nodes if node.nodeKind == "FIELD" and node.name == "siteInfraMapper")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == find_by_id.localId and edge.toNodeLocalId == target.localId)
    uses_field = next(edge for edge in graph.edges if edge.edgeType == "USES_FIELD" and edge.fromNodeLocalId == find_by_id.localId and edge.toNodeLocalId == field.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.argument_count is None
    assert call_edge.unresolvedTarget is None
    assert call_edge.metadata["callKind"] == "FIELD_METHOD_REFERENCE"
    assert call_edge.metadata["receiverText"] == "this.siteInfraMapper"
    assert call_edge.metadata["receiverTypeHint"] == "SiteInfraMapper"
    assert call_edge.metadata["targetTypeText"] == "SiteInfraMapper"
    assert call_edge.metadata["resolutionReason"] == "FIELD_TYPE_HINT"
    assert "unresolvedReason" not in call_edge.metadata
    assert [item.lineStart for item in call_edge.evidence] == [method_ref_line]
    assert [item.lineStart for item in uses_field.evidence] == [method_ref_line]
    assert all("SiteInfraMapperImpl" not in (node.name or "") for node in graph.nodes)


def test_java_method_reference_bare_field_receiver_resolves_when_not_shadowed():
    text = """package example;

import java.util.Optional;
import java.util.UUID;

class Repo {
  private final Repository repository;
  private final SiteInfraMapper siteInfraMapper;

  Optional<Site> findById(UUID id) {
    return repository.findById(id).map(siteInfraMapper::asSite);
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

interface Repository {
  Optional<SiteEntity> findById(UUID id);
}

class Site {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    method_ref_line = next(index for index, line in enumerate(text.splitlines(), 1) if "siteInfraMapper::asSite" in line)
    find_by_id = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "findById")
    target = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "asSite")
    field = next(node for node in graph.nodes if node.nodeKind == "FIELD" and node.name == "siteInfraMapper")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == find_by_id.localId and edge.toNodeLocalId == target.localId)
    uses_field = next(edge for edge in graph.edges if edge.edgeType == "USES_FIELD" and edge.fromNodeLocalId == find_by_id.localId and edge.toNodeLocalId == field.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.metadata["callKind"] == "FIELD_METHOD_REFERENCE"
    assert call_edge.metadata["receiverText"] == "siteInfraMapper"
    assert [item.lineStart for item in call_edge.evidence] == [method_ref_line]
    assert [item.lineStart for item in uses_field.evidence] == [method_ref_line]


def test_java_method_reference_parameter_receiver_resolves_without_field_usage():
    text = """package example;

import java.util.List;
import java.util.stream.Stream;

class Service {
  List<Site> map(Stream<SiteEntity> stream, SiteInfraMapper mapper) {
    return stream.map(mapper::asSite).toList();
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

class Site {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    caller = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "map")
    target = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "asSite")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == caller.localId and edge.toNodeLocalId == target.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.metadata["callKind"] == "PARAMETER_METHOD_REFERENCE"
    assert call_edge.metadata["receiverText"] == "mapper"
    assert call_edge.metadata["receiverTypeHint"] == "SiteInfraMapper"
    assert not [edge for edge in graph.edges if edge.edgeType == "USES_FIELD" and edge.fromNodeLocalId == caller.localId]


def test_java_method_reference_bare_receiver_uses_local_variable_before_shadowed_field():
    text = """package example;

import java.util.List;
import java.util.stream.Stream;

class Service {
  private final OtherMapper siteInfraMapper;

  List<Site> map(Stream<SiteEntity> stream) {
    SiteInfraMapper siteInfraMapper = createMapper();
    return stream.map(siteInfraMapper::asSite).toList();
  }

  SiteInfraMapper createMapper() {
    return null;
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

interface OtherMapper {
  Other asSite(SiteEntity entity);
}

class Site {}
class Other {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    caller = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "map")
    target = next(
        node
        for node in graph.nodes
        if node.nodeKind == "CALLABLE" and node.name == "asSite" and "SiteInfraMapper" in (node.localId or "")
    )
    field = next(node for node in graph.nodes if node.nodeKind == "FIELD" and node.name == "siteInfraMapper")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == caller.localId and edge.toNodeLocalId == target.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.metadata["callKind"] == "LOCAL_VARIABLE_METHOD_REFERENCE"
    assert call_edge.metadata["receiverTypeHint"] == "SiteInfraMapper"
    assert not [
        edge
        for edge in graph.edges
        if edge.edgeType == "USES_FIELD" and edge.fromNodeLocalId == caller.localId and edge.toNodeLocalId == field.localId
    ]


def test_java_method_reference_overloaded_target_is_not_guessed_without_arity():
    text = """package example;

import java.util.List;
import java.util.stream.Stream;

class Service {
  List<Site> map(Stream<SiteEntity> stream, SiteInfraMapper mapper) {
    return stream.map(mapper::asSite).toList();
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
  Site asSite(OtherEntity entity);
}

class Site {}
class SiteEntity {}
class OtherEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    caller = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "map")
    method_ref_call = next(
        edge
        for edge in graph.edges
        if edge.edgeType == "CALLS"
        and edge.fromNodeLocalId == caller.localId
        and edge.metadata.get("methodName") == "asSite"
        and edge.metadata.get("callKind") == "PARAMETER_METHOD_REFERENCE"
    )

    assert method_ref_call.toNodeLocalId is None
    assert method_ref_call.resolutionStatus == "MULTIPLE_CANDIDATES"
    assert method_ref_call.unresolvedTarget["name"] == "asSite"
    assert method_ref_call.metadata["unresolvedReason"] == "MULTIPLE_METHODS_MATCH"


def test_java_method_reference_static_local_type_resolves():
    text = """package example;

import java.util.List;
import java.util.stream.Stream;

class Service {
  List<Site> map(Stream<SiteEntity> stream) {
    return stream.map(SomeMapper::asSite).toList();
  }
}

class SomeMapper {
  static Site asSite(SiteEntity entity) {
    return new Site();
  }
}

class Site {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    caller = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "map")
    target = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "asSite")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == caller.localId and edge.toNodeLocalId == target.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.metadata["callKind"] == "STATIC_METHOD_REFERENCE"
    assert call_edge.metadata["receiverText"] == "SomeMapper"
    assert call_edge.metadata["targetTypeText"] == "SomeMapper"
    assert call_edge.metadata["resolutionReason"] == "QUALIFIED_NAME_MATCH"


def test_java_direct_field_receiver_call_still_resolves_after_method_reference_support():
    text = """package example;

class Repo {
  private final SiteInfraMapper siteInfraMapper;

  Site convert(SiteEntity entity) {
    return this.siteInfraMapper.asSite(entity);
  }
}

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

class Site {}
class SiteEntity {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    caller = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "convert")
    target = next(node for node in graph.nodes if node.nodeKind == "CALLABLE" and node.name == "asSite")
    call_edge = next(edge for edge in graph.edges if edge.edgeType == "CALLS" and edge.fromNodeLocalId == caller.localId and edge.toNodeLocalId == target.localId)

    assert call_edge.resolutionStatus == "RESOLVED"
    assert call_edge.argument_count == 1
    assert call_edge.metadata["callKind"] == "FIELD_RECEIVER"
    assert call_edge.metadata["resolutionReason"] == "FIELD_TYPE_HINT"


def test_java_parser_keeps_receiver_type_hints_out_of_edge_metadata():
    text = """package example;

class Controller {
  void handle(TicketMapper mapper) {
    TicketDto dto = mapper.toApi(new Ticket());
    dto.validate();
  }
}

class TicketMapper {
  TicketDto toApi(Ticket ticket) { return new TicketDto(); }
}

class TicketDto {
  void validate() {}
}

class Ticket {}
"""
    result = JavaParserAdapter().parse(text, metadata(text))
    graph = StaticGraphMaterializer().to_graph(result)
    call_edges = [edge for edge in graph.edges if edge.edgeType == "CALLS"]
    to_api = next(edge for edge in call_edges if edge.metadata["methodName"] == "toApi")
    validate = next(edge for edge in call_edges if edge.metadata["methodName"] == "validate")

    assert to_api.metadata["callKind"] == "PARAMETER_RECEIVER"
    assert to_api.resolutionStatus == "RESOLVED"
    assert to_api.metadata["resolutionReason"] == "PARAMETER_TYPE_HINT"
    assert to_api.argument_count == 1
    assert "receiverTypeHint" not in to_api.metadata
    assert validate.metadata["callKind"] == "LOCAL_VARIABLE_RECEIVER"
    assert validate.resolutionStatus == "RESOLVED"
    assert validate.metadata["resolutionReason"] == "LOCAL_VARIABLE_TYPE_HINT"
    assert validate.argument_count == 0
    assert "receiverTypeHint" not in validate.metadata


def test_static_graph_materializer_creates_entrypoint_hints_for_lifecycle_config_and_tests():
    text = """package example;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import jakarta.annotation.PostConstruct;

class Config {
  @Bean
  TicketMapper mapper() { return new TicketMapper(); }

  @PostConstruct
  void init() {}
}

class ConfigTest {
  @Test
  void verifiesConfig() {}
}

class TicketMapper {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    entrypoints = [claim for claim in graph.claims if claim.claimKind == "ENTRYPOINT_HINT"]
    kinds = {claim.metadata["entrypointKind"] for claim in entrypoints}

    assert {"CONFIGURATION_BEAN", "LIFECYCLE", "TEST"} <= kinds


def test_static_graph_materializer_uses_kafka_listener_entrypoint_contract():
    text = """package example;

import org.springframework.kafka.annotation.KafkaListener;

class PaymentListener {
  @KafkaListener(topics = "payments.created")
  void handle(String payload) {}
}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))
    entrypoint = next(claim for claim in graph.claims if claim.claimKind == "ENTRYPOINT_HINT")

    assert entrypoint.metadata["entrypointKind"] == "KAFKA"
    assert entrypoint.metadata["topic"] == "payments.created"
    boundary = next(item for item in graph.boundaries if item.role == "PROVIDED")
    descriptors = {descriptor.path: descriptor.value for descriptor in boundary.descriptors}
    assert descriptors["messaging.topic"] == "payments.created"
    assert descriptors["annotation.name"] == "KafkaListener"


def test_static_graph_materializer_emits_generic_boundaries_for_required_calls_schedules_and_health():
    text = """package example;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;

class JobRunner {
  private final RemoteGateway gateway;

  @Scheduled(cron = "0 0 * * * *")
  void runJob() {
    gateway.create("payload");
  }
}

class HealthController {
  @GetMapping("/health")
  String health() {
    return "ok";
  }
}

interface RemoteGateway {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))

    provided = [boundary for boundary in graph.boundaries if boundary.role == "PROVIDED"]
    required = [boundary for boundary in graph.boundaries if boundary.role == "REQUIRED"]
    provided_descriptors = [{descriptor.path: descriptor.value for descriptor in boundary.descriptors} for boundary in provided]
    required_descriptors = [{descriptor.path: descriptor.value for descriptor in boundary.descriptors} for boundary in required]

    scheduled = next(item for item in provided_descriptors if item.get("provided.kind") == "SCHEDULED")
    assert scheduled["schedule.expression"] == '(cron = "0 0 * * * *")'
    assert "http.route" not in scheduled
    assert any(item.get("http.method") == "GET" and item.get("http.route") == "/health" for item in provided_descriptors)
    assert any(item.get("call.method") == "create" and item.get("call.receiverTypeHint") == "RemoteGateway" for item in required_descriptors)
    assert all(boundary.evidence for boundary in graph.boundaries)
    assert all(descriptor.evidence for boundary in graph.boundaries for descriptor in boundary.descriptors)


def test_static_graph_materializer_emits_event_consumer_and_producer_boundaries_from_source_evidence():
    text = """package example;

import org.springframework.kafka.annotation.KafkaListener;

class Events {
  private final EventGateway gateway;

  @KafkaListener(topics = "payments.created")
  void consume(String payload) {}

  void publish(String payload) {
    gateway.send(payload);
  }
}

interface EventGateway {}
"""
    graph = StaticGraphMaterializer().to_graph(JavaParserAdapter().parse(text, metadata(text)))

    consumer = next(boundary for boundary in graph.boundaries if boundary.role == "PROVIDED")
    producer = next(boundary for boundary in graph.boundaries if boundary.role == "REQUIRED")
    consumer_descriptors = {descriptor.path: descriptor.value for descriptor in consumer.descriptors}
    producer_descriptors = {descriptor.path: descriptor.value for descriptor in producer.descriptors}

    assert consumer_descriptors["messaging.topic"] == "payments.created"
    assert producer_descriptors["call.method"] == "send"
    assert producer_descriptors["call.receiverTypeHint"] == "EventGateway"


def test_anchor_validator_accepts_callable_claim_only_when_evidence_overlaps_method():
    static_graph = StaticGraphMaterializer().to_graph(parse_sample())
    get_node = next(node for node in static_graph.nodes if node.nodeKind == "CALLABLE" and node.name == "get")
    enrichment = GraphAnalysisResult.parse_obj(
        {
            "nodes": [],
            "edges": [],
            "claims": [
                {
                    "localId": "claim-good",
                    "nodeLocalId": get_node.localId,
                    "claimKind": "RESPONSIBILITY",
                    "summary": "Loads a ticket and returns its API response.",
                    "evidence": [{"lineStart": 18, "lineEnd": 19, "text": "helper and mapper calls", "metadata": {}}],
                    "confidence": 0.86,
                    "metadata": {},
                },
                {
                    "localId": "claim-bad",
                    "nodeLocalId": get_node.localId,
                    "claimKind": "RESPONSIBILITY",
                    "summary": "Defines the controller class.",
                    "evidence": [{"lineStart": 9, "lineEnd": 10, "text": "class annotations", "metadata": {}}],
                    "confidence": 0.9,
                    "metadata": {},
                },
            ],
            "diagnostics": [],
        }
    )

    merged = AnchorAwareGraphValidator().merge(static_graph, enrichment, metadata().line_count)
    claims = {claim.localId: claim for claim in merged.claims}

    assert claims["claim-good"].metadata["status"] == "TRUSTED"
    assert claims["claim-bad"].metadata["status"] == "CANDIDATE"
    assert claims["claim-bad"].metadata["rejectionReason"] == "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD"
    assert any(item["code"] == "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD" for item in merged.diagnostics)


def test_anchor_validator_rejects_unanchored_llm_structure_and_claim_targets():
    static_graph = StaticGraphMaterializer().to_graph(parse_sample())
    enrichment = GraphAnalysisResult.parse_obj(
        {
            "nodes": [
                {
                    "localId": "invented",
                    "nodeKind": "CALLABLE",
                    "name": "invented",
                    "lineStart": 1,
                    "lineEnd": 1,
                    "confidence": 0.9,
                    "metadata": {},
                }
            ],
            "edges": [
                {
                    "localId": "edge-missing-source",
                    "fromNodeLocalId": "missing-anchor",
                    "toNodeLocalId": None,
                    "edgeType": "REFERENCES",
                    "resolutionStatus": "UNRESOLVED",
                    "confidence": 0.9,
                    "evidence": [{"lineStart": 1, "lineEnd": 1, "text": "package example", "metadata": {}}],
                    "unresolvedTarget": {"name": "MissingThing", "kindHint": "TYPE"},
                    "metadata": {},
                }
            ],
            "claims": [
                {
                    "localId": "claim-missing",
                    "nodeLocalId": "missing-anchor",
                    "claimKind": "RESPONSIBILITY",
                    "summary": "Invented behavior.",
                    "evidence": [{"lineStart": 1, "lineEnd": 1, "text": "package example", "metadata": {}}],
                    "confidence": 0.9,
                    "metadata": {},
                }
            ],
            "diagnostics": [],
        }
    )

    merged = AnchorAwareGraphValidator().merge(static_graph, enrichment, metadata().line_count)

    assert all(node.localId != "invented" for node in merged.nodes)
    assert all(claim.localId != "claim-missing" for claim in merged.claims)
    assert all(edge.localId != "edge-missing-source" for edge in merged.edges)
    assert {item["code"] for item in merged.diagnostics} >= {
        "LLM_UNANCHORED_STRUCTURE_CANDIDATE",
        "LLM_CLAIM_TARGET_NOT_FOUND",
        "LLM_EDGE_TOPOLOGY_REJECTED",
    }
