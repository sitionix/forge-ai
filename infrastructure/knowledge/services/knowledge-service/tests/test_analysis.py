import json
import os
import threading
import time
from pathlib import Path

import pytest
from pydantic import ValidationError

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

from knowledge_service import main
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_response_parser import AiAnalysisResponseParser
from knowledge_service.analysis_schema import AnalysisBuildRequest, AnalysisResult
from knowledge_service.analysis_service import AnalysisJobRunner
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.source_config import load_source_config


class StubAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, result=None, fail=False, block_event=None, bad_response_attempts=0, outcomes=None):
        self.result = result or valid_result()
        self.fail = fail
        self.block_event = block_event
        self.bad_response_attempts = bad_response_attempts
        self.outcomes = list(outcomes or [])
        self.repair_prompts = []
        self.calls = 0

    def analyze(self, payload, line_count, repair_prompt=None):
        self.calls += 1
        if repair_prompt:
            self.repair_prompts.append(repair_prompt)
        if self.block_event is not None:
            self.block_event.wait(2)
        if self.outcomes:
            outcome = self.outcomes.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome
        if self.calls <= self.bad_response_attempts:
            raise KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=self.calls)
        if self.fail:
            raise RuntimeError("model failed")
        self.result.validate_lines(line_count)
        return self.result


def valid_result():
    return AnalysisResult.parse_obj({
        "fileSummary": "Defines an object handler and helper.",
        "symbols": [
            {
                "localId": "s1",
                "name": "ObjectHandler",
                "kind": "CLASS",
                "roles": [{"role": "HTTP_HANDLER", "confidence": 0.9, "evidence": ["Has a method annotated with an HTTP mapping."]}],
                "lineStart": 1,
                "lineEnd": 5,
                "metadata": {"language": "java"},
            },
            {
                "localId": "s2",
                "name": "create",
                "kind": "METHOD",
                "roles": [{"role": "ENTRYPOINT", "confidence": 0.8, "evidence": ["Method is externally callable in this file."]}],
                "lineStart": 3,
                "lineEnd": 4,
                "metadata": {},
            },
        ],
        "relations": [
            {
                "fromLocalId": "s1",
                "toLocalId": "s2",
                "relation": "CONTAINS",
                "confidence": 1.0,
                "evidence": ["The method is declared inside the class."],
                "lineStart": 3,
                "lineEnd": 3,
                "metadata": {},
            }
        ],
        "diagnostics": [],
    })


