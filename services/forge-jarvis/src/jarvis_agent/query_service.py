from __future__ import annotations

from typing import Any, Dict, Protocol

from pydantic import ValidationError

from jarvis_agent.knowledge_client import KnowledgeBadResponseError
from jarvis_agent.query_schema import (
    JarvisHumanAnswerResponse,
    JarvisQueryRequest,
)


class KnowledgeQueryGateway(Protocol):
    async def query(self, payload: Dict[str, Any]) -> Dict[str, Any]: ...


class JarvisQueryService:
    def __init__(self, knowledge_gateway: KnowledgeQueryGateway) -> None:
        self.knowledge_gateway = knowledge_gateway

    async def query(self, request: JarvisQueryRequest) -> JarvisHumanAnswerResponse:
        payload = {
            "queryText": request.queryText,
            "intent": request.intent.value,
            "includeTests": request.includeTests,
            "maxFlows": request.maxFlows,
        }
        if request.answerLanguage:
            payload["answerLanguage"] = request.answerLanguage
        bundle = await self.knowledge_gateway.query(payload)
        try:
            return JarvisHumanAnswerResponse.parse_obj(bundle)
        except ValidationError as exc:
            raise KnowledgeBadResponseError("Knowledge returned a malformed query response") from exc
