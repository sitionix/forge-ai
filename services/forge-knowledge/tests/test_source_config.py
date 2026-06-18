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
    assert loaded.file_classifier.classify("pom.xml").flow_domain == "BUILD"
    assert loaded.file_classifier.classify("src/main/java/example/App.java").language == "java"


def test_config_relative_paths_are_supported(tmp_path):
    config_dir = tmp_path / "config" / "knowledge"
    config_dir.mkdir(parents=True)
    workspace = tmp_path / "config" / "workspace"
    workspace.mkdir()
    catalog = tmp_path / "config" / "services.yaml"
    catalog.write_text("services: {}\n", encoding="utf-8")
    config = config_dir / "knowledge-sources.yaml"
    config.write_text("catalog:\n  path: \"../services.yaml\"\n  workspace_root: \"../workspace\"\n", encoding="utf-8")

    loaded = load_source_config(config)

    assert loaded.catalog.path == catalog
    assert loaded.catalog.workspace_root == workspace


def test_env_paths_are_supported(tmp_path, monkeypatch):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services: {}\n", encoding="utf-8")
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        "catalog:\n  path: \"${KNOWLEDGE_TEST_CATALOG}\"\n  workspace_root: \"${KNOWLEDGE_TEST_WORKSPACE}\"\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("KNOWLEDGE_TEST_CATALOG", str(catalog))
    monkeypatch.setenv("KNOWLEDGE_TEST_WORKSPACE", str(workspace))

    loaded = load_source_config(config)

    assert loaded.catalog.path == catalog
    assert loaded.catalog.workspace_root == workspace


def test_root_relative_paths_are_supported(tmp_path, monkeypatch):
    forge_home = tmp_path / "forge-ai"
    forge_home.mkdir()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    catalog = forge_home / "services.yaml"
    catalog.write_text("services: {}\n", encoding="utf-8")
    config_dir = tmp_path / "outside-config"
    config_dir.mkdir()
    config = config_dir / "knowledge-sources.yaml"
    config.write_text(f"catalog:\n  path: \"services.yaml\"\n  workspace_root: \"{workspace}\"\n", encoding="utf-8")
    monkeypatch.setenv("FORGE_AI_HOME", str(forge_home))

    loaded = load_source_config(config)

    assert loaded.catalog.path == catalog


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
