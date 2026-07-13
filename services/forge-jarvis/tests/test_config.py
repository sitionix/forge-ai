import pytest

from jarvis_agent.config import load_app_config
from support import write_runtime_config


def test_root_forge_config_is_supported(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)

    config = load_app_config(
        config_file=config_file,
        environ={
            "FORGE_AI_HOME": str(tmp_path),
            "FORGE_CONFIG_DIR": str(tmp_path / "config"),
            "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
            "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
        },
    )

    assert config.config_dir == tmp_path / "config"
    assert config.model.default_model == "qwen2.5-coder:14b"
    assert config.model.context_tokens == 32768
    assert config.knowledge.request_timeout_seconds == 120
    assert config.knowledge.flow_explanation_request_timeout_seconds == 185
    assert config.allowed_actions_path == tmp_path / "config" / "jarvis" / "allowed-actions.yaml"


def test_jarvis_config_dir_files_are_supported(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    override_dir = tmp_path / "override-jarvis"
    override_dir.mkdir()
    (override_dir / "system-prompt.md").write_text("override system", encoding="utf-8")
    (override_dir / "allowed-actions.yaml").write_text("actions: {}\n", encoding="utf-8")

    config = load_app_config(
        config_file=config_file,
        environ={
            "FORGE_AI_HOME": str(tmp_path),
            "FORGE_CONFIG_DIR": str(tmp_path / "config"),
            "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
            "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            "JARVIS_CONFIG_DIR": str(override_dir),
        },
    )

    assert config.system_prompt == "override system"
    assert config.allowed_actions_path == override_dir / "allowed-actions.yaml"


def test_root_generative_config_changes_jarvis_model_and_context(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    original = config_file.read_text(encoding="utf-8")
    updated = original.replace("model: qwen2.5-coder:14b", "model: root-shared-model")
    updated = updated.replace("context-tokens: 32768", "context-tokens: 4096")
    config_file.write_text(updated, encoding="utf-8")

    config = load_app_config(
        config_file=config_file,
        environ={
            "FORGE_AI_HOME": str(tmp_path),
            "FORGE_CONFIG_DIR": str(tmp_path / "config"),
            "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
            "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
        },
    )

    assert config.model.default_model == "root-shared-model"
    assert config.model.context_tokens == 4096


def test_flow_explanation_transport_timeout_tracks_shared_deadline_and_grace(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    original = config_file.read_text(encoding="utf-8")
    updated = original.replace("request-timeout-seconds: 180", "request-timeout-seconds: 0.1", 1)
    updated = updated.replace("flow-explanation-transport-grace-seconds: 5", "flow-explanation-transport-grace-seconds: 0.05")
    config_file.write_text(updated, encoding="utf-8")

    config = load_app_config(
        config_file=config_file,
        environ={
            "FORGE_AI_HOME": str(tmp_path),
            "FORGE_CONFIG_DIR": str(tmp_path / "config"),
            "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
            "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
        },
    )

    assert config.knowledge.request_timeout_seconds == 120
    assert config.knowledge.flow_explanation_request_timeout_seconds == pytest.approx(0.15)


def test_flow_explanation_transport_timeout_must_exceed_deadline(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    original = config_file.read_text(encoding="utf-8")
    config_file.write_text(
        original.replace("flow-explanation-transport-grace-seconds: 5", "flow-explanation-transport-grace-seconds: 0"),
        encoding="utf-8",
    )

    with pytest.raises(ValueError):
        load_app_config(
            config_file=config_file,
            environ={
                "FORGE_AI_HOME": str(tmp_path),
                "FORGE_CONFIG_DIR": str(tmp_path / "config"),
                "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
                "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            },
        )


@pytest.mark.parametrize("provider", ["openai", "custom", ""])
def test_jarvis_generative_provider_must_be_ollama(tmp_path, provider) -> None:
    config_file = write_runtime_config(tmp_path)
    original = config_file.read_text(encoding="utf-8")
    config_file.write_text(original.replace("provider: ollama", f"provider: {provider}"), encoding="utf-8")

    with pytest.raises(ValueError, match="generative provider must be ollama"):
        load_app_config(
            config_file=config_file,
            environ={
                "FORGE_AI_HOME": str(tmp_path),
                "FORGE_CONFIG_DIR": str(tmp_path / "config"),
                "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
                "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            },
        )


def test_jarvis_generative_context_below_minimum_fails_loading(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    original = config_file.read_text(encoding="utf-8")
    config_file.write_text(original.replace("context-tokens: 32768", "context-tokens: 512"), encoding="utf-8")

    with pytest.raises(ValueError):
        load_app_config(
            config_file=config_file,
            environ={
                "FORGE_AI_HOME": str(tmp_path),
                "FORGE_CONFIG_DIR": str(tmp_path / "config"),
                "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
                "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            },
        )
