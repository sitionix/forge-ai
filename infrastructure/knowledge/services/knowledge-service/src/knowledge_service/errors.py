from __future__ import annotations


class KnowledgeError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class ConfigMissingError(KnowledgeError):
    def __init__(self):
        super().__init__("KNOWLEDGE_CONFIG_MISSING", "No local knowledge-sources.yaml configured")
