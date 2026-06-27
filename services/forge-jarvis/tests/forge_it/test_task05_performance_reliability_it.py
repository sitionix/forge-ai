from __future__ import annotations

import asyncio
import re
import time
from pathlib import Path

import pytest
from support import AsgiResponse
from support import AsgiTestClient as TestClient
from support import FakeKnowledgeClient, FakeModelClient, build_test_app, knowledge_bundle, write_runtime_config

from jarvis_agent.action_executor import ActionExecutionError, ActionExecutor
from jarvis_agent.action_registry import ActionRegistry
from jarvis_agent.intent_schema import Intent

pytestmark = pytest.mark.forge_it

WARMUP_SAMPLES = 2
MEASURED_SAMPLES = 8


def test_perf_jar_01_status_and_actions_are_bounded_and_do_not_call_ollama(tmp_path):
    app, *_rest, model, _knowledge = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        samples = [
            *_sample_route(lambda: client.get("/api/v1/jarvis/status")),
            *_sample_route(lambda: client.get("/api/v1/jarvis/actions")),
        ]

    assert model.health_calls == 0
    _assert_samples(samples, max_p50_ms=15, max_p95_ms=40, max_p99_ms=60, max_bytes=4096)
    for sample in samples:
        timing = _parse_server_timing(sample.response.headers["server-timing"])
        assert timing["knowledge"] == 0
        assert timing["ollama"] == 0
        assert timing["action"] == 0


