from __future__ import annotations

from typing import List

from jarvis_agent.chat_schema import ChatContextItem


def build_chat_prompt(system_prompt: str, message: str, context_items: List[ChatContextItem]) -> str:
    context_block = "\n\n".join(
        f"[{index}] {item.sourceId}/{item.relativePath} lines {item.lineStart}-{item.lineEnd}\n"
        f"{item.content or ''}"
        for index, item in enumerate(context_items, start=1)
    )
    if not context_block:
        context_block = "(no relevant local Knowledge context was found)"

    return (
        f"{system_prompt.strip()}\n\n"
        f"Knowledge context:\n{context_block}\n\n"
        f"User question:\n{message}\n\n"
        "Plain text answer:"
    )
