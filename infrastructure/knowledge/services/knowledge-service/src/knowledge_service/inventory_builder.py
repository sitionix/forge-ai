from __future__ import annotations

from datetime import datetime, timezone
from typing import List

from knowledge_service.file_metadata import FileMetadata
from knowledge_service.file_scanner import scan_source
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.skipped_reasons import SkippedBreakdown, SkippedReason
from knowledge_service.source_catalog import SourceMetadata
from knowledge_service.source_config import SourceConfig


class InventoryBuilder:
    def __init__(self, config: SourceConfig, store: InventoryStore):
        self.config = config
        self.store = store

    def build(self, source_ids: List[str], groups: List[str]) -> dict:
        started_at = datetime.now(timezone.utc).isoformat()
        result = ServiceYamlCatalogProvider(self.config).load()
        selected_candidates = [
            source for source in result.sources
            if (not source_ids or source.sourceId in source_ids)
            and (not groups or source.group in groups)
        ]
        selected = [source for source in selected_candidates if source.rootExists]
        files: list[FileMetadata] = []
        skipped = SkippedBreakdown()
        for source in selected_candidates:
            if not source.rootExists:
                skipped.increment(SkippedReason.MISSING_SOURCE_ROOT)
        for source in selected:
            source_files, source_skipped = scan_source(source, self.config.indexing)
            files.extend(source_files)
            skipped.merge(source_skipped)
        completed_at = datetime.now(timezone.utc).isoformat()
        return self.store.replace_inventory(selected, files, skipped, started_at, completed_at)
