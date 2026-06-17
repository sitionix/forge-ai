# Forge AI Knowledge UX

The Operator UI page is `boot/src/main/resources/static/operator/knowledge.html`.

The browser calls Forge AI backend endpoints only. It does not call the local Knowledge service port directly.

The page shows runtime status, catalog state, catalog-derived sources, inventory status, indexed files, and AI analysis status/results.

The Inventory card shows indexed file count, skipped count, and a compact skipped breakdown. Zero-count reasons are hidden. Analyze refreshes inventory automatically before semantic scanning.

Skipped means files or paths were seen during inventory build but were not indexed because they matched exclude rules, did not match include rules, were too large, binary, unsafe, unreadable, symlinked outside the source root, or came from missing configured roots. Skipped items are not already processed files and are not errors by default.

The Retrieval Context UI section was removed. Jarvis chat still has a transitional direct Knowledge `/api/v1/knowledge/context` dependency until semantic retrieval is rebuilt on AI analysis results.

The Structural Facts UI section was removed from the primary Knowledge screen. Semantic symbols and relations are shown through AI analysis service details.

The AI Structural Analysis section calls Forge analysis endpoints only. It displays latest status, active job progress, processed/skipped/failed counts, current source/file, symbol and relation totals, and previews roles/confidence/evidence. It polls job status while queued or running and never calls Ollama directly from the browser.

The Flow Context UI section was removed. Flow-level semantics should be produced or retrieved from AI analysis results, not from manual inventory/facts builders.
