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
    assert "down" not in exc.value.diagnostic()["metadata"]


def test_ollama_embedding_provider_missing_model_404_is_actionable():
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(404, json={"error": 'model "embeddinggemma" not found, try pulling it first'})
        )
    )
    provider = OllamaEmbeddingProvider("http://localhost:11434", "embeddinggemma", 1, client=client)

    with pytest.raises(EmbeddingProviderError) as exc:
        provider.embed_texts(["one"])

    diagnostic = exc.value.diagnostic()
    assert exc.value.code == "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE"
    assert exc.value.message == (
        "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model."
    )
    assert diagnostic["metadata"] == {"statusCode": 404, "model": "embeddinggemma"}
    assert "Traceback" not in diagnostic["message"]


def test_ollama_embedding_provider_endpoint_404_is_distinct_from_missing_model():
    client = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(404, text="404 page not found")))
    provider = OllamaEmbeddingProvider("http://localhost:11434", "embeddinggemma", 1, client=client)

    with pytest.raises(EmbeddingProviderError) as exc:
        provider.embed_texts(["one"])

    assert exc.value.code == "SEMANTIC_PROVIDER_UNAVAILABLE"
    assert "endpoint returned HTTP 404" in exc.value.message
    assert exc.value.diagnostic()["metadata"] == {"statusCode": 404, "endpoint": "/api/embed"}
