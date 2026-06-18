from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from knowledge_service.file_metadata import FileMetadata
from knowledge_service.file_scanner import scan_source
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import SourceConfig


class KnowledgeFreshnessService:
    def __init__(self, config: SourceConfig, store: InventoryStore):
        self.config = config
        self.store = store

    def check(self, source_ids: Optional[List[str]] = None, groups: Optional[List[str]] = None) -> Dict[str, Any]:
        selected_sources = self._selected_sources(source_ids or [], groups or [])
        selected_source_ids = [source.sourceId for source in selected_sources]
        snapshot_files = self.store.snapshot_files(selected_source_ids if selected_source_ids else None)
        current_files = self._current_files(selected_sources)
        result = self._compare(snapshot_files, current_files)
        by_source: Dict[str, Dict[str, Any]] = {}
        for source_id in sorted(set(selected_source_ids) | {row["sourceId"] for row in snapshot_files} | {file.sourceId for file in current_files}):
            source_snapshot = [row for row in snapshot_files if row["sourceId"] == source_id]
            source_current = [file for file in current_files if file.sourceId == source_id]
            by_source[source_id] = self._compare(source_snapshot, source_current)
        result["sources"] = by_source
        return result

    def check_snapshot(self, snapshot_files: List[Dict[str, Any]], source_ids: Optional[List[str]] = None) -> Dict[str, Any]:
        selected_sources = self._selected_sources(source_ids or [], [])
        current_files = self._current_files(selected_sources)
        return self._compare(snapshot_files, current_files)

    def _selected_sources(self, source_ids: List[str], groups: List[str]):
        result = ServiceYamlCatalogProvider(self.config).load()
        return [source for source in result.sources if (not source_ids or source.sourceId in source_ids) and (not groups or source.group in groups)]

    def _current_files(self, sources) -> List[FileMetadata]:
        files: List[FileMetadata] = []
        for source in sources:
            source_files, _ = scan_source(source, self.config.indexing, self.config.file_classifier)
            files.extend(source_files)
        return files

    def _compare(self, snapshot_files: List[Dict[str, Any]], current_files: List[FileMetadata]) -> Dict[str, Any]:
        snapshot_by_key = {(row["sourceId"], row["relativePath"]): row for row in snapshot_files}
        current_by_key = {(file.sourceId, file.relativePath): file for file in current_files}
        snapshot_keys = set(snapshot_by_key)
        current_keys = set(current_by_key)
        new_keys = current_keys - snapshot_keys
        deleted_keys = snapshot_keys - current_keys
        modified_keys = {key for key in snapshot_keys & current_keys if snapshot_by_key[key]["contentHash"] != current_by_key[key].contentHash}
        affected_ids = [snapshot_by_key[key]["id"] for key in deleted_keys | modified_keys if snapshot_by_key[key].get("id") is not None]
        affected_scanned = len(self.store.analyzed_file_ids(affected_ids))
        status = "UP_TO_DATE" if not new_keys and not modified_keys and not deleted_keys else "OUTDATED"
        return {
            "status": status,
            "checkedAt": datetime.now(timezone.utc).isoformat(),
            "newFiles": len(new_keys),
            "modifiedFiles": len(modified_keys),
            "deletedFiles": len(deleted_keys),
            "affectedScannedFiles": affected_scanned,
        }
