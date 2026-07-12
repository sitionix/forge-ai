import asyncio

from jarvis_agent.ollama_client import OllamaClient


class FakeOllamaResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self):
        return {"response": '{"action":"noop"}'}


class RecordingAsyncClient:
    def __init__(self) -> None:
        self.posts = []

    async def post(self, url, json):
        self.posts.append({"url": url, "json": json})
        return FakeOllamaResponse()


def test_jarvis_generate_payloads_include_shared_num_ctx() -> None:
    client = OllamaClient(
        base_url="http://localhost:11434",
        model="qwen2.5-coder:14b",
        context_tokens=32768,
        timeout_seconds=120,
    )
    asyncio.run(client.aclose())
    recorder = RecordingAsyncClient()
    client._client = recorder  # type: ignore[assignment]

    async def exercise() -> None:
        await client.classify_intent("system", "user", [])
        await client.generate_text("prompt")

    asyncio.run(exercise())

    assert [post["json"]["options"]["num_ctx"] for post in recorder.posts] == [32768, 32768]
    assert [post["json"]["model"] for post in recorder.posts] == ["qwen2.5-coder:14b", "qwen2.5-coder:14b"]
