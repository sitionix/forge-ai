from pathlib import Path

import pytest

from knowledge_service.errors import KnowledgeError
from knowledge_service.source_config import load_source_config


def test_missing_local_config_returns_none(tmp_path):
    assert load_source_config(tmp_path / "missing.yaml") is None


def test_valid_local_config_loads_catalog(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services: {}\n", encoding="utf-8")
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(f"catalog:\n  path: \"{catalog}\"\n  workspace_root: \"{workspace}\"\n", encoding="utf-8")

    loaded = load_source_config(config)

    assert loaded.catalog.path == catalog
    assert loaded.catalog.workspace_root == workspace


def test_invalid_catalog_path_rejected(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(f"catalog:\n  path: \"{tmp_path / 'missing.yaml'}\"\n  workspace_root: \"{workspace}\"\n", encoding="utf-8")

    with pytest.raises(KnowledgeError) as exc:
        load_source_config(config)

    assert exc.value.code == "SERVICE_CATALOG_NOT_FOUND"


def test_invalid_workspace_root_rejected(tmp_path):
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services: {}\n", encoding="utf-8")
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(f"catalog:\n  path: \"{catalog}\"\n  workspace_root: \"{tmp_path / 'missing'}\"\n", encoding="utf-8")

    with pytest.raises(KnowledgeError) as exc:
        load_source_config(config)

    assert exc.value.code == "KNOWLEDGE_CONFIG_INVALID"
