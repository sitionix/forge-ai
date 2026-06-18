from jarvis_agent.config import load_app_config


def test_forge_config_dir_jarvis_config_is_supported(tmp_path, monkeypatch) -> None:
    config_dir = tmp_path / "config" / "jarvis"
    config_dir.mkdir(parents=True)
    (config_dir / "model.yaml").write_text(
        "default_model: test-model\n"
        "ollama_base_url: http://127.0.0.1:11434\n"
        "knowledge:\n"
        "  base_url: http://127.0.0.1:7081\n",
        encoding="utf-8",
    )
    (config_dir / "system-prompt.md").write_text("system", encoding="utf-8")
    (config_dir / "chat-prompt.md").write_text("chat", encoding="utf-8")
    (config_dir / "allowed-actions.yaml").write_text("actions: {}\n", encoding="utf-8")

    monkeypatch.delenv("JARVIS_CONFIG_DIR", raising=False)
    monkeypatch.setenv("FORGE_CONFIG_DIR", str(tmp_path / "config"))

    config = load_app_config()

    assert config.config_dir == config_dir
    assert config.model.default_model == "test-model"
    assert config.allowed_actions_path == config_dir / "allowed-actions.yaml"