def test_perf_jar_02_chat_records_dependency_timings_and_redacts_context(tmp_path):
    knowledge = DelayedKnowledgeClient(
        bundle=knowledge_bundle(
            context=[
                {
                    "sourceId": "forge-ai",
                    "displayName": "Forge AI",
                    "relativePath": "src/JarvisGateway.java",
                    "lineStart": 1,
                    "lineEnd": 3,
                    "content": "secret source content must not return",
                    "matchType": "content",
                    "reason": "Matched JarvisGateway",
                    "score": 1.0,
                    "metadata": {"tags": ["java"]},
                }
            ]
        )
    )
    model = DelayedModelClient(generate_response="Answer without source content")
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model, knowledge=knowledge)

    with TestClient(app) as client:
        samples = _sample_route(lambda: client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"}))

    for sample in samples:
        body = sample.response.json()
        timing = _parse_server_timing(sample.response.headers["server-timing"])
        assert timing["knowledge"] > 0
        assert timing["ollama"] > 0
        assert body["usedContext"]
        assert all(item.get("content") is None for item in body["usedContext"])
        text = sample.response.body.decode("utf-8")
        assert "secret source content" not in text
        assert "http://localhost" not in text
        assert "Traceback" not in text
    _assert_samples(samples, max_p50_ms=80, max_p95_ms=140, max_p99_ms=180, max_bytes=8192)


def test_perf_jar_03_command_executor_caps_output_and_times_out_process_group(tmp_path):
    output_executor = ActionExecutor(
        _registry(tmp_path, ["bash", "-lc", "yes jarvis | head -n 1000"]),
        timeout_seconds=5,
        max_output_bytes=64,
        max_concurrency=2,
    )
    result = output_executor.execute(Intent(action="safe", target="run", arguments={}), user_text="cap output")
    assert result.output is not None
    assert "[output truncated]" in result.output
    assert len(result.output.encode("utf-8")) <= 96

    timeout_executor = ActionExecutor(
        _registry(tmp_path, ["bash", "-lc", "sleep 5"]),
        timeout_seconds=1,
        max_output_bytes=1024,
    )
    with pytest.raises(ActionExecutionError, match="timed out"):
        timeout_executor.execute(Intent(action="safe", target="run", arguments={}), user_text="timeout")


def test_perf_jar_03b_command_route_uses_async_subprocess_without_blocking_status(tmp_path):
    config_file = write_runtime_config(tmp_path)
    config_file.parent.joinpath("jarvis", "allowed-actions.yaml").write_text(
        """
actions:
  safe:
    description: Slow deterministic command
    targets:
      run:
        command: ["bash", "-lc", "sleep 0.2; printf done"]
""".lstrip(),
        encoding="utf-8",
    )
    intent_model = FakeModelClient(intent_response='{"action":"safe","target":"run","arguments":{}}')
    app, _, app_config, *_ = build_test_app(config_file, model=intent_model)
    registry = ActionRegistry.from_yaml(app_config.allowed_actions_path)
    app, *_ = build_test_app(config_file, model=intent_model, executor=ActionExecutor(registry, timeout_seconds=2, max_concurrency=1))

    async def exercise_routes():
        client = TestClient(app)
        command_task = asyncio.create_task(client._request("POST", "/api/v1/jarvis/command", {"text": "run safe command"}, {}))
        await asyncio.sleep(0.03)
        before = time.perf_counter()
        status = await client._request("GET", "/api/v1/jarvis/status", None, {})
        status_ms = (time.perf_counter() - before) * 1000
        command = await command_task
        return command, status, status_ms

    command, status, status_ms = asyncio.run(exercise_routes())
    command_text = command.body.decode("utf-8")
    assert status.status_code == 200
    assert status_ms < 120
    assert command.status_code == 200
    assert "done" in command_text
    assert '["bash"' not in command_text
    assert "sleep 0.2" not in command_text
    assert _parse_server_timing(command.headers["server-timing"])["action"] > 0


class DelayedKnowledgeClient(FakeKnowledgeClient):
    async def context(self, query: str, max_context_chars: int):
        await asyncio.sleep(0.002)
        return await super().context(query, max_context_chars)


class DelayedModelClient(FakeModelClient):
    async def generate_text(self, prompt: str) -> str:
        await asyncio.sleep(0.002)
        return await super().generate_text(prompt)


class _RouteSample:
    def __init__(self, response: AsgiResponse) -> None:
        assert response.status_code < 500
        self.response = response
        timing = _parse_server_timing(response.headers["server-timing"])
        self.route_ms = timing["route"]
        self.bytes = int(response.headers["x-response-bytes"])
        assert response.headers["x-route-key"]
        assert response.headers["x-correlation-id"]


def _sample_route(factory, measured: int = MEASURED_SAMPLES) -> list[_RouteSample]:
    for _ in range(WARMUP_SAMPLES):
        response = factory()
        assert response.status_code < 500
    return [_RouteSample(factory()) for _ in range(measured)]


def _assert_samples(samples: list[_RouteSample], *, max_p50_ms: float, max_p95_ms: float, max_p99_ms: float, max_bytes: int) -> None:
    durations = [sample.route_ms for sample in samples]
    assert _percentile(durations, 50) <= max_p50_ms
    assert _percentile(durations, 95) <= max_p95_ms
    assert _percentile(durations, 99) <= max_p99_ms
    assert max(sample.bytes for sample in samples) <= max_bytes


def _parse_server_timing(value: str) -> dict[str, float]:
    result: dict[str, float] = {}
    for name in ("route", "knowledge", "ollama", "action"):
        match = re.search(rf"(?:^|,\s*){name};dur=([0-9]+(?:\.[0-9]+)?)", value)
        assert match is not None, value
        result[name] = float(match.group(1))
    return result


def _percentile(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round((percentile / 100) * (len(ordered) - 1))))
    return ordered[index]


def _registry(tmp_path: Path, command: list[str]) -> ActionRegistry:
    yaml_path = tmp_path / "allowed-actions.yaml"
    yaml_path.write_text(
        """
actions:
  safe:
    description: Safe test command
    targets:
      run:
        command: COMMAND
""".replace("COMMAND", repr(command)),
        encoding="utf-8",
    )
    return ActionRegistry.from_yaml(yaml_path)
