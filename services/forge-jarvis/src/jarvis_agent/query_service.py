from __future__ import annotations

from typing import Any, Dict, Protocol

from jarvis_agent.query_schema import JarvisQueryRequest, JarvisQueryResponse


class KnowledgeQueryGateway(Protocol):
    async def query(self, payload: Dict[str, Any]) -> Dict[str, Any]: ...


class JarvisQueryService:
    def __init__(self, knowledge_gateway: KnowledgeQueryGateway) -> None:
        self.knowledge_gateway = knowledge_gateway

    async def query(self, request: JarvisQueryRequest) -> JarvisQueryResponse:
        payload = {
            "query": request.query,
            "intent": request.intent,
            "maxAnchors": request.maxAnchors,
            "depth": request.depth,
        }
        bundle = await self.knowledge_gateway.query(payload)
        return JarvisQueryResponse.parse_obj(bundle)
