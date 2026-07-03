from __future__ import annotations

from pathlib import Path

from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.graph_validation import GraphRepairPromptBuilder, enum_validation_error

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"
FORBIDDEN_VALUES = {"UNKNOWN", "DIAGNOSTIC", "RELATED_TO"}


def test_repair_prompt_uses_yaml_allowed_values_and_diagnostics():
    policy = load_analysis_policy(POLICY_PATH)
    provider = GraphContractProvider(policy=policy)
    contract = provider.resolve("settings.yaml", "service:\n  url: http://example\n")
    payload = {
        "sourceId": "svc",
        "inventoryFileId": 1,
        "relativePath": "settings.yaml",
        "contentHash": "hash",
        "lineCount": 2,
        "analysisPolicy": contract_payload(contract),
    }
    error = enum_validation_error(
        path="$.claims[0].claimKind",
        message="claimKind is not allowed by the effective analysis graph profiles.",
        actual="PURPOSE",
        allowed_values=contract.allowed_claim_kinds,
    )

    prompt = GraphRepairPromptBuilder(provider).build(payload, "{bad", [error], attempt=2, max_attempts=3)

    assert "CONFIG_REFERENCE" in prompt
    assert "CONFIGURES" in prompt
    assert "PURPOSE" in prompt
    assert "$.claims[0].claimKind" in prompt
    assert '"allowedValues": ["RESPONSIBILITY", "CONFIG_REFERENCE"]' in prompt
    assert all(value not in prompt for value in FORBIDDEN_VALUES)


def test_active_prompt_parser_and_repair_files_do_not_define_old_allowed_lists():
    files = [
        REPO_ROOT / "config" / "knowledge" / "analysis-prompt.md",
        REPO_ROOT / "config" / "knowledge" / "prompts" / "code-graph-enrichment.md",
        REPO_ROOT / "config" / "knowledge" / "prompts" / "text-graph-enrichment.md",
        REPO_ROOT / "config" / "knowledge" / "prompts" / "document-graph-enrichment.md",
        REPO_ROOT / "services" / "forge-knowledge" / "src" / "knowledge_service" / "graph_response_parser.py",
        REPO_ROOT / "services" / "forge-knowledge" / "src" / "knowledge_service" / "graph_validation.py",
        REPO_ROOT / "services" / "forge-knowledge" / "src" / "knowledge_service" / "analysis_client.py",
    ]

    for path in files:
        text = path.read_text(encoding="utf-8")
        assert "ALLOWED_GRAPH" not in text
        assert "ALLOWED_RESOLUTION_STATUSES" not in text
        assert all(value not in text for value in FORBIDDEN_VALUES)
