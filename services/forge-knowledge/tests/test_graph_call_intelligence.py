import inspect

import pytest

from knowledge_service.graph_call_intelligence import classify_call_metadata


def test_classify_call_metadata_contract_has_no_relative_path_input():
    signature = inspect.signature(classify_call_metadata)

    assert "relative_path" not in signature.parameters


def test_explicit_test_flow_domain_may_produce_internal_test_category():
    metadata = classify_call_metadata(
        {"flowDomain": "TEST", "resolutionStatus": "RESOLVED", "methodName": "helper"},
        None,
        "RESOLVED",
    )

    assert metadata["callTargetCategory"] == "INTERNAL_TEST"


def test_src_test_path_metadata_alone_cannot_produce_internal_test_category():
    metadata = classify_call_metadata(
        {
            "flowDomain": "CODE",
            "resolutionStatus": "UNRESOLVED",
            "methodName": "helper",
            "relativePath": "src/test/java/FooTest.java",
        },
        None,
        "UNRESOLVED",
    )

    assert metadata["callTargetCategory"] == "UNKNOWN"


@pytest.mark.parametrize(
    ("flow_domain", "expected_category"),
    [
        ("WORKFLOW", "WORKFLOW"),
        ("BUILD", "BUILD"),
        ("CONFIG", "INTERNAL_CONFIG"),
        ("DATA", "INTERNAL_CONFIG"),
    ],
)
def test_non_code_categories_come_from_explicit_flow_domain(flow_domain, expected_category):
    metadata = classify_call_metadata(
        {"flowDomain": flow_domain, "resolutionStatus": "UNRESOLVED", "methodName": "helper"},
        None,
        "UNRESOLVED",
    )

    assert metadata["callTargetCategory"] == expected_category


@pytest.mark.parametrize(
    "path_like_value",
    [
        ".github/workflows/build.yml",
        "pom.xml",
        "build.gradle",
        "config/service.yaml",
    ],
)
def test_path_like_metadata_cannot_produce_workflow_build_or_config_category(path_like_value):
    metadata = classify_call_metadata(
        {
            "flowDomain": "CODE",
            "resolutionStatus": "UNRESOLVED",
            "methodName": "helper",
            "relativePath": path_like_value,
        },
        None,
        "UNRESOLVED",
    )

    assert metadata["callTargetCategory"] == "UNKNOWN"
