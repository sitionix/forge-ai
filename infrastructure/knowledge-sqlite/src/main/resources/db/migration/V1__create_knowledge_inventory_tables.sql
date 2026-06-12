CREATE TABLE IF NOT EXISTS inventory_builds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    status TEXT NOT NULL,
    source_count INTEGER NOT NULL,
    file_count INTEGER NOT NULL,
    skipped_count INTEGER NOT NULL,
    skipped_reasons_json TEXT,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS sources (
    source_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    group_name TEXT,
    path TEXT NOT NULL,
    root_exists INTEGER NOT NULL,
    tags_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id TEXT NOT NULL,
    source_path TEXT NOT NULL,
    absolute_path TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    extension TEXT,
    size_bytes INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    last_modified TEXT NOT NULL,
    indexed_at TEXT NOT NULL
);
