from knowledge_service.source_validator import validate_service_entry


def test_invalid_service_id_is_diagnostic():
    diagnostics = validate_service_entry("bad/id", {"label": "Bad", "path": "bad"})
    assert diagnostics[0].code == "INVALID_SERVICE_ID"


def test_absolute_service_path_rejected():
    diagnostics = validate_service_entry("svc", {"label": "Service", "path": "/tmp/service"})
    assert diagnostics[0].code == "ABSOLUTE_SERVICE_PATH"
