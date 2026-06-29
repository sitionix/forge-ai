import httpx
import pytest

from knowledge_service.embedding_provider import EmbeddingProviderError, FakeDeterministicEmbeddingProvider, OllamaEmbeddingProvider


def test_fake_embedding_provider_is_deterministic():
    provider = FakeDeterministicEmbeddingProvider(dimension=16)

    first = provider.embed_texts(["Jarvis query flow", "Knowledge query"])[0]
    second = provider.embed_texts(["Jarvis query flow"])[0]

    assert first == second
    assert len(first) == 16
    assert first != provider.embed_texts(["WireMock params"])[0]


def test_ollama_embedding_provider_rejects_non_local_url():
    with pytest.raises(EmbeddingProviderError) as exc:
        OllamaEmbeddingProvider("https://example.com", "embeddinggemma", 1)

    assert exc.value.code == "SEMANTIC_PROVIDER_UNAVAILABLE"


def test_ollama_embedding_provider_parses_valid_embed_response():
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"embeddings": [[1.0, 0.0], [0.0, 1.0]]})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    provider = OllamaEmbeddingProvider("http://127.0.0.1:11434", "embeddinggemma", 1, client=client)

    vectors = provider.embed_texts(["one", "two"])

    assert vectors == [[1.0, 0.0], [0.0, 1.0]]
    assert requests[0].url.path == "/api/embed"


def test_ollama_embedding_provider_rejects_invalid_response_shape():
    client = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"embedding": [1.0, 2.0]})))
    provider = OllamaEmbeddingProvider("http://localhost:11434", "embeddinggemma", 1, client=client)

    with pytest.raises(EmbeddingProviderError) as exc:
        provider.embed_texts(["one"])

    assert exc.value.code == "SEMANTIC_PROVIDER_UNAVAILABLE"
    assert "embeddings" in exc.value.message


def test_ollama_embedding_provider_failure_is_controlled():
    client = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(503, text="down")))
    provider = OllamaEmbeddingProvider("http://localhost:11434", "embeddinggemma", 1, client=client)

    with pytest.raises(EmbeddingProviderError) as exc:
        provider.embed_texts(["one"])

    assert exc.value.code == "SEMANTIC_PROVIDER_UNAVAILABLE"
    assert "HTTP 503" in exc.value.message