def build_inventory(tmp_path, content=None, include_large=False, extra_files=None):
    workspace = tmp_path / "workspace"
    service = workspace / "edge-gateway"
    (service / "src/main/java/example").mkdir(parents=True)
    (service / "src/main/java/example/ObjectHandler.java").write_text(
        content or "public class ObjectHandler {\n  @PostMapping\n  public void create() {\n  }\n}\n",
        encoding="utf-8",
    )
    if include_large:
        (service / "src/main/java/example/LargeFile.java").write_text("x" * 200, encoding="utf-8")
    for relative_path, file_content in (extra_files or {}).items():
        path = service / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(file_content, encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  edge-gateway:
    label: Edge Gateway
    path: edge-gateway
    group: edge
    tags: [java]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, service


def create_source_config(tmp_path, content=None):
    workspace = tmp_path / "workspace"
    service = workspace / "edge-gateway"
    (service / "src/main/java/example").mkdir(parents=True)
    (service / "src/main/java/example/ObjectHandler.java").write_text(
        content or "public class ObjectHandler {\n  public void create() {}\n}\n",
        encoding="utf-8",
    )
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  edge-gateway:
    label: Edge Gateway
    path: edge-gateway
    group: edge
    tags: [java]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    return config


def app_config(tmp_path, max_file_chars=30000):
    return AppConfig(
        tmp_path,
        "127.0.0.1",
        7081,
        tmp_path / "knowledge-sources.yaml",
        tmp_path / "knowledge.sqlite",
        analysis_max_file_chars=max_file_chars,
    )


def app_config_with_retries(tmp_path, retry_attempts):
    return AppConfig(
        tmp_path,
        "127.0.0.1",
        7081,
        tmp_path / "knowledge-sources.yaml",
        tmp_path / "knowledge.sqlite",
        analysis_max_attempts_per_file=retry_attempts,
    )


def wait_job(store, job_id):
    analysis_store = AnalysisStore(store.db_path)
    for _ in range(80):
        job = analysis_store.job(job_id)
        if job["status"] in {"COMPLETED", "FAILED", "STOPPED"}:
            return job
        time.sleep(0.025)
    raise AssertionError("job did not finish")


def test_ai_output_schema_validates_valid_response():
    result = valid_result()

    assert result.symbols[0].roles[0].role == "HTTP_HANDLER"


def test_invalid_json_rejected():
    with pytest.raises(ValidationError):
        AnalysisResult.parse_raw("{bad")


def test_ai_response_parser_parses_valid_json():
    raw = json.dumps(valid_result().dict())

    result = AiAnalysisResponseParser().parse(raw, 5)

    assert isinstance(result, AnalysisResult)
    assert result.symbols[0].name == "ObjectHandler"


def test_ai_response_parser_extracts_markdown_wrapped_json():
    raw = "```json\n" + json.dumps(valid_result().dict()) + "\n```"

    result = AiAnalysisResponseParser().parse(raw, 5)

    assert isinstance(result, AnalysisResult)
    assert result.relations[0].relation == "CONTAINS"


def test_ai_response_parser_rejects_natural_language():
    result = AiAnalysisResponseParser().parse("I cannot analyze this file.", 5)

    assert result.code == "ANALYSIS_AI_INVALID_JSON"


def test_ai_response_parser_rejects_empty_response():
    result = AiAnalysisResponseParser().parse("   ", 5)

    assert result.code == "ANALYSIS_AI_EMPTY_RESPONSE"


def test_ai_response_parser_rejects_schema_invalid_json():
    result = AiAnalysisResponseParser().parse('{"symbols":[],"relations":[]}', 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert len(result.message) < 560


def test_ai_response_parser_rejects_json_null_as_schema_invalid():
    result = AiAnalysisResponseParser().parse("null", 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_response_parser_truncates_raw_preview():
    result = AiAnalysisResponseParser().parse("x" * 5000, 5)

    assert result.code == "ANALYSIS_AI_INVALID_JSON"
    assert len(result.raw_preview) == 4000


def test_ai_response_parser_rejects_non_critical_schema_noise():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["role"] = "EXCEPTION"
    payload["symbols"][0]["lineEnd"] = 50
    payload["relations"][0]["relation"] = "HAS_FIELD"
    payload["relations"][0]["lineEnd"] = 50

    result = AiAnalysisResponseParser().parse(json.dumps(payload), 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_response_parser_rejects_relations_with_unknown_symbol_references():
    payload = valid_result().dict()
    payload["relations"][0]["toLocalId"] = "UNKNOWN"

    result = AiAnalysisResponseParser().parse(json.dumps(payload), 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_output_schema_accepts_java_record_kind():
    payload = valid_result().dict()
    payload["symbols"][0]["kind"] = "RECORD"

    result = AnalysisResult.parse_obj(payload)

    assert result.symbols[0].kind == "RECORD"


def test_unknown_role_rejected():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["role"] = "BUSINESS_ROLE"

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_unknown_relation_rejected():
    payload = valid_result().dict()
    payload["relations"][0]["relation"] = "BUSINESS_RELATION"

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_line_range_outside_file_rejected():
    result = valid_result()

    with pytest.raises(ValueError):
        result.validate_lines(2)


def test_evidence_required_for_non_unknown_role():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["evidence"] = []

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_non_localhost_ollama_base_url_rejected(tmp_path):
    with pytest.raises(Exception):
        OllamaAnalysisClient("http://example.com:11434", "model", 1, tmp_path / "missing.md")


def test_large_file_skipped(tmp_path):
    store, _, _ = build_inventory(tmp_path, include_large=True)
    runner = AnalysisJobRunner(store, app_config(tmp_path, max_file_chars=150))

    job = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    final = wait_job(store, job["jobId"])
    files = AnalysisStore(store.db_path).files(None, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert files["total"] == 1


def test_unchanged_file_skipped_by_hash(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    analyzer = StubAnalyzer()
    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    second = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert second["skippedUnchangedFileCount"] == 1
    assert analyzer.calls == 1


def test_analysis_max_files_uses_stable_inventory_order(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {\n  }\n\n}\n",
    })
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1, force=True), StubAnalyzer())["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert files["total"] == 1
    assert files["files"][0]["relativePath"] == "src/main/java/example/AaaHandler.java"


def test_changed_file_reanalyzed_and_previous_analysis_removed(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    first_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0)
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler {\n  public void updated() {}\n}\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(AnalysisResult.parse_obj({
        "fileSummary": "Updated.",
        "symbols": [{"localId": "s3", "name": "updated", "kind": "METHOD", "roles": [{"role": "UTILITY", "confidence": 0.5, "evidence": ["Method exists."]}], "lineStart": 2, "lineEnd": 2, "metadata": {}}],
        "relations": [],
        "diagnostics": [],
    })))["jobId"])
    second_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0)

    assert first_symbols["total"] == 2
    assert second_symbols["total"] == 1
    assert second_symbols["symbols"][0]["name"] == "updated"


def test_freshness_up_to_date_after_completed_scan_with_unchanged_files(tmp_path):
    store, config, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert AnalysisStore(store.db_path).status()["scannedFileCount"] == 1
    assert freshness["status"] == "UP_TO_DATE"
    assert freshness["newFiles"] == 0
    assert freshness["modifiedFiles"] == 0
    assert freshness["deletedFiles"] == 0


def test_freshness_outdated_when_scanned_file_modified(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void updated() {} }\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["modifiedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_scanned_file_deleted(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").unlink()

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["deletedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_new_eligible_file_added(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text("public class SecondHandler {}\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["newFiles"] == 1
    assert freshness["affectedScannedFiles"] == 0


def test_analyze_refreshes_inventory_and_restores_freshness(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text("public class SecondHandler {}\n", encoding="utf-8")
    assert KnowledgeFreshnessService(load_source_config(config), store).check()["status"] == "OUTDATED"

    InventoryBuilder(load_source_config(config), store).build([], [])
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()
    assert freshness["status"] == "UP_TO_DATE"
    assert AnalysisStore(store.db_path).status()["fileCount"] == 2


def test_background_job_returns_id_and_updates_progress(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    response = runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))
    running = AnalysisStore(store.db_path).job(response["jobId"])
    unblock.set()
    final = wait_job(store, response["jobId"])

    assert response["status"] == "QUEUED"
    assert running["status"] in {"QUEUED", "RUNNING"}
    assert final["processedFileCount"] == 1


def test_stop_analysis_releases_active_slot_and_prevents_old_file_write(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    old_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))["jobId"]
    analysis_store = AnalysisStore(store.db_path)

    for _ in range(80):
        running = analysis_store.job(old_job_id)
        if running["status"] == "RUNNING" and running["currentRelativePath"]:
            break
        time.sleep(0.025)
    else:
        raise AssertionError("job did not start")

    stop = runner.stop(old_job_id)
    assert stop["status"] == "STOP_REQUESTED"
    assert analysis_store.active_job() is None

    new_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"]
    new_final = wait_job(store, new_job_id)
    unblock.set()
    old_final = wait_job(store, old_job_id)
    files = analysis_store.files(None, "ANALYZED", None, 10, 0)

    assert new_final["status"] == "COMPLETED"
    assert old_final["status"] == "STOPPED"
    assert files["total"] == 1


def test_one_active_job_rule_enforced(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))

    with pytest.raises(Exception):
        runner.start(AnalysisBuildRequest(), StubAnalyzer())
    unblock.set()


