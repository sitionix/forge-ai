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
    assert config.model.default_model == "qwen2.5-coder:7b"
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
