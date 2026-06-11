import pytest

from knowledge_service.errors import KnowledgeError
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import load_source_config


def write_config(tmp_path, catalog_text, selection=""):
    workspace = tmp_path / "workspace"
    workspace.mkdir(exist_ok=True)
    (workspace / "service-a").mkdir(exist_ok=True)
    catalog = tmp_path / "services.yaml"
    catalog.write_text(catalog_text, encoding="utf-8")
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"catalog:\n  path: \"{catalog}\"\n  workspace_root: \"{workspace}\"\nselection:\n{selection}",
        encoding="utf-8",
    )
    return load_source_config(config)


def test_missing_services_root_rejected(tmp_path):
    config = write_config(tmp_path, "not_services: {}\n")
    with pytest.raises(KnowledgeError) as exc:
        ServiceYamlCatalogProvider(config).load()
    assert exc.value.code == "SERVICE_CATALOG_INVALID"


def test_service_with_missing_path_is_diagnostic(tmp_path):
    config = write_config(tmp_path, "services:\n  svc:\n    label: Service\n")
    result = ServiceYamlCatalogProvider(config).load()
    assert result.sources == []
    assert result.diagnostics[0].code == "MISSING_PATH"


def test_service_path_resolves_under_workspace_and_missing_root_reports_false(tmp_path):
    config = write_config(tmp_path, "services:\n  svc:\n    label: Service\n    path: missing-service\n    tags: [java]\n")
    result = ServiceYamlCatalogProvider(config).load()
    source = result.sources[0]
    assert source.absoluteRoot.as_posix().endswith("/workspace/missing-service")
    assert source.rootExists is False


def test_selection_include_groups_include_services_and_exclude_services(tmp_path):
    catalog = """
services:
  a:
    label: A
    path: service-a
    group: backend
  b:
    label: B
    path: service-b
    group: frontend
"""
    config = write_config(tmp_path, catalog, "  include_groups: [backend]\n")
    assert [s.sourceId for s in ServiceYamlCatalogProvider(config).load().sources] == ["a"]

    config = write_config(tmp_path, catalog, "  include_services: [b]\n")
    assert [s.sourceId for s in ServiceYamlCatalogProvider(config).load().sources] == ["b"]

    config = write_config(tmp_path, catalog, "  exclude_services: [a]\n")
    assert [s.sourceId for s in ServiceYamlCatalogProvider(config).load().sources] == ["b"]