def test_failed_ai_file_does_not_crash_whole_service(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 1
    assert final["failedFileCount"] == 1
    service = AnalysisStore(store.db_path).service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]
    assert service["analysis"]["processedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["diagnostics"][0]["code"] == "ANALYSIS_FILE_FAILED"
    assert service["diagnostics"][0]["count"] == 1


def test_service_status_uses_active_job_counts_while_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "skippedUnchangedFileCount": 0,
        "currentSourceId": "edge-gateway",
        "currentRelativePath": "src/main/java/example/ObjectHandler.java",
    })

    service = analysis_store.service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["processedFileCount"] == 0
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["analysis"]["currentRelativePath"] == "src/main/java/example/ObjectHandler.java"


def test_bad_ai_json_is_retried_before_file_fails(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(bad_response_attempts=1)
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["failedFileCount"] == 0
    assert analyzer.calls == 2
    assert analyzer.repair_prompts
    assert files["total"] == 1
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_RETRY_SUCCEEDED"}


def test_max_attempts_exceeded_marks_file_failed_with_preview(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad again", attempt=2),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["failedFileCount"] == 1
    assert files["files"][0]["attemptCount"] == 2
    assert files["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["files"][0]["lastRawResponsePreview"] == "{bad again"
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_timeout_marks_file_failed_and_continues(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out", attempt=1),
        valid_result(),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 3))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    failed = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 1
    assert analyzer.calls == 2
    assert {file["analysisStatus"] for file in files["files"]} == {"ANALYZED", "FAILED"}
    assert failed["files"][0]["attemptCount"] == 1
    assert failed["files"][0]["lastErrorCode"] == "ANALYSIS_AI_TIMEOUT"


def test_transport_error_marks_file_failed_and_continues_after_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error", attempt=1),
        valid_result(),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 1


def test_last_progress_at_updates(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert final["lastProgressAt"]


def test_interrupted_running_jobs_are_marked_failed_on_startup_cleanup(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
    })

    analysis_store.mark_interrupted_jobs()
    job = analysis_store.job("job-running")

    assert job["status"] == "FAILED"
    assert job["currentSourceId"] is None
    assert job["diagnostics"][0]["code"] == "ANALYSIS_JOB_INTERRUPTED"


def test_symbols_and_relations_endpoints_return_roles_and_evidence(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    symbols = analysis_store.symbols(None, "HTTP_HANDLER", None, None, None, 10, 0)
    relations = analysis_store.relations(None, "CONTAINS", None, None, 10, 0)

    assert symbols["symbols"][0]["roles"][0]["evidence"]
    assert relations["relations"][0]["evidence"]


def test_no_source_file_mutation(tmp_path):
    store, _, service = build_inventory(tmp_path)
    source = service / "src/main/java/example/ObjectHandler.java"
    before = source.read_text(encoding="utf-8")
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert source.read_text(encoding="utf-8") == before


def test_no_production_domain_hardcoded_synonyms():
    src = Path("infrastructure/knowledge/services/knowledge-service/src/knowledge_service")
    banned = ["_AUTH_QUERY", "site creation", "авторизація"]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in src.rglob("*.py"))

    assert all(term not in combined for term in banned)


class FakeRunner:
    def start(self, request):
        return {"jobId": "job-1", "status": "QUEUED", "message": "Knowledge analysis job queued"}

    def stop(self, job_id):
        return {"jobId": job_id, "status": "STOP_REQUESTED", "message": "Knowledge analysis stop requested"}


def configure_api(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())
    return store


def post_json(path, payload):
    import asyncio
    return asyncio.run(asgi_json("POST", path, payload))


def get_json(path):
    import asyncio
    return asyncio.run(asgi_json("GET", path, None))


async def asgi_json(method, path, payload):
    body = json.dumps(payload or {}).encode("utf-8")
    messages = []
    received = False

    async def receive():
        nonlocal received
        if received:
            return {"type": "http.disconnect"}
        received = True
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        messages.append(message)

    raw_path, _, query = path.partition("?")
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method,
        "path": raw_path,
        "raw_path": raw_path.encode("utf-8"),
        "query_string": query.encode("utf-8"),
        "headers": [(b"content-type", b"application/json"), (b"accept", b"application/json")],
        "client": ("testclient", 50000),
        "server": ("testserver", 80),
        "scheme": "http",
    }
    await main.app(scope, receive, send)
    status = next(message["status"] for message in messages if message["type"] == "http.response.start")
    response_body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
    return {"status": status, "json": json.loads(response_body.decode("utf-8") or "{}")}


def test_analysis_api_build_proxies_to_runner(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/analysis/build", {"sourceIds": ["edge-gateway"], "concurrency": 1})

    assert result["status"] == 200
    assert result["json"]["jobId"] == "job-1"


def test_analysis_api_refreshes_inventory_before_queueing_job(tmp_path, monkeypatch):
    create_source_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())

    result = post_json("/api/v1/knowledge/analysis/build", {"sourceIds": ["edge-gateway"], "concurrency": 1})

    status = store.status()
    rows, _ = store.search_rows(["edge-gateway"], [])
    assert result["status"] == 200
    assert status["status"] == "READY"
    assert status["fileCount"] == 1
    assert len(rows) == 1


def test_analysis_api_job_status_endpoint(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-2",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
    })

    result = get_json("/api/v1/knowledge/analysis/jobs/job-2")

    assert result["json"]["status"] == "RUNNING"


def test_analysis_api_status_files_symbols_relations(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?sourceId=edge-gateway")
    symbols = get_json("/api/v1/knowledge/analysis/symbols?role=HTTP_HANDLER")
    relations = get_json("/api/v1/knowledge/analysis/relations?relation=CONTAINS")

    assert status["json"]["symbolCount"] == 2
    assert files["json"]["total"] == 1
    assert symbols["json"]["symbols"][0]["roles"][0]["role"] == "HTTP_HANDLER"
    assert relations["json"]["relations"][0]["relation"] == "CONTAINS"


def test_analysis_api_stop_job(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/analysis/jobs/job-1/stop", {})

    assert result["status"] == 200
    assert result["json"]["status"] == "STOP_REQUESTED"


def test_status_api_separates_coverage_and_freshness_without_running_ai(tmp_path, monkeypatch):
    store, _, service = build_inventory(tmp_path)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    class FailingRunner:
        def start(self, request):
            raise AssertionError("status must not run AI analysis")

    monkeypatch.setattr(main, "analysis_runner", FailingRunner())

    result = get_json("/api/v1/knowledge/status")

    assert result["status"] == 200
    assert result["json"]["coverage"]["scannedFiles"] == 1
    assert result["json"]["coverage"]["eligibleFiles"] == 1
    assert result["json"]["freshness"]["status"] == "OUTDATED"
    assert result["json"]["freshness"]["modifiedFiles"] == 1


def test_analysis_api_exposes_failed_file_diagnostics_and_progress(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        valid_result(),
    ])
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?status=FAILED")

    assert status["json"]["lastCompletedAt"]
    assert files["json"]["total"] == 1
    assert files["json"]["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["json"]["files"][0]["lastRawResponsePreview"] == "{bad"
    assert {item["code"] for item in files["json"]["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_services_status_returns_inventory_analysis_and_facts_counts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert result["status"] == 200
    assert service["sourceId"] == "edge-gateway"
    assert service["label"] == "Edge Gateway"
    assert service["inventory"]["eligibleFileCount"] == 1
    assert service["analysis"]["inventoryFileCount"] == 1
    assert service["analysis"]["analyzedFileCount"] == 1
    assert service["analysis"]["percent"] == 100.0
    assert service["facts"]["symbolCount"] == 2
    assert service["facts"]["relationCount"] == 1


def test_services_status_uses_current_content_hash_for_analyzed_and_stale(tmp_path, monkeypatch):
    store, config, service_root = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service_root / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert service["analysis"]["inventoryFileCount"] == 1
    assert service["analysis"]["analyzedFileCount"] == 0
    assert service["analysis"]["staleFileCount"] == 1
    assert service["analysis"]["status"] == "OUTDATED"


def test_services_status_reports_failed_files_separately_and_groups_diagnostics(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
    ])
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert service["analysis"]["analyzedFileCount"] == 0
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert {item["code"]: item["count"] for item in service["diagnostics"]}["ANALYSIS_AI_INVALID_JSON"] == 1


def test_services_status_missing_inventory_returns_zero_counts(tmp_path, monkeypatch):
    config = create_source_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert load_source_config(config)
    assert service["inventory"]["eligibleFileCount"] == 0
    assert service["analysis"]["inventoryFileCount"] == 0
    assert service["facts"]["symbolCount"] == 0
    assert service["facts"]["relationCount"] == 0


def test_analysis_store_drops_legacy_fact_tables(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    with store._connect() as conn:
        for table in ("symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"):
            conn.execute(f"CREATE TABLE {table} (id INTEGER)")

    store.init()

    with store._connect() as conn:
        tables = {
            row["name"]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
        }
    assert not {"symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"} & tables
